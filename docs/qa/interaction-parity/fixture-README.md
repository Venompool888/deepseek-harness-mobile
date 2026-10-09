# Synthetic DSH rc.2 interaction fixture

This is a loopback-only, synthetic upstream for temporary Android QA. It does not read credentials, contact a real DSH process, execute tools, or authorize real approvals. It is deliberately not production-quality WebSocket infrastructure.

## Start

Requires Node.js 18+; there are no package dependencies. The service binds exactly to `127.0.0.1:3082`; it has no LAN listener. Start only when the parent agent is ready to run the isolated QA session:

```sh
node docs/qa/interaction-parity/fixture-upstream.mjs
```

Stop with Ctrl-C. The mock upstream itself has no TLS. The parent must put the separate temporary bridge in front of it and configure its upstream origin as `http://127.0.0.1:3082`. This fixture does not create a bridge pair or manage certificates.

## Admin controls

All admin routes require a loopback peer and are outside the bridge allowlist. Inject only synthetic records:

```sh
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"approval","sessionId":"qa-session-a"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"question","sessionId":"qa-session-a"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"plan-review","sessionId":"qa-session-a"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"multiselect","sessionId":"qa-session-a"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"multiquestion","sessionId":"qa-session-a"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"free-text","sessionId":"qa-session-a"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"timed-question","sessionId":"qa-session-a"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"subagent-approval","sessionId":"qa-session-a"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/inject -H 'Content-Type: application/json' -d '{"scenario":"two-sessions-multiple-pending"}'
curl -sS -X POST http://127.0.0.1:3082/__qa/continued -H 'Content-Type: application/json' -d '{"sessionId":"qa-session-b","callId":"qa-call-continued"}'
curl -sS http://127.0.0.1:3082/__qa/state
```

A timed question request includes `wait:{callId,timed:true}`. Its associated mux stream endpoint is `userQuestions/attachWait`; the fixture reports 60 seconds remaining and keeps the stream open until the client cancels or the local admin triggers timeout. A continued question is represented in `session/follow` and `session/control` snapshots under `projections.values.userQuestions.active` with `state:"continued"`, and is answerable through the unary `userQuestions/answer` method. The fixture accepts `{agentId,callId,answer:{answers:[{id,selected,custom?}]}}`, checks the batch IDs against the pending projection, and returns the native nested `RemoteResult` `{ok:true,value:boolean}` inside the gateway RPC response. Use the synthetic UI flow or a direct local request only; no real user answer is sent upstream.

To simulate host timeout for a pending `timed-question`, first read its `eventId` or `wait.callId` from `/__qa/state`, then call `POST /__qa/timeout` with either `{"eventId":"qa-event-0001"}` or `{"callId":"qa-call-0001"}`. This updates the question projection to `continued`, ends matching attachWait streams, cancels the pending waterfall for active clients that received it, and records the synthetic timeout.

To cancel a currently pending waterfall, read its synthetic event id from `/__qa/state`, then:

```sh
curl -sS -X POST http://127.0.0.1:3082/__qa/cancel -H 'Content-Type: application/json' -d '{"eventId":"qa-event-0001"}'
```

Clear synthetic state with `POST /__qa/reset`.

## Wire contract represented

- Unary requests: `POST /api/<method>` containing `{type:"client-request",rpcId,method,payload:{args}}`; responses preserve `rpcId` and return `{type:"server-response",rpcId,result:{ok:true,value}}`.
- Native mux: WebSocket `GET /api/remote.mux`; client `open` is `{type:"open",streamId,endpoint,payload:{args}}`, and server items are `{type:"item",streamId,value}`. `cancel` is accepted by `streamId`.
- `session/follow` returns the existing synthetic snapshot fixture shape. `session/control` returns `{type:"baseline",value:{projections:{sessionId:{asOfSeq,values}}}}`.
- `$events` first sends `ready` with a fresh `clientId`, then replays every still-pending waterfall as `waterfall` items. `$events/result` requires a live event stream client id that was actually delivered the known pending event, records the exact result, and cancels the waterfall for clients that saw it; reconnect creates a new client id and replays events not yet settled. Approval events carry a `callId` matching a synthetic native `tool/call` record whose safe command is `echo INTERACTION_QA_ONLY`, plus bilingual `displayReason` text.
- `user-questions/request` is a waterfall payload with `questions`; answers use `{answers:[{id,selected,custom?}]}` as the result value. Question fixtures cover generic single choice, plan-review intent, multi-select, multiple questions, free text, timed foreground wait, continued projection, cancellation, and more than one pending interaction across two sessions.

Contract references used while building the fixture: the tagged `remote-events.ts` allowlist (including both `approval/request` and `user-questions/request`), Gateway `stream-protocol.ts`, and `user-questions/src/index.ts` / `types.ts` at [`dsh-v0.2.0-rc.2`](https://github.com/deepseek-ai/deepseek-harness/tree/dsh-v0.2.0-rc.2). Local app wire evidence is `HarnessRpc.kt`, `HarnessRemoteMux.kt`, `HarnessApi.kt`, `HarnessSessionState.kt`, and `app/src/test/resources/dsh-rc2/session-follow.json`.

## Current bridge gate to account for

The repaired bridge explicitly allows `userQuestions/answer` and `userQuestions/attachWait` in addition to `$events/result` and `$events`. An older bridge binary rejects those methods; build the current source before running these cases. The fixture admin routes must stay local and should never be added to the bridge allowlist.

## QA cases to exercise

1. Open either synthetic session and confirm initial session snapshot and controls load.
2. Inject same-session `approval`; verify one Reject/Allow once choice settles exactly one `$events/result` and the outcome appears in `/__qa/state`.
3. Inject approval before opening `$events`, then connect/reconnect; verify ready precedes replay and the same event id remains answerable.
4. Inject approval for `qa-session-b` while A is selected; verify session scoping and no accidental answer against A.
5. Inject `subagent-approval`; the fixture adds a child directory row with `origin:subagent` and `parentSessionId`, then emits the request with the child Agent ID. Verify legal child follow addressing and a read-only child page with an actionable approval.
6. Inject each question variant and compare rendered option shapes, selection/custom text, and exact answer object recorded in state.
7. Inject a timed question; open `userQuestions/attachWait` using `{agentId,callId}`; verify remaining duration and cancellation behavior.
8. Seed a continued question via `/__qa/continued`, reopen `session/follow`, answer via `userQuestions/answer`, then verify it leaves `active`, enters `settled`, and no duplicate answer is accepted.
9. Inject `two-sessions-multiple-pending`; inspect whether simultaneous interactions remain individually addressable and whether the UI reconciles each response.
10. Cancel an event and verify a `cancel` item dismisses only the matching event id.

The mock can test Android-to-bridge serialization, routing, UI state, correlation, and expected error branches. It cannot establish that a real DSH rc.2 host emits each fixture, that a child Agent maps to a user-visible Session, or that real Host timeout/ownership/race semantics match this simplified implementation. Keep a separate harmless dedicated DSH validation session for those claims; do not approve any real tool action as part of this fixture phase.

Unary session methods that use the rc.2 request wrapper (including session/projections/page) are unwrapped from `args.request`; session/list uses `_request`. Root list rows omit origin; only child rows declare `origin:"subagent"`. The mock wait is held until the admin timeout, rather than pretending to implement real Host scheduling.
