package org.famical.calendar;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.JSONObject;

final class DeviceCalendars {
    static final class Choice {
        final long id;final String label;
        Choice(long id,String label){this.id=id;this.label=label;}
        @Override public String toString(){return label;}
    }
    static List<Choice> choices(ContentResolver resolver) {
        List<Choice> result=new ArrayList<>();
        try(Cursor c=resolver.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{"_id","calendar_displayName","account_type"},
                "calendar_access_level>=? AND sync_events=1",new String[]{"500"},"calendar_displayName ASC")) {
            if(c==null)throw new IllegalStateException("캘린더 목록을 읽을 수 없습니다.");
            while(c.moveToNext())result.add(new Choice(c.getLong(0),c.getString(1)+" ("+c.getString(2)+")"));
        }return result;
    }
    static final class Item {
        long id,start,end;boolean allDay,recurring;String title,note,marker="",zone="";
        String key(){return (recurring?"r:":"n:")+id+(recurring?":"+start:"");}
        JSONObject payload() throws Exception {
            ZoneId tz=allDay?ZoneOffset.UTC:ZoneId.systemDefault();
            LocalDateTime a=Instant.ofEpochMilli(start).atZone(tz).toLocalDateTime();
            LocalDateTime b=Instant.ofEpochMilli(Math.max(start,end-1)).atZone(tz).toLocalDateTime();
            return new JSONObject().put("title",displayTitle())
                .put("note",note==null?"":note).put("day",a.toLocalDate().toString()).put("end_day",b.toLocalDate().toString())
                .put("clock",allDay?"":a.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        private String displayTitle() {
            String value=title==null||title.isEmpty()?"(제목 없음)":title;
            return marker.startsWith("famical://sync/")&&!marker.endsWith("/single")&&value.startsWith("보기 · ")?value.substring(5):value;
        }
        String hash() throws Exception {
            // Include duration/timezone/recurrence identity, not just the fields shown by the web app.
            String value=id+"\n"+start+"\n"+end+"\n"+allDay+"\n"+recurring+"\n"+title+"\n"+note+"\n"+zone;
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();
        }
    }
    static final class Snapshot {
        final Map<String,Item> items=new LinkedHashMap<>();
        final Set<Long> existing=new HashSet<>();
    }
    private static final String[] COLUMNS={"_id","title","description","dtstart","dtend","allDay","rrule","rdate","original_id","deleted","customAppUri","eventTimezone"};
    static Item one(ContentResolver resolver,long calendar,long id) {
        try(Cursor c=resolver.query(CalendarContract.Events.CONTENT_URI,COLUMNS,"calendar_id=? AND _id=? AND deleted=0",new String[]{""+calendar,""+id},null)) {
            if(c==null||!c.moveToFirst())return null;
            Item i=new Item();i.id=c.getLong(0);i.title=c.getString(1);i.note=c.getString(2);i.start=c.getLong(3);i.allDay=c.getInt(5)!=0;
            i.end=c.isNull(4)?i.start+(i.allDay?86400000:3600000):c.getLong(4);
            i.zone=c.getString(11)==null?"":c.getString(11);i.marker=c.getString(10)==null?"":c.getString(10);return i;
        }
    }
    static Snapshot read(ContentResolver resolver,long calendar,long first,long last) {
        boolean found=choices(resolver).stream().anyMatch(c->c.id==calendar);
        if(!found)throw new IllegalStateException("선택한 캘린더가 없거나 쓰기 권한·동기화가 해제되었습니다. 삭제 연동을 중단했습니다.");
        Snapshot result=new Snapshot();Map<Long,Item> repeating=new HashMap<>();
        try(Cursor c=resolver.query(CalendarContract.Events.CONTENT_URI,COLUMNS,"calendar_id=?",new String[]{String.valueOf(calendar)},null)) {
            if(c==null)throw new IllegalStateException("일정 읽기 실패: 삭제 연동을 중단했습니다.");
            while(c.moveToNext()) {
                if(c.getInt(9)!=0)continue;
                Item i=new Item();i.id=c.getLong(0);i.title=c.getString(1);i.note=c.getString(2);i.start=c.getLong(3);
                i.allDay=c.getInt(5)!=0;i.end=c.isNull(4)?i.start+(i.allDay?86400000:3600000):c.getLong(4);
                i.recurring=(!c.isNull(6)&&!c.getString(6).isEmpty())||(!c.isNull(7)&&!c.getString(7).isEmpty())||!c.isNull(8);
                i.marker=c.getString(10)==null?"":c.getString(10);i.zone=c.getString(11)==null?"":c.getString(11);
                result.existing.add(i.id);
                if(i.recurring)repeating.put(i.id,i);else result.items.put(i.key(),i);
                if(result.existing.size()>10000)throw new IllegalStateException("선택한 캘린더에 일정이 너무 많습니다.");
            }
        }
        Uri.Builder uri=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(uri,first);ContentUris.appendId(uri,last);
        try(Cursor c=resolver.query(uri.build(),new String[]{"event_id","begin","end"},"calendar_id=?",new String[]{String.valueOf(calendar)},"begin ASC")) {
            if(c==null)throw new IllegalStateException("반복 일정 읽기 실패: 삭제 연동을 중단했습니다.");
            while(c.moveToNext()) {
                Item base=repeating.get(c.getLong(0));if(base==null)continue;
                Item i=new Item();i.id=base.id;i.title=base.title;i.note=base.note;i.allDay=base.allDay;
                i.start=c.getLong(1);i.end=c.getLong(2);i.recurring=true;i.zone=base.zone;i.marker=base.marker;
                result.items.put(i.key(),i);
                if(result.items.size()>10000)throw new IllegalStateException("반복 일정 건수가 너무 많습니다.");
            }
        }return result;
    }
    static ContentValues values(JSONObject event,Item previous,long calendar,String marker) throws Exception {
        boolean allDay=event.optString("clock").isEmpty();ZoneId tz=allDay?ZoneOffset.UTC:ZoneId.systemDefault();
        LocalDate day=LocalDate.parse(event.getString("day")),last=LocalDate.parse(event.getString("end_day"));
        LocalTime time=allDay?LocalTime.MIDNIGHT:LocalTime.parse(event.getString("clock"));
        long begin=day.atTime(time).atZone(tz).toInstant().toEpochMilli();long end;
        if(allDay)end=last.plusDays(1).atStartOfDay(tz).toInstant().toEpochMilli();
        else if(previous!=null&&!previous.allDay) {
            // Preserve exact end time/duration where the web model has no end-time field.
            JSONObject before=previous.payload();
            if(before.getString("day").equals(event.getString("day"))&&before.getString("end_day").equals(event.getString("end_day"))&&before.getString("clock").equals(event.getString("clock")))end=previous.end;
            else {
                LocalTime endTime=Instant.ofEpochMilli(previous.end).atZone(tz).toLocalTime();
                end=(endTime.equals(LocalTime.MIDNIGHT)?last.plusDays(1):last).atTime(endTime).atZone(tz).toInstant().toEpochMilli();
                if(end<=begin)end=begin+Math.max(60000,previous.end-previous.start);
            }
        } else end=last.atTime(time).atZone(tz).toInstant().toEpochMilli()+3600000;
        ContentValues v=new ContentValues();v.put("title",(event.optBoolean("projection")?"보기 · ":"")+event.getString("title"));v.put("description",event.optString("note"));
        v.put("dtstart",begin);v.put("dtend",end);v.put("allDay",allDay?1:0);
        v.put("eventTimezone",allDay?"UTC":ZoneId.systemDefault().getId());
        if(previous==null){v.put("calendar_id",calendar);v.put("customAppPackage","org.famical.calendar");v.put("customAppUri",marker);}
        // Attendees, alarms, location, availability and account ownership are left intact.
        return v;
    }
    static long insert(ContentResolver resolver,JSONObject event,long calendar,String marker) throws Exception {
        Uri uri=resolver.insert(CalendarContract.Events.CONTENT_URI,values(event,null,calendar,marker));
        if(uri==null)throw new IllegalStateException("휴대전화 일정 생성 실패");return ContentUris.parseId(uri);
    }
    static void change(ContentResolver resolver,Item before,JSONObject event,long calendar,boolean delete) throws Exception {
        if(before.recurring)throw new IllegalStateException("반복 일정은 원본 캘린더에서 수정해 주세요.");
        String where="_id=? AND calendar_id=? AND dtstart=? AND dtend=? AND allDay=? AND COALESCE(rrule,'')='' AND COALESCE(rdate,'')='' AND original_id IS NULL AND COALESCE(title,'')=? AND COALESCE(description,'')=?";
        String[] args={""+before.id,""+calendar,""+before.start,""+before.end,before.allDay?"1":"0",before.title==null?"":before.title,before.note==null?"":before.note};
        int changed=delete?resolver.delete(CalendarContract.Events.CONTENT_URI,where,args):resolver.update(CalendarContract.Events.CONTENT_URI,values(event,before,calendar,""),where,args);
        if(changed!=1)throw new IllegalStateException("휴대전화 일정이 변경되었습니다. 다시 동기화해 주세요.");
    }
}
