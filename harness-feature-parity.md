# Harness Android feature parity

Status checked against the local Harness source and the Pixel build on 2026-08-15.

## Native and wired

- Session list, selection, creation, rename, fork, history, cancel, and periodic running-state refresh. Long-press a drawer session for rename/fork.
- Remembered default workspace plus a manual workspace chooser for every new session.
- Model selection and adapter-advertised Thinking effort through `session.selectModel`.
- Queue and steer prompt delivery through the official `session.prompt` mode field.
- Default and Plan collaboration modes through the official `/plan` command and `plan` projection.
- Permission presets through the official `/permission` command and `permissions` projection; options are host-provided rather than hard-coded.
- Agent preset roster and blank-session selection through `agentPreset.list` and `agentPreset.select`.
- Image and text attachment intake through Android's system document picker.
- Local session search, workspace/default-directory management, and the ChatGPT-style drawer information architecture.
- Authenticated `/api/events.mux` WebSocket updates with history polling retained as a recovery fallback.
- Character-by-character assistant reveal for both partial and single-shot completed responses, including a semi-transparent newest-character fade-in.

## Still needs a native surface

- Session archive/unarchive, manual reorder, older-history pagination, and queued-item edit/remove.
- Workspace create/rename/delete/reorder and native directory browsing/adoption.
- Agent preset read/copy/open/delete authoring screens.
- Approval/question composer, plan-review card, goal editing, subagent tree, background jobs, schedules, workflow runs, terminal, deliverables/downloads, feedback, skills, commands, and settings schema editor.

The Settings footer keeps the authenticated Harness Web surface reachable for these advanced domains during the native migration. That fallback preserves access, but it is not counted as native parity.
