package com.hivirtus.zygiskmode.floatsvc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.hivirtus.zygiskmode.overlay.OverlayBootstrap;

/** Foreground service — APatch/Android 14 par reliable floating bubble */
public final class FloatService extends Service {

    private static final String TAG = "VirtusFloat";
    private static final String CH = "virtus_overlay";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "FloatService onCreate");
        startAsForeground();
        OverlayBootstrap.start(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        OverlayBootstrap.ensureVisible();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        OverlayBootstrap.stop(getApplicationContext());
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH, "Virtus", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle("Virtus")
                .setContentText("Floating bubble active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details);
        startForeground(77001, b.build());
    }
}
