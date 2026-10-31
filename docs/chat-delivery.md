# Chat delivery and connection recovery

A slow WebSocket client used to block broadcasts to every other client. Chat
submission wrote to the terminal before broadcasting, so the message could be
delivered while its acknowledgement waited indefinitely. The Android client
discarded the request after 30 seconds and could no longer reconcile a late
acknowledgement. Navigating away refreshed history but left an uncertain draft.

Broadcasts now enqueue without waiting for another client's queue. A saturated
client disconnects and restores its watches after reconnecting. Socket writes
have a ten-second deadline. Android preserves packet order with a bounded
consumer, probes connection health on foreground/network changes, and uses a
connection generation to restore watches even after a very fast reconnect.

Each bound text, attachment, or widget send has an opaque request ID. The backend
reserves that ID before terminal input and stores a small receipt in
`chat_send_receipts.db`. It stores the result before acknowledging, with a
delivery checkpoint before broadcast. Repeating the same request returns its
receipt; reusing its ID with a different payload is rejected. Receipts store a
payload digest and delivery state, without another copy of the message or
offline transcript. Old receipt metadata is pruned after seven days.

`get-chat-send-result` reads a receipt using `bindingId` and `requestId`; it does
not submit input and does not require reopening the conversation. Its
`chat-send-result` reply has one of these states:

| State | Meaning |
| --- | --- |
| `pending` | The original submission is still being processed. |
| `complete` | `success` and optional `error` contain the recorded result. |
| `unknown` | Delivery cannot be established safely; inspect the conversation. |

An interrupted write or backend restart never causes an automatic resend.
Android keeps bounded, persisted delivery tickets so leaving and reopening chat
does not lose the original request. Visible chats check unresolved receipts
with a finite retry budget. Late confirmation clears only the matching draft
and attachment; a newly edited draft is preserved. Editing stays available
while sending, and the Check action refreshes delivery state in place. Full and
split-screen chats use the same ticket store. Offline history is unchanged.

The `chat-binding` envelope advertises `sendReceipts: true`. Older backends keep
the existing unconfirmed-delivery fallback; clients never replay a submission
to simulate acknowledgement.

Verification uses synthetic data and owned test processes:

```bash
cd backend-rust
cargo test -- --test-threads=1
cd ../android-native
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest
cd ..
python3 scripts/test_chat_send_recovery.py --binary /path/to/backend --output /path/to/private/results
```

The protocol fixture creates its own tmux socket, fake terminal agent, database
and backend. It checks lost acknowledgements, concurrent duplicate requests,
reconnect and backend restart, verifying exactly one terminal submission and
one persisted user event. It never sends prompts to a user agent or model.

For real Android screen checks, build a debug APK with `-PuiPoc=true` and use the
owned `agentshell-ui-poc` emulator:

```bash
python3 scripts/test_android_chat_send_recovery.py --binary /path/to/backend --apk /path/to/isolated-debug.apk --output /path/to/private/android-results
```

The Android fixture verifies the APK's synthetic configuration and package,
then injects acknowledgement loss, a disconnect after delivery, and a 31-second
receipt blackout. It checks recovery in the same chat, editing while waiting,
preserving a newer draft, and exactly one terminal submission. `--case drop`
runs only the fresh-start dropped-acknowledgement case.

The 2026-10-30 validation passed 665 Rust tests (one existing ignored test),
267 Android tests, nine live protocol checks, and seven Android screen checks.
Three additional fresh-data launches recovered a dropped acknowledgement with
one read-only query around 0.5 seconds after sending. The full-queue regression
and immediate-pong regression failed with their original behavior restored.
