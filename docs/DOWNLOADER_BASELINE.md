# StreamPocket 全面升级：能力基线与验收覆盖

更新时间：2026-11-07（北京时间）。用户已授权全面升级：以下13个项目的互补能力是最低验收基线。本文记录能力目标、已有证据、实现状态与验收条件；文档建立不代表功能实现、测试通过或正式发布。

## 1. 目标与状态口径

本批开发范围为任务恢复、通用 HTTP(S) 文件下载、下载预设、命名模板与下载归档；这些项目尚须逐项实现并验收。其他全部能力继续保留为未完成范围，不能因优先级、开发批次、候选库不适配而删除或降级。未经对应证据更新，不把“开发中”记为“完成”。

把 Motrix、Rayburst（原 Motrix Next）、aria2-next、IDM、AB Download Manager、Gopeed、YTDLnis、Seal、gallery-dl、N_m3u8DL-RE、JDownloader 2、1DM、ADM 的互补能力作为全面升级的能力下限。最终提供统一的任务、文件、认证、错误恢复与播放体验。UI 框架和下载引擎分开评价；工具数量、线程数量、配置数量不作为完成证据。

下列验收是目标契约，不是对上游已实测的断言。默认优先在手机本机实现。确实由平台能力限定的桌面/NAS功能，才映射到受控的电脑/NAS执行目标，并保留同一任务的可见状态、控制与成果定位；不能用远端执行替代本来可以本机完成的下载。执行位置差异应让用户看得懂，不能让用户猜文件在哪台设备。

状态：**已有源码**＝基线核验已取得自有实现证据，但本次研究未实机复验；**部分**＝覆盖部分行为且有明确缺口；**缺口**＝基线核验已确认未实现；**待核验**＝基线核验尚无足够证据，不等于未实现。上游证据标为官方文档、官方源码或开发者商店描述，不混作实测。

下表自有状态对应本批开工前的源码检查点；后续实现与验收应在同一行更新，并保留对应版本与证据。当前自有源码锚点：`wozhenbang2004/StreamPocket@e38088e44d88a8a35b787445abac6e177bf176b8`。源码版本 0.6.0/code33；核验到的正式 Release 仍 v0.5.1/code32，0.6.0 临时签名预发布不满足原正式版覆盖升级验收。

## 2. 项目覆盖索引

| 研究对象 | 作为下限的能力 | 对应验收项 | 证据与边界 |
|---|---|---|---|
| Motrix v2 | 通用协议、独立执行核心、重启会话、tracker、插件、桌面/Server一致任务 | P01–P07、R01–R09、X01–X05 | 官方仓库/发布；当前 stable 2.3.0。应用 MIT；引擎另核许可。通用下载优先本机；桌面专属与Server功能可映射远端目标 |
| Rayburst | 多协议、任务/连接/peer独立限额、资源嗅探、受控接管、休眠UI但持续执行、媒体选择 | P01–P07、W01–W05、R03、R05、R08–R09、M01–M05 | 官方仓库/源码；stable4.1.1，beta4.1.2-beta.2；旧名称去重，不算两个竞品 |
| aria2-next | 自适应范围、慢尾处理、身份校验、检查点、BT/ED2K、原生流媒体 | P01–P07、R01–R09、M01–M05 | 官方引擎3.0.10；有Android ARM64产物。旧`.aria2`不导入、FTP/libaria2不提供，非无缝替换 |
| IDM | 直链接管、视频发现、按浏览器/站点/类型排除、临时放行、下载交接 | W01–W05、R05 | 官方Options；闭源，参考行为，不能声称搬用源码；普遍速度优势未实测 |
| AB Download Manager | 任务/分片下载、低干扰接管与独立控制、非加密HLS | P01、W01–W05、M01 | Apache-2.0；官方源码/CHANGELOG确认Kotlin+OkHttp。非加密HLS有版本和扩展要求；YouTube不支持不成为我方解析能力上限 |
| Gopeed | Go下载框架、BT/ED2K、JS扩展、多目标与浏览器接管；main中的FTP/HLS能力独立记录 | P01–P07、W01–W05、X01–X05 | 已核实main `go.mod`含`anacrolix/torrent`与`goed2k`，另有JS扩展机制。main FTP/HLS不能直接宣称已进入当前Release；远端confirm选项部分路径只控通知 |
| YTDLnis | 媒体解析、模板预览、下载预设、持续订阅/调度、归档 | B01–B06、O01–O04 | 固定官方源码与指南；不能默认照搬会删除本地文件的同步选项 |
| Seal | 媒体下载预设、归档与下载配置流程 | B01、B04、O01、O03 | 固定官方源码；各细项本次研究未在Android上实测 |
| gallery-dl | 图集/作者/章节提取、稳定ID增量归档、命名/元数据、ZIP/CBZ | B01–B05、O01–O04 | 官方GitHub与文档；活跃开发已迁Codeberg，新仓全源码本次研究未读。archive不是哈希去重 |
| N_m3u8DL-RE | DASH/HLS/MSS、多轨/Period、分片校验、直播、字幕、独立合并/封装 | M01–M07、R06、O04 | 官方main文档/release；MIT；Termux/Bionic产物存在不等于可嵌APK；main与release能力需逐项钉版本 |
| JDownloader2 | 资源包/链接容器、crawler/hoster插件、账号路由、分卷解包 | A01–A04、B01–B03、O05–O06、X04 | 官方支持文档；整套本地移植需要完整JRE/JDK，允许桌面/NAS受控执行；组件许可分别核对 |
| 1DM | Android浏览器发现、地址刷新、BT、调度/网络条件与文件组织 | P03、W01–W04、R02、R04–R05、O01 | 本次研究仅开发者商店描述；精确功能范围与行为待样机补验，不自造源码或性能结论 |
| ADM | Android媒体发现、BT、调度/网络条件、任务管理与文件分类 | P03、W01、R01–R05、O01 | 本次研究仅开发者商店描述；两产品各自精确功能差异待补证，不能称全量覆盖完成 |

## 3. 通用协议与传输能力

| ID | 目标下限 | 来源 | 我方现状（基线核验） | 可验收结果 |
|---|---|---|---|---|
| P01 | 普通HTTP(S)文件与视频共享任务入口；支持无后缀/无长度/重定向资源 | Motrix、Rayburst、aria2-next、AB、IDM；S01–S06 | **部分**：公开HTTP(S)视频经yt-dlp；通用文件入口**缺口** | 普通安装包、文档、媒体、无后缀附件均可建任务；正确处理Content-Disposition、长度未知、重定向与文件名；不得把HTML错误页存成成功文件 |
| P02 | Range分段、断点、单任务连接和多任务并发分离 | aria2-next、AB、IDM；S03/S05/S06 | **部分**：单任务1/4/8连接；全局串行，无完整总带宽/每站预算入口 | 同时运行多任务；服务器200/206/416行为正确；不支持Range自动退回可用路径；实际连接数受单任务、每站、全局预算约束 |
| P03 | BT种子与磁力、metadata、文件选择、做种/上传控制 | Motrix、Rayburst、aria2-next；S01–S03 | **缺口** | 添加torrent/magnet→取得metadata→选择文件→下载→hash校验→保存；多文件部分选择、重启、做种限额、停止上传和无源状态可验证 |
| P04 | FTP、SFTP、Metalink等通用协议覆盖，按引擎真实能力路由 | Motrix与aria2-next互补；S01/S03 | **缺口**；已有aria2依赖不代表已开放协议 | 每个宣称支持的协议各有真实传输与失败样本；FTP不能因aria2-next不支持而消失，允许原引擎或远端执行；凭据不写明文历史/日志 |
| P05 | ED2K/Kad、thunder协议链接按实际解码目标执行 | Rayburst/aria2-next；S02/S03 | **缺口** | 协议链接正确识别、显示原来源与执行目标；ED2K真实传输校验；thunder解码后按目标协议处理，不宣称能凭链接绕过源站限制 |
| P06 | BT连接与可达性：tracker来源/健康、DHT/PEX、端口映射、做种预算 | Motrix、Rayburst/aria2-next；S01–S03 | **缺口** | tracker失效不误报磁力已坏；更新来源有版本与失败回退；DHT/PEX/UPnP/NAT-PMP按引擎和执行目标真实能力展示；映射无效可诊断；上传与做种可限额和停止 |
| P07 | 多镜像/Metalink资源身份与校验，明确各协议版本支持 | Motrix、aria2-next；S01/S03 | **缺口** | 相同资源多来源可选择/切换且字节校验一致；损坏镜像不污染成品；Metalink4与不支持的Metalink3有明确区分；不把任意同名URL合并成镜像 |

## 4. 网页发现、接管与账号

| ID | 目标下限 | 来源 | 我方现状（基线核验） | 可验收结果 |
|---|---|---|---|---|
| W01 | 浏览器媒体发现：列表聚合HLS/DASH/音视频，播放不受打断 | Rayburst Connect、IDM、1DM；S04/S06/S17 | **缺口**：普通下载浏览器/资源嗅探未实现 | 页面同资源重复请求聚合；画质/类型/来源可辨；自动播放音乐不抢焦点；可主动选资源入队 |
| W02 | 发现、提示、接管、提交确认四类行为可独立控制 | Rayburst、AB、Gopeed、IDM；S04–S08 | **待核验**：未有此接管链证据 | 全局与站点设置易找；每次确认/按偏好后台下载/下载并打开任务可选；“不提示”不等于“禁止发现”；规则命中原因可查看 |
| W03 | 按来源页与资源URL、域名、后缀、MIME、体积过滤；本次放行 | IDM、AB、Gopeed、Rayburst；S04–S08 | **缺口** | CDN资源仍可按来源页规则管理；本次跳过不改永久偏好；本站不提示可撤销；非GET、HTML网页保存与不适配请求保留原流程 |
| W04 | 外部分享/主动入口与受控浏览器衔接，保留来源和认证上下文 | IDM、Connect、Gopeed、1DM；S04/S06/S08/S17 | **部分**：分享文案可提取链接；浏览器衔接未实现 | 分享文本/单链接/列表均可接收；任务保留来源页、最终URL与所选项；未获外部应用网络权限时用分享/扩展/远端桥接，不能宣称全局嗅探所有App |
| W05 | 可靠交接：接收回执、去重、失败恢复，避免浏览器和下载器两边丢任务 | Connect、Gopeed、Motrix；S01/S04/S08 | **缺口** | 相同提交ID重试只生一任务；接收结果未知先查询；下载器未接收时原流程仍可继续；跨设备明确目标、授权、状态和成果位置 |
| A01 | 站点认证、Cookie/headers按来源隔离，避免凭据跨域扩散 | Rayburst媒体契约、JD、现有yt-dlp；S04/S09/S10 | **已有源码**：账号和代理；隔离细节**待核验** | 登录态有效/过期/退出各有样本；跨域重定向不无条件转发敏感头；导出诊断无Cookie/token/签名URL；任务不复制秘密到历史 |
| A02 | 凭据失效时暂停相关任务，更新后继续相同内容 | JD、1DM地址刷新、Media3 Resolver；S09/S17/S19 | **部分**：已有账号；失效刷新/身份续传路径待核验 | 过期链接/401/403区分认证、限流和资源删除；重登录/刷新地址后核对内容身份再续传，不把另一文件拼到旧断点 |
| A03 | 多账号/免费/付费或远端服务的使用策略与优先级 | JD Account Usage Rules；S09 | **待核验** | 每站可选账号与明确回退；付费额度/不可用状态可解释；用户禁用某账号或免费回退后不会暗用；只使用用户已有授权 |
| A04 | 插件区分网页收集/解析、真实下载、账号支持，不用一个站点列表掩盖局限 | JD crawler/hoster；S09/S10 | **部分**：现有yt-dlp解析；插件职责和支持证据待核验 | 来源能力页能区分公开解析、登录下载、合集、直播、字幕；同站点解析成功但下载需登录时给明确可处理状态 |

## 5. 批量、持续收藏与组织

| ID | 目标下限 | 来源 | 我方现状（基线核验） | 可验收结果 |
|---|---|---|---|---|
| B01 | 文本/多链接/播放列表/图集/章节/资源包统一收集，先选内容再入队 | JD、gallery-dl、YTDLnis、Seal；S09–S14 | **部分**：分享最多100不同链接；YouTube播放列表/B站分P显式展开前100项；部分图集ZIP | 支持分页/继续展开，不将100项截断误报完整；显示总量或未知；重复输入聚合；用户能预选、剔除、批量设格式/目标目录 |
| B02 | 合集为父对象，子任务独立重试与统一统计 | JD、gallery-dl、YTDLnis；S09–S13 | **部分**：已有链接展开，父子成果语义待核验 | 某项失败不阻断其他项；父任务清晰显示成功/失败/跳过/待选；可只重试失败项，不重新下完整合集 |
| B03 | 链接容器/批量清单导入与导出，保留包名、密码引用和来源 | JD容器/Click'n'Load；S10 | **缺口** | 文本和可公开格式清单可回导；DLC/CCF/RSDF兼容按真实能力声明；需要外部服务的格式显示依赖，不能称作本地ZIP或安全加密承诺 |
| B04 | 稳定内容ID下载归档，区分资源、格式、版本与文件存续 | gallery-dl、YTDLnis、Seal；S11–S14 | **部分**：输入/任务去重；长期内容归档**缺口** | 链接更新不重复下相同资源；同资源新画质可按策略另下；取消/失败不写成完成；磁盘文件已删可选择补下或仅保留记录；archive不冒充hash校验 |
| B05 | 作者/频道/合集持续订阅，仅新增、历史范围、定时/手动检查 | YTDLnis、gallery-dl增量机制；S11/S13 | **缺口** | 首次不误拉全部历史；重复检查不重复入队；有最近检查/下次条件/错误/暂停；源站删帖默认不删本地文件；自动检查具网络/电量/数量预算 |
| B06 | 下载预设与调度：格式/目录/命名/字幕/时间条件可保存复用 | YTDLnis、Seal、Rayburst；S02/S11/S12 | **部分**：有现有画质/连接档位等设置；预约与模板**缺口** | 配置前有真实命名与格式预览；批量应用可撤销；定时任务到点只启动一次；订阅调度与用户发起长传输采用适合机制 |
| O01 | 类型/来源/作者/合集组织、搜索筛选与命名模板 | gallery-dl、YTDLnis、1DM/ADM；S11/S13/S17/S18 | **部分**：视频库、状态筛选；命名模板固定 | 按任务/文件两个视角查找；中文/emoji/长路径/重名不丢信息；模板预览可见；大量记录仍可定位与操作 |
| O02 | 元数据、封面、字幕与主文件形成可继续成果 | gallery-dl、现有yt-dlp；S13/S14 | **已有源码**：字幕/封面/部分图集；归属一致性待复验 | 侧车文件与主文件身份匹配；移动目录、重命名、重新下载后引用仍对；导出成果包带可选元数据，认证秘密不入包 |
| O03 | 保存位置、迁移、磁盘满/权限丢失、移动或删除后的状态一致 | Android现状、Rayburst/JD流程；S02/S09/S20 | **部分**：SAF迁移与URI更新已有；边界待实机复验 | 迁移保留进度/成果；权限丢失可重新授权；磁盘满不标成功；删除任务与删除文件分开，选择明确且可解释 |
| O04 | 图集ZIP/CBZ、音视频封装、后处理均有阶段与失败恢复 | gallery-dl、N_m3u8DL-RE、FFmpeg；S13–S15 | **部分**：FFmpeg/图集ZIP已有；更广后处理未实现 | 下载完成与封装完成区分；中途失败保留可恢复源数据；重复后处理不覆盖已有正确文件；容器改变不被标为转码 |
| O05 | 分卷完整性、密码关联、完整后自动解包 | JD；S09/S10 | **缺口** | 缺卷、坏卷、错误密码逐类显示；只在完整且校验后解包；失败保留原件；目标路径越界和资源耗尽有界，不把解包失败算下载丢失 |
| O06 | 完成后的规则动作：通知、目录、解包、远端成果收取等 | JD、Rayburst、gallery-dl；S02/S09/S13 | **部分**：通知已有；可配置后处理规则待核验 | 仅确认成功后触发；重试不重复执行；低频高级动作渐进披露；优先本机完成普通后处理，平台限定的远端关机等动作明确对象与授权 |

## 6. 可靠性、流媒体与播放

| ID | 目标下限 | 来源 | 我方现状（基线核验） | 可验收结果 |
|---|---|---|---|---|
| R01 | task ID/检查点/排队状态持久；进程与设备重启恢复一致 | Motrix、aria2-next、Android调度；S01/S03/S19 | **部分**：TaskStore持久化；START_NOT_STICKY，重建运行项→interrupted，需显式重试 | 下载中终止/系统回收/重启后核对同任务同断点；无重复执行、重复文件或假完成；主动暂停不被恢复；强停App后遵守系统机制、重新打开再恢复 |
| R02 | 弱网、切网、临时URL、登录过期、服务端限流分类恢复 | aria2-next、1DM、现有yt-dlp；S03/S17/S20 | **部分**：已有重试与断点；URL刷新/自动恢复不完整 | 重试有上限/退避/Retry-After；429不加线程硬顶；可刷新URL保持内容身份；不可恢复错误说明下一操作，结果未知先核对 |
| R03 | 前台/后台/锁屏/熄屏与长任务调度，UI可释放 | Rayburst、Android UIDT/Media3；S02/S19 | **部分**：dataSync前台服务、通知、WakeLock；持久长任务调度缺口 | 支持Android版本各走真实路径；UI销毁不取消无关任务；通知控制与实际状态一致；耗电/内存有测量；不存在无限后台保证 |
| R04 | Wi-Fi/计费网络/漫游/充电/时间窗、总带宽与优先级 | ADM/1DM开发者描述、Rayburst、YTDLnis；S02/S11/S17/S18 | **部分**：排序已有；限速/预约/自动条件恢复**缺口**，各网络条件细节待核验 | 条件不满足可见地等待；切网不违背限制；新高优先任务可运行且旧任务不饥饿；约束改动持久且可回退 |
| R05 | 任务并发、单任务连接、每站连接、BT peers各自有预算 | Rayburst/aria2-next/AB；S02/S03/S05 | **部分**：全局串行，1/4/8单任务；其余缺口 | 每类限额可观察且不互相冒充；慢尾/限流时自动调整；暂停/取消只影响目标；公平性和实际吞吐用相同样本对测 |
| R06 | 真实完整性：文件/轨道/分片/哈希/封装校验与原子发布 | aria2-next、N_m3u8DL-RE、现有校验；S03/S15/S20 | **部分**：轨道/时长/MP4截断/图集检查；非全片解码 | 退出码0但截断/缺片仍失败；完成前不发布假成品；hash或协议校验按内容类型适配；原文件不因失败被覆盖 |
| R07 | 组件独立更新、签名校验、兼容协商、回滚与旧断点隔离 | Motrix、Rayburst/aria2-next、现有yt-dlp；S01–S03/S20 | **部分**：yt-dlp校验/回滚基础；其他组件未核验 | 更新前固定版本与能力；进行中任务不被静默换引擎；新版本失败回滚；旧`.aria2`不兼容明确保留原引擎，迁移只在验证后允许 |
| R08 | 网络策略覆盖下载、解析、更新与tracker，代理/系统代理/IPv4与IPv6故障可辨 | Rayburst、aria2-next；S02/S03 | **部分**：账号与代理已有；作用域/系统代理/地址族恢复待核验 | 每类请求使用声明策略；直连/代理失败不会静默换路；慢IPv6样本不无限卡住；证书校验使用可信策略，诊断可定位DNS/TLS/代理/HTTP阶段 |
| R09 | 暂停、取消保留、取消删除、失败重试、完成历史具有一致语义 | 现有任务体系、Rayburst、JD；S02/S09/S20 | **部分**：暂停/断点/重试/排序已有；跨引擎语义待核验 | 暂停不删除断点，删除按明确选项处理；重试不复制完成文件；运行中删记录/切执行目标不会产生孤儿执行；历史状态对应真实成果 |
| M01 | HLS/DASH/MSS清单、初始化段、多音轨/字幕与Period选择 | N_m3u8DL-RE、Rayburst、Media3；S02/S15/S19 | **部分**：yt-dlp+FFmpeg媒体下载；高级多轨/Period待核验 | 展示可用轨道/语言/角色并选取；所选下载与播放一致；MSS补齐实际可用路线；清单/分片身份与鉴权不串内容 |
| M02 | 直播录制、限时、手动结束、空闲超时、可靠收尾 | N_m3u8DL-RE、aria2-next/Rayburst；S03/S15 | **缺口**：当前排除直播 | 显示录制时长不伪造百分比；结束后等待封装成功再完成；断网/清单结束/过期片段可解释；终止保留可恢复片段 |
| M03 | 直播回看/片段范围/字幕单独处理等高级能力 | N_m3u8DL-RE；S15 | **缺口/待核验** | 服务端有回看窗口才可选；范围按整片段边界说明；不会承诺精确剪辑；多Period与字幕偏移样本正确 |
| M04 | 缓存与导出分离；MP4/MKV/音频/字幕输出可预览其组合限制 | Media3、Rayburst、N_m3u8DL-RE；S02/S15/S19 | **部分**：FFmpeg输出；共享离线缓存缺口 | 不支持容器/编码组合在开始前或明确阶段报错；缓存可播与可导出文件分别验收；合并不冒充转码 |
| M05 | HTTP/HLS/DASH真正边下边播，共享已取得数据并调度缺失位置 | Media3、aria2、Rayburst；S03/S19 | **缺口**：播放器只接已保存file/content，入口仅completed可播 | 未完成可播放；缓存命中/网络字节证明无两份完整传输；seek未下区有缓冲和优先级；moov头尾、206/200/416、断网复播与URL过期均覆盖 |
| M06 | BT边下边播，metadata/选片/索引/已验证piece/deadline窗口 | libtorrent；S19 | **缺口** | 只消费hash验证piece；seek清除旧窗口并重排；无源/慢peer/缺片有等待状态；不能用顺序下载或在线播放链接冒充完成 |
| M07 | DRM与授权状态准确，合法离线许可可播放，导出能力诚实 | Media3 DRM；S19 | **待核验** | 许可有效/过期/缺失可辨；加密缓存不被标为可导出明文；平台允许离线时可走标准授权；不宣称拿到m3u8/mpd等于能下所有内容 |
| M08 | 延续本地播放、画中画、音轨/字幕/手势，以及现有标记、分屏与轻剪辑 | 我方现有播放器与README；S20 | **已有源码/文档**：本地content/file播放与画中画；README列有音轨/字幕、手势、标记、分屏、轻剪辑；实际范围待复验 | 常见媒体可打开；返回原任务；权限/文件被移除可恢复；画中画不误停下载；新增流播不破坏现有音轨字幕、进度标记与剪辑引用；导出不改原片 |

## 7. 扩展、远端、体验与正式交付

| ID | 目标下限 | 来源 | 我方现状（基线核验） | 可验收结果 |
|---|---|---|---|---|
| X01 | 手机本地与电脑/NAS远端执行共用任务和成果模型 | Motrix Server、Gopeed、JD；S01/S08/S09 | **缺口**：远端下载未实现 | 选择目标时看得见设备/目录/在线状态；断联重连不重复提交；查询确认后归并状态；成果明确在远端或已拉回本地 |
| X02 | RPC/扩展接口认证、幂等提交、版本与能力协商 | Motrix MDXP、Rayburst Connect、Gopeed；S01/S04/S08 | **缺口** | 未授权调用被拒；权限仅覆盖选定目标；协议版本不兼容可解释；任务ID和提交回执能跨重试核对；不为下载暴露无鉴权控制端口 |
| X03 | 单执行权威：插件/解析器/传输/后处理融入同队列 | Motrix/Rayburst/JD；S01/S02/S09 | **部分**：已有自有任务队列；扩展契约缺口 | 同任务暂停/取消/恢复语义一致；插件不得另建不可见队列；父任务聚合子成果；事件可回放定位失败，UI不直接接管引擎持久化 |
| X04 | 可扩展解析、命名与后处理，但有权限/资源边界 | Motrix插件、JD、gallery-dl；S01/S09/S13 | **待核验** | 安装前标明来源/版本/能力；插件失败不破坏主队列；限权目录与网络；卸载/禁用可逆；来源更新与应用更新分别回滚 |
| X05 | 诊断可导出，错误提供具体原因与处理入口 | Rayburst、N_m3u8DL-RE doctor；S02/S15 | **部分**：现有错误/日志范围待核验 | 复现故障可关联task/阶段/引擎版本/重试；导出脱敏；结果未知和已失败区分；普通界面不堆日志和内部名词 |
| U01 | 首页聚焦新增与当前任务；高级能力按任务展开 | 用户已确认审美规则；上游流程融合 | **部分**：当前原生实现有改进；本次研究未视觉实机复验 | 首次分享→选择→下载→后台→打开顺畅；紧凑黑白灰、弱边框、清晰层级；实际批准组件原码适配；动效和减少动态效果均检查 |
| U02 | 大量任务、长标题、空/加载/错误/待授权/待选状态不丢上下文 | Rayburst/Motrix/既有设计要求 | **待核验** | 代表性大量任务仍可滚动操作；切筛选不展示旧数据；详情返回保留位置；取消/错误可恢复；不得每加功能再加同权卡片 |
| D01 | 原正式包名和签名覆盖更新，数据与任务保留 | 当前用户正式更新要求；S20 | **缺口**：正式0.6.0未完成，临时签名预发布不算 | 原0.5.1安装→应用内发现→下载验证→原签名覆盖→新版本启动；任务/账号/目录/历史保留；code/manifest/Release附件一致 |
| D02 | 核心能力逐项证据，不以编译/源码/宣传当实机完成 | 本次研究证据口径 | **未验收** | 每项记录版本、设备/执行目标、输入、操作、观察、网络字节/文件校验和失败恢复；源码实现、编译、运行、发布、用户实机分别记状态 |

## 8. 交付分组与覆盖守卫

本批首先实现恢复、通用HTTP(S)、预设、命名与归档，并按上表关联ID验收。后续按依赖推进并发预算、发现与交接、持续收藏与共享播放、BT/高级流媒体/资源包及远端与扩展。所有组都属于已授权全面升级范围；这是执行排序，不能借优先级把后续能力移出范围。

每个切片应引用上表ID与真实上游版本，写清本地/远端执行位置、成功路径、常见失败和验证方法。评估候选失败时保留能力目标并换可行实现，不把“某库不适合”改写成“能力不需要”。完全没有证据的上游声称先标待核验，不承诺不存在的具体行为；1DM/ADM的精确能力差异、Gopeed发行版逐协议范围与JS扩展契约应补证并更新同一矩阵。

覆盖关闭条件：所有行都有实现/明确不支持且经用户决定/待核验的真实状态；待核验、未实现、未实机、未正式发布均不得被统计成完成。评论未获取部分仍需续补，不能把本次研究33个可见评论条目当全部125条。

## 9. 来源索引（本次研究已研究证据）

- **S01 Motrix**：[主仓](https://github.com/agalwood/Motrix)、[2.3.0](https://github.com/agalwood/Motrix/releases/tag/v2.3.0)。应用/核心/引擎分层、Server、协议、会话与插件；文档与源码，未本次研究实测。
- **S02 Rayburst**：[主仓](https://github.com/AnInsomniacy/rayburst)、[稳定4.1.1](https://github.com/AnInsomniacy/rayburst/releases/tag/v4.1.1)、[媒体契约](https://github.com/AnInsomniacy/rayburst/blob/main/docs/MEDIA.md)。应用MIT，下载引擎另核。
- **S03 aria2-next**：[README](https://github.com/AnInsomniacy/aria2-next)、[Releases](https://github.com/AnInsomniacy/aria2-next/releases)。GPLv2；Android ARM64；不导入旧.aria2、缺FTP/libaria2等兼容边界明确。
- **S04 Rayburst Connect**：[主仓](https://github.com/AnInsomniacy/rayburst-connect)、[过滤源码](https://github.com/AnInsomniacy/rayburst-connect/blob/main/lib/download/filter.ts)、[桌面确认设置](https://github.com/AnInsomniacy/rayburst/blob/main/src/components/preference/Advanced.vue)。MIT应用/扩展不覆盖引擎GPL许可。
- **S05 AB**：[浏览器扩展](https://github.com/amir1376/ab-download-manager-browser-integration)、[主应用](https://github.com/amir1376/ab-download-manager)。主应用Apache-2.0、Kotlin+OkHttp；已读Config.ts与OkHttp下载客户端。集成前固定实际采纳版本并保留具体文件和依赖许可。
- **S06 IDM**：[Options](https://www.internetdownloadmanager.com/support/using_idm/options.html)、[视频面板按站点关闭](https://internetdownloadmanager.com/register/new_faq/video18.html)。闭源产品的官方行为资料，非我方可复制实现。
- **S07 AB配置**：扩展`src/configs/Config.ts`、官方CHANGELOG的1.7非加密HLS及新版扩展要求。参数配置不等于Android行为实测。
- **S08 Gopeed**：[扩展](https://github.com/GopeedLab/browser-extension)、[主应用](https://github.com/GopeedLab/gopeed)、[main go.mod](https://github.com/GopeedLab/gopeed/blob/main/go.mod)、[Releases](https://github.com/GopeedLab/gopeed/releases)。Go框架，main依赖`anacrolix/torrent`与`goed2k`，有JS扩展机制；FTP/HLS的main实现和已发行能力必须分开验证。已读浏览器扩展`options/components/BasicSettings.tsx`、`options/types.ts`、`background/index.ts`。当前发行版的逐协议范围与JS扩展契约仍待补齐。
- **S09 JD账号/执行**：[插件分工](https://support.jdownloader.org/en/knowledgebase/article/list-of-supported-websites-plugins)、[账号规则](https://support.jdownloader.org/en/knowledgebase/article/account-manager-account-useage-rule-disable-free-download)、[NAS运行要求](https://support.jdownloader.org/en/knowledgebase/article/install-jdownloader-on-nas-and-embedded-devices)。官方GPL页面为[GPLv3](https://jdownloader.org/gpl)，具体源码/模块许可还需核对，不能统称整包同许可。
- **S10 JD资源包**：[LinkCrawler](https://support.jdownloader.org/en/knowledgebase/article/what-are-linkcrawler-rules)、[链接容器](https://jdownloader.org/knowledge/wiki/linkprotection/container/dlc-rsdf-and-ccf)、[分卷解包](https://support.jdownloader.org/en/knowledgebase/article/what-shall-i-do-with-rar-files)。旧容器安全宣传不作为当前安全结论。
- **S11 YTDLnis**：[固定源码1278ff0](https://github.com/deniscerri/ytdlnis/tree/1278ff0d10362a0c68d04713ed35caba66134e1e)、[订阅](https://ytdlnis.org/docs/guides/observe-sources)、[模板](https://ytdlnis.org/docs/guides/filename-templates)。GPLv3；Android工程机制未在本次研究安装运行。
- **S12 Seal**：[固定源码7677f61](https://github.com/JunkFood02/Seal/tree/7677f61a20fda4210e84215fef9e9b51d25daa47)。GPLv3；具体复制文件保留上游与许可。
- **S13 gallery-dl**：[官方GitHub迁移公告](https://github.com/mikf/gallery-dl)、[配置](https://gdl-org.github.io/docs/configuration.html)、[命名与后处理选项](https://gdl-org.github.io/docs/options.html)。GPL-2.0；Python3.8+/Requests；Codeberg新上游本次研究读取受限。
- **S14 gallery-dl archive**：[Usage](https://github.com/mikf/gallery-dl/wiki/Usage)、上述配置`extractor.*.archive`/`archive-mode`。稳定ID台账和磁盘文件校验不是同一机制。
- **S15 N_m3u8DL-RE**：[main说明](https://github.com/nilaoda/N_m3u8DL-RE/blob/main/README.en.md)、[Releases](https://github.com/nilaoda/N_m3u8DL-RE/releases)。MIT；main与release版本能力应分开；Android Termux/Bionic不自动等于APK集成完成。
- **S17 1DM**：[开发者商店页](https://play.google.com/store/apps/details?id=idm.internet.download.manager)。仅开发者描述；未本次研究实测、未取得开源实现。
- **S18 ADM**：[开发者商店页](https://play.google.com/store/apps/details?id=com.dv.adm)。仅开发者描述；未本次研究实测、未取得开源实现。
- **S19 流播与Android**：[Media3下载](https://developer.android.com/media/media3/exoplayer/downloading-media)、[CacheDataSource](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource)、[UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt)、[libtorrent流播](https://www.libtorrent.org/streaming.html)、[aria2手册](https://aria2.github.io/manual/en/html/aria2c.html)、[HTTP RFC9110](https://www.rfc-editor.org/rfc/rfc9110.html)。Media3 Apache-2.0、libtorrent BSD风格、aria2 GPL，第三方组件与具体包另核。
- **S20 我方事实源**：[固定main](https://github.com/wozhenbang2004/StreamPocket/tree/e38088e44d88a8a35b787445abac6e177bf176b8)、`DownloadService.java`、`TaskStore.java`、`DownloadTuning.java`、`Engine.java`、`PlayerActivity.java`、`MainActivity.java`、[既有研究](https://github.com/wozhenbang2004/StreamPocket/blob/e38088e44d88a8a35b787445abac6e177bf176b8/docs/UPGRADE_RESEARCH_2026-11-04.md)、[Release](https://github.com/wozhenbang2004/StreamPocket/releases)。以上自有现状来自基线核验只读源码核验，未安装测试。

