# Server-components hardening — status

Tracking doc for `security/server-components-hardening`. The blocking correctness/security items from
the security review are now **all resolved** on this branch (each with a red→green regression test);
what remains under "Follow-up" is design/altitude and minor cleanup that is safe to defer.

## Done — HTTP action-dispatch glue moved into the runtime

The bounded read, status mapping, and security headers were copy-pasted into both demo servers and had
already drifted (the docs CSP dropped `'unsafe-inline'`). They now live once in `kinetica-runtime`:

- `dispatchHttp(transport, body: String?): ServerActionHttpResponse` (common) — one decode → dispatch →
  encode path: `null` body → **413**, undecodable envelope → **400** (generic message, never the 500
  catch-all), otherwise dispatched → **200**.
- `readBoundedRequestBody(input, limit = DEFAULT_MAX_SERVER_ACTION_BODY_BYTES): String?` (jvm) — the
  bounded read, with a `DEFAULT_MAX_SERVER_ACTION_BODY_BYTES` (64 KB) default.
- `KineticaSecurityHeaders: Map<String, String>` (common) — the shared header baseline; `style-src`
  keeps `'unsafe-inline'` (required by the browser renderer's inline-style layout) with a KDoc note.

## Done — blocking review findings (each with a regression test)

1. **Unbounded `/demo/api/stack` POST → heap-exhaustion DoS.** `respondDemoStackSubmission` now reads via
   `readBoundedRequestBody(...) ?: 413` before parsing, matching `/actions`.
   Test: `DocsServerTest.demoStackRejectsOversizedBodiesWith413`.
2. **Compiler-generated dispatcher rejected every action.** The emitter no longer emits a verifier-less
   `val`; it emits a `kineticaGeneratedServerActionDispatcher(verifyCapabilityToken, verifyCsrfToken)`
   factory that *requires* the verifiers (so "auth never configured" can't silently become "all
   rejected", and no secret is compiled in). Golden text pins the factory + verifier forwarding; the
   annotated sample constructs through it. (This also resolves the root cause of old item 8.)
3. **`safeHtmlTagName` allowlist broke snapshots + SSR fidelity.** Replaced with a case-insensitive
   **denylist** of executable/document-mutating tags (`script`, `iframe`, `object`, `embed`, `svg`,
   `math`, `template`, `style`, `link`, `meta`, `base`, `frame`, `frameset`, `applet`, …) plus the
   char-gate. Legitimate DSL/HTML tags render verbatim again; dangerous tags (incl. uppercase `<SCRIPT>`)
   still collapse to `<div data-kinetica-tag=…>`. Tests: existing snapshots pass unchanged + new
   case-insensitivity/legitimate-tag cases.
4. **Deferred-subtree render errors leaked raw messages to `/stream`.** `toServerRenderStream` now logs
   the detail server-side and streams a generic `"Server render failed."` `BoundaryError`.
   Test: `serverRenderStreamEmitsDeferredSubtreesAsTheyBecomeReady` (asserts the scrubbed message and
   that the raw detail does not leak).
5. **Type-invalid-but-shape-valid payload was swallowed as `"Server action failed."`.** Payload decode
   now happens outside the handler-guarding try and returns a distinct `"Invalid server action payload."`
   client error. Tests: `serverActionDispatcherReportsTypeInvalidPayloadAsClientError` (3.5→Int) and
   `serverActionDispatcherConvertsInitBlockFailureToClientError` (`@Serializable init{}` require).

## Done — follow-up review (second pass)

- **Decode catch was too narrow (regression from item 5).** kotlinx does not wrap `@Serializable`
  `init{}`/constructor exceptions, so an `IllegalArgumentException` escaped `dispatch()` (a `suspend fun`
  contractually returning `ServerActionResponse`) → a 500. Broadened the decode catch to `Exception`
  (still rethrowing `CancellationException`).
- **Strict CSP broke the bench report's inline `<script>`.** `respondStaticAsset` applies
  `script-src 'self'` to every asset, including the published `bench/report/index.html`, whose inline
  tooltip script was blocked. Externalized it to a same-origin `bench/report/report.js` (referenced via
  `<script src>`, which `script-src 'self'` permits) — CSP stays strict everywhere, no per-route
  exception; `generate.mjs` emits it and `bundle-bench-static.mjs` stages it.
- **Cleanups:** collapsed the dead-elvis / duplicated `Failure` in the handler catch (old items 11–12,
  `ServerActionRejection.message` is now a non-null `override`); corrected the `validationError()` KDoc
  overflow overclaim (old item 13).

## Follow-up — design / altitude (safe to defer; not blocking)

- **Two hand-maintained tag tables drift.** `safeHtmlTagName` (`Html.kt`, SSR) and `browserTagNameFor`
  (`kinetica-browser/src/BrowserMapping.kt`, CSR) encode the DSL-tag→element mapping independently: SSR
  renders `<column>`/`<textInput>` verbatim while the browser hydrates `<div>`/`<input>`. No shared
  source of truth, no compile-time check. **Fix:** one tag-descriptor table in the runtime with
  per-renderer projections.
- **Error scrubbing is re-implemented per emission site.** The log-then-generic idiom is hand-written at
  the deferred-subtree stream and at each demo server's 500 catch-all; a future error-emitting path can
  forget to scrub. **Fix:** funnel error responses/chunks through one scrub helper.
- **`ServerActionRejection` is exception-as-control-flow.** Consider a `validate: (I) -> String? = {null}`
  parameter on `serverActionStub` so shape+domain validation is one declarative phase, keeping the
  exception only for mid-handler faults.
- **Reference-server dedup.** `applySecurityHeaders()` and `const SERVER_THREAD_POOL_SIZE = 8` are each
  duplicated in the docs and sample server mains. Move into a small shared server-util (or the runtime
  JVM source set) if one is introduced.

## Follow-up — minor / assessed (low priority)

- **`readBoundedRequestBody` allocates the full 64 KB buffer regardless of `Content-Length`.** Optional:
  reject/size by a declared `Content-Length` first (the bounded loop must stay — the header can lie).
- **413 path does not drain an oversized body** → a client streaming ≫64 KB may see a connection reset
  instead of a clean 413. Working-as-intended for the DoS goal: fully draining an attacker-sized body
  would reinstate the exhaustion this guards against; `com.sun.net.httpserver` drains a bounded amount on
  close, which covers legitimate slightly-over clients.
- **No request-read timeout on the fixed thread pool** (slowloris): a demo-server residual, strictly
  better than the prior single-thread `executor=null`; not introduced here.
- **`respondText("")` (empty body) sends chunked** instead of `Content-Length: 0` — latent; no current
  caller passes an empty body.
- **Catch-all `respondText(500)` can double-send headers** if a prior handler already sent them (client
  disconnect mid-write) — pre-existing; the client is already gone.
