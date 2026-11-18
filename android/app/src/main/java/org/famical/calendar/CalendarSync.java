package org.famical.calendar;

import android.Manifest;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Explicit opt-in, one selected writable calendar and one account per connection. */
final class CalendarSync {
    private static final java.util.concurrent.ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final int JOB=20261118;
    static boolean permitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED&&context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)==PackageManager.PERMISSION_GRANTED;
    }
    static void schedule(Context context) {
        SharedPreferences p=context.getSharedPreferences("famical",0);
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(!p.getBoolean("sync_enabled",false)||!permitted(context)){scheduler.cancel(JOB);return;}
        if(scheduler.getPendingJob(JOB)!=null)return;
        scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(context,CalendarSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(3600000).setPersisted(true).build());
    }
    static void submit(Context context,String cookie,String conflict,java.util.function.Consumer<String> done) {
        Context app=context.getApplicationContext();
        WORK.execute(()->{
            String status;
            try{status=run(app,cookie,conflict);}catch(Exception e){status="연동 중단: "+(e.getMessage()==null?"캘린더 권한·NAS 연결을 확인해 주세요.":e.getMessage());}
            app.getSharedPreferences("famical",0).edit().putString("sync_status",status).apply();
            String result=status;MAIN.post(()->{CalendarWidget.refreshAll(app);if(done!=null)done.accept(result);});
        });
    }
    static CalendarApi api(Context context,String cookie,boolean mustBeEnabled) {
        SharedPreferences p=context.getSharedPreferences("famical",0);String server=p.getString("server","");
        String connection=p.getString("sync_connection","");
        return new CalendarApi(server,cookie,()->{
            String current=CookieManager.getInstance().getCookie(server);
            return server.equals(p.getString("server",""))&&Objects.equals(cookie,current)&&
                (!mustBeEnabled||(p.getBoolean("sync_enabled",false)&&connection.equals(p.getString("sync_connection",""))));
        });
    }
    static void connect(Context context,DeviceCalendars.Choice choice,String visibility,boolean exportPrivate,String cookie,java.util.function.Consumer<String> done) {
        SharedPreferences p=context.getSharedPreferences("famical",0);
        WORK.execute(()->{
            String status;
            try {
                CalendarApi api=api(context,cookie,false);JSONObject user=api.me().optJSONObject("user");
                if(user==null)throw new IllegalStateException("먼저 가족 계정으로 로그인해 주세요.");
                String token=p.getString("sync_connection","");
                long uid=user.getLong("id");
                if(token.isEmpty()||p.getLong("sync_user",-1)!=uid||p.getLong("sync_calendar",-1)!=choice.id)token=UUID.randomUUID().toString();
                api.request("calendar-sync/connection","POST",new JSONObject().put("connection",token).put("label",choice.label).put("visibility",visibility));
                p.edit().putString("sync_connection",token).putLong("sync_user",uid).putLong("sync_calendar",choice.id)
                    .putString("sync_server",api.server).putString("sync_label",choice.label).putBoolean("sync_export_private",exportPrivate).putBoolean("sync_enabled",true).apply();
                AccountPreferences.store(p,user);status="연결했습니다. 지금 동기화를 시작합니다.";
                MAIN.post(()->{schedule(context);submit(context,cookie,"",done);});
            }catch(Exception e){status="연결 실패: "+e.getMessage();String result=status;MAIN.post(()->done.accept(result));}
        });
    }
    static void disconnect(Context context,String cookie,java.util.function.Consumer<String> done) {
        SharedPreferences p=context.getSharedPreferences("famical",0);
        // Freeze local writes immediately, including already queued jobs.
        p.edit().putBoolean("sync_enabled",false).apply();schedule(context);
        WORK.execute(()->{
            String status;
            try {
                CalendarApi api=api(context,cookie,false);JSONObject user=api.me().optJSONObject("user");
                if(user==null||user.getLong("id")!=p.getLong("sync_user",-1))throw new IllegalStateException("연결할 때 사용한 가족 계정으로 로그인해 주세요.");
                api.request("calendar-sync/connection","DELETE",new JSONObject().put("connection",p.getString("sync_connection","")));
                status="연동을 해제했습니다. 양쪽의 기존 일정은 보관됩니다.";
            }catch(Exception e){status="기기 자동 연동은 중지했습니다. NAS 연결 해제는 다시 시도해 주세요: "+e.getMessage();}
            String result=status;p.edit().putString("sync_status",result).apply();MAIN.post(()->done.accept(result));
        });
    }
    static String decide(boolean nativeChanged,boolean serverChanged,String mode,String override) {
        if(nativeChanged&&(mode.equals("projection")))return "conflict";
        if(nativeChanged&&serverChanged) {
            if(override.equals("native"))return "native";
            if(override.equals("server")&&!mode.equals("occurrence"))return "server";
            return "conflict";
        }
        return nativeChanged?"native":serverChanged?"server":"same";
    }
    private static boolean equalPayload(JSONObject a,JSONObject b) {
        for(String field:new String[]{"title","day","end_day","clock","note"})if(!a.optString(field).equals(b.optString(field)))return false;
        return true;
    }
    private static boolean supported(JSONObject payload) throws JSONException {
        long days=java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(payload.getString("day")),LocalDate.parse(payload.getString("end_day")));
        return payload.getString("title").length()<=100&&payload.getString("note").length()<=2000&&days>=0&&days<=365;
    }
    private static JSONObject message(String token,String key,String action,String hash) throws JSONException {
        return new JSONObject().put("connection",token).put("external_key",key).put("action",action).put("native_hash",hash);
    }
    static String run(Context context,String cookie,String override) throws Exception {
        return runWithApi(context,api(context,cookie,true),override);
    }
    static String runWithApi(Context context,CalendarApi api,String override) throws Exception {
        SharedPreferences p=context.getSharedPreferences("famical",0);
        if(!p.getBoolean("sync_enabled",false))return "자동 연동이 꺼져 있습니다.";
        if(!permitted(context))throw new IllegalStateException("캘린더 읽기·쓰기 권한이 필요합니다.");
        JSONObject user=api.me().optJSONObject("user");
        if(user==null||user.getLong("id")!=p.getLong("sync_user",-1)||!api.server.equals(p.getString("sync_server","")))throw new IllegalStateException("연동한 가족 계정으로 다시 로그인해 주세요.");
        AccountPreferences.store(p,user);
        long calendar=p.getLong("sync_calendar",-1);String token=p.getString("sync_connection","");
        LocalDate first=LocalDate.now().minusMonths(1).withDayOfMonth(1),last=LocalDate.now().plusMonths(12).withDayOfMonth(1).minusDays(1);
        long begin=first.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),end=last.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        JSONObject snapshot=api.request("calendar-sync?connection="+token+"&from="+first+"&to="+last,"GET",null);
        ContentResolver resolver=context.getContentResolver();DeviceCalendars.Snapshot nativeData=DeviceCalendars.read(resolver,calendar,begin,end);
        JSONArray links=snapshot.getJSONArray("links"),events=snapshot.getJSONArray("events");
        Set<String> linked=new HashSet<>(),paired=new HashSet<>();int changed=0,conflicts=0,unsupported=0;
        int missing=0;
        for(int i=0;i<links.length();i++) {
            JSONObject link=links.getJSONObject(i);String key=link.getString("external_key");
            if(key.startsWith("n:")&&link.optJSONObject("event")!=null&&!link.getString("mode").equals("projection")&&!nativeData.items.containsKey(key))missing++;
        }
        if(missing>5&&!override.equals("native"))throw new IllegalStateException("휴대전화에서 많은 일정이 사라져 삭제 연동을 보류했습니다. 계정 동기화를 확인한 뒤 의도한 삭제라면 '휴대전화 우선'으로 실행하세요.");
        for(int index=0;index<links.length();index++) {
            JSONObject link=links.getJSONObject(index);String key=link.getString("external_key");linked.add(key);
            JSONObject event=link.optJSONObject("event");DeviceCalendars.Item nativeItem=nativeData.items.get(key);
            if(event!=null)paired.add(event.getLong("id")+":"+(event.optBoolean("projection")?event.getString("day"):""));
            boolean nativeChanged=nativeItem!=null&&!nativeItem.hash().equals(link.getString("native_hash"));
            boolean serverChanged=event!=null&&!event.getString("revision").equals(link.getString("server_revision"));
            if(nativeChanged&&!supported(nativeItem.payload())){unsupported++;continue;}
            if(event==null) {
                if(nativeItem!=null){
                    if(nativeChanged&&override.equals("native")&&!link.getString("mode").equals("projection")) {
                        api.request("calendar-sync/item","POST",message(token,key,"restore",nativeItem.hash()).put("revision",link.getString("server_revision")).put("event",nativeItem.payload()));changed++;continue;
                    }
                    if((nativeChanged&&!override.equals("server"))||nativeItem.recurring){conflicts++;continue;}
                    // Recheck login/config before touching a provider row.
                    api.me();DeviceCalendars.change(resolver,nativeItem,null,calendar,true);changed++;
                }continue;
            }
            if(nativeItem==null) {
                if(key.startsWith("r:")) {
                    long occurrence=Long.parseLong(key.substring(key.lastIndexOf(':')+1));
                    if(occurrence<begin||occurrence>=end)continue; // Rolling window is never evidence of deletion.
                }
                if((serverChanged||link.getString("mode").equals("projection"))&&override.equals("server")) {
                    String projection=event.optBoolean("projection")?event.getString("day"):"";
                    String marker="famical://sync/"+token+"/"+event.getLong("id")+"/"+(projection.isEmpty()?"single":projection);
                    DeviceCalendars.Item restored=null;
                    for(DeviceCalendars.Item candidate:nativeData.items.values())if(candidate.marker.equals(marker)){restored=candidate;break;}
                    api.me();
                    if(restored==null)restored=DeviceCalendars.one(resolver,calendar,DeviceCalendars.insert(resolver,event,calendar,marker));
                    if(restored==null)throw new IllegalStateException("일정 복구를 확인할 수 없습니다.");
                    api.request("calendar-sync/item","POST",message(token,restored.key(),"rebind",restored.hash()).put("replaces_key",key).put("event_id",event.getLong("id")).put("revision",event.getString("revision")).put("projection_day",projection));changed++;continue;
                }
                if(link.getString("mode").equals("projection")||(serverChanged&&!override.equals("native"))){conflicts++;continue;}
                api.request("calendar-sync/item","POST",message(token,key,"delete",link.getString("native_hash")).put("revision",event.getString("revision")));changed++;continue;
            }
            if((nativeChanged||serverChanged)&&equalPayload(nativeItem.payload(),event)) {
                api.request("calendar-sync/item","POST",message(token,key,"checkpoint",nativeItem.hash()).put("revision",event.getString("revision")));continue;
            }
            String decision=decide(nativeChanged,serverChanged,link.getString("mode"),override);
            if(decision.equals("conflict")){conflicts++;continue;}
            if(decision.equals("native")) {
                api.request("calendar-sync/item","POST",message(token,key,"update",nativeItem.hash()).put("revision",event.getString("revision")).put("event",nativeItem.payload()));changed++;
            } else if(decision.equals("server")) {
                if(!equalPayload(nativeItem.payload(),event)) {
                    if(nativeItem.recurring){conflicts++;continue;}
                    api.me();DeviceCalendars.change(resolver,nativeItem,event,calendar,false);
                    nativeItem=DeviceCalendars.one(resolver,calendar,nativeItem.id);if(nativeItem==null)throw new IllegalStateException("일정이 동기화 도중 삭제되었습니다.");changed++;
                }
                api.request("calendar-sync/item","POST",message(token,key,"checkpoint",nativeItem.hash()).put("revision",event.getString("revision")));
            }
        }
        for(DeviceCalendars.Item item:nativeData.items.values()) {
            if(linked.contains(item.key())||item.marker.startsWith("famical://sync/")||item.start>=end||item.end<begin)continue;
            JSONObject payload=item.payload();
            if(!supported(payload)){unsupported++;continue;}
            JSONObject result=api.request("calendar-sync/item","POST",message(token,item.key(),"import",item.hash()).put("event",payload));
            JSONObject imported=result.optJSONObject("event");if(imported!=null)paired.add(imported.getLong("id")+":");changed++;
        }
        for(int index=0;index<events.length();index++) {
            JSONObject event=events.getJSONObject(index);String day=event.optBoolean("projection")?event.getString("day"):"";
            if(event.optString("visibility").equals("private")&&!p.getBoolean("sync_export_private",false))continue;
            if(event.optInt("sync_readonly")==1||paired.contains(event.getLong("id")+":"+day))continue;
            String marker="famical://sync/"+token+"/"+event.getLong("id")+"/"+(day.isEmpty()?"single":day);
            DeviceCalendars.Item item=null;
            for(DeviceCalendars.Item candidate:nativeData.items.values())if(candidate.marker.equals(marker)){item=candidate;break;}
            api.me();
            if(item==null){long id=DeviceCalendars.insert(resolver,event,calendar,marker);item=DeviceCalendars.one(resolver,calendar,id);}
            else if(!equalPayload(item.payload(),event)){DeviceCalendars.change(resolver,item,event,calendar,false);item=DeviceCalendars.one(resolver,calendar,item.id);}
            if(item==null)throw new IllegalStateException("휴대전화 일정 저장을 확인할 수 없습니다.");
            api.request("calendar-sync/item","POST",message(token,item.key(),"attach",item.hash()).put("event_id",event.getLong("id")).put("revision",event.getString("revision")).put("projection_day",day));changed++;
        }
        return java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))+" 연동 완료 · 반영 "+changed+"건 · 충돌 "+conflicts+"건 · 지원 범위 초과 "+unsupported+"건";
    }
}
