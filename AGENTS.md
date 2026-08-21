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

The `firAndIrAgreeOn*` drift tests in
`kinetica-compiler/test/KineticaFirCheckerTest.kt` pin the shared tables;
extend them when adding region kinds or single-run hosts.
