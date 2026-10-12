# dshbridge 局域网与 Pixel 10 验证 — 2026-10-10

本轮将 dshbridge 服务端开放到本机 Wi-Fi 局域网，并在 Pixel 10 的原生 Android App 中完成配对、聊天与重启恢复。**dsh 服务端**仍是原生 DeepSeek Harness；**dshbridge 服务端**仍是独立的认证与转发通信壳。职责区分见 [服务端说明](../../architecture/server-roles.md)。本轮未部署公网入口。

## 实际连接

| 组件 | 本轮地址与职责 |
| --- | --- |
| 原生 dsh 服务端 | `127.0.0.1:3080`，负责原生会话、模型和任务；通过内部启动 token / Cookie 认证 |
| dshbridge 服务端 | `https://192.168.10.101:3090`，绑定 Mac 的局域网地址，使用 TLS 和独立设备认证 |
| Pixel 10 | Wi-Fi 地址 `192.168.10.105`，连接 dshbridge，路由明确使用 `wlan0` |

`adb reverse --list` 为空；Mac 的实际 TCP 连接显示 `192.168.10.101:3090 → 192.168.10.105:<port>` 已建立。USB 用于安装与操作手机，App 的通信通过 Wi-Fi 局域网传输。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 手机到 Mac 局域网可达性 | 两次 ping 均成功；目标路由为 wlan0 |
| 专用测试证书 | CA 与 IP-SAN 证书；Python 默认严格 TLS 和主机名校验通过，不信任该 CA 的客户端拒绝握手 |
| 匿名根路径、原生 `/api/session/list`、固定 `/v1/rpc` | 全部 404、正文 0 字节，无 Set-Cookie / Location / Server 头 |
| 原生 dsh 上游认证与 RPC | 内部 token 兑换 Cookie 成功，session/list 成功 |
| Pixel 配对 | 一次性配对码成功兑换，dshbridge 登记一个未撤销的 Pixel 10 设备，App 显示 Connected |
| 聊天收发 | 在单独的 LAN connectivity QA 会话发送测试提示，收到准确的 `LAN_PIXEL_OK`；未要求工具或文件操作 |
| 强制停止并重新打开 App | 自动恢复已保存的局域网连接和同一 QA 会话，无需重新配对 |
| 实际安装 APK | 从手机拉取 base.apk，其 SHA-256 与本轮 LAN APK 一致 |
| 收尾状态 | 原输入法已恢复，测试用明文配对文件已删除，两个服务保留运行 |

[Pixel 重启后的已连接与回复截图](pixel-lan-restored.png)。原始服务端日志、路由映射、认证状态和证书私钥均在仓库外的本机私有目录。

## 本轮 Debug APK 与证书范围

基础源码提交为 `557ef7a88bfaf1d42d0a3ac81e46c08c148fdca9`。仅在构建期间添加 Debug 资源覆盖，让该 APK 信任系统 CA 与本轮专用测试 CA；保持证书和主机名校验，未在手机安装全局 CA。构建成功、APK 签名验证通过，打包 CA 与服务端 CA 公钥字节一致。构建后临时源码资源已移除。

本轮 LAN APK SHA-256：`a744b2054efb21db14b57ceb3ac3ffeeb3b1a4518f87224bbd44d2edebdf6501`。APK 与构建记录留在本机私有部署目录，原交付 ZIP 中的 APK 保持原来的构建版本。后续普通 Debug 构建不自动包含这份临时 CA；本轮证书有效期为签发后 30 天。

初始生成的测试证书缺少 AKI/SKI 密钥标识，已用原钥匙重新签发补齐后通过严格 TLS 验证。手机逐字输入 JSON 时，输入法曾自动改写字段名；改用 mobile-use 的整段文本输入后核对解析结果与服务端配对资料一致，再提交配对。设计与协议源码没有为本轮局域网测试放宽验证。

当前是手动启动的本机局域网服务，未设置开机运行。主机重启或进程退出后需要重新启动；Mac 局域网 IP 改变时需同步更新监听、连接资料与证书。旧 USB 配置离线时出现的“模型凭据”提示属于现有错误分类问题，本轮未修改该界面分支。

## 后续交互验收更新（2026-10-10）

本记录描述初次 LAN 验证时的独立 Web Host 3080。随后为处理 Desktop 任务的实时审批与问答，dshbridge 上游已切换为 Desktop 内置 Host 127.0.0.1:19387，使用严格匹配该 Host 的现有 Cookie；Pixel 更新为 1.1.1-debug，原设备配对保留。最新运行状态和原生交互回传证据见 [交互补齐验收](../interaction-parity/validation.md)。
