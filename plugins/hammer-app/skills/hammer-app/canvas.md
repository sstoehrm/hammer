# hammer.canvas and hammer.gl: defdraw, defloop

`hammer.canvas` (Canvas 2D) and `hammer.gl` (WebGL2) define draw components with
the same props and bindings as `defc`, but returning a draw fn instead of
hiccup. `defdraw` redraws at the next animation frame when a binding its opts or
draw fn names changes; `defloop` also redraws every frame while `:run?` is
truthy. hammer never clears the canvas: clear it yourself.

```clojure
(require '[hammer.canvas :refer [defdraw defloop]])

(defdraw chart [] [pts [:points] sel [:selected]]
  {:size [800 400] :on-click [:pick]}                 ; dispatches [:pick x y]
  (fn [ctx {:keys [w h]}]
    (.clearRect ctx 0 0 w h)
    (doseq [[id {:keys [x y]}] pts]
      (set! (.-fillStyle ctx) (if (= id sel) "red" "gray"))
      (.fillRect ctx x y 4 4))))

(defloop balls [] [world (volatile! (init-world 200)) paused? [:paused?]]
  {:run? (not paused?)}
  (fn [ctx {:keys [w h dt]}] (vswap! world step dt) (render ctx w h @world)))

(defc page [] [] [:div [chart] [balls]])              ; embed like any component
```

- Draw fn: `(fn [ctx info])`, or `(fn [ctx info res])` with `:init`. `ctx` is
  the 2D context scaled by `devicePixelRatio` (draw in CSS pixels); with
  `hammer.gl` it is the `WebGL2RenderingContext`, viewport already set. `info`
  is `{:w :h :dpr}`, plus `{:t :dt :n}` for `defloop` (ms since start, capped
  delta, frame count).
- Opts (a literal map, may use bindings): `:size [w h]` (absent: fills its CSS
  box, so give the container a height), `:run?` and `:max-dt` (100) for
  `defloop`, `:init (fn [ctx info] res)` / `:dispose (fn [res])`, `:attrs` for
  the `<canvas>`, and `:on-*` canvas events: a fn gets `(e {:x :y})`
  canvas-local, an event vector is dispatched with `x y` appended.
  `hammer.gl` only: `:context-attrs`, `:fallback` (static hiccup shown without
  WebGL2), `:on-unsupported (fn [reason])`.
- Per-frame mutable state goes in a `volatile!` binding (as `world` above): an
  atom binding is watched and re-evaluates opts and draw fn on every change.
- A global atom deref'd **inside the draw fn** is not tracked (it is a fn):
  bind it (`[c cart]`) or read it through a db path, or a `defdraw` never
  redraws. A deref in the opts map is tracked.
- Standalone, without `hammer.core` in the bundle: `(hammer.canvas/mount! [chart]
  el db)` (or `hammer.gl/mount!`), where `el` is a `<canvas>` or a container.
  Both namespaces also export `reg-event`, `dispatch`, `on-error!`, etc.
- `hammer.gl`: each component owns a WebGL2 context, and browsers keep about 16
  per page; keep live `hammer.gl` components well under that.
