# Android 交互面板：agy 设计整合记录

2026-10-10。设计委托遵循 Yuri 指定的 `agy-subagent` 技能，执行 Codex 子代理为非 Astra `gpt-6-luna`，agy 会话 `515fe41f-6ac7-4adc-ae93-cfef5d3fbd0e`。原始设计草案保存在本机 `/tmp/dsh-interactions-agy-20261010/design/design.md`。本文件记录实际采纳的设计与协议校正；原始草案中的网络描述不能代替原生源码。

## 采纳的界面设计

- 延续现有 Android Views 界面，在聊天页底部的输入区位置呈现待处理面板。
- 审批使用醒目的琥珀色标题与边框，展示原生说明；只有关联 `callId` 的历史调用才显示命令。底部固定“拒绝”和“允许一次”。
- 问答与计划确认使用蓝色标题，完整显示问题、说明、选项说明和自定义文本输入。单选与多选采用各自的原生控件，不预选答案。
- 每批问题必须回答完整后一次提交；未回答的部分有行内提示，草稿保留。
- 主体有独立、限定高度的滚动区域，操作按钮位于滚动区域外。输入法展开时按当前可见窗口高度缩小正文区域。
- 同一会话的多个请求用前后按钮切换，显示序号与总数；会话侧栏提供待处理分组和数量。每个请求保留自己的草稿与提交状态。
- 提交中禁用该请求的控件；错误保留草稿并允许重试。远程解决或取消只移除对应请求。
- continued 问题可选择稍后回答，并在聊天页保留重开入口。限时问题只显示倒计时，时间到达不自动批准或提交。
- 触控控件至少 48 dp，文字使用 sp；支持现有深色、浅色主题与系统字体缩放。

## 依据源码校正的协议事实

原生 rc.2 通过 WebSocket `remote.mux` 的 `$events` 流转发两个 waterfall：`approval/request` 和 `user-questions/request`。计划确认是 question 的 `intent.kind = "plan-review"`，不是第三种 RPC。面板保留服务端的选项标签及 `intent.approve`，提交按钮表示“提交计划决定”，不会默认选择批准。

普通审批和前台问答的响应是 `$events/result`：审批的 `outcome.value` 为 `allowed-once` 或 `rejected`；问答的结果为 `{answers:[{id,selected,custom?}]}`。取消前台问答发送 `ASK_CANCELLED` rejection。只有投影中 `state = "continued"` 的问题才调用 `userQuestions/answer`，携带 `agentId`、`callId` 和 `answer`；返回的嵌套 RemoteResult 为 false 时不能报告提交成功。

限时问题通过 `userQuestions/attachWait` 流取得 `remainingMs`，该流结束表示等待结束。回答结果不会从这个流下发，而是通过独立的 `$events/result` 回传。continued 面板的隐藏只作用于本地。

`agentId` 是请求 Agent 的 Session ID。子 Agent 的读取地址需要目录中的父身份：`kind = "subagent"`、`parentSessionId`、`childSessionId`、`mode = "unknown"`。客户端不猜父身份。子会话页面用于查看任务与处理审批，根 Agent 的聊天与控制操作不在子页面开放。

源码依据：dsh `dsh-v0.2.0-rc.2` 的 `packages/api/remotes/src/remote-events.ts`、`packages/interaction/user-questions/src/{index,types}.ts`、`packages/api/session-controller/src/{types,history,list}.ts`；实现见本仓库 `HarnessApi.kt`、`HarnessInteractions.kt`、`HarnessSessionAddress.kt` 和 `MainActivity.kt`。

## 委托与权限收尾

agy 只负责设计，没有修改产品源码。设计输出经父代理按原生协议审阅和整合。临时文件权限限定到设计输入与输出目录，设计完成后移除了本轮新增的 permission 规则，保留原有用户设置。
