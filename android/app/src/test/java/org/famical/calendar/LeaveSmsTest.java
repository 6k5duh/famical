package org.famical.calendar;

import static org.junit.Assert.*;

import android.content.Context;
import java.time.LocalDate;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LeaveSmsTest {
  private static final String BODY =
      "[Web발신]\n[휴가승인 요청]\n신청자: 강지은\n2026-11-19~2026-11-19\n연차/월간연차\n급여명세서앱에서 승인";

  @Test
  public void requestedLeaveNamesAndPhotoDescriptionsBecomeShortTitles() throws Exception {
    String[][] cases = {
      {"연차/월간연차", "년차"},
      {"월간연차", "년차"},
      {"생리휴가", "생휴"},
      {"경조휴가", "경조"},
      {"향군(예비군)", "향군"},
      {"유급", "유급"},
      {"유급(근속휴가, 대체휴가등)", "유급"},
      {"무급", "무급"},
      {"산전후휴가", "출산"},
      {"가족돌봄휴가", "돌봄"},
      {"유계결근", "유결"},
      {"유계결근(연차/월간연차 잔여 부족)", "유결"},
      {" 향군 （예비군） ", "향군"}
    };
    for (String[] entry : cases) {
      JSONObject event = LeaveSms.parse("0522195000", BODY.replace("연차/월간연차", entry[0]));
      assertNotNull(entry[0], event);
      assertEquals(entry[0], "강지은 " + entry[1], event.getString("title"));
      assertEquals("work", event.getString("category"));
      assertEquals("private", event.getString("visibility"));
    }
  }

  @Test
  public void descriptionsDoNotCauseDuplicatesButDifferentLeaveTypesDo() throws Exception {
    NativeStore store = signedInStore();
    store.enableLeaveSms(true);
    assertTrue(store.importLeaveSms(LeaveSms.parse("0522195000", BODY.replace("연차/월간연차", "유급"))));
    assertFalse(
        store.importLeaveSms(
            LeaveSms.parse("0522195000", BODY.replace("연차/월간연차", "유급(근속휴가, 대체휴가등)"))));
    assertTrue(store.importLeaveSms(LeaveSms.parse("0522195000", BODY.replace("연차/월간연차", "무급"))));
    store.close();
  }

  @Test
  public void previouslyImportedLongNameIsNotImportedAgainAfterUpgrade() throws Exception {
    NativeStore store = signedInStore();
    store.enableLeaveSms(true);
    JSONObject old = LeaveSms.parse("0522195000", BODY).put("title", "강지은 생리휴가");
    assertTrue(store.importLeaveSms(old));
    assertFalse(
        store.importLeaveSms(LeaveSms.parse("0522195000", BODY.replace("연차/월간연차", "생리휴가"))));
    assertEquals(1, NativeStore.array(store.view(), "pending").length());
    store.close();
  }

  @Test
  public void exactExampleBecomesPrivateWorkEvent() throws Exception {
    JSONObject e = LeaveSms.parse("052-219-5000", BODY);
    assertEquals("강지은 년차", e.getString("title"));
    assertEquals("2026-11-19", e.getString("day"));
    assertEquals(e.getString("day"), e.getString("end_day"));
    assertEquals("work", e.getString("category"));
    assertEquals("private", e.getString("visibility"));
    assertEquals("solar", e.getString("calendar_type"));
    assertFalse(e.getBoolean("annual"));
    assertTrue(e.getString("note").contains("승인 완료 여부는 확인되지 않았습니다"));
  }

  @Test
  public void spacesInternationalSenderAndMultipartConcatenationAreAccepted() throws Exception {
    String body =
        BODY.replace("[Web발신]", "[Web 발신]")
            .replace("[휴가승인 요청]", "[휴가 승인 요청]")
            .replace("\n", "\r\n");
    assertNotNull(LeaveSms.parse("+82 52-219-5000", body.substring(0, 30) + body.substring(30)));
    assertEquals(
        LeaveSms.key(LeaveSms.parse("0522195000", BODY)),
        LeaveSms.key(LeaveSms.parse("+82522195000", body)));
  }

  @Test
  public void unrelatedSendersAndMessagesAreIgnored() throws Exception {
    assertNull(LeaveSms.parse("010-219-5000", BODY));
    assertNull(LeaveSms.parse("10522195000", BODY));
    assertNull(LeaveSms.parse("0522195000", BODY.replace("[휴가승인 요청]", "[휴가승인 완료]")));
    assertNull(LeaveSms.parse("0522195000", BODY.replace("[Web발신]", "")));
    assertNull(LeaveSms.parse("0522195000", BODY.replace("신청자:", "이름:")));
  }

  @Test
  public void malformedAndReversedAndExcessiveDatesAreIgnored() throws Exception {
    assertNull(LeaveSms.parse("0522195000", BODY.replace("2026-11-19", "2026-02-30")));
    assertNull(
        LeaveSms.parse(
            "0522195000", BODY.replace("2026-11-19~2026-11-19", "2026-11-24~2026-11-20")));
    assertNull(
        LeaveSms.parse(
            "0522195000", BODY.replace("2026-11-19~2026-11-19", "2026-01-01~2027-12-31")));
  }

  @Test
  public void rangeKeepsEndpointsAndUsesWeekdayCounting() throws Exception {
    JSONObject e =
        LeaveSms.parse(
            "0522195000", BODY.replace("2026-11-19~2026-11-19", "2026-11-20~2026-11-24"));
    assertEquals("2026-11-20", e.getString("day"));
    assertEquals("2026-11-24", e.getString("end_day"));
    assertEquals(
        3,
        NativeCalendar.weekdays(
            LocalDate.parse(e.getString("day")), LocalDate.parse(e.getString("end_day"))));
    assertFalse(NativeCalendar.weekday(LocalDate.of(2026, 11, 21)));
    assertFalse(NativeCalendar.weekday(LocalDate.of(2026, 11, 22)));
  }

  @Test
  public void receiverRequiresSystemSmsBroadcastAndUsesCalendarLabel() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    var info =
        context
            .getPackageManager()
            .getReceiverInfo(new android.content.ComponentName(context, LeaveSmsReceiver.class), 0);
    assertEquals("android.permission.BROADCAST_SMS", info.permission);
    assertTrue(info.exported);
    assertEquals("달력", context.getString(R.string.app_name));
  }

  private NativeStore signedInStore() throws Exception {
    NativeStore store = new NativeStore(RuntimeEnvironment.getApplication());
    var state = NativeStore.class.getDeclaredField("state");
    state.setAccessible(true);
    state.set(
        store, new JSONObject().put("user", new JSONObject().put("id", 7).put("name", "사용자")));
    return store;
  }

  @Test
  public void disabledDoesNotImportAndEnabledPersistsOutboxAndDedupAcrossRestart()
      throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    NativeStore store = signedInStore();
    JSONObject e = LeaveSms.parse("0522195000", BODY);
    assertFalse(store.importLeaveSms(e));
    store.enableLeaveSms(true);
    assertTrue(store.importLeaveSms(e));
    assertFalse(store.importLeaveSms(e));
    assertEquals(1, NativeStore.array(store.view(), "pending").length());
    assertEquals(7, store.items().values().iterator().next().getInt("owner"));
    store.close();
    store = new NativeStore(c);
    assertFalse(store.importLeaveSms(e));
    assertEquals(1, NativeStore.array(store.view(), "pending").length());
    store.enableLeaveSms(false);
    assertFalse(store.importLeaveSms(LeaveSms.parse("0522195000", BODY.replace("강지은", "다른이름"))));
    store.close();
  }

  @Test
  public void separateApplicantsAndDatesAreNotDuplicates() throws Exception {
    NativeStore store = signedInStore();
    store.enableLeaveSms(true);
    assertTrue(store.importLeaveSms(LeaveSms.parse("0522195000", BODY)));
    assertTrue(store.importLeaveSms(LeaveSms.parse("0522195000", BODY.replace("강지은", "김가족"))));
    assertTrue(
        store.importLeaveSms(
            LeaveSms.parse("0522195000", BODY.replace("2026-11-19", "2026-11-20"))));
    assertEquals(3, NativeStore.array(store.view(), "pending").length());
    store.close();
  }

  @Test
  public void loggedOutCannotEnableOrImport() throws Exception {
    NativeStore store = new NativeStore(RuntimeEnvironment.getApplication());
    assertFalse(store.importLeaveSms(LeaveSms.parse("0522195000", BODY)));
    assertThrows(IllegalStateException.class, () -> store.enableLeaveSms(true));
    store.close();
  }
}
