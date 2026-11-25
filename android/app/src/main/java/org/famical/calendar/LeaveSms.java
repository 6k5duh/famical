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
    String kind =
        dates
            .group(3)
            .replace('（', '(')
            .replace('）', ')')
            .replaceAll("\\([^()]*\\)", "")
            .split("/", 2)[0]
            .replaceAll("\\s+", "");
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
        .put("title", name + " " + shortName(kind))
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

  private static String shortName(String kind) {
    switch (kind) {
      case "연차":
      case "월간연차":
      case "년차":
        return "년차";
      case "생리휴가":
        return "생휴";
      case "경조휴가":
        return "경조";
      case "향군":
        return "향군";
      case "유급":
        return "유급";
      case "무급":
        return "무급";
      case "산전후휴가":
        return "출산";
      case "가족돌봄휴가":
        return "돌봄";
      case "유계결근":
        return "유결";
      default:
        return kind;
    }
  }

  // v2.2.0 used the full leave name in its persistent duplicate key.
  static String legacyKey(JSONObject event) throws Exception {
    String title = event.getString("title");
    int space = title.lastIndexOf(' ');
    String suffix = title.substring(space + 1), original;
    switch (suffix) {
      case "생휴":
        original = "생리휴가";
        break;
      case "경조":
        original = "경조휴가";
        break;
      case "출산":
        original = "산전후휴가";
        break;
      case "돌봄":
        original = "가족돌봄휴가";
        break;
      case "유결":
        original = "유계결근";
        break;
      default:
        return key(event);
    }
    return key(
        new JSONObject(event.toString()).put("title", title.substring(0, space + 1) + original));
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
