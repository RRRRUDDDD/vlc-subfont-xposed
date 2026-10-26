# 本地网课播放器模块检查

范围：检查 vlc-subfont-xposed 当前工作区与相邻 vlc-android 源码。未修改应用代码；既有未提交改动保留。源码行为不代表目录中所有 APK 均已验证一致。

## 现有能力
模块提供字幕字体选择、SRT 字体替换、ASS 缺字回退、Provider/镜像传输、首页音频与播放列表入口精简，并将部分媒体库音频查询结果置空。没有课程管理、课程级倍速设置或字幕检索界面。
VLC 本地源码已有单媒体/全局倍速记忆、书签及重命名、AB 循环及端点持久化、续播、快进退时长设置和长按临时加速。因此优先复用已有播放能力，扩展课程组织与操作入口。

## 先处理的问题
1. 字体关闭时提前返回：VlcHooker.runSetup 在读取 pruneHome 之前检查 pc.enabled，导致 Provider 可用但字体未启用时 sPruneHome 保持默认 true。期望的独立开关可能无效。
2. 设置显示不一致：MainActivity.refreshUi 读取 prune_home，却未调用 mPruneHomeSwitch.setChecked(pruneHome)。布局默认 true，保存 false 后重开页面可能显示开启。
3. 首页精简同时隐藏 nav_playlists；对于本地课程编排，建议音频入口与播放列表入口分别控制，保留课程播放列表的访问能力。
4. 音频精简作用于 MedialibraryImpl 查询，影响范围超出导航显示；有 MP3/M4A 课程时应验证列表与搜索，建议将隐藏界面与禁用数据查询分开。
5. sPruneHome 默认 true，异步 runSetup 才赋值，而导航 hook 已安装，存在首次导航早于配置读取的时序风险，需要真机确认。LibVLC 构造前最多等待 20 秒，也应检查慢 Provider 时的启动耗时，不能仅凭静态检查断言 ANR。

## 功能优先级
| 优先级 | 功能 | 用户收益与实现边界 |
| --- | --- | --- |
| P1 | 学习快捷栏 | 常显回退 5/10 秒、前进、1/1.25/1.5/1.75/2 倍、书签、AB 入口；复用 VLC 控制，保持播放状态与原设置一致。长按加速已有实现，优先做入口和配置。 |
| P1 | 继续学习与课程目录 | 用户指定目录为课程；展示上次课节、时间点、已完成数与下一课；校验数字课号排序及跨目录策略。复用视频进度，另存课程级状态；手动完成/撤销，不把拖到结尾等同学会。 |
| P1 | 按课程记忆 | 同课程沿用倍速、字幕偏好；跨课节继承。VLC 已有全局/单媒体记忆，新增课程级优先顺序，避免覆盖单媒体选择。续播前可选回退 3–5 秒，并防止重复回退。 |
| P2 | 重点书签与导出 | 一键标记并命名，跨课节查看，导出 Markdown/JSON；书签底层与重命名已存在，新增课程聚合和便携导出。 |
| P2 | 可搜索字幕面板 | 先支持用户选取的外置 SRT，搜索并点句跳转/回听；ASS 排版、内嵌字幕及无字幕视频另行评估。转写或 OCR 不属于首版。 |
| P2 | 截图笔记 | 截图关联课程、课节、时间与备注，手动导出；优先确保截图成功和时间对应准确。 |

第一批建议：修正独立开关与回显，拆分播放列表精简，再做学习快捷栏和继续学习。随后扩展课程级倍速与书签导出。静音跳过需要额外音频分析，不建议排在课程导航、回退和重点标记之前。

## 代码依据
- 模块 VlcHooker.java：runSetup / onLibVlcInit / installAudioPruningHooks / hideAudioNavigation。
- 模块 MainActivity.java：onCreate / refreshUi / writeMediaConfig；FontProvider.java：effectiveEnabled / query。
- VLC PlaybackSpeedDialog.kt：changeSpeedTo；media/PlaylistManager.kt：saveMediaMeta / restoreSpeed / ABRepeat 元数据、播放速度恢复。
- VLC BookmarkListDelegate.kt / viewmodels/BookmarkModel.kt：书签增删、重命名与跳转。
- VLC VideoTouchDelegate.kt 与 PreferencesVideoControls.kt：临时加速、跳转秒数。

验证：逐项静态核查相关符号与控制流程；尚未安装测试 APK、真机操作或验证运行性能。新增功能为建议，不代表已经实现。官方网页仅辅助参考，本报告的确定性结论来自本地源码。

