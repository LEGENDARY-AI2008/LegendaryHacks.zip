package com.legendary.hacks;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.util.Random;

public class DeviceSpoofManager {

    public static boolean spoofAll(Context ctx) {
        boolean imei = spoofImei();
        boolean aid  = spoofAndroidId();
        boolean mac  = spoofMac();
        return imei || aid || mac;
    }

    // Spoof IMEI via service call (requires root)
    private static boolean spoofImei() {
        String fakeImei = generateImei();
        // Works on most MTK/Qualcomm devices with root
        String cmd = "service call iphonesubinfo 1 s16 \"" + fakeImei + "\"";
        return RootUtils.execSilent(cmd);
    }

    // Spoof Android ID in settings database
    private static boolean spoofAndroidId() {
        String fakeId = randomHex(16);
        String cmd = "settings put secure android_id " + fakeId;
        return RootUtils.execSilent(cmd);
    }

    // Spoof WiFi MAC address (requires root + net-admin)
    private static boolean spoofMac() {
        String fakeMac = generateMac();
        // Try ip command first (Android 10+)
        boolean ok = RootUtils.execSilent("ip link set wlan0 address " + fakeMac);
        if (!ok) {
            // Fallback: busybox ifconfig
            ok = RootUtils.execSilent("busybox ifconfig wlan0 hw ether " + fakeMac);
        }
        return ok;
    }

    // Generate a Luhn-valid 15-digit IMEI
    private static String generateImei() {
        Random r = new Random();
        // TAC prefix — use a Samsung prefix
        String base = "35" + String.format("%06d", r.nextInt(999999))
                            + String.format("%06d", r.nextInt(999999));
        // Compute Luhn check digit
        int sum = 0;
        for (int i = 0; i < base.length(); i++) {
            int d = Character.getNumericValue(base.charAt(i));
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        int check = (10 - (sum % 10)) % 10;
        return base + check;
    }

    private static String generateMac() {
        Random r = new Random();
        // Keep locally administered bit set, multicast bit clear
        return String.format("02:%02x:%02x:%02x:%02x:%02x",
            r.nextInt(256), r.nextInt(256),
            r.nextInt(256), r.nextInt(256), r.nextInt(256));
    }

    private static String randomHex(int len) {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        String chars = "0123456789abcdef";
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
