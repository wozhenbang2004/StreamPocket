# 拾流 · StreamPocket

Android 本地视频下载与媒体工作区：粘贴链接、选择可用画质、下载到手机，再播放、整理、标记和轻剪辑。

Local-first Android video downloader and media workspace. Parsing, downloading and media processing run on the device; no hosted resolver or application account is required.

> **当前源码：0.2.7-test / versionCode 13。** 这是早期测试项目。自有源码使用 GPL-3.0-or-later。**暂不提供公开 APK**：预编译原生依赖的对应源码与许可证材料尚未核对完整，见 [发行状态](docs/RELEASE_STATUS.md)。源码公开不等于二进制发行审计完成。

## 功能

- 链接与分享文本解析、源站实际可用画质、多视频逐项选择；使用 yt-dlp，抖音为[实验插件](docs/DOUYIN_PATCH.md)。
- 本地 FIFO 下载队列、前台通知、暂停/继续、取消和同任务重试；1 / 4 / 8 路配置，实际连接数取决于协议与源站。
- 区分视频、音频、合并、保存阶段；未知或估算大小明确标注，文件保存成功才显示完成。
- 视频库：封面、搜索、来源/合集、网格/列表、多选分享与实际文件重命名；移除库记录不等于删除原文件。
- Media3 播放器：完整画幅 FIT、横屏、倍速、上次位置、拖动缩略图、时间点与片段标记。
- 轻剪辑：双端时间轴、精确范围、持久草稿、多片段排序和后台导出；成片另存，原片保留。
- 本机账号会话：平台官方网页登录，分平台启用/暂停/清除；AndroidKeyStore AES/GCM 加密保存，任务使用临时快照，不读取其他应用 Cookie。
- 跟随系统/浅色/深色、关闭动效、播放偏好、解析器更新与诊断。
- [应用内更新](docs/APP_UPDATES.md)：前台检查 GitHub 稳定 Release、提示后下载、校验并交给系统确认安装。当前没有可用公开 APK Release，尚未验证端到端在线升级；不是系统推送或静默安装。

## 兼容性与限制

| 项目 | 范围 |
| --- | --- |
| 系统 | Android 7.0+，minSdk 24；compile/target SDK 35 |
| ABI | 正常构建仅 arm64-v8a |
| Debug 包名 | dev.streampocket.localtest |
| 来源 | X/Twitter、哔哩哔哩及 yt-dlp 支持站点；不保证每个链接有效 |
| 不包含 | DRM/付费权限绕过、验证码绕过、直播录制、主页批量、图片合集、边下边看 |
| 设备限制 | 部分 WebP 原生依赖为 4 KB LOAD 对齐，16 KB 页面设备未验证 |

最高可用画质取决于源站、账号权限、地区、网络和编解码器，不能恢复平台压缩前的原片。并发数不是速度倍增保证。拖动小图来自附近关键帧，不保证帧精确或固定帧率；真实手机解码性能与完整账号登录仍需设备验证。自动化测试不等于真机验收，见 [测试说明](docs/TESTING.md)。

## 构建

需要 JDK 17、Python 3.9+、Android SDK Platform 35 和 Build Tools 35.0.0。AGP 8.7.3、Gradle 8.9。

1. 在 Android Studio 的 SDK Manager 安装上述 SDK。
2. 设置 ANDROID_HOME，或在不提交的 local.properties 中设置 sdk.dir。
3. 在仓库根目录执行：

```bash
# Linux / macOS
chmod +x gradlew
./gradlew assembleDebug

# 检查
./gradlew testDebugUnitTest lintDebug
```

```powershell
# Windows
.\gradlew.bat assembleDebug
```

输出：app/build/outputs/apk/debug/app-debug.apk。自行构建使用本机 debug key，不能覆盖签名不同的既有测试包；不要为解决签名冲突直接卸载含有需要保留数据的应用。仓库不含维护者签名私钥、密码或个人会话。

gradlew 是短 Python 启动器，不是官方 Gradle Wrapper 二进制；它从 Gradle 官方下载 8.9 并核验固定 SHA-256。也可使用官方 Gradle 8.9。构建需访问 Google Maven、Maven Central 和 Gradle 官方分发地址。二进制再分发前请核对[第三方义务](THIRD_PARTY_NOTICES.md)。

## 使用

1. 复制链接，打开“新建 → 粘贴 → 解析视频”，选择画质并下载；也可从其他应用分享文本。
2. 文件保存到系统 Download/StreamPocket，在视频库打开、整理或分享。
3. 需要账号时，在“设置 → 账号管理”进入对应平台官方网页，完成自己的登录后保存。“已保存”不等于源站确认会话有效。

Android 10+ 保存自己的下载无需“所有文件”权限；Android 7–9 需要公共目录写入权限。通知被拒绝时，下载页仍展示状态。系统强制结束进程或限制后台运行后，可能需要重新打开应用手动重试。

仅使用自己的账号和有权下载的内容，遵守来源条款与适用法律。应用直接访问源站/CDN，不提供远程代理解析或账号提权。

## 目录与许可

- app/src/main：应用、插件与固定官方 yt-dlp zipapp。
- app/src/test、app/src/androidTest：回归测试与可选设备验收 runner。
- tools：构建启动器和插件测试。
- docs：使用、测试、依赖来源与发行说明。
- test-media：获许可的测试媒体，仅用于隔离测试，不进入生产 APK。
- licenses：第三方许可文本。

自有部分使用 **GPL-3.0-or-later**，见 [LICENSE](LICENSE)。抖音插件保留上游 Unlicense；第三方材料保留各自条款，见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。感谢 yt-dlp、youtubedl-android、AndroidX Media3、Termux 及其依赖维护者。《Big Buck Bunny》测试素材由 Blender Foundation 提供，CC BY 3.0，见[署名和衍生说明](test-media/README.md)。本项目不使用 Seal / YTDLnis 品牌或复制其应用源码。
