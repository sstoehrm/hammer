# hammer.gl: WebGL2 replaces WebGPU (design)

Date: 2026-09-28 · Branch: `canvas/webgl2` (from `canvas/draw`) · Status: draft for review

Amends `2026-09-27-canvas-gpu-draw-design.md`. Everything there still holds for
`hammer.canvas` and the shared runtime (`hammer.draw`). This document replaces its
WebGPU parts.

## Why

The manual check (#4) showed that WebGPU is not usable by default where users are:
- Firefox has no `navigator.gpu`.
- Linux Chromium returns no adapter while Vulkan is disabled.

WebGL2 works without flags in Chrome, Firefox and Safari. The GPU variant switches to
WebGL2 now, and WebGPU comes back once support is default. The WebGPU implementation
stays in `canvas/draw`'s history.

## Decisions

| Topic | Decision |
|---|---|
| Namespace | `hammer.gl` replaces `hammer.gpu`: `defdraw`, `defloop`, `mount!` and the event API, the same facade shape as `hammer.canvas`. `hammer.gpu`, its fake and its tests are removed. |
| Draw fn | `(fn [gl info])`, or `(fn [gl info res])` with `:init`. `gl` is the raw `WebGL2RenderingContext`. `info` is unchanged. |
| Viewport | Before each draw, hammer calls `gl.viewport(0, 0, drawingBufferWidth, drawingBufferHeight)`, the counterpart of Canvas 2D's `setTransform(dpr…)`. The draw fn owns everything else, including clearing. |
| Context | One context per component: `canvas.getContext("webgl2", attrs)`. `:context-attrs` (a map, e.g. `{:antialias false :alpha false}`) is read once at setup, because context attributes are fixed at creation. |
| Unsupported | `getContext` returns null → log `hammer: WebGL2 unavailable: …` once per page, call `:on-unsupported` with the reason, and render the static `:fallback` hiccup in DOM embedding. The semantics are the same as the old gpu variant. |
| Context loss | On `webglcontextlost`, hammer calls `preventDefault` (so the context can be restored) and runs `:dispose`. WebGL calls on a lost context are no-ops, so `delete*` inside `:dispose` is harmless. Drawing stops. On `webglcontextrestored`, hammer runs `:init` again and redraws. A `defloop`'s clock freezes while the context is lost (the not-drawable rule). |
| Unmount | Remove the loss/restore listeners, run `:dispose`, and release the context with `WEBGL_lose_context.loseContext()` when available, because browsers cap live WebGL contexts per page (about 16, and the oldest is lost). |
| Helpers | None. `clearColor` plus `clear` replaces `gpu/pass`. |
| Bundles | `size-gpu` becomes `size-gl`. `bb sizes`: each bundle must not include another variant's namespace files (`hammer/gl.cljs` takes the place of `hammer/gpu.cljs`). |
| Benchmark | `gpu-points` becomes `gl-points`: 100k points drawn with `drawArrays(POINTS)` from one `ARRAY_BUFFER`, hammer vs vanilla WebGL2 doing the same calls. |
| Examples | `examples/gpu` becomes `examples/gl` (port 8291): the swatch and pulse via `clearColor`/`clear`, plus a shader-drawn triangle whose colour comes from the db. |

## Non-goals

- A portable WebGL2/WebGPU abstraction, a WebGL1 fallback, or shader and program
  helpers.
- Sharing one context across components. Each component has its own canvas; the
  context cap is documented.

## Later / TODO

- [ ] Bring back a WebGPU variant once it's on by default in Firefox and Linux Chromium.
  See `canvas/draw` for the removed `hammer.gpu`.
- [ ] 3D and a retained scene graph (unchanged from the base spec).

## Testing

- A fake WebGL2 context in jsdom: `getContext("webgl2", attrs)` records calls, and
  `drawingBufferWidth`/`drawingBufferHeight` follow the canvas.
  `getExtension("WEBGL_lose_context")` returns a recording `loseContext`. One mode
  returns null.
- Cases:
  - the viewport is set to the backing store (DPR 2) before the draw fn runs;
  - `:context-attrs` are passed through;
  - unsupported: `:fallback` renders, `:on-unsupported` gets the reason, and the log
    happens once for two components;
  - `webglcontextlost` is default-prevented, runs `:dispose` and stops drawing, and a
    loop's clock freezes; `webglcontextrestored` runs `:init` and redraws;
  - unmount calls `loseContext` and removes the listeners;
  - `defloop` with gl.
- Gates: `npm test`, `bb sizes`, 0-warning builds, and one `gl-points` benchmark run.
- Manual: `examples/gl` renders in Chrome, Chromium with default flags, and Firefox
  (all three without any flags).
