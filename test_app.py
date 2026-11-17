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
 def test_version_identification(self):
  c=self.app.test_client();r=c.get('/')
  self.assertIn('Famical v1.2.0',r.text);self.assertIn('?v=1.2.0',r.text)
  self.assertEqual(r.headers['Cache-Control'],'no-store')
  self.assertEqual(r.headers['X-Famical-Version'],'1.2.0')
  self.assertEqual(c.get('/api/version').json,{'version':'1.2.0'})

if __name__=='__main__':unittest.main()
