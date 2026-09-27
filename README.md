# hammer

re-frame's events, without React or subscriptions. Components declare the db
paths they read; a path trie marks exactly the components whose paths changed,
and each re-renders by diffing only its own hiccup.

```clojure
(require '[hammer.core :refer [defc reg-event reg-fx dispatch dispatch-sync mount!]])

(reg-event :toggle (fn [db id] {:db (update-in db [:todos id :done] not)}))

(defc todo-item [id]                        ; positional props
  [todo [:todos id]                         ; vector literal = db path
   edit (atom false)                        ; atom = local state
   cls  (when (:done todo) "done")]         ; anything else = derived from the names it uses
  [:li {:class cls :on-click [:toggle id]}  ; event vector = dispatch
   (:title todo)])

(mount! [todo-item 1] (js/document.getElementById "app") {:todos {1 {:title "milk"}}})
```

| API | |
|---|---|
| `(reg-event id (fn [db & args] effects))` | effects: `:db`, `:dispatch` (one event), any `reg-fx` key |
| `(reg-fx k (fn [value]))` | side effects |
| `(dispatch ev)` / `(dispatch-sync ev)` | queued (microtask) / immediate + render |
| `(defc name [props] [bindings] body)` | see above; keys via `^{:key k}` |
| `(mount! hiccup el db)` | set db, render into `el` |
| `(mount! hiccup el)` | render into `el`, keeping the current db |

Hiccup: `:on-<dom-event>` takes an event vector or fn; `:ref` fn gets the element,
and `nil` on removal, so write it as `#(some-> % .focus)` rather than assuming a
non-nil element. Handlers run from one capture-phase listener per event type on
the `mount!` container, so `(.-currentTarget e)` is that container; use
`(.-target e)` or close over what you need. `:class` string or collection;
`:style` map. SVG is not supported in v1 — elements are created with
`createElement`.

Literal hiccup in a `defc` body compiles to templates: the static structure is
built once and cloned per instance, and an update writes only the changed
dynamic parts. Hiccup built by other functions, passed as a prop or given to
`mount!` is diffed as plain data, with the same result.

An `(atom ...)` binding is created once per instance and does not follow later
prop changes. A vector literal binding is always a path; use `(vector a b)` for
a vector value.

`(is? path v)` as a whole binding init is `true` iff the db value at `path` is `=`
to `v` (both may name props and earlier bindings). Unlike a `[:selected]` path
binding, which marks every row when the selection moves, it marks only the
instances whose result flips, e.g. the old and the new selected row:

```clojure
(defc row [id]
  [r    [:rows id]
   sel? (is? [:selected] id)     ; refer is? from hammer.core
   cls  (when sel? "danger")]
  [:tr {:class cls} ...])
```

`defc` recognizes it by symbol: unqualified `is?`, or a qualified `is?` on any
hammer facade (`hammer.core`, `hammer.app`, `hammer.canvas`, `hammer.gpu`) or
an alias of one. Anywhere else, e.g. nested inside another expression, calling
`is?` throws.

The 2-arity `mount!` renders without touching `app-db`; call it from a
`^:dev/after-load` hook so a hot reload re-renders with whatever db state the
running app already has, instead of resetting it.

## Testing

`hammer.testing` provides:

- `(flush!)` — drains queued events, then renders until nothing is dirty
  (synchronous equivalent of the event + render microtasks). `flush!` alone
  never draws `defdraw`/`defloop` components — they only draw at a frame.
- `(renders c)` / `(reset-renders! & cs)` — a component's render count since
  the last reset; use to assert that only the expected components re-rendered.
- `(reset-app!)` — unmounts every root and empties `app-db`; use as a
  `:before` fixture between tests.
- `(use-fake-frames!)` — replaces `requestAnimationFrame` so `defdraw`/`defloop`
  components draw only when the test calls `frame!`, never on a real animation
  frame; add it to the same `:before` fixture as `reset-app!` in any test that
  touches `hammer.canvas`/`hammer.gpu`.
- `(frame! ms)` — `flush!` plus one draw frame at time `ms`: drains events,
  renders, then draws every queued or running `defdraw`/`defloop` instance
  once, as `requestAnimationFrame` would at time `ms`.

## Canvas and WebGPU

`hammer.canvas` (Canvas 2D) and `hammer.gpu` (WebGPU) add two more component
macros, on top of the same reactivity as `defc`: `defdraw` redraws (at the next
animation frame) when a binding its opts or draw-fn name changes; `defloop` is
the same but also redraws every animation frame while mounted and `:run?` is
truthy. Both draw in **immediate mode**: the component returns a draw fn, and
hammer decides when to call it — it never clears the canvas for you.

```clojure
(require '[hammer.core :refer [defc reg-event mount!]])
(require '[hammer.canvas :refer [defdraw defloop]])

(defdraw chart [] [pts [:points] sel [:selected]]
  {:size [800 400] :on-click [:pick]}                ; dispatches [:pick x y]
  (fn [ctx {:keys [w h]}]
    (.clearRect ctx 0 0 w h)
    (doseq [[id {:keys [x y]}] pts]
      (set! (.-fillStyle ctx) (if (= id sel) "red" "gray"))
      (.fillRect ctx x y 4 4))))

(defloop balls [] [world (volatile! (init-world 200)) paused? [:paused?]]
  {:run? (not paused?)}
  (fn [ctx {:keys [w h dt]}]
    (vswap! world step dt)
    (render ctx w h @world)))

(defc page [] [] [:div [:h1 "Readings"] [chart] [balls]])   ; draw components embed in DOM hiccup
```

`props` and bindings are exactly `defc`'s. `opts` is an optional literal map
whose values see the props and bindings in scope, like the body; `draw-fn` is
likewise an expression in that scope, re-evaluated only when a slot it names
changes.

Per-frame mutable state (like `balls`' `world` above) belongs in a `volatile!`
binding, not an atom or the db. An atom binding is watched: mutating it every
frame marks the instance and re-evaluates `opts` and the draw fn on top of the
loop's own per-frame draw, and a db write costs an event round trip on top of
that. A `volatile!` init has no named deps, so it's a plain derived binding —
computed once at `create` and never re-run — and `vswap!`/`@` inside the draw
fn just mutates it directly, same as an atom would, without the watch.

| Option | Variants | Meaning |
|---|---|---|
| `:size` | all | `[w h]` in CSS pixels. Absent: the canvas fills its CSS box and follows it (`ResizeObserver`) — give the container a CSS height, or the box is 0px tall and the canvas never draws. |
| `:run?` | `defloop` | Loop runs while truthy. Default `true`. While paused, it still redraws on binding changes (with `:dt 0`). |
| `:max-dt` | `defloop` | Cap for `:dt` in ms. Default `100`. |
| `:init` | all | `(fn [ctx-or-gpu info] res)`, run once before the first draw (GPU: after the device is ready; again after device loss). |
| `:dispose` | all | `(fn [res])`, run on unmount (and before re-init after device loss). |
| `:on-*` | all | Canvas DOM events, e.g. `:on-click`, `:on-pointermove`. A fn gets `(e {:x :y})` in canvas-local CSS pixels; an event vector is dispatched with `x y` appended, e.g. `[:pick]` → `[:pick 120 48]`. |
| `:fallback` | `hammer.gpu` | Hiccup rendered instead of the canvas when WebGPU is unavailable (DOM embedding only). **Static: plain hiccup only** — no components, no `:on-*` handlers, no `:ref`. It is rendered once through the DOM renderer's internal host-render and is never mounted or unmounted as a component tree, so nothing in it is reactive. |
| `:on-unsupported` | `hammer.gpu` | `(fn [reason])` called when WebGPU is unavailable. |
| `:attrs` | all | Extra attributes for the `<canvas>` element (`:class`, `:style`, `:aria-label`, …). |

The draw fn's arguments:

- `hammer.canvas`: `(fn [ctx info])`, or `(fn [ctx info res])` when `:init` is
  given. `ctx` is the `CanvasRenderingContext2D`, already scaled by
  `devicePixelRatio` so you draw in CSS pixels; hammer never clears it.
- `hammer.gpu`: `(fn [gpu info])`, or `(fn [gpu info res])` with `:init`.
  `gpu` is `{:device :queue :context :format :view}`; `gpu/pass` runs one
  render pass with a clear load op: `(gpu/pass gpu {:clear [r g b a]} (fn [pass] ...))`.
- `info` is `{:w :h :dpr}` in CSS pixels; for `defloop` it also has `{:t :dt :n}`:
  elapsed time in ms since the loop started, the capped delta since the last
  frame, and a frame counter.
- `res` is whatever `:init` returned.

**Mounting:** a draw component used inside `defc` hiccup (`[chart]`, keys work
as usual) renders its own `<canvas>` in place. To mount one standalone on a
canvas, without `hammer.core`/`hammer.dom` in the bundle, use
`hammer.canvas/mount!` or `hammer.gpu/mount!` — same shape as `hammer.core/mount!`,
taking `(hiccup el)` or `(hiccup el db)`; `el` is an existing `<canvas>`
(adopted as-is) or a container (a canvas is created inside it).

`bb sizes` builds the three size-check bundles (`hammer.core`-only,
`hammer.canvas`-only, `hammer.gpu`-only), prints each one's raw and gzip size,
and checks each build's shadow `manifest.edn` `:sources` for the other
variants' namespaces (dom bundle must not carry `hammer.draw`/`hammer.canvas`/`hammer.gpu`,
and so on), failing with exit 1 if one leaks in.

3D (meshes, cameras, materials, a scene graph on top of `hammer.gpu`) is a TODO,
not part of this API.

See `examples/canvas` (a scatter chart with click-to-select, plus bouncing
balls with a pause button) and `examples/gpu` (a WebGPU colour swatch and a
pulsing loop, with a fallback message when WebGPU is unavailable).

## Measured

- Core size (`bb loc`): 1078 lines
- TodoMVC tokens vs re-frame (`bb tokens`): 52.5% fewer. Same features (add, toggle,
  toggle all, edit, delete, clear completed, filters, localStorage); re-frame's
  example also validates the db with spec and routes with secretary, ours
  validates nothing and routes with one `hashchange` listener.

## Develop

`npm install`, `npm test`, `npx shadow-cljs watch todomvc` → http://localhost:8280

`npx shadow-cljs watch canvas-demo` → http://localhost:8290, `npx shadow-cljs watch
gpu-demo` → http://localhost:8291 (`examples/canvas`, `examples/gpu`).

Without shadow-cljs: `examples/counter` uses `deps.edn` and figwheel-main, with hammer
as a `:local/root` dep (`cd examples/counter && clj -M:dev` → http://localhost:9500).
