# Review

## Critical
无。

## Warning
- 两路外部审查代理在本轮未返回结果；本地静态检查和 Gradle 构建均通过。
- 配置兼容旧版 `prune_home`：新字段存在时使用独立设置；只有旧字段时，音频沿用旧值，播放列表也沿用旧值。

## Info
- `prune_audio` 控制音频导航和既有音频媒体库查询清空。
- `prune_playlists` 只控制播放列表导航，不影响播放列表数据。
- 新安装默认精简音频、保留播放列表；旧配置会按 `prune_home` 迁移。
