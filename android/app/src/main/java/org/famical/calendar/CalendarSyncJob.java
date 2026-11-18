package org.famical.calendar;

import android.app.job.JobService;
import android.app.job.JobParameters;
import android.webkit.CookieManager;

public class CalendarSyncJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        String server=getSharedPreferences("famical",0).getString("server","");
        String cookie=server.isEmpty()?null:CookieManager.getInstance().getCookie(server);
        if(cookie==null)return false;
        CalendarSync.submit(this,cookie,"",status->jobFinished(params,false));return true;
    }
    @Override public boolean onStopJob(JobParameters params) {return true;}
}
