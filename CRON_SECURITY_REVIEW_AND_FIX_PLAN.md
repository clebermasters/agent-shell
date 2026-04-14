# Cron Security Review And Fix Plan

Date: 2026-04-14

## Purpose

This document captures the current cron-related issues in the uncommitted worktree, with special focus on:

- the security risks in the new cron metadata persistence approach
- the functional mismatches between frontend clients and backend handlers
- what must be preserved so we do not break existing cron jobs
- the safest sequence of changes required before these remaining cron files can be committed

This review is based on the current local code in:

- `backend-rust/src/cron/mod.rs`
- `backend-rust/src/cron_handler.rs`
- `backend-rust/src/types/mod.rs`
- `backend-rust/src/websocket/cron_cmds.rs`
- `flutter/lib/features/cron/screens/cron_job_editor_screen.dart`
- `flutter/lib/data/models/cron_job.dart`
- `flutter/lib/data/services/websocket_service.dart`
- `android-native/app/src/main/java/com/agentshell/feature/cron/CronJobEditorScreen.kt`
- `android-native/app/src/main/java/com/agentshell/feature/cron/CronViewModel.kt`

## Executive Summary

The cron feature should be kept. The new direction is useful:

- manual cron jobs should continue to work
- AI cron jobs should be supported
- AI metadata should be recoverable when jobs are reloaded from crontab

However, the current uncommitted cron set is not safe to commit yet.

There are two high-priority blockers:

1. Security: user-controlled cron metadata is written into crontab comment lines without newline sanitization. This creates a cron injection path.
2. Contract mismatch: the REST cron handler cannot represent manual command jobs correctly, while the WebSocket path can. This creates inconsistent behavior across transports.

There is also one important product/compatibility change that needs an explicit decision:

3. AI jobs are currently normalized into a Codex/OpenAI-wrapper execution path, overriding provider/model semantics from the request.

The app itself currently uses the WebSocket path for cron create/update, not the REST handler. That means:

- existing app behavior is less broken than the raw backend diff first suggests
- but the backend is still inconsistent and should be fixed before the cron feature is committed

## Current Behavior By Transport

### WebSocket path

The app creates and updates cron jobs through WebSocket messages:

- `flutter/lib/data/services/websocket_service.dart`
- `backend-rust/src/websocket/cron_cmds.rs`

In this path, the full `CronJob` object is sent, including `command`.

This means command-based cron jobs are still representable over WebSocket today.

### REST path

The REST handler uses `CreateJobRequest` in `backend-rust/src/cron_handler.rs`.

That request shape currently includes:

- `name`
- `workdir`
- `prompt`
- `llm_provider`
- `llm_model`
- `schedule`
- `enabled`

It does not include `command`.

The handler then constructs `CronJob { command: String::new(), ... }`.

This means manual command jobs cannot be represented over REST today.

### Cron manager path

`backend-rust/src/cron/mod.rs` now includes `normalize_ai_job()`.

Behavior:

- if `workdir` and `prompt` are both present, the manager rewrites `job.command`
- it also forces `llm_provider = "openai"`
- it normalizes `llm_model` to a Codex/OpenAI-compatible model

This is a deliberate behavior change from generic provider/model execution to a Codex/OpenAI-wrapper execution path.

## Security Finding 1: Cron Injection Through Metadata Comments

### Root Cause

`backend-rust/src/cron/mod.rs` writes metadata comments like:

- `# Workdir:<value>`
- `# Prompt:<value>`
- `# LLM-Provider:<value>`
- `# LLM-Model:<value>`

This happens in `metadata_comments()`.

The values are written with raw `format!()` string interpolation and no newline sanitization.

Relevant locations:

- `backend-rust/src/cron/mod.rs:229`
- `backend-rust/src/cron/mod.rs:237`
- `backend-rust/src/cron/mod.rs:240`
- `backend-rust/src/cron/mod.rs:247`
- `backend-rust/src/cron/mod.rs:254`

### Why This Is Dangerous

Crontab is line-oriented. A newline inside any metadata field is not "data"; it becomes a new crontab line.

That means a malicious or malformed prompt can break out of the intended comment line and inject a real cron entry.

Conceptually:

- intended stored line: `# Prompt:<user data>`
- actual stored result when `<user data>` contains a newline:
  - first line stays a comment
  - next line becomes an independent crontab line

This is not a shell quoting problem. The shell quoting logic in `build_ai_command()` does not protect this path, because the bug happens before the shell is involved, during crontab serialization.

### Practical Impact

Impact if exploited:

- arbitrary scheduled command execution as the same Unix user that owns the crontab
- persistence beyond the immediate app session
- corruption of the managed crontab block
- possible confusion during later parsing, loading, toggling, or deletion

### Threat Model

This is not only a "malicious user typing in the UI" issue.

Possible sources of the unsafe value:

- a compromised client
- another frontend using the backend
- any future REST/API integration
- imported or synchronized cron definitions
- programmatic callers

If this backend is reachable on a remote server, the impact becomes more serious because a remote client can persist scheduled execution on that host.

### Severity

High.

Reasoning:

- direct persistence into a scheduler
- user-controlled input
- low complexity
- command execution impact

### Secondary Data Integrity Problem

Even without malicious intent, multiline prompt/workdir content cannot round-trip safely through the current comment format.

Current side effects:

- only the first line is reliably parsed back into metadata
- subsequent lines can become stray comments or cron lines
- leading/trailing whitespace is lost because the parser uses `.trim()`

So this is both a security issue and a data corruption issue.

## Functional Finding 2: REST And WebSocket Contracts Diverged

### Root Cause

The shared job model still includes `command`:

- `backend-rust/src/types/mod.rs:103`

The app editors also still populate `command`:

- `flutter/lib/features/cron/screens/cron_job_editor_screen.dart:134`
- `android-native/app/src/main/java/com/agentshell/feature/cron/CronJobEditorScreen.kt:90`

The WebSocket path passes that through:

- `flutter/lib/data/services/websocket_service.dart:351`
- `backend-rust/src/websocket/cron_cmds.rs:18`

But the REST handler dropped `command` from the request shape:

- `backend-rust/src/cron_handler.rs:9`

And then forces:

- `command: String::new()`

in both create and update:

- `backend-rust/src/cron_handler.rs:33`
- `backend-rust/src/cron_handler.rs:73`

### Impact

Manual command jobs:

- still work through WebSocket
- do not work through REST

This means the system currently has two different meanings for "create cron job", depending on transport.

That is a maintenance risk and a future integration trap.

## Product Finding 3: AI Jobs Are Now Being Rewritten To A Specific Backend

### Root Cause

`normalize_ai_job()` in `backend-rust/src/cron/mod.rs` rewrites any AI job into:

- `OPENAI_BASE_URL=<wrapper>`
- `OPENAI_API_KEY=<wrapper>`
- `skill-agent --streaming --llm-provider openai --llm-model <normalized model> agent <prompt>`

It also forces:

- `llm_provider = "openai"`

and normalizes model selection with:

- `normalize_ai_model()`

Relevant locations:

- `backend-rust/src/cron/mod.rs:181`
- `backend-rust/src/cron/mod.rs:195`
- `backend-rust/src/cron/mod.rs:216`

### Why This Matters

This may be the intended product direction, but it is not a neutral refactor.

It changes behavior from:

- "run the provider/model requested by the job"

to:

- "route AI cron jobs through the local OpenAI-compatible wrapper and a Codex-compatible model"

That may be correct, but it needs to be explicit.

### Compatibility Risk

If any existing or future client expects:

- Anthropic models
- generic provider pass-through
- provider-specific execution behavior

then the new logic silently changes the job.

This is not a security problem by itself, but it is a compatibility and product-contract issue.

## Data Model Finding 4: Job Type Is Implicit, Not Explicit

Right now the backend infers job type from field presence:

- if `workdir` and `prompt` exist, treat it as an AI job
- otherwise, leave `command` alone

This can work, but it is fragile unless validation is strict.

Current risks:

- partial AI payloads can be accepted accidentally
- both `command` and AI fields can be present at once
- different clients may not agree on precedence

This is manageable, but only if the validation contract is formalized.

## What Must Be Preserved

The fix must preserve these guarantees:

1. Manual command cron jobs must continue to work exactly as before.
2. AI cron jobs must continue to work.
3. Existing app clients that use WebSocket must not break.
4. Crontab parsing/loading must continue to support existing already-written jobs.
5. Future REST and WebSocket behavior must be consistent.

## Recommended Direction

The safest direction is:

- keep support for both manual jobs and AI jobs
- keep command execution as the canonical executed cron line
- keep AI metadata, but store it safely
- align REST and WebSocket behavior around the same validation rules

Do not remove the feature.

Do not reduce the system back to "command-only".

Do not commit the current cron files until the storage and validation contract is corrected.

## Safe Backend Contract

### Recommended semantic rules

Rule 1:

- a manual job is valid when `command` is non-empty and AI fields are absent or empty

Rule 2:

- an AI job is valid when `workdir` and `prompt` are both non-empty

Rule 3:

- for AI jobs, backend derives the final executable `command`

Rule 4:

- if both a meaningful `command` and meaningful AI fields are present, backend should either:
  - reject the request as ambiguous, or
  - accept AI as authoritative and document that clearly

Recommendation:

- reject ambiguous payloads

That is safer and easier to reason about.

### Recommended request shape

There are two viable approaches.

#### Option A: Minimal change

Keep current `CronJob` shape and formalize validation:

- `command`
- `workdir`
- `prompt`
- `llmProvider`
- `llmModel`

This is the fastest path and requires the smallest frontend churn.

#### Option B: Explicit job mode

Add an explicit field such as:

- `jobType = "command" | "ai"`

This is cleaner long-term, but it is a larger API change.

Recommendation:

- use Option A first
- only move to explicit `jobType` if the cron feature expands further

## Safe Metadata Storage

The current comment-per-field approach should not remain as raw text.

### Recommended replacement

Store one structured metadata line in a safe serialized form.

Preferred format:

- one comment line
- JSON payload
- base64url encoded

Example shape:

- `# AgentShell-Meta:<base64url(json)>`

Example JSON keys:

- `version`
- `kind`
- `workdir`
- `prompt`
- `llmProvider`
- `llmModel`

### Why This Is Better

- newline-safe
- easier to version
- easier to extend
- easy to validate on load
- avoids line-oriented injection from raw user text

### Backward Compatibility

The parser should support both:

- new structured metadata line
- old legacy per-field comment lines

That allows existing managed jobs to continue loading after upgrade.

## Validation Requirements

Before writing anything to crontab, backend should validate:

- `name` is non-empty
- `schedule` is valid
- manual job: `command` is non-empty
- AI job: `workdir` and `prompt` are non-empty
- ambiguous payloads are rejected
- metadata fields do not contain NUL bytes

Even after moving to encoded metadata, validation still matters.

## Recommended Implementation Plan

### Phase 1: Security hardening first

Goal:

- eliminate the crontab injection path before any cron refactor is committed

Tasks:

1. Replace raw metadata comment lines with one safe encoded metadata line.
2. Add parser support for the new metadata line.
3. Keep legacy parsing for previously stored jobs.
4. Add tests proving newline-containing metadata cannot create extra cron lines.

### Phase 2: Contract unification

Goal:

- make REST and WebSocket behave the same way

Tasks:

1. Update `CreateJobRequest` to support manual command jobs and AI jobs.
2. Add shared backend validation logic for job mode detection.
3. Ensure REST create/update can preserve manual jobs.
4. Ensure WebSocket and REST both produce the same stored `CronJob`.

### Phase 3: Product decision lock-in

Goal:

- make the AI routing behavior explicit

Tasks:

1. Decide whether AI jobs should always use the OpenAI-compatible wrapper.
2. If yes, document that as policy and keep provider/model normalization intentional.
3. If no, preserve caller-selected provider/model and remove forced normalization.

This is a product decision, not just an implementation detail.

### Phase 4: Frontend consistency review

Goal:

- keep UI behavior clear and predictable

Tasks:

1. Confirm both editors send valid manual-job and AI-job payloads.
2. Make validation messaging explicit in the UI.
3. If ambiguous payloads are rejected, surface the error cleanly.

## Recommended Test Matrix

These tests should exist before committing the cron set.

### Security tests

1. AI prompt containing newline does not create extra crontab lines.
2. Workdir containing newline does not create extra crontab lines.
3. Encoded metadata round-trips correctly.
4. Legacy raw metadata still parses.

### Manual job tests

1. Create manual job via WebSocket.
2. Update manual job via WebSocket.
3. Create manual job via REST.
4. Update manual job via REST.
5. Toggle manual job enabled/disabled and preserve command.

### AI job tests

1. Create AI job via WebSocket.
2. Update AI job via WebSocket.
3. Create AI job via REST.
4. Update AI job via REST.
5. Load AI job from crontab and recover metadata.

### Validation tests

1. Reject empty command when AI fields are absent.
2. Reject missing workdir for AI jobs.
3. Reject missing prompt for AI jobs.
4. Reject ambiguous payload containing both command mode and AI mode.

## Recommended Commit Strategy

Do not commit the remaining cron files as one mixed patch.

Recommended commit breakdown:

1. `fix(cron): harden metadata storage and parsing`
   - backend only
   - includes security tests

2. `fix(cron): align manual and AI job validation across transports`
   - backend plus any required frontend/request updates
   - includes REST and WebSocket parity tests

3. `docs(cron): document cron job modes and AI routing`
   - optional, but useful once the product decision is final

4. `chore(flutter): refresh pubspec lock`
   - separate housekeeping commit if still needed

## Recommended Immediate Next Step

The safest next engineering move is:

1. fix metadata storage first
2. then align REST contract with the WebSocket contract
3. then run cron-focused backend tests
4. only then commit the cron feature

This preserves the feature while reducing the chance of shipping either:

- broken manual jobs
- unsafe crontab serialization

## Final Recommendation

Keep the cron feature.

Keep support for both manual and AI jobs.

Do not merge the current cron patch as-is.

The feature is worth keeping, but it needs:

- secure metadata persistence
- consistent transport contracts
- explicit validation rules
- tests proving that manual jobs are not regressed

