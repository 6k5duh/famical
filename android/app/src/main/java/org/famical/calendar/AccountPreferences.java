package org.famical.calendar;

import android.content.SharedPreferences;
import org.json.JSONObject;

final class AccountPreferences {
    static void store(SharedPreferences p,JSONObject user) {
        if(user==null||!user.has("shift_team"))return;
        String team=user.optString("shift_team","A");
        if(!team.equals("A")&&!team.equals("B")&&!team.equals("none"))team="A";
        p.edit().putString("widget_team",team).putBoolean("widget_shift",user.optInt("shift_on",1)!=0).apply();
    }
}
