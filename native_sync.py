"""Offline clients exchange source events, with atomic idempotent writes."""
import hashlib
import json
import re
from flask import request, session, jsonify, abort

FIELDS = ('title', 'day', 'end_day', 'clock', 'note', 'calendar_type', 'annual',
          'leap', 'visibility', 'color', 'category', 'owner', 'sync_readonly')


def revision(event):
    return hashlib.sha256(json.dumps({key: bool(event[key]) if key in ('annual', 'leap') else event[key] for key in FIELDS},
                                    sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def register_native_sync(app, db, required, user, write_event):
    with db() as connection:
        connection.execute('''CREATE TABLE IF NOT EXISTS native_receipts(
            owner INTEGER NOT NULL REFERENCES users(id), operation TEXT NOT NULL,
            digest TEXT NOT NULL, event_id INTEGER,
            PRIMARY KEY(owner, operation))''')

        connection.execute('''CREATE TABLE IF NOT EXISTS native_creations(
            owner INTEGER NOT NULL REFERENCES users(id), creation_key TEXT NOT NULL,
            event_id INTEGER REFERENCES events(id) ON DELETE SET NULL,
            PRIMARY KEY(owner, creation_key))''')

    def output(row):
        if row is None:
            return None
        event = dict(row)
        event['annual'] = bool(event['annual'])
        event['leap'] = bool(event['leap'])
        return {**event, 'revision': revision(event)}

    def visible(connection, event_id):
        return connection.execute("""SELECT e.*,u.name AS author FROM events e
            JOIN users u ON u.id=e.owner
            WHERE e.id=? AND (e.visibility='shared' OR e.owner=?)""",
                                  (event_id, session['uid'])).fetchone()

    @app.get('/api/native-sync')
    @required
    def native_snapshot():
        with db() as connection:
            rows = connection.execute("""SELECT e.*,u.name AS author FROM events e
                JOIN users u ON u.id=e.owner
                WHERE e.visibility='shared' OR e.owner=? ORDER BY e.id""",
                                      (session['uid'],)).fetchall()
        profile = dict(user())
        events = [output(row) for row in rows]
        version = hashlib.sha256(json.dumps([profile, [(e['id'], e['revision'], e['author']) for e in events]],
                                            sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        if request.args.get('version') == version:
            return jsonify(protocol=1, user=profile, version=version, unchanged=True)
        return jsonify(protocol=1, user=profile, version=version, events=events)

    @app.post('/api/native-sync')
    @required
    def native_mutation():
        data = request.get_json()
        if not isinstance(data, dict):
            abort(400)
        operation = data.get('operation', '')
        event_id = data.get('id')
        action = data.get('action')
        if not isinstance(operation, str) or not re.fullmatch(r'[a-zA-Z0-9-]{16,80}', operation):
            abort(400)
        if action not in ('save', 'delete') or (event_id is not None and
                                              (type(event_id) is not int or event_id <= 0)):
            abort(400)
        creation_key = data.get('creation_key', '')
        if event_id is None and (not isinstance(creation_key, str) or
                                 not re.fullmatch(r'[a-zA-Z0-9-]{16,80}', creation_key)):
            abort(400)
        payload = data.get('event', {})
        if not isinstance(payload, dict) or 'entries' in payload:
            abort(400)
        digest = hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        with db() as connection:
            connection.execute('BEGIN IMMEDIATE')
            receipt = connection.execute('SELECT * FROM native_receipts WHERE owner=? AND operation=?',
                                         (session['uid'], operation)).fetchone()
            if receipt:
                if receipt['digest'] != digest:
                    return jsonify(error='이미 처리된 작업 번호입니다.'), 409
                return jsonify(ok=True, event=output(visible(connection, receipt['event_id'])))
            if event_id is None:
                creation = connection.execute('SELECT event_id FROM native_creations WHERE owner=? AND creation_key=?',
                                              (session['uid'], creation_key)).fetchone()
                if creation:
                    # A previous create may have committed before its response was lost.
                    # A changed offline draft needs an explicit choice against that event.
                    return jsonify(conflict=True, current=output(visible(connection, creation['event_id'])))
                if action == 'delete':
                    connection.execute('INSERT INTO native_creations VALUES(?,?,NULL)', (session['uid'], creation_key))
                    connection.execute('INSERT INTO native_receipts VALUES(?,?,?,NULL)',
                                       (session['uid'], operation, digest))
                    return jsonify(ok=True, event=None)
            creating = event_id is None
            if event_id is not None:
                old = visible(connection, event_id)
                if old is None:
                    return jsonify(conflict=True, current=None)
                if old['owner'] != session['uid'] and not user()['admin']:
                    abort(403)
                if data.get('revision') != revision(old):
                    return jsonify(conflict=True, current=output(old))
            response = app.make_response(write_event(connection, event_id, payload,
                                                     'DELETE' if action == 'delete' else
                                                     'PUT' if event_id else 'POST'))
            if response.status_code >= 300:
                return response
            if action == 'save':
                event_id = response.get_json()['ids'][0]
            if creating:
                connection.execute('INSERT INTO native_creations VALUES(?,?,?)',
                                   (session['uid'], creation_key, event_id))
            connection.execute('INSERT INTO native_receipts VALUES(?,?,?,?)',
                               (session['uid'], operation, digest, event_id))
            return jsonify(ok=True, event=output(visible(connection, event_id)))
