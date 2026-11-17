package org.famical.calendar;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.util.TypedValue;
import android.widget.RemoteViews;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class WidgetRenderer {
    private static final int[] DAYS = {R.id.day_0, R.id.day_1, R.id.day_2, R.id.day_3, R.id.day_4, R.id.day_5, R.id.day_6, R.id.day_7, R.id.day_8, R.id.day_9, R.id.day_10, R.id.day_11, R.id.day_12, R.id.day_13, R.id.day_14, R.id.day_15, R.id.day_16, R.id.day_17, R.id.day_18, R.id.day_19, R.id.day_20, R.id.day_21, R.id.day_22, R.id.day_23, R.id.day_24, R.id.day_25, R.id.day_26, R.id.day_27, R.id.day_28, R.id.day_29, R.id.day_30, R.id.day_31, R.id.day_32, R.id.day_33, R.id.day_34, R.id.day_35, R.id.day_36, R.id.day_37, R.id.day_38, R.id.day_39, R.id.day_40, R.id.day_41};
    private static final int[] ROWS = {R.id.week_0,R.id.week_1,R.id.week_2,R.id.week_3,R.id.week_4,R.id.week_5};
    static RemoteViews create(Context context,float width,float height,LocalDate today,String status) {
        SharedPreferences p=context.getSharedPreferences("famical",Context.MODE_PRIVATE);
        String team=p.getString("widget_team","A");boolean enabled=p.getBoolean("widget_shift",true);
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.calendar_widget_grid);
        views.setTextViewText(R.id.widget_title,today.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE",Locale.KOREAN)));
        views.setTextViewTextSize(R.id.widget_title,TypedValue.COMPLEX_UNIT_SP,width>=400?22:17);
        String shift=WidgetSummary.shift(today,team,enabled);
        views.setTextViewText(R.id.widget_shift,shift);
        views.setViewVisibility(R.id.widget_shift,shift.isEmpty()?View.GONE:View.VISIBLE);
        int first=YearMonth.from(today).atDay(1).getDayOfWeek().getValue()%7;
        int length=YearMonth.from(today).lengthOfMonth();int rows=(first+length+6)/7;
        for(int row=0;row<6;row++) views.setViewVisibility(ROWS[row],row<rows?View.VISIBLE:View.GONE);
        for(int i=0;i<42;i++) {
            int day=i-first+1;boolean active=day>=1&&day<=length;
            views.setViewVisibility(DAYS[i],active?View.VISIBLE:View.INVISIBLE);
            views.setTextViewText(DAYS[i],active?String.valueOf(day):"");
            if(active) {
                LocalDate date=today.withDayOfMonth(day);boolean selected=day==today.getDayOfMonth();
                views.setInt(DAYS[i],"setBackgroundResource",background(date,team,enabled,selected));
            }
        }
        String lines=today.toString().equals(p.getString("widget_day",""))?p.getString("widget_lines",""):"앱에서 로그인 후 갱신해 주세요";
        views.setTextViewText(R.id.widget_events,lines);
        views.setViewVisibility(R.id.widget_events,height<280?View.GONE:View.VISIBLE);
        views.setInt(R.id.widget_events,"setMaxLines",height<300?1:3);
        views.setTextViewTextSize(R.id.widget_events,TypedValue.COMPLEX_UNIT_SP,width>=400?16:13);
        String version="";
        try { version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName; }
        catch(android.content.pm.PackageManager.NameNotFoundException ignored) {}
        views.setTextViewText(R.id.widget_status,status+(version==null||version.isEmpty()?"":" · v"+version));
        Intent open=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        Intent update=new Intent(context,CalendarWidget.class).setAction(CalendarWidget.REFRESH);
        views.setOnClickPendingIntent(R.id.widget_refresh,PendingIntent.getBroadcast(context,0,update,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        return views;
    }
    private static int background(LocalDate date,String team,boolean enabled,boolean selected) {
        if(date.getDayOfWeek().getValue()>=6) return selected?R.drawable.day_cell_weekend_today:R.drawable.day_cell_weekend;
        String shift=WidgetSummary.shift(date,team,enabled);
        if(shift.endsWith("야간"))return selected?R.drawable.day_cell_night_today:R.drawable.day_cell_night;
        if(!shift.isEmpty())return selected?R.drawable.day_cell_day_today:R.drawable.day_cell_day;
        return selected?R.drawable.day_cell_default_today:R.drawable.day_cell_default;
    }
}
