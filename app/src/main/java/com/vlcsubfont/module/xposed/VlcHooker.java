package com.vlcsubfont.module.xposed;

import com.vlcsubfont.module.FontStore;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
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
import java.lang.reflect.Array;
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

    private static final String TAG = "VlcSubtitleFont";

    private static volatile Application sApp;
    private static final CountDownLatch sSetupDone = new CountDownLatch(1);
    private static volatile String sResult = "setup-running";
    private static volatile MemoryPatcher.Spec sSpec;
    /** 常驻引用防止 GC 关闭 fts.xml 的 fd */
    private static volatile ParcelFileDescriptor sFtsFd;
    private static volatile boolean sSetupStarted;
    private static volatile boolean sToastShown;
    private static volatile boolean sPruneAudio = true;
    private static volatile boolean sPrunePlaylists;
    /** 配置读取完成前不修改 VLC 导航或媒体库结果，避免启动竞态。 */
    private static volatile boolean sPruneHomeReady;

    private static final class Config {
        boolean enabled;
        long version;
        String fontName;
        boolean pruneAudio = true;
        boolean prunePlaylists;
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
        installAudioPruningHooks(cl);
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
            Config mc = readMediaConfig(app);
            // Read this independently from the font switch: pruning is a separate setting.
            sPruneAudio = pc != null ? pc.pruneAudio : (mc == null || mc.pruneAudio);
            sPrunePlaylists = pc != null ? pc.prunePlaylists : (mc != null && mc.prunePlaylists);
            sPruneHomeReady = true;
            log("audio pruning " + (sPruneAudio ? "enabled" : "disabled")
                    + ", playlist pruning " + (sPrunePlaylists ? "enabled" : "disabled"));
            if ((pc != null && !pc.enabled) || (pc == null && mc != null && !mc.enabled)) {
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
                int audioIndex = c.getColumnIndex("prune_audio");
                int playlistIndex = c.getColumnIndex("prune_playlists");
                int legacyIndex = c.getColumnIndex("prune_home");
                cfg.pruneAudio = audioIndex >= 0
                        ? c.getInt(audioIndex) != 0
                        : legacyIndex < 0 || c.getInt(legacyIndex) != 0;
                cfg.prunePlaylists = playlistIndex >= 0
                        ? c.getInt(playlistIndex) != 0
                        : legacyIndex >= 0 && c.getInt(legacyIndex) != 0;
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
            boolean legacyPrune = json.optBoolean("pruneHome", true);
            cfg.pruneAudio = json.has("pruneAudio")
                    ? json.optBoolean("pruneAudio", true) : legacyPrune;
            cfg.prunePlaylists = json.has("prunePlaylists")
                    ? json.optBoolean("prunePlaylists", false) : legacyPrune;
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
                    notifyOnce("VLC字幕字体自定义未生效: " + sResult);
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

    private static void installAudioPruningHooks(ClassLoader cl) {
        try {
            Class<?> navigator = XposedHelpers.findClass(
                    "org.videolan.vlc.gui.helpers.Navigator", cl);
            Class<?> mainActivity = XposedHelpers.findClass(
                    "org.videolan.vlc.gui.MainActivity", cl);
            XposedHelpers.findAndHookMethod(navigator, "setupNavigation",
                    mainActivity, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            hideAudioNavigation(param);
                        }
                    });
            log("audio navigation hook installed");
        } catch (Throwable t) {
            log("audio navigation hook failed: " + t);
        }

        try {
            Class<?> medialibrary = XposedHelpers.findClass(
                    "org.videolan.medialibrary.MedialibraryImpl", cl);
            String[] audioMethods = {
                    "getAudio", "getPagedAudio", "getRecentAudio", "searchAudio", "getAudioCount",
                    "getAlbums", "getAlbumsCount", "getArtists", "getArtistsCount",
                    "getGenres", "getGenresCount"
            };
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sPruneHomeReady || !sPruneAudio || param.getResult() == null) return;
                    if (param.getResult() != null) {
                        Class<?> type = param.getResult().getClass();
                        if (type.isArray()) {
                            param.setResult(Array.newInstance(type.getComponentType(), 0));
                        } else if (param.getResult() instanceof Integer) {
                            param.setResult(0);
                        }
                    }
                }
            };
            for (String method : audioMethods) {
                XposedBridge.hookAllMethods(medialibrary, method, hook);
            }
            log("audio library query hooks installed");
        } catch (Throwable t) {
            log("audio library hook failed: " + t);
        }
    }

    private static void hideAudioNavigation(XC_MethodHook.MethodHookParam param) {
        if (!sPruneHomeReady || (!sPruneAudio && !sPrunePlaylists)) return;
        try {
            Object activity = param.args[0];
            String packageName = ((Context) activity).getPackageName();
            int audioId = ((Context) activity).getResources().getIdentifier(
                    "nav_audio", "id", packageName);
            int videoId = ((Context) activity).getResources().getIdentifier(
                    "nav_video", "id", packageName);
            int[] navigationIds = {
                    ((Context) activity).getResources().getIdentifier("navigation", "id", packageName),
                    ((Context) activity).getResources().getIdentifier("navigation_rail", "id", packageName)
            };
            for (int navigationId : navigationIds) {
                if (navigationId == 0) continue;
                Object navigation = XposedHelpers.callMethod(activity, "findViewById", navigationId);
                if (navigation == null) continue;
                Object menu = XposedHelpers.callMethod(navigation, "getMenu");
                Object audioItem = XposedHelpers.callMethod(menu, "findItem", audioId);
                if (audioItem != null && sPruneAudio) {
                    XposedHelpers.callMethod(audioItem, "setVisible", false);
                }
                int playlistId = ((Context) activity).getResources().getIdentifier(
                        "nav_playlists", "id", packageName);
                Object playlistItem = XposedHelpers.callMethod(menu, "findItem", playlistId);
                if (playlistItem != null && sPrunePlaylists) {
                    XposedHelpers.callMethod(playlistItem, "setVisible", false);
                }
            }
            int currentId = XposedHelpers.getIntField(param.thisObject, "currentFragmentId");
            int playlistId = ((Context) activity).getResources().getIdentifier(
                    "nav_playlists", "id", packageName);
            if ((currentId == audioId && sPruneAudio)
                    || (currentId == playlistId && sPrunePlaylists)
                    || currentId == 0) {
                XposedHelpers.setIntField(param.thisObject, "currentFragmentId", videoId);
            }
            log("navigation pruning applied");
        } catch (Throwable t) {
            log("hide audio navigation failed: " + t);
        }
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
