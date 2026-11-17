package org.famical.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

final class WidgetSummary {
    static String shift(LocalDate date, String team, boolean enabled) {
        if (!enabled || date.getDayOfWeek()==DayOfWeek.SATURDAY || date.getDayOfWeek()==DayOfWeek.SUNDAY) return "";
        long weeks=Math.floorDiv(ChronoUnit.DAYS.between(LocalDate.of(2026,11,16),date),7);
        boolean night=Math.floorMod(weeks,2)==0;
        if ("B".equals(team)) night=!night;
        return team+"조 "+(night?"야간":"주간");
    }
    static String monthGrid(LocalDate today) {
        YearMonth month=YearMonth.from(today);int offset=month.atDay(1).getDayOfWeek().getValue()%7;
        StringBuilder text=new StringBuilder("일 월 화 수 목 금 토\n");
        for(int i=0;i<offset;i++) text.append("   ");
        for(int day=1;day<=month.lengthOfMonth();day++) {
            text.append(String.format(Locale.ROOT,"%2d ",day));
            if((offset+day)%7==0 && day<month.lengthOfMonth()) text.append('\n');
        }
        return text.toString();
    }
    static boolean occursToday(String start,String end,LocalDate today) {
        String stamp=today.toString();return start.compareTo(stamp)<=0 && end.compareTo(stamp)>=0;
    }
}
