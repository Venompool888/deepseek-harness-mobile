# Private dsh Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在不改变 Android 的 dsh 业务协议的前提下，实现带设备认证、每部署随机路径的通信壳，让未认证公网请求无法直接访问 dsh Web 页面和原生接口。

**Architecture:** 新增与 dsh 同机运行的 Go 服务，配置一个固定回环上游；它承载原始 RPC 信封、Remote mux 帧和指定会话导出。Android 抽出传输边界，直连与通信壳作为明确的连接类型，前后台统一选择传输；通信壳错误不会回退直连。

**Tech Stack:** Go 1.26.x、标准库 net/http、github.com/coder/websocket v1.8.15；现有 Kotlin Views、OkHttp 4.12.0、org.json、Android Keystore。Go 版本由官方 https://go.dev/dl/ 核实补丁与校验和后安装到任务工具目录；WebSocket 版本依据 https://github.com/coder/websocket/releases/tag/v1.8.15。

**Spec:** `/Users/yuri/Desktop/deepseek_harness_android/docs/superpowers/specs/2026-10-09-private-transport-design.md`

## Global Constraints

- Android 保留 dsh 0.2.0-rc.2 的业务协议；保留 Kotlin Views 和 Material 主题。
- 每条随机路径包含至少 128 位随机量，首次初始化生成并持久保存；重启与升级不改变路径，配置损坏停止启动。
- 公网必须使用 HTTPS/WSS；开发和 Pixel USB 测试允许明确的回环 HTTP，不能以此替代公网 HTTPS 验收。
- 上游仅允许配置好的回环 origin，不接受客户端提供的目标，不跟随上游重定向。
- 不提供 dsh HTML、JS/CSS、favicon、WebView 跳转、任意文件或任意路径转发。
- 根路径、未知路径、固定 `/v1/*`、`/api/*` 和无效认证的真实路径返回统一的空 404；认证先于业务解析和 WS 升级。
- 一次性配对凭据有效期 10 分钟；设备 token 为随机 256 位；服务端只保存 token 摘要。
- 手机用 Android Keystore 保护持久凭据，按 origin 隔离；凭据不在 URL 查询参数、日志和错误里出现。
- 路径更换需显式维护、重新分发和重新配对；本轮不做自动轮换或旧路径兼容。
- Codex 子代理仅使用 GPT-6/6.1；前端设计通过 agy-subagent，禁止 agy 修改协议或服务端代码。
- 保留已有未提交包名迁移、OAuth、主题和 rc.2 适配；不发布公网、不推送远端、不修改防火墙。

## Review Focus

- 双重编码路径、点段、反斜线和跨 origin 的路径资料必须拒绝，不能被 URL 规范化后导向另一个入口。
- 并发配对兑换只能成功一次；凭据存储失败不得返回成功，也不得留下可重用的半完成记录。
- 网络中断或后台恢复后，旧连接的帧与凭据不能污染新服务器；变更操作不自动重试。
- 设备撤销与建立 WS 并发时，不能留下仍可使用的已撤销连接。
- 通信壳模式下导出、Web 管理入口和后台通知不能绕过传输层直接访问 dsh。

## Files and shared contracts

服务端文件放在 `bridge/`，使用模块 `app.deepseek.harness/bridge`。`cmd/dsh-bridge/main.go` 只处理命令行；`internal/config/` 负责配置及路由；`internal/auth/` 负责设备状态；`internal/upstream/` 负责内部认证及 HTTP；`internal/gateway/` 负责外层路由、转发和 WS 生命周期。

Android 新建 `HarnessTransport.kt`、`DirectHarnessTransport.kt`、`BridgeHarnessTransport.kt`、`BridgeConnection.kt`、`BridgePairingClient.kt`、`ConnectionStore.kt`；修改 `HarnessApi.kt`、`HarnessRemoteMux.kt`、`MainActivity.kt`、`TaskMonitorService.kt`。设计交付放在 `docs/superpowers/designs/private-transport/`，验收放在 `docs/qa/private-transport/`。

协议固定为以下配对资料及成功响应，真实值不提交：

```json
{"protocolVersion":1,"origin":"https://example.com","pairPath":"/random/random"}
```

```json
{"deviceName":"Pixel 10","pairingCode":"one-time-secret"}
```

```json
{"protocolVersion":1,"deviceId":"id","deviceToken":"secret","routes":{"rpc":"/a/b","stream":"/c/d","export":"/e/f"}}
```

`random` 等短值仅是格式示例；实际生成至少 128 位随机量。路径必须匹配 `^/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+$`，不得含 `%`、查询串、片段、点或反斜线。手机只用已验证 origin 加相对路径。配对资料通过本地管理命令导出 JSON，配对码单独输入；本轮不新增二维码扫描依赖。

---

### Task 1: Persistent routes and local administration

**Files:** Create `bridge/go.mod`, `bridge/go.sum`, `bridge/cmd/dsh-bridge/main.go`, `bridge/internal/config/config.go`, `bridge/internal/config/config_test.go`, `bridge/README.md`; modify `.gitignore` to exclude deployment state and generated binaries.

**Interfaces:** Produces `config.Routes{Pair,RPC,Stream,Export string}`, `config.Config{ProtocolVersion int, Listen, Upstream, PublicOrigin string, Routes Routes}`; `config.Init(dir string, cfg Config) (Config,error)`, `config.Load(dir string) (Config,error)`. Route mapping is written to `routes.json`, other nonsecret fields to `config.json`; secrets use auth state in Task 2.

- [x] **Step 1: Write route persistence and corruption tests.** Include collision detection, independent deployment mappings, refusal to reinitialize an existing directory, and origin/path validation.

```go
func TestRoutesPersist(t *testing.T) {
    dir := t.TempDir()
    cfg, err := Init(dir, Config{ProtocolVersion: 1, Listen: "127.0.0.1:3090", Upstream: "http://127.0.0.1:3080", PublicOrigin: "http://127.0.0.1:3090"})
    if err != nil { t.Fatal(err) }
    loaded, err := Load(dir)
    if err != nil || loaded.Routes != cfg.Routes { t.Fatalf("lost routes: %v", err) }
    if cfg.Routes.RPC == cfg.Routes.Pair { t.Fatal("route collision") }
    if err := os.WriteFile(filepath.Join(dir, "routes.json"), []byte("broken"), 0600); err != nil { t.Fatal(err) }
    if _, err := Load(dir); err == nil { t.Fatal("silently recovered corrupt mapping") }
}
```

- [x] **Step 2: Prepare verified Go tooling after plan approval and run** `go test ./internal/config` in `bridge/`. Expected: RED, undefined Init/Load. Dependency setup belongs to this deliverable, not a separate scaffolding task.
- [x] **Step 3: Implement atomic configuration and commands.** `init --state DIR --listen HOST:PORT --upstream URL --public-origin URL`, `routes --state DIR`, `serve --state DIR --tls-cert FILE --tls-key FILE`. Generate each route from two independent 8-byte `crypto/rand` buffers encoded with RawURLEncoding. Reject duplicate route strings, non-loopback upstreams, non-HTTPS public origins except explicit development loopback, and insecure public listening without TLS or explicit `--behind-tls-proxy`.

```go
func segment() (string, error) {
    b := make([]byte, 8)
    if _, err := rand.Read(b); err != nil { return "", err }
    return base64.RawURLEncoding.EncodeToString(b), nil
}
```

Use exclusive initialization, private directories/files and validated atomic replacement; reject partial state on subsequent startup. Pin localhost resolution to verified loopback IPs for the upstream transport. Plain HTTP development is bound to loopback; behind-proxy mode documents the requirement that only the TLS proxy can reach the listener.
- [x] **Step 4: Run** `go test ./internal/config` and `go vet ./internal/config`. Expected: GREEN, no diagnostics.
- [x] **Step 5: Commit only Task 1 files** with message `feat: add persistent randomized bridge routes`.

### Task 2: One-use pairing and revocable device credentials

**Files:** Create `bridge/internal/auth/store.go`, `store_test.go`; modify CLI and `bridge/README.md`.

**Interfaces:** `auth.Open(dir string) (*Store,error)`; `Store.NewPairing(now time.Time) (string,error)`, `Store.Pair(code,name string,now time.Time) (DeviceCredential,error)`, `Store.Authenticate(token string) (deviceID string,ok bool)`, `Store.Revoke(deviceID string) error`, `Store.Devices() []DeviceInfo`; `DeviceCredential{DeviceID,DeviceToken string}`. Revoke notifies a gateway callback registered by Task 4, and new connections recheck authentication when registering.

- [x] **Step 1: Write tests for expiry, two simultaneous redemptions, persistence failure and revocation after restart.** Inject a clock and state-writer into Store tests, keeping public methods above stable.

```go
func TestPairingOnlyOnce(t *testing.T) {
    store, err := Open(t.TempDir()); if err != nil { t.Fatal(err) }
    now := time.Unix(1000, 0)
    code, err := store.NewPairing(now); if err != nil { t.Fatal(err) }
    credential, err := store.Pair(code, "Pixel 10", now); if err != nil { t.Fatal(err) }
    if _, err := store.Pair(code, "second", now); err == nil { t.Fatal("code reused") }
    if _, ok := store.Authenticate(credential.DeviceToken); !ok { t.Fatal("not authenticated") }
    if err := store.Revoke(credential.DeviceID); err != nil { t.Fatal(err) }
    if _, ok := store.Authenticate(credential.DeviceToken); ok { t.Fatal("revoked token works") }
}
```

- [x] **Step 2: Run** `go test ./internal/auth`. Expected: RED, missing Store implementation.
- [x] **Step 3: Implement locked, durable transactions.** Generate pairing codes and tokens using 32 random bytes; hash with SHA-256 before storing. Enforce 10-minute expiry and single successful exchange, persist consumption and token record in one transaction before returning. Lost pairing responses require a new local pairing code. Validate names (1–80 characters), refuse invalid/repeated/expired codes without secret-bearing errors. Add `pair --state DIR`, `devices --state DIR`, `revoke --state DIR --device ID` commands; print secrets only in the explicitly requested local pairing output.

```go
func digest(value string) string {
    sum := sha256.Sum256([]byte(value))
    return hex.EncodeToString(sum[:])
}
```

- [x] **Step 4: Run** `go test -race ./internal/auth ./internal/config`. Expected: GREEN, one concurrent redemption succeeds and disk-failure tests return no credentials.
- [x] **Step 5: Commit Task 2 files** with message `feat: add bridge pairing and device revocation`.

### Task 3: Authenticated RPC and export forwarding

**Files:** Create `bridge/internal/upstream/client.go`, `client_test.go`, `bridge/internal/gateway/http.go`, `http_test.go`, `allowed.go`; modify CLI, README.

**Interfaces:** `upstream.New(origin,launchToken string) (*Client,error)`; `Client.RPC(ctx context.Context,raw []byte) ([]byte,error)`, `Client.Export(ctx context.Context,sessionID string,descendants bool) (*http.Response,error)`, `Client.Headers() (http.Header,error)` for Task 4. `gateway.New(cfg config.Config,store *auth.Store,up *upstream.Client) *Gateway`; Gateway implements http.Handler without default ServeMux path redirects.

- [x] **Step 1: Write httptest cases proving anonymous requests cause zero upstream calls**, uniform empty 404 on root/fixed/unknown/random paths, auth before JSON parsing, unsafe path rejection, scalar/null RPC response preservation, upstream redirect rejection and streamed export headers.

```go
func TestAnonymousNeverReachesUpstream(t *testing.T) {
    var hits atomic.Int32
    upstreamServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1); w.WriteHeader(500) }))
    defer upstreamServer.Close()
    cfg, err := config.Init(t.TempDir(), config.Config{ProtocolVersion:1, Listen:"127.0.0.1:3090", Upstream:upstreamServer.URL, PublicOrigin:"http://127.0.0.1:3090"})
    if err != nil { t.Fatal(err) }
    store, err := auth.Open(t.TempDir()); if err != nil { t.Fatal(err) }
    up, err := upstream.New(upstreamServer.URL, ""); if err != nil { t.Fatal(err) }
    for _, path := range []string{"/", "/v1/rpc", "/api/session/list", cfg.Routes.RPC} {
        rr := httptest.NewRecorder()
        New(cfg, store, up).ServeHTTP(rr, httptest.NewRequest("POST", path, strings.NewReader("invalid")))
        if rr.Code != 404 || rr.Body.Len() != 0 { t.Fatalf("fingerprint at %s", path) }
    }
    if hits.Load() != 0 { t.Fatal("anonymous upstream request") }
}
```

- [x] **Step 2: Run** `go test ./internal/upstream ./internal/gateway`. Expected: RED for missing Client/Gateway.
- [x] **Step 3: Implement authenticated exact-path routing.** Reject RawPath/percent encoding and noncanonical paths; no redirect or framework branding. Pair body limited to 8 KiB; RPC body to 8 MiB; per-device max 8 concurrent requests. Rate-limit pair attempts to 10/minute per direct peer with bounded/expiring entries; do not trust forwarded address headers. Return success pairing schema above; invalid pairing is empty 404, throttling is stable 429 without product details.

Parse RPC type, rpcId, method and payload.args while retaining the original body for forwarding. Build an explicit allowlist from current HarnessApi methods and their rc.2 argument contract. Do not accept arbitrary URLs, methods with traversal, arbitrary HTTP headers or cookies from clients. Internal token exchange only targets root of fixed origin and validates the expected 303 plus authority-bound dsh-auth cookie without following redirects. HTTPS client verifies certificates normally. Token is read from a private file, never a CLI argument or URL in logs.

```go
// After Authenticate and allowlist validation:
request, err := http.NewRequestWithContext(ctx, http.MethodPost, fixedOrigin+"/api/"+method, bytes.NewReader(raw))
if err != nil { return nil, err }
request.Header.Set("Content-Type", "application/json")
// Attach only the internally acquired dsh Cookie; never forward the device token.
```

For export, accept only sessionId/includeDescendants JSON and construct query with url.Values. Return only validated ZIP content type and a locally constructed filename; drop Set-Cookie, Location, Server and internal headers. Stream to the client with cancellation and 5-minute limit. HTTP connection errors are sanitized bridge errors, original authenticated dsh RPC business responses retain their envelope. Disable redirects and automatic replay of mutating calls.
- [x] **Step 4: Run** `go test -race ./internal/...` and `go vet ./...`. Expected: GREEN, exact RPC bodies retained, credentials not cross-forwarded, cancellation releases export upstream.
- [x] **Step 5: Commit Task 3 files** with message `feat: forward authenticated dsh RPC and exports`.

### Task 4: Authenticated Remote mux relay

**Files:** Create `bridge/internal/gateway/stream.go`, `stream_test.go`; extend gateway/auth/CLI integration.

**Interfaces:** Add Gateway stream handling on cfg.Routes.Stream; bridge physical connections are registered by deviceID. `Gateway.CloseDevice(deviceID string)` cancels all registered streams; auth revocation calls it after durable state change. `Client.Headers()` supplies internal Cookie headers only, never public token.

- [x] **Step 1: Write integration tests for authentication before upgrade, exact frame relay, endpoint allowlist, disconnect cleanup and the revoke/register race.** Use a fake internal WS server and actual authenticated gateway socket; ensure the raw open frame is byte-preserved and device token is absent upstream.

```go
frame := []byte(`{"type":"open","streamId":"s1","endpoint":"session/control","payload":{"args":{}}}`)
// Send frame on the paired test WS, capture it at the internal WS, compare bytes.
if !bytes.Equal(captured, frame) { t.Fatal("changed dsh frame") }
// Revoke device, then assert the next read fails and the upstream socket closes.
```

- [x] **Step 2: Run** `go test ./internal/gateway -run 'Stream|Revoke'`. Expected: RED before stream relay exists.
- [x] **Step 3: Implement using pinned coder/websocket.** Connect only to fixed internal `/api/remote.mux`; enforce same-origin Origin when present for browser clients, permit absent Origin for authenticated native clients. Read limit 16 MiB, maximum 4 physical connections/device, write timeout 15s. Validate outgoing open/cancel frame types and explicit endpoint allowlist without rewriting payload. Two sequential relay loops preserve order without unbounded queues; any close/error cancels both loops. Register under device lock and recheck revocation; durable revoke closes active sockets. Logs record device ID and lifecycle only, not frames or tokens. Reject request compression initially, use ping/timeout policy compatible with long-running dsh tasks.

```go
ctx, cancel := context.WithCancel(r.Context())
defer cancel()
// Both relay directions share ctx; either failure cancels and closes both sockets.
```

- [x] **Step 4: Run** `go test -race ./...` and `go vet ./...`. Expected: GREEN, no races or leaked sockets in reconnect/revoke tests.
- [x] **Step 5: Commit Task 4 files** with message `feat: relay private authenticated remote mux streams`.

### Task 5: Android transport boundary and compatibility

**Files:** Create `HarnessTransport.kt`, `DirectHarnessTransport.kt`, `BridgeHarnessTransport.kt`, `BridgeConnection.kt`, tests `BridgeConnectionTest.kt`, `HarnessTransportTest.kt`; modify `HarnessApi.kt`, `HarnessRemoteMux.kt`. Paths are under `app/src/main/java/app/deepseek/harness/mobile/` and `app/src/test/java/app/deepseek/harness/mobile/`.

**Interfaces:** Define `HarnessTransport.rpc(envelope: JSONObject): JSONObject`, `openMux(listener: WebSocketListener): WebSocket`, `export(sessionId: String, includeDescendants: Boolean, sink: OutputStream)`. Direct/bridge transports use the same interface. HarnessApi retains its existing constructor as a direct-transport compatibility adapter; new factory accepts `transport: () -> HarnessTransport`, capturing a single connection profile per call/physical stream. `BridgeConnection(origin, deviceId, token, rpcPath, streamPath, exportPath, protocolVersion)` validates and redacts diagnostics; its companion provides `parseRoutes(origin: String, json: JSONObject): BridgeRoutes`, where `BridgeRoutes(rpcPath: String, streamPath: String, exportPath: String)` holds only validated relative paths.

- [x] **Step 1: Write tests for dangerous mappings, redirect refusal, no Cookie leakage, no fallback and origin switching.** Keep existing native transport tests as compatibility tests.

```kotlin
@Test fun rejectsForeignRoute() {
    assertThrows(IllegalArgumentException::class.java) {
        BridgeConnection.parseRoutes("https://one.example", JSONObject()
            .put("rpc", "https://two.example/rpc")
            .put("stream", "/a/b").put("export", "/c/d"))
    }
}
```

- [x] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*BridgeConnectionTest' --tests '*HarnessTransportTest'`. Expected: RED for new transport types.
- [x] **Step 3: Extract transport code, keeping RPC serialization and state reducers unchanged.** Bridge posts the identical envelope to stored rpcPath, uses stored streamPath plus its own Authorization and no dsh Cookie, and posts export JSON while copying response to sink. Direct retains rc.2 auth behavior. Disable HTTP/WS redirects on both; transport 404 means bridge credential/path unavailable, not dsh login. Separate bridge authentication failure from Cloudflare Access and internal upstream unavailable. Capture server generation so old responses cannot change a newly selected connection.

```kotlin
val request = Request.Builder()
    .url(profile.origin + profile.rpcPath)
    .header("Authorization", "Bearer ${profile.token}")
    .post(envelope.toString().toRequestBody("application/json".toMediaType()))
    .build()
```

- [x] **Step 4: Run** `./gradlew :app:testDebugUnitTest`. Expected: all existing 70 tests and new transport cases GREEN.
- [x] **Step 5: Commit Task 5 files** with message `refactor: share direct and private dsh transports`.

### Task 6: Pairing, protected storage and background integration

**Files:** Create `BridgePairingClient.kt`, `ConnectionStore.kt`, tests `BridgePairingClientTest.kt`, `ConnectionStoreTest.kt`; modify `MainActivity.kt`, `TaskMonitorService.kt`, manifest backup rules as needed.

**Interfaces:** `BridgePairingClient.pair(origin: String, pairPath: String, code: String, deviceName: String): BridgeConnection`; companion `BridgePairingClient.decode(origin: String, json: JSONObject): BridgeConnection` validates a received success object using Task 5 parseRoutes. `ConnectionStore.load(origin: String): SavedConnection?`, `save(connection: SavedConnection)`, `remove(origin: String)`; SavedConnection discriminates Direct/Bridge. `transportFor(origin: String)` is used by foreground and service through one factory, not duplicate selection code.

- [x] **Step 1: Write tests using injected persistence/encryption adapters.** Verify direct migration, origin separation, malformed mapping rejection before save, no partial save on failed pairing, secrets absent from toString and uncertain receipt does not retry. Add pure connection-mode tests for background selection and blocked Web actions.

```kotlin
@Test fun doesNotSaveUntrustedPairingMapping() {
    val json = JSONObject("""{"protocolVersion":1,"deviceId":"d","deviceToken":"secret","routes":{"rpc":"//other.example/a","stream":"/a/b","export":"/c/d"}}""")
    assertThrows(IllegalArgumentException::class.java) { BridgePairingClient.decode("https://one.example", json) }
}
```

- [x] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*BridgePairingClientTest' --tests '*ConnectionStoreTest'`. Expected: RED before implementation.
- [x] **Step 3: Implement Keystore AES-GCM storage.** Unique nonce per write, encrypted profile blob plus schema version in app-private storage; never persist plaintext tokens or pair codes in SharedPreferences. Exclude credentials from backup/device transfer; missing/invalidated Keystore key asks for new pairing and does not fall back. Import nonsecret connection JSON, pair via POST, validate mapping and response before atomic save; clear transient code after attempt and cancel stale pairing when switching origins.

```kotlin
val cipher = Cipher.getInstance("AES/GCM/NoPadding")
cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
val nonce = cipher.iv
val encrypted = cipher.doFinal(profileJson.toByteArray(Charsets.UTF_8))
```

Use ConnectionStore/factory in TaskMonitorService; service starts only with known profile. Close active mux when forgetting/changing a profile. Export via worker transport to app-private temporary ZIP then MediaStore Downloads on API 29+, or Storage Access Framework on API 26–28, deleting temporary files after completion/cancel. Remove DownloadManager direct URL usage for bridge. Disable official Web/advanced browser actions in bridge mode with a brief “在服务端电脑本地使用” explanation; do not construct random root Web URLs.
- [x] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease`. Expected: GREEN. Pixel checks in Task 8 cover actual Keystore and process restart.
- [x] **Step 5: Commit Task 6 files** with message `feat: persist paired connections across foreground and background`.

### Task 7: agy connection design and native UI integration

**Files:** Create `docs/superpowers/designs/private-transport/agy-design.md`, `agy-preview.html`; modify focused connection UI in MainActivity, AppLanguage/resources as needed. agy writes only task-owned design directory, never app or bridge source.

**Interfaces:** Consumes pairing states Idle/Pairing/Connected/NeedsPairing/NetworkUnavailable/UpstreamUnavailable, connection type Direct/Bridge, nonsecret connection JSON and transient pairing code. Produces native layout/interaction specification; Codex implements the UI against Task 6 client/store.

- [x] **Step 1: Main agent prepares exact agy task-directory permissions from agy-subagent references/permissions.md.** Dispatch liaison `gpt-6-luna` with max effort and fork_turns none (user model rule overrides obsolete skill model); include absolute spec/plan, allowed paths, state contract, original design references, no real token or user messages. Ask agy for Chinese/English, light/dark, keyboard and large-font behavior. Planning approval must precede this implementation dispatch.
- [x] **Step 2: Validate actual output and permissions/errors**, then integrate local preview and design. Check no product hashes changed by agy and remove task-specific permissions. Do not report SUCCESS based only on CLI exit code.
- [x] **Step 3: Implement connection type selector, import JSON field and separate masked one-time code input following agy output.** Display saved bridge connection with device name/forget action; pair/network/upstream failures retain enough nonsecret data to retry, never automatic retry of pair exchange. Existing direct flow remains reachable explicitly.

```kotlin
// Pairing admission captures identity before starting worker work.
val pairingGeneration = connectionGeneration
worker.execute {
    val profile = pairingClient.pair(origin, pairPath, code, deviceName)
    mainHandler.post {
        if (pairingGeneration == connectionGeneration) selectPairedConnection(profile)
    }
}
```

`selectPairedConnection(profile)` persists validated connection, clears code, closes old live stream, updates server identity and refreshes using bridge transport. Handle failure on the same generation check. No unrelated MainActivity rewrite.
- [x] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease`; visually verify preview states and basic accessibility on Pixel. Expected: GREEN and no real credentials in artifacts.
- [x] **Step 5: Commit Task 7 files** with message `feat: add native private-server pairing flow`.

### Task 8: End-to-end verification and delivery

**Files:** Create `docs/qa/private-transport/validation.md`, `bridge/scripts/build.sh`, sanitized protocol examples in README; preserve evidence of decisions and test counts.

**Interfaces:** Uses compiled bridge, official local dsh rc.2, current Android APK, connected Pixel 10. Build outputs stay ignored; validation document links nonsecret artifacts.

- [x] **Step 1: Add TLS integration test using httptest.NewTLSServer and trusted test client.** Authentication must work on HTTPS and WSS with certificate validation; expired/revoked token, wrong route and upstream unavailable return specified responses. Do not disable TLS validation in product clients. Run `go test -race ./...` and expect GREEN.
- [x] **Step 2: Start bridge at 127.0.0.1:3090 against dsh at 127.0.0.1:3080 with transient state outside repo.** Use generated routes to test anonymous root, fixed/native paths, malformed methods and WS upgrades. Save only status/body-shape evidence; confirm zero upstream access from anonymous cases, no brand/Cookie/Location leaks. This is local exposure validation, not a completed external public scan.
- [x] **Step 3: Install Debug APK to Pixel 10 and temporarily reverse port 3090.** Pair using one-time local code, check session/model list, send harmless prompt in dedicated test session, observe stream, stop, approve/reject controlled harmless task, background completion notification, ZIP export, app restart and device revoke. Test interruption/restoration of shell connection without duplicate prompt submission. Confirm no app traffic targets dsh port directly in bridge mode; remove temporary reverse and restore changed input method at finish.
- [x] **Step 4: Run final Go/Android checks and cross-build artifacts.** Use CGO_ENABLED=0; build Windows amd64/arm64, macOS amd64/arm64, Linux amd64/arm64 from bridge module.

```sh
go test -race ./...
go vet ./...
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o dist/dsh-bridge-windows-amd64.exe ./cmd/dsh-bridge
GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build -o dist/dsh-bridge-darwin-arm64 ./cmd/dsh-bridge
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o dist/dsh-bridge-linux-amd64 ./cmd/dsh-bridge
```

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease` from repo root. Expected: GREEN; record actual tool versions, counts and tested platform, mark Windows/Linux binaries as cross-built unless physically run.
- [x] **Step 5: Perform one fresh whole-branch review using GPT-6-astra**, with spec, plan, Review Focus and ledger. Fix important findings with regression tests, record limits, and commit validation with message `test: validate private transport and Pixel pairing`.

## Self-review and execution handoff

Coverage: configuration/routes (Task 1), pairing/credential transactions (Task 2), HTTP/upstream/export (Task 3), WS/resource/revocation (Task 4), preserved dsh protocol (Task 5), secure storage/background/export (Task 6), agy/native UI (Task 7), TLS/Pixel/cross-platform verification (Task 8). Each Review Focus case is assigned to Tasks 1/5/6, 2, 5/6/8, 4, and 6/8 respectively.

推荐 Native 执行：主代理依次实现，agy 独立交付连接页设计，最后一次独立审查整个分支。任务之间共享传输和认证接口较多，统一实现可减少交接成本。若用户选择 Subagent-driven，则逐任务独立实现和审查；所有 Codex 子代理仍遵守 GPT-6/6.1 限制。

执行时优先复用现有干净的 `codex/dsh-rc2-core` managed worktree，以其已验证产品基线为起点，将本轮规格和计划的文档提交带入后建立 `codex/private-transport` 分支。开始前比对原工作区与基线，保留用户新增修改；不从原 checkout 的旧 main 产品快照开始。若同步回原工作区，逐文件确认未被用户并发修改后才更新，不自动合并、推送或发布。


实施完成记录：2026-10-10；最终验收见 `docs/qa/private-transport/validation.md`。Task 6 导出按记录的 Ruling 统一使用 SAF；保留分支和原工作区，不自动 merge/push/部署。
