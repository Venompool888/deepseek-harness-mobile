# rc.2 Web 源码视觉校准验收

日期：2026-10-10（Australia/Brisbane）。用户要求深入理解 Web 前端，精准还原小图标、按钮与样式。本轮 Android 源码提交为 `dcf3c18`，基线为 `9bfa330`；交付版本 `1.1.2-debug` / versionCode 8。前一轮完整交互适配的历史证据仍在 [interaction-parity](../interaction-parity/validation.md)，本文件只记录这轮视觉改动和实际复测。

## 源码与设计依据

参考对象是官方 `dsh-v0.2.0-rc.2` 完整 tag archive，SHA-256 为 `c126f2f5dc56820e62d07e52eba6455cc20fffb379fc876626993452d86d4010`。定向阅读了 Web 启动/插件、renderer/slot、主题、Button、SVG、conversation composer、消息动作、审批与用户问答组件；没有声称逐行审查整个仓库。深度导读、来源链接和 Android 映射见 [dsh-web-frontend.md](../../architecture/dsh-web-frontend.md)。

本地 rc.2 Web `127.0.0.1:3080` 的 DOM computed style 用于核对 CSS cascade 后的颜色和控件尺寸，记录见 [runtime-observations.json](runtime-observations.json)。该 Web Host 只作视觉参考；手机最终通过 dshbridge 连接实际拥有任务的 Desktop Host `127.0.0.1:19387`。

设计按照用户要求通过 agy-subagent 交给 `gemini-3.1-pro-high`。Luna 做源码调查、限定实现和独立审查；所有 Codex 子代理均为非 Astra `gpt-6-luna`。Gemini 成功读到了 Web 关键组件，没有读 Android，主代理结合 Android 源码、运行时样式和 Pixel 交叉核实。原始意见及未采纳建议见 [provenance.md](../../superpowers/designs/web-visual-fidelity/provenance.md)。

## 本轮改动

| 范围 | 落地内容 |
| --- | --- |
| 15 个 drawable | 用 rc.2 SVG 原始几何校准侧栏、新会话、搜索、设置、加号、复制/完成、赞/踩及其选中态、分支、Think、发送和 Stop；保留 16 单位 viewport 与 1 / 1.3 笔画 |
| Dark palette | bgBase `#151517`、composer `#2c2c2e`、主按钮 `#f9fafb`、发送蓝 `#7aaaff` 与警示色分别映射到 Android 语义角色 |
| Composer | 外卡 28dp 圆角；48dp 点击格内绘制 28dp add 圆与 34dp send 圆；白色 16dp send glyph；禁用整体 alpha .4 |
| Approval / question | 20dp 卡片、composer 底色；approval 警示 strip/border；按钮 12dp 圆角、disabled .4、ripple；保留原点击和响应逻辑 |
| 消息动作 | 8dp 视觉圆角，赞/踩的已选中态使用对应 fill 图标；48dp 命中格保留 |

[icon-source-manifest.json](icon-source-manifest.json) 记录每个 drawable 的来源、尺寸、笔画和内容哈希。[图标对照页](icon-comparison.html) 可切换明暗主题，按 1×/4×/6× 检查十组代表性图标，另保留 [改动前对照](icon-comparison-before.html)。右栏由 Vector XML 投影为 SVG，只证明静态几何对照，不替代 Android 实际 raster、tint 和触控验证。

图标源为 MIT 授权，app 中的 `assets/licenses/deepseek-harness-MIT.txt` 保留上游许可。两个 APK 都已检查 ZIP 内含此文件；本页和对照图的衍生来源许可见 [upstream-LICENSE.txt](upstream-LICENSE.txt)。

## 构建与独立审查

在隔离 worktree 实际运行 `:app:testDebugUnitTest --rerun :app:lintDebug :app:assembleDebug --console=plain`，Java 使用本机 OpenJDK 21。结果是 23 suite / 96 tests，0 failure / 0 error；lint 0 error、84 warning；构建成功，51 个任务均实际执行。精确计数见 [final-checks.json](final-checks.json)，执行日志见 [android-build.log](android-build.log)。许可证加入后又正常 assemble 并制作实际安装包；没有把测试 UP-TO-DATE 计作执行。

独立 Luna 审查 `9bfa330..dcf3c18`，检查源 SVG/Vector、InsetDrawable 与图标居中、Send/Stop 状态、选中反馈和 palette，未发现有证据的 P1/P2 缺陷。该审查没有运行 Pixel 或 Gradle；本页的实际设备与测试证据由主代理获取。本轮没有改 bridge 或协议，未重新宣称 Go 验证；上一轮 Go race/vet 结果保留在历史验收。

## Pixel 实机结果

设备为连接的 Pixel 10。覆盖安装 `app.deepseek.harness.mobile.debug`，PackageManager 确认 `1.1.2-debug` / code 8，保留原配对。手机通过 `192.168.10.105 → 192.168.10.101:3090` 的 Wi-Fi HTTPS/WSS 连接；ADB reverse 列表为空。

| 检查 | 实际结果与证据 |
| --- | --- |
| 真实 Desktop 聊天 | 打开隔离验收会话，复制/赞踩/分支、Think、新建会话及 sidebar 图标正常显示；[正常聊天](pixel-dark-chat.png) |
| Send 空/有草稿 | 空输入呈 .4 透明度，有草稿呈蓝底白箭头；输入仅限 QA 草稿，没有发送，检查后清除；[草稿](pixel-keyboard-draft.png) |
| 软键盘 | 微信输入法打开时 composer 位于键盘上方、send/add 可见；[键盘打开](pixel-keyboard-visible.png) |
| Commands | 从更新后的 add 按钮打开实际服务返回的命令菜单，再关闭；未执行菜单项；[菜单](pixel-commands.png) |
| Dark approval | 合成 pending 事件显示 strip、底色、边框、关联命令和两枚按钮；点击 Reject，回传 rejected 并匹配原 eventId；[审批](pixel-approval-dark.png) |
| Dark / Light question | 两种主题均可见完整选项/自由输入和提交按钮；Light 选择 Option B、提交结果匹配 eventId；[Dark](pixel-question-dark.png)、[Light](pixel-question-light.png) |
| Light approval | 浅色背景、警示 strip、黑色主按钮可读；Reject 回传成功；[审批](pixel-approval-light.png) |
| 150% 字体 | Dark 审批原因/命令可换行，按钮保持可见并点击 Reject 成功；[大字体](pixel-approval-dark-large.png) |
| 最终恢复 | 字体 1.0、Dark、原微信输入法；冷启动后连回真实 Desktop；[最终页面](pixel-final-desktop.png) |

合成上游仅监听 loopback `127.0.0.1:3082`，不执行工具、不读取真实任务。共四次实际响应（三次 Reject、一次 Option B）均 `matchedPending:true`，最终无 pending，见 [outcomes.json](outcomes.json)。合成事件验证 UI 和回传；本轮没有在真实 dsh 重新触发所有问答类型，上一轮真实审批/问答及更多合成类型的记录仍是历史证据。

第一次 Commands 截图碰到输入法临时剪贴板浮层，未把它算作成功；随后重新打开并通过新 UI dump 与截图确认真正命令菜单。大字体也在 Activity 重建后重新获取 UI，确认控件及文字实际放大。仅保存隔离 QA 会话截图，没有把其他任务正文提供给 Gemini。

最终 dshbridge PID `76177`，上游是原有 Desktop Host `127.0.0.1:19387`，TCP 已建立连接的 Wi-Fi peer 为 `192.168.10.105`。合成上游和临时图标 HTTP 预览服务已停止，手机临时 UI dump 已删除，agy 任务权限已移除。dsh Desktop 原有进程没有重启。两个服务端的职责继续见 [server-roles.md](../../architecture/server-roles.md)。

## 交付与边界

本机 `artifacts/web-visual-fidelity-1.1.2/`（忽略，不入库）保存 LAN 专用 Debug APK、仅系统 CA 的普通 Debug APK和精确校验信息。LAN APK SHA-256 为 `6e3865429449d15206044b951a9af0a460e915df2a47db71ee4e97423933a268`；普通 APK 为 `29e26886bcb608c07a9b7bcea5cfdc404fdb6335f54303f43a8b5631612e770c`，详见 [apk-hashes.json](apk-hashes.json)。仍使用已有 Debug 签名，本轮没有正式签名发行或公网发布。

LAN APK 仅额外信任现有部署专用 CA，没有装系统 CA、绕过证书或主机名校验；临时 debug CA overlay 已从源码删除。普通重新构建不包含该 CA。原 1.1.1 本地交付仍保留作回退，旧验收未重写成这轮结果。

本轮完成的是指定源码组件的深入分析和高辨识度视觉元素校准。整体布局、字体/行高、所有弹窗/菜单、阴影、hover/focus/pressed 动画、全部边框透明度及 Android 像素 raster 没有逐项达到 Web 逐像素一致；drawer 个别紧凑点击格尚未统一 48dp。已经验证的键盘、字号和主题功能也不等同于对应区域的逐像素还原。
