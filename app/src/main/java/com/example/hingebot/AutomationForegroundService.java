package com.example.hingebot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps the SmartDiscovery session alive
 * while the user freely uses their phone.
 *
 * <p>It shows a persistent notification with the current session state
 * and a tap action that opens {@link MainActivity}.
 *
 * <p>It does NOT contain any automation logic — that lives exclusively
 * in {@link HingeAutomationService} (the AccessibilityService).
 * This service only manages Android background-execution compliance.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Started by {@link MainActivity} when the user taps "Start Session".</li>
 *   <li>Stopped by {@link MainActivity} or via the notification action.</li>
 * </ol>
 */
public class AutomationForegroundService extends Service {

    public static final String CHANNEL_ID         = "smartdiscovery_channel";
    public static final String ACTION_STOP_SESSION = "com.example.hingebot.STOP_SESSION";

    private static final int NOTIFICATION_ID = 1001;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HingeAutomationService.ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                String status = intent.getStringExtra(HingeAutomationService.EXTRA_STATUS);
                int right     = intent.getIntExtra(HingeAutomationService.EXTRA_RIGHT, 0);
                int total     = intent.getIntExtra(HingeAutomationService.EXTRA_TOTAL, 0);
                updateNotification(status, right, total);
            }
        }
    };

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        IntentFilter filter = new IntentFilter(HingeAutomationService.ACTION_STATUS_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SESSION.equals(intent.getAction())) {
            HingeAutomationService svc = HingeAutomationService.getInstance();
            if (svc != null) svc.stopAutomation("User stopped via notification");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Ready", 0, 0));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SmartDiscovery Session",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows the active automation session status");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String status, int right, int total) {
        // Tap → open MainActivity
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // "Stop" action
        Intent stopIntent = new Intent(this, AutomationForegroundService.class);
        stopIntent.setAction(ACTION_STOP_SESSION);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("SmartDiscovery — Session Active")
                .setContentText(status != null ? status : "Running")
                .setSubText("→→ ← ← ←  |  Rights: " + right + "  Total: " + total)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(String status, int right, int total) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(status, right, total));
        }
    }
}
