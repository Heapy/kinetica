# Kinetica documentation site

Self-hosting documentation: every page is Markdown parsed by `kinetica-markdown`, rendered to a
Kinetica `Node` tree on the JVM, and served as safe HTML by the framework's own server-rendering
path. Interactive examples are Kinetica browser apps mounted into the pages; the
server-components demo (hydrated island + streamed patch + typed action) is hosted by the same
server.

## Modules

| Path | What it is |
|------|------------|
| `docs/docs-site` | `jvm/app` — the HTTP server; content in `resources/docs/*.md`, styles in `resources/site.css` |
| `docs/docs-client` | `js/app` — mounts the live examples (`::: example <name>` directives) |
| `kinetica-markdown` | the Markdown → Kinetica nodes battery (repo root) |

## Run locally

```sh
cd bench && npm install && cd ..
./kotlin publish mavenLocal -m kinetica-compiler   # mandatory plugin, resolved via mavenLocal
node scripts/bundle-docs.mjs
node scripts/build-game-of-life.mjs
PORT=8080 ./kotlin run -m docs-site
open http://127.0.0.1:8080/
```

The server reads JS bundles from `build/tasks/` by default (`KINETICA_BUNDLES_DIR` overrides).
Bundled outputs under `_docs-client_bundle` and `_server-components-client_bundle` are preferred;
raw Kotlin/JS link outputs remain a fallback for local development. `node scripts/bundle-docs.mjs`
also writes Brotli sidecars and a generated `_docs-site_assets/site.css`; the server emits
hash-query asset URLs, immutable caching for matching hashes, ETags, and `Content-Encoding: br`
when the browser requests it. `node scripts/build-game-of-life.mjs` stages the four Game of Life
apps, their trace report, and raw results under `/game-of-life/`.

## Docker

```sh
docs/docker-build.sh                          # package + build bundles + docker build
docker run --rm -p 8080:8080 kinetica-docs
```

The image is self-contained: the executable jar (markdown content and CSS live inside it as
resources) plus the two bundled JS entrypoints under `/app/bundles`. `PORT` env is honored;
`/healthz` backs the container healthcheck. `docs/.docker-stage/` is a disposable build context
created by the script.

## Verify

```sh
DOCS_BASE_URL=http://127.0.0.1:8080 node docs/verify-docs.mjs
```

Checks: all pages server-render with no source-link comment leaking into the HTML, the live
counter/keyed-list/effect-timer/form-signup examples mount and react, the resource-fetch demo
on `/docs/resources` loads its per-visitor session stack, surfaces the backend's intentional
`NullPointerException` for "Java" through the error boundary and recovers on retry, and the
server-components demo hydrates its island, receives the streamed patch, and dispatches the
typed add-to-cart action.

## Adding a page

1. Write `docs/docs-site/resources/docs/<slug>.md`.
2. Register the slug in `DocPages` (`docs/docs-site/src/Pages.kt`) — order defines the sidebar.
3. Link every section to the code it documents with an HTML comment right under the heading:
   `<!-- code: kinetica-runtime/src/ComponentScope.kt (state, derived) -->`. Comments are
   skipped by the markdown parser and never render; they keep prose and implementation
   cross-checkable.
4. Embed live examples with a `::: example <name>` line; implement the name in
   `docs/docs-client/src/main.kt`.
5. `node scripts/bundle-docs.mjs && ./kotlin build -m docs-site && PORT=8080 ./kotlin run -m docs-site`.
