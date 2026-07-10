package com.hivirtus.zygiskmode.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Reference-style floating bubble — left side, dark blue, shield icon */
public final class FloatBubble {

    public interface OnTapListener { void onTap(); }

    private final Context ctx;
    private final OnTapListener onTap;
    private WindowManager wm;
    private LinearLayout root;
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
                float density = ctx.getResources().getDisplayMetrics().density;
                int bubble = (int) (52 * density);
                int pad = (int) (6 * density);

                root = new LinearLayout(ctx);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setGravity(Gravity.CENTER_HORIZONTAL);
                root.setPadding(pad, pad, pad, pad);

                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(0xFF1A3A6B);
                bg.setStroke((int) (2 * density), 0xFF4A7FD4);
                root.setBackground(bg);

                TextView icon = new TextView(ctx);
                icon.setText("\u26E8");
                icon.setTextColor(Color.WHITE);
                icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
                icon.setGravity(Gravity.CENTER);
                root.addView(icon, new LinearLayout.LayoutParams(bubble - pad * 2, bubble - pad * 2));

                TextView label = new TextView(ctx);
                label.setText("OTP");
                label.setTextColor(0xFFBBD4FF);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
                label.setTypeface(Typeface.DEFAULT_BOLD);
                label.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lpLabel.topMargin = (int) (-4 * density);
                root.addView(label, lpLabel);

                int totalH = bubble + (int) (12 * density);
                lp = new WindowManager.LayoutParams(
                        bubble, totalH,
                        OverlayUtil.primaryOverlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = (int) (8 * density);
                lp.y = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.38f);

                root.setOnTouchListener(this::onTouch);
                OverlayUtil.addView(wm, root, lp);
                Log.i("VirtusOverlay", "Bubble shown left-side");
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

    public boolean isShowing() {
        return root != null;
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
        float density = ctx.getResources().getDisplayMetrics().density;
        int bubble = (int) (52 * density);
        lp.x = (lp.x + bubble / 2 < half) ? (int) (8 * density) : w - bubble - (int) (8 * density);
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
    }
}
