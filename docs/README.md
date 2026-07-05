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
./kotlin build -m docs-client
./kotlin build -m server-components-client
PORT=8080 ./kotlin run -m docs-site
open http://127.0.0.1:8080/
```

The server reads JS bundles from `build/tasks/` by default (`KINETICA_BUNDLES_DIR` overrides).

## Docker

```sh
docs/docker-build.sh                          # package + build bundles + docker build
docker run --rm -p 8080:8080 kinetica-docs
```

The image is self-contained: the executable jar (markdown content and CSS live inside it as
resources) plus the two JS bundle graphs under `/app/bundles`. `PORT` env is honored;
`/healthz` backs the container healthcheck. `docs/.docker-stage/` is a disposable build context
created by the script.

## Verify

```sh
DOCS_BASE_URL=http://127.0.0.1:8080 node docs/verify-docs.mjs
```

Checks: all pages server-render, the live counter/keyed-list examples mount and react, and the
server-components demo hydrates its island, receives the streamed patch, and dispatches the
typed add-to-cart action.

## Adding a page

1. Write `docs/docs-site/resources/docs/<slug>.md`.
2. Register the slug in `DocPages` (`docs/docs-site/src/Pages.kt`) — order defines the sidebar.
3. Embed live examples with a `::: example <name>` line; implement the name in
   `docs/docs-client/src/main.kt`.
4. `./kotlin build -m docs-site && PORT=8080 ./kotlin run -m docs-site`.
