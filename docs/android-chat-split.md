# Android chat and terminal split

The chat header is a single compact row with a session title, live agent status,
split button, and options menu. Tap the title or choose **Connection & usage**
to expand connection controls, detailed activity, and provider usage. Terminal,
file browser, theme, and clear-history actions are in the options menu.

For tmux chats, tap **Split chat and terminal** in the header to open a terminal
for the same session and window. Tap the button again to return to chat only.
ACP chats have no tmux terminal, so they do not offer this action.

- Drag the divider to resize. Tap it for **Split layout**; double-tap it to swap
  chat and terminal. Layout options are also available in the header menu.
- Choose **Auto**, **Top / bottom**, or **Side by side**. Auto stacks phone
  portrait screens and uses side-by-side panes in landscape or at 840dp width.
  Opening the keyboard does not change Auto's orientation.
- Swap panes or reset their sizes in the layout dialog. TalkBack can adjust the
  divider as a range and invoke its swap and layout actions.
- Narrow chat panes place the message editor above its action buttons so it
  retains usable width. Wider panes keep the single-row composer.
- Enabled state, orientation, pane order, and separate sizes for each orientation
  are saved immediately for each server, session, and tmux window. Closing the
  split retains its layout for next time. Swapping retains the chat's size.

The terminal uses an independent WebSocket so it does not replace the chat's
watch subscription. Swapping and changing orientation retain both compositions,
including the draft, chat scroll state, and terminal connection. Terminal input
waits for attachment and never queues keystrokes across reconnections.

The backend must include the `terminal_cmds.rs` change that honors the requested
window when attaching a PTY. Build/deploy that backend with the Android update
to guarantee that the terminal initially displays the chat's exact tmux window.

## Verification

```bash
cd android-native
./gradlew -PuiPoc=true assembleDebug testDebugUnitTest \
  --tests com.agentshell.data.local.ChatSplitPreferencesTest \
  --tests com.agentshell.feature.chat.ChatTerminalSplitTest \
  --tests com.agentshell.feature.chat.ChatScrollStateTest
cd ../backend-rust
cargo test --release websocket::terminal_cmds::tests
cd ..
python3 scripts/test_android_chat_split.py --serial emulator-5560 \
  --apk android-native/app/build/outputs/apk/debug/app-debug.apk \
  --output /tmp/agentshell-chat-split-verification
```

Build production APKs without `-PuiPoc=true` to restore the normal server
configuration. The isolated Android fixture uses only the owned
`agentshell-ui-poc` emulator and loopback WebSocket data.
