package org.famical.calendar;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.widget.RemoteViews;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;

public class CalendarWidget extends AppWidgetProvider {
    static final String REFRESH="org.famical.calendar.WIDGET_REFRESH";
    private static final java.util.concurrent.ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids) { refresh(context,goAsync()); }
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,android.os.Bundle options) {
        renderOne(context,manager,id,options,context.getSharedPreferences("famical",Context.MODE_PRIVATE).getString("widget_status","갱신해 주세요"));
    }
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
        NativeStore nativeStore=NativeStore.get(context);
        if(nativeStore.view().optJSONObject("user")!=null) {
            WORK.execute(()->{try{nativeStore.updateWidget();nativeStore.sync();}finally{if(pending!=null)pending.finish();}});
            return;
        }
        String server=p.getString("server","");String cookie;
        try { cookie=server.isEmpty()?null:CookieManager.getInstance().getCookie(server); }
        catch(RuntimeException e) { cookie=null; }
        String session=cookie==null?"":cookie;String fingerprint=hash(server+"\n"+session);
        boolean includePrivate=p.getBoolean("widget_private",false);
        if(!fingerprint.equals(p.getString("widget_session",""))) p.edit().remove("widget_lines").remove("widget_day").remove("widget_time").remove("widget_month_events").remove("widget_month").putString("widget_session",fingerprint).apply();
        render(context,"갱신 중");
        if(server.isEmpty() || session.isEmpty()) {
            clear(p);render(context,"앱에서 서버 설정·로그인해 주세요");if(pending!=null)pending.finish();return;
        }
        WORK.execute(()->{
            String status;String lines=null,monthEvents=null;LocalDate today=LocalDate.now();
            try {
                String origin=ServerAddress.normalize(server);
                CalendarApi accountApi=new CalendarApi(origin,session,()->origin.equals(p.getString("server",""))&&session.equals(CookieManager.getInstance().getCookie(origin)));
                org.json.JSONObject account=accountApi.me().optJSONObject("user");
                if(account==null)throw new IllegalStateException("Login required");
                AccountPreferences.store(p,account);
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
                        WidgetEvents.Snapshot snapshot=WidgetEvents.parse(body,today,includePrivate);
                        lines=snapshot.todayLines;monthEvents=snapshot.monthEvents;
                        status=java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))+" 갱신";
                    }
                } finally { conn.disconnect(); }
            } catch(Exception e) { status="연결 실패 · 마지막 갱신 내용"; }
            String result=lines,markers=monthEvents,message=status;
            MAIN.post(()->{
                String current=p.getString("server","");String currentCookie;
                try { currentCookie=current.isEmpty()?"":CookieManager.getInstance().getCookie(current); }catch(RuntimeException e){currentCookie="";}
                if(includePrivate==p.getBoolean("widget_private",false) && fingerprint.equals(hash(current+"\n"+(currentCookie==null?"":currentCookie)))) {
                    if(result!=null) {
                        if(result.isEmpty()) clear(p);
                        else p.edit().putString("widget_lines",result).putString("widget_day",today.toString()).putString("widget_time",message).putString("widget_month_events",markers==null?"[]":markers).putString("widget_month",YearMonth.from(today).toString()).apply();
                    }
                    render(context,message);
                }
                if(pending!=null)pending.finish();
            });
        });
    }
    private static void clear(SharedPreferences p) { p.edit().remove("widget_lines").remove("widget_day").remove("widget_time").remove("widget_month_events").remove("widget_month").apply(); }
    private static String hash(String text) {
        try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString(); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static void renderCached(Context context) { render(context,"기기 저장 일정"); }
    private static void render(Context context,String status) {
        SharedPreferences p=context.getSharedPreferences("famical",Context.MODE_PRIVATE);
        p.edit().putString("widget_status",status).apply();
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        int[] ids=manager.getAppWidgetIds(new android.content.ComponentName(context,CalendarWidget.class));
        for(int id:ids) renderOne(context,manager,id,manager.getAppWidgetOptions(id),status);
    }
    private static void renderOne(Context context,AppWidgetManager manager,int id,android.os.Bundle options,String status) {
        RemoteViews views;
        if(android.os.Build.VERSION.SDK_INT>=31) {
            java.util.ArrayList<android.util.SizeF> sizes=options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            java.util.Map<android.util.SizeF,RemoteViews> variants=new java.util.LinkedHashMap<>();
            if(sizes!=null) for(android.util.SizeF size:sizes) {
                if(size.getWidth()>0 && size.getHeight()>0 && variants.size()<8)
                    variants.put(size,WidgetRenderer.create(context,size.getWidth(),size.getHeight(),LocalDate.now(),status));
            }
            views=variants.isEmpty()?fallbackView(context,options,status):new RemoteViews(variants);
        } else views=fallbackView(context,options,status);
        manager.updateAppWidget(id,views);
    }
    private static RemoteViews fallbackView(Context context,android.os.Bundle options,String status) {
        boolean landscape=context.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int width=options.getInt(landscape?AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH:AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,280);
        int height=options.getInt(landscape?AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT:AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,320);
        return WidgetRenderer.create(context,width,height,LocalDate.now(),status);
    }
}
