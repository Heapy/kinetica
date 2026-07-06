// End-to-end verification of the docs site: SSR pages, live examples, hydrated demo island.
// Usage: DOCS_BASE_URL=http://127.0.0.1:8080 node docs/verify-docs.mjs
import { existsSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { pathToFileURL } from "node:url";

const base = process.env.DOCS_BASE_URL ?? "http://127.0.0.1:8080";
const vendoredPlaywright =
  "/Users/yoda/dev/pet/kinetica/.tools/playwright/node_modules/playwright/index.mjs";
const vendoredChromium =
  "/Users/yoda/dev/pet/kinetica/.playwright-browsers/chromium-1228/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing";
const playwrightImport = process.env.PLAYWRIGHT_IMPORT ??
  (existsSync(vendoredPlaywright) ? vendoredPlaywright : "playwright");
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ??
  (existsSync(vendoredChromium) ? vendoredChromium : undefined);

const { chromium } = await import(
  playwrightImport.startsWith("/") ? pathToFileURL(playwrightImport) : playwrightImport
);

function rawGet(url, headers = {}) {
  const target = url instanceof URL ? url : new URL(url);
  const client = target.protocol === "https:" ? https : http;
  return new Promise((resolve, reject) => {
    const request = client.request(target, { headers }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => resolve({
        status: response.statusCode,
        headers: response.headers,
        body: Buffer.concat(chunks),
      }));
    });
    request.on("error", reject);
    request.end();
  });
}

function extractAsset(html, pattern, label) {
  const match = html.match(pattern);
  if (!match) throw new Error(`missing ${label} asset URL`);
  return new URL(match[1], base);
}

async function verifyStaticAsset(url) {
  const response = await rawGet(url, { "Accept-Encoding": "br" });
  if (response.status !== 200) throw new Error(`${url.pathname} -> ${response.status}`);
  if (response.headers["content-encoding"] !== "br") {
    throw new Error(`${url.pathname} did not serve Brotli`);
  }
  if (!response.headers["cache-control"]?.includes("immutable")) {
    throw new Error(`${url.pathname} missing immutable cache-control`);
  }
  const etag = response.headers.etag;
  if (!etag) throw new Error(`${url.pathname} missing ETag`);

  const revalidated = await rawGet(url, {
    "Accept-Encoding": "br",
    "If-None-Match": etag,
  });
  if (revalidated.status !== 304) {
    throw new Error(`${url.pathname} ETag revalidation -> ${revalidated.status}`);
  }
}

const browser = await chromium.launch({ headless: true, executablePath });
const page = await browser.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });

try {
  // health + all doc pages render server-side
  const health = await fetch(`${base}/healthz`);
  if (!health.ok) throw new Error("healthz failed");
  const home = await fetch(base).then((r) => r.text());
  for (const needle of ["Kinetica", "sidebar", "Design pillars", "/docs/getting-started"]) {
    if (!home.includes(needle)) throw new Error(`home page missing: ${needle}`);
  }
  const slugs = ["getting-started", "state", "ui-dsl", "lists-and-keys", "effects", "resources",
    "router", "forms", "motion", "data", "persist", "markdown", "server-components",
    "browser-renderer", "performance", "compiler-plugin", "testing"];
  for (const slug of slugs) {
    const res = await fetch(`${base}/docs/${slug}`);
    if (!res.ok) throw new Error(`/docs/${slug} -> ${res.status}`);
    const body = await res.text();
    if (!body.includes("<h1>")) throw new Error(`/docs/${slug} has no h1`);
  }
  console.log(`OK   ${slugs.length + 1} pages server-render`);

  const demoHtml = await fetch(`${base}/examples/server-components`).then((r) => r.text());
  await verifyStaticAsset(extractAsset(home, /href="([^"]*\/assets\/site\.css\?hash=[0-9a-f]+)"/, "site.css"));
  await verifyStaticAsset(extractAsset(home, /src="([^"]*\/docs-client\/docs-client\.mjs\?hash=[0-9a-f]+)"/, "docs-client"));
  await verifyStaticAsset(
    extractAsset(
      demoHtml,
      /src="([^"]*\/sc-client\/server-components-client\.mjs\?hash=[0-9a-f]+)"/,
      "server-components-client",
    ),
  );
  console.log("OK   static assets are hashed, Brotli-compressed, and cacheable");

  // live example: counter mounts and is interactive
  await page.goto(`${base}/docs/state`, { waitUntil: "networkidle" });
  await page.waitForSelector('[data-example="counter"] button', { timeout: 10_000 });
  await page.click('[data-example="counter"] [data-testid="ex-increment"]');
  await page.waitForFunction(
    () => document.querySelector('[data-example="counter"]')?.textContent?.includes("1 click"),
    undefined, { timeout: 5_000 },
  );
  console.log("OK   live counter example mounts and reacts");

  // live example: keyed list reverse
  await page.goto(`${base}/docs/lists-and-keys`, { waitUntil: "networkidle" });
  await page.waitForSelector('[data-example="keyed-list"] li', { timeout: 10_000 });
  const before = await page.$$eval('[data-example="keyed-list"] li', (n) => n.map((x) => x.textContent));
  await page.click('[data-example="keyed-list"] button:has-text("Reverse")');
  await page.waitForFunction(
    (first) => document.querySelector('[data-example="keyed-list"] li')?.textContent !== first,
    before[0], { timeout: 5_000 },
  );
  console.log("OK   keyed-list example reorders");

  // server-components demo: hydration island + typed action + stream
  await page.goto(`${base}/examples/server-components`, { waitUntil: "networkidle" });
  await page.getByText("Hydration plan loaded: 1 client island").waitFor({ timeout: 10_000 });
  await page.waitForFunction(() =>
    document.querySelector("#recommendations")?.textContent?.includes("Deferred recommendations loaded on the server"),
    undefined, { timeout: 10_000 },
  );
  await page.locator('[data-testid="add-to-cart"]').click();
  await page.getByText(/Added 1 of runtime-license/).waitFor({ timeout: 10_000 });
  console.log("OK   server-components demo hydrates, streams, dispatches action");

  if (errors.length) throw new Error(`page errors: ${errors.join(" | ")}`);
  console.log("Docs verification passed");
} finally {
  await browser.close();
}
