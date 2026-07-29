# Desktop GPU & shaders

<!-- code: kinetica-render-core/src/HostAdapter.kt (HostAdapter), kinetica-appkit/src@macosArm64/AppKitKineticaApp.kt (renderAppKitApp), kinetica-gtk/src@linuxX64/GtkKineticaApp.kt (renderGtkApp) -->

> **Status: design note, nothing implemented** (July 2026). This page records why a GPU/shader
> story could matter for the desktop renderers, the integration options on the table, and their
> trade-offs — so the discussion doesn't have to be re-derived when we pick it up.

## Where the desktop renderers stand

The desktop story today is two **retained-widget renderers** over the shared reconciler:
`kinetica-appkit` (macosArm64) and `kinetica-gtk` (GTK4, linuxX64, CI-only). Each is a thin
`HostAdapter` — roughly 400 lines — that maps a deliberately tiny tag vocabulary
(`row`/`column`/`button`/`checkbox`/`textfield`) onto native widgets. Layout is owned by the
toolkit (`NSStackView` / `GtkBox`); Kinetica has no layout engine, no drawing layer, and no GPU
code of its own.

## Why shaders in a desktop app at all

Modern toolkits already draw everything with shaders: GTK4 renders widgets through GSK's GPU
renderers, AppKit composites layers through Core Animation, and Apple's Liquid Glass is
literally a refraction shader shipped inside the standard toolkit. So the real question is
never "why shaders" but "why *custom* shaders". Three reasons show up in practice:

1. **Effects too expensive on CPU.** Backdrop blur / frosted glass, glow, shimmer skeletons,
   dissolve and ripple transitions. Blurring a window-sized backdrop 120 times a second is a
   non-starter on CPU and sub-millisecond on GPU.
2. **Owner-drawn content.** Maps, dataviz with millions of points, image/video viewports,
   media players with user filter chains. Toolkit widgets end and a GPU canvas takes over.
3. **Extreme-performance UI.** GPU terminals (Ghostty, Alacritty), editors like Zed whose
   entire UI is SDF shaders, Flutter and Compose Desktop via Skia. Qt Quick even exposes a
   declarative `ShaderEffect` element.

For plain forms and CRUD screens the toolkit's own shaders are all anyone needs; a custom
shader story is worth its cost only when one of the three above appears.

## What it would solve for Kinetica

- **A material/depth design language** — glass panels, glow, real transition effects — that the
  current widget vocabulary cannot express at all.
- **Canvas-grade content** inside Kinetica apps (charts, maps, media) without leaving the
  framework for a foreign view.
- **Long-term optionality:** a single owner-drawn renderer with identical pixels on every
  desktop, and a strong performance narrative to go with the [benchmark work](/docs/performance).

## Integration options

### Option A — a `canvas` escape-hatch host tag

A new tag whose adapter creates `MTKView` (AppKit) or `GtkGLArea` (GTK4) and hands the app a
draw callback plus input events. The reconciler is untouched: `HostAdapter<V>` is generic over
the view type and does not care that one host draws itself.

- **Solves:** owner-drawn content (reason 2) inside otherwise-native apps.
- **Pros:** smallest possible change — one tag per adapter; incremental; the rest of the UI
  keeps native widgets and their accessibility, IME and focus behaviour for free.
- **Cons:** two shader dialects to feed (Metal on macOS, GL on Linux); effects stop at the
  canvas rectangle, so no glass *over* sibling widgets; on GTK4 `GskGLShader` is deprecated
  since 4.16, leaving `GtkGLArea` as the only custom-shader path.

### Option B — toolkit-native effects on widgets

Expose what each toolkit already has: on AppKit, `CIFilter` chains on `layer.backgroundFilters`
(blur + displacement is a genuine liquid-glass-over-app-content) and `NSVisualEffectView`; on
GTK4, snapshot blur nodes only.

- **Solves:** frosted-glass / material styling (reason 1), primarily on macOS.
- **Pros:** cheap; GPU-accelerated by the toolkit itself; follows the platform's own design
  language.
- **Cons:** capability mismatch between platforms — GTK has blur but no refraction, so the
  effect vocabulary collapses to the intersection; needs a styling surface in a prop model
  that is currently stringly-typed (`setProp(view, name, value)`).

### Option C — an owner-drawn SDL3 renderer

A `kinetica-sdl` module (linuxX64 + macosArm64): SDL3 for window/input, SDL_GPU
(Vulkan/Metal/D3D12) for drawing, `V` = a node of our own scene graph — the `HostAdapter`
contract already fits. Everything a toolkit normally provides must be built: a layout engine
(render-core has none), text via SDL_ttf 3.x (HarfBuzz shaping), clipping and scrolling, focus,
IME, accessibility via AccessKit's C API, and the controlled-state resync the other adapters
get from `isControlledTag`. Shader portability comes from SDL_shadercross
(HLSL → SPIR-V/MSL/DXIL); a Linux-only start can stick to glslang → SPIR-V.

The liquid-glass pipeline this enables: render UI layers to offscreen textures → downsampled
gaussian blur of the backdrop → a panel fragment shader doing SDF-driven refraction
displacement, edge chromatic aberration and a specular highlight. The shader itself is the
cheap part — a few hundred lines; the toolkit around it is the actual work.

- **Solves:** all three reasons at once — full pixel ownership.
- **Pros:** one renderer, identical pixels on every desktop; a complete material language
  becomes possible; SDL3 cinterop is a flat C API behind `pkg-config sdl3`, far cleaner than
  the generated-`.def` dance GTK required; unlike `kinetica-gtk` it builds on macOS too.
- **Cons:** by far the largest scope — effectively a mini-Flutter, with accessibility and IME
  the hardest parts; abandons native look-and-feel; adds a whole new performance surface to
  keep honest.

## Constraints that shape any option

- **Props are strings.** `HostAdapter.setProp` passes `name: String, value: String`; any
  styling/effect surface means either parsing string props or a typed extension of the
  contract.
- **Layout lives in the toolkit today.** Options A/B inherit it; option C must bring its own.
- **Glass is window-local everywhere.** Pixels behind the window belong to the compositor
  (KDE ships a blur protocol, GNOME none), so true desktop-backdrop refraction is out of reach
  for every option — including SDL3.
- **Adapter invariants carry over.** Caption folding (`foldsChildren`) and controlled-state
  resync (`isControlledTag`) are part of the contract; a new adapter must honour both.

## Recommendation

Option A first: one tag per adapter unlocks canvas content immediately and commits to nothing.
Option B as macOS-side polish where a design genuinely wants glass. Option C only if owning
every pixel becomes strategic — and then staged: window + rects + text, then scene graph +
layout + `HostAdapter`, and the glass pipeline last, because it is the easiest stage of the
three.
