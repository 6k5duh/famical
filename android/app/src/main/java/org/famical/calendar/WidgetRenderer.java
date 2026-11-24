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
    private static final int[] CELLS = {R.id.cell_0, R.id.cell_1, R.id.cell_2, R.id.cell_3, R.id.cell_4, R.id.cell_5, R.id.cell_6, R.id.cell_7, R.id.cell_8, R.id.cell_9, R.id.cell_10, R.id.cell_11, R.id.cell_12, R.id.cell_13, R.id.cell_14, R.id.cell_15, R.id.cell_16, R.id.cell_17, R.id.cell_18, R.id.cell_19, R.id.cell_20, R.id.cell_21, R.id.cell_22, R.id.cell_23, R.id.cell_24, R.id.cell_25, R.id.cell_26, R.id.cell_27, R.id.cell_28, R.id.cell_29, R.id.cell_30, R.id.cell_31, R.id.cell_32, R.id.cell_33, R.id.cell_34, R.id.cell_35, R.id.cell_36, R.id.cell_37, R.id.cell_38, R.id.cell_39, R.id.cell_40, R.id.cell_41};
    private static final int[] MARKERS = {R.id.markers_0, R.id.markers_1, R.id.markers_2, R.id.markers_3, R.id.markers_4, R.id.markers_5, R.id.markers_6, R.id.markers_7, R.id.markers_8, R.id.markers_9, R.id.markers_10, R.id.markers_11, R.id.markers_12, R.id.markers_13, R.id.markers_14, R.id.markers_15, R.id.markers_16, R.id.markers_17, R.id.markers_18, R.id.markers_19, R.id.markers_20, R.id.markers_21, R.id.markers_22, R.id.markers_23, R.id.markers_24, R.id.markers_25, R.id.markers_26, R.id.markers_27, R.id.markers_28, R.id.markers_29, R.id.markers_30, R.id.markers_31, R.id.markers_32, R.id.markers_33, R.id.markers_34, R.id.markers_35, R.id.markers_36, R.id.markers_37, R.id.markers_38, R.id.markers_39, R.id.markers_40, R.id.markers_41};
    private static final int[][] BARS = {{R.id.marker_0_0, R.id.marker_0_1, R.id.marker_0_2}, {R.id.marker_1_0, R.id.marker_1_1, R.id.marker_1_2}, {R.id.marker_2_0, R.id.marker_2_1, R.id.marker_2_2}, {R.id.marker_3_0, R.id.marker_3_1, R.id.marker_3_2}, {R.id.marker_4_0, R.id.marker_4_1, R.id.marker_4_2}, {R.id.marker_5_0, R.id.marker_5_1, R.id.marker_5_2}, {R.id.marker_6_0, R.id.marker_6_1, R.id.marker_6_2}, {R.id.marker_7_0, R.id.marker_7_1, R.id.marker_7_2}, {R.id.marker_8_0, R.id.marker_8_1, R.id.marker_8_2}, {R.id.marker_9_0, R.id.marker_9_1, R.id.marker_9_2}, {R.id.marker_10_0, R.id.marker_10_1, R.id.marker_10_2}, {R.id.marker_11_0, R.id.marker_11_1, R.id.marker_11_2}, {R.id.marker_12_0, R.id.marker_12_1, R.id.marker_12_2}, {R.id.marker_13_0, R.id.marker_13_1, R.id.marker_13_2}, {R.id.marker_14_0, R.id.marker_14_1, R.id.marker_14_2}, {R.id.marker_15_0, R.id.marker_15_1, R.id.marker_15_2}, {R.id.marker_16_0, R.id.marker_16_1, R.id.marker_16_2}, {R.id.marker_17_0, R.id.marker_17_1, R.id.marker_17_2}, {R.id.marker_18_0, R.id.marker_18_1, R.id.marker_18_2}, {R.id.marker_19_0, R.id.marker_19_1, R.id.marker_19_2}, {R.id.marker_20_0, R.id.marker_20_1, R.id.marker_20_2}, {R.id.marker_21_0, R.id.marker_21_1, R.id.marker_21_2}, {R.id.marker_22_0, R.id.marker_22_1, R.id.marker_22_2}, {R.id.marker_23_0, R.id.marker_23_1, R.id.marker_23_2}, {R.id.marker_24_0, R.id.marker_24_1, R.id.marker_24_2}, {R.id.marker_25_0, R.id.marker_25_1, R.id.marker_25_2}, {R.id.marker_26_0, R.id.marker_26_1, R.id.marker_26_2}, {R.id.marker_27_0, R.id.marker_27_1, R.id.marker_27_2}, {R.id.marker_28_0, R.id.marker_28_1, R.id.marker_28_2}, {R.id.marker_29_0, R.id.marker_29_1, R.id.marker_29_2}, {R.id.marker_30_0, R.id.marker_30_1, R.id.marker_30_2}, {R.id.marker_31_0, R.id.marker_31_1, R.id.marker_31_2}, {R.id.marker_32_0, R.id.marker_32_1, R.id.marker_32_2}, {R.id.marker_33_0, R.id.marker_33_1, R.id.marker_33_2}, {R.id.marker_34_0, R.id.marker_34_1, R.id.marker_34_2}, {R.id.marker_35_0, R.id.marker_35_1, R.id.marker_35_2}, {R.id.marker_36_0, R.id.marker_36_1, R.id.marker_36_2}, {R.id.marker_37_0, R.id.marker_37_1, R.id.marker_37_2}, {R.id.marker_38_0, R.id.marker_38_1, R.id.marker_38_2}, {R.id.marker_39_0, R.id.marker_39_1, R.id.marker_39_2}, {R.id.marker_40_0, R.id.marker_40_1, R.id.marker_40_2}, {R.id.marker_41_0, R.id.marker_41_1, R.id.marker_41_2}};
    private static final int[] ROWS = {R.id.week_0,R.id.week_1,R.id.week_2,R.id.week_3,R.id.week_4,R.id.week_5};
    static RemoteViews create(Context context,float width,float height,LocalDate today,String status) {
        SharedPreferences p=context.getSharedPreferences("famical",Context.MODE_PRIVATE);
        String team=p.getString("widget_team","A");boolean enabled=p.getBoolean("widget_shift",true);
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.calendar_widget_marks);
        views.setTextViewText(R.id.widget_title,today.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE",Locale.KOREAN)));
        views.setTextViewTextSize(R.id.widget_title,TypedValue.COMPLEX_UNIT_SP,width>=400?22:17);
        String shift=WidgetSummary.shift(today,team,enabled);
        views.setTextViewText(R.id.widget_shift,shift);
        views.setViewVisibility(R.id.widget_shift,shift.isEmpty()||height<280?View.GONE:View.VISIBLE);
        int first=YearMonth.from(today).atDay(1).getDayOfWeek().getValue()%7;
        int length=YearMonth.from(today).lengthOfMonth();int rows=(first+length+6)/7;
        for(int row=0;row<6;row++) views.setViewVisibility(ROWS[row],row<rows?View.VISIBLE:View.GONE);
        String metadata=YearMonth.from(today).toString().equals(p.getString("widget_month",""))?p.getString("widget_month_events","[]"):"[]";
        java.util.List<java.util.List<Integer>> monthColors=WidgetEvents.monthColors(metadata,YearMonth.from(today),p.getBoolean("widget_private",false));
        for(int i=0;i<42;i++) {
            int day=i-first+1;boolean active=day>=1&&day<=length;
            views.setViewVisibility(CELLS[i],active?View.VISIBLE:View.INVISIBLE);
            views.setTextViewText(DAYS[i],active?String.valueOf(day):"");
            if(active) {
                LocalDate date=today.withDayOfMonth(day);boolean selected=day==today.getDayOfMonth();
                Intent openDay=new Intent(context,MainActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(android.net.Uri.parse("famical://calendar/"+date))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                views.setOnClickPendingIntent(CELLS[i],PendingIntent.getActivity(context,0,openDay,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
                views.setInt(CELLS[i],"setBackgroundResource",background(date,team,enabled,selected));
                java.util.List<Integer> colors=monthColors.get(day);
                views.setViewVisibility(MARKERS[i],colors.isEmpty()?View.INVISIBLE:View.VISIBLE);
                for(int j=0;j<3;j++) {
                    views.setViewVisibility(BARS[i][j],j<colors.size()?View.VISIBLE:View.GONE);
                    if(j<colors.size())views.setInt(BARS[i][j],"setBackgroundColor",colors.get(j));
                }
            }
        }
        String lines=today.toString().equals(p.getString("widget_day",""))?p.getString("widget_lines",""):"앱에서 로그인 후 갱신해 주세요";
        views.setTextViewText(R.id.widget_events,lines);
        views.setViewVisibility(R.id.widget_events,height<280||(height<330&&rows==6)?View.GONE:View.VISIBLE);
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
