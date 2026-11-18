package org.famical.calendar;

import android.content.SharedPreferences;
import android.webkit.WebView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class StartupServerTest {
    private void check(String saved,String expected) throws Exception {
        SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("famical",0);
        prefs.edit().clear().commit();
        if(saved!=null)prefs.edit().putString("server",saved).commit();
        try(var controller=Robolectric.buildActivity(MainActivity.class).create()) {
            var field=MainActivity.class.getDeclaredField("browser");field.setAccessible(true);
            WebView web=(WebView)field.get(controller.get());
            assertEquals(expected,Shadows.shadowOf(web).getLastLoadedUrl());
            assertEquals(expected,prefs.getString("server",""));
        }
    }
    @Test public void firstInstallConnectsAutomatically() throws Exception {
        check(null,"https://famical.ymsh.synology.me/");
    }
    @Test public void previouslyEmptyAddressConnectsAutomatically() throws Exception {
        check("","https://famical.ymsh.synology.me/");
    }
    @Test public void explicitSavedAddressStillConnectsAutomatically() throws Exception {
        check("https://calendar.example.test/","https://calendar.example.test/");
    }
}
