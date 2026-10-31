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
