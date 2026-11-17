import os, sqlite3, secrets, time, re, getpass
from datetime import date
import calendar
from korean_lunar_calendar import KoreanLunarCalendar
from pathlib import Path
from functools import wraps
from flask import Flask, request, session, jsonify, render_template, abort
from werkzeug.security import generate_password_hash, check_password_hash

DATA = Path(os.environ.get('DATA_DIR', '/data'))
DATA.mkdir(parents=True, exist_ok=True)
keyfile = DATA / 'secret.key'
if not keyfile.exists():
    try:
        fd = os.open(keyfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f: f.write(secrets.token_hex(32))
    except FileExistsError: pass
app = Flask(__name__)
app.config.update(SECRET_KEY=keyfile.read_text(), SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE='Lax', SESSION_COOKIE_SECURE=os.getenv('COOKIE_SECURE','true') == 'true',
    PERMANENT_SESSION_LIFETIME=604800, MAX_CONTENT_LENGTH=16384)
def db():
    con = sqlite3.connect(DATA / 'calendar.db', timeout=20)
    con.row_factory = sqlite3.Row
    con.execute('PRAGMA foreign_keys=ON')
    return con
with db() as c:
    c.executescript('''CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, name TEXT NOT NULL, password TEXT NOT NULL, admin INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, title TEXT NOT NULL, day TEXT NOT NULL, clock TEXT NOT NULL, note TEXT NOT NULL, owner INTEGER NOT NULL REFERENCES users(id));
CREATE TABLE IF NOT EXISTS attempts(username TEXT PRIMARY KEY, count INTEGER, since REAL);''')
    existing={r[1] for r in c.execute('PRAGMA table_info(events)')}
    for name, definition in [('calendar_type', "TEXT NOT NULL DEFAULT 'solar'"), ('annual', 'INTEGER NOT NULL DEFAULT 0'), ('leap', 'INTEGER NOT NULL DEFAULT 0')]:
        if name not in existing: c.execute(f'ALTER TABLE events ADD COLUMN {name} {definition}')
def user():
    with db() as c: return c.execute('SELECT id, username, name, admin FROM users WHERE id=?',(session.get('uid'),)).fetchone()
def required(fn):
    @wraps(fn)
    def inner(*a, **kw):
        if not user(): abort(401)
        return fn(*a, **kw)
    return inner
@app.before_request
def csrf():
    if request.method in ('POST','PUT','DELETE'):
        if not secrets.compare_digest(request.headers.get('X-CSRF-Token',''), session.get('csrf','!')): abort(403)
@app.after_request
def headers(r):
    r.headers['X-Content-Type-Options']='nosniff'
    r.headers['Referrer-Policy']='same-origin'
    r.headers['Content-Security-Policy']="default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
    if request.path.startswith('/api/'): r.headers['Cache-Control']='no-store'
    return r
@app.errorhandler(400)
@app.errorhandler(401)
@app.errorhandler(403)
@app.errorhandler(404)
@app.errorhandler(409)
@app.errorhandler(413)
@app.errorhandler(429)
def error(e): return jsonify(error={400:'입력값을 확인해 주세요.',401:'로그인이 필요합니다.',403:'권한이 없거나 세션이 만료되었습니다. 새로고침해 주세요.',404:'항목을 찾을 수 없습니다.',409:'정보가 변경되었습니다.',413:'입력 내용이 너무 깁니다.',429:'로그인 시도가 많습니다. 15분 후 다시 시도해 주세요.'}.get(e.code,'오류')), e.code
@app.get('/')
def index(): return render_template('index.html')
@app.get('/api/me')
def me():
    session.setdefault('csrf',secrets.token_hex(32))
    u=user()
    return jsonify(user=dict(u) if u else None, csrf=session['csrf'])
@app.post('/api/login')
def login():
    data=request.get_json() or {}; name=str(data.get('username',''))[:80]; now=time.time()
    with db() as c:
        attempt=c.execute('SELECT * FROM attempts WHERE username=?',(name,)).fetchone()
        if attempt and now-attempt['since']<900 and attempt['count']>=10: abort(429)
        u=c.execute('SELECT * FROM users WHERE username=?',(name,)).fetchone()
        if not check_password_hash(u['password'] if u else DUMMY, str(data.get('password',''))):
            if not attempt or now-attempt['since']>=900:
                c.execute('INSERT OR REPLACE INTO attempts VALUES(?,1,?)',(name,now))
            else: c.execute('UPDATE attempts SET count=count+1 WHERE username=?',(name,))
            c.commit()
            return jsonify(error='아이디 또는 비밀번호가 맞지 않습니다.'),401
        c.execute('DELETE FROM attempts WHERE username=?',(name,))
    session.clear(); session['uid']=u['id']; session['csrf']=secrets.token_hex(32); session.permanent=True
    return jsonify(ok=True)
DUMMY=generate_password_hash(secrets.token_hex(16))
@app.post('/api/logout')
def logout(): session.clear(); return jsonify(ok=True)
@app.post('/api/password')
@required
def password():
    d=request.get_json() or {}; new=str(d.get('new',''))
    if len(new)<12 or len(new)>200: abort(400)
    with db() as c:
        u=c.execute('SELECT * FROM users WHERE id=?',(session['uid'],)).fetchone()
        if not check_password_hash(u['password'],str(d.get('old',''))): return jsonify(error='현재 비밀번호가 맞지 않습니다.'),400
        c.execute('UPDATE users SET password=? WHERE id=?',(generate_password_hash(new),u['id']))
    session.clear()
    return jsonify(ok=True)
def lunar_to_solar(year, month, day, leap=False):
    k=KoreanLunarCalendar()
    if not k.setLunarDate(year,month,day,leap) or bool(k.isIntercalation)!=bool(leap): return None
    return k.SolarIsoFormat()
def occurrences(event, year):
    sy,sm,sd=map(int,event['day'].split('-'))
    years=[year-1,year,year+1] if event['annual'] and event['calendar_type']=='lunar' else ([year] if event['annual'] else [sy])
    for y in years:
        if event['calendar_type']=='lunar':
            stamp=lunar_to_solar(y,sm,sd,event['leap'])
            if stamp: yield stamp
        else:
            try: yield date(y,sm,sd).isoformat()
            except ValueError: pass
@app.get('/api/lunar')
@required
def lunar_month():
    month=request.args.get('month','')
    if not re.fullmatch(r'(19|20)\d{2}-(0[1-9]|1[0-2])',month): abort(400)
    y,m=map(int,month.split('-')); result={}
    for d in range(1,calendar.monthrange(y,m)[1]+1):
        k=KoreanLunarCalendar()
        if k.setSolarDate(y,m,d): result[date(y,m,d).isoformat()]={'month':k.lunarMonth,'day':k.lunarDay,'leap':bool(k.isIntercalation)}
    return jsonify(result)
@app.get('/api/events')
@required
def events():
    month=request.args.get('month','')
    if not re.fullmatch(r'(19|20)\d{2}-(0[1-9]|1[0-2])',month): abort(400)
    with db() as c: rows=c.execute('SELECT e.*,u.name AS author FROM events e JOIN users u ON e.owner=u.id').fetchall()
    result=[]
    for row in rows:
        e=dict(row)
        for stamp in occurrences(e,int(month[:4])):
            if stamp.startswith(month+'-'): result.append({**e,'source_day':e['day'],'day':stamp})
    return jsonify(sorted(result,key=lambda e:(e['day'],e['clock'],e['id'])))
@app.route('/api/events',methods=['POST'])
@app.route('/api/events/<int:eid>',methods=['PUT','DELETE'])
@required
def save(eid=None):
    with db() as c:
        if eid is not None:
            old=c.execute('SELECT * FROM events WHERE id=?',(eid,)).fetchone()
            if not old: abort(404)
            if old['owner']!=session['uid'] and not user()['admin']: abort(403)
        if request.method=='DELETE': c.execute('DELETE FROM events WHERE id=?',(eid,)); return jsonify(ok=True)
        d=request.get_json() or {}
        title=str(d.get('title','')).strip(); day=str(d.get('day','')); clock=str(d.get('clock','')); note=str(d.get('note',''))
        kind=d.get('calendar_type','solar'); annual=d.get('annual',False); leap=d.get('leap',False)
        if kind not in ('solar','lunar') or type(annual) is not bool or type(leap) is not bool: abort(400)
        if not re.fullmatch(r'(19|20)\d{2}-\d{2}-\d{2}',day): abort(400)
        if kind=='solar':
            try: date.fromisoformat(day)
            except ValueError: abort(400)
            leap=False
        else:
            y,m,dd=map(int,day.split('-'))
            if not lunar_to_solar(y,m,dd,leap): return jsonify(error='유효하지 않거나 지원 범위를 벗어난 음력 날짜입니다. 윤달 여부도 확인해 주세요.'),400
        if not 1<=len(title)<=100 or len(note)>2000 or (clock and not re.fullmatch(r'([01]\d|2[0-3]):[0-5]\d',clock)): abort(400)
        values=(title,day,clock,note,kind,int(annual),int(leap))
        if eid: c.execute('UPDATE events SET title=?,day=?,clock=?,note=?,calendar_type=?,annual=?,leap=? WHERE id=?',values+(eid,))
        else: c.execute('INSERT INTO events(title,day,clock,note,calendar_type,annual,leap,owner) VALUES(?,?,?,?,?,?,?,?)',values+(session['uid'],))
    return jsonify(ok=True)
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(); p.add_argument('command',choices=['add-user','reset-password']); p.add_argument('username'); p.add_argument('--name'); p.add_argument('--admin',action='store_true'); args=p.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_.-]{1,40}',args.username): p.error('아이디는 영문·숫자·_.- 1~40자')
    pw=getpass.getpass('새 비밀번호 (12자 이상): ')
    if len(pw)<12 or len(pw)>200: p.error('비밀번호는 12~200자')
    if pw!=getpass.getpass('비밀번호 확인: '): p.error('비밀번호 불일치')
    with db() as c:
        if args.command=='add-user':
            c.execute('INSERT INTO users(username,name,password,admin) VALUES(?,?,?,?)',(args.username,args.name or args.username,generate_password_hash(pw),int(args.admin)))
        else:
            cur=c.execute('UPDATE users SET password=? WHERE username=?',(generate_password_hash(pw),args.username))
            if not cur.rowcount: p.error('사용자를 찾을 수 없습니다.')
    print('완료')

@app.get('/sw.js')
def sw():
    return app.response_class("self.addEventListener('install',e=>self.skipWaiting());self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));self.addEventListener('fetch',e=>{});",mimetype='application/javascript',headers={'Cache-Control':'no-cache'})
