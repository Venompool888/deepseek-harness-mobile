<p align="center">
  <img src="design/android-whale-logo-white.png" width="260" alt="DeepSeek Harness Mobile logo">
</p>

<h1 align="center">DeepSeek Harness Mobile</h1>

<p align="center">
  一个连接自托管 DeepSeek Harness 的 Android 客户端。
</p>

<p align="center">
  <a href="https://github.com/Venompool888/deepseek-harness-mobile/releases/latest"><strong>下载最新版 APK</strong></a>
</p>

## 不只是聊天：在手机上看着 Agent 干活

原生 Android 界面会持续呈现 Harness 的真实执行过程，而不是只留下一个等待动画：

- 实时展示 **Think、Write、工具调用、回复内容和运行时长**。
- **To-dos** 汇总已完成、进行中和待处理步骤，长任务进度一眼可见。
- 上下文仪表显示使用比例，并可展开查看 System prompt、Tools 与 Messages 占用。
- 切到后台后，系统通知仍会显示 Harness 任务正在运行，方便随时返回会话。
- 在支持的 OPPO / ColorOS 设备上，可通过 **流体云** 快速查看当前任务状态。

<p align="center">
  <img src="docs/images/showcase/live-agent-progress.png" width="310" alt="在 Android 原生界面实时查看 Agent 的思考、写入、待办与运行时长">
  &nbsp;
  <img src="docs/images/showcase/context-and-todos.png" width="310" alt="查看 Harness 上下文占用明细和已完成的任务清单">
</p>

<p align="center">
  <sub>实时执行过程、上下文压力与任务清单，都集中在同一个移动会话里。</sub>
</p>

<p align="center">
  <img src="docs/images/showcase/background-task-notification.png" width="520" alt="Android 系统通知持续显示 DeepSeek Harness 后台任务运行状态">
</p>

<p align="center">
  <sub>离开应用也能看到后台任务状态。</sub>
</p>

### ColorOS 流体云

已在 OPPO CPH2797（ColorOS 16）实机显示胶囊态与展开卡片态，无需打开应用即可确认 Harness 任务仍在运行。

<p align="center">
  <img src="docs/images/showcase/coloros-fluid-cloud-capsule.jpg" width="620" alt="DeepSeek Harness 任务在 ColorOS 流体云中的胶囊态">
</p>

<p align="center">
  <img src="docs/images/showcase/coloros-fluid-cloud-expanded.jpg" width="620" alt="DeepSeek Harness 任务在 ColorOS 流体云中的展开卡片态">
</p>

<p align="center">
  <sub>胶囊态快速扫一眼，展开后查看任务标题与运行状态。</sub>
</p>

## 安装

本流程已在 **OPPO CPH2797（Android 16 / ColorOS 16）** 上实际验证。

1. 在手机 Chrome 中打开 [Latest Release](https://github.com/Venompool888/deepseek-harness-mobile/releases/latest)。
2. 找到 **Assets**，点击 `deepseek-harness-mobile-v1.1.0.apk` 下载。
3. 下载完成后打开 APK，并按 ColorOS 的安装提示继续。如果系统阻止安装来自浏览器的应用，请按系统提示临时允许当前浏览器安装未知应用，然后返回继续安装。
4. 安装完成后打开 **DeepSeek Harness Mobile**。

<p align="center">
  <img src="docs/images/oppo/01-github-release-apk.png" width="320" alt="在 OPPO 手机上从 GitHub Release 下载 APK">
</p>

> 当前 v1.1.0 APK 的 SHA-256：`e026a373b3a9359293e3daeced3cfa08a0df109dd91d4d489a1f37f737c54fde`

## 首次连接

首次打开时，应用不会预置任何服务器地址：

1. 输入你自己的 Harness 服务器地址，例如 `https://harness.example.com`。
2. 点击 **测试并连接**。
3. 如果服务器启用了 Cloudflare Access，请在出现的登录页中完成服务器所有者配置的验证方式。
4. 如果 Harness 显示 **Internal Testing Notice**，点击 **Continue**。
5. 返回客户端后，顶部出现 **已连接** 即表示配置成功。

<p align="center">
  <img src="docs/images/oppo/02-first-launch.png" width="320" alt="首次打开时配置 Harness 服务器">
  &nbsp;
  <img src="docs/images/oppo/optional-harness-notice.png" width="320" alt="Harness Internal Testing Notice">
</p>

<p align="center">
  <img src="docs/images/oppo/03-connected-session.png" width="320" alt="连接成功后的原生会话界面">
</p>

实测中，强制停止并重新打开应用后，服务器配置、登录状态和当前会话均能保留。

## 更换服务器

打开左侧边栏，点击底部设置菜单中的 **服务器连接**，即可测试并切换到其他内网 IP 或域名。

<p align="center">
  <img src="docs/images/oppo/04-settings-menu.png" width="520" alt="从侧边栏设置菜单进入服务器连接">
</p>

## 连接要求

- Android 8.0（API 26）或更高版本。
- 手机必须能够访问你的 Harness 服务器。
- 公网服务建议并默认要求使用 HTTPS。
- HTTP 仅适用于局域网 IP、`localhost` 或 `.local` 地址。

服务器地址与登录 Cookie 只保存在设备本地。本仓库及发布 APK **不包含维护者的服务器域名、账号、密钥或访问凭据**。
