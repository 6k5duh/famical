package org.famical.calendar;

import static org.junit.Assert.*;

import android.content.*;
import java.time.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NativeOfflineTest {
  Context context;

  static class FakeStore extends NativeStore {
    boolean offline = true, conflict = false;
    JSONObject remote, profile;
    int saves;

    FakeStore(Context context) throws Exception {
      super(context);
      profile =
          new JSONObject().put("id", 1).put("name", "가족").put("shift_on", 1).put("shift_team", "B");
    }

    @Override
    JSONObject request(String path, String method, JSONObject body, String csrf) throws Exception {
      if (offline) throw new java.io.IOException("서버 연결 끊김");
      if (path.equals("me")) return new JSONObject().put("user", profile).put("csrf", "test");
      if (path.startsWith("native-sync?"))
        return new JSONObject()
            .put("protocol", 1)
            .put("version", "v" + saves)
            .put("user", profile)
            .put("events", remote == null ? new JSONArray() : new JSONArray().put(remote));
      if (conflict) return new JSONObject().put("conflict", true).put("current", remote);
      if (body.optString("action").equals("delete") && body.isNull("id"))
        return new JSONObject().put("ok", true).put("event", JSONObject.NULL);
      saves++;
      if (body.optString("action").equals("delete")) remote = null;
      else
        remote =
            NativeStore.copy(body.getJSONObject("event"))
                .put("id", 10)
                .put("owner", 1)
                .put("revision", "r" + saves);
      return new JSONObject()
          .put("ok", true)
          .put("event", remote == null ? JSONObject.NULL : remote);
    }
  }

  @Before
  public void setup() throws Exception {
    context = RuntimeEnvironment.getApplication();
    context.deleteDatabase("native-calendar.db");
    context
        .getSharedPreferences("famical", 0)
        .edit()
        .putString("server", "https://calendar.example.test/")
        .commit();
    NativeStore db = new NativeStore(context);
    JSONObject state =
        new JSONObject().put("user", new JSONObject().put("id", 1).put("name", "가족"));
    ContentValues row = new ContentValues();
    row.put("server", "https://calendar.example.test/");
    row.put("value", state.toString());
    db.getWritableDatabase().insert("state", null, row);
    db.close();
  }

  JSONObject event() throws Exception {
    return new JSONObject()
        .put("title", "오프라인 교육")
        .put("day", "2026-11-20")
        .put("end_day", "2026-11-24")
        .put("category", "work")
        .put("owner", 1);
  }

  @Test
  public void offlineEditSurvivesRestartAndSyncsAfterRecovery() throws Exception {
    FakeStore store = new FakeStore(context);
    store.save(null, event(), false);
    store.synchronize();
    assertEquals(1, store.items().size());
    assertEquals(1, NativeStore.array(store.view(), "pending").length());
    FakeStore restarted = new FakeStore(context);
    assertEquals("오프라인 교육", restarted.items().values().iterator().next().getString("title"));
    restarted.offline = false;
    restarted.synchronize();
    assertEquals(0, NativeStore.array(restarted.view(), "pending").length());
    assertEquals(1, restarted.saves);
    assertTrue(restarted.items().containsKey("s10"));
    restarted.synchronize();
    assertEquals(1, restarted.saves);
  }

  @Test
  public void concurrentRemoteEditIsHeldForChoice() throws Exception {
    FakeStore store = new FakeStore(context);
    store.save(null, event(), false);
    store.offline = false;
    store.synchronize();
    JSONObject local = store.items().get("s10");
    local.put("title", "내 수정");
    store.save("s10", local, false);
    store.remote.put("title", "다른 기기 수정");
    store.conflict = true;
    store.synchronize();
    JSONObject pending = NativeStore.array(store.view(), "pending").getJSONObject(0);
    assertTrue(pending.getBoolean("conflict"));
    assertEquals("내 수정", store.items().get("s10").getString("title"));
    store.resolve("s10", false);
    assertEquals("다른 기기 수정", store.items().get("s10").getString("title"));
  }

  @Test
  public void deletingUnsentItemDoesNotSendInvalidDelete() throws Exception {
    FakeStore store = new FakeStore(context);
    store.save(null, event(), false);
    String key = store.items().keySet().iterator().next();
    store.save(key, store.items().get(key), true);
    store.offline = false;
    store.synchronize();
    assertEquals(0, store.saves);
    assertEquals(0, store.items().size());
  }

  @Test
  public void editorOpenedBeforeCreationAckUsesServerId() throws Exception {
    FakeStore store = new FakeStore(context);
    store.save(null, event(), false);
    String key = store.items().keySet().iterator().next();
    JSONObject stale = store.items().get(key);
    store.offline = false;
    store.synchronize();
    stale.put("title", "열어둔 편집기 수정");
    store.save(key, stale, false);
    store.synchronize();
    assertEquals(1, store.items().size());
    assertEquals("열어둔 편집기 수정", store.items().get("s10").getString("title"));
    assertEquals(0, NativeStore.array(store.view(), "pending").length());
  }

  @Test
  public void calendarMatchesKoreanDatesAndWeekdayExample() throws Exception {
    NativeCalendar cal = new NativeCalendar(context);
    assertEquals(LocalDate.of(2026, 2, 17), cal.convert("2026-01-01", true, false));
    assertEquals(LocalDate.of(2027, 2, 7), cal.convert("2027-01-01", true, false));
    assertEquals(
        3, NativeCalendar.weekdays(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 24)));
    assertEquals("중복휴일", cal.holiday(LocalDate.of(2027, 5, 3)));
    JSONObject e = event().put("day", "20261120").put("end_day", "20261124");
    cal.validate(e);
    assertEquals("2026-11-20", e.getString("day"));
    assertEquals("(음 1.1)", cal.lunar(LocalDate.of(2026, 2, 17)));
  }
}
