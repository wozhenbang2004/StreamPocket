# 界面组件与 Relay 适配

## 采用范围

拾流继续由 Java / Android Views 渲染。Relay 本次核对的前端由本地 WebView、React / React DOM 19.2.6、Affect UI、Vaul 1.1.2 / Radix 和 Lucide React 0.468.0 组成。两者不是同一个 UIKit；拾流的 Pocket 控件也不是 React 库的 Android 包装。

本次从 Relay 实际采用的组件原码与覆盖样式出发，将它的中性色表面、稳定的分段标签、移动选中底片、按压反馈和弹层参数适配到已有 Android 控件。这样可以统一新建、下载、视频库、设置与播放器菜单，同时沿用现有输入法、下载服务、数据状态和播放接口。

## 来源与对应实现

| 来源 | 留存材料 | Android 适配与边界 |
| --- | --- | --- |
| [Affect UI](https://github.com/flohoeller/affectui/tree/37b91fa302dea59e28ce1eb2ba7acd7ce23296be)，commit `37b91fa302dea59e28ce1eb2ba7acd7ce23296be`，MIT | [button.tsx](design/upstream/affectui/button.tsx)、[segmented-control-clean.tsx](design/upstream/affectui/segmented-control-clean.tsx)、[许可证](design/upstream/affectui/LICENSE) | `PocketButton`、`PocketTabs` 与 `UiTheme`。沿用原组件形态及 Relay 的中性色覆盖；不包含原按钮全部文字 blur 或内容宽度变形效果。 |
| Vaul npm 1.1.2，MIT | 原包 [style.css](design/upstream/vaul/style.css)、[许可证](design/upstream/vaul/LICENSE.md) | `PocketDialog` 采用底部进入/退出、500ms 曲线、手机顶部圆角与轻遮罩。保留 Android Dialog 回调、输入和取消语义；仅标题区域支持拖动，不宣称实现完整 Vaul 的后景缩放、速度判定、嵌套滚动仲裁或弹层栈。 |
| Lucide React 0.468.0，ISC | 原包提取的 [30 个图标节点](design/upstream/lucide/icon-nodes.json)、[许可证及 Feather 署名](design/upstream/lucide/LICENSE) | `PocketIcon` 使用真实节点转换出的 Android Path；导航、视频库及播放器菜单共用同一组路径。 |

原 TSX / CSS 仅作可追溯的源材料，不参与 APK 运行。运行时没有增加 React、Vaul、WebView 前端或新的 UI 依赖。开关、单选、多选等继续采用现有原生语义，由 Pocket 控件绘制；其统一外观不等于来自 Affect UI 的同名组件。

## 设计与交互规则

浅色页面使用 `#F7F7F7`，内容表面 `#F0F0F0`，浮起的选中面 `#FFFFFF`，主文字 `#252525`，次文字 `#6D6D6D`。深色页面使用 `#141414`，内容表面 `#202020`，浮起面 `#303030`，主文字 `#F4F4F4`。具体值集中在 `UiTheme.Palette`，普通控件不使用绿色强调色。

常驻装饰描边移除；输入聚焦、键盘焦点和选择标记保留有意义的轮廓。弱阴影只用于导航选中面、分段底片和浮层，不给每个内容卡片加阴影。导航文字与分段标签明确位于底片上方，防止 Android elevation 遮挡内容。

| 交互 | 参数与行为 |
| --- | --- |
| 按钮 | 12dp 圆角，Android 常用操作保留至少 48dp 高度；按压缩放至 0.97，160ms、曲线 `(0.22, 1, 0.36, 1)`。UP、CANCEL、脱离窗口和按住期间禁用均恢复状态。 |
| 分段筛选 | 14dp 灰轨，内侧 4dp，10dp 选中面；标签始终 medium 字重。前缘 160ms、后缘 220ms，曲线 `(0.25, 0.9, 0.3, 1)`。依据实际标签边界布局，兼容 RTL 和宽度变化。拖动在 UP 提交，CANCEL 回原选择。 |
| 页切换 | 5dp 上移归位与淡入，160ms；下载状态轮询不重复播放页面进入动画。 |
| 弹层 | 手机底部贴边，仅顶部 24dp 圆角；平板宽度最多 580dp。遮罩 alpha 0.22；进出和拖动回弹 500ms、曲线 `(0.32, 0.72, 0, 1)`。 |

动效沿用应用开关及系统动画设置。禁用清理、可访问性点击、键盘操作和真正的数据提交不能依赖动画完成事件。

## 页面结构

新建页的解析结果卡片把封面与作品信息放在上部，画质和下载动作放在整行底部，避免长画质名称被封面挤成窄列。可选附件保留在卡片下方。解析中只显示真实的不定进度提示，不生成虚假的完成百分比。

下载页保持筛选控件和滚动容器挂载，只更新标题控制区和任务区，避免刷新拆掉正在运动的底片。选择状态、筛选、任务次序、恢复和移除记录的原业务规则不变。

设置项采用标题、说明和尾部入口的层级，说明在标题下方获得可用宽度。视频库保持现有二列/三列/列表及筛选能力，统一操作图标、表面和选择反馈。播放器本次只统一中性色及共享菜单控件，不改媒体链路。

## 维护与验证

`PocketIcon.java` 由 `python tools/generate_ui_icons.py` 从保留节点生成；重生成需要开发环境中的 `fonttools==4.61.1`，APK 不依赖该工具。不要手工改生成路径或用同名自画图案覆盖它。

`RelayControlsTest` 覆盖按住期间禁用、取消、重新启用、RTL / 宽度变化、键盘、开关透明度及多选的实际点击语义。`TaskQueueUiTest` 覆盖筛选宿主的持续挂载；既有画质、下载队列、输入、窄屏、大字体和播放器测试继续执行。

原 CI 执行 `testDebugUnitTest lintDebug assembleDebug assembleRelease` 和 Android 模拟器媒体回归。`WhiteUiRenderTest` 等输出到 `app/build/ui-render`，下载队列和视频库输出到现有 view-fixtures；这些当前代码的原生图形渲染使用离线样例，并非已安装手机的截图，也不证明源站实时解析/下载可用。最终构建与验收结果以对应 PR 的检查和产物为准。
