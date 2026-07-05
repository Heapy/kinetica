# Server Components Demo

This is the end-to-end Kinetica server components demo. It serves a JVM-rendered page,
hydrates a Kotlin/JS client island, streams a deferred server patch, and calls a typed
server action.

## Run

Build the browser island first:

```shell
./kotlin build -m server-components-client
```

Start the JVM server:

```shell
./kotlin run -m server-components -- --port=4180
```

Open:

```text
http://127.0.0.1:4180/
```

## What To Check

- The initial document is server-rendered HTML.
- The hydration plan is embedded in `#kinetica-hydration-plan`.
- The add-to-cart client island hydrates from `ClientRef` props.
- The `/stream` endpoint applies deferred recommendations.
- The `cart.add` server action updates the cart status from the JVM handler.

For a protocol-only snapshot without starting the HTTP server:

```shell
./kotlin run -m server-components -- --print
```
