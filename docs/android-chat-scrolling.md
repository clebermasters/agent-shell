# Android chat scrolling

`ChatScreen.kt` uses `ChatScrollState.kt` to follow the actual end of the list.
This applies to TMUX chat history/events and ACP streaming replies.

- Initial opening waits for history and unread tracking. It opens at the first
  unread message when one exists; otherwise it opens at the latest reply.
- Following observes message content and measured layout, including same-ID
  streaming updates, widget height changes, and viewport changes.
- Any manual scroll pauses following. Appended replies, history refreshes, and
  prepended history retain Compose's stable message key and pixel offset.
- Reaching the actual bottom manually or pressing **Scroll to bottom** resumes
  following. A partially visible last message does not count as the bottom.
- Background/resume retains the reading position. A different bound conversation
  gets its own list state; a transient binding check retains the current scope.

The final `ChatListAnchor.End` item is deliberately separate from the message:
scrolling to a tall message's index only reveals its beginning. Automatic moves
use `requestScrollToItem` instead of restarting animations on every event.
The unread divider has its own stable key too, so removing it on resume cannot
change the offset of text inside a tall message.

## Regression found on 2026-10-10

The previous implementation observed only `messages.size`, considered a partly
visible final row to be the bottom, and reset initial positioning on pause.
An isolated emulator reproduced both a 45-paragraph new reply stopping before
its tail and a 70-paragraph update to the same ID failing to follow. The resume
test also exposed text moving when the unread divider disappeared inside its row.

After the fix, all 17 emulator assertions passed, including identical reading
bounds after appends, same-ID growth, full history refresh, resume, and prepending.
The scroll policy, read-state, and activity repository suites passed 36 tests.
One initial activity repository run hit an existing temporary DataStore file
cleanup race; the same unchanged suites passed on rerun. The production release
build also passed. This verification used the emulator, not a physical phone.

## Repeat the Android verification

Use an owned API 35 emulator at 1080×1920. The fixture never accesses production
TMUX sessions or the backend database. It resets only `com.agentshell.debug` on
the named emulator and checks that the APK contains the public fixture token.
Install Python `websockets >= 14` and ensure `adb` is on PATH.

```bash
cd android-native
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew -PuiPoc=true assembleDebug \
  testDebugUnitTest --tests com.agentshell.feature.chat.ChatScrollStateTest
cd ..
python3 scripts/test_android_chat_autoscroll.py --serial emulator-5560 \
  --apk android-native/app/build/outputs/apk/debug/app-debug.apk
```

The script starts a loopback WebSocket fixture on port 40199, feeds the normal
ChatScreen, and uses UIAutomator to verify visible tails and identical reading
bounds. It covers long replies, same-ID growth, manual pauses, history refresh,
resume, explicit/manual return to bottom, rapid arrivals, arrivals during a drag,
old history, prepending, and opening unread history. Results and failure screenshots
are written to a temporary directory, or the directory passed via `--output`.

Build production APKs **without** `-PuiPoc=true` to regenerate the normal server
configuration from `.env`.
