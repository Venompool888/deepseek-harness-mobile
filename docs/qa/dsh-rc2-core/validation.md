# rc.2 Android core validation — 2026-10-09

Target: bundled official dsh 0.2.0-rc.2 Web, localhost:3080. Native Kotlin Views client. Device: connected Pixel 10. Debug package: `app.deepseek.harness.mobile.debug`; existing release/legacy packages and data retained.

## Verified

- Root launch-link parsing, invalid/duplicate tokens, clean saved origin; token exchange through the real connection dialog.
- Same-authority Harness Cookie filtering and simultaneous Access Bearer/Cookie headers; actual native HTTP contract test captures headers. External Access login itself was not exercised against a real protected deployment.
- Exact Remote request names, argument names, response correlation, primitive/array values and business error codes.
- Logical mux routing/cancel/error, snapshot/journal duplicate/gap handling, assistant revision gaps, compact stream timestamps, surface replacement, projection watermarks, historical page merge.
- Actual dedicated Pixel session: create, send, real `PIXEL_RC2_OK` reply, streamed reasoning/text and settled Connected state.
- Approval rejection and allowance of synthetic `printf` commands. Pause/Home → resume recovered an outstanding approval under fresh correlation; Allow once removed the card and the task completed. Commands changed no files.
- Real running task stopped from the native Stop button and settled idle.
- Background run after Home: Android notification record transitioned from ongoing foreground progress to green completion progress; excerpt was populated. Return to app refreshed idle state.
- Model changed to V4-Pro; blank test session changed to minimal Agent preset and reopened selector confirmed the selected marker.
- Preserved Chinese input method after temporary automation switch; DEBUG-only prefill extras overcome ADB key injection transformations without bypassing connection/send buttons.
- Native HTTP lost-receipt test: host accepts first request, returns transport error, retry retains requestId, exactly one admission.
- Debug assemble, all 70 unit tests, Debug lint and Release lint passed. Final installed APK is the Debug build.

## Review fixes

One independent GPT-6-family review found authority leakage, approval replay/expiry, required-stream failure, selection generation, projection watermarks, live running status and uncertain-send identity problems. Each was addressed in the consolidated fix pass. Protocol/state regressions observed RED→GREEN; additional actual HTTP transport tests pass. Activity handlers now gate success/auth/error and approval callbacks; foreground polling only performs recovery when no live carrier exists. Required logical stream completion fails its carrier; unavailable/forbidden methods present a compatibility error.

## Limits

- Physical network loss, an approval answered from a second Desktop UI, and delayed in-flight Activity selection responses were checked in lifecycle/source tests and guards, but not all reproduced as end-to-end device faults.
- Large font scaling, light theme, TalkBack traversal and older-history button placement have not received a full device audit. Existing theme and scroll behavior remain; these are follow-up UI acceptance work.
- Directory browsing may be unavailable on the installed Web profile's native picker. The typed host error is shown; no fallback endpoint is invented.
- Compatibility is rc.2 only. Advanced Web-only features remain outside this round.
- The notification service retains its existing polling cadence; foreground live state uses Remote streams.

## Decisions and deferred details

- Generic RPC decoding returns Any? to preserve real array/scalar/void results; adapter operations explicitly select their expected shape. Wrong shape fails the operation.
- Work was isolated while preserving the user's dirty baseline in its own commit; reviewed files are synchronized back only if the baseline still matches, avoiding overwriting concurrent edits.
- Background polling remains independently owned by the service; completion freshness is bounded by its poll interval.
- DEBUG intent prefill is test tooling only. Release ignores it; transient extras are removed from the current Intent. Connection/send remain actual UI actions.
- Existing package migration/OAuth/theme changes are user baseline. No additional review of that baseline is claimed.
- Deferred minors: some errors/context labels remain single-language; live state retains and rebuilds the current follow's history and replacement scan can become quadratic over very long sessions. Complete localization and incremental bounding are follow-up work.

USB localhost verification used `adb reverse tcp:3080 tcp:3080`; temporary mapping is removed at task completion. The Mac server remains bound to loopback. A later phone session needs a reachable server address or a new USB reverse mapping.
