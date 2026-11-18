"""Authenticated, owner-only Android Calendar Provider synchronization.

Links survive event deletion so a delayed/retried import cannot resurrect it.
Native repeat occurrences are view-only; their recurrence rules stay on-device.
"""
import hashlib
import json
import re
from datetime import date
from flask import request, session, jsonify, abort

FIELDS = ('title', 'day', 'end_day', 'clock', 'note', 'calendar_type', 'annual', 'leap', 'visibility', 'color')


def revision(event):
    return hashlib.sha256(json.dumps({k: event[k] for k in FIELDS}, sort_keys=True,
                                     ensure_ascii=False).encode()).hexdigest()


def register_sync(app, db, required, calendar_date, event_spans, next_event_id):
    with db() as c:
        c.executescript('''
CREATE TABLE IF NOT EXISTS calendar_connections (
 owner INTEGER PRIMARY KEY REFERENCES users(id), connection TEXT NOT NULL,
 label TEXT NOT NULL, visibility TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS calendar_links (
 owner INTEGER NOT NULL REFERENCES users(id), connection TEXT NOT NULL,
 external_key TEXT NOT NULL, event_id INTEGER REFERENCES events(id) ON DELETE SET NULL,
 mode TEXT NOT NULL, projection_day TEXT NOT NULL DEFAULT '',
 native_hash TEXT NOT NULL, server_revision TEXT NOT NULL,
 PRIMARY KEY(owner,connection,external_key));
''')

    def connection(c):
        token=request.args.get('connection') if request.method=='GET' else (request.get_json() or {}).get('connection')
        row=c.execute('SELECT * FROM calendar_connections WHERE owner=? AND connection=?',
                      (session['uid'],token)).fetchone()
        if not row: abort(409)
        return row

    def output(event, projection_day='', year=None):
        event=dict(event)
        rev=revision(event)
        if projection_day:
            spans=list(event_spans(event,year or int(projection_day[:4])))
            span=next((s for s in spans if s[0]==projection_day),None)
            if not span: return None
            event.update(day=span[0],end_day=span[1])
        return {**event, 'revision':rev, 'projection':bool(projection_day)}

    @app.route('/api/calendar-sync/connection',methods=['POST','DELETE'])
    @required
    def sync_connection():
        d=request.get_json() or {}; token=d.get('connection','')
        if not isinstance(token,str) or not re.fullmatch(r'[a-zA-Z0-9-]{16,80}',token): abort(400)
        with db() as c:
            c.execute('BEGIN IMMEDIATE')
            old=c.execute('SELECT * FROM calendar_connections WHERE owner=?',(session['uid'],)).fetchone()
            if old and old['connection']!=token:
                return jsonify(error='다른 휴대전화 또는 캘린더가 이미 연결되어 있습니다. 먼저 기존 기기에서 연동을 해제해 주세요.'),409
            if request.method=='DELETE':
                c.execute('DELETE FROM calendar_connections WHERE owner=?',(session['uid'],))
                return jsonify(ok=True)
            label=d.get('label',''); visibility=d.get('visibility','private')
            if not isinstance(label,str) or not 1<=len(label)<=200 or visibility not in ('private','shared'): abort(400)
            c.execute('INSERT INTO calendar_connections VALUES(?,?,?,?) ON CONFLICT(owner) DO UPDATE SET label=excluded.label, visibility=excluded.visibility',
                      (session['uid'],token,label,visibility))
        return jsonify(ok=True)

    @app.get('/api/calendar-sync')
    @required
    def sync_snapshot():
        try:
            first=date.fromisoformat(request.args.get('from','')); last=date.fromisoformat(request.args.get('to',''))
            if not 0<=(last-first).days<=550: abort(400)
        except ValueError: abort(400)
        with db() as c:
            conn=connection(c)
            own={r['id']:dict(r) for r in c.execute('SELECT * FROM events WHERE owner=?',(session['uid'],))}
            links=[]
            for row in c.execute('SELECT * FROM calendar_links WHERE owner=? AND connection=?',(session['uid'],conn['connection'])):
                link=dict(row); event=own.get(row['event_id'])
                link['event']=output(event,row['projection_day']) if event else None
                links.append(link)
            events=[]
            for event in own.values():
                seen=set()
                for year in range(first.year,last.year+1):
                    for start,end in event_spans(event,year):
                        if start in seen or start>last.isoformat() or end<first.isoformat(): continue
                        seen.add(start)
                        projection=bool(event['annual'] or event['calendar_type']=='lunar')
                        events.append(output(event,start if projection else '',year))
        return jsonify(events=events,links=links,visibility=conn['visibility'],user_id=session['uid'])

    @app.post('/api/calendar-sync/item')
    @required
    def sync_item():
        d=request.get_json() or {}; key=d.get('external_key',''); action=d.get('action')
        if not isinstance(key,str) or not re.fullmatch(r'[nr]:[0-9]+(?::[0-9]+)?',key): abort(400)
        native_hash=d.get('native_hash','')
        if not isinstance(native_hash,str) or not re.fullmatch(r'[a-f0-9]{64}',native_hash): abort(400)
        with db() as c:
            c.execute('BEGIN IMMEDIATE'); conn=connection(c)
            link=c.execute('SELECT * FROM calendar_links WHERE owner=? AND connection=? AND external_key=?',
                           (session['uid'],conn['connection'],key)).fetchone()
            event=c.execute('SELECT * FROM events WHERE id=? AND owner=?',
                            ((link['event_id'] if link else d.get('event_id')),session['uid'])).fetchone()
            if action in ('import','attach','rebind') and link:
                # Idempotent create/attach: return current state without overwriting it.
                return jsonify(ok=True,event=output(event,link['projection_day']) if event else None)
            if action not in ('import','attach','update','delete','checkpoint','restore','rebind'): abort(400)
            replaced=None
            if action=='rebind':
                replaced=c.execute('SELECT * FROM calendar_links WHERE owner=? AND connection=? AND external_key=?',
                                   (session['uid'],conn['connection'],d.get('replaces_key'))).fetchone()
                if not replaced or not event or replaced['event_id']!=event['id'] or key.startswith('r:'): abort(409)
            if action=='restore':
                if not link or event or d.get('revision')!=link['server_revision'] or link['mode']=='projection': abort(409)
            elif action!='import':
                if not event: abort(409)
                if d.get('revision')!=revision(event): abort(409)
            if action in ('update','delete','checkpoint') and not link: abort(409)
            if action in ('update','delete') and link['mode']=='projection': abort(409)
            if action=='delete':
                c.execute('DELETE FROM events WHERE id=? AND owner=?',(event['id'],session['uid']))
                return jsonify(ok=True,event=None)
            if action in ('import','update','restore'):
                payload=d.get('event')
                if not isinstance(payload,dict): abort(400)
                title=payload.get('title',''); note=payload.get('note',''); clock=payload.get('clock','')
                start=payload.get('day',''); end=payload.get('end_day',start)
                try:
                    if not 0<=(calendar_date(end,'solar')-calendar_date(start,'solar')).days<=365: abort(400)
                except (ValueError,TypeError): abort(400)
                if not isinstance(title,str) or not 1<=len(title)<=100 or not isinstance(note,str) or len(note)>2000: abort(400)
                if not isinstance(clock,str) or (clock and not re.fullmatch(r'([01]\d|2[0-3]):[0-5]\d',clock)): abort(400)
                if action in ('import','restore'):
                    readonly=key.startswith('r:')
                    eid=next_event_id(c)
                    c.execute('INSERT INTO events(id,title,day,end_day,clock,note,owner,visibility,sync_readonly) VALUES(?,?,?,?,?,?,?,?,?)',
                              (eid,title,start,end,clock,note,session['uid'],'private' if action=='restore' else conn['visibility'],int(readonly)))
                else:
                    if event['annual'] or event['calendar_type']!='solar': abort(409)
                    eid=event['id']
                    # Native payload cannot change owner or widen visibility.
                    c.execute('UPDATE events SET title=?,day=?,end_day=?,clock=?,note=? WHERE id=?',
                              (title,start,end,clock,note,eid))
                event=c.execute('SELECT * FROM events WHERE id=?',(eid,)).fetchone()
            mode='occurrence' if key.startswith('r:') else 'normal'; projection_day=''
            if action in ('attach','rebind'):
                projection_day=d.get('projection_day','')
                if not isinstance(projection_day,str): abort(400)
                if projection_day:
                    try: date.fromisoformat(projection_day)
                    except ValueError: abort(400)
                if event['annual'] or event['calendar_type']=='lunar':
                    if not output(event,projection_day) or not projection_day: abort(400)
                    mode='projection'
                elif projection_day: abort(400)
                if replaced:
                    if projection_day!=replaced['projection_day']: abort(409)
                    c.execute('UPDATE calendar_links SET event_id=NULL WHERE owner=? AND connection=? AND external_key=?',
                              (session['uid'],conn['connection'],replaced['external_key']))
            if link:
                c.execute('UPDATE calendar_links SET event_id=?,native_hash=?,server_revision=? WHERE owner=? AND connection=? AND external_key=?',
                          (event['id'],native_hash,revision(event),session['uid'],conn['connection'],key))
                projection_day=link['projection_day']
            else:
                # One native counterpart for each event/occurrence in a connection.
                duplicate=c.execute('SELECT 1 FROM calendar_links WHERE owner=? AND connection=? AND event_id=? AND projection_day=?',
                                    (session['uid'],conn['connection'],event['id'],projection_day)).fetchone()
                if duplicate: abort(409)
                c.execute('INSERT INTO calendar_links VALUES(?,?,?,?,?,?,?,?)',
                          (session['uid'],conn['connection'],key,event['id'],mode,projection_day,native_hash,revision(event)))
        return jsonify(ok=True,event=output(event,projection_day))
