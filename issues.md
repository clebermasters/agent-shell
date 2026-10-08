I went through the Android startup/render path and the Rust websocket/session pipeline. I have not run traces yet, so this ranking is from hot-path inspection rather than benchmark numbers.

The biggest opportunities, in order of likely user impact, are these.

1. **Make terminal startup self-contained and warm.**
   The terminal HTML currently pulls xterm CSS, xterm JS, and addons from jsDelivr, and it sets `scrollback: 50000`. Your `XTermView` loads `file:///android_asset/terminal.html` into a WebView and destroys that WebView on dispose. In split-screen, the terminal WebView is only created after first focus. That means terminal readiness depends on both WebView spin-up and remote subresources, which is exactly the kind of thing users feel as “blank terminal,” “slow first open,” or “sometimes flaky.” I would vendor xterm locally, load subresources through `WebViewAssetLoader`, trim default scrollback to something like 5k–10k, and keep one warmed WebView or a tiny pool ready. Android’s own guidance describes `WebViewAssetLoader` as a performant way to load in-app content and subresources.    ([Android Developers][1])

2. **Fix the split-screen illusion.**
   There is a hidden architectural mismatch here: the code explicitly says only one PTY reader exists per WebSocket connection, so only the **focused** terminal panel gets live output; non-focused panels just keep their last rendered buffer. That is a huge UX limiter because users expect true simultaneous live terminals in split-screen. I would change the protocol so output messages are always tagged by session/panel and each panel can stay live, even if you still multiplex over one socket. This is one of the biggest “wow, this suddenly feels real” improvements you can make.  

3. **Cut cold-start work, then add Baseline + Startup Profiles.**
   `AgentShellApp.onCreate()` is doing meaningful work immediately: notification setup, Hilt entry-point resolution, eager `audioPlayerManager().connect()`, app-wide WebSocket observers, foreground-service management, and reconnect hooks. Some of that should be lazy or deferred until first use or after first frame. Then add Baseline Profiles for cold start, sessions screen, terminal open, chat open, and host switch. Android says Baseline Profiles improve code execution speed by about **30% from first launch** and recommends generating them with Macrobenchmark/BaselineProfileRule around critical user journeys; Startup Profiles further optimize DEX layout for startup.  ([Android Developers][2])

4. **Your tabs are not actually kept alive, even though the comment says they are.**
   `HomeScreen` says tab content is “kept alive,” but `TabContent` only renders content when `visible` is true. So switching tabs tears down hidden tabs instead of keeping them warm. That is a classic hidden source of “why does this screen feel like it reloads every time?” I would replace it with a true keep-alive approach: `SaveableStateHolder`, per-tab `NavHost`, or a real IndexedStack-style layout that keeps children composed but hidden.  

5. **Stop request storms and make the app feel immediate, even before the socket settles.**
   `SessionsViewModel.requestSessions()` sends five websocket requests every time it runs: sessions, ACP sessions, favorites, tags, and tag assignments. Then create/kill/delete flows schedule another delayed refresh. That makes reconnects and screen entry bursty. I would replace this with a single bootstrap payload or server-driven delta updates, and show last-known local state immediately while refreshing in the background. Android’s offline-first guidance explicitly recommends presenting local data immediately instead of waiting for the first network call to complete.   ([Android Developers][3])

6. **Centralize websocket fan-out on Android instead of having many independent collectors.**
   Right now, `TerminalService.attachSession()`, `attachSessionKeyed()`, `SessionsViewModel`, `SplitScreenViewModel`, and `TerminalViewModel` all collect the raw `wsService.messages` stream independently. That means repeated filtering, repeated parsing, and repeated per-message work across features. I would create one typed router layer inside `WebSocketService` or repositories, fan out by message type/session, and expose narrow flows to screens. This will reduce duplicate work and make behavior much easier to reason about.    

7. **The tmux monitor is probably burning CPU all the time.**
   On the Rust side, `TmuxMonitor` polls every **250 ms**, lists sessions, and then lists windows for every session on every tick. That is a hidden background tax that can show up as server CPU use, battery drain, and sluggishness under load. I would move to tmux hooks if possible; otherwise use adaptive polling: fast only right after user actions, slow when idle, and split cheap session existence checks from expensive per-window enumeration. 

8. **Remove backend hot-path blocking and backpressure.**
   This is the biggest Rust-side stability/perf cluster. In the ACP/Codex streaming loops, you persist message chunks and tool events before broadcasting them. `ChatEventStore` opens a SQLite connection on each append, and `append_or_merge_text()` fetches the last row, deserializes JSON, mutates it, and writes it back. At the same time, `ClientManager.broadcast()` holds a read lock while awaiting sends to each client, which Tokio explicitly recommends restructuring away from. On Android, the websocket `pendingQueue` is unbounded while disconnected and then flushes wholesale on reconnect. In practice, that combination can create lag spikes, reconnection bursts, and “one slow client makes everybody feel delayed.” I would snapshot senders before any await, move chat persistence to a dedicated writer task with small chunk coalescing windows, and bound/dedupe queued outbound messages by type.       ([Tokio][4])

If I compress all of that into the three changes most likely to create a **tremendous** user-perceived difference, it is this:

* **Terminal path first:** local xterm assets, `WebViewAssetLoader`, smaller scrollback, warmed/reused WebView.   ([Android Developers][1])
* **State and navigation feel instant:** real tab keep-alive, no 5-message refresh bursts, show cached data first.   ([Android Developers][3])
* **Backend under load stops stuttering:** adaptive tmux monitoring, no lock-across-await broadcast loop, async batched chat persistence.    ([Tokio][4])

Next, I’d turn this into a prioritized patch plan with the exact files and code changes for the top 5 wins.

[1]: https://developer.android.com/develop/ui/views/layout/webapps/load-local-content "Load in-app content  |  Views  |  Android Developers"
[2]: https://developer.android.com/topic/performance/baselineprofiles/overview "Baseline Profiles overview  |  App quality  |  Android Developers"
[3]: https://developer.android.com/topic/architecture/data-layer/offline-first "Build an offline-first app  |  App architecture  |  Android Developers"
[4]: https://tokio.rs/tokio/tutorial/shared-state "Shared state | Tokio - An asynchronous Rust runtime"
