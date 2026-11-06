package com.winlator.cmod.session;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * YurNLator base, part 16: fixes a game needs in its own files, applied before launch.
 *
 * The Last of Us Part I: the game reserves 512 GiB of address space at start
 * ({@code movabs rdx, 0x8000000000}). Android kernels give a process 39-bit (512 GiB) user
 * space, so the reservation fails and the game quits at start. GameHub's "tloul" component
 * patches that size to 192 GiB ({@code 0x3000000000}) in tlou-i.exe; this does the same, with
 * a backup next to the exe (tlou-i.exe.YurNLator.bak).
 */
public final class GameFixes {
    private GameFixes() {}

    private static final long TLOU_SIZE = 0x39b7288L;          // tlou-i.exe the GameHub patch knows
    private static final long TLOU_OFFSET = 0x3b3170L;
    private static final byte[] TLOU_ORIG  = {(byte) 0xBA, 0, 0, 0, 0, (byte) 0x80, 0, 0, 0, 0x48};
    private static final byte[] TLOU_FIXED = {(byte) 0xBA, 0, 0, 0, 0, 0x30, 0, 0, 0, 0x48};

    /** Applies what the game in [shortcut] needs; [log] gets one line per fix. */
    public static void apply(android.content.Context context, Container container, Shortcut shortcut, Consumer<String> log) {
        if (container == null || shortcut == null || shortcut.path == null) return;
        File exe = unixPath(container, shortcut.path);
        if (exe == null) return;
        File dir = exe.getParentFile();
        if (dir == null) return;
        String name = exe.getName().toLowerCase(Locale.ROOT);
        boolean part2 = name.startsWith("tlou-ii") || (name.equals("launcher.exe") && new File(dir, "tlou-ii.exe").isFile());
        if (part2) {
            for (String n : new String[]{"tlou-ii.exe", "tlou-ii-l.exe"}) {
                File f = new File(dir, n);
                if (f.isFile()) tlou2AddressSpace(f, log);
            }
            // As GameHub's tloull1: the game's own Agility SDK D3D12 goes aside, VKD3D's is used.
            for (String n : new String[]{"d3d12.dll", "d3d12core.dll"}) {
                File f = new File(dir, n);
                if (f.isFile()) {
                    File to = new File(dir, n + ".disabled_by_tlou2_patch");
                    say(log, "TLOU2: " + n + (to.exists() || !f.renameTo(to) ? " couldn't be moved aside" : " moved aside (VKD3D's is used)"));
                }
            }
            int bits = userAddressBits();
            say(log, "device: " + (bits > 0 ? bits + "-bit" : "unknown") + " user address space");
            // The Part I hook on a 39-bit address space: its generic part (high reservations,
            // 40-bit address requirements) applies here too; its Part I trap checks the code first.
            if (new File(dir, "tlou-ii.exe").isFile())
                tlouHook(context, container, shortcut.getExtra("envVars", ""), dir, log, !(bits == 0 || bits <= 39));
        } else if (name.startsWith("tlou-i") || name.equals("launcher.exe")) {
            for (String n : new String[]{"tlou-i.exe", "tlou-i-l.exe"}) {
                File f = new File(dir, n);
                if (f.isFile()) tlouAddressSpace(f, log);
            }
            if (new File(dir, "tlou-i.exe").isFile()) {
                int bits = userAddressBits();
                say(log, "device: " + (bits > 0 ? bits + "-bit" : "unknown") + " user address space");
                // 39 bits (512 GiB) can't hold the game's fixed high reservations: the hook moves them.
                // A larger address space needs only the exe patch (as GameHub does).
                if (bits == 0 || bits <= 39) tlouHook(context, container, shortcut.getExtra("envVars", ""), dir, log, false);
                else tlouHook(context, container, shortcut.getExtra("envVars", ""), dir, log, true);
            }
        }
    }

    private static void tlouAddressSpace(File exe, Consumer<String> log) {
        try (RandomAccessFile raf = new RandomAccessFile(exe, "r")) {
            long offset = -1;
            if (raf.length() == TLOU_SIZE) {
                byte[] b = new byte[TLOU_ORIG.length];
                raf.seek(TLOU_OFFSET);
                raf.readFully(b);
                if (Arrays.equals(b, TLOU_FIXED)) { say(log, "TLOU: " + exe.getName() + " already patched"); return; }
                if (Arrays.equals(b, TLOU_ORIG)) offset = TLOU_OFFSET;
            }
            if (offset < 0) {
                // Another build: patch only when the sequence is found exactly once.
                long[] found = find(raf, TLOU_ORIG);
                long[] fixed = find(raf, TLOU_FIXED);
                if (found[1] == 0 && fixed[1] >= 1) { say(log, "TLOU: " + exe.getName() + " already patched"); return; }
                if (found[1] != 1) {
                    say(log, "TLOU: " + exe.getName() + " (" + raf.length() + " bytes): address-space patch not applied, "
                            + (found[1] == 0 ? "sequence not found" : "sequence found " + found[1] + " times"));
                    return;
                }
                offset = found[0];
            }
            raf.close();
            File bak = new File(exe.getPath() + ".YurNLator.bak");
            if (!bak.exists()) copy(exe, bak);
            try (RandomAccessFile w = new RandomAccessFile(exe, "rw")) {
                w.seek(offset);
                w.write(TLOU_FIXED);
            }
            say(log, "TLOU: patched " + exe.getName() + " at 0x" + Long.toHexString(offset)
                    + " (address-space reservation 512 -> 192 GiB; backup " + bak.getName() + ")");
        } catch (Exception e) {
            say(log, "TLOU: couldn't patch " + exe.getName() + ": " + e.getMessage());
        }
    }

    /**
     * The rest the game needs on Android's 39-bit address space (tools/tlouhook): a proxy
     * dxgi.dll in the game folder that moves its fixed high reservations down, clamps its 40-bit
     * address requirements and fills a NULL pipeline-library name; DXVK's dxgi.dll goes next to
     * it as dxgi_dxvk.dll. Set TLOUHOOK=0 in the game's variables to leave it out.
     */
    private static void tlouHook(android.content.Context context, Container container, String shortcutEnv, File dir,
                                 Consumer<String> log, boolean notNeeded) {
        File hook = new File(dir, "dxgi.dll");
        File proxied = new File(dir, "dxgi_dxvk.dll");
        File marker = new File(dir, "dxgi.dll.yurnlator");
        try {
            String env = (container.getEnvVars() != null ? container.getEnvVars() : "") + " "
                    + (shortcutEnv != null ? shortcutEnv : "");
            // TLOUHOOK=1 forces it on a large address space, TLOUHOOK=0 leaves it out on any.
            boolean off = env.contains("TLOUHOOK=0") || (notNeeded && !env.contains("TLOUHOOK=1"));
            if (off) {
                if (marker.isFile() && hook.isFile()) {
                    hook.delete(); marker.delete();
                    File bak = new File(dir, "dxgi.dll.YurNLator.bak");
                    if (bak.isFile()) bak.renameTo(hook);
                    say(log, "TLOU: hook removed (" + (notNeeded ? "not needed with this address space" : "TLOUHOOK=0") + ")");
                }
                return;
            }
            File sys = new File(container.getRootDir(), ".wine/drive_c/windows/system32/dxgi.dll");
            if (!sys.isFile()) { say(log, "TLOU: no system32 dxgi.dll, hook not installed"); return; }
            if (!proxied.isFile() || proxied.length() != sys.length()) copy(sys, proxied);
            byte[] asset;
            try (java.io.InputStream in = context.getAssets().open("gamefixes/tlou_dxgi.dll")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                asset = out.toByteArray();
            }
            if (hook.isFile() && !marker.isFile() && hook.length() != asset.length) {
                File bak = new File(dir, "dxgi.dll.YurNLator.bak");
                if (!bak.exists()) copy(hook, bak);
            }
            if (!hook.isFile() || hook.length() != asset.length || !Arrays.equals(java.nio.file.Files.readAllBytes(hook.toPath()), asset)) {
                try (FileOutputStream out = new FileOutputStream(hook)) { out.write(asset); }
                new FileOutputStream(marker).close();
                say(log, "TLOU: hook installed (dxgi.dll -> dxgi_dxvk.dll; log: tlouhook.log in the game folder)");
            } else {
                say(log, "TLOU: hook in place");
            }
        } catch (Exception e) {
            say(log, "TLOU: couldn't install the hook: " + e.getMessage());
        }
    }

    private static int cachedBits = -1;

    /**
     * Bits of the user address space this kernel gives a process (39 = 512 GiB, 48 = 256 TiB),
     * from the highest mapping of this process (the stack sits at the top); 0 = unknown.
     */
    public static synchronized int userAddressBits() {
        if (cachedBits >= 0) return cachedBits;
        long top = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int dash = line.indexOf('-'), sp = line.indexOf(' ');
                if (dash <= 0 || sp <= dash) continue;
                try {
                    long end = Long.parseUnsignedLong(line.substring(dash + 1, sp), 16);
                    if (Long.compareUnsigned(end, 0xffff000000000000L) < 0 && Long.compareUnsigned(end, top) > 0) top = end;
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        int bits = 0;
        if (top > 0) bits = 64 - Long.numberOfLeadingZeros(top - 1);
        cachedBits = bits;
        return bits;
    }

    // Part II, from GameHub's tloull1 (four builds): "48 BA" + imm64 0x8000000000 -> 0x3000000000.
    private static final byte[] TLOU2_ORIG  = {0x48, (byte) 0xBA, 0, 0, 0, 0, (byte) 0x80, 0, 0, 0};
    private static final byte[] TLOU2_FIXED = {0x48, (byte) 0xBA, 0, 0, 0, 0, 0x30, 0, 0, 0};
    private static final Object[][] TLOU2_BUILDS = {
            {"tlou-ii.exe", 0x3ca5878L, 0x1267732L},
            {"tlou-ii-l.exe", 0x3cde070L, 0x1279fc2L},
            {"tlou-ii.exe", 0x3cb18c0L, 0x126b532L},
            {"tlou-ii-l.exe", 0x3cea0c0L, 0x127ddf2L},
    };

    private static void tlou2AddressSpace(File exe, Consumer<String> log) {
        try (RandomAccessFile raf = new RandomAccessFile(exe, "r")) {
            long offset = -1;
            for (Object[] b : TLOU2_BUILDS) {
                if (!exe.getName().equalsIgnoreCase((String) b[0]) || raf.length() != (Long) b[1]) continue;
                byte[] cur = new byte[TLOU2_ORIG.length];
                raf.seek((Long) b[2]);
                raf.readFully(cur);
                if (Arrays.equals(cur, TLOU2_FIXED)) { say(log, "TLOU2: " + exe.getName() + " already patched"); return; }
                if (Arrays.equals(cur, TLOU2_ORIG)) offset = (Long) b[2];
            }
            if (offset < 0) {
                long[] found = find(raf, TLOU2_ORIG);
                long[] fixed = find(raf, TLOU2_FIXED);
                if (found[1] == 0 && fixed[1] >= 1) { say(log, "TLOU2: " + exe.getName() + " already patched"); return; }
                if (found[1] != 1) {
                    say(log, "TLOU2: " + exe.getName() + " (" + raf.length() + " bytes): address-space patch not applied, "
                            + (found[1] == 0 ? "sequence not found" : "sequence found " + found[1] + " times"));
                    return;
                }
                offset = found[0];
            }
            raf.close();
            File bak = new File(exe.getPath() + ".YurNLator.bak");
            if (!bak.exists()) copy(exe, bak);
            try (RandomAccessFile w = new RandomAccessFile(exe, "rw")) {
                w.seek(offset);
                w.write(TLOU2_FIXED);
            }
            say(log, "TLOU2: patched " + exe.getName() + " at 0x" + Long.toHexString(offset)
                    + " (address-space reservation 512 -> 192 GiB; backup " + bak.getName() + ")");
        } catch (Exception e) {
            say(log, "TLOU2: couldn't patch " + exe.getName() + ": " + e.getMessage());
        }
    }

    /** {first offset, count} of [pattern] in the file. */
    private static long[] find(RandomAccessFile raf, byte[] pattern) throws java.io.IOException {
        long first = -1, count = 0;
        byte[] buf = new byte[1 << 20];
        long pos = 0;
        raf.seek(0);
        int keep = 0;
        while (true) {
            int n = raf.read(buf, keep, buf.length - keep);
            if (n <= 0) break;
            int len = keep + n;
            for (int i = 0; i + pattern.length <= len; i++) {
                if (buf[i] != pattern[0]) continue;
                int k = 1;
                while (k < pattern.length && buf[i + k] == pattern[k]) k++;
                if (k == pattern.length) { if (first < 0) first = pos + i; count++; }
            }
            keep = Math.min(pattern.length - 1, len);
            System.arraycopy(buf, len - keep, buf, 0, keep);
            pos += len - keep;
        }
        return new long[]{first, count};
    }

    private static void copy(File from, File to) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[1 << 20];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    /** "F:\Game\x.exe" -> the container drive's folder + "Game/x.exe". */
    static File unixPath(Container container, String winPath) {
        String p = winPath.trim().replace('"', ' ').trim();
        if (p.length() < 3 || p.charAt(1) != ':') return null;
        String letter = String.valueOf(p.charAt(0)).toUpperCase(Locale.ROOT);
        String rest = p.substring(2).replace('\\', '/');
        for (String[] d : container.drivesIterator()) {
            if (d[0].equalsIgnoreCase(letter)) return new File(d[1] + (rest.startsWith("/") ? rest : "/" + rest));
        }
        return null;
    }

    private static void say(Consumer<String> log, String line) {
        android.util.Log.i("GameFixes", line);
        if (log != null) log.accept("[YurNLator] " + line);
    }
}
