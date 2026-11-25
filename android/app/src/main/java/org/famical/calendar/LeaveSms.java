package org.famical.calendar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.*;
import org.json.JSONObject;

/** Parses only the explicitly supported sender and leave-request format. */
final class LeaveSms {
  static boolean senderMatches(String sender) {
    if (sender == null) return false;
    String number = sender.replaceAll("[\\s()\\-]", "");
    if (number.startsWith("+82")) number = "0" + number.substring(3);
    return number.equals("0522195000");
  }

  static JSONObject parse(String sender, String body) throws Exception {
    if (!senderMatches(sender) || body == null || body.length() > 5000) return null;
    String text = body.replace("\r", "");
    if (!Pattern.compile("\\[Web\\s*발신\\]", Pattern.CASE_INSENSITIVE).matcher(text).find()
        || !Pattern.compile("\\[휴가\\s*승인\\s*요청\\]").matcher(text).find()) return null;
    Matcher applicant = Pattern.compile("(?m)^\\h*신청자\\h*[:：]\\h*([^\\n]+?)\\h*$").matcher(text);
    Matcher dates =
        Pattern.compile(
                "(?m)^\\h*(\\d{4}-\\d{2}-\\d{2})\\h*[~～]\\h*(\\d{4}-\\d{2}-\\d{2})\\h*\\n"
                    + "\\h*([^\\n"
                    + "]+?)\\h*$")
            .matcher(text);
    if (!applicant.find() || !dates.find()) return null;
    String name = applicant.group(1).trim();
    String kind = dates.group(3).split("/", 2)[0].trim();
    if (name.isEmpty() || name.length() > 50 || !kind.matches("[가-힣 ]{1,20}")) return null;
    LocalDate start, end;
    try {
      start = LocalDate.parse(dates.group(1));
      end = LocalDate.parse(dates.group(2));
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
    if (start.getYear() < 1900
        || end.getYear() > 2099
        || end.isBefore(start)
        || ChronoUnit.DAYS.between(start, end) > 365) return null;
    return new JSONObject()
        .put("title", name + " " + (kind.equals("연차") || kind.equals("년차") ? "년차" : kind))
        .put("day", start.toString())
        .put("end_day", end.toString())
        .put("calendar_type", "solar")
        .put("annual", false)
        .put("leap", false)
        .put("category", "work")
        .put("visibility", "private")
        .put("clock", "")
        .put("color", "blue")
        .put("note", "휴가승인 요청 문자에서 자동 등록 · 승인 완료 여부는 확인되지 않았습니다.");
  }

  static String key(JSONObject event) throws Exception {
    String value =
        event.getString("title")
            + "\n"
            + event.getString("day")
            + "\n"
            + event.getString("end_day");
    byte[] hash =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder("sms-");
    for (byte b : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }
}
