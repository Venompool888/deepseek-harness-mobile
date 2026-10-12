# dshbridge 与原生 dsh 服务端联调验收 — 2026-10-10

本轮已实现 dshbridge 服务端与 Android 原生客户端，兼容本机官方 dsh **0.2.0-rc.2**。验证对象是 macOS arm64 上的回环服务和 USB 连接的 Pixel 10；没有发布公网服务。真实随机路径、配对码、设备 token、dsh 登录 token 和认证状态均未放入仓库或交付包。

本文 **dsh 服务端**指原生任务/会话/模型/Web 服务，**dshbridge 服务端**指自研认证与转发通信壳。Go 测试和六种服务端构建产物属于 dshbridge；业务任务与原生协议验证使用 dsh。两者职责、参数及凭据的区分见 [服务端职责说明](../../architecture/server-roles.md)。

## 环境与自动检查

| 检查 | 实际结果 |
| --- | --- |
| Go 1.26.9 darwin/arm64，`go test -race -count=1 ./...` | 24 项测试通过，0 失败 |
| `go vet ./...` | 通过 |
| Gradle 9.4.1、JDK 21.0.12.1，`:app:testDebugUnitTest` | 88 项通过，0 失败、错误或跳过 |
| `:app:assembleDebug` | APK 生成并安装到 Pixel 10 |
| `:app:lintDebug :app:lintRelease` | 0 错误；worktree 分别 76、78 个警告，同步后的原项目分别 77、79 个警告 |
| `CGO_ENABLED=0`、`-trimpath` 六目标构建 | Windows/macOS/Linux，各 amd64/arm64；全部构建成功 |

macOS arm64 的 dshbridge 程序实际运行过。其他五种 dshbridge 程序只完成交叉编译，没有在目标系统运行；Windows 的账户 ACL 也未在这台 Mac 上验证。APK 是 `app.deepseek.harness.mobile.debug`，版本 1.1.0-debug / versionCode 6；原有 release 和旧包数据保留。

同步回原项目后再次通过全部 Go race/vet、88 项 Android 测试、Debug 构建和两种 lint。原目录保留的 `mipmap-anydpi-v26` 资源额外产生一项 ObsoleteSdkInt 警告，本轮没有修改这份既有资源。最终交付 APK 与 Pixel 上安装的 base.apk 通过 SHA-256 一致性核验。

## 协议、认证和入口

- 初始化生成四条独立的至少 128 位随机路径，持久保存在私有 `routes.json`；重启不旋转，缺失或损坏时拒绝启动。路径资料拒绝编码、点段、反斜线、跨 origin 和重复映射。
- 十分钟一次性配对码与 256 位设备 token；dshbridge 服务端只持久保存摘要。并发兑换只有一次成功，持久化失败不返回凭据；独立 CLI 撤销跨进程生效。
- 原始 dsh RPC 信封与 Remote mux 帧通过明确 allowlist 转发；内部 cookie 和外部设备 token 分离。上游固定回环地址，不跟随重定向，不接受客户端提供的目标。
- HTTPS/WSS 集成测试使用可信测试证书进行真实 TLS 握手；无效/撤销凭据与错误路径返回空 404，上游不可用返回桥接错误。产品没有关闭证书校验。
- 本机九组匿名请求：根路径、原生 RPC、固定 RPC、原生 WS、实际随机 RPC/stream/export、畸形匿名 RPC、匿名 WS upgrade，全部为 404 / 0 字节，没有 Set-Cookie、Location 或 Server 头。别名结果保存在 [anonymous-results.json](anonymous-results.json)。`TestAnonymousNeverReachesUpstream` 同时证明匿名请求不会触达上游。
- HTTP 请求体、普通响应、导出与 WS 建立/写入均有边界；慢请求释放名额。截断或超限 ZIP 中止传输，Android 删除部分文件。撤销与 WS 建立并发时重新验证凭据，迟到 socket 不被接纳。

这些是当前程序在本机的行为证据。实际公网 TLS、反向代理、其他转发入口和防火墙尚未部署或扫描；本方案不保证流量分析不可识别。

## Pixel 10 实测

临时只反向转发 3090；dsh 在 Mac 的 3080 回环运行，手机私有模式没有原生接口回退。前台、后台和导出共用私有传输及连接身份。

| 场景 | 实际结果 |
| --- | --- |
| 新设备配对、历史与模型列表 | 配对成功，数据正常加载 |
| 原生发送与流式回复 | 专用 `Private transport Pixel QA` 会话得到 `PRIVATE_PIXEL_OK`，恢复 Connected |
| 停止正在运行的任务 | 原生 Stop 生效，dsh 服务端 running=false |
| 工具批准 | Allow once 后受控临时文件得到准确测试内容 |
| 工具拒绝 | Reject 后任务报告拒绝，目标文件没有创建 |
| Home 后后台完成 | 本包通知从运行状态变为 `Completed · Private transport Pixel QA` |
| 会话 ZIP 导出 | 系统文件选择器写入 Downloads；15900 字节、1 个条目、CRC 正常，包含测试回复 |
| 强制停止与重启 | Keystore 保存的私有连接恢复，无需重新配对 |
| dshbridge 中断与恢复 | 未发送成功的草稿保留；恢复后仍为 4 turns、无待提交消息，没有重复发送 |
| 撤销与重新配对 | 显示设备授权不可用，未进入 Web 登录或直连；新配对恢复同一 QA 会话 |
| 配对码字段 | 真机先发现明文显示，修复后 password=true 且层级中无明文码；禁用 autofill 和输入状态保存 |
| 配对恢复界面 | 已打开的配对表单不会被重复的设备授权提示盖住 |
| 大字体 | 英文深色、系统 150% 字体：标题和模式文字完整，表单可滚动，主操作可到达；模式有明确选中颜色；键盘打开后仍可滚动到 Pair and connect |

截图只含本轮专用测试内容或空白配对字段：

- [聊天成功](pixel-private-chat.png)
- [真实审批卡片](pixel-private-approval.png)
- [150% 字体配对页](pixel-pair-font150-top.png)、[滚动后的主操作](pixel-pair-font150-bottom.png)、[键盘打开时的主操作](pixel-pair-font150-keyboard.png)

测试结束移除临时 USB 3090 reverse，恢复原输入法和 100% 字体，关闭本轮启动的测试服务。手机保存的是本机测试 origin；正式使用需要导入实际部署的 dshbridge HTTPS 配置并配对。

## 独立审查与回归

一次 GPT-6-astra 全分支只读审查覆盖 28493e4..3a9b1e0：0 Critical、7 Important、1 Minor。主实现者完成一次修复轮；没有以第二次审查代替测试证据。七项 Important 均已修复：

| 问题 | 回归证据与处理 |
| --- | --- |
| 排队旧请求或事件回复污染新服务器 | `queuedOldMutationCannotReachNewServer`、`delayedOldPromptHasZeroRequestsAtReplacementCarrier` RED→GREEN；工作/回调持有连接 generation |
| mux 构造与关闭竞态留下迟到连接 | `closeWhileMuxConstructionIsBlockedInvalidatesLateSocket` RED→GREEN；过期连接立即关闭 |
| 忘记、同 origin 重配对、导出及直连表单的身份边界 | `sameOriginRePairAndForgetInvalidateCallbacksAndExportAdmission` RED→GREEN；导出与表单捕获身份，取消失效 |
| 旧后台轮询消费新连接的监视项 | `oldBackgroundPollCannotConsumeNewWatch` RED→GREEN；更新、通知和停止均在身份守卫内 |
| 慢 HTTP 请求/响应占用处理资源 | `TestStalledBodyReleasesHandler` RED→GREEN；`TestStalledExportReleasesRequestSlot` 验证超时后释放名额 |
| 截断导出被当作成功 | `TestTruncatedExportFailsClient`、`abortedChunkedExportDeletesPartialDestination` RED→GREEN；超限导出同样中止 |
| 直连 200 HTML 登录页被误判普通错误 | `directHTMLResponseRequestsAuthentication` RED→GREEN，恢复登录分类 |

额外竞态：`TestRevokeDuringUpstreamWSHandshake` 先重现撤销设备仍被接纳，再修复为握手完成后重验、15 秒建立上限。最终 Go race/vet 与 Android 整套检查通过。

审查时未完成的真机停止、拒绝、后台完成、撤销和中断恢复已由上面的真机测试补齐。公网部署、目标 OS 运行、完整 TalkBack 遍历和更多设备仍是明确限制，见下面保留决定。

## 决定与保留项

1. **Ruling：复用 managed worktree 并保留原工作区。** 同步前逐文件与 28493e4 产品基线比较。成本：并发修改的文件必须拒绝覆盖，另行处理。
2. **Ruling：撤销通过持久认证状态加载和 250 ms 空闲 WS 检查生效。** 不新增跨进程 IPC。成本：空闲连接可能多存活约 250 ms；已经被 dsh 接纳的操作无法靠撤销回滚。
3. **Ruling：所有支持的 Android 版本统一用 Storage Access Framework 导出。** 成本：API 29+ 也需要多一步选择目的地。
4. **Ruling：公网域名/TLS 代理/防火墙/外部扫描留在实际部署阶段。** 成本：本轮交付不能代表公网环境已经验收，也不作不可识别承诺。
5. **Ruling：macOS arm64 实跑，其余五目标只交叉编译。** 成本：目标系统启动、Windows ACL 等仍需部署验收。
6. **Ruling：本轮验证原生英文深色的普通/150% 字体，以及 AGY 英文浅色 150% 预览；完整 TalkBack 与更广设备矩阵延期。** 成本：仍可能需要无障碍或特定设备界面修正。
7. **Ruling：流量分析抗性、多用户托管、出站中继、安装器和进程守护不在本轮。** 成本：部署运行管理需要后续工作。
8. **Minor (deferred)：HarnessRemoteConnection companion 中未使用的 OkHttpClient。** 实际连接使用 harnessStreamClient；成本：保留一处无行为影响的冗余定义。

## 设计与交付

连接页设计使用用户指定的 [agy-subagent 技能](/Users/yuri/.codex/skills/agy-subagent/SKILL.md)。AGY 在隔离目录交付 [设计说明](../../superpowers/designs/private-transport/agy-design.md) 与 [交互预览](../../superpowers/designs/private-transport/agy-preview.html)，没有修改 dshbridge 服务端或 Android 协议代码。最终结果核验 SUCCESS、0 step errors、无 denied actions；任务专属权限已移除。

程序与 APK 是本地构建交付。使用 [bridge/README.md](../../../bridge/README.md) 中的 init / pair / serve / devices / revoke 命令维护。私有 state 必须独立保存并备份；交付包不含任何测试 state。保留 `codex/private-transport` 分支和 worktree，没有自动 merge、push 或公网发布。

八项任务的提交范围、回归结果及全部保留决定见 [execution-record.md](execution-record.md)。
