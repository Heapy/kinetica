# Agent guidance

## Invariant: FIR/IR policy must stay shared (kinetica-compiler)

The FIR checker (`KineticaFirExtension.kt`) must predict exactly what the IR
numbering pass (`KineticaIrFrames.kt`) does. Do not let the two phases drift:

- Every classification table both phases consult (`REGION_CONTENT_PARAMETERS`,
  `SINGLE_RUN_SCOPE_FUNCTIONS`, `MULTI_RUN_DSL_PARAMETERS`, the Kinetica-lambda
  predicate) lives only in `kinetica-compiler/src/KineticaFramePolicy.kt`.
  Never re-introduce private copies in either phase.
- Contract-based single-run verdicts flow FIR→IR through the per-compilation
  `SingleRunOracle` (built in `KineticaCompilerRegistrar.registerExtensions`,
  never a top-level object). A callee with no oracle entry must fall back to
  the same `KineticaFramePolicy` name lists on BOTH sides.
- Any construct IR declines to transform needs a matching FIR error or a
  located IR `ERROR` — never a silent `LOGGING`-only bail-out.

## Invariant: what IR frames, FIR must reject — exactly

`KineticaIrGenerationExtension.collectFramingTargetsIn` reaches every declaration
whose path from the file crosses NO function body: file, class, object,
enum-entry body, property accessors, constructor bodies, property/field
initializers, `init { }` blocks, and anonymous-object literals declared inside
those initializers. `KineticaLocalComponentDeclarationChecker` reports the exact
complement — "some `FirFunctionSymbol` is on the containment path" (local
function, lambda, property accessor, constructor). Widening one side without the
other reopens the compile-clean/crash-at-render hole this rule exists to close.

That pairing governs COMPONENT FRAMING only. Entry content (`runtime.render { … }`)
carries no `@UiComponent` declaration for FIR to reject, so the entry pass must
reach strictly further than the framing collection: it descends into nested
classes of every body it visits, and the collection yields each
`IrValueParameter.defaultValue` as its own entry body. Both are idempotent with
the framing collection — `wrappedContentLambdas` wraps a content literal once.

Two consequences to preserve:

- The `IrClass` scan over initializer expressions (`collectNestedClasses`) must
  stop at every `IrFunction` boundary — that stop is what makes the FRAMING
  complement exact. The entry pass deliberately does not stop there.
- A `@UiComponent`-typed lambda literal argument is ALWAYS wrapped into its own
  fresh region and NEVER descended inline, on every branch of
  `KineticaFrameTransformer.Walker.visitCall`. Wrapping on the fall-through
  branches only left the slot-DSL, event-DSL and hostEvent-fusion branches
  handing unwrapped content to the runtime.

## Invariant: what the GATED walker owns, FIR must gate the same way

`KineticaFrameTransformer.Walker` (component bodies, and every content lambda it
wraps) refuses to descend into multi-run and stored lambdas, while the
entry-point pass wraps content bottom-up ungated. Which of the two owns a
position is decided by the numbering ROOT — the OUTERMOST enclosing named
function — not by the innermost containment boundary.
`isInsideComponentNumberingRoot` is that test on the FIR side; do not replace it
with `classifyContainment`, which stops at the innermost boundary and therefore
misses nested content lambdas and local functions inside a component.

Positions no frame reaches at all are FIR rejections, never IR widenings: default
argument values of a `@UiComponent` function (evaluated by the `$default` stub
before `beginComponentFrame`), callable references to `@UiComponent` functions
(no call site to stage at), and `@UiComponent` disagreement across an override
chain (framing reads the override's annotation, staging reads the resolved
base's).

The gated walker can lexically reach reusable declarations nested under a
component root, but that does not make direct static ordinals there safe. A
local function, property accessor, constructor, or named local-class initializer
can run repeatedly, so direct ordinal consumers there classify as `OUTSIDE`.
An anonymous-object initializer evaluated directly at its expression site is
not reusable and remains owned by the outer frame, while its accessors and
functions are reusable boundaries. Compiler-wrapped content is different: its
fresh region uses documented call-site/invocation-position identity and must not
be rejected merely because its closure can escape.

The `firAndIrAgreeOn*` drift tests in
`kinetica-compiler/test/KineticaFirCheckerTest.kt` pin the shared tables;
extend them when adding region kinds or single-run hosts.
