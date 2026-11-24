package org.famical.calendar;

import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.time.*;
import java.util.*;
import org.json.*;

final class NativeMonthView extends View {
  static final class Entry {
    final String key;
    final JSONObject event;
    final LocalDate start, end;

    Entry(String key, JSONObject event, LocalDate start, LocalDate end) {
      this.key = key;
      this.event = event;
      this.start = start;
      this.end = end;
    }

    String progress(LocalDate day) {
      return event.optString("category").equals("work") && !start.equals(end)
          ? " ("
              + NativeCalendar.weekdays(start, day)
              + "/"
              + NativeCalendar.weekdays(start, end)
              + ")"
          : "";
    }
  }

  private final NativeCalendar calendar;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ArrayList<List<Entry>> items = new ArrayList<>();
  private LocalDate first;
  private YearMonth month = YearMonth.now();
  private JSONObject user = new JSONObject();
  private int count = 35;
  private Bitmap previousMonth;
  private ValueAnimator monthAnimator;
  private float monthProgress = 1;
  private int monthDirection;

  void animateMonth(int direction) {
    if (!ValueAnimator.areAnimatorsEnabled()
        || getWidth() <= 0
        || getHeight() <= 0
        || first == null) {
      finishMonthAnimation();
      return;
    }
    Bitmap snapshot = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
    draw(new Canvas(snapshot));
    finishMonthAnimation();
    previousMonth = snapshot;
    monthDirection = direction > 0 ? 1 : -1;
    monthProgress = 0;
    ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
    monthAnimator = animator;
    animator.setDuration(240);
    animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
    animator.addUpdateListener(
        value -> {
          monthProgress = (float) value.getAnimatedValue();
          invalidate();
        });
    animator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            if (monthAnimator == animation) {
              monthAnimator = null;
              previousMonth = null;
              monthProgress = 1;
              invalidate();
            }
          }
        });
    animator.start();
  }

  void finishMonthAnimation() {
    ValueAnimator animator = monthAnimator;
    monthAnimator = null;
    if (animator != null) animator.cancel();
    previousMonth = null;
    monthProgress = 1;
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    super.onSizeChanged(w, h, oldW, oldH);
    if (w != oldW || h != oldH) finishMonthAnimation();
  }

  @Override
  protected void onDetachedFromWindow() {
    finishMonthAnimation();
    super.onDetachedFromWindow();
  }

  private float downX, downY;
  private boolean tracking, moved;
  private LocalDate selected;
  private boolean compact;
  private java.util.function.IntConsumer moveMonth = delta -> {};
  private java.util.function.Consumer<Boolean> collapse = value -> {};

  void gestures(
      java.util.function.IntConsumer moveMonth, java.util.function.Consumer<Boolean> collapse) {
    this.moveMonth = moveMonth;
    this.collapse = collapse;
  }

  void selection(LocalDate date, boolean compact) {
    selected = date;
    this.compact = compact;
    invalidate();
  }

  List<Entry> entries(LocalDate date) {
    if (first == null) return Collections.emptyList();
    long index = java.time.temporal.ChronoUnit.DAYS.between(first, date);
    return index >= 0 && index < items.size()
        ? new ArrayList<>(items.get((int) index))
        : Collections.emptyList();
  }

  private java.util.function.BiConsumer<LocalDate, List<Entry>> select = (day, entries) -> {};

  NativeMonthView(Context context, NativeCalendar calendar) {
    super(context);
    this.calendar = calendar;
    setContentDescription("월 달력. 좌우로 밀어 월 이동, 위로 밀어 일정 목록, 아래로 밀어 달력 펼치기.");
  }

  void display(
      YearMonth month,
      JSONObject user,
      Map<String, JSONObject> events,
      String category,
      boolean mine,
      java.util.function.BiConsumer<LocalDate, List<Entry>> select) {
    this.month = month;
    this.user = user;
    this.select = select;
    int offset = month.atDay(1).getDayOfWeek().getValue() % 7;
    first = month.atDay(1).minusDays(offset);
    count = (offset + month.lengthOfMonth() + 6) / 7 * 7;
    items.clear();
    for (int i = 0; i < count; i++) items.add(new ArrayList<>());
    for (Map.Entry<String, JSONObject> item : events.entrySet()) {
      JSONObject e = item.getValue();
      if ((mine && e.optLong("owner") != user.optLong("id"))
          || (!category.equals("all") && !category.equals(e.optString("category")))) continue;
      for (LocalDate[] span : calendar.spans(e, month.getYear()))
        for (int i = 0; i < count; i++) {
          LocalDate day = first.plusDays(i);
          if (day.isBefore(span[0])
              || day.isAfter(span[1])
              || (e.optString("category").equals("work")
                  && !span[0].equals(span[1])
                  && !NativeCalendar.weekday(day))) continue;
          items.get(i).add(new Entry(item.getKey(), e, span[0], span[1]));
        }
    }
    for (List<Entry> entries : items)
      entries.sort(Comparator.comparing(e -> e.event.optString("clock")));
    invalidate();
  }

  private float dp(float n) {
    return n * getResources().getDisplayMetrics().density;
  }

  private void text(
      Canvas canvas, String text, float x, float y, float size, int color, float width) {
    paint.setColor(color);
    paint.setTextSize(dp(size));
    paint.setTypeface(Typeface.DEFAULT);
    String fitted =
        android.text.TextUtils.ellipsize(
                text,
                new android.text.TextPaint(paint),
                width,
                android.text.TextUtils.TruncateAt.END)
            .toString();
    canvas.drawText(fitted, x, y, paint);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (first == null) return;
    if (previousMonth != null) {
      int frame = canvas.save();
      canvas.clipRect(0, 0, getWidth(), getHeight());
      canvas.drawBitmap(previousMonth, -monthDirection * monthProgress * getWidth(), 0, null);
      canvas.translate(monthDirection * (1 - monthProgress) * getWidth(), 0);
      drawMonth(canvas);
      canvas.restoreToCount(frame);
    } else drawMonth(canvas);
  }

  private void drawMonth(Canvas canvas) {
    float width = getWidth() / 7f, header = dp(22), height = (getHeight() - header) / (count / 7f);
    for (int col = 0; col < 7; col++)
      text(
          canvas,
          new String[] {"일", "월", "화", "수", "목", "금", "토"}[col],
          col * width + dp(5),
          dp(15),
          11,
          col == 0 || col == 6 ? 0xffa6485b : 0xff285449,
          width - dp(5));
    for (int i = 0; i < count; i++) {
      LocalDate day = first.plusDays(i);
      float left = i % 7 * width, top = header + i / 7 * height;
      int layer =
          canvas.saveLayerAlpha(
              left, top, left + width, top + height, YearMonth.from(day).equals(month) ? 255 : 140);
      String holiday = calendar.holiday(day),
          shift =
              WidgetSummary.shift(
                  day, user.optString("shift_team", "A"), user.optInt("shift_on", 1) != 0);
      if (shift.contains(" ")) shift = shift.substring(shift.indexOf(' ') + 1);
      boolean red = !NativeCalendar.weekday(day) || !holiday.isEmpty();
      paint.setColor(
          red
              ? 0xfff3dfe1
              : shift.equals("야간") ? 0xfff7efbb : shift.equals("주간") ? 0xffdce9d8 : Color.WHITE);
      canvas.clipRect(left, top, left + width, top + height);
      canvas.drawRect(left, top, left + width, top + height, paint);
      if (compact && day.equals(selected)) {
        paint.setColor(0x30285449);
        canvas.drawRect(left, top, left + width, top + height, paint);
      }
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(day.equals(LocalDate.now()) ? 2 : 0.5f));
      paint.setColor(day.equals(LocalDate.now()) ? 0xffd83d3d : 0xffc4ceca);
      canvas.drawRect(left + 1, top + 1, left + width - 1, top + height - 1, paint);
      paint.setStyle(Paint.Style.FILL);
      text(
          canvas,
          String.valueOf(day.getDayOfMonth()),
          left + dp(4),
          top + dp(17),
          14,
          red ? 0xffa6485b : 0xff233a32,
          width - dp(7));
      text(canvas, shift, left + width - dp(22), top + dp(13), 8, 0xff57654d, dp(22));
      text(canvas, calendar.lunar(day), left + dp(3), top + dp(29), 8, 0xff65705a, width - dp(6));
      if (!holiday.isEmpty())
        text(canvas, holiday, left + dp(3), top + dp(41), 8, 0xffa02e38, width - dp(6));
      float start = top + dp(holiday.isEmpty() ? 34 : 46);
      int capacity = Math.max(0, (int) ((top + height - start) / dp(20)) - 1);
      List<Entry> entries = items.get(i);
      for (int n = 0; n < Math.min(capacity, entries.size()); n++) {
        Entry entry = entries.get(n);
        JSONObject e = entry.event;
        float y = start + n * dp(20);
        paint.setColor(WidgetEvents.color(e.optString("color")));
        canvas.drawRoundRect(
            left + dp(2), y, left + width - dp(2), y + dp(18), dp(2), dp(2), paint);
        String progress = entry.progress(day);
        paint.setTextSize(dp(9));
        float progressWidth = paint.measureText(progress);
        String label =
            e.optString("clock")
                + (e.optString("clock").isEmpty() ? "" : " ")
                + e.optString("title");
        text(
            canvas,
            label,
            left + dp(4),
            y + dp(13),
            9,
            Color.WHITE,
            Math.max(0, width - dp(8) - progressWidth));
        if (!progress.isEmpty())
          text(
              canvas,
              progress,
              left + width - dp(3) - progressWidth,
              y + dp(13),
              9,
              Color.WHITE,
              progressWidth + 1);
      }
      if (entries.size() > capacity)
        text(
            canvas,
            "+" + (entries.size() - capacity) + "개",
            left + dp(4),
            top + height - dp(5),
            10,
            0xff285449,
            width - dp(8));
      canvas.restoreToCount(layer);
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downX = event.getX();
        downY = event.getY();
        tracking = true;
        moved = false;
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
      case MotionEvent.ACTION_CANCEL:
        tracking = false;
        return true;
      case MotionEvent.ACTION_MOVE:
        if (Math.hypot(event.getX() - downX, event.getY() - downY)
            > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
        return true;
      case MotionEvent.ACTION_UP:
        if (!tracking || first == null) return true;
        tracking = false;
        float dx = event.getX() - downX, dy = event.getY() - downY;
        float threshold =
            Math.max(dp(48), ViewConfiguration.get(getContext()).getScaledTouchSlop() * 3);
        if (Math.abs(dx) >= threshold && Math.abs(dx) > Math.abs(dy) * 1.25f) {
          moveMonth.accept(dx < 0 ? 1 : -1);
          return true;
        }
        if (Math.abs(dy) >= threshold && Math.abs(dy) > Math.abs(dx) * 1.25f) {
          collapse.accept(dy < 0);
          return true;
        }
        if (monthAnimator != null) return true;
        if (moved || Math.hypot(dx, dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop())
          return true;
        float header = dp(22);
        if (event.getX() < 0
            || event.getX() >= getWidth()
            || event.getY() < header
            || event.getY() >= getHeight()) return true;
        int column = (int) (event.getX() / (getWidth() / 7f));
        int row = (int) ((event.getY() - header) / ((getHeight() - header) / (count / 7f)));
        int index = row * 7 + column;
        if (index >= 0 && index < count) select.accept(first.plusDays(index), items.get(index));
        performClick();
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
