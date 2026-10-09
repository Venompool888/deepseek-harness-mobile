# Private dsh transport

This service exposes its own authenticated transport while dsh remains bound to loopback. It preserves dsh 0.2.0-rc.2 message formats. Random routes are stable deployment configuration, not an authentication substitute.

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

Android's Private server mode is the default pairing flow; Direct connection remains available for explicit local/native connections. Forgetting a private profile invalidates its work and credentials and requires fresh pairing. Export uses the system file picker on every supported Android version. Web management stays on the server computer in private mode. A failed private connection never falls back to native dsh routes.

No public domain, certificate, reverse proxy, firewall rule, installer, or process supervisor is provisioned by this repository. Deploy the bridge with your own trusted TLS certificate or an isolated TLS reverse proxy, and keep the dsh listener on loopback. External exposure still needs to be verified on that actual host.
