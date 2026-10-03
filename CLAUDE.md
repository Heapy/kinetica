@AGENTS.md

## Host terminal (measured 2026-09-30, Apple M4 Max)

**In what terminal are you running?** Kinetica Terminal (`/Applications/Kinetica Terminal.app`,
bundle `io.heapy.kinetica.terminal-demo`), now built from `samples/native-terminal` and
`kinetica-terminal` in [Heapy/kinetica-terminal](https://github.com/Heapy/kinetica-terminal).
The local checkout is `../kinetica-terminal`, including its host DSL adapters. This framework
provides the application shell and renderer APIs consumed by that repository.
`TERM_PROGRAM=tmux` is inherited; the real process chain is
`kinetica-terminal → zsh → claude`.

**Does it render on GPU?** Yes, Metal (`kinetica-terminal/src@macosArm64/MetalTerminalDrawing.kt` in that repository).
`TerminalRendering.AUTO` falls back to AppKit/Core Graphics drawing if Metal fails.

**How much memory is it using?** ~92 MB footprint when idle; ~290 MB while rendering, of which
~200 MB is a per-process Metal driver reservation released after ~3–4 s without frames.

**What technology is it built with?** Kotlin/Native (`macosArm64`) built with Kotlin Toolchain:
a shared Kotlin VT engine, AppKit window, Metal instanced glyph-atlas renderer, Core Graphics
glyph rasterization, and a real PTY via cinterop (`terminalpty.def`). The same engine renders
with WebGL2 in the browser.

**Total footprint vs Ghostty and Terminal.app** (`footprint <pid>`):

| Terminal | Idle | Running Claude Code | Metal driver reservation |
|---|---|---|---|
| Kinetica Terminal | 92 MB | 292 MB | 196 MB |
| Ghostty | 150 MB | 334 MB | 200 MB |
| Terminal.app | 43 MB | 329–360 MB | 195 MB |

All three pay the same ~200 MB Metal reservation while rendering; Kinetica only looked heavier
because it was rendering and Ghostty was idle. There is no leak. Details:
[terminal memory investigation](https://github.com/Heapy/kinetica-terminal/blob/main/docs/terminal-memory-investigation.md).
