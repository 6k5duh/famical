package org.famical.calendar;

import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

final class WidgetEvents {
    static final class Snapshot {
        final String monthEvents, todayLines;
        Snapshot(String monthEvents,String todayLines) { this.monthEvents=monthEvents;this.todayLines=todayLines; }
    }
    static Snapshot parse(String body,LocalDate today,boolean includePrivate) throws JSONException {
        JSONArray response=new JSONArray(body), visible=new JSONArray();List<String> lines=new ArrayList<>();
        for(int i=0;i<response.length();i++) {
            JSONObject event=response.getJSONObject(i);
            boolean personal="private".equals(event.optString("visibility","shared"));
            if(personal&&!includePrivate)continue;
            String start=event.optString("day",""),end=event.optString("end_day",start);
            if(end.isEmpty())end=start;
            try { if(LocalDate.parse(end).isBefore(LocalDate.parse(start)))continue; }
            catch(java.time.format.DateTimeParseException invalid){continue;}
            // Cache only date/color/visibility metadata for the full-month markers.
            JSONObject marker=new JSONObject().put("day",start).put("end_day",end)
                    .put("color",event.optString("color","sage")).put("visibility",personal?"private":"shared");
            visible.put(marker);
            if(lines.size()<3&&WidgetSummary.occursToday(start,end,today)) {
                String time=event.optString("clock","");lines.add((time.isEmpty()?"종일":time)+"  "+event.optString("title","일정"));
            }
        }
        return new Snapshot(visible.toString(),lines.isEmpty()?"오늘 표시할 일정이 없습니다":String.join("\n",lines));
    }
    static List<List<Integer>> monthColors(String metadata,YearMonth month,boolean includePrivate) {
        List<List<Integer>> result=new ArrayList<>();for(int i=0;i<=month.lengthOfMonth();i++)result.add(new ArrayList<>(3));
        try {
            JSONArray events=new JSONArray(metadata);
            for(int i=0;i<events.length();i++) {
                JSONObject event=events.getJSONObject(i);
                if("private".equals(event.optString("visibility"))&&!includePrivate)continue;
                LocalDate start,end;
                try { start=LocalDate.parse(event.getString("day"));end=LocalDate.parse(event.optString("end_day",event.getString("day"))); }
                catch(java.time.format.DateTimeParseException invalid){continue;}
                if(start.isBefore(month.atDay(1)))start=month.atDay(1);
                if(end.isAfter(month.atEndOfMonth()))end=month.atEndOfMonth();
                if(end.isBefore(start))continue;
                for(int day=start.getDayOfMonth();day<=end.getDayOfMonth();day++) {
                    List<Integer> colors=result.get(day);if(colors.size()<3)colors.add(color(event.optString("color","sage")));
                }
            }
        } catch(JSONException ignored) { /* Missing/corrupt cache: no schedule markers. */ }
        return result;
    }
    static final String[] COLOR_IDS = {"sage","blue","rose","amber","violet","red","coral","orange","gold","lime","teal","cyan","indigo","magenta","brown","slate"};
    static final String[] COLOR_LABELS = {"숲초록","파랑","진분홍","황토","보라","빨강","산호","주황","금빛","올리브","청록","하늘","남보라","자홍","갈색","회색"};
    private static final int[] COLORS = {0xff36705b,0xff3568a1,0xffa74d6d,0xff9d5c1d,0xff7454a1,0xffb52e32,0xffb9473a,0xffb84b00,0xff876500,0xff4f7200,0xff007a70,0xff007594,0xff5146b8,0xffac287a,0xff84532b,0xff52616b};
    static int color(String value) {
        int index = java.util.Arrays.asList(COLOR_IDS).indexOf(value);
        return COLORS[Math.max(0,index)];
    }
}
