package org.famical.calendar;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
public class WidgetSummaryTest {
    @Test public void alternatesMondayAndSkipsWeekends() {
        assertEquals("A조 야간",WidgetSummary.shift(LocalDate.of(2026,11,17),"A",true));
        assertEquals("A조 주간",WidgetSummary.shift(LocalDate.of(2026,11,23),"A",true));
        assertEquals("B조 야간",WidgetSummary.shift(LocalDate.of(2026,11,23),"B",true));
        assertEquals("",WidgetSummary.shift(LocalDate.of(2026,11,21),"A",true));
        assertEquals("",WidgetSummary.shift(LocalDate.of(2026,11,17),"A",false));
    }
    @Test public void calendarKeepsAllDatesAndWeekBoundaries() {
        String november=WidgetSummary.monthGrid(LocalDate.of(2026,11,17));
        assertTrue(november.startsWith("일 월 화 수 목 금 토\n 1  2  3  4  5  6  7 \n"));assertTrue(november.endsWith("29 30 "));
        String leap=WidgetSummary.monthGrid(LocalDate.of(2028,2,1));assertTrue(leap.contains("29 "));
    }
    @Test public void includesBothEndsOfMultiDayEvents() {
        assertTrue(WidgetSummary.occursToday("2026-11-17","2026-11-20",LocalDate.of(2026,11,17)));
        assertTrue(WidgetSummary.occursToday("2026-11-17","2026-11-20",LocalDate.of(2026,11,20)));
        assertFalse(WidgetSummary.occursToday("2026-11-17","2026-11-20",LocalDate.of(2026,11,21)));
    }
}
