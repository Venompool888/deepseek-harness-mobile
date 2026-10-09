# Android 与 dsh rc.2 交互补齐验收

日期：2026-10-10（Australia/Brisbane）。用户报告 Desktop 显示 Waiting for approval，而 Pixel 没有对应窗口，要求用 Luna 探索并补齐交互。调查、协议实现与审查代理均为非 Astra `gpt-6-luna`。UI 设计委托 agy，整合记录见 [agy-design.md](../../superpowers/designs/interaction-parity/agy-design.md)。

## 原因与修复

Android 曾只接收当前选中 Session 的普通 approval，每个 Session 仅保存一条请求，其他来源被交给下一答复者；没有前台问答、计划确认、继续问答的原生面板。现在按事件 ID/稳定 call ID 分别缓存请求，保留所有会话，提供数量、切换和各自草稿。其他会话或子 Agent 有待处理请求时，页头下方显示醒目入口；点击打开侧栏的待处理分组。该提示独立刷新，不重建当前问答卡，不自动选择或回答。

局域网 dshbridge 原先连接独立 Web Host `127.0.0.1:3080`，截图任务却运行在 Desktop Host `127.0.0.1:19387`。实时 waterfall 不在两个 Host 之间共享；修复客户端之外还要让 bridge 连接拥有该任务的 Host。本轮新增严格绑定 authority 的 `--dsh-cookie-file`，支持已有 Desktop Cookie，同时保留 launch-token 模式。手机使用独立的 dshbridge 设备凭据，原生 Cookie 不发给手机。

子 Agent 的 `agentId` 是子 Session ID，但 history/follow/page 必须使用目录记录的父 ID 和 `kind:"subagent"` 地址。客户端缓存 `origin`/`parentSessionId`，不猜父身份。未解析来源会保留请求并刷新目录；子 Session 页仅展示任务和审批。列表点击采用最新目录对象，防止占位行丢失子身份。

Pixel 超时测试发现 attachWait end 与原 waterfall cancel 会先于异步 `next` 返回，导致未捕获 stale-result 异常杀死进程。新增真实 mux 驱动的单元回归测试先复现旧问题，再验证后台捕获异常、按代清理及 continued 项保留。

## 原生交互范围

| 原生来源 | Android 界面与响应 |
| --- | --- |
| `approval/request` | 本地化原因、关联调用命令、拒绝/允许一次；`$events/result` 的 `result.value` 分别为 `rejected`/`allowed-once` |
| `user-questions/request` | 单选、多选、选项说明、每题自由输入、多题完整提交；waterfall result 为 `{answers:[{id,selected,custom?}]}` |
| question `intent.kind:"plan-review"` | 计划正文与服务端选项，明确选择后提交决定；保留 `intent.approve` 标签，未默认批准 |
| question `wait:{callId,timed:true}` | `userQuestions/attachWait` 的 remainingMs 倒计时；不自动提交；等待结束后按服务端投影转 continued |
| projection `userQuestions.active` 的 continued | 可隐藏/重开，保留草稿，通过 `userQuestions/answer` 回答；嵌套 RemoteResult false 报告已失效 |
| waterfall cancel / 用户取消 | Host cancel 仅移除相应 eventId；前台问答用户取消发送 `ASK_CANCELLED`，continued 隐藏不远程取消 |
| 多请求/多会话/重连 | 独立关联、独立提交状态；新 clientId/代重放，旧响应不能结算新代请求 |
| 子 Agent 审批 | 原始 Agent ID 关联、合法子 Session 地址、只读任务页面和审批面板 |

rc.2 remotes 明确只转发两个上述 waterfall。原生提供者自行处理的 unattended app-server approval/MCP elicitation 没有对应外发 waterfall；本轮没有虚构另一套客户端响应接口。已有登录、模型配置、权限、错误等界面继续使用现有流程。

## 证据范围

合成上游只监听 `127.0.0.1:3082`，不执行工具、不联系真实 dsh、不读取凭据。通过临时 dshbridge 上游切换，在 Pixel 与 Mac 的真实 Wi-Fi 链路上注入 rc.2 形状的事件并核对实际点击回传。模拟能验证 UI、封装、路由与关联；不能单独证明真实 dsh 每种事件的生命周期。因此另保留隔离空工作区中的真实 Desktop QA Session，并将生产上游切回 Desktop Host。

手机仍通过 `192.168.10.105 → 192.168.10.101:3090` 的 HTTPS/WSS 连接。没有 ADB reverse。临时 LAN APK 仅额外信任此部署专用 CA；没有安装系统 CA，没有不安全 TrustManager/hostname verifier。专用 CA Overlay 打包后移除，不提交到源码。

## 实际验收结果

| 检查 | 结果与范围 |
| --- | --- |
| Android 单元测试 | `:app:testDebugUnitTest --rerun` 实际执行；23 suite / 96 tests，0 failure / 0 error |
| Android lint 与构建 | `:app:lintDebug :app:assembleDebug` 成功；lint 0 error、85 warning；本轮不扩展为全面警告整改 |
| Go | `go test -race -count=1 ./...` 所有 5 个包通过，`go vet ./...` 通过；使用本机临时 Go 工具链 |
| Pixel 安装 | `app.deepseek.harness.mobile.debug`，versionCode 7，`1.1.1-debug`；覆盖安装保留原设备配对 |
| 普通审批与多会话 | 合成同会话/不同会话请求保留；允许一次、拒绝分别回传到正确 eventId，没有额外自动回答 |
| 普通问答 | 单选、多选+custom、纯 custom、多题提交、未答完阻止提交、取消 ASK_CANCELLED，通过；载荷见 outcomes-initial.json |
| 计划决定 | 提交 Approve plan；最终包提交 Request changes；1.5 倍字体触发 Activity 重建后已选决定保留，按钮可操作 |
| 审批重放 | 最终包强制停止/重开，新的 clientId 收到同一待处理 event 并成功允许一次 |
| timed → continued | 显示倒计时；超时切换、late cancel、隐藏/重开和 service answer 均通过；已选 Continue 保留；切换前后同一进程 PID 14681 |
| 子 Agent | 合法子地址打开、命令关联、Reject 回传到 child ID，解决后仅显示只读说明 |
| 其他会话提醒 | 最终包在 A 页收到 B 审批，页头显示 1 条并打开 B；随后在 B 页收到 child 审批，同样显示入口并打开 child。两次 Reject 均匹配正确 sessionId/eventId，解决后提醒隐藏；见 outcomes-attention.json |
| 真实 Desktop 问答 | 隔离 QA Session 收到原生 ask_user_question，Pixel 提交“验收通过”，原生任务复述该选择并结束 |
| 真实 Desktop 审批 | 同一 QA Session 请求只打印文本的 Bash escalation；Pixel 显示真实审批说明/命令，点击 Reject，原生任务确认拒绝且未重试、未执行 |
| 真实 LAN 链路 | 最终 dshbridge PID 58111 的已建立 TCP peer 为 192.168.10.105；上游为 Desktop 127.0.0.1:19387；ADB reverse 列表为空 |

合成记录第一阶段运行于首次面板包，1.1.1 面板包又复测了审批重放、子会话、plan change、字体/Activity 草稿恢复和 timed→continued 竞态，并在真实 Desktop 上验证问答与拒绝审批。最后只增加其他会话提醒，再实际重跑全部 96 项单测、lint、构建并验证根会话及子会话提醒；覆盖安装后已连回真实 Desktop QA 会话。计划确认、多选及 timed 的真实 Host 发出/调度行为未逐一重新触发，覆盖来自 tagged 源码、单测与注入测试。

真实 QA 会话名为 `Android 交互验收 2026-10-10`，空工作区位于本机临时验收目录。未审批用户截图中原有任务。专用合成进程已停止；真实 Desktop 原有进程没有停止或重启。手机最后停在真实 QA 已完成会话，字体恢复为 1.0，输入法保持原微信输入法。

## 交付与运行状态

局域网 dshbridge 保持运行在 `https://192.168.10.101:3090`，使用原设备配对和随机入口；上游是原生 Desktop Host `http://127.0.0.1:19387`。没有正式公网部署或开机启动。固定 Desktop Cookie 失效时需要管理员更新私有 Cookie 文件并重启 dshbridge，不会匿名降级。

本机交付目录为项目的 `artifacts/interaction-parity-1.1.1/`（忽略，不提交），包含 LAN 专用 Debug APK、仅系统 CA 的普通 Debug APK和本机 macOS arm64 dshbridge。`final-checks.json` 记录精确版本、测试计数和 SHA-256；LAN APK 的 SHA-256 为 `96a040c445acb85b998f87027d973035dfc1f046928b0109b3ae0f24d69a2f3f`。这些 APK 使用已有 Debug 签名，本轮未制作正式签名发行版。

当前私有服务状态、Cookie 与证书保存在用户 Library 下的 dshbridge 部署目录，未放入仓库。专用 CA debug overlay 已删除；源代码的正式信任配置只含系统 CA。`fixture-upstream.mjs`/`fixture-README.md` 可用于本地复现合成场景，任何 QA admin route 都不在 bridge allowlist。

## 截图与原始记录

- [真实 Desktop 审批面板](pixel-native-approval.png) 与 [真实问答](pixel-native-question.png)，[原生任务确认回答/拒绝](pixel-native-result.png)。
- [continued 面板](pixel-continued-final.png)、[子 Agent 审批](pixel-child-approval.png)、[子 Agent 只读](pixel-child-read-only.png)、[大字体计划面板](pixel-plan-large-font.png)。
- [其他会话待处理提醒](pixel-other-session-attention.png)、[最终包连回真实 Desktop](pixel-final-desktop.png)。
- 合成实际响应载荷：[第一阶段](outcomes-initial.json)、[最终包](outcomes-final.json)。
- [最终提醒入口响应载荷](outcomes-attention.json)。
- [构建与测试计数/校验和](final-checks.json)、[Android 执行日志](android-final.log)、[Go race 执行日志](go-final.log)。

代码交付分支 `codex/android-interactions-rc2`。dshbridge 源码提交 `42fd791`，Android 面板提交 `737422c`，提醒入口提交 `79f7852`；交付 APK 使用与该源码逐文件一致的已测试实现。
