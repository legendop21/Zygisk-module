package com.hivirtus.zygiskmode.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.util.Log;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Lightweight draggable bubble — smooth, chhota, APK nahi */
public final class FloatBubble {

    public interface OnTapListener { void onTap(); }

    private final Context ctx;
    private final OnTapListener onTap;
    private WindowManager wm;
    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private float downX, downY;
    private int startX, startY;
    private boolean dragging;

    public FloatBubble(Context context, OnTapListener listener) {
        this.ctx = context.getApplicationContext();
        this.onTap = listener;
    }

    public void show() {
        if (root != null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                root = new FrameLayout(ctx);
                TextView logo = new TextView(ctx);
                logo.setText("H");
                logo.setTextColor(Color.WHITE);
                logo.setTextSize(16f);
                logo.setGravity(Gravity.CENTER);
                int dp = (int) (ctx.getResources().getDisplayMetrics().density * 48);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColors(new int[]{0xFF6C5CE7, 0xFF00CEC9});
                logo.setBackground(bg);
                FrameLayout.LayoutParams inner = new FrameLayout.LayoutParams(dp, dp);
                root.addView(logo, inner);

                lp = new WindowManager.LayoutParams(
                        dp, dp, OverlayUtil.primaryOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = ctx.getResources().getDisplayMetrics().widthPixels - dp - 24;
                lp.y = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.32f);

                root.setOnTouchListener(this::onTouch);
                OverlayUtil.addView(wm, root, lp);
            } catch (Throwable t) {
                Log.e("VirtusOverlay", "FloatBubble show failed: " + t.getMessage(), t);
            }
        });
    }

    public void hide() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (root != null && wm != null) wm.removeView(root);
            } catch (Exception ignored) {}
            root = null;
            lp = null;
        });
    }

    private boolean onTouch(View v, MotionEvent e) {
        if (lp == null) return false;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = (int) (e.getRawX() - downX);
                int dy = (int) (e.getRawY() - downY);
                if (Math.abs(dx) > 6 || Math.abs(dy) > 6) dragging = true;
                lp.x = startX + dx;
                lp.y = startY + dy;
                wm.updateViewLayout(root, lp);
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging && onTap != null) onTap.onTap();
                else snapEdge();
                return true;
            default:
                return false;
        }
    }

    private void snapEdge() {
        int w = ctx.getResources().getDisplayMetrics().widthPixels;
        int half = w / 2;
        int dp = (int) (ctx.getResources().getDisplayMetrics().density * 48);
        lp.x = (lp.x + dp / 2 < half) ? 12 : w - dp - 12;
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
    }
}
