# dshbridge server

**dshbridge** is our transport server. **dsh server** means the native DeepSeek Harness server. The current design runs these two separate components on the same host: Android connects to dshbridge over HTTPS/WSS; dshbridge forwards supported dsh messages to the loopback dsh server. dsh executes tasks, calls models and owns sessions/workspaces and the native Web interface. dshbridge owns device pairing, authentication, revocation and random route mappings.

The component name is **dshbridge**; the existing CLI/executable name is `dsh-bridge`, with sources under `bridge/cmd/dsh-bridge/`. Command examples below use that existing name. See [server roles and terminology](../docs/architecture/server-roles.md).

dshbridge preserves dsh 0.2.0-rc.2 message formats. Random routes are stable dshbridge deployment configuration, not an authentication substitute. Its pairing codes/device tokens are separate from the native dsh launch token/Cookie. The dshbridge outer protocol version (`protocolVersion: 1`) is separate from the native dsh software version.

## Addresses and credentials

| Setting | Component |
| --- | --- |
| `--upstream` | Native dsh server's loopback origin |
| `--public-origin`, phone configuration `origin` | dshbridge server's client-facing origin; public deployment requires HTTPS |
| `--dsh-token-file` | Native dsh launch token used internally by dshbridge |
| `--dsh-cookie-file` | Existing native dsh Cookie for the selected loopback Host; mutually exclusive with token file |
| `pair`, `devices`, `revoke`, device token, `routes.json` | dshbridge device access and random routes |

Start the loopback dsh server, then configure/start dshbridge and pair the phone. The two processes are maintained separately; dshbridge does not manage the dsh process. Private server mode in Android means dshbridge; Direct connection means native dsh. Native Web management is accessed locally on the dsh server host.

## Build and initialize dshbridge

Build with Go 1.26 or newer: `go build ./cmd/dsh-bridge`.

Initialize a private state directory (outside the repository):

```sh
dsh-bridge init --state /private/bridge-state --listen 127.0.0.1:3090 --upstream http://127.0.0.1:3080 --public-origin http://127.0.0.1:3090
dsh-bridge routes --state /private/bridge-state
```

`routes.json` is the one authoritative mapping from pair/rpc/stream/export to deployment paths; `config.json` contains nonsecret deployment settings. Keep both in backups. Missing or corrupt configuration stops startup. Never publish either mapping or deployment credentials in a public document. On Windows restrict state directory access using the owner's account ACL; POSIX private modes alone do not establish Windows ACLs.

HTTP development is for loopback only. Public clients require HTTPS/WSS. A reverse proxy must point only to this service, never to dsh or its static resources. dsh itself must not have another publicly forwarded listener. This is not NaïveProxy and does not guarantee resistance to traffic analysis.

Generate a one-use, ten-minute pairing code locally with `dsh-bridge pair --state DIR`. Import the `connection` JSON in the phone and enter the separately printed `pairingCode`. This local command output contains a secret; keep it private. List device IDs with `devices --state DIR`; revoke using `revoke --state DIR --device ID`. Server operations reload credential state, so separate admin processes take effect without restart. Back up the private state directory, including `auth.json`. Tokens and pairing codes are stored only as SHA-256 hashes. If a process dies while holding `.auth.lock`, stop all bridge processes before removing that stale lock; contention fails closed instead of automatically breaking another process's lock.

Serve locally with `dsh-bridge serve --state DIR --dsh-token-file PRIVATE_FILE`. The file contains only the local dsh launch token, not a full URL. With public TLS use `--tls-cert CERT --tls-key KEY`. Alternatively use `--behind-tls-proxy` with a loopback listener and an HTTPS public origin; only your TLS proxy may forward to that listener. Plaintext public listeners are rejected. The RPC allowlist is explicit and covers current native operations; it is not a general-purpose HTTP proxy.

Active streams check device validity before forwarding each data frame. The server also checks idle streams every 250 ms and closes revoked connections; new requests observe revocation immediately. Pairing and revoke commands serialize credential transactions across processes. An interrupted pairing response needs a freshly generated pairing code; the service does not replay the exchange.

## Connection protocol examples

All paths below are synthetic documentation values. Every deployment creates its own independent 128-bit random value for each route; each route is split into two URL-safe segments. Keep the generated `routes.json` as the authoritative local maintenance record. Initialization never silently rotates it.

Pairing configuration given to the phone:

```json
{"protocolVersion":1,"origin":"https://gateway.example.test","pairPath":"/Q9w2aX8mN4b/T6pR1yK3cH0"}
```

The phone sends a POST to that deployment's pairing path. The one-use code goes in the JSON body, together with `deviceName`. A successful response supplies `protocolVersion`, `deviceId`, `deviceToken` and the `rpc`, `stream`, `export` relative paths. The token is an independent 256-bit random device credential. The Android client encrypts it with Android Keystore and saves it outside backups; it does not claim that every phone has hardware-backed keys.

For RPC, the device sends `Authorization: Bearer <device-token>` to the generated RPC path. The body preserves dsh's envelope:

```json
{"type":"client-request","rpcId":"example-request-1","method":"session/list","payload":{"args":{"_request":{}}}}
```

WebSocket uses the generated stream path and the same device Authorization header; dsh open/item/cancel/end/error frames retain their structure. Export POST accepts `{"sessionId":"example-session","includeDescendants":true}` and streams a ZIP. None of these public requests needs the internal dsh launch token or cookie.

Known and unknown unauthenticated endpoints return an empty 404 and never reach dsh. The bridge does not serve HTML, JavaScript bundles, native `/api/*`, or a discovery/health page. A valid device credential intentionally unlocks the preserved dsh protocol; randomized paths alone provide no authorization.

## Builds and operation limits

Run `bash bridge/scripts/build.sh` from the repository root, or `bash scripts/build.sh` from this module, with Go on PATH. Outputs are Windows/macOS/Linux, each in amd64 and arm64, under `bridge/dist/`. macOS arm64 was run locally; the other targets are cross-built artifacts.

The HTTP server configures a 16 KiB MaxHeaderBytes setting and a ten-second header deadline (Go may reserve additional parsing space). Bodies have a fifteen-second read deadline, including rejected requests that the HTTP server may drain. RPC bodies are capped at 8 MiB; pair/export bodies at 8 KiB. A device may hold eight HTTP requests and four physical WebSocket connections. RPC upstream forwarding is bounded at sixty seconds; ordinary HTTP responses at seventy-five seconds. WebSocket establishment and writes are bounded at fifteen seconds. Export transfers have a five-minute deadline and a 512 MiB ceiling. A truncated or oversized transfer is aborted, and Android removes its partial destination.

Each request/frame validates its credential. Idle WS revocation checks run every 250 ms. An operation already accepted by dsh cannot be rolled back by deleting the external credential. Device revocation is local administration; the public transport cannot create arbitrary upstream destinations or administrative endpoints.

Android's Private server mode is the default pairing flow; Direct connection remains available for explicit local/native connections. Forgetting a private profile invalidates its work and credentials and requires fresh pairing. Export uses the system file picker on every supported Android version. Native dsh Web management stays on the dsh server host in private mode. A failed private connection never falls back to native dsh routes.

No public domain, certificate, reverse proxy, firewall rule, installer, or process supervisor is provisioned by this repository. Deploy dshbridge with your own trusted TLS certificate or an isolated TLS reverse proxy, and keep the native dsh server listener on loopback. External exposure still needs to be verified on that actual host.

## Connect to the same native Host as Desktop

Pending approvals and foreground questions belong to a running native dsh Host. A separately started Web Host does not share another Host's live interaction queue, even when both installations use the same dsh version. Set `config.json`'s `upstream` to the loopback origin of the Host that owns the task. The Desktop rc.2 Host observed in this validation listens on `127.0.0.1:19387`; this is an observed profile configuration, not a universal discovery rule. A separately started Web Host had used `127.0.0.1:3080`.

For an already authenticated Desktop Host, use `dsh-bridge serve --state DIR --dsh-cookie-file PRIVATE_COOKIE_FILE` with the same TLS flags as the deployment. The private file contains one native `name=value` Cookie, at most 4096 bytes, for that exact upstream authority. dshbridge rejects a mismatched Cookie name, extra cookies, header separators and simultaneous token/cookie options. Keep the file outside the repository with access limited to its owner. The Cookie stays inside dshbridge; Android continues to use its separate device credential.

A fixed Cookie does not refresh itself. If Desktop invalidates it, requests fail and keep presenting that Cookie until the operator replaces it and restarts dshbridge. There is no anonymous fallback. Keep the native dsh listener on loopback and restart only dshbridge when changing its upstream authentication.

The interaction allowlist includes `$events/result`, `userQuestions/answer`, `$events` and `userQuestions/attachWait` alongside the existing session streams. It does not expose native administration or a pending-events discovery endpoint.
