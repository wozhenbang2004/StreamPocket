# 抖音兼容补丁（Experimental）

本插件把真实的上游补丁包装为 yt-dlp extractor override，保持原有 URL 匹配、媒体字段与全部画质解析。它仍是**实验性支持**；打包、离线测试或第三方成功记录不代表本 App 已完成抖音实网下载验证。

## 来源与版本

- 上游：[yt-dlp PR #17826](https://github.com/yt-dlp/yt-dlp/pull/17826)，作者 `viking1304`。
- 固定 head：`728fe46fbaa97d6f03efcaf9e766d4db63338b8d`。
- 源文件：[yt_dlp/extractor/tiktok.py](https://github.com/yt-dlp/yt-dlp/blob/728fe46fbaa97d6f03efcaf9e766d4db63338b8d/yt_dlp/extractor/tiktok.py)，Git blob `dba66e736334dccf875719ac0ad9d7ee5715b51d`。
- 许可证：[Unlicense](https://github.com/yt-dlp/yt-dlp/blob/728fe46fbaa97d6f03efcaf9e766d4db63338b8d/LICENSE)。PR 作者已明确勾选原创且愿意按 Unlicense 发布。其研究致谢链接为 [Evil0ctal 的发现](https://github.com/Evil0ctal/Douyin_TikTok_Download_API/commit/d9f718800df20c0255979d93ebadb33dd3a3b757)。本项目复用的是上述 PR 的实现。

本地对应：`app/src/main/assets/plugins/streampocket/yt_dlp_plugins/extractor/douyin_compat.py`。原始兼容包装包括 imports、来源注释及 `_StreamPocketDouyinIE(DouyinIE, plugin_name='streampocket-experimental')`；签名和兼容请求方法沿用固定提交中的实现。0.4.0 另增加 `/note/` 匹配、images 图集输出和请求作品 ID 核对，这些增量是本项目适配，不冒充原 PR 原码。没有自行发明签名算法，也没有修改其他平台提取器。

## 与下载引擎的契约

1. 保持目录层级，把 `assets/plugins` 递归复制到 App 私有目录 `files/plugins`。
2. 每次**解析和下载**均传 `--plugin-dirs <files/plugins>`。其下必须是 `streampocket/yt_dlp_plugins/extractor/douyin_compat.py`，而非直接把 `yt_dlp_plugins` 放在传入目录下。官方加载器会遍历传入目录的子项作为插件包。
3. 私有 override class 使用官方 `plugin_name` 关键字替换内置 `DouyinIE`。`ie_key()` 仍为 `Douyin`，显示名为 `Douyin+streampocket-experimental`。只改 `IE_NAME` 不能等同该替换机制。
4. 插件沿用内置 URL 匹配；抖音分享短链仍由 yt-dlp 正常重定向到完整视频 URL。图文笔记、直播、主页批量下载未由本插件新增实现。
5. 请求 `aid=6383`，保留上游返回的全部画质。App 按最高可用格式选择，不把 720p 伪称 1080p，也不作重新编码放大。
6. `_real_extract()` 返回 `http_headers.Referer=https://www.douyin.com/`，供需要 Referer 的 `douyinvod.com` CDN 使用。若 App 后续自行保存/重新使用 format 信息，必须保留这些 headers；使用 yt-dlp 正常提取下载则不需要全局 `--referer`，避免影响 X、B站等其他平台。

依赖仅 Python 标准库 `hashlib/json/time/urllib.parse/uuid` 和 yt-dlp 自身 `DouyinIE`、`ExtractorError`、`traverse_obj`。不依赖 Playwright、Node、第三方解析 API 或独立 JS 签名库。

`passport_csrf_token` 与 `ttwid` 只在缺失时按上游方式初始化，不覆盖用户导入的现有 Cookie。`UIFID` 先从 Cookie 查找，再回退到公开网页响应头；详情请求按上游的 `x-secsdk-web-signature` 公式签名。本插件不实现验证码交互；平台仍可能拒绝请求，应向用户显示失败并保留重试入口。

## 验证

离线命令（先安装真实的 `yt-dlp` Python 包）：

```bash
PYTHONPYCACHEPREFIX=/tmp/streampocket-plugin-pycache python3 -m py_compile app/src/main/assets/plugins/streampocket/yt_dlp_plugins/extractor/douyin_compat.py tools/test_douyin_plugin.py
PYTHONDONTWRITEBYTECODE=1 python3 tools/test_douyin_plugin.py
```

测试使用真实 yt-dlp 插件加载器与 Cookie API，网络方法被 Mock。覆盖目录发现与覆盖注册、原 Cookie 保留、缺失 Cookie 初始化、UIFID 获取、签名构造、画质请求参数及下载 Referer。`--list-extractors` 会在 CLI 的插件初始化前退出，不能单独作为 override 已生效的证据；这里在独立进程执行真实加载器，再通过 `YoutubeDL.get_info_extractor('Douyin')` 检查实际命中的类。

固定签名向量是本项目依据固定提交公式、手动拼出的完整字节串，并以 Python hashlib 与 OpenSSL 两种方式核算；**它不是上游提供的 golden、真实线上请求或下载成功证据**。Android 嵌入的 yt-dlp 版本还需检查 `--verbose` 加载信息并完成设备测试。上游未来合并或修改该实现时，应重新比较补丁并考虑移除 override。

