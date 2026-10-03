# Harmon native preview

An Apple Silicon macOS app built with Kinetica and AppKit. It connects to the running Harmon
agent's authenticated local live API. The app stays here while Kinetica evolves, and can later
move into Harmon without releasing Kinetica first. An explicit sample mode remains available.
The app does not install, start, stop, or reconfigure Harmon services.

The [Kinetica application shell](../../kinetica-application/README.md) owns its native
windows, tabs, menus, command routing and focus. Cmd+N opens an independent monitor;
Cmd+T adds one to the active tab group. Cmd+Shift+[ / ] changes tabs, Cmd+W closes the
active tab, Cmd+F focuses search and Cmd+Q waits for polling/transport cleanup before quit.
Each monitor retains independent filters and frozen state. Hidden tabs, minimized windows
and a hidden application suspend polling; showing them resumes the same session.

## Build and run

On an Apple Silicon Mac with the Xcode command-line tools installed, from the repository root:

```sh
./kotlin publish mavenLocal -m kinetica-compiler
bash scripts/package-harmon-native.sh
open "build/apps/Harmon Preview.app"
```

The script builds a debug executable and creates an ad hoc signed local app bundle. Quit the
preview before rebuilding it. This is not a notarized distribution package.

By default the app discovers the current agent through
`~/Library/Application Support/Harmon/live-ui.endpoint`. Start your existing Harmon agent
first; if it is unavailable, the window shows a connection error and retries automatically.

For a reproducible frozen **sample** snapshot with no network access:

```sh
open "build/apps/Harmon Preview.app" --args --sample --frozen
```

Quit an already running preview before passing launch arguments. The executable can also run
directly with `./kotlin run -m harmon-native -- --sample --frozen`. `--frozen` alone pauses
the real connection before the first fetch. For an isolated agent or fixture, pass
`--endpoint-file=/absolute/path/to/live-ui.endpoint`; the descriptor can specify only a port
and token, and the connection always goes to `127.0.0.1`.

## Live connection

The Foundation client polls `GET /api/live?watch=1` with a bearer token loaded from the endpoint
file. It re-reads the file on each request to follow agent restarts and token/port rotation.
Tokens are never put in URLs, displayed, or logged. HTTP redirects, cookies, caching and
system proxies are disabled for this connection. Requests have a five-second timeout.

Polling follows the server's retry/sample cadence, bounded to 250 ms–15 seconds. Transport
failures retry with exponential delays of 1–15 seconds; Reconnect retries immediately.
Freeze, minimize, Hide and window closure cancel polling and in-flight requests. Resume
immediately reconnects. This lets the agent's shared sampling lease expire when the window
is not watching, without creating a second collector or sampler.

The app accepts wire schemas **2 and 3**. Their process-tree and metric fields are identical;
v3 adds alert category and PID associations. v2 alerts remain general notices without invented
row associations. Other schemas produce an explicit incompatibility message. No connection
failure silently switches to sample data.

Live rows preserve the server's identity keys, self/total readings and availability/partial
flags. Byte metrics are converted to MiB or KiB/s for display. Totals come from Harmon and
are not recalculated from the filtered rows. Warming, stale and disconnected states keep the
last available snapshot and its capture timestamp visible. Connection status reports the
live API's state; it is not a complete launchd/collector service-status report.

## Included behavior

- Native `NSOutlineView` with disclosure controls, single selection, keyboard navigation,
  sortable headers, resizable columns and scrolling.
- Live agent updates and Freeze/Resume; one-second simulated updates and single-step
  snapshots in `--sample` mode.
- Search by process name or PID, retaining ancestor paths and descendants of matching parents.
  Search opens matching branches; explicit collapse remains effective until the query changes.
- Overview, Memory, I/O, Activity and Energy column presets, with self and subtree totals.
  Filtering never changes totals. Missing readings stay last in either sort direction.
- Unavailable readings display `—`; incomplete totals display `≥`. Fixtures include process
  exits, reused PIDs, missing readings, and an alert.
- Selection, expansion and scroll anchoring survive snapshot updates; identity includes a
  start time so PID reuse does not select an unrelated process. Column widths are
  retained when switching presets. These preferences are in-memory for this milestone.
- Ready, Warming and Stale simulation controls appear only in sample mode. Closing the
  window disposes the renderer, its effect and the live HTTP session.

## Reuse in Harmon

`ProcessModel.kt` owns the display model, fixtures, filtering and sorting. `LivePayload.kt`
adapts the versioned wire payload; `LocalLiveSource.kt` owns endpoint discovery and Foundation
HTTP; `LiveConnection.kt` owns polling and retry/cancellation. `Main.kt` owns the window,
lifecycle and Kinetica UI. The generic table lives in
[`kinetica-appkit`](../../kinetica-appkit/README.md), with no Harmon dependency.

The next application milestone needs full service status, settings read/write contracts,
persistent preferences, and distribution signing/notarization. The client currently carries
a small compatible wire DTO subset; moving it into Harmon would allow sharing those types.

## Verification

```sh
./kotlin test --platform macosArm64 -m kinetica-runtime -m kinetica-appkit -m harmon-native
./kotlin build -m native-counter -m harmon-native
python3 scripts/verify-harmon-live.py
```

AppKit integration tests create unshown windows and require a macOS GUI session. They cover
native selection/expansion/sort events, retained item identity and column width, viewport reuse,
scroll anchoring, disposal, input bindings and invalid models. The sample tests cover filtering,
totals, missing metrics, PID reuse, and a complete Kinetica-to-AppKit render/update cycle.
Live tests cover both schema versions, exact server totals, missing metrics, polling cadence,
backoff and in-flight cancellation. The Python harness supplies temporary loopback servers
and synthetic credentials to exercise the actual Foundation client: authentication, endpoint
rotation, HTTP errors, redirect rejection and cancellation. Its native test entry point skips
when the fixture environment is absent. It never contacts the installed Harmon service.
The macOS CI job runs the suite and transport harness and packages the preview.

On the development Apple Silicon machine, a debug run measured about 123 ms for 30 direct
table updates with 1,000 rows, creating 68 native cells in an 800×400 viewport. The full pipeline
(fixture creation, aggregation/sorting, serialization, reconciliation and AppKit layout) took
about 3.1 seconds for 30 updates, or 102 ms per update. These are diagnostic measurements,
not release performance guarantees; release profiling and a sustained memory check remain
necessary before raising the refresh frequency or treating the preview as a production app.

Manual GUI checks include search entry, header sorting, keyboard selection, selection across
sample updates, collapse/expand during search, column presets, and preview state changes.
