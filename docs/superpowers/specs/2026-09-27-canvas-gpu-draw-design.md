# hammer draw components: Canvas 2D and WebGPU (design)

Date: 2026-09-27 · Branch: `canvas/draw` (from `perf2/slim`) · Status: draft for review

## Goal

Let hammer apps draw to a `<canvas>`, with Canvas 2D or WebGPU, using hammer's
existing reactivity (db paths, `is?`, local atoms, derived bindings). Drawing is
**immediate mode**: a component returns a draw fn, and hammer decides when to call it.
A retained scene graph (hiccup shapes, per-shape events) may be layered on top
later. It is not part of this design.

Target uses: visualisation inside DOM apps, full-canvas apps and games, and large
data rendered by user code.

## Decisions

| Topic | Decision |
|---|---|
| Model | Immediate mode (B). Retained scene graph (A) possibly later, on top. |
| Components | Two macros per variant: `defdraw` (redraw on change) and `defloop` (redraw every frame while running). |
| Variants | `hammer.core` (DOM, unchanged), `hammer.canvas` (Canvas 2D), `hammer.gpu` (WebGPU). |
| Consumers | ClojureScript apps only; macros are available. |
| Builds | Three bundles are build outputs used for size and namespace checks (`bb sizes`), not libraries for JS users. |
| GPU | WebGPU only. Unsupported browsers: detect, log, render `:fallback` / call `:on-unsupported`. No WebGL fallback. |
| GPU level | Raw: the draw fn gets `{:device :queue :context :format :view}`. Optional `gpu/pass` helper for single-pass drawing. |
| GPU resources | `:init` / `:dispose` lifecycle; hammer re-runs `:init` after device loss. |
| Frame loop | `defloop` only; one shared `requestAnimationFrame` loop that runs while any running `defloop` is mounted. `dt` capped at 100 ms by default. |
| Per-frame db ticks | Not in v1 (`reg-tick` deferred); loop state lives in local atoms or the db via events. |
| Base | `perf2/slim` (templates, `is?`, slim runtime). |

## Non-goals (v1)

- Retained scene graph, per-shape hit testing (layer A).
- WebGL fallback, WebGPU compute helpers, shader helpers.
- Server-side rendering or any server feature.
- A JS/TS API without macros.

## Later / TODO

- [ ] **3D:** meshes, cameras, lights, materials, loaders (glTF), 3D math. Too much work for v1. Revisit on top of `hammer.gpu`, together with layer A.
- [ ] Retained 2D scene graph with per-shape events (layer A).
- [ ] `reg-tick`: per-frame db updates for games that keep state in the db.

## API

### Shape

```clojure
(defdraw name [props*] [bindings*] opts? draw-fn)
(defloop name [props*] [bindings*] opts? draw-fn)
```

- `props` and `bindings` are exactly `defc`'s: vector literal = db path, `(is? path v)`,
  an init evaluating to an atom = local state, anything else = derived.
- `opts` is an optional literal map. Its values are expressions evaluated with the
  props and bindings in scope, like the body.
- `draw-fn` is an expression evaluated with the props and bindings in scope. It returns
  the fn hammer calls to draw. It is re-evaluated only when a slot it names changes
  (`body-deps`), exactly like a `defc` body.

### Draw fn arguments

| Variant | `defdraw` | `defloop` |
|---|---|---|
| `hammer.canvas` | `(fn [ctx info] …)` / `(fn [ctx info res] …)` with `:init` | same; `info` also has `:t :dt :n` |
| `hammer.gpu` | `(fn [gpu info] …)` / `(fn [gpu info res] …)` | same |

- `ctx`: the `CanvasRenderingContext2D`, already scaled by `devicePixelRatio`, so user
  code draws in CSS pixels. Hammer does not clear it; the draw fn owns the whole canvas.
- `gpu`: `{:device :queue :context :format :view}`. `:view` is the current texture view
  of the canvas for this frame.
- `info`: `{:w :h :dpr}` in CSS pixels; for `defloop` also `{:t :dt :n}`: time in ms
  since the loop started, delta in ms (capped, see `:max-dt`), frame counter.
- `res`: whatever `:init` returned.

### Options

| Key | Variants | Meaning |
|---|---|---|
| `:size` | all | `[w h]` in CSS pixels. Absent: the canvas fills its CSS box and follows it (`ResizeObserver`). |
| `:run?` | `defloop` | Loop runs while truthy. Default `true`. While paused, it still redraws on binding changes (with `:dt 0`). |
| `:max-dt` | `defloop` | Cap for `:dt` in ms. Default `100`. |
| `:init` | all | `(fn [ctx-or-gpu info] res)`, run once before the first draw (GPU: after the device is ready; again after device loss). |
| `:dispose` | all | `(fn [res])`, run on unmount (and before re-init after device loss). |
| `:on-*` | all | Canvas DOM events, e.g. `:on-click`, `:on-pointermove`. A fn gets `(e {:x :y})` in canvas-local CSS pixels; an event vector is dispatched with `x y` appended, e.g. `[:pick]` → `[:pick 120 48]`. |
| `:fallback` | `hammer.gpu` | Hiccup rendered instead of the canvas when WebGPU is unavailable (DOM embedding only). |
| `:on-unsupported` | `hammer.gpu` | `(fn [reason])` called when WebGPU is unavailable. |
| `:attrs` | all | Extra attributes for the `<canvas>` element (`:class`, `:style`, `:aria-label`, …). |

### Examples

```clojure
(ns app.chart
  (:require [hammer.core :refer [defc reg-event mount!]]
            [hammer.canvas :refer [defdraw defloop]]))

(defdraw chart []
  [pts [:points]
   sel [:selected]]
  {:size [800 400]
   :on-click [:pick]}                               ; dispatches [:pick x y]
  (fn [ctx {:keys [w h]}]
    (.clearRect ctx 0 0 w h)
    (doseq [[id {:keys [x y]}] pts]
      (set! (.-fillStyle ctx) (if (= id sel) "red" "gray"))
      (.fillRect ctx x y 4 4))))

(defloop balls []
  [world   (atom (init-world 200))
   paused? [:paused?]]
  {:run? (not paused?)}
  (fn [ctx {:keys [w h dt]}]
    (swap! world step dt)
    (render ctx w h @world)))

(defc page [] []
  [:div [:h1 "Readings"] [chart] [balls]])        ; draw components inside DOM hiccup
```

```clojure
(ns app.heat
  (:require [hammer.gpu :as gpu :refer [defdraw reg-event mount!]]))

(defdraw heatmap []
  [cells [:grid]]
  {:init     (fn [g _] {:pipe (make-pipeline g) :buf (make-buffer g 65536)})
   :dispose  (fn [{:keys [buf]}] (.destroy buf))
   :fallback [:p "This view needs WebGPU."]}
  (fn [g info {:keys [pipe buf]}]
    (.writeBuffer (:queue g) buf 0 (grid->f32 cells))
    (gpu/pass g {:clear [0 0 0 1]}
      (fn [pass] (.setPipeline pass pipe) (.draw pass 6 (count cells))))))

;; full-canvas app, no hammer.dom in the bundle:
(mount! [heatmap] (js/document.getElementById "c") {:grid (initial-grid)})
```

### Mounting

- **Inside a DOM app:** a draw component in `hammer.core` hiccup (`[chart]`, keys work as
  usual) renders its own `<canvas>`.
- **Full-canvas app:** `hammer.canvas/mount!` / `hammer.gpu/mount!` take `(hiccup el)` or
  `(hiccup el db)`, like `hammer.core/mount!`. `el` is an existing `<canvas>` (adopted as-is)
  or a container (a canvas is created inside). The hiccup must be a single draw
  component.

## Semantics

### When a draw fn runs

1. A db change, `is?` flip, local atom change or prop change marks the instance, as for `defc`.
2. The scheduler's flush runs the instance's **draw runner**: `refresh!` recomputes
   bindings; if a slot the draw fn or opts name changed, the instance is queued for the
   next animation frame. It is never drawn in the microtask.
3. At the frame: running `defloop`s advance (`:t :dt :n`), then every queued or running
   draw instance is drawn once, in mount order. The browser
   paints.

Many events between two frames produce one draw per instance. DOM components keep
their microtask flush.

### The loop

- One module-level rAF loop. It is requested only while at least one mounted `defloop`
  has `:run?` truthy, or a draw is queued. Otherwise no rAF is pending (idle app = no CPU).
- `:t` starts at 0 when the component's loop first runs; pausing freezes `:t`; `:dt` is the
  elapsed time since the previous frame, capped at `:max-dt`, and 0 for a draw caused by a
  binding change while paused.

### Size and DPR

- The canvas backing store is `ceil(w * dpr) × ceil(h * dpr)`. `ctx` has
  `setTransform(dpr, 0, 0, dpr, 0, 0)` applied before each draw (Canvas 2D).
- On a size or DPR change (resize, zoom, moving to another monitor): resize the backing
  store, reconfigure the GPU context, redraw.

### Errors

- A throwing draw fn, `:init` or opt expression is logged by component name. The
  canvas keeps its last content, and the instance draws again on its next trigger. A
  loop keeps running.
- `:init` throwing: the component does not draw until remounted, or until device loss
  triggers a re-init.

### WebGPU lifecycle

- One adapter and device per page, requested on the first `hammer.gpu` mount.
  Components mounted before the device is ready draw once it is.
- Unsupported (no `navigator.gpu`, `requestAdapter` returns null, `requestDevice`
  rejects): log once; per component call `:on-unsupported` and, in DOM embedding,
  render `:fallback` instead of the canvas.
- Device lost (`device.lost` resolves, not by our `destroy`): run `:dispose` for all
  gpu components, request a new device, reconfigure every context, run `:init` again,
  redraw.
- `gpu/pass`: `(pass gpu {:clear [r g b a]} f)` creates an encoder and one render pass on
  `(:view gpu)` with a clear load op, calls `(f pass)`, ends the pass and submits.

## Architecture

### Namespaces (facade per variant)

| Namespace | Contents | Requires |
|---|---|---|
| `hammer.events`, `hammer.state`, `hammer.trie`, `hammer.cells`, `hammer.scheduler` | shared runtime (existing) | — |
| `hammer.app` (new) | public event API: `reg-event reg-fx dispatch dispatch-sync is?` | runtime |
| `hammer.macros` (new, clj) | binding compilation shared by all macros: `binding-spec`, `deps-of`, `is?` detection | — |
| `hammer.dom` | DOM renderer, templates, **host hook** | runtime |
| `hammer.core` | DOM facade: re-exports `hammer.app`, `defc`, `mount!` (unchanged API) | `hammer.app`, `hammer.dom` |
| `hammer.draw` (new, internal) | shared draw runtime: draw runner, rAF queue and loop, canvas host (element, DPR, resize, events), `mount!` for a bare canvas | runtime |
| `hammer.canvas` | Canvas 2D facade: re-exports `hammer.app`, `defdraw`, `defloop`, `mount!` | `hammer.app`, `hammer.draw` |
| `hammer.gpu` | WebGPU facade: same, plus device management and `pass` | `hammer.app`, `hammer.draw` |

`hammer.core`, `hammer.canvas` and `hammer.gpu` don't require each other. A DOM app that
embeds a chart requires `hammer.core` and `hammer.canvas`.

### Per-component runners

`Comp` gains a runner (and a host, below). The scheduler calls the instance's
component runner instead of one global runner. DOM components keep `dom/update-inst!`;
draw components use `draw/run!` (refresh, then queue for the frame).

### Host hook (DOM embedding without a dependency)

A draw component's `Comp` carries a host object: `create` (props → element, mounts the
instance), `update` (new props), `destroy` and `node`. When `hammer.dom` normalizes a
component vector whose component has a host, it makes a `:host` vnode and delegates to
it: create, patch (props via `set-props!`), keyed moves (via `node`), and unmount.
`hammer.dom` knows only this shape, never `hammer.draw`.

## Builds and size checks

- `size/dom.cljs`, `size/canvas.cljs`, `size/gpu.cljs`: tiny apps using one facade each,
  built with shadow-cljs `release` (three builds in `shadow-cljs.edn`).
- `bb sizes`: builds all three, prints raw and gzip size per bundle, and checks each
  bundle's shadow build report:
  - dom: no `hammer.draw`, `hammer.canvas`, `hammer.gpu`
  - canvas: no `hammer.dom`, `hammer.gpu`
  - gpu: no `hammer.dom`, `hammer.canvas`

  A forbidden namespace fails the task (exit 1). Sizes are only reported; there is no cap.

## Testing

- **Unit (node + jsdom, `npm test`):**
  - a recording fake 2D context: draw calls, transforms, DPR setup;
  - a fake rAF clock, driven from `hammer.testing`: a new `(frame! ms)` advances the
    loop, and `flush!` also runs queued draws.
- **Canvas cases:**
  - redraw only when a named slot changes;
  - many events → one draw per frame;
  - `defloop` runs, pauses and resumes through `:run?`; `:dt` is capped;
  - `:size` and DPR, and resize through a fake `ResizeObserver`;
  - `:on-*` with local coordinates, fn and vector forms;
  - error isolation;
  - unmount stops drawing and runs `:dispose`;
  - embedding inside `defc` with keyed moves.
- **GPU cases, with a stub `navigator.gpu`:**
  - draws only after the device is ready;
  - the unsupported path (`:fallback`, `:on-unsupported`);
  - device loss re-runs `:dispose` and `:init` and redraws;
  - `pass` records a clear load op and submits.
- **Regression:** the full existing suite, todomvc included, stays green; the facade split
  must not change `hammer.core` behaviour.
- **Manual:** `examples/canvas` (chart with pick, bouncing balls with pause) and
  `examples/gpu` (heatmap plus the fallback message), in Chrome; the gpu fallback also
  in Firefox.
- `bb sizes` passes.

## Success criteria

1. `defdraw` and `defloop` work in both variants, as specified above, with unit tests.
2. Draw components embed in `hammer.core` apps and also mount standalone on a canvas.
3. `bb sizes` shows the three bundles free of each other's namespaces.
4. The existing test suite and the js-framework-benchmark entry are unaffected: same
   API, and no measurable regression versus `perf2/slim`.
5. Examples run in Chrome; the gpu example shows its fallback where WebGPU is missing.
