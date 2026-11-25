package org.famical.calendar;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.*;
import android.webkit.CookieManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import org.json.*;

/** A private SQLite transaction stores the snapshot and durable outbox together. */
class NativeStore extends SQLiteOpenHelper {
  private static NativeStore instance;

  static synchronized NativeStore get(Context context) {
    String raw =
        context
            .getSharedPreferences("famical", 0)
            .getString("server", ServerAddress.DEFAULT_SERVER);
    String server = ServerAddress.normalize(raw.isEmpty() ? ServerAddress.DEFAULT_SERVER : raw);
    if (instance == null || !instance.server.equals(server))
      instance = new NativeStore(context.getApplicationContext());
    return instance;
  }

  private final SharedPreferences prefs;
  private final Context context;
  private JSONObject state;
  private boolean syncing;
  private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  Runnable changed = () -> {};
  String status = "기기에 저장됨";
  final String server;

  NativeStore(Context context) {
    super(context, "native-calendar.db", null, 1);
    this.context = context.getApplicationContext();
    prefs = context.getSharedPreferences("famical", 0);
    String raw = prefs.getString("server", ServerAddress.DEFAULT_SERVER);
    server = ServerAddress.normalize(raw.isEmpty() ? ServerAddress.DEFAULT_SERVER : raw);
    try (Cursor cursor =
        getReadableDatabase()
            .rawQuery("SELECT value FROM state WHERE server=?", new String[] {server})) {
      state = cursor.moveToFirst() ? new JSONObject(cursor.getString(0)) : new JSONObject();
    } catch (JSONException e) {
      throw new IllegalStateException("기기 저장 파일을 읽을 수 없습니다. 앱 데이터를 보관하고 확인하세요.", e);
    }
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE state(server TEXT PRIMARY KEY,value TEXT NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

  synchronized JSONObject view() {
    return copy(state);
  }

  static JSONObject copy(JSONObject value) {
    try {
      return new JSONObject(value.toString());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private void persist() {
    ContentValues values = new ContentValues();
    values.put("server", server);
    values.put("value", state.toString());
    getWritableDatabase()
        .insertWithOnConflict("state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
  }

  private void notifyChanged() {
    main.post(() -> changed.run());
  }

  static JSONArray array(JSONObject value, String key) {
    JSONArray result = value.optJSONArray(key);
    return result == null ? new JSONArray() : result;
  }

  synchronized LinkedHashMap<String, JSONObject> items() {
    LinkedHashMap<String, JSONObject> result = new LinkedHashMap<>();
    JSONArray events = array(state, "events"), pending = array(state, "pending");
    for (int i = 0; i < events.length(); i++) {
      JSONObject e = events.optJSONObject(i);
      result.put("s" + e.optLong("id"), copy(e));
    }
    for (int i = 0; i < pending.length(); i++) {
      JSONObject p = pending.optJSONObject(i);
      if (!p.isNull("id")) result.remove("s" + p.optLong("id"));
      if (!p.optString("action").equals("delete"))
        result.put(p.optString("key"), copy(p.optJSONObject("event")));
    }
    return result;
  }

  synchronized void save(String key, JSONObject event, boolean delete) throws Exception {
    JSONArray pending = array(state, "pending");
    JSONObject old = null;
    for (int i = 0; i < pending.length(); i++)
      if (pending.getJSONObject(i).optString("key").equals(key)) {
        old = pending.getJSONObject(i);
        break;
      }
    if (old != null && old.optBoolean("conflict"))
      throw new IllegalStateException("설정의 동기화 충돌 항목을 먼저 확인하세요.");
    JSONObject p =
        new JSONObject()
            .put("key", key == null ? UUID.randomUUID().toString() : key)
            .put("operation", UUID.randomUUID().toString())
            .put("event", copy(event))
            .put("action", delete ? "delete" : "save");
    long id = old == null ? event.optLong("id") : old.optLong("id");
    JSONObject alias = state.optJSONObject("aliases");
    if (id == 0 && key != null && alias != null) id = alias.optLong(key);
    String revision = old == null ? event.optString("revision") : old.optString("revision");
    if (old == null && event.optLong("id") == 0 && id != 0)
      for (int i = 0; i < array(state, "events").length(); i++) {
        JSONObject remote = array(state, "events").getJSONObject(i);
        if (remote.optLong("id") == id) revision = remote.optString("revision");
      }
    p.put("id", id == 0 ? JSONObject.NULL : id)
        .put("revision", revision)
        .put(
            "creation_key",
            old == null ? UUID.randomUUID().toString() : old.optString("creation_key"));
    if (old != null) remove(pending, old.optString("key"));
    pending.put(p);
    state.put("pending", pending);
    persist();
    NativeSyncJob.schedule(context, true);
    notifyChanged();
  }

  synchronized void enableLeaveSms(boolean enabled) throws Exception {
    if (state.optJSONObject("user") == null) throw new IllegalStateException("먼저 로그인하세요.");
    state.put("smsEnabled", enabled);
    persist();
  }

  synchronized boolean importLeaveSms(JSONObject event) throws Exception {
    JSONObject user = state.optJSONObject("user");
    if (!state.optBoolean("smsEnabled") || user == null) return false;
    NativeCalendar.get(context).validate(event);
    String key = LeaveSms.key(event);
    JSONObject imported = state.optJSONObject("smsImported");
    if (imported == null) imported = new JSONObject();
    if (imported.has(key)) return false;
    JSONObject before = copy(state);
    event = copy(event).put("owner", user.getLong("id")).put("author", user.optString("name"));
    imported.put(key, true);
    state.put("smsImported", imported);
    state.put(
        "smsLastImport",
        java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm")));
    try {
      // The deduplication record and outbox are persisted in the same SQLite write.
      save(key, event, false);
    } catch (Exception e) {
      state = before;
      throw e;
    }
    return true;
  }

  private static void remove(JSONArray items, String key) {
    for (int i = items.length() - 1; i >= 0; i--)
      if (items.optJSONObject(i).optString("key").equals(key)) items.remove(i);
  }

  synchronized void preference(String team, boolean on) throws Exception {
    state.getJSONObject("user").put("shift_team", team).put("shift_on", on ? 1 : 0);
    state.put("preferencesPending", true);
    persist();
    NativeSyncJob.schedule(context, true);
    notifyChanged();
  }

  JSONObject request(String path, String method, JSONObject body, String csrf) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(server + "api/" + path).openConnection();
    connection.setConnectTimeout(8000);
    connection.setReadTimeout(12000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod(method);
    String cookie = CookieManager.getInstance().getCookie(server);
    if (cookie != null) connection.setRequestProperty("Cookie", cookie);
    try {
      if (body != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("X-CSRF-Token", csrf == null ? "" : csrf);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
      int code = connection.getResponseCode();
      for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet())
        if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie"))
          for (String value : header.getValue())
            CookieManager.getInstance().setCookie(server, value);
      CookieManager.getInstance().flush();
      InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      if (stream != null)
        try (InputStream in = stream) {
          byte[] buffer = new byte[4096];
          int n;
          while ((n = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, n);
            if (bytes.size() > 16_000_000) throw new IOException("일정 데이터가 너무 많습니다.");
          }
        }
      JSONObject result;
      try {
        result = new JSONObject(bytes.toString("UTF-8"));
      } catch (JSONException e) {
        throw new IOException("서버 응답을 확인하세요. NAS v2.0.0 이상이 필요합니다.");
      }
      if (code < 200 || code >= 300)
        throw new IOException(result.optString("error", "서버 응답 오류") + " (HTTP " + code + ")");
      return result;
    } finally {
      connection.disconnect();
    }
  }

  void login(String username, String password, java.util.function.Consumer<String> done) {
    worker.execute(
        () -> {
          String error = "";
          try {
            JSONObject me = request("me", "GET", null, null);
            request(
                "login",
                "POST",
                new JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .put("remember", true),
                me.getString("csrf"));
            JSONObject user = request("me", "GET", null, null).getJSONObject("user");
            synchronized (this) {
              JSONObject prior = state.optJSONObject("user");
              if (prior != null && prior.optLong("id") != user.optLong("id"))
                throw new IOException("저장된 계정과 다릅니다. 먼저 로그아웃하세요.");
              if (state.optBoolean("preferencesPending") && prior != null)
                user.put("shift_team", prior.optString("shift_team"))
                    .put("shift_on", prior.optInt("shift_on"));
              state.put("user", user);
              persist();
            }
          } catch (Exception e) {
            error = e.getMessage();
          }
          String result = error;
          main.post(
              () -> {
                notifyChanged();
                done.accept(result);
                if (result.isEmpty()) sync();
              });
        });
  }

  void sync() {
    sync(null);
  }

  synchronized void sync(Runnable done) {
    if (state.optJSONObject("user") == null) {
      if (done != null) main.post(done);
      return;
    }
    if (syncing) {
      if (done != null) worker.execute(() -> main.post(done));
      return;
    }
    syncing = true;
    worker.execute(
        () -> {
          synchronize();
          if (done != null) main.post(done);
        });
  }

  void synchronize() {

    status = "동기화 중…";
    notifyChanged();
    try {
      JSONObject me = request("me", "GET", null, null), user = me.optJSONObject("user");
      String csrf = me.getString("csrf");
      if (user == null || user.optLong("id") != view().getJSONObject("user").getLong("id"))
        throw new IOException("로그인이 만료되었습니다. 설정에서 같은 계정으로 다시 로그인하세요.");
      JSONObject initial = view();
      if (initial.optBoolean("preferencesPending")) {
        JSONObject profile = initial.getJSONObject("user");
        request(
            "preferences",
            "PUT",
            new JSONObject()
                .put("shift_team", profile.getString("shift_team"))
                .put("shift_on", profile.optInt("shift_on") != 0),
            csrf);
        synchronized (this) {
          JSONObject current = state.getJSONObject("user");
          if (current.optString("shift_team").equals(profile.optString("shift_team"))
              && current.optInt("shift_on") == profile.optInt("shift_on"))
            state.put("preferencesPending", false);
          persist();
        }
      }
      JSONArray pending = array(view(), "pending");
      for (int i = 0; i < pending.length(); i++) {
        JSONObject p = pending.getJSONObject(i);
        if (p.optBoolean("conflict")) continue;
        JSONObject wire =
            new JSONObject()
                .put("operation", p.getString("operation"))
                .put("creation_key", p.optString("creation_key"))
                .put("id", p.opt("id"))
                .put("revision", p.optString("revision"))
                .put("action", p.optString("action"))
                .put("event", p.getJSONObject("event"));
        JSONObject result;
        try {
          result = request("native-sync", "POST", wire, csrf);
        } catch (Exception e) {
          synchronized (this) {
            JSONArray current = array(state, "pending");
            for (int k = 0; k < current.length(); k++)
              if (current.getJSONObject(k).optString("key").equals(p.optString("key")))
                current.getJSONObject(k).put("error", e.getMessage());
            state.put("pending", current);
            persist();
          }
          throw e;
        }
        synchronized (this) {
          JSONArray current = array(state, "pending");
          for (int k = 0; k < current.length(); k++) {
            JSONObject item = current.getJSONObject(k);
            if (!item.optString("key").equals(p.optString("key"))) continue;
            if (result.optBoolean("conflict"))
              item.put("conflict", true)
                  .put("current", result.opt("current"))
                  .put("error", "다른 기기에서 수정·삭제되었습니다.");
            else {
              JSONObject event = result.optJSONObject("event");
              if (event != null) {
                JSONArray events = array(state, "events");
                for (int j = events.length() - 1; j >= 0; j--)
                  if (events.getJSONObject(j).optLong("id") == event.optLong("id"))
                    events.remove(j);
                events.put(event);
                state.put("events", events);
                JSONObject aliases = state.optJSONObject("aliases");
                if (aliases == null) aliases = new JSONObject();
                aliases.put(item.getString("key"), event.getLong("id"));
                state.put("aliases", aliases);
              } else {
                JSONArray events = array(state, "events");
                for (int j = events.length() - 1; j >= 0; j--)
                  if (events.getJSONObject(j).optLong("id") == p.optLong("id")) events.remove(j);
                state.put("events", events);
              }
              if (item.optString("operation").equals(p.optString("operation"))) current.remove(k);
              else {
                item.put("id", event == null ? JSONObject.NULL : event.getLong("id"))
                    .put("revision", event == null ? "" : event.getString("revision"));
                if (event != null) item.getJSONObject("event").put("id", event.getLong("id"));
              }
            }
            break;
          }
          state.put("pending", current);
          persist();
        }
      }
      JSONObject snapshot =
          request("native-sync?version=" + view().optString("version"), "GET", null, null);
      if (snapshot.optInt("protocol") != 1) throw new IOException("NAS v2.0.0 이상으로 업데이트하세요.");
      synchronized (this) {
        if (!snapshot.optBoolean("unchanged")) state.put("events", snapshot.getJSONArray("events"));
        state.put("version", snapshot.getString("version"));
        if (!state.optBoolean("preferencesPending"))
          state.put("user", snapshot.getJSONObject("user"));
        state.put(
            "synced",
            java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm")));
        persist();
        AccountPreferences.store(prefs, state.getJSONObject("user"));
        status =
            array(state, "pending").length() == 0
                ? "동기화 " + state.optString("synced")
                : "기기 저장 · 확인 필요 " + array(state, "pending").length() + "건";
      }
    } catch (Exception e) {
      status = "오프라인 · " + e.getMessage();
    } finally {
      synchronized (this) {
        syncing = false;
      }
      updateWidget();
      notifyChanged();
    }
  }

  synchronized void resolve(String key, boolean local) throws Exception {
    JSONArray pending = array(state, "pending");
    for (int i = 0; i < pending.length(); i++) {
      JSONObject p = pending.getJSONObject(i);
      if (!key.equals(p.optString("key"))) continue;
      if (!local) {
        if (p.optBoolean("conflict") && !p.isNull("id")) {
          JSONArray events = array(state, "events");
          for (int n = events.length() - 1; n >= 0; n--)
            if (events.getJSONObject(n).optLong("id") == p.optLong("id")) events.remove(n);
          if (p.optJSONObject("current") != null) events.put(p.getJSONObject("current"));
          state.put("events", events);
        }
        pending.remove(i);
        break;
      }
      JSONObject current = p.optJSONObject("current");
      if (current == null) p.put("creation_key", UUID.randomUUID().toString());
      p.put("id", current == null ? JSONObject.NULL : current.getLong("id"))
          .put("revision", current == null ? "" : current.optString("revision"))
          .put("operation", UUID.randomUUID().toString())
          .put("conflict", false)
          .put("error", "");
      p.getJSONObject("event").put("id", current == null ? 0 : current.getLong("id"));
      if (current == null && p.optString("action").equals("delete")) pending.remove(i);
      break;
    }
    state.put("pending", pending);
    persist();
    NativeSyncJob.schedule(context, true);
    notifyChanged();
  }

  void updateWidget() {
    try {
      JSONObject user = view().optJSONObject("user");
      if (user == null) return;
      java.time.LocalDate today = java.time.LocalDate.now();
      java.time.YearMonth month = java.time.YearMonth.from(today);
      JSONArray projected = new JSONArray();
      for (JSONObject event : items().values())
        for (java.time.LocalDate[] span :
            NativeCalendar.get(context).spans(event, today.getYear())) {
          java.time.LocalDate start = span[0].isBefore(month.atDay(1)) ? month.atDay(1) : span[0],
              end = span[1].isAfter(month.atEndOfMonth()) ? month.atEndOfMonth() : span[1];
          for (java.time.LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (event.optString("category").equals("work")
                && !span[0].equals(span[1])
                && !NativeCalendar.weekday(day)) continue;
            JSONObject copy =
                NativeStore.copy(event).put("day", day.toString()).put("end_day", day.toString());
            projected.put(copy);
          }
        }
      WidgetEvents.Snapshot snapshot =
          WidgetEvents.parse(
              projected.toString(), today, prefs.getBoolean("widget_private", false));
      AccountPreferences.store(prefs, user);
      prefs
          .edit()
          .putString("widget_lines", snapshot.todayLines)
          .putString("widget_day", today.toString())
          .putString("widget_month_events", snapshot.monthEvents)
          .putString("widget_month", month.toString())
          .putString("widget_time", "기기 저장 일정")
          .apply();
      CalendarWidget.renderCached(context);
    } catch (Exception ignored) {
      /* Existing widget cache remains readable. */
    }
  }

  void logout(java.util.function.Consumer<String> done) {
    if (array(view(), "pending").length() > 0) {
      done.accept("미전송 일정이 있습니다. 동기화와 충돌 확인 후 로그아웃하세요.");
      return;
    }
    worker.execute(
        () -> {
          try {
            JSONObject me = request("me", "GET", null, null);
            request("logout", "POST", new JSONObject(), me.optString("csrf"));
          } catch (Exception ignored) {
          }
          synchronized (this) {
            if (array(state, "pending").length() > 0) {
              main.post(() -> done.accept("미전송 일정이 있습니다. 동기화 후 다시 시도하세요."));
              return;
            }
            state = new JSONObject();
            persist();
          }
          CookieManager.getInstance().setCookie(server, "session=; Max-Age=0; Path=/; Secure");
          CookieManager.getInstance().flush();
          prefs
              .edit()
              .putBoolean("sync_enabled", false)
              .remove("widget_lines")
              .remove("widget_month_events")
              .remove("widget_day")
              .remove("widget_month")
              .apply();
          main.post(
              () -> {
                CalendarWidget.renderCached(context);
                notifyChanged();
                done.accept("");
              });
        });
  }
}
