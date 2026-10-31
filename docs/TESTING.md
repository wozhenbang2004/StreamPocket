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
