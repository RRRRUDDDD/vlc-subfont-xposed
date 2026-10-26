# VLC字幕字体自定义

为 VLC for Android（`org.videolan.vlc`）自定义字幕字体，支持 SRT 文本字幕和 ASS 字幕缺字回退。无需修改 VLC APK，也无需替换系统字体。

## 功能

- 选择并应用 ttf、otf、ttc 字体。
- 支持 SRT 字幕字体和 ASS 缺字回退字体。
- 可精简 VLC 的音频入口和音频媒体库查询。
- 可单独精简播放列表入口。
- 可隐藏桌面图标；隐藏后仍可从 LSPosed 的模块设置入口打开设置页。

## 安装与使用

1. 安装 APK。
2. 在 LSPosed 中启用本模块，并将 VLC 加入作用域。
3. 从 LSPosed 的模块设置入口打开本模块，选择 ttf / otf / ttc 字体。
4. 保持自定义字幕字体开关打开，强制停止 VLC 后重新打开。
5. 在 VLC 中播放视频即可看到字幕字体效果。

## 播放器设置

- **精简音频入口和媒体库**：隐藏音频入口，并按开关清空音频媒体库查询结果。
- **精简播放列表入口**：单独控制播放列表入口，默认保留播放列表。
- **隐藏桌面图标**：隐藏桌面启动图标，模块设置仍通过 LSPosed 进入。

## 兼容性

当前已针对 VLC 3.7.1 arm64-v8a 样本验证。其他版本如果没有生效，请查看 LSPosed 日志中的 `VlcSubtitleFont` 标签。

字体文件最终写入 VLC 私有目录。模块通过 ContentProvider 将字体文件描述符交给 VLC 进程，并在 Provider 不可用时使用 `Android/media/com.vlcsubfont.module/` 镜像作为兜底。

## 项目结构

- `app/src/main/java/com/vlcsubfont/module/MainActivity.java`：模块设置页面。
- `app/src/main/java/com/vlcsubfont/module/FontProvider.java`：配置和字体 Provider。
- `app/src/main/java/com/vlcsubfont/module/xposed/VlcHooker.java`：VLC Hook、字体同步和播放器入口精简。
- `app/src/main/java/com/vlcsubfont/module/xposed/MemoryPatcher.java`：运行时字体路径补丁。

## 构建

使用 Android Studio 或 Gradle 构建：

```powershell
gradle :app:assembleRelease
```
