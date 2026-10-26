package com.vlcsubfont.module.xposed;

import com.vlcsubfont.module.FontStore;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * VLC 注入端：负责取配置/字体（模块 App 的 ContentProvider，media 镜像兜底）、
 * 落到 VLC 私有目录、生成 fts.xml，并在 LibVLC 初始化前完成内存补丁。
 */
public final class VlcHooker {

    private static final String TAG = "VlcSubFont";

    private static volatile Application sApp;
    private static final CountDownLatch sSetupDone = new CountDownLatch(1);
    private static volatile String sResult = "setup-running";
    private static volatile MemoryPatcher.Spec sSpec;
    /** 常驻引用防止 GC 关闭 fts.xml 的 fd */
    private static volatile ParcelFileDescriptor sFtsFd;
    private static volatile boolean sSetupStarted;
    private static volatile boolean sToastShown;

    private static final class Config {
        boolean enabled;
        long version;
        String fontName;
    }

    private VlcHooker() {
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    public static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sApp == null) {
                                sApp = (Application) param.thisObject;
                            }
                            startSetup();
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        try {
            Class<?> libVLC = XposedHelpers.findClass("org.videolan.libvlc.LibVLC", cl);
            XposedBridge.hookAllConstructors(libVLC, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    onLibVlcInit(param);
                }
            });
        } catch (Throwable t) {
            log("hook LibVLC 失败: " + t);
        }
    }

    // ---------------------------------------------------------------- setup

    private static synchronized void startSetup() {
        if (sSetupStarted) {
            return;
        }
        sSetupStarted = true;
        Application app = sApp;
        if (app == null) {
            sResult = "no-context";
            sSetupDone.countDown();
            return;
        }
        Thread t = new Thread(() -> runSetup(app), "vlc-subfont-setup");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static void runSetup(Application app) {
        try {
            Config pc = queryProviderConfig(app);
            if (pc != null && !pc.enabled) {
                sResult = "disabled";
                return;
            }
            Config mc = readMediaConfig(app);
            if (pc == null && mc != null && !mc.enabled) {
                sResult = "disabled";
                return;
            }

            File base = new File(app.getFilesDir(), "subfont");
            if (!base.isDirectory() && !base.mkdirs()) {
                throw new IOException("无法创建 " + base);
            }
            File font = new File(base, "font.ttf");
            File fts = new File(base, "fts.xml");
            File stamp = new File(base, ".applied");
            File ass = new File(app.getApplicationInfo().dataDir, FontStore.ASS_FALLBACK_NAME);

            long version = pc != null ? pc.version : (mc != null ? mc.version : -1L);
            boolean fresh = !(stamp.canRead() && font.canRead() && ass.canRead() && version >= 0
                    && Long.toString(version).equals(readText(stamp).trim()));

            if (fresh) {
                Long applied = copyFromProvider(app, pc, font, ass);
                if (applied == null) {
                    applied = copyFromMediaMirror(app, font, ass);
                }
                if (applied == null) {
                    sResult = (pc == null && mc == null) ? "no-module" : "no-font";
                    log("未取到字体（Provider 与 media 镜像均不可用）");
                    return;
                }
                writeText(stamp, Long.toString(applied));
            }

            writeText(fts, FontStore.buildFtsXml(font.getAbsolutePath()));

            ParcelFileDescriptor pfd =
                    ParcelFileDescriptor.open(fts, ParcelFileDescriptor.MODE_READ_ONLY);
            sFtsFd = pfd;
            sSpec = new MemoryPatcher.Spec(pfd.getFd(), ass.getAbsolutePath());

            if (!fts.canRead() || !font.canRead() || !ass.canRead()) {
                throw new IOException("VLC 无法读取生成的字体文件");
            }
            sResult = "ok";
            log("就绪: font=" + font + " fts-fd=" + pfd.getFd() + " ass=" + ass);
        } catch (Throwable t) {
            sResult = "error: " + t.getMessage();
            log("初始化失败: " + t);
        } finally {
            sSetupDone.countDown();
        }
    }

    private static Config queryProviderConfig(Application app) {
        try {
            Cursor c = app.getContentResolver()
                    .query(FontStore.CONFIG_URI, null, null, null, null);
            if (c == null) {
                log("Provider 配置查询返回空");
                return null;
            }
            try {
                if (!c.moveToFirst()) {
                    return null;
                }
                Config cfg = new Config();
                cfg.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0;
                cfg.version = c.getLong(c.getColumnIndexOrThrow("version"));
                cfg.fontName = c.getString(c.getColumnIndexOrThrow("font_name"));
                return cfg;
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            log("Provider 不可用: " + t);
            return null;
        }
    }

    private static Config readMediaConfig(Application app) {
        try {
            File f = mediaMirrorConfig();
            if (!f.canRead()) {
                return null;
            }
            JSONObject json = new JSONObject(readText(f));
            Config cfg = new Config();
            cfg.enabled = json.optBoolean("enabled");
            cfg.version = json.optLong("version", -1L);
            cfg.fontName = json.optString("fontName", null);
            return cfg;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long copyFromProvider(Application app, Config pc, File font, File ass) {
        try (InputStream in = app.getContentResolver().openInputStream(FontStore.FONT_URI)) {
            if (in == null) {
                return null;
            }
            copyTo(in, font);
            copyFile(font, ass);
            return pc != null ? pc.version : 0L;
        } catch (Throwable t) {
            log("Provider 取字体失败: " + t);
            return null;
        }
    }

    private static Long copyFromMediaMirror(Application app, File font, File ass) {
        try {
            File src = new File(new File(android.os.Environment.getExternalStorageDirectory(),
                    "Android/media/" + FontStore.MODULE_PACKAGE), "font.ttf");
            if (!src.canRead()) {
                return null;
            }
            try (InputStream in = new FileInputStream(src)) {
                copyTo(in, font);
            }
            copyFile(font, ass);
            Config mc = readMediaConfig(app);
            return mc != null ? mc.version : 0L;
        } catch (Throwable t) {
            log("media 镜像取字体失败: " + t);
            return null;
        }
    }

    private static File mediaMirrorConfig() {
        return new File(new File(android.os.Environment.getExternalStorageDirectory(),
                "Android/media/" + FontStore.MODULE_PACKAGE), "config.json");
    }

    // ------------------------------------------------------------ libvlc hook

    private static void onLibVlcInit(XC_MethodHook.MethodHookParam param) {
        try {
            if (!sSetupDone.await(20, TimeUnit.SECONDS)) {
                log("初始化超时，本次跳过");
                return;
            }
            if (!"ok".equals(sResult)) {
                if (sResult.startsWith("error") || "no-module".equals(sResult)
                        || "no-font".equals(sResult)) {
                    notifyOnce("VLC 字幕字体模块未生效: " + sResult);
                }
                return;
            }
            injectSsaFontsDir(param);
            MemoryPatcher.initNative(sApp);
            if (MemoryPatcher.patchOnce(sSpec) == 0) {
                startPatchRetry();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static volatile boolean sRetryStarted;

    /** VLC 3.7.x 延迟加载 libvlc.so：构造时可能还没映射，轮询等待加载完成后再补。 */
    private static void startPatchRetry() {
        synchronized (VlcHooker.class) {
            if (sRetryStarted) {
                return;
            }
            sRetryStarted = true;
        }
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000;
            int r;
            do {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
                r = MemoryPatcher.patchOnce(sSpec);
            } while (r == 0 && System.currentTimeMillis() < deadline);
            if (r == 0) {
                log("等待 libvlc.so 加载超时，放弃补丁");
            }
        }, "vlc-subfont-patch");
        t.setDaemon(true);
        t.start();
    }

    private static void injectSsaFontsDir(XC_MethodHook.MethodHookParam param) {
        String opt = "--ssa-fontsdir="
                + new File(sApp.getFilesDir(), "subfont").getAbsolutePath();
        if (param.args.length >= 2) {
            if (param.args[1] instanceof List) {
                List<Object> list = asObjectList(param.args[1]);
                if (!list.contains(opt)) {
                    list.add(opt);
                }
            } else {
                List<String> l = new ArrayList<>();
                l.add(opt);
                param.args[1] = l;
            }
        }
        // 参数不足两枚（1 参构造器）时跳过；其内部委托到 2 参构造器时会再次进入本钩子
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asObjectList(Object o) {
        return (List<Object>) o;
    }

    private static void notifyOnce(final String msg) {
        if (sToastShown) {
            return;
        }
        sToastShown = true;
        Application app = sApp;
        if (app == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    // ------------------------------------------------------------------- io

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("rename 失败: " + tmp + " -> " + f);
        }
    }

    private static void copyTo(InputStream in, File target) throws IOException {
        byte[] buf = new byte[65536];
        File tmp = new File(target.getParentFile(), target.getName() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("rename 失败: " + tmp + " -> " + target);
        }
    }

    private static void copyFile(File src, File target) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            copyTo(in, target);
        }
    }
}
