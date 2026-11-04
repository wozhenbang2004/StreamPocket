# 安装与版本

当前发行版本 **0.5.0 / versionCode 31**，应用名“拾流”。支持 Android 7.0+ / ARM64，包名为 `dev.streampocket.localtest`，沿用既有安装身份与签名。此版本加入下载队列管理、观看状态与收藏、合集整理、系统画中画、播放模式和音轨/字幕选择，并保留下载与更新恢复修复。

- [安装包](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.5.0/StreamPocket-arm64-v8a.apk)
- [版本更新内容](https://github.com/wozhenbang2004/StreamPocket/releases/tag/v0.5.0)
- [应用内更新](APP_UPDATES.md)
- [第三方对应源码](THIRD_PARTY_SOURCES.md)

已有拾流直接覆盖安装，不必卸载或清除数据。来源安装权限由系统管理，最后一步由用户确认。

应用源码随该 Release 标签提供；原生组件保持不变，其对应源码八分片继续保留在 v0.2.9-test 的固定链接中。请按第三方源码说明合并、校验后查看构建配方与许可证。

## 0.5.0 · 2026-11-04

发行标签 `v0.5.0` 对应源码提交 `780752dad98674f2b64dc67eba5eae1019bd86c9`，与已验证提交 `f3b35c65eb2d974e6623b83b373d886f04e592d6` 具有相同 Git 树。验证记录见 [CI run 41774940683](https://github.com/wozhenbang2004/StreamPocket/actions/runs/41774940683)；合并后的 [main CI run 41778320226](https://github.com/wozhenbang2004/StreamPocket/actions/runs/41778320226) 同样通过。

### 安装包身份

| 项目 | 值 |
| --- | --- |
| 版本 | 0.5.0 / versionCode 31 |
| 包名与平台 | `dev.streampocket.localtest` · ARM64 · minSdk 24 |
| 固定发行资产名 | `StreamPocket-arm64-v8a.apk` |
| 签名后大小 | 65,107,685 bytes |
| APK SHA-256 | `7e4f0d43aa5c479feee20fe733ac3d81cd68f86593070d729a27d3f75bcfc5da` |
| 证书 SHA-256 | `67d2d8e285c629f3c9076a9f00f12d4a79c9139d452aa2b876dae803df8373fd` |

签名成品的包名、版本、ARM64、minSdk 24、非 debuggable、16KB 对齐与 APK v2/v3 签名校验均通过。固定名与带版本号安装包使用相同字节；`update-manifest.json` 依据签名后成品的大小、摘要和身份生成，作为独立 Release 资产提供。发布后远端资产大小与摘要已核对一致。

### 验证结果与边界

- **58 个测试套件、409 项测试全部通过**：0 失败、0 错误、0 跳过。
- 独立执行 **83 项下载核心更新检查**与 **5 项进程停止检查**，全部通过。
- `testDebugUnitTest lintDebug assembleDebug assembleRelease` 及可并装预览构建通过；**Lint 0 错误、74 警告**，未宣称清除全部警告。
- **Android 15 / x86_64 模拟器实际媒体验收通过**：许可横屏、竖屏视频解码与首帧，拖动取帧，面板取消与返回，侧滑露出操作不误执行，返回视频库与退出释放播放器。
- **系统画中画验收通过**：窗口缩小后回到系统桌面，主界面离开前台仍继续播放；切换下一条后出现首帧、保持播放和音频焦点、进度继续推进；随后返回全屏和视频库。画中画需要 Android 8.0+ 且设备支持。

上述模拟器和离线回归不代表用户手机/OEM、ARM64 硬件解码性能、耗电、真实源站下载、真实剪辑导出或实机覆盖安装已经验证。本次没有速度或耗电改善百分比结论。

以下保留历史发行与验证记录；各版本的签名摘要和测试结果只属于其对应产物。

## 2026-11-01 发布范围

用户追加明确要求：“发布啊，也要发布啊，release 推送更新。”授权将已推送开发分支中的链接/剪贴板体验、三列视频库、下滑切换、失败记录与恢复提示作为正式版本发布，并提供应用内更新。

发行检查：版本递增至 22；恢复原签名并与 0.3.1 清单中的公开证书摘要核对；构建非 debuggable Release；发布固定名称 APK 和更新清单；发布后回读 `/releases/latest`，校验远端清单与安装包。测试与设备验证边界见 [TESTING](TESTING.md)。

本次签名成品：65,204,674 字节，SHA-256 `28b73ecc3f3a7607a3264e4c67e4cff70d5b4cf45b5d363d8b07d5fbc8aac42c`；证书 SHA-256 `67d2d8e285c629f3c9076a9f00f12d4a79c9139d452aa2b876dae803df8373fd`。带版本号与应用内更新 APK 使用相同字节；更新清单单独作为 Release 资产分发，不写入 APK。


## 0.3.3 追加范围

沿用用户对本项目“release 推送更新”的授权，处理同日实机反馈：输入聚焦、普通画面上下刷视频、双指缩放及条件复原、双视频分屏与 X 无画质的有限恢复。保留原包名与签名，递增至 versionCode 23。功能、手势和资源边界见 MEDIA_EXPERIENCE；验证记录见 TESTING。

0.3.3 成品：65216962 字节，SHA-256 `149e809d2abd793df6b5be39ab58cda330ebee15047110ecc6c0a5953358ef01`；证书 SHA-256 `67d2d8e285c629f3c9076a9f00f12d4a79c9139d452aa2b876dae803df8373fd`，与 0.3.2 一致。非 debuggable，包名/版本/ARM64/minSdk、zipalign 16KB 与 APK 签名校验通过。最后修订后播放器/分屏15项重点用例、Lint及Release构建 exit 0。

0.3.3 发行资产包括固定名安装包、带版本号安装包与 update-manifest.json；两个 APK 字节相同。源代码与本标签对应，第三方原生源码入口保持不变。


## 0.3.4 · YouTube 渠道

新增 YouTube 长短链接、Shorts、别名去重、单视频规范化、现有画质选择/音视频合并/队列接线和独立来源筛选。源码从已发布 475bd21 继承，没有新增原生组件。

274 项单元与离线原生 View 回归、Lint、Release 构建通过；原签名、包名/24/0.3.4、非 debuggable、16KB 对齐、QuickJS 与 EJS 组件检查通过。签名成品 65216962 字节，SHA-256 `692eb0c8cfd5d7c2c1682731a75636171ed6280fab17b0e9b1f5fe9f63400410`。两个 APK 字节相同，清单对应签名后成品。

YouTube 真实源站解析/传输、用户手机网络、硬件播放与 OEM 覆盖安装未执行；离线测试不冒充实网成功。
