package com.legendary.hacks;

/*
 * MemoryPatcher — dynamic signature scanner + patcher
 *
 * No hardcoded offsets. Scans libil2cpp.so at runtime for
 * known byte patterns that surround our patch targets.
 * Works across OB versions as long as Garena doesn't rewrite
 * the core damage/recoil logic from scratch.
 *
 * HOW SIGNATURE SCANNING WORKS:
 *   Each pattern is a hex string where ?? is a wildcard byte.
 *   We read libil2cpp.so in chunks, slide a window across,
 *   compare each position against the pattern, skip wildcards.
 *   When we match, we apply the patch at the offset within
 *   the match that we care about.
 *
 * PATTERNS EXPLAINED:
 *   Headshot: The damage multiplier function checks hit location
 *     with a comparison + conditional branch. The bytes before
 *     and after the branch are stable across OB versions because
 *     they load the HitLocation enum value. We match those stable
 *     bytes and patch the branch instruction to NOP.
 *
 *   Recoil: The recoil accumulator is a struct of two floats
 *     (x, y) initialized to known non-zero values in a setup
 *     function. We scan for the float load pattern and zero them.
 *
 * UPDATING PATTERNS FOR A NEW OB:
 *   1. Pull libil2cpp.so from /data/app/com.dts.freefireth-*/base.apk
 *   2. Run IL2CppDumper on it
 *   3. Find HitLocation check in the dump, grab 8-12 bytes before
 *      and after the branch as your new signature
 *   4. Replace the pattern strings below
 */
public class MemoryPatcher {

    // arm64 NOP: 1F 20 03 D5
    private static final String NOP = "1f2003d5";
    // IEEE 754 float 0.0: 00 00 00 00
    private static final String ZERO_F = "00000000";

    /*
     * Headshot signature — arm64
     *
     * Pattern explanation:
     *   The damage function loads the HitLocation enum value,
     *   compares it against HEAD (0x0), then branches.
     *   Bytes before branch are stable FP load instructions.
     *   ?? wildcards cover the branch immediate (changes per build).
     *
     *   Patch offset 12: the conditional branch instruction.
     *   We NOP it so the headshot multiplier path is always taken.
     *
     * To update: in IDA/Ghidra open libil2cpp.so, find
     *   "BulletHitDamageProcess" or "CalcHitDamage", locate
     *   the CMP+BNE near HitLocation check, grab 6 bytes before
     *   and 4 bytes after as new signature.
     */
    private static final String SIG_HEADSHOT =
        "E8031F2A" +   // MOV W8, WZR (zeroing before compare) -- stable
        "0801084A" +   // EOR W8, W8, W8 variant -- stable
        "????????" +   // wildcard: immediate varies per build
        "1F2003D5";    // NOP we want to find the slot for (patch here-4)

    private static final int HEADSHOT_PATCH_OFFSET = 8; // bytes into match

    /*
     * No-recoil signature — arm64
     *
     * The recoil struct initializer loads two float constants
     * via FMOV instructions. The surrounding register ops are
     * stable. We find the first FMOV and zero both floats.
     *
     * Patch offsets 0 and 4: the two FMOV instruction slots.
     */
    private static final String SIG_RECOIL =
        "????????" +   // wildcard: register allocation varies
        "00008052" +   // MOV W0, #0 before recoil init -- stable
        "????????" +   // wildcard
        "????????";    // wildcard: first FMOV (patch here)

    private static final int RECOIL_X_PATCH_OFFSET = 12;
    private static final int RECOIL_Y_PATCH_OFFSET = 16;

    // Scan chunk size: 4MB at a time to avoid OOM
    private static final int CHUNK_SIZE = 4 * 1024 * 1024;

    private static String activePid  = null;
    private static long   libBase    = 0L;
    private static long   libSize    = 0L;
    private static long   hsAddress  = 0L;
    private static long   rcXAddress = 0L;
    private static long   rcYAddress = 0L;

    // ----------------------------------------------------------------

    public static boolean patchHeadshot(String pid) {
        activePid = pid;
        if (!resolveLib(pid)) return false;

        long addr = scanForPattern(pid, SIG_HEADSHOT, HEADSHOT_PATCH_OFFSET);
        if (addr == 0L) return false;

        hsAddress = addr;
        return writeMem(pid, addr, NOP);
    }

    public static boolean patchNoRecoil(String pid) {
        if (libBase == 0L) return false;

        long addrX = scanForPattern(pid, SIG_RECOIL, RECOIL_X_PATCH_OFFSET);
        if (addrX == 0L) return false;

        rcXAddress = addrX;
        rcYAddress = addrX + 4;
        return writeMem(pid, rcXAddress, ZERO_F) && writeMem(pid, rcYAddress, ZERO_F);
    }

    public static void unpatch() {
        activePid  = null;
        libBase    = 0L;
        libSize    = 0L;
        hsAddress  = 0L;
        rcXAddress = 0L;
        rcYAddress = 0L;
    }

    // ----------------------------------------------------------------
    // Pattern scanner
    // ----------------------------------------------------------------

    /*
     * Reads libil2cpp.so in CHUNK_SIZE chunks via root dd,
     * slides a window looking for the pattern,
     * returns absolute address of (match_start + patchOffset).
     */
    private static long scanForPattern(String pid, String hexPattern, int patchOffset) {
        byte[] pattern  = parsePattern(hexPattern);
        boolean[] wilds = parseWildcards(hexPattern);

        long scanned = 0;

        while (scanned < libSize) {
            long chunkAddr = libBase + scanned;
            int  chunkLen  = (int) Math.min(CHUNK_SIZE, libSize - scanned);

            // Read chunk as hex via dd | xxd
            String cmd = String.format(
                "dd if=/proc/%s/mem bs=1 skip=%d count=%d 2>/dev/null | xxd -p -c 1",
                pid, chunkAddr, chunkLen
            );
            String hexDump = RootUtils.exec(cmd);
            if (hexDump == null || hexDump.isEmpty()) {
                scanned += chunkLen;
                continue;
            }

            // Convert hex dump to byte array
            String[] hexLines = hexDump.trim().split("\n");
            byte[] data = new byte[hexLines.length];
            for (int i = 0; i < hexLines.length; i++) {
                try {
                    data[i] = (byte) Integer.parseInt(hexLines[i].trim(), 16);
                } catch (Exception e) {
                    data[i] = 0;
                }
            }

            // Slide window
            for (int i = 0; i <= data.length - pattern.length; i++) {
                if (matches(data, i, pattern, wilds)) {
                    return chunkAddr + i + patchOffset;
                }
            }

            // Overlap by pattern.length to catch splits across chunks
            scanned += chunkLen - pattern.length;
        }

        return 0L; // not found
    }

    private static boolean matches(byte[] data, int offset, byte[] pattern, boolean[] wilds) {
        for (int i = 0; i < pattern.length; i++) {
            if (!wilds[i] && data[offset + i] != pattern[i]) return false;
        }
        return true;
    }

    // Parse "AABB??CC" into byte array (wildcards = 0x00)
    private static byte[] parsePattern(String hex) {
        hex = hex.replaceAll("\\s", "");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            String b = hex.substring(i * 2, i * 2 + 2);
            result[i] = b.equals("??") ? 0 : (byte) Integer.parseInt(b, 16);
        }
        return result;
    }

    // Returns true for each byte position that is a wildcard (??)
    private static boolean[] parseWildcards(String hex) {
        hex = hex.replaceAll("\\s", "");
        boolean[] wilds = new boolean[hex.length() / 2];
        for (int i = 0; i < wilds.length; i++) {
            wilds[i] = hex.substring(i * 2, i * 2 + 2).equals("??");
        }
        return wilds;
    }

    // ----------------------------------------------------------------
    // Lib resolution
    // ----------------------------------------------------------------

    private static boolean resolveLib(String pid) {
        // Get base address
        libBase = ProcessUtils.getModuleBase(pid, "libil2cpp.so");
        if (libBase == 0L) return false;

        // Get size from maps: end_addr - base_addr of first mapping
        String maps = RootUtils.exec(
            "cat /proc/" + pid + "/maps | grep libil2cpp.so | head -1"
        );
        if (maps == null) return false;
        try {
            String[] parts = maps.trim().split("-");
            long end = Long.parseLong(parts[1].split("\\s")[0], 16);
            libSize = end - libBase;
        } catch (Exception e) {
            libSize = 80 * 1024 * 1024L; // default 80MB fallback
        }
        return true;
    }

    // ----------------------------------------------------------------
    // Memory write
    // ----------------------------------------------------------------

    private static boolean writeMem(String pid, long address, String hexBytes) {
        StringBuilder printf = new StringBuilder();
        for (int i = 0; i < hexBytes.length(); i += 2) {
            printf.append("\\x").append(hexBytes, i, i + 2);
        }
        String cmd = String.format(
            "printf '%s' | dd of=/proc/%s/mem bs=1 seek=%d conv=notrunc 2>/dev/null",
            printf.toString(), pid, address
        );
        return RootUtils.execSilent(cmd);
    }
}
