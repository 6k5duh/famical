package org.famical.calendar;

import static org.junit.Assert.*;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CalendarGestureTest {
  private NativeMonthView calendar;
  private AtomicInteger month, taps;
  private List<Boolean> collapsed;
  private float density;
  private JSONObject user;

  @Before
  public void setup() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    density = context.getResources().getDisplayMetrics().density;
    user =
        new JSONObject().put("id", 1).put("name", "가족").put("shift_team", "A").put("shift_on", 1);
    calendar = new NativeMonthView(context, NativeCalendar.get(context));
    calendar.layout(0, 0, (int) (360 * density), (int) (500 * density));
    month = new AtomicInteger();
    taps = new AtomicInteger();
    collapsed = new ArrayList<>();
    calendar.gestures(month::addAndGet, collapsed::add);
    calendar.display(
        YearMonth.of(2026, 11),
        user,
        new LinkedHashMap<>(),
        "all",
        false,
        (day, entries) -> taps.incrementAndGet());
  }

  private void event(int action, float x, float y) {
    MotionEvent e = MotionEvent.obtain(0, 100, action, x * density, y * density, 0);
    calendar.onTouchEvent(e);
    e.recycle();
  }

  private void swipe(float x, float y, float endX, float endY) {
    event(MotionEvent.ACTION_DOWN, x, y);
    event(MotionEvent.ACTION_MOVE, endX, endY);
    event(MotionEvent.ACTION_UP, endX, endY);
  }

  @Test
  public void horizontalAndVerticalGesturesNeverOpenDateEditor() {
    swipe(280, 200, 60, 205);
    assertEquals(1, month.get());
    swipe(60, 200, 280, 200);
    assertEquals(0, month.get());
    swipe(150, 380, 150, 100);
    swipe(150, 100, 150, 380);
    assertEquals(Arrays.asList(true, false), collapsed);
    assertEquals(0, taps.get());
  }

  @Test
  public void tapsWorkButCancelDiagonalAndShortDragsDoNotSelect() {
    swipe(120, 100, 120, 100);
    assertEquals(1, taps.get());
    swipe(120, 100, 145, 100);
    swipe(30, 100, 150, 220);
    event(MotionEvent.ACTION_DOWN, 100, 100);
    event(MotionEvent.ACTION_CANCEL, 100, 100);
    event(MotionEvent.ACTION_UP, 100, 100);
    assertEquals(1, taps.get());
    assertEquals(0, month.get());
    assertTrue(collapsed.isEmpty());
  }

  @Test
  public void monthAnimationMovesBothPagesAndReleasesSnapshot() throws Exception {
    calendar.animateMonth(1);
    calendar.display(
        YearMonth.of(2026, 12),
        user,
        new LinkedHashMap<>(),
        "all",
        false,
        (day, entries) -> taps.incrementAndGet());
    assertNotNull(field(calendar, "previousMonth"));
    android.animation.ValueAnimator animator =
        (android.animation.ValueAnimator) field(calendar, "monthAnimator");
    animator.setCurrentPlayTime(100);
    float progress = (float) field(calendar, "monthProgress");
    assertTrue(progress > 0 && progress < 1);
    Bitmap image =
        Bitmap.createBitmap(calendar.getWidth(), calendar.getHeight(), Bitmap.Config.ARGB_8888);
    calendar.draw(new Canvas(image));
    swipe(120, 100, 120, 100);
    assertEquals(0, taps.get());
    calendar.animateMonth(-1);
    assertNotNull(field(calendar, "previousMonth"));
    ((android.animation.ValueAnimator) field(calendar, "monthAnimator")).end();
    assertNull(field(calendar, "previousMonth"));
    assertNull(field(calendar, "monthAnimator"));
    swipe(120, 100, 120, 100);
    assertEquals(1, taps.get());
    calendar.animateMonth(1);
    calendar.layout(0, 0, calendar.getWidth() + 20, calendar.getHeight());
    assertNull(field(calendar, "previousMonth"));
  }

  private Object field(Object target, String name) throws Exception {
    var f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  @Test
  public void collapsedAgendaHasThreeRowsAndUpdatesOnMonthSwipe() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    context
        .getSharedPreferences("famical", 0)
        .edit()
        .clear()
        .putString("server", "https://gesture.example.test/")
        .commit();
    var singleton = NativeStore.class.getDeclaredField("instance");
    singleton.setAccessible(true);
    singleton.set(null, null);
    NativeStore store = NativeStore.get(context);
    var stateField = NativeStore.class.getDeclaredField("state");
    stateField.setAccessible(true);
    LocalDate today = LocalDate.now();
    JSONArray events = new JSONArray();
    for (int i = 0; i < 5; i++)
      events.put(
          new JSONObject()
              .put("id", i + 1)
              .put("owner", 1)
              .put("title", "일정 " + (i + 1))
              .put("day", today.toString())
              .put("end_day", today.toString())
              .put("color", "blue")
              .put("category", "family"));
    stateField.set(store, new JSONObject().put("user", user).put("events", events));
    try (var controller = Robolectric.buildActivity(MainActivity.class).create()) {
      MainActivity activity = controller.get();
      LinearLayout root = (LinearLayout) field(activity, "root");
      root.measure(
          View.MeasureSpec.makeMeasureSpec((int) (390 * density), View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec((int) (780 * density), View.MeasureSpec.EXACTLY));
      root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
      calendar = (NativeMonthView) field(activity, "grid");
      int expandedHeight = calendar.getHeight();
      assertEquals(View.GONE, ((TextView) field(activity, "status")).getVisibility());
      swipe(150, 300, 150, 100);
      android.animation.ValueAnimator folding =
          (android.animation.ValueAnimator) field(activity, "collapseAnimator");
      assertNotNull(folding);
      folding.setCurrentPlayTime(120);
      LinearLayout animatedPanel = (LinearLayout) field(activity, "agendaPanel");
      assertTrue(
          animatedPanel.getLayoutParams().height > 0
              && animatedPanel.getLayoutParams().height < Math.round(212 * density));
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
      root.measure(
          View.MeasureSpec.makeMeasureSpec((int) (390 * density), View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec((int) (780 * density), View.MeasureSpec.EXACTLY));
      root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
      LinearLayout panel = (LinearLayout) field(activity, "agendaPanel");
      ListView list = (ListView) field(activity, "agendaList");
      assertEquals(View.VISIBLE, panel.getVisibility());
      assertEquals(5, list.getAdapter().getCount());
      assertEquals(Math.round(168 * density), list.getLayoutParams().height);
      assertEquals(
          Math.round(56 * density),
          list.getAdapter().getView(0, null, list).getLayoutParams().height);
      assertEquals(expandedHeight - panel.getHeight(), calendar.getHeight());
      Bitmap bitmap =
          Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
      root.draw(new Canvas(bitmap));
      try (var output = new java.io.FileOutputStream("/tmp/famical-android-collapsed.png")) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
      }
      swipe(280, 150, 70, 150);
      assertEquals(YearMonth.from(today).plusMonths(1), field(activity, "month"));
      assertEquals(1, list.getAdapter().getCount());
      assertFalse(list.getAdapter().isEnabled(0));
      swipe(150, 100, 150, 250);
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
      assertEquals(View.GONE, panel.getVisibility());
      swipe(150, 300, 150, 100);
      ((android.animation.ValueAnimator) field(activity, "collapseAnimator"))
          .setCurrentPlayTime(100);
      swipe(150, 100, 150, 300);
      ((android.animation.ValueAnimator) field(activity, "collapseAnimator")).end();
      assertEquals(View.GONE, panel.getVisibility());
      assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, panel.getLayoutParams().height);
    }
  }
}
