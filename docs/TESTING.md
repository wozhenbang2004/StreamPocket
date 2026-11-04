# 开发检查

## 0.5.0 功能批次 · 集成验证入口

本批升级 Media3 1.11.1、compileSdk 36、AGP 8.13.2 与 Gradle 8.14.6，保留 targetSdk 35 / minSdk 24。下载整队控制、批量/优先级、库筛选/收藏/合集、画中画/播放模式/轨道选择的新增回归与全量检查一起执行，最终结果以同一提交的 CI 为准。

除原有单元、View、Lint 与 ARM64 构建外，CI 新增隔离的 Android 15 / x86_64 模拟器任务。它复用 `UiAcceptanceInstrumentation`、许可参考媒体和 `tools/qa-x86.init.gradle`，用真实 Android Media3 解码与系统窗口验证横/竖视频首帧、拖动取帧、返回视频库，以及从真实菜单进入 PiP 后播放位置继续前进、回全屏和退出释放。构建与执行入口为：

```bash
gradle --no-daemon --console=plain -I tools/qa-x86.init.gradle assembleDebug assembleDebugAndroidTest
bash tools/run-emulator-acceptance.sh
```

执行脚本只面向 CI 创建的 `emulator-${EMULATOR_PORT:-5554}`，失败时也收集日志和截图。模拟器包不会上传为安装/发行包。`StreamPocket-emulator-playback` artifact 保存该运行的原生窗口截图、Instrumentation 输出和 logcat；观察到的模拟器解码仍不代表 ARM64 手机硬件解码、OEM PiP、耗电或源站下载。该入口新增时尚未运行，不以脚本存在声称验收通过。

## 2026-11-04 · 更新、停止与错误恢复

本批基于 0.4.1 主线 `ef623f55c1530e90a7ce0dbe3839a4f9ef289af8`。方案和采用边界见 [升级研究](UPGRADE_RESEARCH_2026-11-04.md)。以下是本地集成的实际结果；Android CI 的最终结果以 PR 对应提交的 Actions 记录为准，不能沿用历史版本的测试数量。

| 本地检查（JDK 17） | 结果与证明范围 |
| --- | --- |
| `CoreUpdateSelfTest` | 83 项检查通过，exit 0；故障注入 HTTP、大小/摘要/zipapp 版本、健康失败和元数据失败回滚、两次真实子进程突然终止后恢复、有界版本探测 |
| `DownloadProcessStopperSelfTest` | 5 组检查通过，exit 0；阻塞终止不占调用线程、注册窗口重试同一执行、旧执行的延迟信号隔离、超时不代表执行完成、终止线程与等待队列有界 |
| `CoreRulesSelfTest` | exit 0；链接、日志脱敏及用户恢复提示 |
| `DownloadFlowSelfTest` | exit 0；错误优先级、X 精确缺格式措辞、批次去重、冷却与可取消调度 |
| `git diff --check` | 通过 |

独立编译两个新辅助类和自检时 `-Xlint:all` 无告警。连同既有链接/调度自检编译时有三项原有 lint 告警（异常类缺少 `serialVersionUID`，两处 try-with-resources 变量未显式引用），无编译错误；本批未为清理这些告警修改无关行为。

X 新增用例先在旧分类上复现失败，再在修复后通过。旧 `CoreRulesSelfTest` 的“当前访问状态”文案断言在未改基线也失败；现改为同时核对 X 来源、重新解析建议与失败链接保留，没有删掉恢复要求。

新增 Android 回归包括：`VideoFirstPlayerUiTest` 的首帧超时后点击真实 Retry、时间位置与播放意图持久化、普通暂停恢复；`DownloadPauseTest` 的阻塞终止与系统超时、活动任务不可提前重试、等待项转中断、暂停意图保留，以及活动 ID 尚未清理时超时或迟到的取消不能重开已结束任务。它们依赖 Android/Robolectric，未在缺少 Android SDK 的本地执行环境中运行。`DownloadProcessStopperTest` 将五组独立停止器检查接入 JUnit。

独立源码审查另封住两处停止窗口：媒体地址刷新等待运行时锁后，每次 `execute` 前重新检查取消；来源调度回调的停止检查与状态写入使用同一服务锁，防止迟到反馈覆盖暂停记录。此处证据是调用链与锁顺序审查，未将其表述成真实源站并发实验。

CI 新增以下独立步骤，确保进程终止恢复检查在普通 JVM classpath 上执行：

```bash
mkdir -p build/reliability-checks
javac -Xlint:all -d build/reliability-checks \
  app/src/main/java/dev/streampocket/CoreUpdate.java \
  app/src/main/java/dev/streampocket/DownloadProcessStopper.java \
  app/src/test/java/dev/streampocket/CoreUpdateSelfTest.java \
  app/src/test/java/dev/streampocket/DownloadProcessStopperSelfTest.java
java -cp build/reliability-checks dev.streampocket.CoreUpdateSelfTest
java -cp build/reliability-checks dev.streampocket.DownloadProcessStopperSelfTest
```

原有 CI 的 `testDebugUnitTest lintDebug assembleDebug assembleRelease` 与并行安装预览包构建继续保留。测试中模拟的 HTTP、播放器时钟和被阻塞的停止器不代表真实平台下载、Android 原生进程树退出、硬件解码、设备断电恢复、耗电或原证书覆盖安装已通过。新核心 `--version` 检查只验证启动和版本，不代替 EJS/各平台能力检查。

## 常用检查与历史证据

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

`DownloadFlowSelfTest` 也可与 `CoreRules`、`SourceFailure`、`SourceScheduler`、`LinkBatch`、`YouTubeLinks` 直接用 JDK 17 编译执行，无需 Android SDK。截图超时用例在 0.2.10 原规则上复现失败。0.2.10 的三项播放器断言仍针对早期合并标签，在未改产品代码的基线中复现；现断言分别核对精确毫秒时间、名称及现有点击父容器，保留原有标记数据检查。

0.2.12 的 `BatchDownloadUiTest` 覆盖解析 A 运行时追加 B/C/剪贴板、保留已有结果/失败身份/草稿、不自动下载、冷启动恢复结果与画质、取消状态恢复。`VideoFirstPlayerUiTest` 覆盖首帧封面立即隐藏、TextureView、主播放期间不自动准备第二路预览、无视频轨暂停报错。它们是离线原生 View 与播放器时钟 fixture，不代表真机硬件解码已通过。

0.2.13 的新增回归在旧代码上分别复现：不同版本复用同一安装content URI，以及启用动效且封面已缓存时视频库独有的共享转场参数。新增检查覆盖版本化安装地址、安装器从同一URI读取已校验字节、安装返回后仍旧版的反馈，以及开启动效下视频库网格/列表入口与下载页一致。窗口转场和OEM安装器的真机行为单独复测，不由离线夹具推定。


0.3.0 的组件检查覆盖 Tab 真实 DOWN/MOVE/UP/CANCEL、长选择列表与缩小可用高度时的底部操作、整行多选、字段错误恢复和更新说明格式。更新会话用可回读暂存内容的系统夹具验证完整字节、损坏拒绝、用户确认、后台等待、权限返回、取消和旧/重复回调；此夹具不执行手机安装。剪辑移除撤销保持片段 ID、选区和顺序，原库记录移除后仍可从草稿 URI 快照恢复。


0.3.1 增加来源多选的取消/应用/清除/页面恢复、实时链接识别不启动解析、画布按住临时2×及松手/CANCEL原倍速恢复检查。视频库宽/竖画面和播放器分组工具使用许可参考静态帧渲染；画面在最终布局与异步绑定之后设置，避免被色块fixture覆盖。已有点击、长按、滚动、面板拖拽、返回、横竖屏和共享播放入口回归保留。以上为隔离View/状态测试，手机手指操作、MediaCodec硬件播放及OEM安装需设备单独验证。

2026-11-01 链接入口体验修订：`testDebugUnitTest lintDebug assembleDebug` 全部 exit 0，249 项测试通过，Lint 无 Error/Fatal。`BatchDownloadUiTest` 的 15 项检查覆盖原有队列行为，以及剪贴板预览、多选/全选/清空、选空禁用、返回不解析、已处理不重复、未处理回前台恢复、手动粘贴保留草稿、多条剪贴板、无效输入、结果收起和 320dp 小屏深色面板。新增的粘贴保留草稿、无效输入禁用两项检查，在 `849a8c8` 原 MainActivity 上均先复现失败，再在当前实现通过。

实际查看了当前原生 View 渲染：[剪贴板选择](design/assets/link-intake-20261101/clipboard-review.png)、[小屏深色与 100 条链接](design/assets/link-intake-20261101/clipboard-small-dark.png)、[结果优先与继续添加](design/assets/link-intake-20261101/ready.png)。它们使用明确的离线样例，灰色封面为未提供缩略图的占位状态；不代表源站联调、真机输入法/手势或硬件解码通过。本轮构建为开发验证包，未发布新的正式安装更新。

同日视频浏览与错误恢复批次：`testDebugUnitTest lintDebug assembleDebug` exit 0，258 项测试、0 失败/错误/跳过，Lint 无 Error/Fatal。在原 `c8b5a8f` 的 VideoLibraryView / PlayerActivity 上，三列选择和下滑切换两条新增旅程均先复现未满足；当前实现通过。定点检查另发现 Drawable 设置覆盖图案内边距，已通过实际 View padding 与命中尺寸断言修正。

当前渲染证据：[三列与缩小图标](design/assets/media-experience-20261101/three-columns.png)、[保留的失败记录与重试](design/assets/media-experience-20261101/failed-link.png)、[播放器控制层](design/assets/media-experience-20261101/player-controls.png)。封面色块和播放器时钟均为明确的离线夹具，不冒充真实视频解码。下滑测试执行真实触摸分发并核对数据库中的上一条进度、下一条标记、跨分页/筛选顺序、取消、后台与重建；手机手指操作、硬件解码、真实源站和正式签名覆盖安装仍未验证。需求与交互边界见 [MEDIA_EXPERIENCE](MEDIA_EXPERIENCE.md)。

0.3.2 发行构建：版本递增至 22 后，`testDebugUnitTest lintDebug assembleRelease` exit 0（258 项、0 失败/错误/跳过，Lint 无 Error/Fatal）。成品为非 debuggable、`dev.streampocket.localtest`、minSdk 24、ARM64；zipalign 16KB 检查和 APK v2/v3 签名验证通过，证书 SHA-256 与 0.3.1 更新清单一致。固定名与带版本号的 APK 字节相同，清单使用签名后成品的大小和 SHA-256。成品身份核验不代替设备覆盖安装；本轮未操作用户手机。

0.3.3：在 3fbc5a3 原 PocketEditText / PlayerActivity 上，两项新增回归先复现失败：触摸聚焦/IME 输入连接、控制层可见时画面偏右位置下滑下一条。修复后整套 262 项测试、Lint、Debug 和 Release 构建通过；独立审查修正手指抬起连续性、缩放后单击、音频焦点、重播、X 媒体序号与分屏返回进度后，再对 Player022UiTest / SplitPlayerUiTest 的 15 项重点用例复验。

重点用例使用真实 MotionEvent / View 分发（两指、多指抬起、放大后点按与拖动、短滑和取消）、IME commitText、原数据库/稳定排序、两路独立播放器时钟、音频焦点瞬失/duck、结束重播、Activity result 发送与接收。接收测试明确让数据库保留旧 checkpoint，再验证精确 result 位置获胜；不是已完成手机端双IO压力测试。

实际查看当前 412dp 原生布局：[上下分屏](design/assets/player-033/split-portrait.jpg)、[左右并列](design/assets/player-033/split-side-by-side.jpg)。图中是获许可静态参考帧，明确标“布局夹具”，不是双路硬件解码证据。真机手指操作、设备双解码上限、截图中 X 链接与代理环境、OEM 覆盖安装未执行；安装包的签名和身份核验单独记录于 RELEASE_STATUS。


0.3.4：新增4项 URL/元数据回归在475bd21上先复现失败；修复后全套274项测试、0失败/错误/跳过，`testDebugUnitTest lintDebug assembleRelease` exit0。新增YouTube短链/Shorts/别名去重、单视频与无列表扩张、画质/分轨选择、错误优先级、失败重建和重试、视频库来源持久化；原生View实际点击1080p与下载，核对任务URL和selector。独立审查指出并修复JavaScript警告遮住429等真实错误的问题，混合日志回归通过。YouTube真实网络与手机设备未验证。
