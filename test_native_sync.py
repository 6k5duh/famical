import uuid
import os, tempfile, importlib, unittest
import test_app

class NativeSyncTest(unittest.TestCase):
    client = test_app.CalendarTest.client
    event = test_app.CalendarTest.event
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory()
        os.environ['DATA_DIR']=cls.tmp.name
        import app
        cls.m=importlib.reload(app)
        cls.app=cls.m.app
        cls.app.config['TESTING']=True
        with cls.m.db() as connection:
            for i in range(4):
                connection.execute('INSERT INTO users(username,name,password,admin) VALUES(?,?,?,?)',
                    (f'user{i}',f'가족{i}',cls.m.generate_password_hash('strong-password-123'),int(i==0)))

    def wire(self, event=None, **kw):
        return dict(operation=str(uuid.uuid4()), creation_key=str(uuid.uuid4()), action='save',
                    event=event or self.event(), **kw)
    def test_native_retry_conflict_private_and_snapshot(self):
        client, headers = self.client(1)
        other, oh = self.client(2)
        payload = self.wire(self.event(title='오프라인 교육', category='work', visibility='private'))
        result = client.post('/api/native-sync', json=payload, headers=headers)
        self.assertEqual(result.status_code, 200)
        event = result.json['event']
        self.assertIs(type(event['annual']), bool)
        self.assertEqual(client.post('/api/native-sync', json=payload, headers=headers).json['event']['id'],event['id'])
        rows = client.get('/api/native-sync').json
        self.assertEqual(sum(e['id']==event['id'] for e in rows['events']),1)
        self.assertNotIn(event['id'], [e['id'] for e in other.get('/api/native-sync').json['events']])
        self.assertTrue(client.get('/api/native-sync?version='+rows['version']).json['unchanged'])
        self.assertNotIn('password', rows['user'])
        changed = self.event(title='웹 수정', category='work', visibility='private')
        self.assertEqual(client.put('/api/events/'+str(event['id']),json=changed,headers=headers).status_code,200)
        write = self.wire(self.event(title='기기 수정'), id=event['id'], revision=event['revision'])
        conflict = client.post('/api/native-sync',json=write,headers=headers).json
        self.assertTrue(conflict['conflict'])
        self.assertEqual(conflict['current']['title'], '웹 수정')
        write['revision'] = conflict['current']['revision']
        updated = client.post('/api/native-sync',json=write,headers=headers).json['event']
        self.assertEqual(updated['title'], '기기 수정')
        denied = other.post('/api/native-sync',json=self.wire(id=updated['id'],revision=updated['revision']),headers=oh)
        self.assertEqual(denied.status_code,403)
        deletion = dict(operation=str(uuid.uuid4()), action='delete', id=updated['id'],revision=updated['revision'])
        self.assertIsNone(client.post('/api/native-sync',json=deletion,headers=headers).json['event'])
        self.assertTrue(client.post('/api/native-sync',json=deletion,headers=headers).json['ok'])
        self.assertTrue(client.post('/api/native-sync',json=self.wire(id=updated['id'],revision=updated['revision']),headers=headers).json['conflict'])
        self.assertIsNone(client.post('/api/native-sync',json=payload,headers=headers).json['event'])
    def test_native_validation_and_operation_reuse(self):
        client, headers=self.client(1)
        payload=self.wire(self.event(day='2026-02-30'))
        self.assertEqual(client.post('/api/native-sync',json=payload,headers=headers).status_code,400)
        payload=self.wire(self.event(title='첫 전송'))
        self.assertTrue(client.post('/api/native-sync',json=payload,headers=headers).json['ok'])
        payload['event']['title']='다른 전송'
        self.assertEqual(client.post('/api/native-sync',json=payload,headers=headers).status_code,409)
    def test_native_concurrent_retry_is_atomic(self):
        from concurrent.futures import ThreadPoolExecutor
        client, headers = self.client(1)
        cookie = client.get_cookie('session').value
        payload = self.wire(self.event(title='동시 재전송'))
        def send(_):
            duplicate = self.app.test_client()
            duplicate.set_cookie('session', cookie)
            response = duplicate.post('/api/native-sync', json=payload, headers=headers)
            self.assertEqual(response.status_code, 200)
            return response.json['event']['id']
        with ThreadPoolExecutor(max_workers=2) as pool:
            ids = list(pool.map(send, range(2)))
        self.assertEqual(ids[0], ids[1])
        self.assertEqual(sum(e['id'] == ids[0] for e in client.get('/api/native-sync').json['events']), 1)
    def test_changed_draft_after_lost_create_response_cannot_duplicate(self):
        client, headers = self.client(1)
        first = self.wire(self.event(title='응답 손실 전'))
        event = client.post('/api/native-sync',json=first,headers=headers).json['event']
        changed = self.wire(self.event(title='응답 손실 후 수정'))
        changed['creation_key'] = first['creation_key']
        result = client.post('/api/native-sync',json=changed,headers=headers).json
        self.assertTrue(result['conflict'])
        self.assertEqual(result['current']['id'],event['id'])
        changed.update(operation=str(uuid.uuid4()),action='delete')
        self.assertTrue(client.post('/api/native-sync',json=changed,headers=headers).json['conflict'])
        client.delete('/api/events/'+str(event['id']),headers=headers)
        self.assertIsNone(client.post('/api/native-sync',json=changed,headers=headers).json['current'])
        fresh = self.wire()
        fresh['action'] = 'delete'
        self.assertTrue(client.post('/api/native-sync',json=fresh,headers=headers).json['ok'])
        self.assertTrue(client.post('/api/native-sync',json={**fresh,'action':'save','operation':str(uuid.uuid4())},headers=headers).json['conflict'])
