// Harness for frameworks that paint into a <canvas> instead of building a DOM (Compose
// Multiplatform on skiko). It serves the same contract as makeHarness in common.mjs, so the
// benchmark definitions in bench.mjs are unchanged — only the way state is observed differs.
//
// Two deliberate deviations from the DOM path, both documented in bench/README.md:
//
//   * Assertions read `window.__bench.frame`, a snapshot the app publishes from its draw phase.
//     It therefore describes a frame that was actually painted, which is the property the DOM
//     assertions get for free by querying the DOM after a render.
//   * Clicks are coordinate clicks (page.mouse.click) on rectangles the app publishes. These
//     are trusted input events exactly like Playwright's element clicks, so the trace anchor
//     (`EventDispatch` of type click) is identical; only the targeting differs, because there
//     is no element to target.
//
// Row-level clicks are derived arithmetically from one published origin (`rowGeometry`) rather
// than from per-row rectangles: publishing 10,000 row positions would be measured work that no
// other framework in the suite performs. Rows are fixed-height, so row N's centre is exact.

// Matches openPage's default-timeout rationale: an automation guard, not part of any measured
// duration (those come from trace timestamps). The Column variant's remove-10k already runs for
// ~37s on a fast machine, which leaves no margin at 60s on a slower CI runner.
const TIMEOUT = 180_000;
const POLLING = 100;

// The suite only ever reads the ids at these positions (replace/remove/swap assertions), and
// the app publishes exactly those — asking for another position is a bug, not a missing feature.
const PUBLISHED_ID_POSITIONS = [1, 2, 5, 999];

export function makeCanvasHarness(page) {
  const waitBridge = (fn, arg) => page.waitForFunction(fn, arg, { polling: POLLING, timeout: TIMEOUT });

  const rectOf = async (name) => {
    await waitBridge((n) => window.__bench?.rects?.[n] != null, name);
    return page.evaluate((n) => window.__bench.rects[n], name);
  };

  const clickRect = async (name) => {
    const rect = await rectOf(name);
    await page.mouse.click(rect.x + rect.width / 2, rect.y + rect.height / 2);
  };

  const clickRow = async (n, column) => {
    await waitBridge(() => window.__bench?.rowGeometry != null);
    const geometry = await page.evaluate(() => window.__bench.rowGeometry);
    const y = geometry.top + (n - 0.5) * geometry.height;
    await page.mouse.click(column === "remove" ? geometry.removeX : geometry.labelX, y);
  };

  return {
    page,
    async clickButton(name) {
      await clickRect(name);
    },
    async hasButton(name) {
      return page.evaluate((n) => window.__bench?.rects?.[n] != null, name);
    },
    async clickRowSelect(n) {
      await clickRow(n, "label");
    },
    async clickRowRemove(n) {
      await clickRow(n, "remove");
    },
    async waitRows(count) {
      await waitBridge((expected) => window.__bench?.frame?.rowCount === expected, count);
    },
    async rowCount() {
      return page.evaluate(() => window.__bench?.frame?.rowCount ?? 0);
    },
    async rowId(n) {
      if (!PUBLISHED_ID_POSITIONS.includes(n)) {
        throw new Error(
          `canvas harness: row id at position ${n} is not published ` +
            `(the app publishes ${PUBLISHED_ID_POSITIONS.join(", ")})`,
        );
      }
      return page.evaluate((i) => window.__bench.frame.ids[i], n);
    },
    async firstLabel() {
      return page.evaluate(() => window.__bench?.frame?.firstLabel ?? "");
    },
    async statusText() {
      throw new Error("canvas harness: no tree app, statusText is not available");
    },
    async waitFn() {
      throw new Error(
        "canvas harness: waitFn takes a DOM predicate; use a named harness assertion instead",
      );
    },
    async waitReplaced(prevId, count) {
      await waitBridge(
        ({ p, c }) => window.__bench?.frame?.rowCount === c && window.__bench.frame.ids[1] !== p,
        { p: prevId, c: count },
      );
    },
    async waitLabelSuffix(suffix) {
      await waitBridge((s) => (window.__bench?.frame?.firstLabel ?? "").endsWith(s), suffix);
    },
    async waitSelected(n) {
      await waitBridge((r) => window.__bench?.frame?.selectedIndex === r, n);
    },
    async waitSwapped(prevRow2) {
      await waitBridge(
        (p) => window.__bench?.frame?.ids[999] === p && window.__bench.frame.ids[2] !== p,
        prevRow2,
      );
    },
    async labelHasTick() {
      return page.evaluate(() => /( !\d+)$/.test(window.__bench?.frame?.firstLabel ?? ""));
    },
  };
}
