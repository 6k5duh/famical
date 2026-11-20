import os, sqlite3, secrets, time, re, getpass
import hashlib
from datetime import date, datetime, timedelta, timezone
import calendar
from korean_lunar_calendar import KoreanLunarCalendar
from pathlib import Path
from functools import wraps
from flask.sessions import SecureCookieSessionInterface
from flask import Flask, request, session, jsonify, render_template, abort
from werkzeug.security import generate_password_hash, check_password_hash

from calendar_holidays import holidays_for_year

APP_VERSION = '2.0.0'

DATA = Path(os.environ.get('DATA_DIR', '/data'))
DATA.mkdir(parents=True, exist_ok=True)
keyfile = DATA / 'secret.key'
if not keyfile.exists():
    try:
        fd = os.open(keyfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f: f.write(secrets.token_hex(32))
    except FileExistsError: pass
REMEMBER_DAYS = 90
NORMAL_DAYS = 7


class CalendarSessionInterface(SecureCookieSessionInterface):
    def get_expiration_time(self, app, session):
        if session.permanent and 'expires_at' in session:
            return datetime.fromtimestamp(session['expires_at'], timezone.utc)
        return super().get_expiration_time(app, session)


app = Flask(__name__)
app.session_interface = CalendarSessionInterface()
app.config.update(SECRET_KEY=keyfile.read_text(), SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE='Lax', SESSION_COOKIE_SECURE=os.getenv('COOKIE_SECURE','true') == 'true',
    SESSION_REFRESH_EACH_REQUEST=False, PERMANENT_SESSION_LIFETIME=REMEMBER_DAYS * 86400, MAX_CONTENT_LENGTH=16384)
def db():
    con = sqlite3.connect(DATA / 'calendar.db', timeout=20)
    con.row_factory = sqlite3.Row
    con.execute('PRAGMA foreign_keys=ON')
    return con
with db() as c:
    c.executescript('''CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL, name TEXT NOT NULL, password TEXT NOT NULL, admin INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, title TEXT NOT NULL, day TEXT NOT NULL, clock TEXT NOT NULL, note TEXT NOT NULL, owner INTEGER NOT NULL REFERENCES users(id));
CREATE TABLE IF NOT EXISTS attempts(username TEXT PRIMARY KEY, count INTEGER, since REAL);''')
    c.execute("""CREATE TABLE IF NOT EXISTS device_sessions (
        digest TEXT PRIMARY KEY,
        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL
    )""")
    existing={r[1] for r in c.execute('PRAGMA table_info(events)')}
    for name, definition in [('calendar_type', "TEXT NOT NULL DEFAULT 'solar'"), ('annual', 'INTEGER NOT NULL DEFAULT 0'), ('leap', 'INTEGER NOT NULL DEFAULT 0'), ('end_day', "TEXT NOT NULL DEFAULT ''"), ('visibility', "TEXT NOT NULL DEFAULT 'shared'"), ('color', "TEXT NOT NULL DEFAULT 'sage'")]:
        if name not in existing: c.execute(f'ALTER TABLE events ADD COLUMN {name} {definition}')
    c.execute("UPDATE events SET end_day=day WHERE end_day=''")
    user_columns={r[1] for r in c.execute('PRAGMA table_info(users)')}
    for name, definition in [('shift_team', "TEXT NOT NULL DEFAULT 'A'"), ('shift_on', 'INTEGER NOT NULL DEFAULT 1')]:
        if name not in user_columns: c.execute(f'ALTER TABLE users ADD COLUMN {name} {definition}')
    if 'category' not in existing:
        c.execute("ALTER TABLE events ADD COLUMN category TEXT NOT NULL DEFAULT 'family' CHECK(category IN ('family','work'))")
    if 'sync_readonly' not in existing:
        c.execute('ALTER TABLE events ADD COLUMN sync_readonly INTEGER NOT NULL DEFAULT 0')
    # Stable identities are essential for delayed device writes and tombstones.
    c.execute('CREATE TABLE IF NOT EXISTS event_ids(id INTEGER PRIMARY KEY AUTOINCREMENT)')
    c.execute('INSERT OR IGNORE INTO event_ids(id) SELECT MAX(id) FROM events HAVING MAX(id)>0')

def next_event_id(c):
    return c.execute('INSERT INTO event_ids DEFAULT VALUES').lastrowid

# This runs only when the volume contains no accounts. Existing accounts are preserved.
with db() as c:
    c.execute('BEGIN IMMEDIATE')
    if not c.execute('SELECT 1 FROM users LIMIT 1').fetchone():
        initial_password=os.environ.get('INITIAL_ADMIN_PASSWORD','')
        if 'INITIAL_ADMIN_PASSWORD' in os.environ and not initial_password:
            raise ValueError('Set INITIAL_ADMIN_PASSWORD in initial-admin.env before the first start (12-200 characters).')
        if initial_password:
            initial_username=os.environ.get('INITIAL_ADMIN_USERNAME','admin')
            initial_name=os.environ.get('INITIAL_ADMIN_NAME','관리자')
            if not re.fullmatch(r'[a-zA-Z0-9_.-]{1,40}',initial_username) or not 12<=len(initial_password)<=200 or not 1<=len(initial_name)<=80:
                raise ValueError('Initial admin: username must be 1-40 ASCII letters/numbers/_.-, password 12-200 characters, name 1-80 characters.')
            c.execute('INSERT INTO users(username,name,password,admin) VALUES(?,?,?,1)',(initial_username,initial_name,generate_password_hash(initial_password)))

def session_digest(token):
    return hashlib.sha256(token.encode('ascii')).hexdigest()


def forget_device(connection):
    token = session.get('device')
    if token:
        connection.execute('DELETE FROM device_sessions WHERE digest=?', (session_digest(token),))


@app.before_request
def check_device_session():
    if not request.path.startswith('/api/') or 'uid' not in session:
        return
    token = session.get('device')
    if token:
        with db() as connection:
            active = connection.execute(
                'SELECT 1 FROM device_sessions WHERE digest=? AND user_id=? AND expires_at>?',
                (session_digest(token), session['uid'], time.time())
            ).fetchone()
        if active:
            return
    session.clear()


def user():
    with db() as c: return c.execute('SELECT id, username, name, admin, shift_team, shift_on FROM users WHERE id=?',(session.get('uid'),)).fetchone()
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
    r.headers['X-Famical-Version']=APP_VERSION
    if request.path=='/' or request.path.startswith('/api/'): r.headers['Cache-Control']='no-store'
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
def index():
    return render_template('index.html', version=APP_VERSION, desktop=request.args.get('view') == 'desktop')
@app.get('/api/version')
def version(): return jsonify(version=APP_VERSION)

@app.route('/api/users',methods=['GET','POST'])
@required
def users():
    if not user()['admin']: abort(403)
    if request.method=='GET':
        with db() as c: rows=c.execute('SELECT id,username,name,admin FROM users ORDER BY id').fetchall()
        return jsonify([dict(r) for r in rows])
    d=request.get_json() or {}
    username=str(d.get('username','')).strip(); name=str(d.get('name','')).strip()
    password=str(d.get('password','')); admin=d.get('admin',False)
    if not re.fullmatch(r'[a-zA-Z0-9_.-]{1,40}',username) or not 1<=len(name)<=80 or not 12<=len(password)<=200 or type(admin) is not bool: abort(400)
    try:
        with db() as c: c.execute('INSERT INTO users(username,name,password,admin) VALUES(?,?,?,?)',(username,name,generate_password_hash(password),int(admin)))
    except sqlite3.IntegrityError: return jsonify(error='이미 사용 중인 아이디입니다.'),409
    return jsonify(ok=True),201

@app.get('/api/me')
def me():
    session.setdefault('csrf',secrets.token_hex(32))
    u=user()
    return jsonify(user=dict(u) if u else None, csrf=session['csrf'])
@app.put('/api/preferences')
@required
def preferences():
    d=request.get_json() or {}
    team=d.get('shift_team'); enabled=d.get('shift_on')
    if team not in ('A','B','none') or type(enabled) is not bool: abort(400)
    with db() as c:
        c.execute('UPDATE users SET shift_team=?,shift_on=? WHERE id=?',(team,int(enabled),session['uid']))
    return jsonify(user=dict(user()))
@app.post('/api/login')
def login():
    data=request.get_json() or {}; name=str(data.get('username',''))[:80]; now=time.time()
    remember = data.get('remember')
    if 'remember' in data and type(remember) is not bool:
        abort(400)
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
        # Password reset may have raced the password check on this request.
        if not c.execute('SELECT 1 FROM users WHERE id=? AND password=?', (u['id'], u['password'])).fetchone():
            abort(401)
        forget_device(c)
        c.execute('DELETE FROM device_sessions WHERE expires_at<=?', (now,))
        token = secrets.token_urlsafe(32)
        lifetime_days = REMEMBER_DAYS if remember else NORMAL_DAYS
        expires_at = int(now) + lifetime_days * 86400
        c.execute('INSERT INTO device_sessions VALUES(?,?,?)', (session_digest(token), u['id'], expires_at))
    session.clear()
    session.update(uid=u['id'], csrf=secrets.token_hex(32), device=token, expires_at=expires_at)
    # API clients predating the checkbox retain their seven-day persistent cookie.
    session.permanent = remember is not False
    return jsonify(ok=True)
DUMMY=generate_password_hash(secrets.token_hex(16))
@app.post('/api/logout')
def logout():
    with db() as connection:
        forget_device(connection)
    session.clear()
    return jsonify(ok=True)
@app.post('/api/password')
@required
def password():
    d=request.get_json() or {}; new=str(d.get('new',''))
    if len(new)<12 or len(new)>200: abort(400)
    with db() as c:
        u=c.execute('SELECT * FROM users WHERE id=?',(session['uid'],)).fetchone()
        if not check_password_hash(u['password'],str(d.get('old',''))): return jsonify(error='현재 비밀번호가 맞지 않습니다.'),400
        c.execute('UPDATE users SET password=? WHERE id=?',(generate_password_hash(new),u['id']))
        c.execute('DELETE FROM device_sessions WHERE user_id=?', (u['id'],))
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
@app.get('/api/holidays')
@required
def holiday_month():
    month = request.args.get('month', '')
    if not re.fullmatch(r'(19|20)\d{2}-(0[1-9]|1[0-2])', month):
        abort(400)
    dates = holidays_for_year(int(month[:4]))
    return jsonify({day: reason for day, reason in dates.items() if day.startswith(month)})


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
def calendar_date(stamp, kind, leap=False):
    if not isinstance(stamp,str) or not re.fullmatch(r'(19|20)\d{2}-\d{2}-\d{2}', stamp):
        raise ValueError('날짜는 YYYY-MM-DD 형식으로 입력해 주세요.')
    if kind=='lunar':
        converted=lunar_to_solar(*map(int,stamp.split('-')),leap)
        if not converted: raise ValueError('유효하지 않은 음력 날짜입니다. 윤달 여부와 지원 범위를 확인해 주세요.')
        return date.fromisoformat(converted)
    return date.fromisoformat(stamp)

def event_spans(event, year):
    start_source=event['day']; end_source=event['end_day'] or start_source
    start_year=int(start_source[:4]); offset=int(end_source[:4])-start_year
    years=[year-1,year,year+1] if event['annual'] else [start_year]
    for y in years:
        start=f'{y:04d}'+start_source[4:]
        end=f'{y+offset:04d}'+end_source[4:]
        try:
            a=calendar_date(start,event['calendar_type'],event['leap'])
            b=calendar_date(end,event['calendar_type'],event['leap'])
            if 0 <= (b-a).days <= 365: yield a.isoformat(),b.isoformat()
        except ValueError: continue

def calendar_segments(event, start, end):
    # Widgets use day/end_day directly. Give every client the same visible weekdays.
    if event['category'] != 'work' or start == end:
        yield {'day': start, 'end_day': end}
        return

    current = date.fromisoformat(start)
    last = date.fromisoformat(end)
    if current.weekday() >= 5:
        current += timedelta(days=7 - current.weekday())

    spans = []
    elapsed = 0
    while current <= last:
        friday = current + timedelta(days=4 - current.weekday())
        through = min(friday, last)
        spans.append((current, through, elapsed))
        elapsed += (through - current).days + 1
        current = friday + timedelta(days=3)
    for first, through, offset in spans:
        yield {'day': first.isoformat(), 'end_day': through.isoformat(),
               'occurrence_start': start, 'occurrence_end': end,
               'work_day_offset': offset, 'work_day_total': elapsed}

@app.get('/api/events')
@required
def events():
    month=request.args.get('month','')
    if not re.fullmatch(r'(19|20)\d{2}-(0[1-9]|1[0-2])',month): abort(400)
    y,m=map(int,month.split('-'))
    first=date(y,m,1).isoformat(); last=date(y,m,calendar.monthrange(y,m)[1]).isoformat()
    with db() as c:
        rows=c.execute("SELECT e.*,u.name AS author FROM events e JOIN users u ON e.owner=u.id WHERE e.visibility='shared' OR e.owner=?",(session['uid'],)).fetchall()
    result=[]
    for row in rows:
        e=dict(row)
        for start,end in event_spans(e,y):
            if start > last or end < first:
                continue
            for segment in calendar_segments(e, start, end):
                if segment['day'] <= last and segment['end_day'] >= first:
                    result.append({**e, 'source_day': e['day'], 'source_end_day': e['end_day'], **segment})
    return jsonify(sorted(result,key=lambda e:(e['day'],e['clock'],e['id'])))
@app.route('/api/events',methods=['POST'])
@app.route('/api/events/<int:eid>',methods=['PUT','DELETE'])
@required
def save(eid=None):
    with db() as c:
        return write_event(c, eid, {} if request.method=='DELETE' else request.get_json() or {}, request.method)

def write_event(c, eid, d, method):
    if eid is not None:
        old=c.execute("SELECT * FROM events WHERE id=? AND (visibility='shared' OR owner=?)",(eid,session['uid'])).fetchone()
        if not old: abort(404)
        if old['owner']!=session['uid'] and not user()['admin']: abort(403)
        if old['sync_readonly']:
            return jsonify(error='휴대전화의 반복 일정입니다. 원본 캘린더에서 수정·삭제해 주세요.'),409
    if method=='DELETE': c.execute('DELETE FROM events WHERE id=?',(eid,)); return jsonify(ok=True)
    title=str(d.get('title','')).strip(); day=str(d.get('day','')); clock=str(d.get('clock','')); note=str(d.get('note',''))
    kind=d.get('calendar_type','solar'); annual=d.get('annual',False); leap=d.get('leap',False)
    if kind not in ('solar','lunar') or type(annual) is not bool or type(leap) is not bool: abort(400)
    end_day=d.get('end_day') or day
    visibility=d.get('visibility','shared'); color=d.get('color','sage')
    if visibility not in ('shared','private') or color not in ('sage','blue','rose','amber','violet'): abort(400)
    if eid and (old['calendar_type']!=kind or bool(old['annual'])!=annual):
        if c.execute('SELECT 1 FROM calendar_links WHERE event_id=?',(eid,)).fetchone():
            return jsonify(error='연동 중인 일정의 날짜 기준·반복 설정은 변경할 수 없습니다. 새 일정으로 등록해 주세요.'),409
    if kind=='solar': leap=False
    try:
        start_date=calendar_date(day,kind,leap)
        end_date=calendar_date(end_day,kind,leap)
    except (ValueError,TypeError): return jsonify(error='날짜가 유효하지 않습니다. 음력은 윤달 여부도 확인해 주세요.'),400
    if not 0 <= (end_date-start_date).days <= 365:
        return jsonify(error='종료일은 시작일 이후여야 하며 일정 기간은 최대 366일입니다.'),400
    entries = d.get('entries', [{'title': title, 'clock': clock}])
    if 'entries' in d and eid is not None: abort(400)
    if not isinstance(entries, list) or not 1 <= len(entries) <= 50 or len(note) > 2000: abort(400)
    titles_and_times = []
    for entry in entries:
        if not isinstance(entry, dict): abort(400)
        name, start_time = entry.get('title'), entry.get('clock', '')
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 100: abort(400)
        if not isinstance(start_time, str) or (start_time and not re.fullmatch(r'([01]\d|2[0-3]):[0-5]\d', start_time)): abort(400)
        titles_and_times.append((name.strip(), start_time))
    category = d.get('category', old['category'] if eid is not None else 'family')
    if category not in ('family', 'work'):
        abort(400)
    ids = []
    for title, clock in titles_and_times:
        values=(title,day,clock,note,kind,int(annual),int(leap),end_day,visibility,color,category)
        target_id = eid or next_event_id(c)
        ids.append(target_id)
        if eid: c.execute('UPDATE events SET title=?,day=?,clock=?,note=?,calendar_type=?,annual=?,leap=?,end_day=?,visibility=?,color=?,category=? WHERE id=?',values+(eid,))
        else: c.execute('INSERT INTO events(id,title,day,clock,note,calendar_type,annual,leap,end_day,visibility,color,category,owner) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',(target_id,)+values+(session['uid'],))
    return jsonify(ok=True, ids=ids)

from calendar_sync import register_sync
register_sync(app, db, required, calendar_date, event_spans, next_event_id)

from native_sync import register_native_sync
register_native_sync(app, db, required, user, write_event)

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
            c.execute('DELETE FROM device_sessions WHERE user_id=(SELECT id FROM users WHERE username=?)', (args.username,))
    print('완료')

@app.get('/sw.js')
def sw():
    return app.response_class("self.addEventListener('install',e=>self.skipWaiting());self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));self.addEventListener('fetch',e=>{});",mimetype='application/javascript',headers={'Cache-Control':'no-cache'})
