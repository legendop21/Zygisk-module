package com.hivirtus.zygiskmode.floatsvc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "VirtusFloat";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "BootReceiver — starting FloatService");
        Intent svc = new Intent(context, FloatService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
