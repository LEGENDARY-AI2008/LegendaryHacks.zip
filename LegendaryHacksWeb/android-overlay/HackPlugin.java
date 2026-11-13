package com.legendary.hacks;

import android.content.Intent;
import android.provider.Settings;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.JSObject;

@CapacitorPlugin(name = "HackPlugin")
public class HackPlugin extends Plugin {

    @PluginMethod
    public void connect(PluginCall call) {
        JSObject result = new JSObject();

        // Check accessibility permission
        if (!isAccessibilityEnabled()) {
            result.put("success", false);
            result.put("needsAccessibility", true);
            result.put("log", "[!] Enable Legendary Hacks in Accessibility Settings first");
            call.resolve(result);
            return;
        }

        // Enable aimlock
        AimLockService.enabled = true;

        result.put("success", true);
        result.put("log",
            "[✓] Accessibility service active\n" +
            "[✓] Aimlock enabled\n" +
            "[✓] Opens Free Fire — aimlock fires automatically\n" +
            "[✓] Legendary Hacks is ON"
        );
        call.resolve(result);
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        AimLockService.enabled = false;
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    @PluginMethod
    public void openAccessibilitySettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve(new JSObject());
    }

    private boolean isAccessibilityEnabled() {
        if (AimLockService.instance != null) return true;
        try {
            int enabled = Settings.Secure.getInt(
                getContext().getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED
            );
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(
                getContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            return services != null && services.contains("com.legendary.hacks");
        } catch (Exception e) {
            return false;
        }
    }
}
