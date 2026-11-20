package org.famical.calendar;

import android.app.job.*;
import android.content.*;
import org.json.*;

/** Network-constrained work keeps offline writes queued across process/device restarts. */
public final class NativeSyncJob extends JobService {
  private static final int PERIODIC = 3701, OUTBOX = 3702;
  private final java.util.Set<JobParameters> active =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

  static void schedule(Context context, boolean pending) {
    JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    ComponentName service = new ComponentName(context, NativeSyncJob.class);
    if (scheduler.getPendingJob(PERIODIC) == null) {
      scheduler.schedule(
          new JobInfo.Builder(PERIODIC, service)
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setPeriodic(60 * 60 * 1000)
              .setPersisted(true)
              .build());
    }
    if (pending)
      scheduler.schedule(
          new JobInfo.Builder(OUTBOX, service)
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setMinimumLatency(1000)
              .setBackoffCriteria(60000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
              .setPersisted(true)
              .build());
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    active.add(params);
    NativeStore store = NativeStore.get(this);
    store.sync(
        () -> {
          if (!active.remove(params)) return;
          JSONArray pending = NativeStore.array(store.view(), "pending");
          boolean retry = store.view().optBoolean("preferencesPending");
          for (int i = 0; i < pending.length(); i++)
            if (!pending.optJSONObject(i).optBoolean("conflict")) retry = true;
          jobFinished(params, params.getJobId() == OUTBOX && retry);
        });
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    active.remove(params);
    return true;
  }
}
