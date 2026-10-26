# VLC 字幕字体 Xposed 模块

为 VLC for Android（`org.videolan.vlc`）自定义字幕字体：**SRT 等文本字幕**与 **ASS 字幕缺字回退**均可指定字体。无需修改 VLC APK，无需替换系统字体，不写入任何系统或公共目录。

原理来自 [acgrip 论坛教程《VLC APK(Android版)更改字幕默认字体的方法》](https://bbs.acgrip.com/thread-12705-1-1.html)：VLC 的字幕字体由 `libvlc.so` 内硬编码的路径决定。原教程通过十六进制修改 `libvlc.so` 后重打包 APK；本模块改为 **运行时内存补丁**——在 `LibVLC` 初始化前后对已加载的 `libvlc.so` 做等长字符串改写，因此：

- VLC 可以随时从应用商店/官网更新，模块持续有效（只要相关字符串还在）；
- 不修改 APK，签名不变，可直接覆盖安装/Play 商店更新；
- 写入通道自适应：优先经模块自带的微型 JNI 库用 `mprotect + memcpy`（私有映射 COW，不落盘）改写；若 native 库不可用则回退 `/proc/self/mem`（FOLL_FORCE 写）。Samsung 等厂商内核禁止应用写 `/proc/[pid]/mem`，因此必须走前者。

## 安装与使用

1. 安装 APK，在 **LSPosed** 中启用本模块，并勾选作用域 **VLC**，然后重启 VLC。
2. 打开本模块 App，选择字体文件（支持 ttf / otf / ttc），保持开关打开。
3. **强制停止 VLC 后重新打开**，播放视频即可生效。

> 注意：VLC 3.7.1（arm64-v8a）已验证字符串存在；其他版本如失败请看 LSPosed 日志（标签 `VlcSubFont`），日志会说明哪一处的模式没有匹配到。

## 它改了什么

| libvlc.so 内原始常量 | 改写为 | 作用 |
| --- | --- | --- |
| `file:///system/etc/fonts.xml` | `file:///proc/self/fd/<n>` | SRT 字体表 fts.xml 放在 VLC 私有目录，以常驻 fd 暴露给 libvlc |
| `/system/fonts\0` | 空字符串 | fts.xml 中的 `<font>` 条目因此可写绝对路径，指向 VLC 私有目录 |
| `/system/fonts/NotoSansCJK-Regular.ttc` | `<VLC dataDir>/ass.ttf` | ASS 缺字时的回退字体 |

另外在 `LibVLC` 构造时注入一个选项 `--ssa-fontsdir=<VLC files>/subfont`，让 libass 能按字体名在用户字体目录中匹配（ASS 字幕指定字体名时正常命中，而不只是回退）。

`fts.xml` 由模块自动生成（含 sans-serif 具名 family + 无名默认回退 family + 常见 alias），libvlc 的 Android 字体提供器按 Android fonts.xml 格式解析。

## 性能开销

- 补丁只在每次 VLC 进程启动时执行一次（扫描 libvlc.so 映射 + 几十字节改写，毫秒级），成功后全部短路；
- 不 hook 任何播放热路径，无常驻线程/服务，运行期额外开销为零；
- 播放时 VLC 只是正常打开字体文件，与手工装字体无异。

## 文件去向（零公共存储污染）

- 字体最终存放于 **VLC 私有目录**：`files/subfont/font.ttf`、`files/subfont/fts.xml`、`<dataDir>/ass.ttf`。卸载 VLC 或清除其数据即完全还原。
- 模块 App 用 **ContentProvider**（binder 传 fd）把字体交给 VLC 进程，双端都不需要存储权限。
- 模块 App 另在自身 `Android/media/com.vlcsubfont.module/` 写一份镜像，仅当 Provider 不可用时兜底（此时需要 VLC 的「所有文件访问」权限）。

## 前提条件

- LSPosed（或兼容 Xposed API 82 的框架）
- 模块作用域勾选 VLC

## 构建

```
gradle assembleRelease
```

需要 Android SDK（compileSdk 35）与 NDK（项目锁定 `ndkVersion`）；`release` 复用 debug 签名便于直接安装。

## 工程结构

```
app/src/main/java/com/vlcsubfont/module/
├── FontStore.java            # 双端共享常量、fts.xml 生成
├── FontProvider.java         # 模块 App 暴露配置/字体的 ContentProvider
├── MainActivity.java         # 选字体 UI
└── xposed/
    ├── ModuleEntry.java      # Xposed 入口（仅作用于 org.videolan.vlc）
    ├── VlcHooker.java        # hook Application.attach / LibVLC 构造器，字体同步与选项注入
    └── MemoryPatcher.java    # libvlc.so 内存补丁（native mprotect 优先，/proc/self/mem 回退）
app/src/main/jni/
└── subfontpatch.c            # 微型 JNI 补丁库：mprotect + memcpy 触发私有映射 COW 改写
```

`subfontpatch.c` 编译出的 .so 以**无压缩**形式存于模块 APK，由 LSPosed 注入的 `base.apk!/lib/<abi>` 搜索路径直接 `System.loadLibrary` 加载——Samsung 等厂商内核禁止应用写 `/proc/[pid]/mem`，此通道是这些设备上唯一可行的写法。

## 故障排查

- 模块未生效：确认 LSPosed 已启用模块并勾选 VLC 作用域，重启 VLC；`Toast` 与 LSPosed 日志会提示原因（`disabled` / `no-font` / `error: ...`）。
- 日志出现「模式不完整」：VLC 版本更新导致字符串变化，欢迎带着 libvlc.so 版本信息提 issue。
- ASS 字幕仍用旧字体：检查 ASS 内指定的字体名是否与所选字体内部名一致；缺字回退始终使用所选字体。
