# Review

## Critical
无。

## Warning
- 当前工作区没有 ADB，无法检查已安装设备上的实际组件状态；仅完成源码和构建验证。
- 隐藏唯一桌面入口后，恢复图标需要从可进入模块设置的入口重新打开，或使用系统组件管理/ADB 启用 `com.vlcsubfont.module/.LauncherAlias`。构建中没有额外常驻恢复入口。

## Info
- Manifest 只有 `LauncherAlias` 持有 MAIN/LAUNCHER，MainActivity 作为目标 Activity 保留。
- 开关使用 `COMPONENT_ENABLED_STATE_DISABLED/ENABLED` 与 `DONT_KILL_APP`，隐藏后当前设置页不会被主动结束。
