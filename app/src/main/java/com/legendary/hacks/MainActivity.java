package com.legendary.hacks;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private Button   btnConnect;
    private TextView tvStatus;
    private TextView tvLog;
    private boolean  isConnected = false;

    private final ExecutorService executor   = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private static final String FF_PACKAGE     = "com.dts.freefireth";
    private static final String FF_PACKAGE_MAX = "com.dts.freefiremax";
    private static final int    OVERLAY_REQ    = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnConnect = findViewById(R.id.btnConnect);
        tvStatus   = findViewById(R.id.tvStatus);
        tvLog      = findViewById(R.id.tvLog);

        checkRootStatus();

        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                // Request overlay permission first if not granted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !Settings.canDrawOverlays(this)) {
                    requestOverlayPermission();
                } else {
                    startInjection();
                }
            } else {
                stopInjection();
            }
        });
    }

    // ----------------------------------------------------------------
    // Overlay permission
    // ----------------------------------------------------------------

    private void requestOverlayPermission() {
        appendLog("[!] Need overlay permission — opening settings...");
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())
        );
        startActivityForResult(intent, OVERLAY_REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_REQ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)) {
                appendLog("[✓] Overlay permission granted");
                startInjection();
            } else {
                appendLog("[✗] Overlay permission denied — overlay won't show");
                startInjection(); // still inject, just no overlay
            }
        }
    }

    // ----------------------------------------------------------------
    // Root check
    // ----------------------------------------------------------------

    private void checkRootStatus() {
        executor.execute(() -> {
            boolean rooted = RootUtils.isRooted();
            mainHandler.post(() -> {
                if (rooted) {
                    appendLog("[✓] Root access detected");
                    appendLog("[✓] Ready to inject");
                    btnConnect.setEnabled(true);
                } else {
                    appendLog("[✗] Root not found");
                    appendLog("[!] Root required for full functionality");
                    tvStatus.setText("NO ROOT");
                    tvStatus.setTextColor(0xFFFF3C3C);
                }
            });
        });
    }

    // ----------------------------------------------------------------
    // Injection
    // ----------------------------------------------------------------

    private void startInjection() {
        btnConnect.setEnabled(false);
        tvStatus.setText("CONNECTING...");
        tvStatus.setTextColor(0xFFF5C518);
        appendLog("[*] Starting injection sequence...");

        executor.execute(() -> {
            appendLogOnMain("[*] Spoofing device identifiers...");
            boolean spoofed = DeviceSpoofManager.spoofAll(getApplicationContext());
            appendLogOnMain(spoofed ? "[✓] Device IDs spoofed" : "[!] Spoof partial — continuing");

            appendLogOnMain("[*] Locating Free Fire process...");
            String ffPid = ProcessUtils.getPid(FF_PACKAGE);
            if (ffPid == null) ffPid = ProcessUtils.getPid(FF_PACKAGE_MAX);

            if (ffPid == null) {
                appendLogOnMain("[!] Free Fire not running — launch the game first");
                mainHandler.post(() -> {
                    tvStatus.setText("FF NOT RUNNING");
                    tvStatus.setTextColor(0xFFFF3C3C);
                    btnConnect.setEnabled(true);
                });
                return;
            }

            appendLogOnMain("[✓] FF PID: " + ffPid);

            appendLogOnMain("[*] Scanning for headshot patch...");
            boolean patched = MemoryPatcher.patchHeadshot(ffPid);
            appendLogOnMain(patched
                ? "[✓] Headshot modifier active"
                : "[✗] Headshot scan failed — OB version may need new patterns");

            appendLogOnMain("[*] Scanning for recoil patch...");
            boolean noRecoil = MemoryPatcher.patchNoRecoil(ffPid);
            appendLogOnMain(noRecoil ? "[✓] No recoil active" : "[!] Recoil scan partial");

            appendLogOnMain("[*] Starting ESP...");
            boolean esp = EspManager.start(ffPid);
            appendLogOnMain(esp ? "[✓] ESP active" : "[!] ESP requires overlay permission");

            final String pid = ffPid;
            mainHandler.post(() -> {
                isConnected = true;
                btnConnect.setText("DISCONNECT");
                btnConnect.setBackgroundColor(0xFFFF3C3C);
                tvStatus.setText("CONNECTED");
                tvStatus.setTextColor(0xFF00FF88);
                btnConnect.setEnabled(true);
                appendLog("[✓] Legendary Hacks active");

                // Start overlay service
                Intent overlayIntent = new Intent(this, OverlayService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(overlayIntent);
                } else {
                    startService(overlayIntent);
                }
                appendLog("[✓] Overlay started — drag it anywhere");
            });
        });
    }

    private void stopInjection() {
        executor.execute(() -> {
            MemoryPatcher.unpatch();
            EspManager.stop();
            appendLogOnMain("[*] Patches removed");
            mainHandler.post(() -> {
                isConnected = false;
                btnConnect.setText("CONNECT");
                btnConnect.setBackgroundColor(0xFFF5C518);
                tvStatus.setText("DISCONNECTED");
                tvStatus.setTextColor(0xFF6B6B80);

                // Stop overlay service
                stopService(new Intent(this, OverlayService.class));
                appendLog("[*] Overlay removed");
            });
        });
    }

    // ----------------------------------------------------------------
    // Log helpers
    // ----------------------------------------------------------------

    private void appendLog(String msg) {
        tvLog.append(msg + "\n");
    }

    private void appendLogOnMain(String msg) {
        mainHandler.post(() -> appendLog(msg));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        EspManager.stop();
    }
}
