# Kinetica soundness test plan (branch frame-ordinals)

Synthesized from 6 framework test-suite surveys (~850 raw cases: INF=Inferno 143, RCT=React 139,
SVL=Svelte 120, SOL=Solid 84, PRE=Preact 158, VUE=Vue 206), deduplicated against Kinetica's
existing coverage, filtered for applicability to the plugin-only / ordinals / template-cloning /
no-VDOM / keyed-each / shared-event-table model.

**135 cases, 14 independent batches.** Each batch = one new test file. Batches share no new
helpers (private per-file helpers are duplicated by design — Kotlin `private` top-level is
file-scoped, so identical helper names across files are legal; the existing suite already does
this with `firstElement`). A worker can implement any batch from this document alone.

---

## 0. Verified environment facts (grounding — do not re-derive)

- **SVG**: zero namespace support in kinetica-browser / kinetica-runtime (no `createElementNS`,
  no `namespaceURI` anywhere in src). All SVG cases → Deferred.
- **Delegation model** (kinetica-browser/src@js/BrowserKineticaApp.kt:139-226): root-element
  listeners for exactly `click`, `input`, `change`, `keydown` (Enter only). Dispatch walks from
  `event.target` up via `parentElement`; the **first** element whose mounted host / template
  binding handles the event **consumes it** (walk returns) — ancestors do NOT also fire.
  Click checks `props["enabled"]` (host path) / `disabled` attribute (template path); a disabled
  match still consumes the event without dispatching. `input` payload = live `element.value`.
  `keydown` Enter calls `preventDefault()` then dispatches.
- **Controlled inputs**: every committed render syncs `input.value` back to the rendered prop
  even when props didn't change (BrowserKineticaApp.kt:422-429). `checked` set via property.
- **Browser re-render is explicit**: `BrowserKineticaApp.render()` is public; store/cell writes
  outside `dispatch` do NOT auto-render the browser app. Tests mutate a probe/store then call
  `app.render()`. Event dispatch calls `dispatchAndRender` internally.
- **TestDom** (kinetica-browser/test@js/TestDom.kt): hand-rolled `document` installed on
  `globalThis` per `installTestDocument()` call. Has insertBefore/removeChild/cloneNode/
  attributes/listeners/dispatchEvent-with-bubbling/outerHTML/textContent/value/checked/type/
  placeholder/`isConnected`. Exposes the element class as `globalThis.Element` → tests can
  monkeypatch `Element.prototype` AFTER `installTestDocument()` for op logging. Has NO
  focus()/selection, NO stopPropagation on synthetic events (not needed — Kinetica's walk is
  internal).
- **DSL surface** (kinetica-runtime/src): `column/row/button/text/textInput/checkbox/host/
  fragment`, `each(items, key, memoize)` (no built-in fallback param — empty-list fallback is an
  `if/else` branch), `keyed(key){}`, `state/derived/event/store/watch/launchEffect/layoutEffect/
  onExit/exitGroup/resource`, `errorBoundary(fallback = { err, info, retry -> }, content)`,
  `loadingBoundary`. No select/radio/textarea. `host(tag, props: Map<String,String>, frameProps,
  semantics, key, content)` — props are strings, so attr type-coercion cases are N/A.
- **kinetica-test**: `KineticaTest.render {}` → `TestRoot` (`awaitIdle`, `node/click/input`,
  matchers `hasTestTag/hasRole/hasText/hasLabel`, `journal()`, `tree()`, `dispose()`).
  Journal: `root.journal().count { it.kind == JournalKind.RenderCommitted }`.

## 0.1 Global authoring rules (apply to every batch)

1. Build preamble (once per checkout, and after any compiler change):
   `./kotlin publish mavenLocal -m kinetica-compiler` — then, if the compiler was republished at
   the SAME version, `touch` any source in the consuming module to defeat stale compile caches.
2. Every slot-consuming body is a **top-level `private @UiComponent fun ComponentScope.X(...)`**
   (use `@UiComponent(skippable = false)` when the test asserts render counts). Slots in
   multi-run lambdas (`List(n){}`, `repeat`, `map`) fail fast — use `each` for repetition.
3. Probes (counters, logs, stores) are **objects passed as component parameters**, never
   top-level mutable vars (mandatory for anything async; do it everywhere for uniformity).
   Scope-free `store(...)` cells are the way to drive data from the test body.
4. test@js: `installTestDocument()` is the FIRST statement of every test; always
   `try { ... } finally { app.dispose() }`.
5. Node identity: `assertSame(elementA, elementB)` on `org.w3c.dom.Element` refs captured
   before the operation (pattern: BrowserKeyedScratchCharacterizationTest).
6. Common async tests (kinetica-test, runTest): the bare Node runner does NOT await test
   promises — tests interleave. Salt ResourceRegistry keys per test, keep collaborators
   per-test, `dispose()` at the end. Prefer fully synchronous tests where possible.
7. `node bundle.mjs | tail; echo $?` reports tail's exit code — run the bundle without piping
   when checking pass/fail.
8. Remount vs reuse is asserted via **state-init side-effect counters**
   (`var n by state { probe.inits++; 0 }`): reuse ⇒ `inits` unchanged; remount ⇒ `inits+1`.
   This is the Kinetica analog of the surveys' Mount/Unmount op logs.

### Run commands per module

```sh
# kinetica-browser batches (B01,B02,B03,B05,B06,B10,B11,B13,B14):
./kotlin build -m kinetica-browser
node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs

# kinetica-runtime batches (B04,B08) — common code, runs on BOTH platforms:
./kotlin test -m kinetica-runtime --platform jvm
./kotlin build -m kinetica-runtime --platform js
node build/tasks/_kinetica-runtime_linkJsTest/kinetica-runtime_test.mjs

# kinetica-test batches (B07,B09,B12) — common code, runs on BOTH platforms:
./kotlin test -m kinetica-test --platform jvm
./kotlin build -m kinetica-test --platform js
node build/tasks/_kinetica-test_linkJsTest/kinetica-test_test.mjs
```

## 0.2 Batch index

| Batch | Pri | Module / file | Platform | Cases |
|---|---|---|---|---|
| B01 keyed reorder correctness + node identity | P0 | kinetica-browser/test@js/BrowserKeyedReorderSoundnessTest.kt | JS (Node) | 13 |
| B02 keyed DOM-op minimality | P0 | kinetica-browser/test@js/BrowserKeyedOpCountTest.kt | JS (Node) | 11 |
| B03 keyed regions with neighbors | P0 | kinetica-browser/test@js/BrowserKeyedNeighborsTest.kt | JS (Node) | 11 |
| B04 key identity & row-state semantics | P0 | kinetica-runtime/test/EachIdentitySemanticsTest.kt | JVM+JS | 10 |
| B05 event delegation edges | P0 | kinetica-browser/test@js/BrowserEventDelegationEdgeTest.kt | JS (Node) | 13 |
| B06 controlled input snap-back | P0 | kinetica-browser/test@js/BrowserControlledInputTest.kt | JS (Node) | 9 |
| B07 error boundary semantics | P0 | kinetica-test/test/ErrorBoundarySoundnessTest.kt | JVM+JS | 12 |
| B08 JS-parity reactivity invariants | P1 | kinetica-runtime/test/ReactivityParityTest.kt | JVM+JS | 10 |
| B09 effect cleanup & disposal ordering | P1 | kinetica-test/test/EffectCleanupOrderingTest.kt | JVM+JS | 10 |
| B10 mount/unmount residue & detach | P1 | kinetica-browser/test@js/BrowserMountResidueTest.kt | JS (Node) | 7 |
| B11 text & child-shape transitions | P2 | kinetica-browser/test@js/BrowserChildShapeTest.kt | JS (Node) | 8 |
| B12 scheduler / batch ordering | P1 | kinetica-test/test/BatchOrderingTest.kt | JVM+JS | 8 |
| B13 template multi-instance identity | P1 | kinetica-browser/test@js/BrowserTemplateInstanceTest.kt | JS (Node) | 6 |
| B14 host prop/attr patching edges | P2 | kinetica-browser/test@js/BrowserHostPropPatchTest.kt | JS (Node) | 7 |

Total: **135**.

---

# B01 — Keyed reorder correctness + node identity (P0)

**File:** `kinetica-browser/test@js/BrowserKeyedReorderSoundnessTest.kt`
**Pattern:** raw-node emission (copy BrowserKeyedScratchCharacterizationTest): build keyed
`HostNode` children (helper `keyedList(keys: List<String>): Node` producing a parent host whose
children are `host(key=k)` each containing `TextNode(k)`), hold `var current: Node`, mount with
`mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) { emit(current) }`, then per
step: reassign `current`, `app.render()`, assert. Define private helpers `firstElement`,
`childElement(parent, index)`, `expectedHtml(keys)` in this file (copy from the
characterization test). For identity assertions capture `Element` refs before the step and
`assertSame` after. No DSL components needed in this batch.

For KSND-012 use a deterministic seeded PRNG (e.g. `var seed = 42; fun nextInt(bound): Int`
linear congruential in the file) — NO kotlin.random default seeding.

**KSND-001 · empty ↔ nonempty cycles are residue-free**
Sources: INF-001, INF-031, VUE-089, VUE-094, VUE-095, SOL-045
Scenario: `[]` → `[a,b,c,d]` → `[]` → `[a,b,c,d]` → `[]` (each step render+assert).
Assert: html equals expected each step; after every `[]` step the list parent has
`childNodes.length == 0` (no stray text/anchor nodes); second population count == 4.

**KSND-002 · append / prepend / insert-middle / insert-both-ends**
Sources: INF-002, INF-003, VUE-090, VUE-091, VUE-092, VUE-093, PRE-004, PRE-006, PRE-008, RCT-012, SVL-033
Scenario table (one test, loop over steps, fresh mount per row):
`[a,b]→[a,b,c]`, `[c]→[a,b,c]`, `[1,2,4,5]→[1,2,3,4,5]`, `[2,3,4]→[1,2,3,4,5]`, `[1,2,3]→[1,4,2,3]→[1,4,5,2,3]`.
Assert: order exact; every surviving key's element `assertSame` its pre-step element.

**KSND-003 · removal at head / tail / middle / contiguous run**
Sources: INF-004, VUE-096, VUE-097, VUE-098, PRE-005, PRE-007, PRE-009, RCT-011
Scenario table: `[a,b,c]→[b,c]`, `→[a,b]`, `[1..5]→[1,2,4,5]`, `[a,b,x,y,z,c,d]→[a,b,c,d]`.
Assert: order exact; survivors keep identity; removed keys' elements have `parentNode == null`.

**KSND-004 · full reverse preserves every node**
Sources: INF-005, VUE-106, PRE-016, RCT-009, SOL-041
Scenario: `[a,b,c,d]→[d,c,b,a]`; then `[0..9]→[9..0]`.
Assert: order exact; all 4 (resp. 10) elements `assertSame` across the reverse.

**KSND-005 · swaps: ends, adjacent, middle pair — and back**
Sources: INF-006, VUE-102, PRE-012, PRE-013, RCT-004, RCT-006, SOL-041
Scenario: `[1,2,3,4]→[4,2,3,1]→[1,2,3,4]`; `[a,b]→[b,a]`; `[a,b,c,d]→[a,c,b,d]→[a,b,c,d]`.
Assert: order after every step; the swapped pair are the SAME two elements exchanged
(orig[first] === new[last] etc., per RCT-004).

**KSND-006 · single moves: front→back, back→front, interior both directions**
Sources: INF-007, INF-008, VUE-099, VUE-100, VUE-101, PRE-010, PRE-014, SOL-041
Scenario table: `[1,2,3]→[2,3,1]`, `[0,1,2,3,4]→[4,0,1,2,3]`, `[1,2,3,4]→[2,3,1,4]`, `→[1,4,2,3]`.
Assert: order + all-node identity each row.

**KSND-007 · cyclic rotation loops return to origin intact**
Sources: INF-009, RCT-010, SVL-025
Scenario: `[1,2,3,4]` rotated left 4× back to `[1,2,3,4]`, then rotated right 4×.
Assert: order at EVERY intermediate step; after the full cycle all elements `assertSame` the
originals (no silent recreation mid-cycle).

**KSND-008 · LIS-defeating permutations**
Sources: INF-014, VUE-107, SOL-042, PRE-015
Scenario table: `[0..5]→[4,3,2,1,5,0]`; `[0..9]→[8,1,3,4,5,6,0,7,2,9]`; `[0..9]→[9,5,0,7,1,2,3,4,6,8]`;
`[milk,bread,chips,cookie,honey]→[chips,bread,cookie,milk,honey]` (Solid "swap backward edge").
Assert: exact order; every element preserved by identity.

**KSND-009 · combined insert + delete + move in one update**
Sources: INF-010, INF-011, VUE-103, VUE-104, VUE-105, PRE-026, PRE-028, PRE-030, RCT-011
Scenario table: `[a,b,c,d]→[e,d,c,a]`; `[a..g]→[b,c,a]`; `[1..5]→[4,1,2,3,6]`; `[c,d]→[a,b,c,e]`;
`[1,4,5]→[4,6]`; `[A,B,C,D,E]→[B,E,C,D]→[B,E,D,C]`; `[0..4]→[2,3,4,5,6]`.
Assert: exact order + count; surviving keys keep identity; no duplicate keys in DOM
(scan children, assert distinct `data-kinetica-key`-free text set sizes match).

**KSND-010 · single-survivor pivot and full key-set replacement**
Sources: INF-012, PRE-020, VUE-103, SOL-041
Scenario: `[1,2,3]→[4,2,5]` (2 survives in the middle); `[0,1,2]→[3,4,5]` (nothing survives).
Assert: survivor `assertSame`; fully-replaced list has all-new elements (assertNotSame each).

**KSND-011 · large partial-overlap reorder (calendar shuffle)**
Sources: INF-013, INF-018
Scenario: 48 keys `wk31..wk36, d1..d31, o1..o11` → next-month layout
`wk35..wk40, o29..o31, d1..d30, o1..o9` (build the two lists programmatically).
Assert: exact expected text order; overlap keys keep identity; child count exact; no key
appears twice.

**KSND-012 · seeded random-permutation fuzz + restore invariant**
Sources: INF-017, INF-020, SVL-020, SVL-021, PRE-033, VUE-108, SOL-040
Scenario: fixed-seed LCG; 100 iterations: pick a random subset (0..12 items) of keys `a..m` in a
random order, render, assert; every 10th iteration restore the canonical full list and
re-assert (SOL "restore invariant" — catches corruption that only shows on the NEXT op).
Assert: `app.innerHtml() == expectedHtml(current)` every iteration; final restore intact.
Pitfall: keep iterations ≤100 — Node runner has no per-test timeout guard.

**KSND-013 · hostile key strings & numeric-vs-string key distinctness**
Sources: INF-015, INF-016
Scenario: keys `['<WEIRD/&\\key>']` → `['INSANE/(/&\\key', '<CRAZY/&\\key>', '<WEIRD/&\\key>']`;
then a list with keys `"1"` and `1.toString()+"x"`-style near-collisions plus keys `1`..`3` given
as `Any` (raw list uses String keys, so emulate: keys `"1"`, `"01"`, `"1 "` are three distinct rows).
Assert: all rows rendered, matching handled by exact string equality; the reused key's element
`assertSame`; html-escaped output correct (`&` etc. escaped in outerHTML).

---

# B02 — Keyed DOM-op minimality (P0)

**File:** `kinetica-browser/test@js/BrowserKeyedOpCountTest.kt`
**Pattern:** same raw-emit pattern as B01, PLUS a private DOM-op logger installed AFTER
`installTestDocument()` (each install replaces `globalThis.Element`, so install order matters):

```kotlin
// Records ops only when the RECEIVER is connected to the document tree, so template-clone
// assembly on detached parents does not pollute counts.
private fun installOpLog(): dynamic = js(
    """
    (function () {
      var ops = [];
      var proto = globalThis.Element.prototype;
      var origInsert = proto.insertBefore;
      var origRemove = proto.removeChild;
      proto.insertBefore = function (child, anchor) {
        if (this.isConnected) ops.push({ op: "insertBefore", child: child, anchor: anchor, parent: this });
        return origInsert.call(this, child, anchor);
      };
      proto.removeChild = function (child) {
        if (this.isConnected) ops.push({ op: "removeChild", child: child, parent: this });
        return origRemove.call(this, child);
      };
      return ops;
    })()
    """
)
private fun opCount(ops: dynamic): Int = ops.length.unsafeCast<Int>()
private fun clearOps(ops: dynamic) { ops.asDynamic().length = 0 }
private fun opAt(ops: dynamic, i: Int): dynamic = ops[i]
```

The mount root must be attached (`testDocument().body.insertBefore(root, null)`) or nothing is
"connected" — do this in every test of this batch. **Always `clearOps(ops)` after the initial
mount** and assert only on the update phase. A "move" is one `insertBefore` of an
already-connected child (TestDom insertBefore auto-detaches from the old parent without calling
the patched removeChild — so moves count as exactly 1 op).

**KSND-014 · append = exactly 1 insert** — Sources: PRE-004, INF-002.
`[a,b]→[a,b,c]`. Assert: `opCount == 1`, op is insertBefore of the new element with null/tail anchor.

**KSND-015 · prepend = 1 insert, survivors untouched** — Sources: PRE-006, VUE-091, INF-003.
`[b,c]→[a,b,c]`. Assert: 1 op; neither b nor c appears as `child` in any op.

**KSND-016 · remove head / tail = 1 remove each** — Sources: PRE-005, PRE-007, INF-004.
`[z,a,b,c]→[a,b,c]` then (fresh mount) `[a,b,c,d]→[a,b,c]`. Assert: exactly 1 removeChild whose
`child` is the removed element; zero inserts.

**KSND-017 · contiguous middle removal = N removes, 0 moves** — Sources: PRE-009, PRE-017, INF-004.
`[a,b,x,y,z,c,d]→[a,b,c,d]`. Assert: exactly 3 removeChild (x,y,z), 0 insertBefore.

**KSND-018 · adjacent swap ≤ 1 move** — Sources: PRE-012, PRE-013.
`[a,b]→[b,a]`; fresh mount `[a,b,c,d]→[a,c,b,d]`. Assert: exactly 1 insertBefore per swap,
0 removes; both elements preserved (identity).

**KSND-019 · single element moved in a longer list = 1 op (LIS quality)** — Sources: PRE-015, PRE-010, PRE-011.
`[a,b,c,d,e,f]` → move e to index 1; fresh mount → move b to index 4; fresh mount
`[b,c,d,a]→[a,b,c,d]`. Assert: exactly 1 insertBefore each, 0 removes.

**KSND-020 · full reverse of 10 = at most n−1 move ops** — Sources: PRE-016, VUE-106.
`[0..9]→[9..0]`. Assert: `opCount <= 9`, all ops are insertBefore, 0 removes, order correct.

**KSND-021 · growing-window diff = 2 removes + 2 inserts, middle untouched** — Sources: PRE-026.
`[0,1,2,3,4]→[2,3,4,5,6]`. Assert: exactly 2 removeChild (0,1) + 2 insertBefore (5,6); elements
2,3,4 never appear as `child` in any op.

**KSND-022 · clear-all uses the bulk path (no per-child removeChild)** — Sources: KNT-0012, INF-178, Playwright bulk-clear self-test.
`[a,b,c,d,e]→[]`. Assert: ZERO removeChild ops recorded on the connected parent (bulk clear goes
through `textContent`/detach walk, not per-child removeChild); parent `childNodes.length == 0`.
Pitfall: if this legitimately fails because the bulk path was changed to per-child removal,
that is a perf regression — the test encodes the KNT-0012 contract.

**KSND-023 · untouched survivors under middle-range replacement** — Sources: PRE-022.
30 keyed rows; replace rows 10..19 with 10 new keys, keep 0..9 and 20..29. Assert: no op's
`child` is a surviving element; 10 removes + 10 inserts exactly.

**KSND-024 · identical re-render = zero DOM ops** — Sources: RCT-001, INF-039, VUE-135, PRE-081.
Render `[a,b,c]`, clearOps, re-assign an EQUAL list, `app.render()`. Assert: `opCount == 0`;
all three elements identical by reference.

---

# B03 — Keyed regions with neighbors (P0)

**File:** `kinetica-browser/test@js/BrowserKeyedNeighborsTest.kt`
**Pattern:** @UiComponent DSL + store probes (this batch needs branches/each/state, not raw
nodes). Template:

```kotlin
private class NeighborProbe {
    val items = store(listOf("a", "b", "c"))
    val items2 = store(listOf<String>())
    val show = store(true)
    var rowInits = 0
}
@UiComponent(skippable = false)
private fun ComponentScope.NeighborsApp(probe: NeighborProbe) {
    column(semantics = Semantics(testTag = "root")) {
        text("head")                                    // static sibling before
        each(probe.items.value, key = { it }) { item -> host("div") { text(item) } }
        text("tail")                                    // static sibling after
    }
}
```
Drive: mutate stores → `app.render()` → assert on `app.innerHtml()` / captured elements.
One private component per case (or per small case family) — do NOT reuse one god-component.

**KSND-025 · appends land inside the keyed region, not after the static tail**
Sources: INF-025, SOL-043, PRE-046
Scenario: `[A,B]` between static head/tail → push C → push D.
Assert: DOM order head,A,B,C,D,tail after each push; head/tail elements keep identity.

**KSND-026 · first insert into an EMPTY region lands between the statics**
Sources: INF-025, INF-176, PRE-057
Scenario: items=[] between head/tail → push A → clear → push B (both sides exercised by a
mirrored component with the region before head).
Assert: order head,A,tail (resp. B,head,tail); statics untouched by identity.

**KSND-027 · two sibling keyed regions with identical keys stay independent**
Sources: INF-026
Scenario: component renders `each(items1, key)` then a static divider then `each(items2, key)`
where both lists use keys `[x,y]` → push `z` to both.
Assert: 5 rows in order x,y,divider,x,y → then x,y,z,divider,x,y,z; no element is shared between
regions (all 4 initial row elements distinct by reference; after push, region-1's x is still
region-1's x).

**KSND-028 · inline text adjacent to a keyed region keeps its position across empty↔nonempty**
Sources: INF-027, INF-172
Scenario: `text("inline")` + each(items) with items=[] → `[n1]` → `[]` → `[n1,n2]`.
Assert: text node stays first (and same node identity via `parent.childNodes.item(0)`); rows
appear strictly after it.

**KSND-029 · conditional node + keyed list sharing a parent: visibility × content matrix**
Sources: INF-028, INF-029, PRE-055
Scenario: `if (show) host("banner")` + each(items). Steps: (true,[a,b,c]) → (false,[v,a]) →
(true,[v,a]) → (true,[]) → (false,[]) → (true,[a]).
Assert: DOM equals `[banner?] + rows` exactly at every step; surviving rows keep identity across
banner toggles.

**KSND-030 · region reorder / full replace / empty between untouched statics; teardown & remount**
Sources: INF-030, INF-021
Scenario: statics test/end around each: `[1,2,3]` → `[3,2,1]` → `[9,8,7]` → `[]` → `[1,2,3]`.
Assert: statics keep identity through ALL steps; each step's order exact; after `[]`, only the
two statics remain.

**KSND-031 · nested each: outer reorder + inner reorder/empty in one update (DSL path)**
Sources: INF-019, SVL-029
Scenario: rows = `[(A,[1,2]), (B,[3])]` where each row renders inner each →
`[(B,[3,4]), (A,[2,1])]` in one render; then delete outer head repeatedly (SVL-029).
Assert: full html equals fresh-mount html of the same data at each step; outer row elements move
by identity; deleting the head removes the whole nested fragment.

**KSND-032 · multi-root rows move atomically and are removed as a unit**
Sources: SOL-044, SVL-030, PRE-136, INF-174
Scenario: each row emits TWO sibling hosts (`host("p"){text(k)}; host("span"){text("($k)")}`);
`[1,2,3]` → reverse → delete middle.
Assert: pair adjacency preserved for every key at each step (`p_k.nextElementSibling === span_k`);
both nodes of a deleted key removed; both nodes of moved keys keep identity.

**KSND-033 · clearing one region never bulk-clears the shared parent**
Sources: INF-178, INF-177, KNT-0012
Scenario: parent holds each#1 (2 rows) + each#2 (2 rows); one update sets items1=[x1..x4]
(grow) and items2=[] (clear).
Assert: after the update parent contains exactly each#1's 4 rows; each#1's surviving original
rows keep identity (proves the parent wasn't wiped by a `textContent = ""` shortcut).

**KSND-034 · empty-list fallback branch swaps with rows and stays reactive**
Sources: SVL-038, SOL-045, SOL-047
Scenario: `if (items.isEmpty()) text("empty $count") else each(items…)`; count is another store.
Steps: [] (fallback) → bump count twice (fallback text updates) → `[1,2,3]` (3 rows, fallback
gone) → `[]` (fallback back, still shows current count) → bump count (updates).
Assert: html at each step; row elements fresh on re-entry (no fallback/content aliasing).

**KSND-035 · inserting a sibling before a stateful row neither remounts nor resets it**
Sources: INF-033, RCT-012, PRE-052
Scenario: rows carry `var n by state { probe.rowInits++; 0 }` and render `"$k:$n"`; click-free
variant: bump one row's cell via a store-of-map driving row text; steps: `[a,c]` → set a's
data → insert b: `[a,b,c]`.
Assert: `rowInits` grows only by 1 (for b); a's and c's elements keep identity; a's mutated
text survives the insertion.

---

# B04 — Key identity & row-state semantics (P0, runtime-level, JVM+JS)

**File:** `kinetica-runtime/test/EachIdentitySemanticsTest.kt`
**Pattern:** drive `KineticaRuntime` directly (RuntimeSmokeCoreTest pattern — this module's
tests have `internal` access):

```kotlin
val runtime = KineticaRuntime()
val scope = ComponentScope(runtime)
fun render(): Node = runtime.render(scope) { App(probe) }.tree
// events: walk tree to HostNode, runtime.dispatch(node.props.getValue("event:onClick"))
```
Remount detection = state-init counters (`state { probe.inits++; 0 }`). Rows render their state
into text so assertions read the tree. All synchronous — no coroutines. Do NOT duplicate
EachMemoizationTest (reference-equal unchanged rows, per-row invalidation, eviction) — this
batch is about IDENTITY semantics only.

**KSND-036 · key change at the same position discards row state**
Sources: RCT-003, PRE-018, INF-041
Scenario: one-row each keyed `"a"`, row state bumped to 5 via dispatched event → swap items to
key `"b"` (same rendered shape) → back to `"a"`.
Assert: inits 1→2→3; state reads 0 after each swap (no resurrection either way — RCT-008 half).

**KSND-037 · key removed then re-added gets a FRESH row (no state resurrection)**
Sources: RCT-008
Scenario: `[x,y]`, bump x's state → `[y]` → `[x,y]`.
Assert: x's inits incremented on re-add; x's state == 0; y's state untouched throughout.

**KSND-038 · rotation preserves per-row state at every step**
Sources: RCT-010, RCT-009, PRE-021, SVL-042
Scenario: `[1,2,3,4]`, set row i's state to i×10 via events → rotate left 4× back to origin.
Assert: after EVERY rotation, each key still renders its own state (10,20,30,40 follow their
keys); inits never grow past 4.

**KSND-039 · reorder of skip-eligible rows still applies the new order**
Sources: RCT-014, PRE-017
Scenario: rows are skippable components with stable inputs (`memoize = true`, unchanged item
data) → permute keys `[X,K,W,H]` → `[H,W,K,X]`.
Assert: rendered order matches the permutation even though no row body re-ran (row node refs
reference-equal to previous render — reuse EachMemoization's reference-equality assertion style,
but on a permuted list).

**KSND-040 · same keys + new item data update rows in place**
Sources: SVL-024, PRE-001
Scenario: `[{id:1,"milk"},{id:2,"bread"}]` keyed by id → same ids with "beer"/"toast".
Assert: text updated; inits stay 2; per-row state (set before the data change) survives.

**KSND-041 · duplicate keys produce a deterministic diagnostic**
Sources: RCT-007, SVL-039, VUE-111
Scenario: items `[1,2,3,1]` keyed by value → render.
Assert: certify current behavior — `ComponentScope.duplicateKey` path: expect a thrown
exception (assertFailsWith) or a deterministic journal/diagnostic naming the key; ALSO the
update variant: valid list → introduce duplicate on second render → same diagnostic. If the
runtime silently tolerates duplicates, the test documents the last-wins/first-wins choice by
asserting rendered row count — pick whichever the implementation does and lock it in.

**KSND-042 · key type/content distinctness**
Sources: INF-015, INF-016
Scenario: keys `1` (Int) and `"1"` (String) as two rows (key: (T) -> Any) → shuffle with string
keys `"a/b\\c&<d>"`; re-render with the Int and String swapped in position.
Assert: 2 distinct rows exist; swapping positions moves state with each key (Int-keyed state
follows the Int key); hostile characters never merge/corrupt rows.

**KSND-043 · keyed(key){} branch identity: same key retains, new key resets**
Sources: SOL-062, SOL-061, RCT-005, PRE-124, PRE-125
Scenario: `keyed(currentKey) { var n by state { probe.inits++; 0 } ... }`; steps: key=A (bump
n=3) → key=A again (data-only change) → key=B → key=A.
Assert: A-rerender keeps n=3, inits=1; B resets (inits=2, n=0); return to A is FRESH (inits=3,
n=0 — no resurrection).

**KSND-044 · row disposal on key exit runs exactly once**
Sources: RCT-414, INF-108, SVL-110
Scenario: rows register `onExit { probe.exits += key }` (or a frame-slot with a disposal hook if
onExit is exit-group-only — then use a state cell + `watch` teardown observable via probe);
`[a,b,c]` → `[a,c]` → `[a]` → `[]`.
Assert: exits log is exactly [b], then [b,c], then [b,c,a] — once per removed key, never for
survivors, none doubled on later renders.

**KSND-045 · value-equal key objects are the same identity (stable data-class keys)**
Sources: SVL-040 (contrast), SVL-023
Scenario: keys are `data class K(val id: Int)` instances freshly allocated each render (new object,
equal value) → 3 re-renders with permutations.
Assert: rows are retained/moved (inits stay at initial count) — key matching is by `equals`,
not reference; documents the contract that fresh-but-equal key objects are STABLE (Svelte
rejects volatile keys; Kinetica's contract is equals-based — lock it in).

---

# B05 — Event delegation edges (P0)

**File:** `kinetica-browser/test@js/BrowserEventDelegationEdgeTest.kt`
**Pattern:** @UiComponent DSL components with probe counters passed as parameters;
`mountKineticaApp(root) { App(probe) }`; fire events via
`element.asDynamic().dispatchEvent(testDomEvent("click"))` (TestDom bubbles to the root
listener). Attach `root` to `document.body` first so the walk terminates at `rootElement`
deterministically. Private helpers: `firstElement`, `elementByTag(root, tag, index)` (walk
`_children` recursively), and a private listener-tally monkeypatch (same technique as B02's
op log) for KSND-053/054:

```kotlin
private fun installListenerTally(): dynamic = js(
    """
    (function () {
      var tally = { adds: 0, removes: 0 };
      var proto = globalThis.Element.prototype;
      var origAdd = proto.addEventListener, origRemove = proto.removeEventListener;
      proto.addEventListener = function (t, l) { tally.adds++; return origAdd.call(this, t, l); };
      proto.removeEventListener = function (t, l) { tally.removes++; return origRemove.call(this, t, l); };
      return tally;
    })()
    """
)
```

**KSND-046 · innermost handler consumes the click; ancestor host handler does NOT fire**
Sources: INF-064, SVL-060, RCT-219 (adapted to Kinetica's stop-at-first-match contract)
Scenario: outer `host("div", onClick→outer++)` wrapping inner `button(onClick→inner++)`;
dispatch click on the inner button element.
Assert: inner==1, outer==0 (CERTIFIES Kinetica's documented consume-at-first-match semantics —
the opposite of DOM bubbling; this is the contract test for BrowserKineticaApp.kt:147-149).
Note: register outer's click via `hostEvent`/`registerHostEvent` on a host node — see
RuntimeSmokeCoreTest for the host-event DSL; if hosts can't take onClick, use nested `button`s.

**KSND-047 · handler-less intermediate element does not terminate the walk**
Sources: INF-067, INF-068
Scenario: `button(onClick→outer++) { host("span") { host("b") { text("x") } } }`; dispatch click
on the innermost `b` element.
Assert: outer==1 — walk passes through elements without bindings and dispatches at the button.
Then re-render a variant where the button's branch drops onClick → dispatch → outer unchanged.

**KSND-048 · click on non-interactive child dispatches the enclosing button's event**
Sources: INF-062, SVL-059
Scenario: `button(onClick) { text("Save") }` — dispatch click directly on the TEXT-wrapping
child element (or the button's child span if text maps to a text node, dispatch on button and
set `event.target` to the text's parent).
Assert: handler fires exactly once; payload is Unit.

**KSND-049 · disabled button swallows clicks, including from its children; re-enable restores**
Sources: INF-062, RCT-213
Scenario: `button(enabled = probe.enabled.value, onClick→n++) { host("span"){ text("x") } }`;
click span while disabled → toggle enabled via store + `app.render()` → click again.
Assert: n==0 while disabled (and the event is CONSUMED — an outer button's handler also stays
0, matching dispatchTo returning true); n==1 after enable.

**KSND-050 · handler closure freshness across self-replacing renders**
Sources: INF-061, RCT-215, SVL-063, VUE-152
Scenario: counter component: `button(onClick = event { count += 1 })`, text shows count;
dispatch click 3× on the SAME element reference.
Assert: text 0→1→2→3; the button element is never recreated (assertSame across renders).

**KSND-051 · handler removed on re-render stops firing; re-added restores**
Sources: INF-060, INF-068, RCT-215, PRE-069
Scenario: `if (probe.armed.value) button(onClick→n++){...} else button {...}` (same position);
click → disarm+render → click → rearm+render → click.
Assert: n goes 1 → 1 → 2. No stale event id fires while disarmed.

**KSND-052 · dispatch that removes the target subtree completes; stale node is inert**
Sources: RCT-222, INF-077, INF-154
Scenario: clicking a button flips state so the re-render removes that button entirely
(replaced by text). Keep a reference to the detached element; dispatch a second click on it.
Assert: first dispatch applies (text shows new state), no exception; second dispatch on the
detached element is a no-op (handler count unchanged) — detached nodes' `__kinetica` must not
reach a live runtime (KNT-0011 bookkeeping).

**KSND-053 · dispose() removes all root listeners; no dispatch after dispose**
Sources: RCT-205, INF-060, SOL-086
Scenario: install listener tally BEFORE mount; mount app (records N adds on root); click works;
`app.dispose()`; dispatch click on the (still-attached) former child element.
Assert: `tally.removes == tally.adds` for the root's delegated set after dispose; handler count
unchanged by the post-dispose click.

**KSND-054 · dispose + remount on the same root dispatches exactly once per click**
Sources: KNT-0014, PRE-069 (dedupe angle)
Scenario: mount → dispose → mount again (fresh probe) → single click.
Assert: new probe's count == 1 (no double dispatch from a leaked first-mount listener);
old probe's count == 0.

**KSND-055 · two apps on sibling roots are isolated**
Sources: RCT-220, INF-069
Scenario: two roots under `document.body`, two `mountKineticaApp` instances with separate
probes; click a button in app A, then in app B.
Assert: A's count 1/B 0 after first click; 1/1 after second. Dispose A; click B again → B==2.

**KSND-056 · input dispatch carries the live element value**
Sources: VUE-160, INF-071, RCT-207 (contrast)
Scenario: `textInput(value = probe.text.value, onInput = event<String>{ probe.log += it })`;
set `input.value = "abc"` directly, dispatch `testDomEvent("input")` on it.
Assert: log == ["abc"] (payload read from the element at dispatch time, not from the prop);
NOTE the Kinetica contract difference vs React: there is NO tracked-value dedupe — a second
identical input event dispatches again (log == ["abc","abc"]). Lock that in.

**KSND-057 · keydown: Enter submits with preventDefault; other keys are ignored**
Sources: infra gap (Enter-submit), RCT-216 (adapted)
Scenario: `textInput(..., onSubmit = event { submits++ })`; dispatch
`testDomEvent("keydown", key = "Enter")` → then `testDomEvent("keydown", key = "a")`.
Assert: submits==1 after Enter and the event object has `preventDefaultCalled == true`;
after "a": submits still 1 and `preventDefaultCalled == false`.

**KSND-058 · template event holes stay row-correct after keyed reorder**
Sources: KNT-0006, KNT-0010, KNT-0014, INF-076, SVL-028
Scenario: each row (template-eligible single-shape row: `button(onClick = event { probe.hits +=
key }) { text(key) }`) over `[a,b,c]`; click row b (hits=[b]); reorder to `[c,a,b]` +
`app.render()`; click the element NOW at index 0 (which is c's moved element) and the element
that was b's (moved to index 2).
Assert: hits == [b, c, b] — each physical element still dispatches ITS OWN row's typed event
after the move (shared event table remap correctness).

---

# B06 — Controlled input snap-back (P0)

**File:** `kinetica-browser/test@js/BrowserControlledInputTest.kt`
**Pattern:** DSL components + probes; drift = assign `input.asDynamic().value = "..."` directly
(user typing emulation); dispatch `testDomEvent("input")` for tracked input. Re-render either
via dispatch (handler path) or store write + `app.render()` (unrelated-update path).
The contract under test is BrowserKineticaApp.kt:422-429 (every committed render syncs value).

**KSND-059 · drift + unrelated re-render snaps back to the cell value**
Sources: RCT-101, INF-147, PRE-082
Scenario: `textInput(value = "lion")` (constant) + sibling counter; drift value to "giraffe"
(no input event); click the sibling counter button (triggers commit).
Assert: `input.value == "lion"` after the commit; counter updated (render really happened).

**KSND-060 · input event writes the cell; DOM equals cell after commit**
Sources: VUE-160, SVL-053
Scenario: `var text by state {""}`; `textInput(value = text, onInput = event<String>{ text = it })`;
set value "abc" + dispatch input.
Assert: rendered mirror text shows "abc"; `input.value == "abc"` (round trip, no clobber).

**KSND-061 · handler that rejects the input restores the previous value on commit**
Sources: RCT-101, RCT-104 (adapted), INF-147
Scenario: onInput handler ignores payloads longer than 3 chars (doesn't write the cell) but
writes a `attempts++` cell so a render still commits; drift "abcdef" + input event.
Assert: `input.value` snapped back to the previous cell value; attempts==1.
Pitfall: if the handler writes NO cell at all, certify behavior too: dispatchAndRender renders
unconditionally (BrowserKineticaApp always calls render after dispatch) → still snaps back.

**KSND-062 · reverting to a previously-rendered value still dispatches (no tracked-value dedupe)**
Sources: RCT-116, RCT-207, RCT-210 (contrast — Kinetica dispatches every input event)
Scenario: value "" → user types "a" (input event, cell="a") → user clears back to "" (input event).
Assert: onInput log == ["a", ""] — both fired; final `input.value == ""`.

**KSND-063 · "" ↔ "0" transitions**
Sources: RCT-110, PRE-075
Scenario: cell "" → set cell "0" + render → set cell "" + render.
Assert: `input.value` is "" → "0" → "" (falsy string still applied; empty string clears).

**KSND-064 · controlled checkbox reverts when the handler doesn't change state, keeps when it does**
Sources: INF-074, INF-148, RCT-208
Scenario A: `checkbox(checked = false, onToggle = event { toggles++ })` (state never changes);
set `input.checked = true` (browser pre-toggle emulation) + dispatch `change`.
Assert: after commit `input.checked == false` (reverted), toggles == 1.
Scenario B: handler writes `checked = !checked` → same drift+dispatch → `input.checked == true`.

**KSND-065 · shrinking a controlled checkbox list applies fresh checked to survivors**
Sources: INF-153
Scenario: 3 checkboxes all checked=false via each; drift first's `checked = true`; one update
shrinks the list to 2 (all still false) + render.
Assert: both remaining inputs `.checked == false` regardless of which physical node was reused.

**KSND-066 · programmatic cell write updates the input; sibling prop patch never clobbers value**
Sources: SVL-053, RCT-119 (adapted)
Scenario: cell "1"; drift to "1x" (no event); update BOTH the value cell (→"2") and the
placeholder cell in one commit.
Assert: `input.value == "2"`, `placeholder` updated — value applied last / not overwritten by
the placeholder patch.

**KSND-067 · two inputs bound to one cell stay in sync**
Sources: RCT-103 (adapted), SVL-099 (adapted)
Scenario: two `textInput`s share `var text by state {""}` (both value=text, onInput writes it);
type "hi" into input#1 (drift + input event).
Assert: after commit BOTH inputs' `.value == "hi"`; then type "yo" into input#2 → both "yo".

---

# B07 — Error boundary semantics (P0, JVM+JS)

**File:** `kinetica-test/test/ErrorBoundarySoundnessTest.kt`
**Pattern:** `KineticaTest.render { App(probe) }` (synchronous cases) — follow
BoundaryRetryEventTest for boundary drive-through. Probes: `class BoundaryProbe { val fail =
store(false); var contentInits = 0; var fallbackInits = 0; val log = mutableListOf<String>() }`.
Throwing content = component reading `probe.fail.value` and `error("boom")` when true.
Trigger updates via `root.click(hasTestTag(...))` on buttons that flip stores, or store write +
`awaitIdle()`. Retry: fallback renders `button(onClick = event { retry.retry() }, testTag =
"retry")`. Keep all cases synchronous except KSND-077 (uses awaitIdle; keep the effect finite).
Only NEW scenarios here — the resource/retry/unit-vs-typed collision regression is already in
BoundaryRetryEventTest.

**KSND-068 · throw during initial render → fallback; outside-boundary siblings intact**
Sources: SVL-100, SOL-103, PRE-147, RCT-420
Scenario: `text("before"); errorBoundary(fallback = {e,_,_ -> text("err:${e.message}")}) {
Thrower() }; text("after")` with fail=true from the start.
Assert: tree shows before / err:boom / after; render does not throw to the test.

**KSND-069 · throw on update replaces content with fallback carrying the error**
Sources: SVL-101, INF-129, RCT-418
Scenario: fail=false renders content (with a state cell bumped to 2) → set fail=true → render.
Assert: fallback rendered with message; content nodes gone; journal shows a committed render
(no half-committed tree).

**KSND-070 · retry after the condition is fixed restores content with FRESH state**
Sources: SOL-104, SVL-102, PRE-155
Scenario: fail=true (fallback) → set fail=false → click retry.
Assert: content back; `contentInits` incremented (fresh slots — state from the pre-error content
must NOT resurrect); fallback gone.

**KSND-071 · retry while still failing lands back in fallback, stably**
Sources: SVL-107
Scenario: fail=true → click retry ×2 (condition still true).
Assert: fallback visible after each retry; `probe.log` shows one capture per retry; no growth in
tree size / no duplicated fallback nodes; runtime still serviceable (flip fail=false + retry →
content).

**KSND-072 · nested boundaries: inner catches, outer content untouched**
Sources: SVL-102, SOL-101, RCT-423
Scenario: outer boundary { text("outer-ok"); inner boundary { Thrower } }; fail=true on update.
Assert: inner fallback shown; "outer-ok" text node still present; outer fallback never rendered
(outer `fallbackInits == 0`).

**KSND-073 · fallback that itself throws escalates to the outer boundary**
Sources: SOL-105, SVL-106, PRE-150
Scenario: inner boundary's fallback throws unconditionally; content throws on update.
Assert: outer fallback rendered; probe log order is [inner-captured, outer-captured];
inner's broken fallback leaves no nodes behind.

**KSND-074 · fallback/content slot isolation across error↔retry cycles**
Sources: RCT-418, memory:boundary-slot-collision (extends the fixed KNT bug to state slots)
Scenario: content has `var c by state { contentInits++; 0 }` + typed event; fallback has
`var f by state { fallbackInits++; 100 }` + its own typed event and a retry button. Cycle:
content (bump c→2) → fail → fallback (bump f→103) → fix+retry → content → fail again → fallback.
Assert: c and f never bleed into each other (content shows 0 after retry per KSND-070; fallback
shows 100 on re-entry — fresh, not 103, unless the framework retains fallback state: certify
whichever and lock it); events dispatched in one mode never invoke the other mode's handler.

**KSND-075 · per-row boundaries in each: one row fails, siblings keep state; failure follows key**
Sources: PRE-157, INF-129, SVL-105
Scenario: `each([a,b,c])` rows each wrap content in their own errorBoundary; row b's content
throws when `failKey == "b"`; set failKey=b → reorder to `[c,b,a]`.
Assert: only b's fallback rendered; a/c keep their per-row state; after reorder the fallback
travels WITH key b (row order c,b(fallback),a) — boundary state is keyed to the row (KNT-0010
frame-era keyed certification interplay).

**KSND-076 · throw inside an event handler leaves the runtime usable**
Sources: RCT-421, SOL-009, INF-128
Scenario: button whose `event { error("handler-boom") }`; click it (wrap in
runCatching/assertFails as appropriate to the harness dispatch surface); then click a healthy
sibling counter button.
Assert: certify the propagation surface (dispatch throws OR boundary captures — lock in current
behavior); the healthy button still works and commits (counter 1); tree not corrupted.

**KSND-077 · throw in launchEffect under a boundary: tree not corrupted, updates continue**
Sources: SVL-103, RCT-422, PRE-113
Scenario: boundary content has `launchEffect { if (probe.effectFails) error("fx") }` plus normal
text; render, `awaitIdle()` (catching if it rethrows), then flip an unrelated store and awaitIdle.
Assert: certify current routing (boundary fallback OR error surfaced to awaitIdle) — whichever,
the subsequent unrelated update renders correctly and dispose() completes. Pitfall: finite
effect only; never a retry loop inside the effect (awaitIdle must terminate).

**KSND-078 · error with NO boundary: throws to caller; next render works**
Sources: SVL-109, SOL-100, INF-130
Scenario: root content throws when fail=true. `assertFails { render/awaitIdle with fail=true }`
(initial-render variant: assertFails on KineticaTest.render itself); then fail=false, render a
fresh root.
Assert: exception message intact; fresh render succeeds — no wedged global state (fresh
KineticaTest.render in the same test must work).

**KSND-079 · errorBoundary + loadingBoundary nesting with a failing resource**
Sources: SOL-106, SOL-112, extends BoundaryRetryEventTest
Scenario: errorBoundary { loadingBoundary(fallback="loading") { resource(saltedKey){ if fail
throw else "data" }.read() } }; first load fails → fix → retry.
Assert: error fallback shown (not a stuck "loading"); after retry content shows "data"; loading
fallback appeared only during the reload window (journal inspection optional).
Pitfall: SALT the resource key with the test name; `renderSuspend`/`runTest` + awaitIdle;
dispose in finally (async-on-Node rules).

---

# B08 — JS-parity reactivity invariants (P1, JVM+JS)

**File:** `kinetica-runtime/test/ReactivityParityTest.kt`
**Pattern:** single-threaded, deterministic twins of the test@jvm scheduler suite — runs on BOTH
platforms (the point is JS parity: frame/cell storage must behave identically on JS —
CI comment). Use raw cells/derived/store + explicit listeners (same internal APIs the @jvm
tests use — this module's test source set has `internal` access). NO threads, NO coroutines.
Probe pattern: recompute counters incremented inside derived bodies; listener logs as lists.
Do NOT copy the JVM tests' threading parts (contention, TOCTOU windows) — only the
graph-shape invariants.

**KSND-080 · diamond glitch-freedom: observers only ever see consistent pairs**
Sources: SVL-001, SOL-020, VUE-029 (twin of DerivedCellDiamondNoGlitchTest)
Scenario: `a` → `b=a*2`, `c=a+1` → `d="$b:$c"`; listener on d logs values; write a=1, a=2.
Assert: log is exactly ["2:2","4:3"] — never a mixed generation; d recomputed once per write.

**KSND-081 · one recompute per wave; every listener notified exactly once**
Sources: SVL-006, SOL-021, VUE-027, VUE-033 (twin of DiamondMultiObserverTest)
Scenario: two sources → 2 mid deriveds → 1 join derived; 3 listeners (one per derived); batch
write both sources (single wave if the API batches; else two waves — mirror the JVM test's
write pattern exactly).
Assert: per-wave recompute counters == 1 for each derived; each listener's call count == waves.

**KSND-082 · deep chain propagates once, in one pass**
Sources: VUE-013, SOL-029 (twin of SchedulerAdversarial_deepchain)
Scenario: chain of 30 deriveds each `prev+1` over source 0; write source=10.
Assert: leaf == 40; every node's recompute counter == 2 (init + one wave); listener fired once.

**KSND-083 · dynamic dep swap drops the stale edge**
Sources: SOL-004, SOL-024, VUE-006, SVL-009 (twin of _dynamicdeps)
Scenario: `d = if (flag) left else right`; observe d; flip flag; then write the ABANDONED branch.
Assert: writing the abandoned cell triggers NO recompute and NO listener call (counters flat);
writing the active one does; flip back re-arms the other.

**KSND-084 · unchanged intermediate prunes downstream**
Sources: SVL-019a, SOL-022, VUE-035 (twin of PrunesUnchangedIntermediate)
Scenario: `mid = src / 2` (integer), `leaf = mid * 10`, listener on leaf; src 0→1 (mid still 0).
Assert: mid recomputes, leaf does NOT, listener silent; src 1→2 → everything fires once.

**KSND-085 · disconnected derived reconnects with the CURRENT value**
Sources: SVL-014, SOL-025, VUE-041
Scenario: `d = derived(c)`; observer reads d only while `gate` is true; gate off → write c twice
→ gate on.
Assert: on reconnection the observer immediately sees the latest c-derived value (no stale
cache, no missed update); subsequent writes propagate normally.

**KSND-086 · unobserved reads don't leak; disposal releases sources**
Sources: SVL-115, SVL-003, SVL-008, VUE-021, SOL-003 (twin of UnobservedDerivedReadDoesNotLeak +
KineticaRuntimeDisposeReleasesSubscriptions)
Scenario: read a derived outside any observer N times; then observe+dispose the observer.
Assert: source's subscriber/listener count (via whatever internal accessor the JVM twins use)
is 0 after unobserved reads and back to 0 after disposal; a post-disposal write calls nothing;
a direct read still returns the fresh value.

**KSND-087 · throwing recompute doesn't kill the derived; listener exceptions are isolated**
Sources: SOL-009, VUE-058 (twin of DerivedCellSurvivesThrowingRecompute + CellListenerExceptionIsolation)
Scenario: derived throws when src==13; drive src 1→13 (read/propagation error surfaced) →2;
separately: two listeners on one cell, first throws.
Assert: after recovery the derived returns src-based value and dependents update; the second
listener still receives every notification despite the first throwing.

**KSND-088 · observe-before-read activates delivery**
Sources: SVL-019, SOL-028 (twin of DerivedCellObserveBeforeReadActivates)
Scenario: subscribe a listener to a derived that has NEVER been read; then write the source.
Assert: listener fires with the computed value (subscription alone activates the node).

**KSND-089 · same-value writes don't notify (equality policy incl. NaN)**
Sources: VUE-007, SOL-002, PRE-111
Scenario: cell 5 → write 5; data-class cell → write equal copy; Double.NaN cell → write NaN.
Assert: listener counts flat for equal writes; certify the NaN policy (equals-based ⇒ NaN==NaN
is false in Kotlin equality? `Double.NaN == Double.NaN` is false but `equals` is true — assert
whichever the cell's equality policy implements and lock it in; cross-check with
RuntimeSmokeCoreTest's equality-policy cases to avoid duplication — this case only adds the
NaN corner and the derived-chain silence).

---

# B09 — Effect cleanup & disposal ordering (P1, JVM+JS)

**File:** `kinetica-test/test/EffectCleanupOrderingTest.kt`
**Pattern:** `KineticaTest.render`/`renderSuspend` + `awaitIdle()`; probes with ordered
`log: MutableList<String>` passed as parameters. Effects must be FINITE (awaitIdle drains).
Follow KineticaTestSmokeTest for effect/dispose plumbing. Async cases obey the Node-runner
rules (per-test probes, salted keys, dispose in finally).

**KSND-090 · launchEffect strict init/cancel pairing across branch toggles**
Sources: SVL-110, PRE-108, PRE-109, SOL-012, VUE-068
Scenario: `if (show) Child()` where Child's `launchEffect { probe.log += "init"; try { awaitCancellation-ish long suspend } finally { probe.log += "cancel" } }`
— use the pattern KineticaTestSmokeTest uses for long-lived dispose effects; toggle show
on→off→on→off via clicks with awaitIdle between.
Assert: log is exactly [init, cancel, init, cancel] — one pair per cycle, no doubles, no leaks.

**KSND-091 · watch stops firing after its owning branch is disposed**
Sources: VUE-067, VUE-072, SVL-087, SOL-004
Scenario: Child has `watch(sharedStoreCell) { probe.log += it }`; toggle Child off; write the
store twice; awaitIdle.
Assert: no new log entries after removal; re-adding Child re-arms (fresh watch fires on next
write, not retroactively for missed ones — certify).

**KSND-092 · removing one keyed row cancels only that row's effect**
Sources: RCT-414, VUE-131
Scenario: each rows `[A,B]` each with a paired init/cancel effect; remove A.
Assert: log gains exactly [cancel A]; B's effect NOT cancelled (no cancel B); B still reacts to
its cell afterwards.

**KSND-093 · reorder then dispose fires every row's cleanup**
Sources: RCT-415
Scenario: rows [A,B] with effects → reorder to [B,A] (awaitIdle; assert NO effect churn — zero
new log entries) → `root.dispose()`.
Assert: after dispose, log contains cancel for BOTH keys exactly once each — a moved row must
not "forget" its cleanup.

**KSND-094 · disposal order across nesting is deterministic — certify it**
Sources: RCT-401, RCT-407, RCT-408, SVL-086, PRE-101, INF-102, VUE-186
Scenario: Parent(effect P) > Child(effect C) > Grandchild(effect G), each logging its cancel;
`root.dispose()`.
Assert: the cancel order is one FIXED sequence — run once, read the actual order, then assert it
exactly (document in the test KDoc that Kinetica certifies parent-first or child-first, matching
what the frame kernel does; the surveys disagree with each other — Inferno/React/Preact
parent-first, Svelte child-first — Kinetica must pick and pin).

**KSND-095 · exitGroup abandonment cleans up**
Sources: infra gap (exitGroup abandonment), RuntimeSmokeSlots (adjacent, not duplicated)
Scenario: `exitGroup(key, visible)` holding a subtree with an effect; set visible=false (exit
begins, subtree retained) → dispose the root BEFORE the exit completes (no advanceTimeBy).
Assert: the retained subtree's effect cancel fires exactly once on dispose; no journal entries
after dispose; a second dispose() is a no-op.

**KSND-096 · layoutEffect before launchEffect relative to commit**
Sources: RCT-411, RCT-412, PRE-116, SVL-081
Scenario: component with `layoutEffect { log += "layout:$count" }` and
`launchEffect { log += "passive:$count" }` reading a state cell; mount, then bump count once.
Assert: per commit, "layout" precedes "passive"; both observe the committed value (0 then 1);
exactly one pair per commit (no re-run storm).

**KSND-097 · effect writing a cell settles with exactly one extra committed render**
Sources: PRE-106, VUE-206, SVL-011
Scenario: `launchEffect { if (copy != source) copy = source }`; write source via click;
awaitIdle.
Assert: journal RenderCommitted count grows by exactly 2 for the click (dispatch render +
effect-invalidation render); DOM shows the converged pair; no further renders on a second
awaitIdle.

**KSND-098 · pending effect of a removed component never runs**
Sources: PRE-107, INF-123, RCT-519, VUE-072
Scenario: Child registers a launchEffect that logs; in the SAME dispatch that would first
commit Child, toggle it off again before awaitIdle (click sets show=true then immediately a
second click sets show=false before awaitIdle; or one handler that flips show twice — pick the
sharpest the harness allows).
Assert: either the effect never ran, or it ran and was cancelled before its log line — certify:
log contains no orphaned "init" without "cancel"; final tree has no Child content.

**KSND-099 · watch batching and declaration order**
Sources: VUE-066, VUE-069, VUE-061, SVL-019d, SVL-083
Scenario: two watches declared A-then-B over the same cell (A also reads a derived of it);
one click writes the cell twice (n+=1; n+=1).
Assert: per flush each watch fired exactly once, with the FINAL value, in declaration order
[A, B]; log across two clicks is [A,B,A,B].

---

# B10 — Mount/unmount residue & detach (P1)

**File:** `kinetica-browser/test@js/BrowserMountResidueTest.kt`
**Pattern:** raw-emit or tiny DSL apps; private listener tally (same snippet as B05 — duplicate
it here, batches are independent); direct `_children`/childNodes inspection.

**KSND-100 · mount/dispose ×50 leaves zero child nodes and balanced listeners**
Sources: SVL-113
Scenario: install tally; loop 50×: `mountKineticaApp(root){ SmallApp(probe) }` → `dispose()`.
Assert: after the loop `root.childNodes.length == 0` (no leaked anchors/text/comment
placeholders) and `tally.adds == tally.removes`.

**KSND-101 · dispose removes exactly the delegated listener set added at mount**
Sources: SOL-086, KNT-0014
Scenario: tally before a single mount; snapshot adds (= number of DelegatedEventTypes);
dispose.
Assert: removes == adds; a click dispatched after dispose reaches no handler (probe flat).

**KSND-102 · externally-cleared root tolerated by dispose**
Sources: SOL-092
Scenario: mount; `root.asDynamic().textContent = ""` (external wipe); `app.dispose()`.
Assert: no exception; root empty; a second dispose() also safe.

**KSND-103 · remount after dispose is a fresh instance**
Sources: RCT-417, SOL-093
Scenario: mount app with state bumped to 3; capture first child element; dispose; mount the
same component fn with a FRESH probe.
Assert: new first child is a different element (assertNotSame); state shows 0 (`inits`
incremented) — nothing resurrected from the first mount.

**KSND-104 · unmounted keyed subtree's elements lose their __kinetica bookkeeping**
Sources: KNT-0011, SVL-115 (analog), INF-113 (analog)
Scenario: keyed rows [a,b,c]; capture b's element; update to [a,c]; render.
Assert: detached b element has `asDynamic().__kinetica == null` (or undefined) — detach
bookkeeping cleared so the runtime isn't retained; survivors still carry their bookkeeping.

**KSND-105 · bulk-clear then dispose**
Sources: KNT-0012, SOL-090
Scenario: 20 keyed rows → items=[] (bulk path) → render → dispose.
Assert: no exception at any step; root empty; listener tally balanced.

**KSND-106 · click on a detached element after removal is inert**
Sources: RCT-205, INF-060
Scenario: keyed row with a button; capture the element; remove its row; dispatch click on the
captured element directly (its parentNode chain is dead so it can't bubble to the root — ALSO
dispatch with `target` forged onto a still-attached ancestor path if TestDom permits; the
assertable core: probe count unchanged, no crash).
Assert: handler count unchanged; no exception.

---

# B11 — Text & child-shape transitions (P2)

**File:** `kinetica-browser/test@js/BrowserChildShapeTest.kt`
**Pattern:** DSL components + stores; capture text nodes via `parent.childNodes.item(i)` and
compare identity with `assertSame` on `unsafeCast<Any>()` refs (text nodes are not Elements).

**KSND-107 · text updates mutate the SAME text node in place**
Sources: INF-034, RCT-015, VUE-113
Scenario: `text(probe.msg.value)`; msg "one" → "two" → "three".
Assert: `childNodes.item(0)` identical across all three renders; nodeValue tracks the cell.

**KSND-108 · text ↔ host switch at the same position, both directions, siblings untouched**
Sources: INF-034, INF-171, PRE-044, RCT-002, VUE-089
Scenario: `text("a"); if (flag) text("mid") else host("div"){text("mid")}; text("z")` — toggle
flag ×3.
Assert: html exact each step; the flanking text nodes keep identity through every toggle.

**KSND-109 · leading/trailing text beside a host toggling present/absent leaves no orphans**
Sources: INF-172, RCT-016, PRE-127
Scenario: `[textA?, host, textB?]` — toggle textA off, textB off, textB on, textA on
(4 independent steps).
Assert: childNodes count exact at each step (no duplicate/orphan text nodes); the host element
keeps identity throughout.

**KSND-110 · adjacent text children remain separate nodes, independently patched**
Sources: INF-170, RCT-015
Scenario: `text(a.value); text(b.value)` — update only b, then only a.
Assert: 2 child nodes always; updating b leaves a's node untouched (identity + nodeValue);
textContent is the concatenation.

**KSND-111 · empty-string and "0" text**
Sources: INF-035, PRE-036, PRE-038, RCT-017
Scenario: `text("")` renders; then cell "" → "0" → "".
Assert: certify: empty string yields an empty text node or no node (lock in current TestDom-
observable behavior — assert childNodes.length constant across ""→"0"→"" OR document the
transition); "0" is VISIBLE text "0", never dropped as falsy.

**KSND-112 · branch switch between different host tags replaces the element**
Sources: RCT-002, PRE-037, VUE-109
Scenario: `if (flag) button(onClick=...){text("x")} else host("p"){text("x")}` — toggle.
Assert: element replaced (assertNotSame, tagName changes); the old tag's attributes
(e.g. `disabled`, event bookkeeping) do not leak onto the new element; toggle back yields a
fresh button that dispatches correctly.

**KSND-113 · sibling fragments grow at their own boundary**
Sources: INF-177, PRE-120, PRE-126
Scenario: two child components A and B each emitting `fragment { each(itemsX) ... }` under one
parent; grow A from 2→4 rows while B shrinks 2→1 in one commit.
Assert: DOM order is [A-rows..., B-rows...] — A's new rows inserted before B's first node, never
appended at the parent end; B's survivor keeps identity.

**KSND-114 · independent null-toggles between fixed siblings**
Sources: INF-029, RCT-013, PRE-055
Scenario: `p1; if(s1) span("abc"); p2; if(s2) span("def"); p3` — toggle s1 off, s2 off, s2 on,
s1 on.
Assert: DOM matches every combination; p1/p2/p3 keep identity through all four transitions;
re-shown spans are fresh elements at the right slots.

---

# B12 — Scheduler / batch ordering (P1, JVM+JS)

**File:** `kinetica-test/test/BatchOrderingTest.kt`
**Pattern:** `KineticaTest.render` + journal assertions
(`root.journal().count { it.kind == JournalKind.RenderCommitted }`) + derived-recompute probe
counters. Synchronous except where noted.

**KSND-115 · N writes in one event handler commit exactly one render**
Sources: RCT-515, SOL-007, VUE-024, INF-119
Scenario: handler does `a = 1; a = 2; b = 9` (three writes, two cells); click.
Assert: RenderCommitted count +1; DOM shows a=2,b=9; a derived over (a,b) recomputed once for
the wave (probe counter).

**KSND-116 · write-then-revert inside one handler leaves DOM unchanged**
Sources: SVL-005, SOL-007, VUE-062
Scenario: handler: `n = 1; n = 0` (back to original); click.
Assert: DOM identical before/after; observers of n's derived saw NO intermediate value (derived
listener log empty for the wave); certify committed-render count (0 or 1 — dispatch always
renders in this harness: lock in the actual count and assert the TREE is value-identical).

**KSND-117 · two cells feeding one derived: one recompute per wave**
Sources: SVL-006, VUE-027, SOL-021
Scenario: `sum = derived { a + b }` rendered as text; handler writes both a and b; click twice.
Assert: recompute counter +1 per click (not +2); text correct each time.

**KSND-118 · write → read derived → write again inside one handler**
Sources: SVL-011, SVL-019b, VUE-036
Scenario: `z = derived { x * y }` (x=1,y=1); handler: `x = 0; val mid = z; y = 0` (mid read must
be fresh: 0); render text shows z.
Assert: handler observed mid == 0 (fresh read after write); final DOM shows 0; the final commit
was not swallowed by the mid-read (SVL derived-write-read-write regression).

**KSND-119 · back-to-back dispatches each commit once, in order**
Sources: RCT-516, RCT-524, INF-126
Scenario: two buttons writing different cells; `click(A); click(B)` without awaitIdle between.
Assert: RenderCommitted +2 (one per dispatch, per the harness's render-per-dispatch contract);
after both, DOM reflects both writes; journal order shows A's commit before B's.

**KSND-120 · scope-free store write renders with cause "cell write"**
Sources: RCT-523, KineticaTestSmoke (extension — not a duplicate: asserts cause + coalescing)
Scenario: component reads `store` cell; from the test body write the store 3× in a row; awaitIdle.
Assert: journal's last RenderCommitted has `attributes["cause"] == "cell write"`; the 3 writes
coalesced into ≤2 commits (certify the exact count once, then pin it); DOM shows the last value.

**KSND-121 · convergent self-write effect reaches its fixpoint within awaitIdle**
Sources: SVL-089, VUE-009, SOL-008
Scenario: `launchEffect { if (power != 10) power += 1 }`; set power=1 via click; awaitIdle.
Assert: DOM shows 10; renders bounded (RenderCommitted delta ≤ 10); a second awaitIdle adds 0.
Pitfall: NEVER write the divergent variant here — with no loop guard awaitIdle would hang;
divergence detection is in Deferred.

**KSND-122 · watch writing a DIFFERENT cell settles without re-triggering itself**
Sources: VUE-059, VUE-080, VUE-062
Scenario: `watch(input) { output = normalize(it) }` where normalize is idempotent
(e.g. coerceIn(0,5)); click writes input=9.
Assert: watch fired once for the wave; output==5 rendered; writing input=9 again (equal value)
fires nothing (equality gate); no ping-pong renders (journal delta pinned).

---

# B13 — Template multi-instance identity (P1)

**File:** `kinetica-browser/test@js/BrowserTemplateInstanceTest.kt`
**Pattern:** raw `templateNode(definition, values, key)` emission — copy the
`keyedTemplateDefinition(...)` builder + `firstElement` from BrowserTemplateKeyAttributeTest
into this file as private helpers (adjust ids). For event-hole cases copy the hole-definition
pattern from BrowserTemplateEventDispatchTest. Fresh definition `id` per test (salt with test
name) so template caches never collide across tests.

**KSND-123 · one definition instantiated ×3 = three independent DOM subtrees**
Sources: INF-134, INF-043, PRE-042
Scenario: emit three `templateNode(def, values=["A"],["B"],["C"])` under one parent.
Assert: 3 elements, pairwise distinct by reference; texts A,B,C; mutating instance 1's DOM
directly does not appear in instances 2/3 (clone isolation).

**KSND-124 · per-instance text holes patch independently**
Sources: INF-134, SOL-083
Scenario: instances values A,B,C → re-render with values A,B2,C.
Assert: only instance 2's text changed; instances 1/3 keep node identity AND text; instance 2
keeps element identity (hole patch, not re-clone).

**KSND-125 · per-instance event holes dispatch to their own event ids**
Sources: KNT-0006, INF-075
Scenario: 3 instances with click holes bound to distinct runtime event ids (register 3 events;
follow BrowserTemplateEventDispatchTest's registration); click instance 2's element.
Assert: only probe 2 fires; counts [0,1,0]; click 1 and 3 → [1,1,1].

**KSND-126 · template instances as keyed rows: reorder preserves identity and hole bindings**
Sources: KNT-0007, KNT-0010, RCT-004, SVL-024
Scenario: keyed template rows a,b,c (keys on templateNode) → reorder [c,a,b] → patch b's value.
Assert: all three elements moved by identity (assertSame per key); `data-kinetica-key`
attributes correct after the move; the value patch lands on b's element at its NEW position.

**KSND-127 · clone attribute mutation doesn't leak into later clones**
Sources: INF-134, PRE-081
Scenario: mount instance 1; `setAttribute("data-dirty","1")` directly on its element; then
render an update that ADDS instance 2 from the same definition.
Assert: instance 2 has no `data-dirty` attribute (cloned from the pristine template, not from
the live instance); instance 1 keeps its out-of-band attribute (framework didn't wipe it —
certify; if the patcher normalizes attributes, pin that instead).

**KSND-128 · unmounting one instance clears only ITS event bindings**
Sources: KNT-0006 (multi-instance angle; single-unmount already covered)
Scenario: 3 clickable instances → remove instance 2 (re-render with 2 instances) → click the
survivors, then dispatch a click on the detached element.
Assert: survivors dispatch normally (counts grow); detached instance's click is inert; its
element's `__kinetica` bookkeeping cleared (cross-check with KSND-104's assertion style).

---

# B14 — Host prop/attr patching edges (P2)

**File:** `kinetica-browser/test@js/BrowserHostPropPatchTest.kt`
**Pattern:** raw-emit `host("box", props = mapOf(...))` nodes + `app.render()`; for KSND-133 a
private setAttribute counter (same monkeypatch technique as B02, patching
`Element.prototype.setAttribute`/`removeAttribute`, recording only when `this.isConnected`).

**KSND-129 · arbitrary host prop add / update / remove round-trip**
Sources: RCT-307, RCT-314, INF-140, PRE-074, VUE-148
Scenario: `props = {"title":"x"}` → `{"title":"y"}` → `{}` → `{"title":"z"}`.
Assert: getAttribute("title") is "x" → "y" → null (attribute REMOVED, not "" or "null") → "z";
element identity constant throughout.

**KSND-130 · data-* / aria-* props pass through and patch**
Sources: RCT-314, PRE-096
Scenario: `{"data-foo":"bar","aria-checked":"false"}` → change data-foo, drop aria-checked.
Assert: values verbatim (string "false" preserved — props are strings, no boolean coercion);
dropped attr removed; unrelated attr untouched.

**KSND-131 · enabled toggling sets/removes disabled on button — host and raw paths**
Sources: RCT-309, PRE-077, INF-143 (contrast: Kinetica's enabled is its own contract)
Scenario: button node `props["enabled"]="false"` → "true" → absent.
Assert: `disabled` attribute present ("") → removed → removed (absent enabled defaults to
enabled per BrowserKineticaApp:170); click dispatch gated accordingly (one dispatch check).

**KSND-132 · semantics patch: testTag/role/label update across renders**
Sources: extends BrowserMappingTest (initial-render only there)
Scenario: host with `Semantics(testTag="a", role=Role.Button, label="L1")` → change all three →
drop label.
Assert: the mapped attributes (whatever BrowserMappingTest asserts for these — data-testtag/
role/aria-label naming per that file) update and the dropped one is removed.

**KSND-133 · unchanged props = zero attribute writes**
Sources: VUE-135, SVL-048, SVL-049, PRE-081
Scenario: host with 3 props; clear the setAttribute counter after mount; re-render with an
EQUAL props map.
Assert: 0 setAttribute/removeAttribute calls on connected elements (diff short-circuits);
then change ONE prop → exactly 1 setAttribute call.

**KSND-134 · unsafe props stay filtered when introduced by a PATCH**
Sources: extends BrowserMappingTest (mount-time filtering), RCT-322 (contrast)
Scenario: host `{"href":"https://ok"}` → patch to `{"href":"js:alert(1)"}` (use the exact
scheme strings BrowserMappingTest exercises) → patch to a `srcdoc` prop.
Assert: the unsafe value is NOT written (attribute removed or old value dropped — match
BrowserMappingTest's mount-time expectation); `event:`/`frame:` prefixed props never appear as
attributes after patches either.

**KSND-135 · template hole prop removal restores the skeleton default**
Sources: KNT-0010 (adjacent — key attr covered; this is a NON-key hole), SVL-049
Scenario: template with an attribute hole (value "big") patched to another value, then to the
hole's absent/default state.
Assert: attribute follows value→value→skeleton-default (or removal — pin what the hole
semantics dictate, mirroring BrowserTemplateKeyAttributeTest's fallback test but for a plain
attribute hole).

---

# Deferred / not implementable now

Each line: what + why + the infra that would unlock it. NOT in batches.

1. **Real focus & selection preservation across patches** (PRE-140..146, RCT-510..514, SVL-053)
   — TestDom has no focus()/selectionStart. Unlock: extend samples/browser-tests Playwright
   self-tests (focus+`setSelectionRange(2,5)`, assert activeElement identity + offsets after
   keyed insert/remove/reorder around the input), or teach TestDom focus/selection emulation.
2. **SVG / MathML namespaces** (INF-165..168, RCT-319..321, PRE-091..094, SVL-050, SOL-087,
   VUE-134/140) — kinetica-browser has zero `createElementNS`/namespace code (verified).
   Unlock: renderer namespace propagation (svg context flag, foreignObject switch-back) + DSL.
3. **select / option / radio / textarea controlled semantics** (RCT-112..115, RCT-121..125,
   INF-152, INF-155..161, VUE-168..170, SVL-051/052/054/055/058, PRE-095) — DSL has no such
   elements. Unlock: DSL + browser mapping for select/radio (+ TestDom option modeling).
4. **IME / composition sessions** (VUE-171) — needs a real browser input pipeline (Playwright).
5. **Style-object semantics** (RCT-301..306, INF-162..164, PRE-087..090, VUE-136..138, SVL-049)
   — no style API beyond flex mapping; props are plain strings. Unlock: style DSL + patcher.
6. **Capture phase, mouseenter/leave synthesis, non-bubbling emulation, scroll/hover/dblclick,
   once/passive modifiers** (RCT-201..203/206/217/218, PRE-070..072, SVL-061/066, VUE-154/159,
   INF-073) — delegation supports click/input/change/keydown-Enter only. Unlock: event-type
   expansion in DelegatedEventTypes + capture semantics decision.
7. **Infinite-update-loop guard** (SVL-010, SVL-088, SOL-030, SOL-031, VUE-009-guard, INF-120)
   — Kinetica has no depth guard; a divergent effect would hang awaitIdle/the Node runner.
   Unlock: scheduler depth limit + diagnostic; then port SVL-088 (throw once, stay usable).
8. **Client-side hydration path** (PRE-145, hydration internals of all surveys) —
   BrowserKineticaApp's hydrate path has no test@js harness. Unlock: server-HTML fixture +
   hydrate entrypoint callable under TestDom.
9. **contenteditable / external DOM mutation tolerance** (INF-132, PRE-083, SVL-057) — needs
   contenteditable semantics + a policy for externally-mutated managed text. Unlock: opt-out
   flag (Inferno's INF-202 contract) in the compiler/renderer.
10. **FLIP/animate geometry on keyed moves** (SVL-043) — needs layout metrics (real browser) +
    kinetica-motion browser integration.
11. **Form reset / defaultValue semantics** (RCT-107/108/111, SVL-058, PRE-078) — no
    defaultValue concept in the DSL; TestDom has no form.reset(). Unlock: DSL decision first.
12. **wasmJs / android / macosArm64 execution of the common batches** — targets build but never
    run in CI. Unlock: CI runners (wasmJs test task + Node wasm runtime).
13. **10k-iteration shuffle stress + perf guards** (PRE-033 full-size, VUE-043) — the Node
    single-process runner has no timeout isolation; KSND-012 covers a bounded 100-round fuzz.
    Unlock: a dedicated stress lane (separate script, not kotlin-test).

## Dropped as not applicable (do not revisit)

- VDOM implementation details: vnode flags/cloning, normalization, `$stable` slot flags,
  mergeProps/splitProps (SOL-130..136), lazy prop getters, JSON-vnode guard (PRE-062),
  children-helper flattening (SOL-019/074), HOC vnode.el bookkeeping (VUE-202).
- React-hooks/legacy-lifecycle-specific semantics: gDSFP/gSBU/cWRP ordering internals
  (RCT-401..406), setState-in-constructor error (INF-121), functional-updater merge (INF-125),
  forceUpdate semantics (INF-127, PRE-063), useImperativeHandle (RCT-509), callback-ref
  cleanup-return protocol (RCT-504..507).
- Suspense/transitions internals (SOL-118..124, SVL-034/035 outro transitions, VUE deferred),
  SuspenseList orchestration.
- Hydration/SSR internals (covered separately by RuntimeSmokeServerTest + Playwright flow).
- Portals (SOL-091 partial — no portal API in Kinetica).
- Vue emits/props casting/warnings (VUE-190..205), Vue deep-watch options (VUE-077/084/086 —
  cells are value-typed, no deep proxies), reactive-proxy semantics (VUE-004/005/045..050,
  SVL proxy cases), observable/external-source interop (SOL-034/035).
- Compiler contracts already gated by kinetica-compiler suites: keyed-list detection
  (INF-201 ≙ EachKeyedFlagTest), template eligibility (INF-203 ≙ KineticaIrTemplateCompileTest),
  event-name canonicalization (INF-204 — Kinetica events are ids, not names), children-attr
  precedence (INF-205), SVG attr mapping (INF-206 → deferred with SVG).
- Already-covered Kinetica ground (do NOT duplicate): derived/scheduler adversarial JVM suite
  (only the B08 parity twins are sanctioned), each memoization/keyed-flag certification,
  resource staleness, template text patch, toSafeHtml escaping, one-render-per-dispatch,
  dispose-blocks-revival, retry unit-vs-typed slot collision (BoundaryRetryEventTest),
  LIS plan shape (ListReconcileTest), nested keyed scratch reorders, data-kinetica-key
  emission/fallback, detach-before-bulk-skip (BrowserDetachBookkeepingTest scenarios).
