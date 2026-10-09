# deepseek-harness-android

Android 客户端（`app/`）+ 自研 Go 通信壳（`bridge/`，组件名 **dshbridge**）。
「dsh 服务端」与「dshbridge 服务端」的职责边界见 [server-roles.md](docs/architecture/server-roles.md)；
dshbridge 的命令与部署限制见 [bridge/README.md](bridge/README.md)。

## 提交

- Conventional Commits，英文 subject，单行祈使句并带范围，例：`refactor(mobile)!: ...`。
- 按主题拆提交，`docs/`、`bridge/`、`app/` 三类互不依赖源码，可以分别提交且各自可构建。
- 包名与 applicationId 是 `app.deepseek.harness.mobile`（2026-10-10 从 `cool.rin.deepseekremote` 改名，
  属 breaking change：对老安装是另一个应用身份）。

## 验证（提交前跑，别只跑 UP-TO-DATE）

- Android 单元测试：`./gradlew :app:testDebugUnitTest --rerun`（当前 22 suite / 88 tests）。
  Gradle 要写 `~/.gradle`，在 DSH 沙箱里需要放宽文件权限；不加 `--rerun` 会直接 UP-TO-DATE 跳过。
- `bridge/` 的 Go 测试：`cd bridge && go test ./...` —— **本机没有 Go 工具链**（Homebrew/Cellar/`/usr/local`
  都没有），Go 侧目前只能引用 `docs/qa/private-transport/validation.md` 里记录的交叉编译证据；
  要真跑先装 Go。
- 真实设备验证走 `mobile-use` skill + ADB；Pixel 相关证据在 `docs/qa/`。

## 仓库约定

- 不入库（见 `.gitignore`）：`artifacts/`、`runs/`、`design-qa.md`、`chatgpt-interaction-audit.md`、
  `test-fixtures/`、`signing/`、`.env`、`bridge/dist/`、`bridge/state/`。这些是本地证据、私钥和部署状态，
  不要用 `git add -f` 绕过。
- 文档分层：规格 `docs/superpowers/specs/`、计划 `plans/`、设计 `designs/`，验收证据 `docs/qa/<topic>/`，
  架构说明 `docs/architecture/`。
- 提交前 `git status` 必须干净。**这个目录可能同时有别的会话在写文档**，所以按路径显式 `git add`，
  不要 `git add -A`；提交后立刻复查 `git status`，出现意料外的改动说明有并发写入者。
