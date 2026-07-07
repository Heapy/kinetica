# Server-components hardening — remaining work

Follow-up plan from the security review of `security/server-components-hardening`. The highest-value
structural change is already **done** (see below); everything under "Remaining" is still open.

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

Both demos now call these; their local copies are deleted. This resolved the review's CSP-divergence
bug (docs demo island layout), the duplicated status-mapping, the duplicated header set, the duplicated
`readBoundedBody`, the duplicated `MAX_ACTION_BODY_BYTES`, and the unused `SerializationException`
binding. Covered by `RuntimeSmokeServerTest.dispatchHttpMapsRequestBodyToHttpStatusCodes`,
`ReadBoundedRequestBodyTest`, and the demos' existing suites.

## Remaining — blocking (correctness / security)

1. **Unbounded `/demo/api/stack` POST → heap-exhaustion DoS.**
   `docs/docs-site/src/main.kt:299` (`respondDemoStackSubmission`) still does
   `exchange.requestBody.readAllBytes().decodeToString()` before the length check. The bounded-body
   hardening only covered `/actions`; this sibling endpoint on the public `0.0.0.0` server can be OOM'd
   by one large POST — directly contradicting the commit's "one POST cannot OOM the JVM."
   **Fix (now trivial):** `val body = readBoundedRequestBody(exchange.requestBody) ?: return 413`, then
   parse `body`. Recommend doing this next — it's a one-liner with the helper just added.

2. **Compiler-generated dispatcher rejects every action.**
   `kinetica-compiler/src/KineticaGeneratedSourceEmitter.kt:95` emits
   `KineticaServerActionDispatcher(KineticaGeneratedServerActionStubs)` with no verifiers. With the new
   fail-closed `{ false }` defaults, the generated `KineticaGeneratedServerActionDispatcher` now returns
   `Failure("Invalid capability token.")` for every action, and there is no way to inject verifiers into
   it. Only golden-text (`CompilerModelTest.kt:728`) and a zero-action sample (`AnnotatedAppTest.kt:25`)
   touch it, so CI stays green.
   **Fix:** stop emitting a pre-built dispatcher; emit a factory instead, e.g.
   `fun kineticaGeneratedServerActionDispatcher(verifyCapabilityToken, verifyCsrfToken) = KineticaServerActionDispatcher(stubs, verifyCapabilityToken = …, verifyCsrfToken = …)`,
   and add a compiler/sample test that actually dispatches an authorized action through the generated
   entry point. Ties to item 8.

3. **`safeHtmlTagName` changes `toSafeHtml` output and breaks two existing snapshot tests.**
   `kinetica-runtime/src/Html.kt:152` maps `column`/`row`/`textInput`/`checkbox` and every
   non-allowlisted tag to `<div data-kinetica-tag="…">`. `kinetica-test/test/KineticaSnapshotTest.kt:78`
   and `samples/todo/test/TodoAppTest.kt:55` assert the old verbatim output and now fail
   (`assertHtmlSnapshot` → `toSafeHtml`, strict `!=`). Those modules were not in the commit's
   "172 tests pass" tally, so the branch does not pass its full suite. Beyond CI it is an SSR-fidelity
   regression: `host("select")`, `textarea`, `video`, `audio`, `canvas`, `dialog`, and custom elements
   now server-render as inert divs while the browser renderer emits them live.
   **Fix — decide the intended SSR shape, then one of:**
   - (a) neutralize only *executable* tags (a denylist: `script`, `iframe`, `object`, `embed`, `svg`,
     `math`, `template`, `style`, `link`, `meta`, `base`), keeping the char-gate for everything else, so
     legitimate DSL/HTML tags survive; **or**
   - (b) keep the allowlist but update the two snapshots (and document that DSL tags SSR as div).
   Option (a) preserves behavior and still closes the injection sink; prefer it. Ties to item 6.

4. **Deferred-subtree render errors leak raw messages.**
   `kinetica-runtime/src/ServerComponents.kt:663` sends `message = error.message ?: error.toString()`
   into a `BoundaryError` chunk streamed to the client. The action and 500 paths were scrubbed to generic
   strings; this parallel streaming path was not, so a throwing `ServerRenderDeferredSubtree` (the
   documented data-fetching pattern) can stream a JDBC/credential error to any `/stream` client.
   **Fix:** log the detail server-side and put a generic message in the `BoundaryError`, mirroring the
   action-handler scrubbing.

5. **Type-invalid-but-shape-valid payload returns `200 "Server action failed."` instead of `400`.**
   `serverActionStub.dispatch` (`ServerComponents.kt`) passes `inputSchema.validate` for e.g.
   `quantity: 3.5` or `> Int.MAX` (both match `Number`), then `decodeFromJsonElement` into `Int` throws
   inside `catch (Throwable)` → generic `Failure` at HTTP 200, bypassing both the 400 mapping and
   `validationError()`.
   **Fix:** separate payload-decode failure (client error) from handler failure — decode before the
   `try` that guards the handler, and return a validation `Failure` (or surface it through
   `dispatchHttp` as 400). Ties to item 7.

## Remaining — design / altitude

6. **Two hand-maintained tag tables drift.** `safeHtmlTagName` (`Html.kt`) and `browserTagNameFor`
   (`kinetica-browser/src/BrowserMapping.kt`) both encode the DSL-tag→element mapping with no shared
   source of truth; adding a DSL builder needs edits in two modules with no compile-time check. This is
   the root cause of item 3's SSR/CSR divergence. **Fix:** one tag-descriptor table in the runtime with
   per-renderer projections that both renderers consume.

7. **`ServerActionRejection` is exception-as-control-flow.** `ServerComponents.kt:216` — the handler type
   is `suspend (I) -> O`, so throwing is the only in-handler rejection path and any other throwable is
   swallowed to a generic message. **Fix:** add a `validate: (I) -> String? = { null }` parameter to
   `serverActionStub` that runs before the handler and returns a `Failure` on non-null, making
   shape+domain validation one declarative phase; keep the exception only for mid-handler faults.

8. **Fail-closed verifiers are a silent default, not a required parameter.** `ServerComponents.kt:376`
   defaults `verifyCapabilityToken`/`verifyCsrfToken` to `{ false }`. An adopter who forgets to wire one
   gets `"Invalid capability token."` on every action — indistinguishable from a real mismatch.
   **Fix (consider):** make the verifiers required constructor parameters so "auth was never configured"
   is a compile error rather than a runtime symptom. Resolves item 2's root cause too.

## Remaining — minor / cleanup

9. **`SERVER_THREAD_POOL_SIZE` still duplicated** in both demo companions (`docs …` and `samples …`).
   Move next to the shared limits or into a tiny shared server-util if one is created.

10. **`readBoundedRequestBody` allocates the full buffer regardless of `Content-Length`.** Optional:
    read `Content-Length` first and reject an over-limit declared size (or size the buffer to it) before
    allocating/reading; the bounded loop must stay because the header can lie. Also revisit whether the
    new 64 KB default is the right framework value.

11. **Duplicate `Failure` construction** in `serverActionStub`'s catch (`ServerComponents.kt:255`):
    collapse the `if/else` to one `Failure` with a computed message.

12. **Dead elvis fallback** `throwable.message ?: "Server action rejected."` (`ServerComponents.kt:256`):
    `ServerActionRejection`'s message is non-null by construction — declare
    `class ServerActionRejection(override val message: String)` and drop the fallback.

13. **`validationError` KDoc overstates overflow prevention** (`server-components-shared/src/Contracts.kt:33`):
    the per-call cap does not stop the cumulative `AtomicInteger cartCount` from overflowing `Int`.
    Correct the comment (or make `cartCount` saturating/`Long` for the demo).

## Assessed — low priority / not diff-introduced

- **No request-read timeout on the fixed thread pool** (slowloris): real, but strictly better than the
  prior single-thread `executor=null`; a demo-server residual, not introduced here.
- **413 path does not drain a >64 KB request body** → a mid-upload client may see a connection reset
  instead of a clean 413 (`com.sun.net.httpserver` drains a bounded amount on close).
- **`respondText("")` (empty body) sends chunked** instead of `Content-Length: 0` — latent; no current
  caller passes an empty body.
- **Catch-all `respondText(500)` can double-send headers** if a prior handler already sent them (client
  disconnect mid-write) — pre-existing; the client is already gone.
- **Docs `### Hardening for production` lacks a `<!-- code: … -->` link** — no CLAUDE.md governs it and
  the convention appears to apply at `##` granularity; cosmetic.
