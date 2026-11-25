package org.famical.calendar;

import android.content.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class LeaveSmsReceiver extends BroadcastReceiver {
  private static final java.util.concurrent.ExecutorService WORK =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  @Override
  public void onReceive(Context context, Intent intent) {
    if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())
        || context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
    if (parts == null || parts.length == 0) return;
    StringBuilder body = new StringBuilder();
    for (SmsMessage part : parts) {
      if (part == null
          || !LeaveSms.senderMatches(part.getOriginatingAddress())
          || part.getMessageBody() == null) return;
      body.append(part.getMessageBody());
    }
    String sender = parts[0].getOriginatingAddress(), message = body.toString();
    PendingResult pending = goAsync();
    Context app = context.getApplicationContext();
    WORK.execute(
        () -> {
          try {
            NativeStore store = NativeStore.get(app);
            if (store.view().optBoolean("smsEnabled")) {
              org.json.JSONObject event = LeaveSms.parse(sender, message);
              if (event != null && store.importLeaveSms(event)) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(store::updateWidget);
              }
            }
          } catch (Exception e) {
            // Do not log SMS bodies or applicant details.
            android.util.Log.w("CalendarSms", "휴가 문자 자동 등록 실패");
          } finally {
            if (pending != null) pending.finish();
          }
        });
  }
}
