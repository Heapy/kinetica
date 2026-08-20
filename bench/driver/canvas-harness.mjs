// Assertions use a draw-phase snapshot. Row coordinates derive from one fixed-height origin so
// the canvas app does not publish 10,000 positions as extra measured work.
//
// This harness also times its own operations, instead of going through parseTrace, because a
// Chrome trace cannot see this renderer at either end of the window. Calibrated against clicks
// of known cost (bench/driver/calibrate.mjs): both ends measured in the page, from a capture-phase
// `pointerdown` to the frame's `drawnAt`, this reports slope 0.9953 with 6.5ms of fixed
// overhead — against the DOM path's 0.9935 / 7.1ms, so the two produce the same quantity.
//
//   * Start. parseTrace opens the window on `EventDispatch` of type click, but Compose runs its
//     handler on **pointerup**, so the browser only dispatches `click` after the operation has
//     finished — measured: pointerdown +0.0, pointerup +1.4 (a 1000ms operation runs here),
//     mouseup +1001.8, click +1001.8, draw +1005.0. A click-anchored window opens after the
//     work it is supposed to contain.
//   * End. A canvas frame emits no Blink `Paint` and no `Commit`. Where the frame provably
//     lands the trace holds zero events, `gpu`/`viz`/`cc` categories included, so any paint the
//     trace does offer belongs to unrelated DOM-layer activity.
//
// The cost of owning the timing is GC attribution: the per-operation GC figures come from trace
// events inside the measured window, and this window is not expressed in trace time. Canvas
// entries therefore report no GC.

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
    // Present only on this harness; driver/bench.mjs prefers it over measureTracedClick.
    async measureOperation(action, wait) {
      await page.evaluate(() => {
        window.__benchMark = { start: null };
        window.addEventListener(
          "pointerdown",
          () => { if (window.__benchMark.start === null) window.__benchMark.start = performance.now(); },
          { capture: true, once: true },
        );
      });
      await action();
      await wait();
      const { start, drawnAt } = await page.evaluate(() => ({
        start: window.__benchMark?.start ?? null,
        drawnAt: window.__bench?.frame?.drawnAt ?? null,
      }));
      if (start === null) return { error: "no pointerdown recorded" };
      if (drawnAt === null) return { error: "app published no draw timestamp" };
      if (drawnAt < start) return { error: "draw predates the click" };
      return { durationMs: drawnAt - start, clickDispatchMs: 0, gcMs: 0, gcCount: 0 };
    },
  };
}
