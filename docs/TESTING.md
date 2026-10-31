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
