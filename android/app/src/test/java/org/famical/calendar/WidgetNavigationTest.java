package org.famical.calendar;

import static org.junit.Assert.*;

import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import java.time.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetNavigationTest {
  @org.junit.Before
  public void login() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    var singleton = NativeStore.class.getDeclaredField("instance");
    singleton.setAccessible(true);
    singleton.set(null, null);
    var state = NativeStore.class.getDeclaredField("state");
    state.setAccessible(true);
    state.set(
        NativeStore.get(context),
        new org.json.JSONObject()
            .put("user", new org.json.JSONObject().put("id", 1).put("name", "가족")));
  }

  private Intent tap(LocalDate month, int cell) {
    Context context = RuntimeEnvironment.getApplication();
    View widget =
        WidgetRenderer.create(context, 300, 480, month, "갱신")
            .apply(context, new FrameLayout(context));
    assertTrue(widget.findViewById(cell).performClick());
    return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
  }

  private Object field(MainActivity activity, String name) throws Exception {
    java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void selected(MainActivity activity, String date) throws Exception {
    assertEquals(LocalDate.parse(date), field(activity, "selectedDate"));
    assertEquals(YearMonth.from(LocalDate.parse(date)), field(activity, "month"));
    assertEquals(true, field(activity, "collapsed"));
    assertTrue(
        ((android.widget.TextView) field(activity, "agendaTitle"))
            .getText()
            .toString()
            .startsWith(
                LocalDate.parse(date).getMonthValue()
                    + "월 "
                    + LocalDate.parse(date).getDayOfMonth()
                    + "일"));
  }

  @Test
  public void widgetDayOpensAgendaAndRunningActivityAcceptsAnotherDay() throws Exception {
    Intent day24 = tap(LocalDate.of(2026, 11, 24), R.id.cell_23);
    Intent day25 = tap(LocalDate.of(2026, 11, 24), R.id.cell_24);
    assertFalse(day24.filterEquals(day25));
    assertEquals(MainActivity.class.getName(), day24.getComponent().getClassName());
    assertTrue((day24.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    var controller = Robolectric.buildActivity(MainActivity.class, day24).create();
    MainActivity activity = controller.get();
    selected(activity, "2026-11-24");
    controller.newIntent(day25);
    selected(activity, "2026-11-25");
    controller.newIntent(tap(LocalDate.of(2027, 1, 1), R.id.cell_5));
    selected(activity, "2027-01-01");
    controller.destroy();
  }

  @Test
  public void invalidDatesDoNotChangeSelectionAndSavedStateWinsOnRecreation() throws Exception {
    Intent intent = tap(LocalDate.of(2026, 11, 24), R.id.cell_23);
    var controller = Robolectric.buildActivity(MainActivity.class, intent).create();
    for (String path : new String[] {"2026-02-30", "2100-01-01", "bad"}) {
      controller.newIntent(
          new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("famical://calendar/" + path)));
      selected(controller.get(), "2026-11-24");
    }
    Bundle state = new Bundle();
    controller.saveInstanceState(state);
    state.putString("calendarMonth", "2026-12");
    state.putString("selectedDate", "2026-12-10");
    controller.destroy();
    var restored = Robolectric.buildActivity(MainActivity.class, intent).create(state);
    selected(restored.get(), "2026-12-10");
    restored.destroy();
  }
}
