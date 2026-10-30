# Offline history and backend memory

The complete offline archive lives in the Android app's Room database and private attachment files. History is retained without a TTL or automatic eviction. The server retains the original agent transcripts and its own overlay events on disk. Preparing an offline download does not require keeping either archive in server RAM.

## Bounded server reads

Codex app-server returns complete threads. AgentShell spools large RPC envelopes to temporary files and walks their turns/items without deserializing the entire thread. It uses the same item conversion as the original history reader, preserving ordinary message rendering, timestamps, and content-derived Android identifiers.

Parsed pages are backed by `history_index.db` next to the backend's `chat_events.db`. JSONL transcripts also have a shared index at `~/.cache/agentshell/transcript_history.sqlite`; `AGENTSHELL_HISTORY_CACHE` overrides this location for isolated tests. File identity, length, timestamps, and committed offsets determine whether a transcript can be appended to or must be reindexed. Incomplete trailing records are retried after more bytes arrive.

These indexes are rebuildable disk caches. Each conversation retains at most two synchronization snapshots so an in-progress download has stable ordering while new messages arrive. Clearing the indexes does not delete original transcripts or phone history; a missing base snapshot causes a normal full-body page response.

Expensive archive work has one worker and at most sixteen waiting requests. Cached pages bypass that worker. Initial pages contain up to 30 messages; older pages contain up to 200. A serialized-body budget of approximately 1 MiB bounds ordinary pages. SQLite caches, filesystem notification queues, live message batches, and WebSocket queues are also bounded. Dropping a watcher aborts its reader task.

glibc's arena count is capped at four with `mallopt` in the backend process. This does not set `MALLOC_ARENA_MAX` in the environment or change allocator settings in agent subprocesses.

## Incremental phone synchronization

The phone first asks for `get-history-sync-capabilities`. Version 1 supports these finite requests independently of live chat watchers:

- `get-history-state`: identifies the current conversation revision and availability. Revisions include source state, overlay changes, and clear-history state.
- `get-history-page`: accepts a revision, offset, limit, and optional completed `baseRevision`. Responses contain ordered entries with a body fingerprint, position, and message. A message body can be omitted when it exists in the base snapshot.

An unchanged, fully synchronized conversation requires no message-body requests. For a changed conversation, the phone reuses messages by their server fingerprints and downloads changed bodies only. Ordering metadata reconciles insertions and deletions even in older parts of the conversation. If an omitted body is missing locally, the phone requests that page again without a base revision.

Each committed page saves its snapshot revision and offset in `cached_history_sync`. Background work yields after four pages per conversation. A restart resumes the saved snapshot. A busy conversation finishes its pinned snapshot first, then catches up with the latest revision; concurrent appends do not continually restart backfill.

Old cached rows are removed only after every page of an authoritative snapshot is committed. Errors and unavailable sources preserve existing offline history. Clear-history and conversation rebinding invalidate incompatible snapshots. Existing client identifiers remain in use; server fingerprints are additional synchronization metadata.

Room migration 10 → 11 adds synchronization metadata and indexes without dropping cached histories. Older backends use the existing watch/page protocol. Older clients can continue using legacy history commands, which now read bounded server pages.

## Large messages and attachments

An individual source record over 256 KiB is preserved in full as a content-addressed `full-message.json` attachment rather than expanded into a large SQLite row or chat bubble. The original record is not truncated. This also covers large tool outputs. The Android viewer reads these files in UTF-8-safe 64 KiB pages.

Attachments are streamed to disk on both sides. Android persists pending download URLs as private job files, so failed downloads resume after restart even if the conversation itself is unchanged. Viewing/exporting a large attachment does not first allocate its complete byte array.

## Verification

Focused regression coverage includes complete archive paging, provider conversion parity, bounded queues/batches, partial UTF-8 records, truncation/reindexing, watcher cleanup, migration preservation, interrupted backfill, active snapshot pinning, changed-body reuse, old edits/deletions, and durable attachment retries.

Run the unit suites:

```sh
cd backend-rust
rtk cargo test -- --test-threads=4

cd ../android-native
rtk proxy env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleRelease --console=plain
```

Run a read-only integration test against existing Codex archives in a second backend instance:

```sh
rtk proxy python3 scripts/test_history_memory.py --largest 5
rtk proxy python3 scripts/test_history_memory.py --largest 0 --soak-seconds 3600 --output /tmp/history-memory-report.json
```

The integration test uses temporary server databases, snapshots, attachments, ports, and authentication. It does not create agent sessions or send prompts. It checks complete pagination and unchanged revisions while sampling the backend PID's RSS and swap. The proposed acceptance ceiling is 512 MiB for that process; Chrome, agent processes, and filesystem cache are reported separately by systemd and must not be confused with backend-only memory.
