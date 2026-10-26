# Review

## Critical
无。

## Warning
- `VlcHooker.runSetup` 在计算 `sPruneHome` 前处理 `pc.enabled == false`，字幕关闭时会直接返回；因此首页精简开关不能独立于字体开关工作。修复时应先读取并设置 `sPruneHome`，再决定是否准备字体文件。
- `MainActivity.refreshUi` 没有把 `prune_home` 写回 `mPruneHomeSwitch`。页面重新进入后可能显示布局默认值，而不是已保存值。
- `hideAudioNavigation` 同时隐藏 `nav_playlists`，这与课程播放列表需求冲突。应拆成独立开关，默认保留播放列表。

## Info
- VLC 原生已支持按媒体或全局记忆倍速、续播、AB 循环端点持久化、书签增删/重命名/跳转、视频快进退和长按临时倍速；首版模块应提供快捷入口和课程聚合，而不是重复实现播放器底层。
- `installAudioPruningHooks` 还会把多个音频媒体库查询结果置空；若课程包含音频课件，应只隐藏不需要的导航，并避免无条件清空数据查询。
- 本次验证只做静态源码检查；未安装 APK、未在 VLC 3.7.1 arm64 设备测试启动顺序和播放性能。
