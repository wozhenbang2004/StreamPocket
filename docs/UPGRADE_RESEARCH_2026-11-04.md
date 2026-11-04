# 拾流升级研究与实施 · 2026-11-04

## 结论与范围

本轮从已发布的 **0.4.1 / versionCode 30**、主线 `ef623f55c1530e90a7ce0dbe3839a4f9ef289af8` 出发，比较本地提取、组件更新、后台传输、播放预加载与帧提取等路线。首批完成可靠性修改；用户追加要求更完整的功能迭代后，本报告继续记录 **0.5.0 / versionCode 31** 的下载管理、视频库、播放器和 Media3 稳定版迁移。保留现有 Java、yt-dlp、FFmpeg、aria2、Media3 组合，Android 长下载调度与端侧转写保留为后续独立验证路线。0.5.0 范围和采用依据见第 8 节。

本轮修改在独立升级分支中审查。版本号、发行 APK 和原签名未变；已有 `uiux/verify-041-20261102` 界面验证分支单独保留。实现与测试状态见本文末尾和 [开发检查](TESTING.md)，不能把开发分支当作已发布版本。

## 1. 当前基线：已有能力先核对

实际检查的是上述主线源码，而非只依据历史问题描述。

| 能力 | 主线现状 | 本轮判断 |
| --- | --- | --- |
| 提取与传输 | youtubedl-android 0.18.1；包内 yt-dlp 2026.10.26；FFmpeg、aria2；按来源调度；有界重试与冷却 | 保留成熟组件，集中改应用与内核之间的生命周期处理 |
| 账号隔离 | 官方 `GET_COOKIE_INFO`、请求专属 Cookie 快照、账号 revision、请求 scope；公开请求不证明账号成功 | 已实现，不重复包装成新功能 |
| 近期解析复用 | 5 分钟、16 项、8 MiB 上限；进程内 AES/GCM；403/410 等条件下有界刷新 | 保留；更新内核后应使旧解析缓存失效 |
| 下载队列 | 串行任务、每任务多连接、暂停/继续、批次持久化、前台服务超时回调 | 真正缺口是停止动作仍会同步等待底层进程 |
| 文件保存 | 可选目录、已保存文件迁移、内容校验与引用更新 | 本轮不另造保存系统，也不把 content URI 当文件路径 |
| 播放与预览 | 首帧保护、生命周期释放、异步预览与资源限制、双视频独立状态 | 定位到显式重试未恢复播放意图；不由此推断所有黑屏的根因 |
| 工具链 | Java 17、AGP 8.7.3、Gradle 8.9、compileSdk/targetSdk 35、minSdk 24、Media3 1.9.4 | Media3 新稳定版需连带迁移编译工具链 |

对应实现入口：[Engine](../app/src/main/java/dev/streampocket/Engine.java)、[DownloadService](../app/src/main/java/dev/streampocket/DownloadService.java)、[TaskStore](../app/src/main/java/dev/streampocket/TaskStore.java)、[PlayerActivity](../app/src/main/java/dev/streampocket/PlayerActivity.java)、[依赖声明](../app/build.gradle)。完整运行依赖许可证记录仍沿用 [RUNTIME_DEPENDENCIES](RUNTIME_DEPENDENCIES.md)；本轮不增加第三方依赖。

## 2. 提取技术：把可更新性做可靠

### yt-dlp 与独立运行组件

截至本轮核对，yt-dlp stable 为 **2026.10.26**，拾流已经包含此版本。该发布提高了最低 Python 要求至 3.11；YouTube 的完整支持还涉及 EJS 与外部 JavaScript 运行时。因此，“只替换一个 Python 脚本”不能代表整个提取环境兼容。官方 zipapp 与其他发行物携带依赖的方式也有区别。[发布说明](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26) · [EJS 官方说明](https://github.com/yt-dlp/yt-dlp/wiki/EJS)

项目已有 QuickJS 与 EJS 接入记录，wrapper 版本号也不等于其嵌入 Python 的版本。本轮保留既有原生组件，没有重新执行 Android 真机上的 Python/QuickJS 能力验收。后续运行时升级必须同时核对 Python、JS runtime、提取器版本、ABI 和对应源码，不能只看 README 的旧介绍。

YTDLnis 的独立组件包值得参考：主应用与 Python、JS、FFmpeg 等运行组件分开演进，近期发布还涉及本地 PO Token 生成。这能缩短网站变化后的修复周期，但增加多组件版本配对、ABI、存储、回滚和供应链维护成本。拾流现阶段先实现可验证、可恢复的 yt-dlp 更新；只有出现可复现的运行时不兼容或 YouTube 样本失败，再引入所需组件。[YTDLnis 发布](https://github.com/deniscerri/ytdlnis/releases) · [组件仓库](https://github.com/deniscerri/ytdlnis-packages) · [组件发布](https://github.com/deniscerri/ytdlnis-packages/releases)

### 可行替代与适用条件

| 路线 | 能解决的问题 | 拾流的采用判断 |
| --- | --- | --- |
| 现有 yt-dlp + 原生 wrapper | 多平台提取、分轨选择、下载和处理复用 | **继续采用并适配**。当前收益来自更可靠的更新与停止边界 |
| YTDLnis 组件拆分机制 | 运行时独立升级、按组件修复兼容问题 | **参考**。先建立校验和回滚，再考虑多组件版本管理；不整套搬入 UI 或架构 |
| NewPipeExtractor | Java 侧平台提取，减小特定平台对 Python 的依赖 | **候选**。平台覆盖与能力模型不同；只有 YouTube 实测表现足以抵消双提供方维护成本时重开 |
| cobalt 自托管 API | 统一服务端处理与解析接口 | **当前不采用**。官方说明没有可直接依赖的公共托管 API；自托管增加服务运营与内容传递，与当前本机处理定位不同 |
| 全面引入另一套浏览器/JS/PO Token 栈 | 某些平台动态挑战与运行时兼容 | **条件评估**。需先有具体错误样本、合法访问条件及可重复实验；不把 token 或 Cookie 当作万能下载开关 |

阅读入口：[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)、[cobalt API](https://github.com/imputnet/cobalt/blob/main/api/README.md)、[cobalt 自托管](https://github.com/imputnet/cobalt/blob/main/docs/run-an-instance.md)。这些项目的存在与设计可用于选型，不等于已经接入拾流或在拾流设备上测得收益；涉及复制实现或新增分发物时再逐项核对其具体许可证及对应源码义务。

### 本轮实际修复

**X 缺格式分类。** yt-dlp 的 `No video could be found in this tweet` 原本被归类为通用不支持，导致已登录 X 的现成公开 syndication 恢复分支无法触发。现在仅把这条精确措辞归到 `NO_FORMATS`。普通 unsupported URL、所选格式不可用仍保持原分类；401、403、429、验证要求等明确错误优先。恢复路径仍由既有 Engine 约束，并且公开请求不携带账号 Cookie，不回写账号状态。它不承诺所有 X 视频都能解析，也不把缺格式归为可立即重试的网络故障。

**核心更新事务。** 原更新入口把网络下载包含在运行时写锁内，并直接依赖上游替换行为。新的流程将官方元数据读取、下载与校验放在写锁之外，串行处理更新请求；只在切换、原生 `--version` 健康检查与提交期间取得写锁。候选必须匹配官方仓库资产路径、发布版本、声明大小和 SHA-256，且确实是包含匹配版本的 zipapp。保留旧核心与旧版本元数据，健康检查或提交失败时回滚，初始化时恢复未提交事务。摘要验证针对官方 HTTPS 发布元数据，不宣称增加了独立发布者签名验证。

## 3. 下载与后台：先保证可停止，再改变调度器

### Android 的当前约束

Android 15 对面向 API 35 及以上的 `dataSync` / `mediaProcessing` 前台服务实施后台累计时限；收到 `onTimeout` 后，应用只有很短的退出窗口。拾流已经实现回调，本轮发现的问题是回调以及用户暂停/取消仍可能同步等待 native destroy。[前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)

Android 14 引入的 **User-Initiated Data Transfer Job（UIDT）** 更贴近用户主动发起、需要通知与进度的长传输。Android 16 下，长时间运行的 WorkManager 即使创建前台服务，也可能耗尽 job quota。因此，“统一换成 WorkManager”没有消除这个约束。UIDT 同样要求合法调度入口、持久化进度和停止恢复；用户从系统任务管理器停止时，进程可能直接结束，不能只靠结束回调保存记录。[UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt) · [长 Worker 与 Android 16 配额](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

推荐的后续设计是在稳定任务身份和现有下载执行器上增加 API 34+ UIDT 调度适配，低版本继续使用适当的前台服务；先验证手动入队、连续批次、系统停止、进程重建与断点恢复，再切换默认值。它是调度层变化，不能顺带重写提取器和文件保存。

### 本轮实际修复

暂停、取消和服务停止先保存状态，再异步发送底层停止信号。终止操作不在主线程或服务锁内等待。每次执行获得独立 process ID；同一任务的临时目录仍使用稳定 task ID，保留既有断点。延迟停止仅针对原执行，不会误杀一次新的重试。

任务进入“正在停止”后保持不可重试，直到执行和请求清理实际返回才转为暂停、取消或中断。发送信号成功、信号超时和进程已退出是不同事实。系统退出路径先回应停止要求；不以 `shutdownNow()` 打断 wrapper 等待来伪造原生任务已退出。媒体地址刷新并重新取得运行时锁后，也重新核对停止意图，避免已点暂停的任务晚启动。来源调度的迟到状态回调与停止操作使用同一服务锁，避免覆盖已经持久化的暂停状态。

### 传输引擎与存储选择

| 方案 | 研究结论 | 决定与验证条件 |
| --- | --- | --- |
| aria2 多连接 | 当前原生传输链已使用；任务并发与连接并发是两个维度 | 保留按来源策略与 native 断点；连接数增加不直接等于速度提升 |
| Media3 DownloadManager | 面向离线播放及缓存管理，包含 DASH/HLS 等下载能力 | 不直接替换“生成可导出的独立媒体文件”链路；缓存与文件交付的目标不同 |
| HttpEngine / Cronet / QUIC | Java/Media3 HTTP 路径可用的网络栈选择 | 当前大部分下载运行在 Python/aria2，添加 Java 网络库不会自动加速它们；有相应瓶颈后再测 |
| 自行实现 Range 续传 | 必须正确处理校验标识、206 Content-Range、200 全量响应及文件变化 | 现有引擎拥有续传职责；不为了参数控制增加另一套未验证下载器 |
| SAF / MediaStore | 适合用户选目录与共享媒体；URI 授权和提供方行为仍需处理 | 保留现有校验、迁移日志和引用更新；失效权限明确反馈，不静默写到另一个目录 |

参考：[Media3 下载](https://developer.android.com/media/media3/exoplayer/downloading-media)、[Media3 网络栈](https://developer.android.com/media/media3/exoplayer/network-stacks)、[HTTP Range 与 If-Range](https://www.rfc-editor.org/rfc/rfc9110.html)、[SAF 文档访问](https://developer.android.com/training/data-storage/shared/documents-files)。这些是选择与验收依据，不是本轮传输速度测量结果。

## 4. 播放：稳定版、预加载与帧提取

### 稳定版迁移的真实成本

核对时 Media3 稳定版为 **1.11.1（2026-09-10）**，预览线为 **1.12.0-alpha02（2026-10-26）**。稳定发布中包含播放器渲染与表面切换等修复，但不能从上游 changelog 推导拾流的某个手机问题必然已解决。[Media3 发布记录](https://developer.android.com/jetpack/androidx/releases/media3)

直接读取官方 1.11.1 tag 后确认：`compileSdkVersion=36`，Android library 构建规则把该值写入 `aarMetadata.minCompileSdk`。当前 compileSdk 35 不能仅修改依赖版本。官方工具兼容表规定 API 36 最低受支持 AGP 为 8.9.1；AGP 8.9 系列需要 Gradle 8.11.1。这里依据发布 tag 的构建配置与官方兼容表，未独立下载二进制 AAR 重验元数据。[版本配置](https://github.com/androidx/media/blob/1.11.1/gradle/libs.versions.toml) · [AAR 配置](https://github.com/androidx/media/blob/1.11.1/build-logic/src/main/kotlin/media3.android-library.gradle.kts) · [工具兼容表](https://developer.android.com/build/releases/about-agp)

已对照 1.11.1 的 Transformer / Codec 源码，本项目使用的编码工厂、导出监听、`start(Composition, String)` 和两个实验性裁剪开关仍存在。Java 可以继续使用这些能力，不需要为播放器升级重写 Kotlin/Compose。全工程编译、音视频导出和真实播放行为仍需验证。

**首批可靠性修改保留了 1.9.4；用户追加更完整功能升级后，0.5.0 批次正式迁移到 1.11.1。** 配套选择 compileSdk 36、AGP 8.13.2、Gradle 8.14.6、Java 17 和 Build Tools 35.0.0。AGP 8.13 官方页明确支持 API 36.1、最低 Gradle 8.13，避免只依据最低版本表推断旧插件支持范围；Gradle 分发的 SHA-256 同步固定在启动脚本和 wrapper 配置。targetSdk 维持 35，最低版本维持 24。全量构建与既有播放/导出测试属于本次集成门槛；设备硬件播放、耗电和非关键帧导出质量仍需对应设备证据。[AGP 8.13 兼容范围](https://developer.android.com/build/releases/agp-8-13-0-release-notes) · [Gradle 官方摘要](https://gradle.org/release-checksums/)

### 哪些新能力值得采用

**有边界的相邻预加载。** `DefaultPreloadManager` 可以依据当前播放位置管理附近媒体，并复用适配的 ExoPlayer 构建配置。拾流的上下滑动切换适合先试“当前项加少量邻居”，以首帧延迟、内存、解码器资源和电量比较收益；不要为每个列表项常驻一个播放器。1.11 的 PlayerPool 也是候选，但不是必须先把 Java UI 迁移到 Compose。[预加载指南](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager)

**统一帧提取。** Inspector 提供异步元数据与帧提取能力，适合进一步统一封面、标记缩略图与拖动预览，支持明确的输出大小和取帧策略。1.10 起帧提取已拆分为 `androidx.media3:media3-inspector-frame`，类为 `androidx.media3.inspector.frame.FrameExtractor`。接入时要保留任务取消、代际隔离、缓存上限和资源释放，不只替换一个方法名。[Inspector](https://developer.android.com/media/media3/inspector) · [1.11.1 release notes](https://github.com/androidx/media/blob/1.11.1/RELEASENOTES.md)

**解码失败诊断与备选播放器。** 现有首帧检查只能发现未显示画面的状态，不能自动判断是源文件、MediaCodec、Surface 还是生命周期问题。下一轮用可复现媒体及设备记录区分故障。libVLC 可作为明确失败样本的备选引擎评估；mpv-android 本身是应用工程，接入 libmpv 还涉及 JNI、原生构建及许可证。两套引擎都会增加安装体积和维护面，不能只因“格式支持多”就默认装上。[mpv-android](https://github.com/mpv-android/mpv-android)

### 本轮实际修复

首帧超时保护会暂停播放并把 `shouldPlay` 设为 false；旧“重试”只重新加载，新的播放器仍按暂停意图初始化。现在仅在用户点击“重试”时恢复播放意图，再执行原有加载过程；保留时间位置与持久化进度。普通页面返回或 Activity 重建仍尊重用户原有暂停状态。

## 5. 社区证据的用途与边界

本轮沿 GitHub 项目、Issues、官方文档及社区入口扩展检索。LinuxDo 上的 [Seal 登录后仍读取不到 Cookie 的讨论](https://linux.do/t/topic/363940) 是 2025 年的用户观察，提醒我们区分“网页登录成功”“Cookie 可取得”和“后续请求确有权限”。它不能证明 2026 年拾流存在相同根因，更不能代替官方 WebKit 接口和实际源码检查。

X、知乎与小黑盒相关检索未取得足以支撑本轮技术决策的完整一手证据；不把搜索摘要写成已读帖子或实测结论。项目现有小黑盒平台支持是代码能力，与是否取得该社区的技术讨论证据是两件事。

## 6. 后续切片与改变决定的条件

| 优先级 | 用户收益与切片 | 进入实施前的必要证据 | 验收重点 |
| --- | --- | --- | --- |
| P0 · 本轮 | X 错误恢复、显式播放重试、更新回滚、可靠停止 | 已定位调用链、针对性回归 | 限流/权限不被绕过；暂停数据保留；旧任务不影响新任务；更新失败仍可使用旧核心 |
| P1 · 已进入 0.5.0 实施 | Media3 stable + 编译工具链迁移 | SDK/AGP/Gradle 配套；现有导出 API 检查 | 全量构建；代表性设备单路/双路播放、seek 与精确裁剪 |
| P1 | 相邻视频预加载 | 稳定版基线通过，设备资源预算明确 | 首帧 p50/p95、峰值内存、切换取消、后台释放；记录原始样本，不预设提升百分比 |
| P1 | API 34+ UIDT 调度适配 | 任务恢复与执行所有权可验证 | 入队与通知、系统停止、进程重建、断点恢复；低版本行为不退化 |
| P2 | Inspector 帧提取统一 | 固定媒体集和现有耗时/资源记录 | 输出时间点和尺寸、过期结果隔离、取消与释放、前台播放不受抢占 |
| 按证据触发 | 独立 Python/JS 组件或本地 PO Token | 特定公开可访问样本因当前运行时失败，备选可重复成功 | 版本矩阵、ABI、体积、回滚、隐私与许可证；真实源站验证 |
| 按证据触发 | libVLC/libmpv 或新 HTTP 栈 | 已分离的解码/网络瓶颈，候选在相同样本有优势 | 用户收益超过体积、耗电、兼容与维护成本；可撤回接入 |

研究保持可重开：新版本、真实设备失败样本或源站行为变化可能改变选择。当前没有后台自动跟踪或未声明的持续运行任务。

## 7. 本轮验证记录

截至集成前：X 分类两套纯 Java 自检已执行并通过；新增缺格式用例先在旧实现上失败，再在修复实现上通过。旧 `CoreRulesSelfTest` 中一项文案断言在未改基线也失败，已改为核对来源、重新解析与保留链接三项实际用户语义。

集成后的核心更新自检 83 项、停止器自检 5 项均在 JDK 17 以 exit 0 完成；两个独立 JVM 的强制终止恢复包含在前者内。播放器新增回归走实际重试按钮与保存/恢复状态，使用项目已有、明确标注的播放器时钟 fixture。具体命令、CI 必需步骤与设备验证边界见 [TESTING](TESTING.md)。

本次执行环境没有 Android SDK、模拟器或连接的手机。本地 Java 检查不能替代 Android 编译、Robolectric、硬件解码、源站真实下载、原证书覆盖安装及耗电测量；这些结果必须分别记录，不能互相推定。

## 8. 0.5.0 功能迭代合同 · 2026-11-04

用户指出上一批可见变化偏少，要求更大、更彻底的优化和功能迭代。审阅实际源码与当前 View 截图后确认：批量解析、来源/标题搜索、合集、双/三列、进度徽标、分屏和轻剪辑已经存在，不把它们重新计为本轮新增。目标改为完整打通“管理下载 → 找回要看的内容 → 连续观看”三段重复使用流程。

| 模块 | 本次新增结果 | 数据与交互约束 |
| --- | --- | --- |
| 下载管理 | 全队列暂停/继续、下一项/移至队尾、多选暂停/继续/失败重试与记录移除 | 持久暂停开关；整队继续不恢复用户手动暂停项；新入队也遵守开关；顺序同时更新持久记录和服务队列；旧执行退出后才能重启 |
| 视频库 | 继续观看/未看/已看筛选、独立收藏、合集改名/删除和批量移出 | schema v1 → v2 保留原数据；组合筛选与分页/相邻导航一致；合集操作不删除视频文件；日期/来源排序继续用稳定锚点 |
| 单播放器 | 显式画中画、单次/单个循环/连续播放、已有音轨与内嵌字幕选择 | PiP 隐藏完整工具层并保持可见播放；普通后台释放；连续播放跟随当前筛选，末项停止；可用轨道以实际媒体为准 |
| 播放基础 | Media3 1.11.1 与配套构建迁移 | 复用既有 Java/Views/Media3，不引入另一套解码器；覆盖既有分屏、进度、标记、剪辑与错误恢复回归 |

设计继承 `MEDIA_EXPERIENCE.md` 的封面优先、低噪音和分组工具规则，以及 `DOWNLOAD_FLOW.md` 的结果优先、对象身份和具体恢复动作。下载页提供一个整队主控制与按需多选；库筛选保持紧凑并与原搜索/来源/合集组合；播放器能力放进既有分组面板。新增按钮保留足够点击范围，长标题与小屏面板不得遮住确认/返回。当前参考来自上一批实际 CI 的原生 View 夹具，新的渲染和交互检查在本批完成后更新，旧图不作为新版验收。

复用决策：采用 Android 系统 [画中画](https://developer.android.com/develop/ui/views/picture-in-picture) 与 Media3 [轨道选择](https://developer.android.com/media/media3/exoplayer/track-selection)，适配现有 Pocket 组件、SQLite 库及单所有者下载服务。PiP 的 `onPause` 不等于不可见，检查点、首帧保护和异步切片必须统一处理；不能只增加 manifest 属性。观看状态从已有时间位置和时长推导，收藏单独持久化；不为同一视频另建重复资料库。

本批不包含云端 AI、外部字幕文件导入、UIDT 调度重写、相邻解码预加载或第二播放引擎。它们涉及独立的数据/资源策略，保留在有明确输入与可测收益的后续切片。当前验收是功能入口可操作、状态重建正确、旧数据保留、停止和播放意图正确、全量 Android 测试/Lint/构建通过，以及本轮代表界面渲染检查；真实设备 PiP/音轨解码、源站成功率和性能提升不能用离线夹具推定。

### 更大功能迭代的下一条研究线：把视频变成可检索、可编辑的内容

0.5.0 补齐日常操作后，最值得继续验证的产品方向是“本地转写 → 点击句子定位画面 → 按句子选段剪辑 → 搜索说过的话”。它复用现有毫秒标记、片段项目与导出流程；转写结果需要绑定稳定视频 ID、时间区间、模型版本和用户修订，不应只生成一段无法回到视频的摘要。这是候选产品路线，本批未安装模型、运行转写或声称已实现。

| 候选 | 已读一手证据与价值 | 在拾流中的判断与下一验证 |
| --- | --- | --- |
| sherpa-onnx + SenseVoice / Whisper | [官方仓库](https://github.com/k2-fsa/sherpa-onnx)、[Android 本地 APK](https://k2-fsa.github.io/sherpa/onnx/android/prebuilt-apk.html)、[SenseVoice 文档](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/sense-voice/pretrained.rst) 提供离线 Android、VAD 与多语种识别路线 | 优先比较中文/夹杂英文的视频；核对时间戳能力、模型授权、内存、耗时和取消。Android 示例可作为实现来源，项目名或 APK 存在不能证明本机精度与性能 |
| whisper.cpp | [官方 Android 示例](https://github.com/ggml-org/whisper.cpp/blob/master/examples/whisper.android/README.md) 建议从 tiny/base 试起；[README](https://github.com/ggml-org/whisper.cpp) 提供模型磁盘/内存表和量化能力 | 作为同一媒体集的备选；模型按需下载并可删除，长音频分段/时间对齐需真实验证，不把桌面结果直接当手机结果 |
| LiteRT-LM | [Google 旧 API 页面](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android) 已将 MediaPipe LLM Inference 标为维护模式，建议迁移到 [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)；[Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md) 有 Android/JVM 与多模态接入说明 | 后续用于章节、内容问答/摘要候选，先做基于转写的可回溯结果。需固定版本、确认 Kotlin/原生组件/设备能力兼容；不照抄示例的 `latest.release`，也不默认给 Android 7 的所有设备加载大模型 |

本次增量改变了一个选型判断：如果做端侧内容理解，不能直接沿用旧 MediaPipe LLM Inference 教程，应先核对 LiteRT-LM 的当前维护方向和设备约束。专业语音识别与通用多模态模型承担不同职责；先比较带时间定位的转写质量，再决定是否值得增加摘要模型。语义检索、章节摘要和按句剪辑均为待验证能力，不计入 0.5.0 已交付功能。
