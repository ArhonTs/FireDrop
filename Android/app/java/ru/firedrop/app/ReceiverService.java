package ru.firedrop.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/** Keeps the receiver alive with a foreground notification and a wake lock. */
public final class ReceiverService extends Service {
    private static final String TAG = "FireDrop";
    public static final String ACTION_START = "ru.firedrop.app.RECEIVE_START";
    public static final String ACTION_STOP = "ru.firedrop.app.RECEIVE_STOP";
    private static final String CHANNEL_ID = "receive";
    private static final int NOTIFICATION_ID = 41;

    public static volatile ReceiverServer server;
    public static volatile boolean active;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastNotificationUpdate;
    private int lastCount = -1;

    public static boolean isRunning() {
        return active && server != null && server.isRunning();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopReceiving();
            return START_NOT_STICKY;
        }

        if (!active) startReceiving();
        return START_NOT_STICKY;
    }

    private void startReceiving() {
        Prefs prefs = new Prefs(this);
        Store store = new Store(this);
        ReceiverServer created = new ReceiverServer(store, prefs.deviceName(),
                new ReceiverServer.Listener() {
                    @Override
                    public void onServerChanged() {
                        maybeUpdateNotification(false);
                    }

                    @Override
                    public void onFileSaved(String name, String location) {
                        maybeUpdateNotification(true);
                    }
                });

        try {
            created.start(prefs.port());
        } catch (Exception error) {
            Log.e(TAG, "cannot start receiver", error);
            // A service started with startForegroundService must enter the
            // foreground even on the failure path, otherwise Android kills the
            // process for missing the five-second deadline.
            try {
                startForegroundCompat();
            } catch (Exception ignored) {
            }
            active = false;
            stopSelf();
            return;
        }

        server = created;
        active = true;
        acquireLocks();
        try {
            startForegroundCompat();
        } catch (Exception error) {
            Log.e(TAG, "cannot enter foreground", error);
            server = null;
            active = false;
            created.stop();
            releaseLocks();
            stopSelf();
        }
    }

    private void stopReceiving() {
        ReceiverServer current = server;
        server = null;
        active = false;
        if (current != null) current.stop();
        releaseLocks();
        stopForeground(true);
        stopSelf();
    }

    private void acquireLocks() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FireDrop:receive");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception error) {
            Log.w(TAG, "wake lock failed", error);
        }

        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FireDrop:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception error) {
            Log.w(TAG, "wifi lock failed", error);
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
        wifiLock = null;
    }

    private void maybeUpdateNotification(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotificationUpdate < 700) return;
        lastNotificationUpdate = now;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.receive_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.receive_channel_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, ReceiveActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 1, open, flags);

        Intent stop = new Intent(this, ReceiverService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, flags);

        ReceiverServer current = server;
        String title;
        String text;
        if (current == null) {
            title = "FireDrop";
            text = "Приём файлов";
        } else {
            int count = current.completedCount();
            title = "Приём включён · код " + current.pin();
            if (count > 0) {
                text = "Получено файлов: " + count + " · " + Ui.formatBytes(current.completedBytes());
            } else {
                text = "Откройте FireDrop на другом устройстве";
            }
        }
        lastCount = current == null ? -1 : current.completedCount();

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                        "Остановить", stopIntent).build());

        return builder.build();
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        ReceiverServer current = server;
        server = null;
        active = false;
        if (current != null) current.stop();
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
