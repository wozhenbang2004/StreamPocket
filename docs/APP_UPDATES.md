# 应用更新

在“设置 → 高级与关于 → 应用更新”中检查、下载新版本。

## 更新流程

1. 查看版本、大小和更新内容，确认下载。
2. 本页显示真实百分比与已下载/总字节；系统 DownloadManager 处理网络等待和断点恢复。
3. 下载完成自动校验安装包，更新页可见时再复核一次并打开手机系统安装确认，不需另点一次安装。
4. 如需允许此来源，在系统设置中开启后返回即可自动继续；最终安装仍由用户在系统界面确认。
5. 从系统安装返回后检查实际安装版本；若仍为旧版，保留安装包并显示重新安装入口。

这是完整 APK 更新，不是差分或静默安装。切到后台不会自动拉起安装界面；点击下载通知或重新进入更新页会恢复当前任务。取消下载仅删除更新缓存，视频、账号与现有应用不变。取消系统确认后不会反复弹出，可主动重新打开确认。

“打开应用时检查更新”可关闭；开启时每天最多自动检查一次，只读取 GitHub 公开 Release。

## 发行资产约定

客户端从 `https://api.github.com/repos/wozhenbang2004/StreamPocket/releases/latest` 读取非 draft、非 prerelease 的发布，要求同时存在：

- `StreamPocket-arm64-v8a.apk`
- `update-manifest.json`

清单字段：`schemaVersion`（1）、`versionCode`、`versionName`、`packageName`、`abi`、`minSdk`、`apkAssetName`、`apkSize`、`apkSha256`、`signingCertificateSha256`。

包名保持 `dev.streampocket.localtest`，ARM64，minSdk24；版本号必须严格递增，并使用与已安装应用相同的证书。客户端核对大小、SHA-256、包名、版本、最低系统与签名身份；只有私有校验目录通过临时只读授权交给系统安装器。系统最终验签与确认不可省略。每个版本使用由versionCode和完整SHA-256组成的独立私有文件名与content URI；已交出的安装地址不再被后续版本覆盖。

发行时应同时保留 [应用源码与第三方对应源码入口](THIRD_PARTY_SOURCES.md)。签名私钥不进入仓库或发布资产。

## 旧版安装未升级

0.2.11 用户若系统安装后仍显示旧版本，可从 Release 下载 `StreamPocket-0.2.13-arm64.apk`，用系统文件管理器打开并覆盖安装。此文件与应用内分发包内容及签名相同，不需要卸载或清除数据。新版包含安装地址与结果检查修正。
