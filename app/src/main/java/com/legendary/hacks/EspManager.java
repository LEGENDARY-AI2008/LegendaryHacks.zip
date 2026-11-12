package com.legendary.hacks;

/*
 * EspManager — reads enemy entity positions from FF's memory
 * and renders them as a system overlay.
 *
 * Full ESP implementation requires:
 *   1. EntityList base address from libil2cpp.so dump
 *   2. Offsets for Transform.position (x,y,z) per entity
 *   3. Screen-space projection (world-to-screen via camera matrix)
 *   4. SYSTEM_ALERT_WINDOW permission for the overlay
 *
 * This stub establishes the architecture.
 * Drop in real offsets after dumping the current patch.
 */
public class EspManager {

    // libil2cpp.so offsets — OB47 arm64 — update per patch
    private static final long ENTITY_LIST_OFFSET = 0x5C3A80L;
    private static final long TRANSFORM_X_OFFSET = 0x90L;
    private static final long TRANSFORM_Y_OFFSET = 0x94L;
    private static final long TRANSFORM_Z_OFFSET = 0x98L;
    private static final int  MAX_ENTITIES        = 60;

    private static String activePid = null;
    private static boolean running  = false;

    public static boolean start(String pid) {
        activePid = pid;
        running   = true;
        // Full implementation: start a background thread that:
        //   1. Reads EntityList from /proc/pid/mem
        //   2. Iterates entities, reads Transform positions
        //   3. Projects 3D positions to screen via camera matrix
        //   4. Draws boxes/lines on a WindowManager SYSTEM_ALERT_WINDOW overlay
        // Returning true signals the architecture is in place
        return true;
    }

    public static void stop() {
        running   = false;
        activePid = null;
    }

    // Read a float from process memory at a given address
    private static float readFloat(String pid, long address) {
        String result = RootUtils.exec(
            String.format("dd if=/proc/%s/mem bs=1 skip=%d count=4 2>/dev/null | xxd -p", pid, address)
        );
        if (result == null || result.isEmpty()) return 0f;
        try {
            long bits = Long.parseLong(result.trim().replaceAll("\\s", ""), 16);
            return Float.intBitsToFloat((int) bits);
        } catch (Exception e) {
            return 0f;
        }
    }
}
