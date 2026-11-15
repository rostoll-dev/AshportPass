package com.rostoll.ashportpda;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.concurrent.Executors;

public class TimerAlarmReceiver extends BroadcastReceiver {
    public static final String CHANNEL_ID = "ashport_timers_channel";
    public static final String LIVE_CHANNEL_ID = "live_updates_travel";
    public static final String ACTION_TIMER_ALARM = "com.rostoll.ashportpda.ACTION_TIMER_ALARM";
    public static final String ACTION_BACKGROUND_HEARTBEAT = "com.rostoll.ashportpda.ACTION_BACKGROUND_HEARTBEAT";
    public static final String ACTION_LIVE_PROGRESS_TICK = "com.rostoll.ashportpda.ACTION_LIVE_PROGRESS_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        if (ACTION_LIVE_PROGRESS_TICK.equals(action)) {
            ApiTimerManager.refreshCachedLiveNotifications(context.getApplicationContext());
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || ACTION_BACKGROUND_HEARTBEAT.equals(action)) {
            final PendingResult pendingResult = goAsync();
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    ApiTimerManager.performHeadlessSync(context.getApplicationContext());
                } finally {
                    pendingResult.finish();
                }
            });
            return;
        }

        int notifId = intent.getIntExtra("notif_id", 108);
        boolean fireCompletionAlert = intent.getBooleanExtra("alert_enabled", true);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        nm.cancel(notifId + 1000);

        if (!fireCompletionAlert) return;

        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        if (title == null) title = "Ashport Pass Timer";
        if (message == null) message = "An Ashport timer is ready!";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Ashport Completion Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Vibrating alerts when bars, cooldowns, or travel finish");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(channel);
        }

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                notifId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(ApiTimerManager.getIconResForTimer(notifId))
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_ALARM)
                   .setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        nm.notify(notifId, builder.build());
    }
}
