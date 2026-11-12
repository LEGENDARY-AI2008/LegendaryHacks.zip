package com.legendary.hacks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class OverlayService extends Service {

    private WindowManager   windowManager;
    private View            overlayView;
    private WindowManager.LayoutParams params;

    // Touch tracking for drag
    private float initialX, initialY;
    private float initialTouchX, initialTouchY;

    private static final String CHANNEL_ID = "legendary_overlay";

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        showOverlay();
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                CHANNEL_ID,
                "Legendary Hacks Overlay",
                NotificationManager.IMPORTANCE_LOW
            );
            chan.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(chan);
        }

        Notification notif = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Legendary Hacks")
            .setContentText("Overlay active — tap to hide")
            .setSmallIcon(android.R.drawable.star_on)
            .build();

        startForeground(1, notif);
    }

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Build the overlay view programmatically -- no XML needed
        overlayView = buildOverlayView();

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 100;

        // Drag listener
        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX      = params.x;
                    initialY      = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    params.x = (int) (initialX + (event.getRawX() - initialTouchX));
                    params.y = (int) (initialY + (event.getRawY() - initialTouchY));
                    windowManager.updateViewLayout(overlayView, params);
                    return true;
            }
            return false;
        });

        windowManager.addView(overlayView, params);
    }

    /*
     * Builds the overlay widget purely in code.
     * Dark pill with gold text + pulsing green dot.
     * No XML layout dependency -- self-contained.
     */
    private View buildOverlayView() {
        // Outer container
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        container.setGravity(android.view.Gravity.CENTER_VERTICAL);
        container.setPadding(dp(10), dp(7), dp(14), dp(7));

        // Dark pill background
        android.graphics.drawable.GradientDrawable bg =
            new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE6050508);           // near-black, 90% opaque
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0xFFF5C518);  // gold border 1dp
        container.setBackground(bg);

        // Green pulse dot
        android.widget.TextView dot = new android.widget.TextView(this);
        dot.setText("●");
        dot.setTextColor(0xFF00FF88);
        dot.setTextSize(8);
        android.view.animation.AlphaAnimation pulse =
            new android.view.animation.AlphaAnimation(1f, 0.2f);
        pulse.setDuration(900);
        pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
        pulse.setRepeatCount(android.view.animation.Animation.INFINITE);
        dot.startAnimation(pulse);

        // Label
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(" LEGENDARY HACKS ");
        label.setTextColor(0xFFF5C518);
        label.setTextSize(10);
        label.setTypeface(android.graphics.Typeface.MONOSPACE,
                          android.graphics.Typeface.BOLD);
        label.setLetterSpacing(0.08f);

        // ON badge
        android.widget.TextView badge = new android.widget.TextView(this);
        badge.setText("ON");
        badge.setTextColor(0xFF000000);
        badge.setTextSize(8);
        badge.setTypeface(android.graphics.Typeface.MONOSPACE,
                          android.graphics.Typeface.BOLD);
        badge.setPadding(dp(4), dp(1), dp(4), dp(1));
        android.graphics.drawable.GradientDrawable badgeBg =
            new android.graphics.drawable.GradientDrawable();
        badgeBg.setColor(0xFF00FF88);
        badgeBg.setCornerRadius(dp(3));
        badge.setBackground(badgeBg);

        container.addView(dot);
        container.addView(label);
        container.addView(badge);

        return container;
    }

    // dp to px helper
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // restart if killed
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
