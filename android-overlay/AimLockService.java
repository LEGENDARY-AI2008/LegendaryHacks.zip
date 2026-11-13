package com.legendary.hacks;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AimLockService extends AccessibilityService {

    // FF enemy skin tone range (HSV-based, covers most skin tones in FF)
    // These are the pixel color ranges we scan for enemy bodies
    private static final int SKIN_R_MIN = 160, SKIN_R_MAX = 220;
    private static final int SKIN_G_MIN = 100, SKIN_G_MAX = 170;
    private static final int SKIN_B_MIN = 70,  SKIN_B_MAX = 130;

    // Red enemy outline (when enemy is spotted/tagged)
    private static final int RED_R_MIN = 180, RED_R_MAX = 255;
    private static final int RED_G_MIN = 0,   RED_G_MAX = 60;
    private static final int RED_B_MIN = 0,   RED_B_MAX = 60;

    // How far above center of detected cluster to aim (head offset)
    // Head is roughly 15% of body height above center mass
    private static final float HEAD_OFFSET_RATIO = 0.35f;

    // Scan every N ms
    private static final int SCAN_INTERVAL_MS = 50; // 20fps scan

    // FOV -- only look within this radius from screen center (pixels)
    private static final int FOV_RADIUS = 300;

    private Handler       handler;
    private Runnable      scanLoop;
    private boolean       running   = false;
    private int           scrWidth  = 0;
    private int           scrHeight = 0;

    // Shared with plugin
    public static AimLockService instance = null;
    public static boolean        enabled  = false;

    @Override
    public void onServiceConnected() {
        instance = this;
        handler  = new Handler(Looper.getMainLooper());

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE))
            .getDefaultDisplay().getRealMetrics(dm);
        scrWidth  = dm.widthPixels;
        scrHeight = dm.heightPixels;

        startScanLoop();
    }

    public void startScanLoop() {
        running  = true;
        scanLoop = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                if (enabled) {
                    doAimLock();
                }
                handler.postDelayed(this, SCAN_INTERVAL_MS);
            }
        };
        handler.post(scanLoop);
    }

    public void stopScanLoop() {
        running = false;
        if (handler != null && scanLoop != null) {
            handler.removeCallbacks(scanLoop);
        }
    }

    /*
     * Core aimlock:
     * 1. Take screenshot via AccessibilityService.takeScreenshot (Android 11+)
     *    or via /proc/fb0 on older devices
     * 2. Scan pixels in FOV circle for enemy color signatures
     * 3. Cluster hits → find nearest enemy center
     * 4. Calculate head position (above center mass)
     * 5. Inject swipe gesture to move crosshair to head
     */
    private void doAimLock() {
        // takeScreenshot is available on Android 11+ (API 30+)
        // For older: use the root-free pixel sampling via View tree
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            takeScreenshot(Display.DEFAULT_DISPLAY,
                getApplicationContext().getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        Bitmap bmp = Bitmap.wrapHardwareBuffer(
                            screenshot.getHardwareBuffer(),
                            screenshot.getColorSpace()
                        ).copy(Bitmap.Config.ARGB_8888, false);
                        processFrame(bmp);
                        bmp.recycle();
                    }
                    @Override
                    public void onFailure(int errorCode) {}
                }
            );
        }
    }

    private void processFrame(Bitmap bmp) {
        int cx = scrWidth  / 2;
        int cy = scrHeight / 2;

        List<Point> hits = new ArrayList<>();

        // Scan FOV circle -- step by 4px for performance
        int step = 4;
        int fov2  = FOV_RADIUS * FOV_RADIUS;

        for (int y = cy - FOV_RADIUS; y < cy + FOV_RADIUS; y += step) {
            if (y < 0 || y >= bmp.getHeight()) continue;
            for (int x = cx - FOV_RADIUS; x < cx + FOV_RADIUS; x += step) {
                if (x < 0 || x >= bmp.getWidth()) continue;
                int dx = x - cx, dy = y - cy;
                if (dx*dx + dy*dy > fov2) continue;

                int pixel = bmp.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8)  & 0xFF;
                int b =  pixel        & 0xFF;

                // Check enemy red outline first (most reliable)
                boolean isRedOutline = r >= RED_R_MIN && r <= RED_R_MAX
                    && g >= RED_G_MIN && g <= RED_G_MAX
                    && b >= RED_B_MIN && b <= RED_B_MAX;

                // Check skin tone fallback
                boolean isSkin = r >= SKIN_R_MIN && r <= SKIN_R_MAX
                    && g >= SKIN_G_MIN && g <= SKIN_G_MAX
                    && b >= SKIN_B_MIN && b <= SKIN_B_MAX;

                if (isRedOutline || isSkin) {
                    hits.add(new Point(x, y));
                }
            }
        }

        if (hits.size() < 8) return; // not enough pixels -- no enemy

        // Find nearest cluster to crosshair center
        Point target = findNearestCluster(hits, cx, cy);
        if (target == null) return;

        // Head is above the center mass -- offset upward
        // The larger the cluster, the taller the enemy -- estimate head
        int headY = target.y - (int)(FOV_RADIUS * HEAD_OFFSET_RATIO);
        headY = Math.max(0, headY);

        // Inject aim swipe from current center to head position
        snapAim(cx, cy, target.x, headY);
    }

    private Point findNearestCluster(List<Point> hits, int cx, int cy) {
        // Simple centroid of all hits -- good enough for single enemy
        // For multi-enemy: find the cluster whose centroid is closest to cx,cy
        long sumX = 0, sumY = 0;
        for (Point p : hits) { sumX += p.x; sumY += p.y; }
        return new Point((int)(sumX / hits.size()), (int)(sumY / hits.size()));
    }

    /*
     * Inject a swipe gesture from (fromX,fromY) to (toX,toY)
     * Duration 1ms = instant snap, feels like aimlock
     * AccessibilityService.dispatchGesture handles this without root
     */
    private void snapAim(int fromX, int fromY, int toX, int toY) {
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);

        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 1); // 1ms = instant

        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke)
            .build();

        dispatchGesture(gesture, null, null);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Check if FF is in foreground
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null) {
                boolean ffForeground = pkg.toString().contains("freefireth")
                    || pkg.toString().contains("freefiremax");
                // Auto enable when FF is open
                if (ffForeground && !enabled) enabled = true;
                if (!ffForeground) enabled = false;
            }
        }
    }

    @Override
    public void onInterrupt() {
        stopScanLoop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        enabled  = false;
        stopScanLoop();
    }
}
