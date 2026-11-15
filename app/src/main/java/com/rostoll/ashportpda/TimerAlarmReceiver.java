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
    public static final String ACTION_TIMER_ALARM = "com.rostoll.ashportpda.ACTION_TIMER_ALARM";
    public static final String ACTION_BACKGROUND_HEARTBEAT = "com.rostoll.ashportpda.ACTION_BACKGROUND_HEARTBEAT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        // 1. Reboot, App Update, or 15-Minute Background Heartbeat -> Silently poll API & schedule alarms
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

        // 2. Standard Timer Expiration -> Post High-Priority Status Bar Notification
        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        int notifId = intent.getIntExtra("notif_id", 1001);

        if (title == null) title = "Ashport Pass Timer";
        if (message == null) message = "An Ashport timer is ready!";

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Ashport Game Timers",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Exact alerts for bars, cooldowns, travel, and hospital/jail release");
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

        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
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
