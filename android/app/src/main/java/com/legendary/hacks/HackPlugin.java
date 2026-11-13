package com.legendary.hacks;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.JSObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

@CapacitorPlugin(name = "HackPlugin")
public class HackPlugin extends Plugin {

    private static final String FF_PKG     = "com.dts.freefireth";
    private static final String FF_PKG_MAX = "com.dts.freefiremax";

    // arm64 NOP
    private static final String NOP       = "1f2003d5";
    private static final String ZERO_F    = "00000000";

    // Signature patterns (wildcards = ??)
    // Headshot: stable bytes around the HitLocation branch in libil2cpp.so
    private static final String SIG_HEADSHOT =
        "E8031F2A" + "0801084A" + "????????" + "1F2003D5";
    private static final int HS_PATCH_OFFSET = 8;

    // Recoil: stable bytes around the recoil float initializer
    private static final String SIG_RECOIL =
        "????????" + "00008052" + "????????" + "????????";
    private static final int RC_PATCH_OFFSET = 12;

    private static final int CHUNK_SIZE = 4 * 1024 * 1024;

    private String activePid  = null;
    private long   libBase    = 0L;
    private long   libSize    = 0L;

    // ----------------------------------------------------------------
    // Called from JS: window.Capacitor.Plugins.HackPlugin.connect()
    // ----------------------------------------------------------------

    @PluginMethod
    public void connect(PluginCall call) {
        new Thread(() -> {
            JSObject result = new JSObject();

            if (!isRooted()) {
                result.put("success", false);
                result.put("log", "[✗] Root not found — root required");
                call.resolve(result);
                return;
            }

            // Spoof device IDs
            spoofDeviceIds();
            result.put("spoof", true);

            // Find FF process
            String pid = getPid(FF_PKG);
            if (pid == null) pid = getPid(FF_PKG_MAX);
            if (pid == null) {
                result.put("success", false);
                result.put("log", "[✗] Free Fire not running — open FF first");
                call.resolve(result);
                return;
            }
            activePid = pid;
            result.put("pid", pid);

            // Resolve libil2cpp
            libBase = getModuleBase(pid, "libil2cpp.so");
            if (libBase == 0L) {
                result.put("success", false);
                result.put("log", "[✗] libil2cpp.so not found in process");
                call.resolve(result);
                return;
            }
            libSize = getLibSize(pid, "libil2cpp.so");
            result.put("libBase", String.format("0x%X", libBase));

            // Patch headshot
            long hsAddr = scanPattern(pid, SIG_HEADSHOT, HS_PATCH_OFFSET);
            boolean hsOk = hsAddr != 0L && writeMem(pid, hsAddr, NOP);
            result.put("headshot", hsOk);

            // Patch recoil
            long rcAddr = scanPattern(pid, SIG_RECOIL, RC_PATCH_OFFSET);
            boolean rcOk = false;
            if (rcAddr != 0L) {
                rcOk = writeMem(pid, rcAddr, ZERO_F)
                    && writeMem(pid, rcAddr + 4, ZERO_F);
            }
            result.put("recoil", rcOk);

            result.put("success", true);
            StringBuilder log = new StringBuilder();
            log.append("[✓] Root confirmed\n");
            log.append("[✓] FF PID: ").append(pid).append("\n");
            log.append("[✓] libil2cpp base: 0x").append(Long.toHexString(libBase)).append("\n");
            log.append(hsOk  ? "[✓] Headshot patch applied\n" : "[!] Headshot scan miss — try restarting FF\n");
            log.append(rcOk  ? "[✓] No recoil applied\n"      : "[!] Recoil scan miss\n");
            log.append("[✓] Legendary Hacks is ON");
            result.put("log", log.toString());

            call.resolve(result);
        }).start();
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        activePid = null;
        libBase   = 0L;
        libSize   = 0L;
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    // ----------------------------------------------------------------
    // Root helpers
    // ----------------------------------------------------------------

    private boolean isRooted() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("id\nexit\n");
            os.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            return line != null && line.contains("uid=0");
        } catch (Exception e) { return false; }
    }

    private String exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\nexit\n");
            os.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) { return null; }
    }

    private boolean execSilent(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\nexit\n");
            os.flush();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    // ----------------------------------------------------------------
    // Device spoof
    // ----------------------------------------------------------------

    private void spoofDeviceIds() {
        String fakeId = randomHex(16);
        execSilent("settings put secure android_id " + fakeId);
        String fakeMac = String.format("02:%02x:%02x:%02x:%02x:%02x",
            (int)(Math.random()*256), (int)(Math.random()*256),
            (int)(Math.random()*256), (int)(Math.random()*256),
            (int)(Math.random()*256));
        execSilent("ip link set wlan0 address " + fakeMac);
    }

    private String randomHex(int len) {
        String chars = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++)
            sb.append(chars.charAt((int)(Math.random()*chars.length())));
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Process utils
    // ----------------------------------------------------------------

    private String getPid(String pkg) {
        String r = exec("pidof " + pkg);
        if (r != null && !r.isEmpty()) return r.trim().split("\\s+")[0];
        r = exec("ps -A | grep " + pkg);
        if (r != null && !r.isEmpty()) {
            String[] parts = r.trim().split("\\s+");
            if (parts.length >= 2) return parts[1];
        }
        return null;
    }

    private long getModuleBase(String pid, String lib) {
        String maps = exec("cat /proc/" + pid + "/maps | grep " + lib);
        if (maps == null || maps.isEmpty()) return 0L;
        try {
            return Long.parseLong(maps.split("\n")[0].split("-")[0], 16);
        } catch (Exception e) { return 0L; }
    }

    private long getLibSize(String pid, String lib) {
        String maps = exec("cat /proc/" + pid + "/maps | grep " + lib + " | head -1");
        if (maps == null) return 80 * 1024 * 1024L;
        try {
            String[] parts = maps.trim().split("-");
            long end = Long.parseLong(parts[1].split("\\s")[0], 16);
            return end - libBase;
        } catch (Exception e) { return 80 * 1024 * 1024L; }
    }

    // ----------------------------------------------------------------
    // Signature scanner
    // ----------------------------------------------------------------

    private long scanPattern(String pid, String hexPat, int patchOffset) {
        byte[]    pattern = parsePattern(hexPat);
        boolean[] wilds   = parseWilds(hexPat);
        long scanned = 0;

        while (scanned < libSize) {
            long chunkAddr = libBase + scanned;
            int  chunkLen  = (int) Math.min(CHUNK_SIZE, libSize - scanned);

            String cmd = String.format(
                "dd if=/proc/%s/mem bs=1 skip=%d count=%d 2>/dev/null | xxd -p -c 1",
                pid, chunkAddr, chunkLen
            );
            String dump = exec(cmd);
            if (dump == null || dump.isEmpty()) { scanned += chunkLen; continue; }

            String[] lines = dump.trim().split("\n");
            byte[] data = new byte[lines.length];
            for (int i = 0; i < lines.length; i++) {
                try { data[i] = (byte) Integer.parseInt(lines[i].trim(), 16); }
                catch (Exception e) { data[i] = 0; }
            }

            for (int i = 0; i <= data.length - pattern.length; i++) {
                if (matches(data, i, pattern, wilds))
                    return chunkAddr + i + patchOffset;
            }
            scanned += chunkLen - pattern.length;
        }
        return 0L;
    }

    private boolean matches(byte[] data, int off, byte[] pat, boolean[] wilds) {
        for (int i = 0; i < pat.length; i++)
            if (!wilds[i] && data[off + i] != pat[i]) return false;
        return true;
    }

    private byte[] parsePattern(String hex) {
        hex = hex.replaceAll("\\s","");
        byte[] r = new byte[hex.length()/2];
        for (int i = 0; i < r.length; i++) {
            String b = hex.substring(i*2, i*2+2);
            r[i] = b.equals("??") ? 0 : (byte)Integer.parseInt(b,16);
        }
        return r;
    }

    private boolean[] parseWilds(String hex) {
        hex = hex.replaceAll("\\s","");
        boolean[] w = new boolean[hex.length()/2];
        for (int i = 0; i < w.length; i++)
            w[i] = hex.substring(i*2, i*2+2).equals("??");
        return w;
    }

    // ----------------------------------------------------------------
    // Memory write
    // ----------------------------------------------------------------

    private boolean writeMem(String pid, long addr, String hexBytes) {
        StringBuilder printf = new StringBuilder();
        for (int i = 0; i < hexBytes.length(); i += 2)
            printf.append("\\x").append(hexBytes, i, i+2);
        String cmd = String.format(
            "printf '%s' | dd of=/proc/%s/mem bs=1 seek=%d conv=notrunc 2>/dev/null",
            printf, pid, addr
        );
        return execSilent(cmd);
    }
}
