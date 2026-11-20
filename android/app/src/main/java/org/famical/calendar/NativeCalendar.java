package org.famical.calendar;

import android.content.Context;
import java.io.*;
import java.time.*;
import java.util.*;
import org.json.*;

/** Indexed month starts avoid an embedded browser or a large calendar dependency. */
final class NativeCalendar {
  private static NativeCalendar instance;

  static synchronized NativeCalendar get(Context context) {
    if (instance == null) instance = new NativeCalendar(context.getApplicationContext());
    return instance;
  }

  private final NavigableMap<LocalDate, JSONArray> moons = new TreeMap<>();
  private final Map<String, LocalDate> lunarStarts = new HashMap<>();
  private final JSONObject holidays;

  NativeCalendar(Context context) {
    try (InputStream in = context.getAssets().open("calendar.dat");
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(
                    new java.util.zip.GZIPInputStream(in),
                    java.nio.charset.StandardCharsets.UTF_8))) {
      StringBuilder text = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) text.append(line);
      JSONObject data = new JSONObject(text.toString());
      holidays = data.getJSONObject("holidays");
      JSONArray rows = data.getJSONArray("months");
      for (int i = 0; i < rows.length(); i++) {
        JSONArray row = rows.getJSONArray(i);
        LocalDate start = LocalDate.parse(row.getString(0));
        moons.put(start, row);
        lunarStarts.put(row.getInt(1) + "-" + row.getInt(2) + "-" + row.getInt(3), start);
      }
    } catch (Exception e) {
      throw new IllegalStateException("음력 데이터를 읽지 못했습니다.", e);
    }
  }

  static String normalize(String value) {
    return value.matches("[0-9]{8}")
        ? value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6)
        : value;
  }

  String holiday(LocalDate day) {
    return holidays.optString(day.toString(), "");
  }

  String lunar(LocalDate day) {
    Map.Entry<LocalDate, JSONArray> entry = moons.floorEntry(day);
    if (entry == null || day.isAfter(LocalDate.of(2050, 12, 31))) return "";
    JSONArray row = entry.getValue();
    return "("
        + (row.optInt(3) == 1 ? "윤" : "음")
        + " "
        + row.optInt(2)
        + "."
        + (java.time.temporal.ChronoUnit.DAYS.between(entry.getKey(), day) + 1)
        + ")";
  }

  LocalDate convert(String stamp, boolean lunar, boolean leap) {
    if (!lunar) return LocalDate.parse(stamp);
    String[] parts = stamp.split("-");
    int year = Integer.parseInt(parts[0]),
        month = Integer.parseInt(parts[1]),
        day = Integer.parseInt(parts[2]);
    LocalDate start = lunarStarts.get(year + "-" + month + "-" + (leap ? 1 : 0));
    if (start == null || day < 1 || day > 30)
      throw new IllegalArgumentException("음력 날짜·윤달을 확인하세요.");
    LocalDate result = start.plusDays(day - 1), next = moons.higherKey(start);
    if ((next != null && !result.isBefore(next)) || result.isAfter(LocalDate.of(2050, 12, 31)))
      throw new IllegalArgumentException("유효하지 않은 음력 날짜입니다.");
    return result;
  }

  List<LocalDate[]> spans(JSONObject event, int year) {
    List<LocalDate[]> spans = new ArrayList<>();
    String day = event.optString("day"), end = event.optString("end_day", day);
    int sy = Integer.parseInt(day.substring(0, 4)),
        offset = Integer.parseInt(end.substring(0, 4)) - sy;
    int[] years =
        event.optBoolean("annual") ? new int[] {year - 1, year, year + 1} : new int[] {sy};
    for (int y : years)
      try {
        LocalDate a =
            convert(
                String.format(Locale.ROOT, "%04d", y) + day.substring(4),
                event.optString("calendar_type").equals("lunar"),
                event.optBoolean("leap"));
        LocalDate b =
            convert(
                String.format(Locale.ROOT, "%04d", y + offset) + end.substring(4),
                event.optString("calendar_type").equals("lunar"),
                event.optBoolean("leap"));
        if (!b.isBefore(a) && java.time.temporal.ChronoUnit.DAYS.between(a, b) <= 365)
          spans.add(new LocalDate[] {a, b});
      } catch (RuntimeException ignored) {
        /* Invalid annual date is omitted for this year. */
      }
    return spans;
  }

  static boolean weekday(LocalDate date) {
    return date.getDayOfWeek().getValue() < 6;
  }

  static int weekdays(LocalDate start, LocalDate end) {
    int count = 0;
    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) if (weekday(d)) count++;
    return count;
  }

  void validate(JSONObject event) throws Exception {
    String day = normalize(event.getString("day")), end = normalize(event.optString("end_day", ""));
    if (end.isEmpty()) end = day;
    if (!day.matches("(19|20)[0-9]{2}-[0-9]{2}-[0-9]{2}")
        || !end.matches("(19|20)[0-9]{2}-[0-9]{2}-[0-9]{2}"))
      throw new IllegalArgumentException("날짜는 YYYY-MM-DD 형식으로 입력하세요.");
    event.put("day", day).put("end_day", end);
    boolean lunar = event.optString("calendar_type").equals("lunar"),
        leap = event.optBoolean("leap");
    LocalDate a = convert(day, lunar, leap), b = convert(end, lunar, leap);
    if (a.getYear() < 1900
        || b.getYear() > 2099
        || b.isBefore(a)
        || java.time.temporal.ChronoUnit.DAYS.between(a, b) > 365)
      throw new IllegalArgumentException("기간은 시작일부터 최대 366일입니다.");
    String title = event.optString("title").trim(), time = event.optString("clock", "");
    if (title.isEmpty() || title.length() > 100 || event.optString("note").length() > 2000)
      throw new IllegalArgumentException("제목은 1~100자, 메모는 2000자까지 입력하세요.");
    if (!time.isEmpty() && !time.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
      throw new IllegalArgumentException("시간은 HH:mm 형식으로 입력하세요.");
    event.put("title", title);
  }
}
