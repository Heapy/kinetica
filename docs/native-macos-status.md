# Native macOS status — 2026-09-25

Kinetica can now run a useful Apple Silicon desktop application written in Kotlin, using
native AppKit controls. Harmon is a working live-monitor reference app. This is a development
milestone; the native API, performance qualification, and application distribution are still
in progress.

The goal is a declarative Kotlin API for high-performance native macOS applications. Native
widgets, virtualization, and cell reuse belong inside the library. Applications should own
their data and behavior without having to implement AppKit delegates. Widget granularity
and shared code should be chosen by measured cost and capability, rather than requiring
every table cell to be a separately reconciled component.

## What works

| Area | Current implementation |
| --- | --- |
| Platform | Kotlin/Native `macosArm64`; AppKit widgets, no JVM or embedded browser |
| Runtime | Shared compiler-driven components, state, effects, events, invalidation and disposal |
| Native reconciliation | Retained `kinetica-render-core` reconciler with keyed matching, LIS moves and a platform adapter |
| Controls | Rows/columns, text, buttons, checkboxes, text inputs, labels, spacers, basic sizing/padding and accessibility identifiers |
| Large tables | Reusable `outlineTable` component backed by `NSOutlineView`, with keyed items and viewport cell reuse |
| Harmon | Authenticated local live API, schema v2/v3, search, sort, metric presets, selection, expansion, freeze/resume and connection states |
| Lifecycle | Main-queue invalidation, cancellation on pause/hide/minimize/close, renderer and network-session disposal |
| Development | Deterministic 1,000-process fixture mode, native integration tests, HTTP transport harness and local app packaging |

The reference app stays in this repository and consumes project modules directly. Developing
it does not require publishing a new Kinetica version or changing the adjacent Harmon checkout.
See [Harmon's README](../samples/harmon-native/README.md) for commands and supported behavior,
and [the AppKit README](../kinetica-appkit/README.md) for the reusable component contract.

## What is shared

Both browser and native applications use the same compiler, runtime, component execution,
state/effect machinery and `Node` model. AppKit uses
[`Reconciler<V>`](../kinetica-render-core/src/Reconciler.kt) through
[`AppKitHostAdapter`](../kinetica-appkit/src@macosArm64/AppKitKineticaApp.kt).
The browser retains its [separate DOM reconciler](../kinetica-browser/src@js/BrowserKineticaApp.kt),
sharing the LIS helper but retaining DOM template handling and its own optimizations.

Native reconciliation currently materializes templates and does not have the browser's
unchanged-subtree identity skip or keyed scratch-buffer pool. Browser performance results
therefore do not establish native performance.

The process table is one host node. Its internal items, cell views and row updates are managed
by the reusable AppKit component. The surrounding Harmon screen uses Kinetica DSL; cells
currently contain model-defined text, not arbitrary DSL content. This is a native widget
integration and does not benchmark generic Kinetica reconciliation of 1,000 row components.

## Performance evidence and limits

The current packaging script builds **Debug**. During this session, a live run with roughly
820 processes had physical-footprint snapshots of 108–161 MiB and a process-lifetime peak of
362 MiB. A 128-second RSS observation ranged from 163–194 MiB without steady upward growth.
The native heap snapshot contained 48 table cell views and 820 item wrappers, supporting
viewport reuse. The native leak scanner reported about 22 KiB; ownership of those allocations
was not established, and this short observation cannot rule out long-term Kotlin retention.
Raw profiling artifacts remain local under the ignored `build/reports/harmon-memory/` folder.

The earlier 1,000-row debug fixture measured about 123 ms for 30 direct native table updates
and 3.1 seconds for 30 complete snapshot/projection/render updates. These are diagnostic
observations from one development machine, not release benchmarks or performance guarantees.

Concrete optimization opportunities identified in the current code:

1. HTTP response copying: the stack sample caught per-byte interop work and allocation in
   `NSData.utf8()` alongside GC marking; evaluate bulk copying in a release build.
2. Payload decoding: a measured response was 2.58 MiB with 25 metrics per process. The client
   builds a JSON tree, then DTOs for all metrics, then maps seven into its domain model.
   Direct typed decoding of the required subset can eliminate intermediate representations.
3. Native component boundary: the table model is encoded into a JSON host property and
   decoded again by AppKit. A typed native payload mechanism needs to preserve the existing
   serializable-node and lifecycle contracts while avoiding this round trip.
4. Projection: hoist repeated metric mappings and resolve sort metadata once per update.

No improvement percentage has been established. The app already disables runtime debug
journaling; code inspection found no app-specific unbounded render history.

## Remaining milestones

1. **Establish a release baseline:** reproducible workloads for idle CPU, update allocations,
   peak/steady memory, scrolling and input latency, plus a longer retention soak. Separate
   data ingestion, projection, generic reconciliation and native widget costs.
2. **Optimize the measured paths:** response copying and decoding first, then the typed host
   boundary. Compare before/after behavior, memory and latency before adding GC tuning or
   generic reconciler optimizations.
3. **Grow the native Kotlin API:** typed layout/control APIs, native app/window/menu lifecycle
   support and additional reusable controls as real applications require them. The current
   sample still owns window/menu setup and uses some AppKit-specific string properties.
4. **Finish Harmon's requested scope:** full collector/service status, settings read/write
   contracts and UI, and persistent preferences. Current connection status only describes
   the live API; it does not implement service management or settings.
5. **Prepare distribution:** release-mode app packaging, signing/notarization and installation
   behavior. The present bundle is ad hoc signed for local development.

## Validation recorded for this milestone

Local validation used Kotlin Toolchain **0.12.2** with Kotlin **2.4.10**. The launcher follow-up
on 2026-09-26 pins that tested Toolchain version in both `kotlin` and `kotlin.bat`; the local
version command and matching wrapper pins were verified. An earlier compatibility check
with 0.12.1 stopped before compilation because the downloaded archive did not match its
pinned checksum. Its checksum check was not bypassed. Fresh-cache bootstrap and Windows
launcher execution have not been revalidated in this follow-up.

- Native AppKit and Harmon tests passed, including the complete Kinetica-to-AppKit pipeline.
- The typed host-event test passed on JVM, JS and macOS; native runtime checks also passed.
- Native sample builds and local app packaging passed.
- The isolated Foundation HTTP harness passed authentication, endpoint rotation, error,
  redirect-rejection and cancellation checks using synthetic credentials.
- Manual GUI checks exercised the preview and a connection to the installed Harmon agent.
- CI now includes the native suites, transport harness and preview packaging. A remote CI run
  for these session changes has not yet been observed.

Historical native tickets in [plan.md](../plan.md) describe intermediate implementations.
In particular, AppKit has used retained shared reconciliation since KNT-0046; the older
teardown/rebuild description is not its current behavior.
