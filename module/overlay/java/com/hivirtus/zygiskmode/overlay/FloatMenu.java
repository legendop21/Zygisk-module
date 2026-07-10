package com.hivirtus.zygiskmode.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** Compact local menu — config.json direct edit, offline */
public final class FloatMenu {

    public interface OnCloseListener { void onClose(); }

    private final Context ctx;
    private final OnCloseListener onClose;
    private WindowManager wm;
    private View panel;
    private JSONObject config;

    public FloatMenu(Context context, OnCloseListener listener) {
        this.ctx = context.getApplicationContext();
        this.onClose = listener;
    }

    public void show() {
        if (panel != null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                config = LocalConfig.load();
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

                LinearLayout box = new LinearLayout(ctx);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(14);
                box.setPadding(pad, pad, pad, pad);
                GradientDrawable card = new GradientDrawable();
                card.setCornerRadius(dp(16));
                card.setColor(0xF0121212);
                box.setBackground(card);

                TextView title = new TextView(ctx);
                title.setText("Virtus Zygisk Mode");
                title.setTextColor(Color.WHITE);
                title.setTextSize(17f);
                box.addView(title);

                TextView sub = new TextView(ctx);
                sub.setText("Local module menu — APK nahi chahiye");
                sub.setTextColor(0xFFAAAAAA);
                sub.setTextSize(11f);
                box.addView(sub);

                ScrollView scroll = new ScrollView(ctx);
                LinearLayout inner = new LinearLayout(ctx);
                inner.setOrientation(LinearLayout.VERTICAL);

                addSwitch(inner, "Hook Incoming SMS", "hook_incoming_sms", true);
                addSwitch(inner, "Hook Outgoing SMS", "hook_outgoing_sms", true);
                addSwitch(inner, "Fake Intercept Success", "intercept_fake_success", true);
                addSwitch(inner, "Auto Extract OTP", "auto_extract_otp", true);
                addSwitch(inner, "Hide Root", "hide_root", true);
                addSwitch(inner, "Phone Spoof", "enable_phone_spoof", false);

                scroll.addView(inner);
                LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                        dp(300), dp(280));
                box.addView(scroll, scrollLp);

                TextView save = new TextView(ctx);
                save.setText("SAVE (local config.json)");
                save.setTextColor(Color.WHITE);
                save.setGravity(Gravity.CENTER);
                save.setPadding(0, dp(10), 0, 0);
                save.setOnClickListener(v -> {
                    if (LocalConfig.save(config)) {
                        Toast.makeText(ctx, "Saved locally", Toast.LENGTH_SHORT).show();
                        hide();
                    } else {
                        Toast.makeText(ctx, "Save fail", Toast.LENGTH_SHORT).show();
                    }
                });
                box.addView(save);

                TextView close = new TextView(ctx);
                close.setText("CLOSE");
                close.setTextColor(0xFF00CEC9);
                close.setGravity(Gravity.CENTER);
                close.setPadding(0, dp(8), 0, 0);
                close.setOnClickListener(v -> hide());
                box.addView(close);

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.dimAmount = 0.45f;
                lp.gravity = Gravity.CENTER;

                panel = box;
                wm.addView(panel, lp);
            } catch (Exception ignored) {}
        });
    }

    public void hide() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (panel != null && wm != null) wm.removeView(panel);
            } catch (Exception ignored) {}
            panel = null;
            if (onClose != null) onClose.onClose();
        });
    }

    private void addSwitch(LinearLayout parent, String label, String key, boolean def) {
        Switch sw = new Switch(ctx);
        sw.setText(label);
        sw.setTextColor(Color.WHITE);
        sw.setChecked(LocalConfig.getBool(config, key, def));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean on) ->
                LocalConfig.putBool(config, key, on));
        parent.addView(sw);
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
