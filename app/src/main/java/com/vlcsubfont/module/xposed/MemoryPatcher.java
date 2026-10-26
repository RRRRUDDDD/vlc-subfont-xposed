package com.vlcsubfont.module.xposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 纯 Java 实现 libvlc.so 字体路径内存补丁（参考 https://bbs.acgrip.com/thread-12705-1-1.html，
 * 但目标全部改为 VLC 私有目录，不在公共存储留任何文件）。
 *
 * 三处等长（或更短，以 NUL 补齐）改写：
 *  1) file:///system/etc/fonts.xml        -> file:///proc/self/fd/<n>   （fts.xml 常驻 fd）
 *  2) /system/fonts\0                     -> ""（空目录，使 fts.xml 中的绝对路径生效）
 *  3) /system/fonts/NotoSansCJK-Regular.ttc -> <dataDir>/ass.ttf        （ASS 缺字回退）
 *
 * 写入通道二选一：
 *  - native 库（mprotect + memcpy，触发私有映射 COW）：Samsung 等内核禁止应用写
 *    /proc 下的 mem 设备，此时必须走这条路；
 *  - /proc/self/mem（FOLL_FORCE 写）：非限制内核上的回退。
 */
public final class MemoryPatcher {

    private static final String TAG = "VlcSubtitleFont";

    private static final int CHUNK = 1 << 20;
    private static final int OVERLAP = 64;
    private static final String LIB_NAME = "libsubfontpatch.so";

    private static final byte[][] ORIG = {
            b("file:///system/etc/fonts.xml"),
            b("/system/fonts\u0000"),
            b("/system/fonts/NotoSansCJK-Regular.ttc"),
    };

    /** 补丁目标三处的原始字节总长（含结尾 NUL），写入时按此长度零填充 */
    private static final int[] REGION = new int[ORIG.length];
    static {
        for (int i = 0; i < ORIG.length; i++) {
            REGION[i] = ORIG[i].length;
        }
    }

    private static final Object sLock = new Object();
    private static volatile boolean sPatched;
    private static volatile boolean sNative;
    private static volatile boolean sNativeTried;

    private MemoryPatcher() {
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    /** 补丁参数：fts.xml 的 fd 与 ASS 回退字体的绝对路径。 */
    public static final class Spec {
        public final int ftsFd;
        public final String assFallbackPath;

        public Spec(int ftsFd, String assFallbackPath) {
            this.ftsFd = ftsFd;
            this.assFallbackPath = assFallbackPath;
        }
    }

    /**
     * 加载模块自带的 native 补丁库。依次尝试:
     *  1) System.loadLibrary（LSPosed 会把模块 native 目录注入目标进程）；
     *  2) PackageManager / 类加载器 / maps 定位模块 APK 后按绝对路径加载。
     * 全部失败时保留 /proc/self/mem 回退通道。
     */
    public static synchronized void initNative(Context app) {
        if (sNativeTried) {
            return;
        }
        sNativeTried = true;
        try {
            System.loadLibrary("subfontpatch");
            sNative = true;
            log("native 补丁库已加载 (loadLibrary)");
            return;
        } catch (Throwable t) {
            log("System.loadLibrary 失败: " + t);
        }
        String dir = moduleNativeDir(app);
        if (dir == null) {
            log("未定位到模块 native 库，使用 /proc/self/mem 方式");
            return;
        }
        try {
            System.load(new File(dir, LIB_NAME).getAbsolutePath());
            sNative = true;
            log("native 补丁库已加载: " + dir);
        } catch (Throwable t) {
            log("加载 native 补丁库失败，使用 /proc/self/mem 方式: " + t);
        }
    }

    /** 依次尝试 PackageManager 与模块类加载器反射定位模块 APK 的 native 库目录。 */
    private static String moduleNativeDir(Context app) {
        try {
            ApplicationInfo ai = app.getPackageManager()
                    .getApplicationInfo(com.vlcsubfont.module.FontStore.MODULE_PACKAGE, 0);
            log("pm: nativeLibraryDir=" + ai.nativeLibraryDir);
            if (ai.nativeLibraryDir != null
                    && new File(ai.nativeLibraryDir, LIB_NAME).canRead()) {
                return ai.nativeLibraryDir;
            }
        } catch (Throwable t) {
            log("pm 定位失败: " + t);
        }
        try {
            Object pathList = XposedHelpers.getObjectField(
                    MemoryPatcher.class.getClassLoader(), "pathList");
            Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            for (Object e : elements) {
                Object p = XposedHelpers.getObjectField(e, "path");
                log("cl: element path=" + p);
                if (p == null) {
                    continue;
                }
                File apk = p instanceof File ? (File) p : new File(p.toString());
                File libDir = new File(apk.getParentFile(), "lib");
                File[] abis = libDir.listFiles();
                if (abis == null) {
                    continue;
                }
                for (File a : abis) {
                    if (new File(a, LIB_NAME).canRead()) {
                        return a.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable t) {
            log("cl 定位失败: " + t);
        }
        // LSPosed 内存化加载后，模块 APK 仍可能被框架 mmap/open，可从 maps/fd 找到路径
        try {
            String needle = com.vlcsubfont.module.FontStore.MODULE_PACKAGE;
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int i = line.indexOf(needle);
                    if (i >= 0) {
                        String path = line.substring(line.lastIndexOf(' ') + 1).trim();
                        log("maps 命中: " + path);
                        String found = findLibBeside(path);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
            File fdDir = new File("/proc/self/fd");
            File[] fds = fdDir.listFiles();
            if (fds != null) {
                for (File fd : fds) {
                    String target;
                    try {
                        target = fd.getCanonicalPath();
                    } catch (Throwable t) {
                        continue;
                    }
                    if (target.contains(needle)) {
                        log("fd 命中: " + target);
                        String found = findLibBeside(target);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("maps/fd 定位失败: " + t);
        }
        return null;
    }

    /** 给定模块内任意文件路径，向同目录/上级目录探测 lib/<abi>/libsubfontpatch.so。 */
    private static String findLibBeside(String path) {
        File f = new File(path);
        File dir = f.isDirectory() ? f : f.getParentFile();
        while (dir != null) {
            File libDir = new File(dir, "lib");
            File[] abis = libDir.listFiles();
            if (abis != null) {
                for (File a : abis) {
                    if (new File(a, LIB_NAME).canRead()) {
                        return a.getAbsolutePath();
                    }
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * 返回: 1 = 已完成全部三处改写（或此前已完成）；
     *       0 = libvlc.so 尚未加载，可稍后重试；
     *      -1 = 永久失败（模式不完整或 IO 异常）。每个进程最多执行一次改写。
     */
    public static int patchOnce(Spec spec) {
        if (sPatched) {
            return 1;
        }
        synchronized (sLock) {
            if (sPatched) {
                return 1;
            }
            List<long[]> ranges;
            try {
                ranges = libVlcRanges();
            } catch (IOException e) {
                log("读取 /proc/self/maps 失败: " + e);
                return -1;
            }
            if (ranges.isEmpty()) {
                return 0;
            }
            byte[][] repl = buildRepl(spec);
            if (repl == null) {
                return -1;
            }
            try {
                int[] counts = sNative ? patchNative(ranges, repl) : patchMem(ranges, repl);
                boolean ok = counts[0] > 0 && counts[1] > 0 && counts[2] > 0;
                log("patch libvlc.so (" + (sNative ? "mprotect" : "proc-mem") + "): fonts.xml-fd="
                        + counts[0] + " fontDir=" + counts[1] + " assFallback=" + counts[2]
                        + (ok ? " -> 成功" : " -> 模式不完整，已放弃（VLC 版本可能不受支持）"));
                if (!ok) {
                    return -1;
                }
                sPatched = true;
                return 1;
            } catch (Throwable t) {
                log("patch 失败: " + t);
                return -1;
            }
        }
    }

    private static byte[][] buildRepl(Spec spec) {
        byte[][] repl = new byte[ORIG.length][];
        String uri = "file:///proc/self/fd/" + spec.ftsFd;
        // REGION 为含结尾 NUL 的区域长度，替换串（内容+NUL）不超过它即可
        if (uri.length() > REGION[0]) {
            log("fts fd URI 过长: " + uri);
            return null;
        }
        repl[0] = pad(uri);
        repl[1] = new byte[REGION[1]]; // 全零 => 空字符串
        if (spec.assFallbackPath.length() > REGION[2]) {
            log("ASS 回退路径过长: " + spec.assFallbackPath);
            return null;
        }
        repl[2] = pad(spec.assFallbackPath);
        return repl;
    }

    /** 编码为原始区域等长的字节数组：内容 + NUL + 零填充 */
    private static byte[] pad(String s) {
        byte[] out = new byte[s.length() + 1];
        System.arraycopy(b(s), 0, out, 0, s.length());
        return out;
    }

    // ------------------------------------------------------------- native 通道

    private static int[] patchNative(List<long[]> ranges, byte[][] repl) throws IOException {
        int[] counts = new int[ORIG.length];
        long[] first = new long[ORIG.length];
        for (long[] r : ranges) {
            long[] out = nativePatchRange(r[0], r[1], r[2] != 0, ORIG, repl);
            if (out == null || out.length != ORIG.length * 2) {
                throw new IOException("native 补丁返回异常");
            }
            for (int p = 0; p < ORIG.length; p++) {
                if (out[2 * p] > 0) {
                    if (counts[p] == 0) {
                        first[p] = out[2 * p + 1];
                    }
                    counts[p] += (int) out[2 * p];
                }
            }
        }
        for (int p = 0; p < ORIG.length; p++) {
            if (counts[p] == 0) {
                continue;
            }
            byte[] got = new byte[repl[p].length];
            if (!nativeRead(first[p], got) || !Arrays.equals(got, repl[p])) {
                throw new IOException("native 校验不一致: pattern#" + p);
            }
        }
        return counts;
    }

    private static native long[] nativePatchRange(long start, long end, boolean exec,
                                                  byte[][] patterns, byte[][] repls);

    private static native boolean nativeRead(long addr, byte[] out);

    // -------------------------------------------------------- proc/mem 回退通道

    private static int[] patchMem(List<long[]> ranges, byte[][] repl) throws IOException {
        int[] counts = new int[ORIG.length];
        long[][] firstTarget = new long[ORIG.length][1];
        try (RandomAccessFile raf = new RandomAccessFile("/proc/self/mem", "rw")) {
            FileChannel ch = raf.getChannel();
            for (long[] r : ranges) {
                scanRange(ch, r[0], r[1], repl, counts, firstTarget);
            }
            verify(ch, repl, counts, firstTarget);
        }
        return counts;
    }

    private static void verify(FileChannel ch, byte[][] repl, int[] counts,
                               long[][] firstTarget) throws IOException {
        for (int p = 0; p < ORIG.length; p++) {
            if (counts[p] == 0) {
                continue;
            }
            byte[] got = new byte[repl[p].length];
            ByteBuffer rb = ByteBuffer.wrap(got);
            long pos = firstTarget[p][0];
            while (rb.hasRemaining()) {
                int n = ch.read(rb, pos + rb.position());
                if (n < 0) {
                    throw new IOException("verify read failed at 0x" + Long.toHexString(pos));
                }
            }
            if (!Arrays.equals(got, repl[p])) {
                throw new IOException("verify mismatch at 0x" + Long.toHexString(pos));
            }
        }
    }

    private static void scanRange(FileChannel ch, long start, long end, byte[][] repl,
                                  int[] counts, long[][] firstTarget) throws IOException {
        byte[] buf = new byte[CHUNK + OVERLAP];
        long addr = start;
        int carry = 0;
        while (addr < end) {
            int want = (int) Math.min((long) CHUNK, end - addr);
            int n = ch.read(ByteBuffer.wrap(buf, carry, want), addr);
            if (n <= 0) {
                throw new IOException("read /proc/self/mem at 0x"
                        + Long.toHexString(addr) + " -> " + n);
            }
            int window = carry + n;
            searchAndPatch(ch, buf, window, addr - carry, repl, counts, firstTarget);
            carry = Math.min(OVERLAP, window);
            System.arraycopy(buf, window - carry, buf, 0, carry);
            addr += n;
        }
    }

    private static void searchAndPatch(FileChannel ch, byte[] buf, int window, long base,
                                       byte[][] repl, int[] counts, long[][] firstTarget)
            throws IOException {
        for (int p = 0; p < ORIG.length; p++) {
            byte[] pat = ORIG[p];
            int from = 0;
            while (true) {
                int idx = indexOf(buf, window, pat, from);
                if (idx < 0) {
                    break;
                }
                long target = base + idx;
                writeAll(ch, repl[p], target);
                if (counts[p] == 0) {
                    firstTarget[p][0] = target;
                }
                counts[p]++;
                from = idx + pat.length;
            }
        }
    }

    private static void writeAll(FileChannel ch, byte[] data, long target) throws IOException {
        ByteBuffer wb = ByteBuffer.wrap(data);
        while (wb.hasRemaining()) {
            int n = ch.write(wb, target + wb.position());
            if (n <= 0) {
                throw new IOException("write /proc/self/mem at 0x"
                        + Long.toHexString(target) + " -> " + n);
            }
        }
    }

    private static int indexOf(byte[] data, int limit, byte[] pat, int from) {
        byte first = pat[0];
        int max = limit - pat.length;
        outer:
        for (int i = from; i <= max; i++) {
            if (data[i] != first) {
                continue;
            }
            for (int j = 1; j < pat.length; j++) {
                if (data[i + j] != pat[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ maps

    /** 返回 libvlc.so 的可读映射区间 long[]{start, end, exec}。 */
    private static List<long[]> libVlcRanges() throws IOException {
        List<long[]> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 6);
                if (parts.length < 2 || !parts[1].startsWith("r")) {
                    continue;
                }
                String path = parts.length >= 6 ? parts[5] : "";
                if (!path.contains("libvlc.so")) {
                    continue;
                }
                int dash = parts[0].indexOf('-');
                if (dash <= 0) {
                    continue;
                }
                try {
                    long start = Long.parseLong(parts[0].substring(0, dash), 16);
                    long end = Long.parseLong(parts[0].substring(dash + 1), 16);
                    if (end > start) {
                        out.add(new long[]{start, end, parts[1].contains("x") ? 1 : 0});
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }
}
