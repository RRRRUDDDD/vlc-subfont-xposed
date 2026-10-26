package com.vlcsubfont.module;

import android.net.Uri;

/**
 * 模块 App 与注入端（VLC 进程）共享的常量与约定。
 *
 * 配置与字体通过模块 App 的 ContentProvider（binder 传 fd）传递，双端均无需存储权限；
 * 模块 App 另在自身 Android/media 目录写一份镜像，供 Provider 不可用时兜底。
 * 字体最终全部落在 VLC 私有目录（files/subfont/ 与 dataDir/ass.ttf），不污染公共存储。
 */
public final class FontStore {

    public static final String MODULE_PACKAGE = "com.vlcsubfont.module";
    public static final String VLC_PACKAGE = "org.videolan.vlc";

    public static final String AUTHORITY = MODULE_PACKAGE + ".font";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    public static final Uri FONT_URI = Uri.parse("content://" + AUTHORITY + "/font");

    /** libvlc.so 补丁 #3 指向的 ASS 回退字体，位于 VLC dataDir 根，路径总长受 37 字符预算约束 */
    public static final String ASS_FALLBACK_NAME = "ass.ttf";

    private FontStore() {
    }

    /**
     * 生成 libvlc Android 字体提供器可解析的 fonts.xml。
     * 因为字体目录常量被补丁清空，font 条目必须写绝对路径（拼接后为 "//..."，Linux 会折叠）。
     * 无名 family 会进入默认回退列表，保证 SRT 默认字体（"Sans"）命中。
     */
    public static String buildFtsXml(String fontPath) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<familyset version=\"23\">\n"
                + "    <family name=\"sans-serif\">\n"
                + "        <font weight=\"400\" style=\"normal\">" + fontPath + "</font>\n"
                + "        <font weight=\"700\" style=\"normal\">" + fontPath + "</font>\n"
                + "    </family>\n"
                + "    <family>\n"
                + "        <font weight=\"400\" style=\"normal\">" + fontPath + "</font>\n"
                + "        <font weight=\"700\" style=\"normal\">" + fontPath + "</font>\n"
                + "    </family>\n"
                + "    <alias name=\"sans\" to=\"sans-serif\" />\n"
                + "    <alias name=\"arial\" to=\"sans-serif\" />\n"
                + "    <alias name=\"helvetica\" to=\"sans-serif\" />\n"
                + "    <alias name=\"noto sans\" to=\"sans-serif\" />\n"
                + "    <alias name=\"noto sans cjk sc\" to=\"sans-serif\" />\n"
                + "</familyset>\n";
    }
}
