import os, tempfile, unittest, importlib, json, subprocess
from pathlib import Path

class CalendarTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory();os.environ['DATA_DIR']=cls.tmp.name
  import app
  cls.m=app; cls.app=app.app;cls.app.config['TESTING']=True
  with app.db() as c:
   for i in range(4):c.execute('INSERT INTO users(username,name,password,admin) VALUES(?,?,?,?)',(f'user{i}',f'가족{i}',app.generate_password_hash('strong-password-123'),int(i==0)))
 def client(self,i):
  c=self.app.test_client();token=c.get('/api/me').json['csrf'];r=c.post('/api/login',json={'username':f'user{i}','password':'strong-password-123'},headers={'X-CSRF-Token':token});self.assertEqual(r.status_code,200)
  token=c.get('/api/me').json['csrf'];return c,{'X-CSRF-Token':token}
 def event(self,**kw):return dict(title='생일',day='2026-11-17',clock='',note='',calendar_type='solar',annual=False,leap=False,**kw) if not kw else {**self.event(),**kw}
 def test_shared_and_authorization(self):
  anon=self.app.test_client();self.assertEqual(anon.get('/api/events?month=2026-11').status_code,401)
  c,h=self.client(1);self.assertEqual(c.post('/api/events',json=self.event()).status_code,403)
  self.assertEqual(c.post('/api/events',json=self.event(),headers=h).status_code,200)
  rows=c.get('/api/events?month=2026-11').json;eid=next(e['id'] for e in rows if e['title']=='생일');uid=c.get('/api/me').json['user']['id']
  for i in range(4):
   other,oh=self.client(i);shared=other.get('/api/events?month=2026-11').json;self.assertIn(eid,[e['id'] for e in shared]);self.assertTrue(any(e['owner']==uid for e in shared))
  other,oh=self.client(2);self.assertEqual(other.put(f'/api/events/{eid}',json=self.event(title='변경'),headers=oh).status_code,403);self.assertEqual(other.delete(f'/api/events/{eid}',headers=oh).status_code,403)
  self.assertEqual(c.put(f'/api/events/{eid}',json=self.event(title='작성자 수정'),headers=h).status_code,200)
  admin,ah=self.client(0);self.assertEqual(admin.delete(f'/api/events/{eid}',headers=ah).status_code,200)
 def test_lunar_and_annual(self):
  c,h=self.client(1)
  # 2026 lunar New Year is February 17; lunar February 30 is valid even when not a solar date.
  for d in [self.event(title='음력 설',day='2026-01-01',calendar_type='lunar',annual=True),self.event(title='음력 2월30',day='2024-02-30',calendar_type='lunar'),self.event(title='윤년 생일',day='2024-02-29',annual=True)]:
   self.assertEqual(c.post('/api/events',json=d,headers=h).status_code,200)
  rows=c.get('/api/events?month=2026-02').json
  self.assertEqual(next(e for e in rows if e['title']=='음력 설')['day'],'2026-02-17')
  self.assertEqual(next(e for e in rows if e['title']=='음력 설')['source_day'],'2026-01-01')
  self.assertNotIn('윤년 생일',[e['title'] for e in rows])
  self.assertIn('윤년 생일',[e['title'] for e in c.get('/api/events?month=2028-02').json])
  self.assertEqual(next(e for e in c.get('/api/events?month=2027-02').json if e['title']=='음력 설')['day'],'2027-02-07')
  moon=c.get('/api/lunar?month=2026-02').json;self.assertEqual(moon['2026-02-17'],{'month':1,'day':1,'leap':False})
  self.assertEqual(c.post('/api/events',json=self.event(day='2026-01-01',calendar_type='lunar',leap=True),headers=h).status_code,400)
  self.assertEqual(c.post('/api/events',json=self.event(day='2026-02-30'),headers=h).status_code,400)
 def test_xss_and_session(self):
  c,h=self.client(3);self.assertEqual(c.post('/api/events',json=self.event(title='<script>alert(1)</script>'),headers=h).status_code,200)
  r=c.get('/');self.assertIn("script-src 'self'",r.headers['Content-Security-Policy']);self.assertNotIn('<script>alert(1)</script>',r.text)
  self.assertEqual(c.post('/api/logout',headers=h).status_code,200);self.assertEqual(c.get('/api/events?month=2026-11').status_code,401)
 def test_leap_month_policy(self):
  e=self.event(day='2025-06-01',calendar_type='lunar',annual=1,leap=1)
  self.assertEqual(list(self.m.occurrences(e,2025)),['2025-07-25'])
  self.assertFalse(any(s.startswith('2026-') for s in self.m.occurrences(e,2026)))
 def test_password(self):
  c,h=self.client(3);self.assertEqual(c.post('/api/password',json={'old':'bad','new':'another-strong-password'},headers=h).status_code,400)
 def test_persistence(self):
  # Re-import uses the same database and signing key.
  before=self.app.secret_key;self.assertEqual(importlib.reload(self.m).app.secret_key,before)
  with self.m.db() as c:self.assertEqual(c.execute('SELECT count(*) FROM users').fetchone()[0],4)
 def test_private_visibility_and_mutation(self):
  owner,h=self.client(1)
  payload=self.event(title='개인 기간',day='2026-11-28',end_day='2026-12-03',visibility='private',color='blue')
  self.assertEqual(owner.post('/api/events',json=payload,headers=h).status_code,200)
  own=owner.get('/api/events?month=2026-12').json
  ev=next(e for e in own if e['title']=='개인 기간');self.assertEqual(ev['end_day'],'2026-12-03');self.assertEqual(ev['color'],'blue')
  for i in (0,2,3):
   other,oh=self.client(i)
   self.assertNotIn(ev['id'],[e['id'] for e in other.get('/api/events?month=2026-12').json])
   self.assertEqual(other.put(f"/api/events/{ev['id']}",json=payload,headers=oh).status_code,404)
   self.assertEqual(other.delete(f"/api/events/{ev['id']}",headers=oh).status_code,404)
  payload['visibility']='shared'
  self.assertEqual(owner.put(f"/api/events/{ev['id']}",json=payload,headers=h).status_code,200)
  other,oh=self.client(2)
  self.assertIn(ev['id'],[e['id'] for e in other.get('/api/events?month=2026-12').json])
  payload['visibility']='private';owner.put(f"/api/events/{ev['id']}",json=payload,headers=h)
  self.assertNotIn(ev['id'],[e['id'] for e in other.get('/api/events?month=2026-12').json])
 def test_ranges_cross_month_and_year(self):
  c,h=self.client(1)
  cases=[self.event(title='연말 여행',day='2026-12-30',end_day='2027-01-03',annual=True),self.event(title='음력 연말',day='2025-12-29',end_day='2026-01-02',calendar_type='lunar',annual=True)]
  for d in cases:self.assertEqual(c.post('/api/events',json=d,headers=h).status_code,200)
  jan=c.get('/api/events?month=2028-01').json
  e=next(e for e in jan if e['title']=='연말 여행');self.assertEqual((e['day'],e['end_day']),('2027-12-30','2028-01-03'))
  feb=c.get('/api/events?month=2026-02').json
  e=next(e for e in feb if e['title']=='음력 연말');self.assertEqual((e['day'],e['end_day']),('2026-02-16','2026-02-18'))
  self.assertFalse(any(e['title']=='연말 여행' for e in c.get('/api/events?month=2028-02').json))
 def test_range_validation_and_defaults(self):
  c,h=self.client(1)
  for d in [self.event(end_day='2026-11-16'),self.event(end_day='2028-01-01'),self.event(end_day='2026-02-30'),self.event(visibility='invalid'),self.event(color='invalid')]:
   self.assertEqual(c.post('/api/events',json=d,headers=h).status_code,400)
  self.assertEqual(c.post('/api/events',json=self.event(title='기본 공개'),headers=h).status_code,200)
  e=next(e for e in c.get('/api/events?month=2026-11').json if e['title']=='기본 공개')
  self.assertEqual((e['day'],e['end_day'],e['visibility'],e['color']),('2026-11-17','2026-11-17','shared','sage'))
 def test_legacy_database_migration(self):
  # Run a new process against an old database, preserving its original user and event.
  import sqlite3,sys
  with tempfile.TemporaryDirectory() as tmp:
   with sqlite3.connect(Path(tmp)/'calendar.db') as con:
    con.executescript("CREATE TABLE users(id INTEGER PRIMARY KEY,username TEXT UNIQUE,name TEXT,password TEXT,admin INTEGER DEFAULT 0); CREATE TABLE events(id INTEGER PRIMARY KEY,title TEXT,day TEXT,clock TEXT,note TEXT,owner INTEGER); INSERT INTO users VALUES(1,'legacy','기존 계정','hash',0); INSERT INTO events VALUES(1,'기존 일정','2026-11-17','','',1);")
   env={**os.environ,'DATA_DIR':tmp}
   result=subprocess.run([sys.executable,'-c',"import app; c=app.db(); r=dict(c.execute('SELECT * FROM events').fetchone()); assert r['end_day']==r['day'] and r['visibility']=='shared' and r['color']=='sage'; assert c.execute('SELECT count(*) FROM users').fetchone()[0]==1"],env=env,capture_output=True,text=True)
   self.assertEqual(result.returncode,0,result.stderr)

 def test_admin_user_management(self):
  admin,h=self.client(0);family,fh=self.client(1)
  payload={'username':'newmember','name':'추가 가족','password':'another-test-password-123','admin':False}
  try:
   self.assertEqual(family.get('/api/users').status_code,403)
   self.assertEqual(family.post('/api/users',json=payload,headers=fh).status_code,403)
   self.assertEqual(admin.post('/api/users',json={**payload,'password':'short'},headers=h).status_code,400)
   self.assertEqual(admin.post('/api/users',json=payload,headers=h).status_code,201)
   self.assertEqual(admin.post('/api/users',json=payload,headers=h).status_code,409)
   rows=admin.get('/api/users').json
   self.assertTrue(any(u['username']=='newmember' for u in rows));self.assertTrue(all('password' not in u for u in rows))
   new=self.app.test_client();token=new.get('/api/me').json['csrf']
   self.assertEqual(new.post('/api/login',json={'username':payload['username'],'password':payload['password']},headers={'X-CSRF-Token':token}).status_code,200)
  finally:
   with self.m.db() as c:c.execute("DELETE FROM users WHERE username='newmember'")
 def test_initial_admin_bootstrap(self):
  import sys
  with tempfile.TemporaryDirectory() as tmp:
   env={**os.environ,'DATA_DIR':tmp,'INITIAL_ADMIN_USERNAME':'initialadmin','INITIAL_ADMIN_NAME':'첫 관리자','INITIAL_ADMIN_PASSWORD':'test-initial-password-123'}
   code="import app; c=app.db(); u=c.execute('SELECT * FROM users').fetchone(); assert u['username']=='initialadmin' and u['admin']==1; assert app.check_password_hash(u['password'],'test-initial-password-123')"
   for pw in ('test-initial-password-123','changed-env-password-456'):
    result=subprocess.run([sys.executable,'-c',code],env={**env,'INITIAL_ADMIN_PASSWORD':pw},capture_output=True,text=True)
    self.assertEqual(result.returncode,0,result.stderr)
   with tempfile.TemporaryDirectory() as empty:
    result=subprocess.run([sys.executable,'-c','import app'],env={**env,'DATA_DIR':empty,'INITIAL_ADMIN_PASSWORD':''},capture_output=True,text=True)
    self.assertNotEqual(result.returncode,0);self.assertIn('INITIAL_ADMIN_PASSWORD',result.stderr)
 def test_holiday_calendar(self):
  client, _ = self.client(1)
  def month(value):
   return client.get('/api/holidays?month='+value).json
  plan = self.m.holidays_for_year(2027)
  self.assertEqual({d for d, label in plan.items() if label=='중복휴일'},
                   {'2027-'+suffix for suffix in ('02-10','05-03','07-19','07-26','08-16','10-04','10-11','12-27')})
  self.assertEqual(month('2027-02')['2027-02-09'],'설날 대체공휴일')
  self.assertEqual(month('2027-05')['2027-05-13'],'부처님오신날')
  self.assertEqual(month('2027-09')['2027-09-15'],'추석')
  self.assertEqual(month('2027-07')['2027-07-17'],'제헌절')
  self.assertEqual(month('2027-07')['2027-07-25'],'노조창립일')
  self.assertEqual([plan[f'2027-08-{d:02}'] for d in range(2,7)],['하기휴무']*5)
  self.assertEqual(plan['2027-12-29'],'회사창립일')
  self.assertNotIn('2028-12-29',month('2028-12'))
  self.assertEqual(month('2028-01')['2028-01-27'],'설날')
  self.assertEqual(client.get('/api/holidays?month=2027-00').status_code,400)

 def test_remembered_device_lifetime_and_revocation(self):
  import time
  from unittest.mock import patch
  def sign_in(remember):
   client=self.app.test_client()
   csrf=client.get('/api/me').json['csrf']
   r=client.post('/api/login',json={'username':'user2','password':'strong-password-123','remember':remember},headers={'X-CSRF-Token':csrf})
   self.assertEqual(r.status_code,200)
   return client,client.get_cookie('session')
  device,cookie=sign_in(True)
  self.assertAlmostEqual(cookie.expires.timestamp()-time.time(),90*86400,delta=5)
  self.assertTrue(cookie.http_only and cookie.secure)
  another,other_cookie=sign_in(False)
  self.assertIsNone(other_cookie.expires)
  replay=self.app.test_client()
  replay.set_cookie('session',cookie.value)
  with patch('time.time',return_value=time.time()+8*86400):
   self.assertEqual(replay.get('/api/me').json['user']['username'],'user2')
   self.assertIsNone(another.get('/api/me').json['user'])
  with patch('time.time',return_value=time.time()+91*86400):
   self.assertIsNone(replay.get('/api/me').json['user'])
  second,_=sign_in(True)
  csrf=device.get('/api/me').json['csrf']
  self.assertEqual(device.post('/api/logout',headers={'X-CSRF-Token':csrf}).status_code,200)
  replay.set_cookie('session',cookie.value)
  self.assertIsNone(replay.get('/api/me').json['user'])
  self.assertEqual(second.get('/api/me').json['user']['username'],'user2')

 def test_password_change_revokes_other_device(self):
  first,headers=self.client(2)
  other,_=self.client(2)
  with self.m.db() as conn:
   old_hash=conn.execute("SELECT password FROM users WHERE username='user2'").fetchone()[0]
  try:
   result=first.post('/api/password',json={'old':'strong-password-123','new':'changed-for-this-test'},headers=headers)
   self.assertEqual(result.status_code,200)
   self.assertIsNone(other.get('/api/me').json['user'])
  finally:
   with self.m.db() as conn:
    conn.execute("UPDATE users SET password=? WHERE username='user2'",(old_hash,))

 def test_category_preserved_with_legacy_clients_and_privacy(self):
  owner,auth=self.client(1)
  other,_=self.client(2)
  legacy=self.event(title='분류 검증 일정',visibility='private')
  self.assertEqual(owner.post('/api/events',json=legacy,headers=auth).status_code,200)
  month='/api/events?month=2026-11'
  event=next(e for e in owner.get(month).json if e['title']==legacy['title'])
  self.assertEqual(event['category'],'family')
  url=f"/api/events/{event['id']}"
  for data,code in [({**legacy,'category':'work'},200),(legacy,200),({**legacy,'category':'bad'},400)]:
   self.assertEqual(owner.put(url,json=data,headers=auth).status_code,code)
  updated=next(e for e in owner.get(month).json if e['id']==event['id'])
  self.assertEqual(updated['category'],'work')
  self.assertFalse(any(e['id']==event['id'] for e in other.get(month).json))

 def test_bulk_creation_is_atomic_and_keeps_private_work_defaults_from_client(self):
  owner,auth=self.client(1)
  other,_=self.client(2)
  payload={'day':'2026-11-20','category':'work','visibility':'private',
           'entries':[{'title':'간편 회의','clock':'14:00'},{'title':'간편 검토','clock':''}]}
  endpoint='/api/events'
  month=endpoint+'?month=2026-11'
  named=lambda client: [e for e in client.get(month).json if e['title'].startswith('간편 ')]
  invalid={**payload,'entries':[payload['entries'][0],{'title':'  '}]}
  self.assertEqual(owner.post(endpoint,json=invalid,headers=auth).status_code,400)
  self.assertEqual(named(owner),[])
  self.assertEqual(owner.post(endpoint,json=payload,headers=auth).status_code,200)
  events=named(owner)
  self.assertEqual({(e['title'], e['clock']) for e in events},{('간편 회의','14:00'),('간편 검토','')})
  self.assertTrue(all(e['category']=='work' and e['visibility']=='private' for e in events))
  self.assertEqual(named(other),[])

 def test_version_identification(self):
  c=self.app.test_client();r=c.get('/')
  self.assertIn('Famical v1.7.1',r.text);self.assertIn('?v=1.7.1',r.text)
  self.assertEqual(r.headers['Cache-Control'],'no-store')
  self.assertEqual(r.headers['X-Famical-Version'],'1.7.1')
  self.assertEqual(c.get('/api/version').json,{'version':'1.7.1'})

 def test_account_shift_preferences_survive_login_and_other_devices(self):
  owner,h=self.client(2)
  for team in ('B','none'):
   r=owner.put('/api/preferences',json={'shift_team':team,'shift_on':True},headers=h)
   self.assertEqual(r.status_code,200);self.assertEqual(r.json['user']['shift_team'],team)
   another,_=self.client(2);self.assertEqual(another.get('/api/me').json['user']['shift_team'],team)
  self.assertEqual(owner.put('/api/preferences',json={'shift_team':'none','shift_on':False},headers=h).status_code,200)
  self.assertEqual(owner.put('/api/preferences',json={'shift_team':'C','shift_on':True},headers=h).status_code,400)
  self.assertEqual(owner.put('/api/preferences',json={'shift_team':'A','shift_on':'false'},headers=h).status_code,400)
  self.assertEqual(owner.put('/api/preferences',json={'shift_team':'A','shift_on':True}).status_code,403)
  self.assertEqual(self.client(1)[0].get('/api/me').json['user']['shift_team'],'A')
  owner.post('/api/logout',headers=h)
  again,_=self.client(2);self.assertEqual(again.get('/api/me').json['user']['shift_on'],0)

 def sync_client(self):
  c,h=self.client(1);token='test-calendar-connection-123'
  r=c.post('/api/calendar-sync/connection',json={'connection':token,'label':'테스트 캘린더','visibility':'private'},headers=h)
  self.assertEqual(r.status_code,200)
  return c,h,token

 def sync_item(self,c,h,token,key,action,**extra):
  return c.post('/api/calendar-sync/item',json={'connection':token,'external_key':key,'action':action,'native_hash':'a'*64,**extra},headers=h)

 def snapshot(self,c,token):
  return c.get(f'/api/calendar-sync?connection={token}&from=2026-01-01&to=2027-01-01').json

 def test_sync_import_retry_privacy_and_tombstone(self):
  c,h,token=self.sync_client();key='n:1001';payload=self.event(title='휴대전화 원본',end_day='2026-11-19')
  r=self.sync_item(c,h,token,key,'import',event=payload);self.assertEqual(r.status_code,200)
  e=r.json['event'];self.assertEqual(e['visibility'],'private');self.assertEqual(e['owner'],c.get('/api/me').json['user']['id'])
  r=self.sync_item(c,h,token,key,'import',event={**payload,'title':'재시도 변경 금지'})
  self.assertEqual(r.json['event']['id'],e['id']);self.assertEqual(r.json['event']['title'],'휴대전화 원본')
  other,oh=self.client(0);self.assertNotIn(e['id'],[v['id'] for v in other.get('/api/events?month=2026-11').json])
  self.assertEqual(self.sync_item(c,h,token,key,'update',revision='stale',event=payload).status_code,409)
  updated=self.sync_item(c,h,token,key,'update',revision=e['revision'],event={**payload,'title':'휴대전화 수정','visibility':'shared'})
  self.assertEqual(updated.status_code,200);self.assertEqual(updated.json['event']['visibility'],'private')
  e=updated.json['event']
  self.assertEqual(c.delete(f"/api/events/{e['id']}",headers=h).status_code,200)
  link=next(v for v in self.snapshot(c,token)['links'] if v['external_key']==key);self.assertIsNone(link['event'])
  r=self.sync_item(c,h,token,key,'import',event=payload);self.assertIsNone(r.json['event'])

 def test_sync_web_edits_conflicts_and_native_deletion(self):
  c,h,token=self.sync_client();key='n:1002'
  e=self.sync_item(c,h,token,key,'import',event=self.event(title='서로 수정')).json['event']
  web=self.event(title='웹 수정 우선 확인',visibility='private')
  self.assertEqual(c.put(f"/api/events/{e['id']}",json=web,headers=h).status_code,200)
  self.assertEqual(self.sync_item(c,h,token,key,'delete',revision=e['revision']).status_code,409)
  self.assertEqual(self.sync_item(c,h,token,key,'update',revision=e['revision'],event=self.event(title='오래된 수정')).status_code,409)
  current=next(v['event'] for v in self.snapshot(c,token)['links'] if v['external_key']==key)
  self.assertEqual(current['title'],'웹 수정 우선 확인')
  self.assertEqual(self.sync_item(c,h,token,key,'checkpoint',revision=current['revision'],native_hash='b'*64).status_code,200)
  self.assertEqual(self.sync_item(c,h,token,key,'delete',revision=current['revision']).status_code,200)
  self.assertIsNone(next(v['event'] for v in self.snapshot(c,token)['links'] if v['external_key']==key))

 def test_sync_owner_scope_connection_and_csrf(self):
  c,h,token=self.sync_client();other,oh=self.client(2)
  self.assertEqual(other.get(f'/api/calendar-sync?connection={token}&from=2026-01-01&to=2026-12-31').status_code,409)
  self.assertEqual(c.post('/api/calendar-sync/item',json={}).status_code,403)
  self.assertEqual(c.post('/api/calendar-sync/connection',json={'connection':'another-device-12345','label':'다른 기기'},headers=h).status_code,409)
  other.post('/api/events',json=self.event(title='다른 사람의 공유 일정'),headers=oh)
  foreign=next(e for e in c.get('/api/events?month=2026-11').json if e['title']=='다른 사람의 공유 일정')
  self.assertNotIn(foreign['id'],[e['id'] for e in self.snapshot(c,token)['events']])
  self.assertEqual(self.sync_item(c,h,token,'n:1003','attach',event_id=foreign['id'],revision='x').status_code,409)
  self.assertEqual(c.get(f'/api/calendar-sync?connection={token}&from=2026-01-01&to=2029-12-31').status_code,400)

 def test_sync_recurring_occurrences_are_source_editable_only(self):
  c,h,token=self.sync_client();key='r:2001:1794870000000'
  e=self.sync_item(c,h,token,key,'import',event=self.event(title='반복 발생일')).json['event']
  self.assertEqual(e['sync_readonly'],1)
  self.assertEqual(c.put(f"/api/events/{e['id']}",json=self.event(title='잘못된 수정'),headers=h).status_code,409)
  self.assertEqual(c.delete(f"/api/events/{e['id']}",headers=h).status_code,409)
  self.assertEqual(self.sync_item(c,h,token,key,'update',revision=e['revision'],event=self.event(title='원본에서 바뀜')).status_code,200)

 def test_sync_lunar_projection_preserves_rule(self):
  c,h,token=self.sync_client()
  c.post('/api/events',json=self.event(title='연동 음력 설',day='2026-01-01',calendar_type='lunar',annual=True),headers=h)
  e=next(v for v in self.snapshot(c,token)['events'] if v['title']=='연동 음력 설')
  self.assertTrue(e['projection']);self.assertEqual(e['day'],'2026-02-17')
  r=self.sync_item(c,h,token,'n:1004','attach',event_id=e['id'],revision=e['revision'],projection_day=e['day'])
  self.assertEqual(r.status_code,200)
  self.assertEqual(self.sync_item(c,h,token,'n:1004','update',revision=e['revision'],event=self.event(title='음력 규칙 훼손 금지')).status_code,409)
  with self.m.db() as con:
   raw=con.execute('SELECT * FROM events WHERE id=?',(e['id'],)).fetchone()
   self.assertEqual((raw['day'],raw['calendar_type'],raw['annual']),('2026-01-01','lunar',1))

 def test_sync_explicit_restore_and_rebind_after_delete_conflicts(self):
  c,h,token=self.sync_client();key='n:1100';payload=self.event(title='삭제 충돌 복구')
  event=self.sync_item(c,h,token,key,'import',event=payload).json['event']
  c.delete(f"/api/events/{event['id']}",headers=h)
  self.assertEqual(self.sync_item(c,h,token,key,'restore',revision='stale',event=payload).status_code,409)
  restored=self.sync_item(c,h,token,key,'restore',revision=event['revision'],event=payload)
  self.assertEqual(restored.status_code,200);self.assertNotEqual(restored.json['event']['id'],event['id']);event=restored.json['event'];self.assertEqual(event['visibility'],'private')
  rebound=self.sync_item(c,h,token,'n:1101','rebind',replaces_key=key,event_id=event['id'],revision=event['revision'])
  self.assertEqual(rebound.status_code,200)
  links={v['external_key']:v for v in self.snapshot(c,token)['links']}
  self.assertIsNone(links[key]['event']);self.assertEqual(links['n:1101']['event']['id'],event['id'])

if __name__=='__main__':unittest.main()
