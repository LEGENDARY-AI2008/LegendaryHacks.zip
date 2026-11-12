package com.legendary.hacks;

public class ProcessUtils {

    // Returns PID string of a running package, null if not found
    public static String getPid(String packageName) {
        String result = RootUtils.exec("pidof " + packageName);
        if (result != null && !result.isEmpty()) {
            return result.trim().split("\\s+")[0];
        }
        // Fallback: grep ps output
        result = RootUtils.exec("ps -A | grep " + packageName);
        if (result != null && !result.isEmpty()) {
            String[] parts = result.trim().split("\\s+");
            if (parts.length >= 2) {
                return parts[1]; // PID is second column in ps output
            }
        }
        return null;
    }

    // Get base address of a loaded library in a process
    public static long getModuleBase(String pid, String moduleName) {
        String maps = RootUtils.exec("cat /proc/" + pid + "/maps | grep " + moduleName);
        if (maps == null || maps.isEmpty()) return 0L;
        try {
            String firstLine = maps.split("\n")[0];
            String addrRange = firstLine.split("-")[0];
            return Long.parseLong(addrRange, 16);
        } catch (Exception e) {
            return 0L;
        }
    }
}
