package org.famical.calendar;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StartupServerTest {
  private void check(String saved, String expected) throws Exception {
    SharedPreferences prefs =
        RuntimeEnvironment.getApplication().getSharedPreferences("famical", 0);
    prefs.edit().clear().commit();
    if (saved != null) prefs.edit().putString("server", saved).commit();
    try (var controller = Robolectric.buildActivity(MainActivity.class).create()) {
      var field = MainActivity.class.getDeclaredField("grid");
      field.setAccessible(true);
      assertTrue(field.get(controller.get()) instanceof NativeMonthView);
      assertEquals(expected, prefs.getString("server", ""));
    }
  }

  @Test
  public void firstInstallUsesDefaultServer() throws Exception {
    check(null, "https://famical.ymsh.synology.me/");
  }

  @Test
  public void emptyAddressUsesDefaultServer() throws Exception {
    check("", "https://famical.ymsh.synology.me/");
  }

  @Test
  public void savedServerStillUsed() throws Exception {
    check("https://calendar.example.test/", "https://calendar.example.test/");
  }
}
