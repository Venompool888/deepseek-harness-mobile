# dsh 0.2.0-rc.2 native adapter contract

Authority: official `dsh-v0.2.0-rc.2` tagged TypeScript source, compared with the local bundled rc.2 Web server on 2026-10-09. Synthetic fixtures contain no user conversations or credentials.

Unary calls use `/api/<namespace>/<method>` with `client-request`, correlation `rpcId`, and `payload.args`. Decode the `server-response` envelope separately from any business-level Result. Preserve object, array, scalar and void values.

| Operation | Remote endpoint | Named arguments |
| --- | --- | --- |
| Session summaries | `session/list` | `_request` |
| Create, projections, selectModel, rename, fork, prompt, cancel | `session/<method>` | `request` |
| Model catalog | `session/modelCatalog` | none |
| Presets | `agentPresets/list` | none |
| Select preset | `agentPresets/select` | `agentId`, `agentPreset` |
| Commands | `commands/execute` | `agentId`, `line`, `submittedAttachments` |
| Provider directory | `llm/listProviders`, `llm/listConfigurableProviders` | none; array return |
| Settings | `settings/describe` | none |
| Credentials | `credentials/describe`, `credentials/set` | `refs`; `ref`, `value` |
| Workspace create | `workspace/create` | `request` |
| Directory browsing | `directoryPicker/list`, `directoryPicker/createDirectory` | `path`; `path`, `name` |
| Feedback | `messageFeedback/list`, `put`, `delete` | `request`; business Result retained |
| Export | `/api/session.export` | existing GET/HEAD fetch route, query sessionId/includeDescendants |

The authenticated WebSocket carrier is `/api/remote.mux`. Open frames contain `streamId`, `endpoint`, `payload.args`; host item/error/end frames share streamId. Cancel each logical stream on disposal. Workspace baseline is `workspace/follow` with no arguments. Session opening is `session/follow` with `request.address={kind:session,sessionId}`, `assistantStream=true`, `maxMessages=80`. There is no resume cursor argument: reconnect with a new authoritative snapshot. `session/control` has no arguments. Session durable sequence, assistant revision, attemptId and chunk index are distinct ordered counters.

Remote events use `$events`: ready establishes clientId; approval/request waterfall carries eventId, agentId, request. Results use `$events/result` with args directly `{clientId,eventId,outcome}`. Only the currently followed session is claimed; other waterfalls receive next. Disconnect discards pending approvals; cancellation invalidates eventId.

Login is GET `/?token=...`, 303 to `./`, and Set-Cookie. Business calls carry clean URLs, the Harness Cookie and optional external Access Bearer together. Redirects during token exchange must remain at the same root origin. Native directory picking may be unavailable on a Web host; its typed error is presented rather than inventing an alternative endpoint.
