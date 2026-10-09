# SDD ledger — plan: docs/superpowers/plans/2026-10-09-private-transport.md

Terminology (2026-10-10): bridge/custom transport server means **dshbridge**; upstream/native server means **dsh**. Task IDs, commands, test names and past review history below retain their recorded names. See [server roles](../../architecture/server-roles.md).

Base: 28493e4; isolated branch codex/private-transport.
Pre-flight: Tasks 1→3/4 config/routes; 2→3/4 device auth; 3→4 upstream headers; 5→6/7 transport/profile; 6→7 state/store; signatures consistent.
Ruling: reuse existing managed worktree and new branch, preserve original checkout — no product differences found — cost if wrong: later file sync must reject concurrent changes.
Task 7 design may proceed in parallel in isolated output directory, no product code ownership conflicts.
Task 1: complete (commits 28493e4..82e760b, tests: /tmp/dsh-private-transport-tools/go/bin/go -C bridge test ./internal/config → ok  	app.deepseek.harness/bridge/internal/config	(cached))
Task 2: complete (commits 82e760b..5fbc32d, tests: /tmp/dsh-private-transport-tools/go/bin/go -C bridge test -race ./internal/auth ./internal/config → ok  	app.deepseek.harness/bridge/internal/config	(cached))
Task 3: complete (commits 5fbc32d..64a0a45, tests: /tmp/dsh-private-transport-tools/go/bin/go -C bridge test -race ./... → ok  	app.deepseek.harness/bridge/internal/upstream	(cached))
Task 4: complete (commits 64a0a45..f1af532, tests: /tmp/dsh-private-transport-tools/go/bin/go -C bridge test -race ./... → ok  	app.deepseek.harness/bridge/internal/upstream	(cached))
Ruling: external CLI revocation reloads durable auth state per request/frame and closes idle streams within 250 ms, rather than cross-process callback — portable local administration, no extra IPC surface — cost if wrong: revoked idle socket may remain open briefly, each frame admission validates state; already accepted operations cannot be rolled back.
Task 5: complete (commits f1af532..b853429, tests: ./gradlew :app:testDebugUnitTest → Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.4.1/userguide/configuration_cache_enabling.html)
Task 6: corrupted metadata test caught an unvalidated mode marker; added fail-closed marker validation and forgotten-profile tombstone handling.
Ruling: export uses Storage Access Framework on all supported Android versions — one authenticated streaming path, user chooses Downloads or another destination, no raw DownloadManager URL — cost if wrong: one extra destination-selection step on API 29+ instead of automatic Downloads.
Task 6: complete (commits b853429..52048ac, tests: ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease → Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.4.1/userguide/configuration_cache_enabling.html)
Task 7: complete (commits 52048ac..b929b1c, tests: ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease → Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.4.1/userguide/configuration_cache_enabling.html)
Final review: GPT-6-astra reviewed 28493e4..3a9b1e0 read-only; seven Important findings, zero Critical, one Minor. One implementer fix pass; no second review.
Final: fixed queued cross-server mutations and event replies — delayedOldPromptHasZeroRequestsAtReplacementCarrier / queuedOldMutationCannotReachNewServer RED→GREEN; whole suite 88/88 Android.
Final: fixed mux closure during construction — closeWhileMuxConstructionIsBlockedInvalidatesLateSocket RED→GREEN; suite 88/88 Android.
Final: fixed forgotten/same-origin profile callbacks, direct dialog admission and export ownership — sameOriginRePairAndForgetInvalidateCallbacksAndExportAdmission RED→GREEN; common callback/worker lease integrated in Activity; suite 88/88 Android.
Final: fixed old background poll consuming replacement watches — oldBackgroundPollCannotConsumeNewWatch RED→GREEN; service applies each result/notification/stop under generation guard; suite 88/88 Android.
Final: fixed stalled HTTP bodies / downstream writes — TestStalledBodyReleasesHandler RED→GREEN; TestStalledExportReleasesRequestSlot checks bounded write and zero retained slots; Go race suite green.
Final: fixed truncated / oversized ZIP success — TestTruncatedExportFailsClient RED→GREEN, TestExportOverflowFailsClient GREEN, abortedChunkedExportDeletesPartialDestination RED→GREEN; suite 88/88 Android and Go race green.
Final: fixed direct HTML login classification — directHTMLResponseRequestsAuthentication RED→GREEN; suite 88/88 Android.
Final: fixed revoke during WS establishment admission — TestRevokeDuringUpstreamWSHandshake RED→GREEN; revalidate before public upgrade, handshake bounded at 15 s; Go race suite green.
Final: minor (deferred): unused HarnessRemoteConnection companion OkHttpClient remains; effective policy uses harnessStreamClient, no behavioral effect.
Final: physical pairing field RED→GREEN: single-line initialization had overridden password variation; input type and transformation reapplied, autofill/state saving disabled; final Pixel hierarchy masks the code and has no plaintext copy.
Final: recovery form suppresses duplicate credential dialogs through a separate visible-form flag, reset on dismiss; same revoked origin re-paired and restored the dedicated four-turn QA session.
Ruling: public domain/TLS proxy/firewall/external scan remain outside this approved local implementation — cost: actual deployment still needs host-specific setup and external verification; no invisibility claim.
Ruling: macOS arm64 binary was run; other five targets are cross-built only, Windows ACLs not tested here — cost: destination OS startup/permissions remain deployment acceptance work.
Ruling: validate native English dark pairing at normal/150% font and AGY English light 150% preview; full TalkBack and broader device matrix deferred — cost: accessibility polish or device-specific layout fixes may still be needed.
Ruling: traffic-analysis resistance, multi-user hosting, outbound relay, installers and process supervision remain excluded — cost: this is a bounded transport bridge, deployment operations require separate work.
Final: native 150% font test found ToggleGroup overriding wrapping and the dark dialog title lacking palette color; apply wrapping after child registration, checked-state colors and explicit title palette. Keyboard overlap receives scroll padding so lower form controls remain reachable.
Final: keyboard visible at 150% font, Pair and connect physically reachable above the keyboard after scroll; final APK installed, original font 1.0 and WeType input method restored.
Task 8: complete (commits b929b1c..0b0b313, tests: bash -c '/tmp/dsh-private-transport-tools/go/bin/go -C bridge test -race -count=1 ./... && /tmp/dsh-private-transport-tools/go/bin/go -C bridge vet ./... && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease' → Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.4.1/userguide/configuration_cache_enabling.html)

Delivery cleanup: task-owned host/bridge/design processes stopped; USB 3090 reverse removed; font 1.0 and original WeType restored; browser viewport override reset and temporary design tab closed. Plaintext task credential inputs removed. Local-only scope, branch retained.

Original checkout delivery: 53 task files synchronized after baseline comparisons, no concurrent conflicts, existing migration/OAuth/theme work preserved. Go race/vet and 88 Android tests/build/lint passed there; inherited v26 icon resource adds one warning per lint variant (77/79 warnings, zero errors). Final APK installed and compared with Pixel base.apk by SHA-256. Debug app stopped after tests to avoid continuing local QA work.
