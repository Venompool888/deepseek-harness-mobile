# Harness Android feature parity

Status checked against the local Harness source and the Pixel build on 2026-08-18.

## Native and wired

- Session list, selection, creation, rename, fork, history, cancel, and periodic running-state refresh. Long-press a drawer session for rename/fork.
- Remembered default workspace plus a manual workspace chooser for every new session.
- Model selection and adapter-advertised Thinking effort through `session.selectModel`.
- Queue and steer prompt delivery through the official `session.prompt` mode field.
- Default and Plan collaboration modes through the official `/plan` command and `plan` projection.
- Permission presets through the official `/permission` command and `permissions` projection; options are host-provided rather than hard-coded.
- Agent preset roster and blank-session selection through `agentPreset.list` and `agentPreset.select`.
- Local session search, workspace/default-directory management, and the ChatGPT-style drawer information architecture.
- Authenticated `/api/events.mux` WebSocket updates with history polling retained as a recovery fallback.
- Character-by-character assistant reveal for both partial and single-shot completed responses, including a semi-transparent newest-character fade-in.
- DeepSeek first-run credential onboarding derived from the same `llm.providers` + `settings.describe` + `credentials.describe` join as Web. Writable credentials can be saved in place; remote/read-only configuration gets an explicit host-Web recovery message instead of a dead control.
- Model-catalog partial failures from `session.models.failures`, shown in the model picker with their provider error and a retry action.
- Native approval request card and allow/deny response.
- Final-assistant action strip matching Web: clipboard copy with confirmation, positive/negative message feedback with compare-and-set reconciliation, branch-at-message, and turn runtime/TTFT/token-rate metrics. Narrow screens keep the official single-line order and allow horizontal scrolling.

## Web source audit: easy-to-miss states

- Implemented: official DeepSeek credential missing, read-only/unavailable credential configuration, provider catalog partial failure, whole refresh failure, prompt submission restore/error, terminal turn error, retry activity, and max-token activity.
- Intentionally different: Web silently skips onboarding when its configuration adapter is absent or read-only. Android keeps normal startup quiet too, but an explicit send/model action or a real `MISSING_CREDENTIAL` turn shows a recovery dialog because a disabled mobile control otherwise looks broken.
- Still absent: the internal-testing welcome/onboarding notice, attachment upload failure/retry, native question composer, plan review, and the full settings-schema editor.

## Still needs a native surface

- Session archive/unarchive, manual reorder, older-history pagination, and queued-item edit/remove.
- Workspace create/rename/delete/reorder and native directory browsing/adoption.
- Agent preset read/copy/open/delete authoring screens.
- Image/text attachments and upload failure/retry.
- Question composer, plan-review card, goal editing, subagent tree, background jobs, schedules, workflow runs, terminal, deliverables/downloads, skills, commands, and settings schema editor.

The Settings footer keeps the authenticated Harness Web surface reachable for these advanced domains during the native migration. That fallback preserves access, but it is not counted as native parity.
