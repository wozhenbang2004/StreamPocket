# 开发检查

```bash
./gradlew testDebugUnitTest lintDebug
```

可按修改范围运行指定测试：

```bash
./gradlew testDebugUnitTest \
  --tests dev.streampocket.AppUpdatesTest \
  --tests dev.streampocket.UpdateActivityTest \
  --tests dev.streampocket.UpdateFlow030Test \
  --tests dev.streampocket.LibraryPlaybackUiTest
```

Robolectric 使用隔离文件、PackageManager / DownloadManager fixture 与原生 View 触摸分发，渲染输出在 `app/build/reports/view-fixtures/`。参考媒体来自 `test-media/`，保留署名，不写入生产库或 APK；测试参考帧不代表设备解码。`app/src/androidTest` 提供可选设备验收入口。

插件测试需要 Python 3.9+ 与对应 yt-dlp 版本，命令及固定来源见 [DOUYIN_PATCH](DOUYIN_PATCH.md)。

本地下载流程定点回归：

```bash
./gradlew testDebugUnitTest \
  --tests dev.streampocket.DownloadFlowTest \
  --tests dev.streampocket.BatchDownloadUiTest \
  --tests dev.streampocket.DownloadPauseTest \
  --tests dev.streampocket.DownloadQueueOrderTest \
  --tests dev.streampocket.Main022UiTest
```

`DownloadFlowSelfTest` 也可与 `CoreRules`、`SourceFailure`、`SourceScheduler`、`LinkBatch` 直接用 JDK 17 编译执行，无需 Android SDK。截图超时用例在 0.2.10 原规则上复现失败。0.2.10 的三项播放器断言仍针对早期合并标签，在未改产品代码的基线中复现；现断言分别核对精确毫秒时间、名称及现有点击父容器，保留原有标记数据检查。

0.2.12 的 `BatchDownloadUiTest` 覆盖解析 A 运行时追加 B/C/剪贴板、保留已有结果/失败身份/草稿、不自动下载、冷启动恢复结果与画质、取消状态恢复。`VideoFirstPlayerUiTest` 覆盖首帧封面立即隐藏、TextureView、主播放期间不自动准备第二路预览、无视频轨暂停报错。它们是离线原生 View 与播放器时钟 fixture，不代表真机硬件解码已通过。

0.2.13 的新增回归在旧代码上分别复现：不同版本复用同一安装content URI，以及启用动效且封面已缓存时视频库独有的共享转场参数。新增检查覆盖版本化安装地址、安装器从同一URI读取已校验字节、安装返回后仍旧版的反馈，以及开启动效下视频库网格/列表入口与下载页一致。窗口转场和OEM安装器的真机行为单独复测，不由离线夹具推定。


0.3.0 的组件检查覆盖 Tab 真实 DOWN/MOVE/UP/CANCEL、长选择列表与缩小可用高度时的底部操作、整行多选、字段错误恢复和更新说明格式。更新会话用可回读暂存内容的系统夹具验证完整字节、损坏拒绝、用户确认、后台等待、权限返回、取消和旧/重复回调；此夹具不执行手机安装。剪辑移除撤销保持片段 ID、选区和顺序，原库记录移除后仍可从草稿 URI 快照恢复。


0.3.1 增加来源多选的取消/应用/清除/页面恢复、实时链接识别不启动解析、画布按住临时2×及松手/CANCEL原倍速恢复检查。视频库宽/竖画面和播放器分组工具使用许可参考静态帧渲染；画面在最终布局与异步绑定之后设置，避免被色块fixture覆盖。已有点击、长按、滚动、面板拖拽、返回、横竖屏和共享播放入口回归保留。以上为隔离View/状态测试，手机手指操作、MediaCodec硬件播放及OEM安装需设备单独验证。
