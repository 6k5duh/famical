package org.famical.calendar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.webkit.CookieManager;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class CalendarWidget extends AppWidgetProvider {
    private static final String REFRESH="org.famical.calendar.WIDGET_REFRESH";
    private static final java.util.concurrent.ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids) { refresh(context,goAsync()); }
    @Override public void onReceive(Context context,Intent intent) {
        if(REFRESH.equals(intent.getAction())) refresh(context,goAsync());
        else super.onReceive(context,intent);
    }
    public static void refreshAll(Context context) {
        Context app=context.getApplicationContext();
        int[] ids=AppWidgetManager.getInstance(app).getAppWidgetIds(new android.content.ComponentName(app,CalendarWidget.class));
        if(ids.length>0) refresh(app,null);
    }
    private static void refresh(Context original,android.content.BroadcastReceiver.PendingResult pending) {
        Context context=original.getApplicationContext();SharedPreferences p=context.getSharedPreferences("famical",Context.MODE_PRIVATE);
        String server=p.getString("server","");String cookie;
        try { cookie=server.isEmpty()?null:CookieManager.getInstance().getCookie(server); }
        catch(RuntimeException e) { cookie=null; }
        String session=cookie==null?"":cookie;String fingerprint=hash(server+"\n"+session);
        boolean includePrivate=p.getBoolean("widget_private",false);
        if(!fingerprint.equals(p.getString("widget_session",""))) p.edit().remove("widget_lines").remove("widget_day").remove("widget_time").putString("widget_session",fingerprint).apply();
        render(context,"갱신 중");
        if(server.isEmpty() || session.isEmpty()) {
            clear(p);render(context,"앱에서 서버 설정·로그인해 주세요");if(pending!=null)pending.finish();return;
        }
        WORK.execute(()->{
            String status;String lines=null;LocalDate today=LocalDate.now();
            try {
                String origin=ServerAddress.normalize(server);
                HttpURLConnection conn=(HttpURLConnection)new URL(origin+"api/events?month="+YearMonth.from(today)).openConnection();
                conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(3000);conn.setReadTimeout(4000);
                conn.setRequestProperty("Cookie",session);conn.setRequestProperty("Accept","application/json");
                try {
                    int code=conn.getResponseCode();
                    if(code==401 || code==403) { status="앱에서 다시 로그인해 주세요";lines=""; }
                    else if(code!=200) { status="서버 응답 오류 · 앱에서 확인"; }
                    else {
                        java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
                        byte[] bytes=new byte[4096];int count;try(java.io.InputStream in=conn.getInputStream()){while((count=in.read(bytes))!=-1){buffer.write(bytes,0,count);if(buffer.size()>2_000_000)throw new java.io.IOException("Response too large");}}
                        String body=buffer.toString("UTF-8");
                        JSONArray events=new JSONArray(body);List<String> items=new ArrayList<>();
                        for(int i=0;i<events.length()&&items.size()<3;i++) {
                            JSONObject e=events.getJSONObject(i);
                            if(!WidgetSummary.occursToday(e.getString("day"),e.optString("end_day",e.getString("day")),today))continue;
                            if("private".equals(e.optString("visibility"))&&!includePrivate)continue;
                            String clock=e.optString("clock","");items.add((clock.isEmpty()?"종일":clock)+"  "+e.getString("title"));
                        }
                        lines=items.isEmpty()?"오늘 표시할 일정이 없습니다":String.join("\n",items);
                        status=java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))+" 갱신";
                    }
                } finally { conn.disconnect(); }
            } catch(Exception e) { status="연결 실패 · 마지막 갱신 내용"; }
            String result=lines,message=status;
            MAIN.post(()->{
                String current=p.getString("server","");String currentCookie;
                try { currentCookie=current.isEmpty()?"":CookieManager.getInstance().getCookie(current); }catch(RuntimeException e){currentCookie="";}
                if(includePrivate==p.getBoolean("widget_private",false) && fingerprint.equals(hash(current+"\n"+(currentCookie==null?"":currentCookie)))) {
                    if(result!=null) {
                        if(result.isEmpty()) clear(p);
                        else p.edit().putString("widget_lines",result).putString("widget_day",today.toString()).putString("widget_time",message).apply();
                    }
                    render(context,message);
                }
                if(pending!=null)pending.finish();
            });
        });
    }
    private static void clear(SharedPreferences p) { p.edit().remove("widget_lines").remove("widget_day").remove("widget_time").apply(); }
    private static String hash(String text) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString(); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void render(Context context,String status) {
        SharedPreferences p=context.getSharedPreferences("famical",Context.MODE_PRIVATE);LocalDate today=LocalDate.now();
        boolean on=p.getBoolean("widget_shift",true);String team=p.getString("widget_team","A");
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.calendar_widget);
        views.setTextViewText(R.id.widget_title,today.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE",Locale.KOREAN)));
        views.setTextViewText(R.id.widget_shift,WidgetSummary.shift(today,team,on));
        views.setTextViewText(R.id.widget_month,coloredMonth(today,team,on));
        views.setTextViewText(R.id.widget_events,today.toString().equals(p.getString("widget_day",""))?p.getString("widget_lines",""):"앱에서 로그인 후 갱신해 주세요");
        views.setTextViewText(R.id.widget_status,status);
        Intent open=new Intent(context,MainActivity.class);open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        Intent update=new Intent(context,CalendarWidget.class).setAction(REFRESH);
        views.setOnClickPendingIntent(R.id.widget_refresh,PendingIntent.getBroadcast(context,0,update,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        manager.updateAppWidget(new android.content.ComponentName(context,CalendarWidget.class),views);
    }
    private static CharSequence coloredMonth(LocalDate today,String team,boolean enabled) {
        String grid=WidgetSummary.monthGrid(today);SpannableString text=new SpannableString(grid);
        int offset=YearMonth.from(today).atDay(1).getDayOfWeek().getValue()%7;
        int header=grid.indexOf('\n')+1;
        for(int day=1;day<=YearMonth.from(today).lengthOfMonth();day++) {
            LocalDate d=today.withDayOfMonth(day);int index=offset+day-1;
            int start=header+index*3+index/7;
            int background=Color.rgb(246,245,239);
            if(d.getDayOfWeek().getValue()>=6)background=Color.rgb(243,223,225);
            else {String shift=WidgetSummary.shift(d,team,enabled);if(shift.endsWith("야간"))background=Color.rgb(247,239,187);else if(!shift.isEmpty())background=Color.rgb(220,233,216);}
            text.setSpan(new BackgroundColorSpan(background),start,start+3,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(Color.rgb(35,58,50)),start,start+3,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(day==today.getDayOfMonth())text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),start,start+3,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }
}
