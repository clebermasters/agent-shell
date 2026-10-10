# Mobile interactive canvas acceptance

The requested goal is a useful, polished, mobile-first version of the isolated
interactive chat experiment. This remains on `poc/open-intelligent-ui`; it does
not deploy or configure production agents.

| Requirement | Verification |
| --- | --- |
| All five capabilities remain functional | Real DOM interactions and rendered WebGL pixels; native WebView callbacks |
| Charts explain real selectable values | Scenario arithmetic, month selection, table equivalence, state restore |
| Calculator handles practical input | Tip presets, steppers, numeric boundaries, invalid input blocks sending |
| Diagram is navigable | Direct selection, previous/next boundaries, meaningful stage descriptions |
| 3D is touch-friendly | Drag, zoom, presets, responsive aspect ratio, unavailable-WebGL fallback |
| Map works offline | Bundled schematic, marker/stop selection, fit bounds, no external requests |
| Live map capability remains available | Real USGS tiles, saved mode, uncached network outage restores the offline layer |
| Small screens and themes are usable | 280/320/360/412/740 CSS px in both themes; no page overflow; named 44px controls; text contrast |
| Cards have native fullscreen and image sharing | Real Android UI taps, image file, controls preserved on close/theme/rotation |
| Entire message bubbles maximize on large displays | Native mixed text/widget message, phone→tablet resize, painted pixels, close restores controls |
| Forms remain usable with the keyboard | Physical field tap, real Android IME, inset-aware scroll containers, calculation retained |
| Controls survive navigating the gallery | Native tab switch and recreation; browser document remount |
| Chat ownership remains exact | MCP publication, native follow-up to A only, cross-conversation/stale lease rejection |
| Failure states are useful | Failed script and renderer load show recoverable error; no daemon credentials in documents |
| Works through normal and split chat renderers | Shared renderer compiled in both; normal mixed message exercised natively; independent bound action protocol tested (full split navigation remains outside this harness) |
| Delivered independently | APK build/signature, unit checks, browser/native reports and reviewed screenshots |

Automatic model routing, the full CopilotKit/AG-UI stack, and streaming model UI
generation are separate architectural work. They are not silently represented
by the sample gallery. The MCP adapter accepts agent-authored complete HTML;
guidance for generating mobile documents is included with this branch.
