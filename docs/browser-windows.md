# Browser windows and split view

AgentShell’s Android Browser screen can manage the live tabs in a Linux host’s managed Chrome profile. Open the [interactive prototype](prototypes/browser-windows.html) to try the controls with sample tabs.

The phone already shows the selected host’s Chrome desktop in a single Browser screen. On Linux, Openbox maximizes normal Chrome windows on the same virtual display. A second window can therefore cover the first, with little indication that the first is still open. A **Windows (2)** button makes both discoverable. Its number counts eligible windows in the selected Chrome profile, including the one on screen. [Current Linux runtime](../scripts/browser-runtime.py)

## Enable the controls

1. Update the backend and its Linux browser component together using `./install.sh --with-browser kasmvnc --agents installed`. Install the matching AgentShell Android build. The Linux installer stages a signed extension, an unpacked copy and its native host under the service user’s account.
2. Open Chrome **inside the AgentShell Browser screen**, in the profile whose tabs you want to manage. Open `chrome://extensions`, enable **Developer mode**, choose **Load unpacked**, and select the full directory `~/.local/share/agentshell/browser-windows/extension` under that Linux user’s home. On a later update of a loaded unpacked extension, use **Reload** on its extensions page.
3. Return to the phone’s Browser controls and tap **Windows**. On first use the native host briefly opens a pairing tab in that profile. The tab closes once Chrome confirms the pairing. The window count then follows the managed profile.

If the picker reports that the extension is offline, check that **AgentShell Browser Windows** is enabled in that same profile, then refresh the picker. If an older running session asks you to select a profile, use the **Chrome profiles** picker to identify the intended one. Side-by-side controls require the running Chrome to expose its Split View API.

## What you can do on the phone

| Action | What happens |
| --- | --- |
| **Windows (2)** | Opens a sheet grouped by window. Each window has a stable label for this session, the title of its active tab, a tab count and a current-window indicator. Tap a window to return to its last active tab. Tap a specific tab to go straight to that page. |
| **Move to window…** | Pick one tab and an existing destination. Chrome moves the live tab there and the phone follows it. Moving the last tab also removes the now-empty source window. |
| **Move to new window** | Detach one tab from a window that has at least two. The new window opens on that tab, and the original is still in the switcher. |
| **View side by side** | Pick a second compatible tab in the same window. Chrome displays both pages in one frame. Tap either pane to direct keyboard input to it. **Exit side by side** returns the pair to two ordinary tabs and keeps both open. |
| **Merge all into this window** | Choose the destination window. Preview the number of incoming tabs and source windows, then confirm or cancel. The destination keeps the tab you were reading. |

In the prototype, move **Weekend map** from Window 2 into Window 1. View it beside **Route ideas**. **Train timetable** stays in Window 2 until you choose to merge all windows.

## Interaction details

- Put **Windows** in the normal browser controls and in **Fullscreen controls**. Reuse the same sheet in both. Keep the count visible when an eligible window opens or closes. A stable label such as “Window 2” helps distinguish two windows even if their active tabs have the same title.
- List tabs in Chrome’s order. Give each row a separate actions button so a tap on the title always switches to the tab. Keep the stream connected while the sheet handles touch and keyboard input. Android Back dismisses the picker or sheet one level at a time.
- The side-by-side picker offers tabs with matching pin and group status. For compatible nonadjacent tabs, show that the second tab will move beside the first. Tabs in different windows can be brought together with **Move to window…** first. Selecting a third tab leaves the original pair available when you return. Offer **Exit side by side** before detaching or individually moving one member of a pair. Explain when an individual move takes a tab out of its group. Chrome owns the divider, page rendering and pane focus. [Chrome Split View](https://support.google.com/chrome/answer/16971124)
- A merge keeps each source’s order within Chrome’s pinned and regular sections. Keep destination pins first, append incoming pins, retain destination regular tabs, then append incoming regular tabs in the window order shown in the sheet. Preserve groups as units and preserve existing split pairs. Chrome removes each source window as its last tab leaves. A window created after the preview stays where it is.
- The catalogue covers normal, non-incognito windows in the paired, managed profile. Browser dialogs remain available in the stream. Clear window titles, selections and pending actions on profile, server or controller changes. Invalidate any pending automatic voice insertion when the active tab or pane changes, and leave the transcript available for review.

## Implementation

1. **Android.** Add the sheet alongside the existing browser controls in [BrowserScreen.kt](../android-native/app/src/main/java/com/agentshell/feature/browser/BrowserScreen.kt). Keep the catalogue and in-flight actions in [BrowserViewModel.kt](../android-native/app/src/main/java/com/agentshell/feature/browser/BrowserViewModel.kt) and use [BrowserRepository.kt](../android-native/app/src/main/java/com/agentshell/data/repository/BrowserRepository.kt) for authenticated requests. Switching focus keeps the mounted viewer. Reconnect fetches a fresh catalogue.
2. **Gateway.** Extend the existing authenticated [browser routes](../backend-rust/src/browser.rs) with a catalogue read and typed actions. Return a profile-scoped snapshot with a revision, focused window, ordered tab IDs, active tab, group, pin and pair membership. Refresh the button count on window changes and the sheet on tab changes. Bind reads and actions to the active viewer and runtime generation. Serialize mutations per controller and deduplicate a retried action by request ID.
3. **Chrome.** In the managed Linux profile, pair a Chrome extension with the runtime through a same-user native messaging host. Pin the extension ID and verify the profile pairing as well as process ownership. Use a supported signed extension install for production. Share only the metadata needed by the sheet. Keep titles out of logs and off disk in Android. [Native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging) · [Linux extension distribution](https://developer.chrome.com/docs/extensions/how-to/distribute/host-on-linux)
4. **Window and tab commands.** Use `windows.getAll` to enumerate normal windows, `windows.update` and `tabs.update` to focus, `windows.create({tabId})` to detach, and `tabs.move` to move an existing tab. Use `tabGroups.move` for whole groups. These commands operate on the live tabs, retaining drafts and navigation history. Read back the result before reporting success. [Windows API](https://developer.chrome.com/docs/extensions/reference/api/windows) · [Tabs API](https://developer.chrome.com/docs/extensions/reference/api/tabs) · [Tab Groups API](https://developer.chrome.com/docs/extensions/reference/api/tabGroups)
5. **Side by side.** Feature-detect `tabs.createSplit` and `tabs.unsplit` on the running browser. Chrome documents both from version 155. Pairing requires adjacent tabs in the same window with matching pin and group status. Before merging, record each pair’s tab IDs and order. After moving, read back the destination. If Chrome separated the pair, recreate it from the same adjacent tabs and verify it. Report any failed pairing separately from how many tabs moved. If the host cannot recreate a source pair, identify it before starting the merge. [Split API](https://developer.chrome.com/docs/extensions/reference/api/tabs#method-createSplit)

The controls run on the Linux managed viewer. The desktop viewer for that same stream sees the resulting Chrome layout too. The macOS provider pins capture and input to a specific native window and leaves window management disabled. [macOS provider](native-macos-browser.md)

## Failure and acceptance cases

A feasibility test ran Chromium 155 on an isolated KasmVNC/Openbox display. The window manager confirmed switching in both directions. A typed draft and the original page load time survived detaching, moving and re-pairing. Three observations affect the implementation:

- The result of a focus request could arrive before the window manager finished switching. Wait for the focus change and verify the selected window with a bounded timeout.
- Moving two paired tabs across windows dissolved their split. Re-pairing the same tab IDs in the destination worked. Moving a whole tab group retained its name, color and members.
- Moving a pinned tab across windows could drop its pin even at index zero. Read back and restore pin state and order before re-creating a split.

- A drag, closed tab or competing desktop action can interrupt a multi-window move. Chrome checks the preview revision immediately before acting. A partial move produces a fresh catalogue and the actual result, such as “3 of 5 tabs moved.” Replaying the same request returns its receipt. Starting another merge requires reviewing the current windows again.
- Use an isolated browser profile with two windows and a form draft. Switch by window and by tab, move a single tab into an existing window, detach it, and merge everything back. Verify the same tab IDs, draft, navigation history, pin state, groups, ordering and destination selection.
- Pair two pages and type in each pane. Exit the split and verify both pages remain open. Repeat in normal and fullscreen Android layouts and on a narrow phone. Cover switching during dictation and a profile handoff while a picker is open.
- Verify cancellation of a merge, the single-tab detach guard, a stale preview, the last tab leaving its source, a split pair in a merge, and a second profile open in the same Chrome process. An older host advertises only the actions it supports.

The following checks use disposable profiles. The headed stock-Chrome check uses the system Python GI bindings, a Python environment with `websockets`, and a private X display with Openbox. The Chromium test exercises real tab contents, pins, groups, split pairs, and partial merges.

```sh
python3 scripts/test_browser_window_extension.py
python3 scripts/test_browser_windows_host.py
python3 scripts/test_browser_windows_host.py --chrome
```

During implementation, all seven actions also passed through the authenticated Rust gateway and native messaging host to stock Google Chrome 157. A second profile in the same Chrome process stayed outside the catalogue and merge. A separate Android emulator run exercised the Compose controls in the normal and fullscreen views against an HTTPS fixture.
