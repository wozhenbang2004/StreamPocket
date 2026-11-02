# 第三方对应源码

0.3.8 新增小黑盒本地提取插件。公开 Web 请求签名函数适配自 `sokoko-org/nonebot-plugin-parser-lite`，固定提交 `a6a68c4db0f0806a1a61f19e2b0474851b365675` 的 `src/nonebot_plugin_parser_lite/parsers/heybox/encrypt.py`（MIT）。保留原作者与逆向贡献者署名，完整许可随 APK 资产 `plugins/streampocket/yt_dlp_plugins/extractor/heybox-signing.LICENSE.txt` 及源代码一起分发；实现位于对应插件源码。原生载荷未改变。

0.2.10 沿用 0.2.9 的八项原生载荷，使用同一份对应源码材料。下载下列八个连续分片到同一目录；它们是一个 ZIP 的连续字节，不是八个独立 ZIP：

- [StreamPocket-0.2.9-third-party-sources.zip.part01](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources.zip.part01) · SHA256 `633f60ed08142ab9ff6d37d21390d9e085a030ee9167b3705f20bcbc465c9556`
- [StreamPocket-0.2.9-third-party-sources.zip.part02](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources.zip.part02) · SHA256 `9fe50029bde02c1aa1b0d22537cd85efa280d56af0566da358de4850b5ddf6cb`
- [StreamPocket-0.2.9-third-party-sources.zip.part03](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources.zip.part03) · SHA256 `49c0b8202792e2557425ea172c9d265288c51f85dcbb810400eee75aca04ce47`
- [StreamPocket-0.2.9-third-party-sources.zip.part04](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources.zip.part04) · SHA256 `96e360eb9b6db1d30341a47ebeaf183432703e53d1970eaa47ae7e937d4c746b`
- [StreamPocket-0.2.9-third-party-sources.zip.part05](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources.zip.part05) · SHA256 `ddb56fe7339c96c2ce5b0a00bbd049ed2116f500c1494fcf46dd56e9bf45e0eb`
- [StreamPocket-0.2.9-third-party-sources.zip.part06](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources.zip.part06) · SHA256 `2d57fb63a0f0139b89d863ab7440d91c39f52c47294715268fae37457721c09c`
- [StreamPocket-0.2.9-third-party-sources.zip.part07](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources.zip.part07) · SHA256 `ccbf51fa9c570c6ecf74c462ba3057a2fbe9dbaa721a6926bdf67a0b74e3bf94`
- [StreamPocket-0.2.9-third-party-sources.zip.part08](https://github.com/wozhenbang2004/StreamPocket/releases/download/v0.2.9-test/StreamPocket-0.2.9-third-party-sources.zip.part08) · SHA256 `7d8acc2dbf61c8d3ae70013f1f2b9dbe6fd76d832118bd31f202aab41f7e85a2`

Linux / macOS：

```bash
cat StreamPocket-0.2.9-third-party-sources.zip.part* > StreamPocket-0.2.9-third-party-sources.zip
sha256sum StreamPocket-0.2.9-third-party-sources.zip
```

Windows cmd.exe：

```bat
copy /b StreamPocket-0.2.9-third-party-sources.zip.part01+StreamPocket-0.2.9-third-party-sources.zip.part02+StreamPocket-0.2.9-third-party-sources.zip.part03+StreamPocket-0.2.9-third-party-sources.zip.part04+StreamPocket-0.2.9-third-party-sources.zip.part05+StreamPocket-0.2.9-third-party-sources.zip.part06+StreamPocket-0.2.9-third-party-sources.zip.part07+StreamPocket-0.2.9-third-party-sources.zip.part08 StreamPocket-0.2.9-third-party-sources.zip
```

合并文件为 385,243,242 bytes，SHA256：`bf7e8557e6f8f52f617758b03f942fd2059eceb40c6c6fef5e6ebccedd8ba76d`。逐分片大小与摘要见 [third-party-source-parts.json](third-party-source-parts.json)。

解压后阅读 `README.md`、`BUILDING.md`、`SOURCES.json`、`SOURCE-MAP.json` 与 `licenses/`。材料包含固定上游源码、Termux 构建配方、包补丁、wrapper 源码、配置及许可证，保留各自版权与许可。应用源码另由当前 Release 标签提供。

## 0.4.0 图集适配来源

`albums.py` 的数据结构以随包 yt-dlp **2026.10.26** 的 `yt_dlp/extractor/xiaohongshu.py` 和 `tiktok.py` 为依据（上游 Unlicense，内核 SHA256：e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3）。小红书所选笔记、视频 stream 和 originVideoKey 既有路径保留；本项目新增 normal 图集输出与任务标记。抖音沿用现有固定 PR 的兼容请求，再增加 images 帖子输出及请求作品 ID 核对。没有采用调研候选的浏览器登录会话、远端解析或代理轮换。自有图集适配代码按文件声明许可，原补丁的 Unlicense 和署名保留。
