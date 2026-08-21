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

Two consequences to preserve:

- The `IrClass` scan over initializer expressions must stop at every `IrFunction`
  boundary — that stop is what makes the complement exact.
- A `@UiComponent`-typed lambda literal argument is ALWAYS wrapped into its own
  fresh region and NEVER descended inline, on every branch of
  `KineticaFrameTransformer.Walker.visitCall`. Wrapping on the fall-through
  branches only left the slot-DSL, event-DSL and hostEvent-fusion branches
  handing unwrapped content to the runtime.

The `firAndIrAgreeOn*` drift tests in
`kinetica-compiler/test/KineticaFirCheckerTest.kt` pin the shared tables;
extend them when adding region kinds or single-run hosts.
