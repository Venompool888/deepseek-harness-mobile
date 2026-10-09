# Android 原生客户端私有通信壳配对与连接设计规范 (agy-design.md)

- **目标平台**: 原生 Android (基于现有 Kotlin Views、Material 3 体系与 DayNight 主题)
- **目标适配版本**: dsh 0.2.0-rc.2 (严格针对 rc.2 协议与私有通信壳 v1 规范)
- **阶段**: 前端交互与视觉设计规范 (Design Only - 不修改应用或通信壳源代码，不引入外部依赖)
- **数据声明**: 本设计及配套交互原型中所有连接地址、设备标识与配对凭据均为**合成模拟数据 (Synthetic Mock Data)**，绝不包含真实凭据，绝不发起真实网络请求。
- **文档语言**: 中英双语对照定义 (规范正文为中文，关键契约、数据结构及文本字段提供中英双语定义)

---

## 1. 架构目标与设计原则

### 1.1 核心目标
本设计定义 DeepSeek Harness Mobile Android 客户端在接入“私有通信壳 (Private Bridge)”时的配对流程、连接状态机、凭据保护与人机交互规范：
1. **隐藏公网特征，保留原生业务能力**：在公网环境下不暴露 dsh Web 页面特征与固定 API 路径；原生客户端通过通信壳提供的每部署随机路径与独立设备认证进行通信，会话、流式交互与审批协议完全保持原生。
2. **显式区分“私有服务”与“直接连接”**：提供清晰的双模切换（私有服务通信壳 vs. 直接连接），既保证公网私有环境的高安全性，又完整保留本地开发与局域网直接连接的可用性。直接连接模式支持输入服务器地址或 dsh 登录链接。
3. **安全分离的配对凭据输入**：严格区分公开非敏感连接配置（JSON 格式：包含协议版本、服务地址及两段各 11 字符 base64url 的配对路径）与短期高熵一次性配对凭据（独立掩码输入框、10 分钟时效、单次兑换作废）。配对成功后清空输入框，且不持久化保存配对码。
4. **零协议黑话的人性化状态反馈**：用户界面坚决杜绝出现底层技术黑话（严禁出现 Mux、RPC ID、信封结构、Cookie 兑换、WebSocket 帧、SHA-256 摘要、回环端口等）；所有异常与状态均转换为人本动作指引。
5. **用户界面去令牌化与中立凭据表述**：已连接状态下使用中立表述“访问凭据已安全保存”，不显示任何原始令牌行，不向用户作出硬件级存储或安全芯片承诺。
6. **无缝适配多样化环境**：完整覆盖中英双语、浅色/深色模式（DayNight）、360dp~411dp 窄屏、150% 动态大字号、软键盘弹起防跳动与 TalkBack 无障碍屏幕阅读器标签。

---

## 2. 连接模型与双模切换设计 (Connection Mode Architecture)

### 2.1 连接模式定义

客户端提供顶置的分段切换器（`MaterialButtonToggleGroup`），明确提供两种连接形态：

```
┌────────────────────────────────────────────────────────┐
│  [  ● 私有服务 (通信壳)  ]   [    直接连接 (局域网)   ]  │
└────────────────────────────────────────────────────────┘
```

| 模式名称 | 适用场景 | 认证与传输通道 | Web 管理入口支持 |
| :--- | :--- | :--- | :--- |
| **私有服务 (Private Server)** | 公网远程访问、高安全私有部署 | 使用通信壳随机路径与已保存的设备访问凭据，上游 dsh 仅监听本地回环。 | **禁用公网 Web 入口**。提供明确解释：“出于安全防护，私有服务模式不向公网开放 Web 管理页面。如需使用完整 Web 管理功能，请在运行服务的电脑本地直接访问。” |
| **直接连接 (Direct Connection)** | 本机 USB 调试、局域网直连、原有开发环境 | 兼容既有 dsh 0.2.0-rc.2 直连协议，使用服务器地址或 dsh 登录链接。 | 支持通过系统浏览器打开局域网/服务器 Web 页面。 |

---

## 3. 六大核心连接状态机与契约规范 (Six Required States)

状态机由 `ConnectionState` 统一驱动。所有网络请求均携带当前分发代次 (`connectionGeneration`)，严防异步回调与并发竞态污染。

```
                    ┌─────────────────────────┐
                    │          Idle           │◄────────────────────────┐
                    │       (空闲/未配对)     │                         │
                    └────────────┬────────────┘                         │
                                 │ 点击“配对并连接”                     │
                                 ▼                                      │
                    ┌─────────────────────────┐                         │
                    │         Pairing         │                         │
                    │       (正在配对…)       │                         │
                    └──────┬───────────┬──────┘                         │
              配对成功     │           │ 配对失败/网络异常              │ 清除连接
                   ┌───────┘           └─────────┐                      │ (Forget)
                   ▼                             ▼                      │
        ┌─────────────────────┐       ┌──────────────────────┐          │
        │      Connected      │       │  NetworkUnavailable  │          │
        │      (已连接)       │       │    (网络不可用)      │          │
        └──────────┬──────────┘       └──────────────────────┘          │
                   │                             ▲                      │
                   │ 运行时凭据被撤销/过期       │ 掉线                 │
                   ▼                             │                      │
        ┌─────────────────────┐                  │                      │
        │    NeedsPairing     │                  │                      │
        │   (需要重新配对)    │                  │                      │
        └─────────────────────┘                  │                      │
                   │ 无法连接后端 dsh            │                      │
                   ▼                             │                      │
        ┌─────────────────────┐                  │                      │
        │ UpstreamUnavailable ├──────────────────┘                      │
        │    (后端服务不可用) │                                         │
        └──────────┬──────────┴─────────────────────────────────────────┘
                   └────────────────────────────────────────────────────┘
```

### 3.1 状态详细规范

#### 状态 1：`Idle`（空闲 / 未配对）
- **触发条件**：应用首次启动未配置连接、用户主动断开或执行了“清除此连接”操作。
- **界面元素**：
  - 双模选择器（默认选中“私有服务”）。
  - **非敏感连接配置输入框 (Connection JSON)**：多行文本框，接收格式形如：
    ```json
    {
      "protocolVersion": 1,
      "origin": "https://harness.example.com",
      "pairPath": "/Q9w2aX8mN4b/T6pR1yK3cH0"
    }
    ```
    下方附即时解析摘要标签（如：“✓ 已识别有效服务地址与配对通道”）。
  - **一次性配对码输入框 (Pairing Code)**：独立单行密码框（掩码 `••••••••`），附眼睛图标切换显隐。说明文字：“有效期 10 分钟，单次兑换作废；配对成功后清空输入框，且不持久化保存配对码”。
  - **设备名称输入框 (Device Name)**：单行文本框，自动预填本机型号（如 `Pixel 10`），允许用户自定义（1~80 字符）。
  - **主操作按钮**：“配对并连接” (`MaterialButton`)，高亮可用。
- **可用操作**：输入/粘贴参数、切换连接类型、点击配对。

#### 状态 2：`Pairing`（正在配对…）
- **触发条件**：用户点击“配对并连接”按钮，客户端校验输入无误后发起配对请求。
- **界面行为**：
  - 所有输入框（配置 JSON、配对码、设备名称）与模式切换器立即置灰禁用 (`isEnabled = false`)。
  - 主按钮禁用并显示内嵌旋转进度环（CircularProgressIndicator），文案变更为“正在验证并配对…” (`Pairing…`)。
  - 锁定当前的 `pairingGeneration = connectionGeneration`，防止多重点击触发并发配对。
- **安全约束**：请求正文仅提交 `{"deviceName": "...", "pairingCode": "..."}` 至指定 `pairPath`。绝对不将配对码拼接在 URL 查询参数中。

#### 状态 3：`Connected`（已连接）
- **触发条件**：配对成功，通信壳返回随机设备标识 (`deviceId`)、设备令牌 (`deviceToken`) 与每部署相对路由表（`rpc`、`stream`、`export`），客户端已安全保存访问凭据。
- **界面表现**：
  - 顶部状态栏与标题行显示绿色指示灯（`● 已连接` / `● Connected`）。
  - 弹窗/主卡片切换为“当前已连接服务”摘要视图：
    - 服务地址：`https://harness.example.com`（脱敏展示干净 origin）。
    - 绑定设备：`Pixel 10`。
    - 凭据状态：`访问凭据已安全保存`（中立表述，无任何原始令牌行或硬件级存储承诺）。
    - 通道版本：`协议 v1`。
  - 配对输入区域隐藏；**配对成功后清空输入框，且不持久化保存配对码**。
  - 提供次级操作按钮：“清除此连接” (`Forget Connection`，线框按钮)。
- **可用操作**：正常进行会话交互、工具执行、审批确认与会话导出。

#### 状态 4：`NeedsPairing`（凭据失效 / 需要重新配对）
- **触发条件**：服务端本地管理员通过命令主动撤销该设备 (`revoke`)、通信壳存储的设备凭据失效或过期。
- **界面表现**：
  - 顶部状态栏显示琥珀色告警指示灯（`● 凭据已失效` / `● Authorization Expired`）。
  - 提示卡片弹出琥珀色警告信息：
    - 中文：“该设备的访问授权已失效或已在服务端撤销。请从电脑端生成新的 10 分钟配对码重新配对。”
    - English: "Device access expired or was revoked. Please generate a fresh 10-minute pairing code on your computer to pair again."
  - **保留非敏感数据**：保留原有的服务地址（JSON 配置）与设备名称，用户无需重新输入服务器信息。
  - **配对码输入框重置**：配对码输入框自动清空并处于可输入状态，获得输入焦点。
  - 主按钮变更为“重新配对” (`Pair Again`)。

#### 状态 5：`NetworkUnavailable`（网络不可用 / 无法连接）
- **触发条件**：移动设备无网络、DNS 解析失败、TCP 连接超时、TLS 握手失败或通信壳公网端口不可达。
- **界面表现**：
  - 状态指示灯为红色（`● 无法连接` / `● Cannot Reach Server`）。
  - 错误横幅明确展示人本错误：
    - 中文：“无法连接到服务器。请检查手机网络连接、飞行模式或服务器地址是否正常。”
    - English: "Unable to reach the server. Please check your network connection or server address."
  - **安全重试机制 (Safe Retry Policy)**：
    - 保留所有非敏感配置。
    - 提供“重试连接” (`Retry Connection`) 按钮。
    - **核心铁律**：若处于初次配对阶段失败，**绝不自动隐式重发一次性配对码**！必须等待用户手动点击重试，避免因为网络抖动在凭据被废弃后产生无效调用。若已配对阶段出现网络中断，后台以指数退避进行长连接重连，不重复提交会话变更请求。

#### 状态 6：`UpstreamUnavailable`（通信壳已连通，但后端服务不可用）
- **触发条件**：通信壳正常响应且客户端认证通过，但通信壳访问本地回环上的 dsh 实例失败（dsh 进程未启动、回环端口未监听、或通信壳内部兑换 dsh 本地 Cookie 失败）。通信壳返回特定的网关错误状态。
- **界面表现**：
  - 状态指示灯为橙红色（`● 后端服务未就绪` / `● Backend Unready`）。
  - 提示横幅明确指出区别，避免用户盲目排查手机网络：
    - 中文：“通信安全通道已连通，但电脑上的后端服务未运行或未就绪。请确认电脑上的服务已正常启动。”
    - English: "The secure connection is established, but the backend service on the computer is not running. Please verify the service is running on your computer."
  - 提供“重新检测” (`Check Again`) 按钮。

---

## 4. 输入格式与安全隔离契约 (Input & Credential Handling)

### 4.1 非敏感配对配置 (Import JSON)
服务端通过本地管理命令导出如下非敏感配置。每个部署的 `pairPath` 包含两段随机 base64url 字符串，每段各 11 字符（共 16 字节，至少 128 位随机量）。该内容不含任何密钥，可通过剪贴板安全导入：

```json
{
  "protocolVersion": 1,
  "origin": "https://harness.example.com",
  "pairPath": "/Q9w2aX8mN4b/T6pR1yK3cH0"
}
```

#### 客户端校验规则
1. **JSON 结构检查**：必须包含非空的 `origin` 与 `pairPath`，且 `protocolVersion == 1`。
2. **协议安全规则**：
   - 公网域名必须强制使用 `https://`。
   - 仅对合法的本地回环 IP (`127.0.0.1`、`localhost`) 或私有局域网测试放行 `http://`。
3. **路径安全规范**：
   - `pairPath` 必须严格匹配规范路径格式（两段各 11 字符的 URL 安全随机字符，形如 `^/[A-Za-z0-9_-]{11}/[A-Za-z0-9_-]{11}$`）。
   - 严禁包含 `%` 编码、`..` 点段、`\` 反斜线、查询参数 `?` 或 URL 片段 `#`。若校验失败，红字报错：“服务路径格式不受支持”。

### 4.2 独立掩码一次性配对码 (Masked One-Time Code)
- **获取途径**：服务端管理员在电脑控制台执行 `dsh-bridge pair` 生成。
- **生命周期**：生成后 10 分钟有效，成功兑换一次后在服务端数据库中立即原子作废。
- **客户端呈现与处理**：
  - 独立的 `EditText` 控件，`inputType = textPassword`。
  - 默认以实心圆点 `••••••••` 掩码呈现。
  - 右侧提供眼睛切换图标，点击可临时切换为明文以便肉眼核对。
  - **生命周期保障**：**配对成功后清空输入框，且不持久化保存配对码**；配对失败时保留以便用户核对是否输错。

### 4.3 设备名称 (Device Name)
- **初始值**：默认读取 `android.os.Build.MODEL`（如 `Pixel 10`），若为空则使用 `Android Device`。
- **校验规则**：限制 1 ~ 80 个字符，两端去除空格。

---

## 5. 保存连接与“清除连接”契约 (Saved Connection & Forget Action)

### 5.1 存储与中立呈现规则
- **界面呈现原则**：
  - 状态标签统一使用中立文案：`访问凭据已安全保存` (`Access credentials saved securely`)。
  - 设置页面与摘要卡片仅展示：
    - 服务器 Origin（如 `https://harness.example.com`）。
    - 连接类型标签（`私有服务`）。
    - 绑定设备名（`Pixel 10`）。
    - 凭据状态（`访问凭据已安全保存`）。
  - **绝不展示任何原始令牌行 (No Raw Token Row)**。
  - **不向用户作出硬件级存储、安全芯片或 Keystore 的承诺**。

> [!NOTE]
> **开发者实现说明 (Developer-Only Implementation Note)**:
> 客户端在实现底层持久化时，应使用应用私有加密存储保存会话凭据与相对路径映射，避免在 SharedPreferences 中持久化明文凭据或一次性配对码。不要在用户可见界面或提示中宣称硬件级存储或安全芯片保护。

### 5.2 “清除连接” (Forget Connection) 交互
用户在已连接卡片或连接管理中可执行“清除连接”：
1. **二次确认弹窗** (`AlertDialog`)：
   - 标题：“确认清除此连接？” (`Forget this connection?`)
   - 内容：“确认清除此连接？将从本设备中移除已保存的访问凭据并断开连接。下次连接需要重新配对。”
   - English: "Forget this connection? The saved access credentials will be removed from this device and the connection will be disconnected. You must pair again next time."
   - 操作按钮：“取消” (`Cancel`) 与“确认清除” (`Forget`，红色/高对比度)。
   - **绝不承诺“安全硬件删除”**。
2. **执行流程**：
   - 关闭当前运行的 WebSocket 流与 RPC 请求。
   - 从设备私有存储中移除保存的访问凭据与连接配置。
   - 界面无缝重置回 `Idle` 状态。

---

## 6. 本地 Web 功能限制说明 (Local-only Web Action Explanation)

### 6.1 限制背景与安全原则
通信壳的设计目标是**不向公网暴露 dsh 的 Web 页面与特征**。因此在私有服务模式下，所有试图打开 Web 控制台的操作（如标题栏更多菜单中的“在浏览器中打开”、“Web 插件管理”等）必须明确拦截。

### 6.2 界面交互与引导说明
- **菜单状态**：更多菜单 (`⋮`) 中的“在浏览器中打开”项在私有模式下呈现为置灰或带有小信息图标 `ⓘ`。
- **点击反馈 / 提示说明**：
  - 点击后弹出标准信息提示对话框 (`AlertDialog`) 或底部浮层：
  - **标题**：“Web 管理功能说明” (`Web Console Notice`)
  - **中文说明**：“出于安全防护考虑，私有服务模式不向公网开放 Web 页面。如需使用完整 Web 管理后台、插件配置或开发者工具，请直接在运行服务的电脑本地浏览器中访问。”
  - **English**：“For security reasons, the web management interface is not exposed over the public network in private server mode. To access the web console, plugin settings, or developer tools, please use a local browser directly on the server computer.”
  - **操作按钮**：“知道了” (`Got it`)。

---

## 7. 视觉规范与 DayNight 调色板 (Design System & Color Tokens)

### 7.1 调色板映射表

| 语义角色 | 浅色模式 (Light) | 深色模式 (Dark) | 界面用途说明 |
| :--- | :--- | :--- | :--- |
| **画布背景 (`surface`)** | `#FFFFFF` | `#000000` | 主界面背景，纯黑适配 OLED |
| **容器底衬 (`cardSurface`)** | `#F8FAFC` | `#18181A` | 设置卡片、代码块底衬、对话框底色 |
| **输入框底壳 (`inputSurface`)** | `#FFFFFF` | `#212121` | 输入框背景 |
| **主标题/文字 (`textPrimary`)** | `#0F1115` | `#F4F4F4` | 标题、输入文字、正文 |
| **次要文字 (`textMuted`)** | `#61666B` | `#ABABAB` | 说明文本、占位符、辅助提示 |
| **品牌主色 (`brandBlue`)** | `#4176E6` | `#528BFF` | 主按钮填充、高亮开关、选中状态 |
| **成功指示 (`successGreen`)** | `#22C55E` | `#5BCF8B` | 已连接状态灯、校验通过指示 |
| **警示指示 (`warningAmber`)** | `#F59E0B` | `#FBBF24` | 凭据失效告警、重连中指示 |
| **错误指示 (`errorRed`)** | `#EC1313` | `#F87171` | 连接失败、格式错误、清除连接按钮 |
| **主边框线条 (`border`)** | `#CFD3D6` | `#4C4C4C` | 输入框、卡片标准边框描边 |
| **弱化描边 (`borderSubtle`)** | `#EBEFF2` | `#333336` | 分割线、内嵌卡片细线描边 |

### 7.2 控件排版与触控规格
- **基准屏幕设计标准**：适配 360dp ~ 411dp 窄屏设备，所有容器水平方向使用 `match_parent` 并在父级统一设置 16dp ~ 20dp 内边距。
- **触控热区**：所有按钮、输入框右侧显隐图标、切换选项均保证 **≥ 48dp × 48dp** 触控目标。
- **圆角规则**：
  - 输入框外框：10dp。
  - 卡片与对话框：16dp ~ 18dp。
  - 药丸按钮与状态胶囊：22dp ~ 24dp（全圆角）。

---

## 8. Android Kotlin Views 布局与组件契约 (Android Views Contract)

### 8.1 布局层级规范 (`dialog_server_connection.xml`)

```
ScrollView (MATCH_PARENT, WRAP_CONTENT, fillViewport="true")
 └── LinearLayout (vertical, padding="20dp")
      ├── TextView [DialogTitle: "服务器连接" / "Server Connection"] (20sp, bold)
      ├── MaterialButtonToggleGroup [ModeToggle: 私有服务 / 直接连接] (height="48dp")
      │
      ├── LinearLayout [PrivateServerContainer] (vertical)
      │    ├── TextView [IntroText: "公网私有环境请使用通信壳配对…"] (13sp)
      │    ├── TextInputLayout [JsonInputLayout: "连接配置 JSON"]
      │    │    └── TextInputEditText (minLines=3, maxLines=5, fontFamily="monospace")
      │    ├── TextView [JsonValidationSummary] (12sp, 动态显示解析结果)
      │    ├── TextInputLayout [PairingCodeLayout: "一次性配对码"] (passwordToggleEnabled="true")
      │    │    └── TextInputEditText (inputType="textPassword", singleLine="true")
      │    ├── TextInputLayout [DeviceNameLayout: "本机设备名称"]
      │    │    └── TextInputEditText (singleLine="true")
      │    ├── MaterialCardView [StatusBannerCard] (动态显示 6 大状态横幅与原因)
      │    │    └── LinearLayout (图标 + 标题 + 详情说明)
      │    └── MaterialCardView [ConnectedSummaryCard] (已连接状态摘要卡片)
      │         ├── TextView (服务地址、设备名、凭据状态: 访问凭据已安全保存)
      │         └── MaterialButton [ForgetButton: "清除此连接"] (outline, 48dp)
      │
      ├── LinearLayout [DirectContainer] (vertical, 兼容旧版直连模式)
      │    ├── TextView [DirectIntroText: "输入服务器地址或 dsh 登录链接…"]
      │    └── TextInputLayout [DirectAddressLayout: "服务器地址或 dsh 登录链接"]
      │
      ├── TextView [InlineErrorText] (12sp, 错误提示条)
      │
      └── LinearLayout [BottomActionBar] (horizontal, height="52dp")
           ├── MaterialButton [ConnectionListButton: "连接历史"] (text-button)
           ├── Space (weight=1)
           ├── MaterialButton [CancelButton: "取消"] (text-button)
           └── MaterialButton [PrimaryActionButton: "配对并连接"] (filled, 48dp touch)
```

---

## 9. 响应式适配、无障碍 (a11y) 与键盘策略

### 9.1 150% 动态大字号排版保护 (150% Font Scaling)
- **拒绝写死固定高度**：所有包含文本的组件外壳一律声明 `android:layout_height="wrap_content"`，严禁为 TextView、Button 设定 `height="40dp"` 等死高度。
- **弹性内边距**：按钮内边距使用 `paddingVertical="12dp"`，确保在 150% 字号下文本自动换行或撑开高度而不被上下截断。
- **滚动条穿透**：最外层始终包裹 `ScrollView`，当超大字号导致内容超出屏幕时平滑纵向滚动。

### 9.2 软键盘弹起自适应 (Keyboard & IME Handling)
- **窗口模式**：Activity 声明 `android:windowSoftInputMode="adjustResize"`。
- **焦点记忆与滚动吸附**：
  - 点击“一次性配对码”输入框时，`ScrollView` 自动滚动确保配对码及主操作按钮停留在可见区域内。
  - 用户在输入框之间通过软键盘“下一项” (`imeOptions="actionNext"`) 依次跳转：配置 JSON → 配对码 → 设备名 → “完成” (`actionDone`)，点按完成直接触发配对逻辑。

### 9.3 无障碍屏幕阅读器标签 (Accessibility / TalkBack)
- **图形与操作项标签**：
  - 模式切换按钮：`contentDescription="切换连接模式：当前选中私有服务"`。
  - 配对码显隐切换：“显示配对码” / “隐藏配对码”。
  - 清除连接按钮：“从设备中移除已保存的连接凭据并断开”。
  - 连接状态指示灯：赋予明确的朗读文本（例如：“连接状态：已连接到服务器”）。
- **动态状态播报**：当状态从 `Pairing` 转为 `Connected` 或遇到错误时，通过 `view.announceForAccessibility(...)` 主动向屏幕阅读器播报状态变更文本。

---

## 10. 中英双语文本契约字典 (Bilingual String Dictionary)

| 文本键名 (`StringKey`) | 中文 (Simplified Chinese) | English |
| :--- | :--- | :--- |
| `title_server_connection` | 服务器连接 | Server Connection |
| `mode_private_server` | 私有服务 | Private Server |
| `mode_direct` | 直接连接 | Direct Connection |
| `hint_config_json` | 连接配置 JSON (包含地址与配对路径) | Connection JSON (Origin & Pair Path) |
| `hint_pairing_code` | 一次性配对码 | One-Time Pairing Code |
| `hint_device_name` | 本机设备名称 | Device Name |
| `field_direct_label` | 服务器地址或 dsh 登录链接 | Server address or dsh sign-in link |
| `label_json_valid` | 已识别有效服务器地址与配对通道 | Valid server address and pairing channel verified |
| `label_json_invalid` | 配置格式有误，请输入有效的连接 JSON | Invalid format. Please enter valid connection JSON |
| `helper_pairing_code` | 有效期 10 分钟，单次兑换作废；配对成功后清空输入框，且不持久化保存配对码 | Valid for 10 minutes; clear the input on success and do not persist the code |
| `status_idle` | 未连接 | Not connected |
| `status_pairing` | 正在验证并配对… | Verifying and pairing… |
| `status_connected` | 已安全连接 | Securely connected |
| `status_needs_pairing` | 访问凭据已失效 | Authorization credentials expired |
| `status_network_unavailable` | 无法连接到服务器 | Unable to reach server |
| `status_upstream_unavailable` | 通信服务已连通，但后端服务未就绪 | Bridge connected, but backend service unready |
| `desc_needs_pairing` | 该设备的访问授权已失效或已在服务端撤销。请从电脑端生成新的 10 分钟配对码重新配对。 | Device access expired or was revoked. Please generate a fresh 10-minute pairing code on your computer. |
| `desc_network_unavailable` | 无法连接到目标服务器，请检查手机网络设置、飞行模式或服务器地址。 | Cannot reach target server. Please check device network, airplane mode, or server address. |
| `desc_upstream_unavailable` | 通信安全通道已建立，但电脑上的后端服务未运行或正在维护。请确认服务已正常启动。 | Secure tunnel established, but backend service on computer is not running. Please verify host status. |
| `label_credentials_status` | 凭据状态 | Credential Status |
| `val_credentials_saved` | 访问凭据已安全保存 | Access credentials saved securely |
| `btn_pair_and_connect` | 配对并连接 | Pair and Connect |
| `btn_pair_again` | 重新配对 | Pair Again |
| `btn_retry` | 重试连接 | Retry Connection |
| `btn_check_again` | 重新检测 | Check Again |
| `btn_forget_connection` | 清除此连接 | Forget Connection |
| `dialog_forget_title` | 确认清除此连接？ | Forget this connection? |
| `dialog_forget_message` | 确认清除此连接？将从本设备中移除已保存的访问凭据并断开连接。下次连接需要重新配对。 | Forget this connection? The saved access credentials will be removed from this device and the connection will be disconnected. You must pair again next time. |
| `notice_web_title` | Web 管理功能说明 | Web Console Notice |
| `notice_web_body` | 出于安全防护考虑，私有服务模式不向公网开放 Web 页面。如需使用完整 Web 管理后台，请直接在运行服务的电脑本地浏览器中访问。 | For security reasons, the web console is not exposed over the public network in private server mode. Please access it locally on the server computer. |
| `btn_got_it` | 知道了 | Got it |
| `btn_cancel` | 取消 | Cancel |

---

## 11. 交付物与验证基准

1. **`agy-design.md`**：本设计规范全文本档。
2. **`agy-preview.html`**：独立自包含的前端交互原型，满足：
   - 包含状态切换栏（支持切换全部 6 个状态及直接连接模式）。
   - 包含浅色/深色主题切换、中英双语切换、100%/150% 动态字号切换及 360dp/411dp 屏幕宽度切换。
   - 包含合成模拟数据示例一键填充，示例 `pairPath` 严格采用两段各 11 字符 base64url（形如 `/Q9w2aX8mN4b/T6pR1yK3cH0`）。
   - 已连接视图展示中立文案“访问凭据已安全保存”，无任何令牌行，不宣称硬件级存储。
   - 纯自包含实现，无任何外部 CDN、CSS、JS 库或字体依赖，零网络请求。
