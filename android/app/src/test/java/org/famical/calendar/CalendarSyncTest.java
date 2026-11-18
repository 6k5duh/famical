package org.famical.calendar;

import android.Manifest;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.provider.CalendarContract;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class CalendarSyncTest {
    Context context;Provider provider;Api api;String token="test-connection-12345";
    static final String REV="server-revision";
    static final class Api extends CalendarApi {
        JSONObject snapshot=new JSONObject(),user=new JSONObject();
        final List<JSONObject> writes=new ArrayList<>();
        Api() throws Exception {super("https://calendar.example.test/","session=test",()->true);user.put("id",1).put("shift_team","none").put("shift_on",1);snapshot.put("events",new JSONArray()).put("links",new JSONArray());}
        @Override JSONObject me() throws Exception {return new JSONObject().put("csrf","test").put("user",user);}
        @Override JSONObject request(String path,String method,JSONObject body) throws Exception {
            if(method.equals("GET"))return snapshot;
            writes.add(body);
            JSONObject event=body.optJSONObject("event");if(event==null)event=new JSONObject();
            event.put("id",999).put("revision",REV);
            return new JSONObject().put("ok",true).put("event",event);
        }
    }
    public static class Provider extends ContentProvider {
        final Map<Long,ContentValues> rows=new LinkedHashMap<>();long next=100;boolean unavailable;
        @Override public boolean onCreate(){return true;}
        @Override public String getType(Uri uri){return "vnd.android.cursor.dir/event";}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
            if(unavailable)return null;
            MatrixCursor cursor=new MatrixCursor(projection);String path=uri.getPath();
            if(path.equals("/calendars")) {Object[] row=new Object[projection.length];for(int i=0;i<row.length;i++)row[i]=projection[i].equals("_id")?1:projection[i].equals("account_type")?"test.samsung":"내 달력";cursor.addRow(row);return cursor;}
            if(path.startsWith("/instances"))return cursor;
            Long only=selection!=null&&selection.contains("AND _id=?")?Long.valueOf(args[1]):null;
            for(Map.Entry<Long,ContentValues> entry:rows.entrySet()) {
                if(only!=null&&!entry.getKey().equals(only))continue;
                Object[] row=new Object[projection.length];for(int i=0;i<row.length;i++)row[i]=entry.getValue().get(projection[i]);cursor.addRow(row);
            }return cursor;
        }
        @Override public Uri insert(Uri uri,ContentValues values) {long id=next++;ContentValues copy=new ContentValues(values);copy.put("_id",id);copy.put("deleted",0);rows.put(id,copy);return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id);}
        @Override public int update(Uri uri,ContentValues values,String where,String[] args) {
            ContentValues old=rows.get(Long.parseLong(args[0]));if(old==null)return 0;old.putAll(values);return 1;
        }
        @Override public int delete(Uri uri,String where,String[] args){return rows.remove(Long.parseLong(args[0]))==null?0:1;}
    }
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();context.getSharedPreferences("famical",0).edit().clear().putBoolean("sync_enabled",true).putString("sync_server","https://calendar.example.test/").putLong("sync_user",1).putLong("sync_calendar",1).putString("sync_connection",token).commit();
        Shadows.shadowOf((android.app.Application)context).grantPermissions(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR);
        provider=new Provider();ProviderInfo info=new ProviderInfo();info.authority="com.android.calendar";provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal("com.android.calendar",provider);api=new Api();
    }
    DeviceCalendars.Item seed() throws Exception {
        ContentValues v=DeviceCalendars.values(event("휴대전화 일정"),null,1,"");v.put("_id",10L);v.put("deleted",0);v.put("hasAlarm",1);provider.rows.put(10L,v);
        return DeviceCalendars.one(context.getContentResolver(),1,10);
    }
    JSONObject event(String title) throws Exception {
        String day=LocalDate.now().toString();return new JSONObject().put("id",21).put("title",title).put("note","").put("day",day).put("end_day",day).put("clock","09:00").put("revision",REV).put("sync_readonly",0).put("projection",false);
    }
    JSONObject link(DeviceCalendars.Item item,JSONObject event,String revision) throws Exception {
        return new JSONObject().put("external_key",item.key()).put("native_hash",item.hash()).put("server_revision",revision).put("mode","normal").put("event",event==null?JSONObject.NULL:event);
    }
    void linked(JSONObject link,JSONObject event) throws Exception {
        api.snapshot.put("links",new JSONArray().put(link));api.snapshot.put("events",event==null?new JSONArray():new JSONArray().put(event));
    }
    @Test public void importsAndExportsWithoutEchoingOwnNewCopies() throws Exception {
        seed();api.snapshot.put("events",new JSONArray().put(event("가족 달력 일정")));
        String status=CalendarSync.runWithApi(context,api,"");
        assertEquals(2,provider.rows.size());assertEquals(2,api.writes.size());
        assertEquals("import",api.writes.get(0).getString("action"));assertEquals("attach",api.writes.get(1).getString("action"));
        assertTrue(provider.rows.get(100L).getAsString("customAppUri").contains(token+"/21/single"));assertTrue(status.contains("충돌 0건"));
    }
    @Test public void nativeEditUpdatesNAS() throws Exception {
        DeviceCalendars.Item item=seed();JSONObject e=event("휴대전화 일정"),link=link(item,e,REV);provider.rows.get(10L).put("title","삼성에서 수정");linked(link,e);
        CalendarSync.runWithApi(context,api,"");assertEquals(1,api.writes.size());assertEquals("update",api.writes.get(0).getString("action"));assertEquals("삼성에서 수정",api.writes.get(0).getJSONObject("event").getString("title"));
    }
    @Test public void serverEditUpdatesNativeWithoutChangingDurationOrAlarm() throws Exception {
        DeviceCalendars.Item item=seed();provider.rows.get(10L).put("dtend",item.start+2*3600000);item=DeviceCalendars.one(context.getContentResolver(),1,10);
        JSONObject e=event("가족 달력에서 수정");linked(link(item,e,"old-revision"),e);
        CalendarSync.runWithApi(context,api,"");ContentValues saved=provider.rows.get(10L);assertEquals("가족 달력에서 수정",saved.getAsString("title"));assertEquals(2*3600000L,saved.getAsLong("dtend")-saved.getAsLong("dtstart"));assertEquals(Integer.valueOf(1),saved.getAsInteger("hasAlarm"));assertEquals("checkpoint",api.writes.get(0).getString("action"));
    }
    @Test public void concurrentEditsAreHeldAndExplicitPreferenceResolves() throws Exception {
        DeviceCalendars.Item item=seed();JSONObject e=event("웹에서 수정");linked(link(item,e,"old"),e);provider.rows.get(10L).put("title","삼성에서 수정");
        assertTrue(CalendarSync.runWithApi(context,api,"").contains("충돌 1건"));assertTrue(api.writes.isEmpty());assertEquals("삼성에서 수정",provider.rows.get(10L).getAsString("title"));
        CalendarSync.runWithApi(context,api,"native");assertEquals("update",api.writes.get(0).getString("action"));
    }
    @Test public void deletionPropagatesInBothDirections() throws Exception {
        DeviceCalendars.Item item=seed();JSONObject e=event("휴대전화 일정");linked(link(item,e,REV),e);provider.rows.clear();
        CalendarSync.runWithApi(context,api,"");assertEquals("delete",api.writes.get(0).getString("action"));
        api.writes.clear();item=seed();linked(link(item,null,REV),null);CalendarSync.runWithApi(context,api,"");assertTrue(provider.rows.isEmpty());
    }
    @Test public void unavailableCalendarNeverTriggersDeletes() throws Exception {
        DeviceCalendars.Item item=seed();JSONObject e=event("휴대전화 일정");linked(link(item,e,REV),e);provider.unavailable=true;
        assertThrows(IllegalStateException.class,()->CalendarSync.runWithApi(context,api,""));assertTrue(api.writes.isEmpty());assertEquals(1,provider.rows.size());
    }
    @Test public void differentLoggedInAccountCannotWrite() throws Exception {
        seed();api.user.put("id",2);assertThrows(IllegalStateException.class,()->CalendarSync.runWithApi(context,api,""));assertTrue(api.writes.isEmpty());assertEquals(1,provider.rows.size());
    }
    @Test public void retryRecoversExportByMarkerInsteadOfDuplicating() throws Exception {
        seed();JSONObject e=event("휴대전화 일정");provider.rows.get(10L).put("customAppUri","famical://sync/"+token+"/21/single");api.snapshot.put("events",new JSONArray().put(e));
        CalendarSync.runWithApi(context,api,"");assertEquals(1,provider.rows.size());assertEquals(1,api.writes.size());assertEquals("attach",api.writes.get(0).getString("action"));
    }
    @Test public void allDayDatesUseUTCAndExclusiveNativeEnd() throws Exception {
        JSONObject e=event("여행").put("day","2026-11-17").put("end_day","2026-11-19").put("clock","");
        ContentValues values=DeviceCalendars.values(e,null,1,"");assertEquals("UTC",values.getAsString("eventTimezone"));assertEquals(3*86400000L,values.getAsLong("dtend")-values.getAsLong("dtstart"));
        DeviceCalendars.Item i=new DeviceCalendars.Item();i.id=1;i.title="여행";i.note="";i.allDay=true;i.start=values.getAsLong("dtstart");i.end=values.getAsLong("dtend");assertEquals("2026-11-19",i.payload().getString("end_day"));
    }
    @Test public void noShiftAlwaysUsesDayOnWeekdays() {
        assertEquals("주간",WidgetSummary.shift(LocalDate.of(2026,11,17),"none",true));assertEquals("주간",WidgetSummary.shift(LocalDate.of(2026,11,23),"none",true));assertEquals("",WidgetSummary.shift(LocalDate.of(2026,11,21),"none",true));assertEquals("",WidgetSummary.shift(LocalDate.of(2026,11,17),"none",false));
    }
    @Test public void projectionEditsNeverOverwriteLunarRule() {
        assertEquals("conflict",CalendarSync.decide(true,false,"projection","native"));assertEquals("conflict",CalendarSync.decide(true,true,"projection","server"));assertEquals("native",CalendarSync.decide(true,false,"occurrence",""));
    }
    @Test public void missingOldRepeatOutsideWindowIsNeverDeleted() throws Exception {
        DeviceCalendars.Item item=seed();item.recurring=true;item.start=LocalDate.now().minusYears(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        provider.rows.get(10L).put("rrule","FREQ=DAILY");provider.rows.get(10L).put("dtstart",item.start);
        JSONObject e=event("과거 반복").put("sync_readonly",1);JSONObject link=link(item,e,REV).put("mode","occurrence");linked(link,e);
        CalendarSync.runWithApi(context,api,"");assertTrue(api.writes.isEmpty());
    }
    @Test public void manyMissingNativeEventsRequireExplicitConfirmation() throws Exception {
        JSONArray links=new JSONArray();
        for(int i=0;i<6;i++){DeviceCalendars.Item item=seed();item.id=10+i;links.put(link(item,event("일정 "+i).put("id",21+i),REV));}
        provider.rows.clear();api.snapshot.put("links",links);
        assertThrows(IllegalStateException.class,()->CalendarSync.runWithApi(context,api,""));assertTrue(api.writes.isEmpty());
    }
    @Test public void explicitServerPreferenceRestoresNativeDeleteConflict() throws Exception {
        DeviceCalendars.Item item=seed();JSONObject e=event("수정된 NAS 일정");linked(link(item,e,"old"),e);provider.rows.clear();
        assertTrue(CalendarSync.runWithApi(context,api,"").contains("충돌 1건"));assertTrue(provider.rows.isEmpty());
        CalendarSync.runWithApi(context,api,"server");assertEquals(1,provider.rows.size());assertEquals("rebind",api.writes.get(0).getString("action"));assertEquals("n:10",api.writes.get(0).getString("replaces_key"));
    }
    @Test public void explicitNativePreferenceRestoresNASDeleteConflict() throws Exception {
        DeviceCalendars.Item item=seed();JSONObject link=link(item,null,REV);provider.rows.get(10L).put("title","보관할 삼성 일정");linked(link,null);
        assertTrue(CalendarSync.runWithApi(context,api,"").contains("충돌 1건"));assertTrue(api.writes.isEmpty());
        CalendarSync.runWithApi(context,api,"native");assertEquals("restore",api.writes.get(0).getString("action"));assertEquals(1,provider.rows.size());
    }
    @Test public void projectionLabelIsNotImportedBackAsTitleChange() throws Exception {
        JSONObject e=event("음력 생일").put("projection",true);String marker="famical://sync/"+token+"/21/2026-11-17";
        long id=DeviceCalendars.insert(context.getContentResolver(),e,1,marker);DeviceCalendars.Item item=DeviceCalendars.one(context.getContentResolver(),1,id);
        assertEquals("보기 · 음력 생일",item.title);assertEquals("음력 생일",item.payload().getString("title"));
    }
    @Test public void newPrivateFamicalEventIsNotExportedWithoutOptIn() throws Exception {
        api.snapshot.put("events",new JSONArray().put(event("개인 일정").put("visibility","private")));
        CalendarSync.runWithApi(context,api,"");assertTrue(provider.rows.isEmpty());assertTrue(api.writes.isEmpty());
        context.getSharedPreferences("famical",0).edit().putBoolean("sync_export_private",true).commit();
        CalendarSync.runWithApi(context,api,"");assertEquals(1,provider.rows.size());assertEquals("attach",api.writes.get(0).getString("action"));
    }
    @Test public void oversizedNativeEditIsReportedWithoutOverwritingNAS() throws Exception {
        DeviceCalendars.Item item=seed();JSONObject e=event("휴대전화 일정");linked(link(item,e,REV),e);
        provider.rows.get(10L).put("description","x".repeat(2001));
        assertTrue(CalendarSync.runWithApi(context,api,"").contains("지원 범위 초과 1건"));assertTrue(api.writes.isEmpty());
    }
    @Test public void managementDialogShowsActualMenuAndPrivacyChoices() throws Exception {
        context.getSharedPreferences("famical",0).edit().putBoolean("sync_enabled",false).putString("server","https://calendar.example.test/").commit();
        android.webkit.CookieManager.getInstance().setCookie("https://calendar.example.test/","session=test; Secure");
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).create().get();
        java.lang.reflect.Method management=MainActivity.class.getDeclaredMethod("showCalendarSync");management.setAccessible(true);management.invoke(activity);
        android.app.AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertEquals(5,dialog.getListView().getAdapter().getCount());dialog.dismiss();
        java.lang.reflect.Method select=MainActivity.class.getDeclaredMethod("selectCalendar");select.setAccessible(true);select.invoke(activity);
        dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getListView().performItemClick(null,0,0);
        dialog=ShadowAlertDialog.getLatestAlertDialog();assertEquals(2,dialog.getListView().getAdapter().getCount());
        assertEquals("양방향 연동 시작",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).getText().toString());dialog.dismiss();activity.finish();
    }
}
