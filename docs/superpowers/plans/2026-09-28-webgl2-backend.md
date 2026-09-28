# hammer.gl (WebGL2 replaces WebGPU): implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the WebGPU draw variant (`hammer.gpu`) with a WebGL2 variant (`hammer.gl`) that works without flags in Chrome, Firefox and Safari.

**Architecture:** `hammer.gl` is a third facade over the shared `hammer.draw` runtime. It follows `hammer.canvas`'s shape and registers a `:gl` Backend: one WebGL2 context per component, the viewport set per draw, context loss and restore, and the context released on unmount. `hammer.gpu` and everything built for it (fake, tests, size app, example, bench scenario) are removed or ported.

**Tech Stack:** ClojureScript, shadow-cljs 3.5.3 (`npm test` = node-test with jsdom), babashka, puppeteer-core with snap Chromium (bench).

**Spec:** `docs/superpowers/specs/2026-09-28-webgl2-backend-design.md` (amends `2026-09-27-canvas-gpu-draw-design.md`).

## Global Constraints

- Worktree `/home/soeren/repos/private/hammer-perf/canvas-webgl2`, branch `canvas/webgl2` (from `canvas/draw`). It has its own `node_modules`.
- `hammer.core`, `hammer.canvas` and `hammer.gl` must not require each other; `hammer.dom` must not require `hammer.draw`. Draws happen only in `hammer.draw/frame!`.
- The draw fn is `(fn [gl info])`, or `(fn [gl info res])` with `:init`. `gl` is the raw `WebGL2RenderingContext`, and the viewport is set to `(0, 0, drawingBufferWidth, drawingBufferHeight)` before each draw.
- Unsupported: log `hammer: WebGL2 unavailable: <reason>` once per page, call `:on-unsupported` with the reason, and render the static `:fallback` in DOM embedding.
- Context loss: `preventDefault`, `:dispose`, no drawing. Restore: `:init` again, then redraw. Unmount: remove the listeners, `:dispose`, then `WEBGL_lose_context.loseContext()`.
- No new runtime dependencies. `npm test` stays green after every task. `bb sizes` exits 0 at the end.
- Commit trailers: a Co-Authored-By naming the model that wrote the commit, plus `Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh`.

## Review Focus

1. **Many `hammer.gl` components on one page (more than about 16):** the browser loses the oldest contexts. Those components must show no errors and simply stop drawing; restore handles them if it happens. Unmount must release contexts, so remount cycles don't exhaust the cap. Tested in Task 1 by `unmount-releases-the-context`.
2. **Context loss during a running `defloop`:** the clock freezes and there are no draws; after restore, `:init` runs once and the loop resumes with `dt 0`. Tested in Task 1.
3. **`:context-attrs` changing between renders:** attributes are fixed at creation, so changes are ignored. The docstring says so; no test needed.
4. **The unsupported path in two components:** one log, both fallbacks render. Tested in Task 1.
5. **Removing `hammer.gpu` leaves no dangling references** (macros facades set, docstrings, builds, bb tasks). Checked by grep in Task 2.

---

### Task 1: `hammer.gl` backend, macros, fake context and tests

**Files:**
- Create: `src/hammer/gl.clj`, `src/hammer/gl.cljs`, `test/hammer/fake_gl.cljs`, `test/hammer/gl_test.cljs`
- Modify: `src/hammer/macros.clj` (add `hammer.gl` to `facades`; `hammer.gpu` goes in Task 2)

**Interfaces:**
- Consumes: `hammer.draw/Backend [setup! draw-arg resized! teardown!]`, `register-backend!`, `queue!`, `dispose!`, `mount!`; `State` fields `canvas`, `ctx`, `opts`, `ext`; `hammer.macros/draw-def`; `hammer.test-util/capture-errors`.
- Produces: `hammer.gl/defdraw` and `defloop` (macros), `reg-event reg-fx dispatch dispatch-sync is? mount!`, and `reset-log!` (a test hook).

- [ ] **Step 1: The fake context** `test/hammer/fake_gl.cljs`

```clojure
(ns hammer.fake-gl
  "jsdom has no WebGL: getContext(\"webgl2\", attrs) returns a recording fake
  (mode :ok) or null (mode :none). restore! puts the original getContext back."
  (:require [hammer.test-env]))

(defonce log (atom []))
(defonce ^:private saved (atom nil))

(def ^:private ops ["viewport" "clearColor" "clear" "drawArrays" "useProgram"])

(defn- fake [^js c attrs]
  (let [o #js {:canvas c :attrs attrs}]
    (doseq [m ops]
      (aset o m (fn [& args] (swap! log conj (into [(keyword m) (.-id c)] args)))))
    (js/Object.defineProperty o "drawingBufferWidth" #js {:get (fn [] (.-width c))})
    (js/Object.defineProperty o "drawingBufferHeight" #js {:get (fn [] (.-height c))})
    (aset o "getExtension"
          (fn [n]
            (when (= n "WEBGL_lose_context")
              #js {:loseContext (fn [] (swap! log conj [:loseContext (.-id c)]))})))
    o))

(defn restore! []
  (when-let [f @saved]
    (set! (.. js/window -HTMLCanvasElement -prototype -getContext) f)
    (reset! saved nil)))

(defn install!
  "mode: :ok or :none."
  [mode]
  (restore!)
  (reset! log [])
  (let [proto (.. js/window -HTMLCanvasElement -prototype)
        prev (.-getContext proto)]
    (reset! saved prev)
    (set! (.-getContext proto)
          (fn [kind attrs]
            (this-as ^js c
              (if (= kind "webgl2")
                (when (= mode :ok)
                  (or (.-__fakegl c)
                      (let [o (fake c attrs)] (set! (.-__fakegl c) o) o)))
                (.call prev c kind attrs)))))))
```

- [ ] **Step 2: Write the failing tests** `test/hammer/gl_test.cljs`

```clojure
(ns hammer.gl-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-gl :as fgl]
            [hammer.test-util :refer [capture-errors]]
            [hammer.core :as core :refer [defc]]
            [hammer.gl :as gl :refer [defdraw defloop]]
            [hammer.draw :as draw]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (gl/reset-log!) (fgl/install! :ok))
                     :after (fn [] (t/reset-app!) (draw/set-raf! nil) (fgl/restore!))})

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(def res-log (atom []))

(defdraw tri [id] [n [:n]]
  {:size [100 50] :attrs {:id id}
   :context-attrs {:antialias false :alpha false}
   :init (fn [_gl _] (swap! res-log conj [:init]) :prog)
   :dispose (fn [r] (swap! res-log conj [:dispose r]))
   :fallback [:p "needs WebGL2"]
   :on-unsupported (fn [r] (swap! res-log conj [:unsupported r]))}
  (fn [g _ res] (.clearColor g 0 0 0 1) (.drawArrays g 4 0 n) (swap! res-log conj [:draw res n])))

(defn- ops [id] (into [] (comp (filter #(= id (second %))) (map #(into [(first %)] (drop 2 %)))) @fgl/log))

(deftest draws-with-viewport-and-context-attrs
  (set! (.-devicePixelRatio js/globalThis) 2)
  (try
    (reset! state/app-db {:n 3})
    (reset! res-log [])
    (let [c (js/document.createElement "canvas")]
      (gl/mount! [tri "a"] c)
      (t/frame! 16)
      (is (= [[:viewport 0 0 200 100] [:clearColor 0 0 0 1] [:drawArrays 4 0 3]] (ops "a")))
      (is (= {:antialias false :alpha false} (js->clj (.-attrs (.getContext c "webgl2")) :keywordize-keys true)))
      (is (= [[:init] [:draw :prog 3]] @res-log)))
    (finally (js-delete js/globalThis "devicePixelRatio"))))

(defc two [] [] [:div [tri "x"] [tri "y"]])

(deftest unsupported-renders-fallback-and-logs-once
  (fgl/install! :none)
  (reset! res-log [])
  (let [host (js/document.createElement "div")
        logs (capture-errors (fn [_] (core/mount! [two] host)))]
    (is (= 1 (count (filter #(= "hammer: WebGL2 unavailable:" (first %)) logs))))
    (is (= 2 (count (.querySelectorAll host "p"))))
    (is (= 0 (count (.querySelectorAll host "canvas"))))
    (is (= [[:unsupported "no WebGL2 context"] [:unsupported "no WebGL2 context"]] @res-log))))

(defn- fire! [^js c type]
  (let [e (new (.-Event js/window) type #js {:cancelable true})]
    (.dispatchEvent c e)
    e))

(deftest context-loss-disposes-and-restore-reinits
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [tri "l"] c)
    (t/frame! 16)
    (reset! res-log [])
    (is (.-defaultPrevented (fire! c "webglcontextlost")))
    (is (= [[:dispose :prog]] @res-log))
    (events/dispatch [::set :n 2])
    (t/frame! 32)
    (is (= [[:dispose :prog]] @res-log) "lost: no draw")
    (fire! c "webglcontextrestored")
    (t/frame! 48)
    (is (= [[:dispose :prog] [:init] [:draw :prog 2]] @res-log))))

(deftest unmount-releases-the-context
  (reset! state/app-db {:n 1})
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [tri "u"] c)
    (t/frame! 16)
    (t/reset-app!)
    (is (some #(= [:loseContext "u"] %) @fgl/log))
    (reset! res-log [])
    (fire! c "webglcontextlost")
    (is (empty? @res-log) "listeners removed")))

(def frames (atom []))

(defloop spin [] [] {:size [10 10] :attrs {:id "s"}}
  (fn [_ {:keys [t dt n]}] (swap! frames conj [t dt n])))

(deftest loop-clock-freezes-while-context-is-lost
  (reset! frames [])
  (let [c (js/document.createElement "canvas")]
    (gl/mount! [spin] c)
    (t/frame! 0) (t/frame! 16)
    (fire! c "webglcontextlost")
    (t/frame! 32) (t/frame! 48)
    (fire! c "webglcontextrestored")
    (t/frame! 500) (t/frame! 516)
    (is (= [[0 0 1] [16 16 2] [16 0 3] [32 16 4]] @frames))))
```

- [ ] **Step 3: Run and check they fail.** Run `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|failures|namespace"`. Expected: `hammer.gl` not available.

- [ ] **Step 4: Macros** `src/hammer/gl.clj`

```clojure
(ns hammer.gl
  (:require [hammer.macros :as m]))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn): a WebGL2 component redrawn
  (at the next animation frame) when a binding its opts or draw-fn name
  changes. draw-fn: (fn [gl info]) or, with :init, (fn [gl info res]); gl is
  the WebGL2RenderingContext with the viewport already set to the backing
  store. :context-attrs (e.g. {:antialias false}) is read once, at creation.
  :fallback is static plain hiccup (no components, :on-*, :ref), rendered
  when the browser has no WebGL2. Each component owns one context; browsers
  cap live contexts per page (about 16), and unmount releases it."
  [cname props bindings & more]
  (m/draw-def &env "defdraw" :gl false cname props bindings more))

(defmacro defloop
  "Like defdraw, but redrawn every animation frame while mounted and :run? is
  truthy; info also has :t :dt :n. The clock freezes while the canvas can't
  draw (0×0 size, context lost). :fallback and the context rules as defdraw."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :gl true cname props bindings more))
```

In `src/hammer/macros.clj`, add `hammer.gl` to the `facades` set.

- [ ] **Step 5: Backend** `src/hammer/gl.cljs`

```clojure
(ns hammer.gl
  "WebGL2 facade: the event API, defdraw/defloop and mount!. Each component
  owns a WebGL2 context on its canvas. Without WebGL2: :fallback /
  :on-unsupported (logged once per page). On webglcontextlost: :dispose and
  no drawing; on webglcontextrestored: :init again and redraw. Unmount
  releases the context (WEBGL_lose_context)."
  (:require-macros [hammer.gl])
  (:require [hammer.app :as app]
            [hammer.draw :as draw]))

(def reg-event app/reg-event)
(def reg-fx app/reg-fx)
(def dispatch app/dispatch)
(def dispatch-sync app/dispatch-sync)
(def is? app/is?)
(def mount! draw/mount!)

(defonce ^:private logged (volatile! false))

(defn reset-log! "Test hook: the next unsupported context logs again." [] (vreset! logged false))

(defn- fallback! [^draw/State st reason]
  ;; a throwing :on-unsupported or :fallback render is the caller's bug: logged,
  ;; never aborts the mount
  (try
    (let [opts (.-opts st)
          ^js ext (.-ext st)]
      (when-let [f (:on-unsupported opts)] (f reason))
      (when-let [h (:fallback opts)]
        (when-let [render (some-> ext .-render)]
          (let [^js wrap (.-wrap ext)]
            (set! (.-textContent wrap) "")
            (.appendChild wrap (render h))))))
    (catch :default e (js/console.error "hammer: gl fallback failed" e))))

(defn- unsupported! [st reason]
  (when-not @logged
    (vreset! logged true)
    (js/console.error "hammer: WebGL2 unavailable:" reason))
  (fallback! st reason))

(draw/register-backend!
 :gl
 (draw/Backend.
  (fn [^draw/State st render]
    (let [^js c (.-canvas st)
          wrap (when render
                 (let [s (js/document.createElement "span")]
                   (.setProperty (.-style s) "display" "contents")
                   (.appendChild s c)
                   s))
          ^js ext #js {:wrap wrap :render render :lost false :onlost nil :onrestored nil}
          ^js g (.getContext c "webgl2" (clj->js (or (:context-attrs (.-opts st)) {})))]
      (set! (.-ext st) ext)
      (if-not g
        (unsupported! st "no WebGL2 context")
        (let [on-lost (fn [^js e]
                        (.preventDefault e)
                        (set! (.-lost ext) true)
                        (draw/dispose! st))
              on-restored (fn [_]
                            (set! (.-lost ext) false)
                            (draw/queue! st))]
          (set! (.-onlost ext) on-lost)
          (set! (.-onrestored ext) on-restored)
          (.addEventListener c "webglcontextlost" on-lost)
          (.addEventListener c "webglcontextrestored" on-restored)
          (set! (.-ctx st) g)))
      (or wrap c)))
  (fn [^draw/State st]
    (let [^js g (.-ctx st)]
      (when (and g (not (.-lost ^js (.-ext st))))
        (.viewport g 0 0 (.-drawingBufferWidth g) (.-drawingBufferHeight g))
        g)))
  ;; the viewport is set before every draw, so a resize needs nothing more
  (fn [_] nil)
  (fn [^draw/State st]
    (let [^js ext (.-ext st)
          ^js c (.-canvas st)
          ^js g (.-ctx st)]
      (when-let [f (some-> ext .-onlost)]
        (.removeEventListener c "webglcontextlost" f)
        (.removeEventListener c "webglcontextrestored" (.-onrestored ext)))
      (when g
        (some-> (.getExtension g "WEBGL_lose_context") (.loseContext)))
      (set! (.-ctx st) nil)))))
```

- [ ] **Step 6: Run the tests.** Run `npm test`: all green (was 178, plus the new gl tests). Check the draw runtime calls `dispose!` before `teardown!` on unmount (it does in `destroy!`), so `:dispose` sees a live context.
- [ ] **Step 7: Commit** `feat: hammer.gl — WebGL2 draw components`.

---

### Task 2: Remove `hammer.gpu` and switch every reference to `hammer.gl`

**Files:**
- Delete: `src/hammer/gpu.clj`, `src/hammer/gpu.cljs`, `test/hammer/gpu_test.cljs`, `test/hammer/fake_gpu.cljs`
- Rename: `size/size/gpu.cljs` → `size/size/gl.cljs`. Port it to `hammer.gl`: one `defdraw` doing `clearColor` + `clear`, plus an `:on-click` event.
- Modify:
  - `shadow-cljs.edn`: `:size-gpu` becomes `:size-gl`.
  - `bb.edn`: the `sizes` forbidden map uses `"hammer/gl.cljs"` in place of `"hammer/gpu.cljs"`, and the build `size-gl` in place of `size-gpu`.
  - `src/hammer/macros.clj`: drop `hammer.gpu` from `facades`.
  - Docstrings in `app.cljs`, `macros.clj`, `draw.cljs`, `dom.cljs`, `core.clj`, `canvas.clj`: mentions of `hammer.gpu`/gpu become `hammer.gl`/gl. The `non-event-on-keys` comment in `draw.cljs` should say `:on-unsupported` is the gl option.
  - `test/hammer/canvas_test.cljs`: the #19 probe uses `hammer.gl/defloop`, and requires `[hammer.gl]` instead of `[hammer.gpu]`.
- [ ] Steps: make the changes. Then `grep -rn "hammer.gpu\|hammer/gpu\|size-gpu\|fake-gpu\|fake_gpu" src test size bb.edn shadow-cljs.edn` must print nothing. `npm test` green, `bb sizes` exit 0 with three lines (dom, canvas, gl). One commit: `refactor: remove hammer.gpu; hammer.gl is the GPU variant`.

---

### Task 3: Example and benchmark scenario

**Files:**
- `examples/gpu` → `examples/gl` (namespace `gl-demo.core`, shadow build `:gl-demo` in place of `:gpu-demo`, dev-http 8291 → `examples/gl/public`, source path `examples/gl/src`, `.gitignore` already covers `examples/*/public/js/`).
  - Content: the labelled swatch (`defdraw`: `clearColor` from a db counter + `clear`, "Colour n" button) and pulse (`defloop`: `clearColor` from `:t`).
  - A third labelled component, "Triangle (defdraw + :init)": compiles a tiny GLSL ES 3.00 program and a vertex buffer in `:init`, deletes them in `:dispose`, and draws one triangle whose colour comes from a db value toggled by a button.
  - `:fallback` text on each component.
- `bench/canvas`: port the `gpu-points` scenario to `gl-points`.
  - hammer: `bench/canvas/src/bench_canvas/gl.cljs`, shadow build `:bench-gl` replacing `:bench-gpu`.
  - vanilla: `bench/canvas/public/vanilla/gl.js` replacing `gpu.js`.
  - Scenario: 100k points drawn with `drawArrays(POINTS)` from one `ARRAY_BUFFER` updated via `bufferSubData` on update. Ops create/update/clear. Identical GL call sequences in both variants.
  - Update `run.mjs` (scenario list, page wiring, the skip logic when no WebGL2), the `bb bench-canvas` forbidden map/builds, and `bench/canvas/README.md`. Drop the WebGPU-specific launch flags. Keep pixel parity if `readPixels` is practical, otherwise mark it `n/a` as before.
- [ ] Steps: implement. Then:
  - `npx shadow-cljs release canvas-demo gl-demo bench-canvas bench-dom bench-gl`: 0 warnings.
  - `node bench/canvas/run.mjs --scenario gl-points --iterations 5` runs.
  - A headless smoke of `examples/gl` in snap Chromium with DEFAULT flags (no Vulkan flags) renders canvases, not the fallback. That's the point of the switch; screenshot it.

  Commit(s).

---

### Task 4: Docs and final verification

- [ ] **README:** "Canvas and WebGPU" becomes "Canvas and WebGL2". Update the options table (`:context-attrs` added; `:fallback`/`:on-unsupported` now for `hammer.gl`), the draw-fn args (`gl` plus viewport), the mounting section, and the example list. Replace the Linux Vulkan note with: "WebGL2 needs no flags; each hammer.gl component owns a context and browsers cap live contexts at about 16 per page".
- [ ] **`.claude/skills/hammer-internals/SKILL.md`:** the files table (`gl.cljs`/`gl.clj`, no `gpu.*`), the draw-components section (context loss/restore, release on unmount).
- [ ] **Base spec** `docs/superpowers/specs/2026-09-27-canvas-gpu-draw-design.md`: add a note at the top saying the WebGPU parts are superseded by `2026-09-28-webgl2-backend-design.md`.
- [ ] **`bench/canvas/RESULTS.md`:** add a short note that `gpu-points` was replaced by `gl-points`, with the new `gl-points` table from one run (5 iterations is fine; mark it as a single run).
- [ ] **Verify:** `npm test` green, `bb sizes` exit 0, all builds 0 warnings. Commit.
