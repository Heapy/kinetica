// Assertions use a draw-phase snapshot, while trusted coordinate clicks preserve the DOM
// harness's EventDispatch trace anchor. Row coordinates derive from one fixed-height origin so
// the canvas app does not publish 10,000 positions as extra measured work.

// This automation guard is outside trace-derived durations and matches openPage.
const TIMEOUT = 180_000;
const POLLING = 100;

// Publishing only asserted positions avoids adding per-row bridge work to canvas measurements.
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
