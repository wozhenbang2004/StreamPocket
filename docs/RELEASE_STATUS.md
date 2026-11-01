# 安装与版本

当前版本 **0.3.4 / versionCode 24**，应用名“拾流”。支持 Android 7.0+ / ARM64，包名为 `dev.streampocket.localtest`，沿用既有安装身份。

- [安装包](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.3.4/StreamPocket-arm64-v8a.apk)
- [版本更新内容](https://github.com/wozhenbang2004/StreamPocket/releases/tag/v0.3.4)
- [应用内更新](APP_UPDATES.md)
- [第三方对应源码](THIRD_PARTY_SOURCES.md)

已有拾流直接覆盖安装，不必卸载或清除数据。来源安装权限由系统管理，最后一步由用户确认。

应用源码随该 Release 标签提供；原生组件保持不变，其对应源码八分片继续保留在 v0.2.9-test 的固定链接中。请按第三方源码说明合并、校验后查看构建配方与许可证。

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
