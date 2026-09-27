# Canvas 2D and WebGPU draw components: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Task graph:** `.blend/specs/2026-09-27-canvas-gpu-draw-tasks.edn`. Whenever you tick a task's checkboxes, also set that task node's `:state`: `:in-progress` when you start it, `:done` once its review is clean, or `:blocked` plus `:reason "…"`.

**Goal:** Add `defdraw` / `defloop` immediate-mode draw components for Canvas 2D (`hammer.canvas`) and WebGPU (`hammer.gpu`), embeddable in DOM apps and mountable on a bare canvas, with three bundles that provably don't contain each other's code.

**Architecture:** The shared runtime (events, trie, cells, scheduler) is unchanged except for per-component runners. Binding compilation moves to `hammer.macros` so the new macros reuse `defc`'s bindings. `hammer.draw` holds the draw runtime (runner, frame queue and loop, canvas host). The two variant namespaces plug in a backend. `hammer.dom` embeds draw components through a generic host hook and never requires them.

**Tech Stack:** ClojureScript, shadow-cljs 3.5.3 (`npm test` = node-test with jsdom), babashka tasks (`bb.edn`).

**Spec:** `docs/superpowers/specs/2026-09-27-canvas-gpu-draw-design.md`. Read it first.

> **Historical note (after implementation):** this plan is kept as written. Names changed during implementation: `hammer.draw/run!` in the code below is `hammer.draw/run-host!` in `src/hammer/draw.cljs` (renamed so it no longer shadows `cljs.core/run!`). The source and `.claude/skills/hammer-internals/SKILL.md` are authoritative.

## Global Constraints

- Base: branch `canvas/draw` (from `perf2/slim`), worktree `/home/soeren/repos/private/hammer-perf/canvas-draw`.
- `hammer.core`'s public API and behaviour stay unchanged (`reg-event reg-fx dispatch dispatch-sync defc is? mount!`).
- No new runtime dependencies (npm or maven). WebGPU only, with no WebGL fallback.
- `hammer.core`, `hammer.canvas` and `hammer.gpu` must not require each other; `hammer.dom` must not require `hammer.draw`.
- `defloop` `:dt` defaults to a cap of `100` ms (`:max-dt`).
- Draws happen only in `hammer.draw/frame!` (an animation frame), never in the scheduler's microtask.
- `bb loc` is report-only. `npm test` must stay green after every task, todomvc included.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh
  ```

## Review Focus

1. **Unmount while a frame is queued:** changing the db, then unmounting before the frame, must not draw into the detached canvas or throw. Test in Task 3.
2. **A `defloop` whose draw fn throws every frame:** the loop keeps running (`:n` keeps growing) and other components still draw. Test in Task 4.
3. **Zero-size canvas** (auto-sized box at 0×0, e.g. `display:none`): no draw call, and no WebGPU `getCurrentTexture` on a 0-size canvas. Drawing resumes when the size becomes positive. Test in Task 6.
4. **`mount!` twice on the same canvas** (hot reload): the first instance's `:dispose` runs once, event listeners are not duplicated, and only the new component draws. Tests in Task 3 (dispose) and Task 5 (listeners).
5. **The same draw component mounted twice:** independent state, props and `:init` resources, and two loops share one pending animation frame. Tests in Task 3 and Task 4.

---

### Task 1: Shared macro helpers and the `hammer.app` event API

**Files:**
- Create: `src/hammer/macros.clj`, `src/hammer/app.cljs`, `test/hammer/app_test.cljs`
- Modify: `src/hammer/core.clj` (remove the moved helpers, require `hammer.macros`), `src/hammer/core.cljs` (re-export from `hammer.app`)

**Interfaces:**
- Produces (clj): `hammer.macros/deps-of [slots form] → [int]`, `is?-form? [env init] → bool`, `lit? [x] → bool`, `binding-spec [env slots pair] → spec-map form`, `check-slots! [macro-name cname props bindings]` (throws `"<macro-name>: …"`), `draw-def` (added in Task 3).
- Produces (cljs): `hammer.app/reg-event reg-fx dispatch dispatch-sync is?`. `is?` is recognized as a binding init when written unqualified, as `hammer.core/is?`, `hammer.app/is?`, `hammer.canvas/is?` or `hammer.gpu/is?`, or through an alias of one of those.

- [ ] **Step 1: Write the failing test** `test/hammer/app_test.cljs`

```clojure
(ns hammer.app-test
  (:require [cljs.test :refer [deftest is]]
            [hammer.test-env]
            [hammer.app :as app]
            [hammer.core :as core :refer [defc]]
            [hammer.cells :as cells]))

(deftest core-reexports-app
  (is (identical? app/reg-event core/reg-event))
  (is (identical? app/reg-fx core/reg-fx))
  (is (identical? app/dispatch core/dispatch))
  (is (identical? app/dispatch-sync core/dispatch-sync))
  (is (identical? app/is? core/is?)))

(defc via-app-alias [id] [on? (app/is? [:sel] id)] [:i (str on?)])

(deftest is?-through-app-alias
  (is (= [:eq] (map #(.-kind ^cells/Spec %) (.-specs via-app-alias)))))
```

- [ ] **Step 2: Run it and check it fails**

Run: `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|No such namespace|failures"`
Expected: compile error `No such namespace: hammer.app`.

- [ ] **Step 3: Create `src/hammer/macros.clj`.** Move `deps-of`, `is?-form?`, `lit?`, `slot-fn` and `binding-spec` from `core.clj` unchanged, except that they become public and `is?-form?` accepts all facades:

```clojure
(ns hammer.macros
  "Binding compilation shared by defc (hammer.core) and defdraw/defloop
  (hammer.canvas, hammer.gpu).")

(def ^:private facades '#{hammer.core hammer.app hammer.canvas hammer.gpu})

(defn deps-of
  "Indices of the slots named anywhere in form (metadata included)."
  [slots form]
  (let [used (set (filter symbol? (tree-seq coll? #(concat (seq %) (meta %)) form)))]
    (vec (keep-indexed (fn [i s] (when (used s) i)) slots))))

(defn is?-form?
  "True for (is? ...), or (q/is? ...) where q is a hammer facade namespace or
  an alias of one in the ns requires. Decided by symbol, not by resolution."
  [env init]
  (and (seq? init)
       (symbol? (first init))
       (= "is?" (name (first init)))
       (let [q (namespace (first init))]
         (or (nil? q)
             (contains? facades (symbol q))
             (contains? facades (get-in env [:ns :requires (symbol q)]))))))

(defn lit? [x] (or (string? x) (number? x) (keyword? x) (boolean? x) (nil? x)))

(defn slot-fn
  "fn of the dep slots returning form; a vector of literals (a constant path)
  is built once and shared by every call."
  [args form]
  (if (and (vector? form) (every? lit? form))
    `(let [p# ~form] (fn ~args p#))
    `(fn ~args ~form)))

(defn binding-spec
  "slots: props and earlier binding names visible to this init. An :eq spec
  has :f for the path and :g for the compared value."
  [env slots [_ init]]
  (let [deps (deps-of slots init)
        args (mapv slots deps)
        eq? (is?-form? env init)]
    (when (and eq? (not= 3 (count init)))
      (throw (ex-info "defc: is? takes a path and a value" {:form init})))
    (cond
      eq? `{:kind :eq :deps ~deps :f ~(slot-fn args (nth init 1)) :g (fn ~args ~(nth init 2))}
      (vector? init) `{:kind :path :deps ~deps :f ~(slot-fn args init)}
      :else `{:kind :expr :deps ~deps :f (fn ~args ~init)})))

(defn check-slots!
  "The binding checks shared by every component macro; messages start with macro."
  [macro cname props bindings]
  (let [slots (into (vec props) (map first (partition 2 bindings)))]
    (when (odd? (count bindings))
      (throw (ex-info (str macro ": bindings need an even number of forms") {:name cname})))
    (when-not (every? simple-symbol? slots)
      (throw (ex-info (str macro ": props and binding names must be plain symbols") {:name cname})))
    (when-not (= (count slots) (count (set slots)))
      (throw (ex-info (str macro ": duplicate prop or binding name") {:name cname :slots slots})))))
```

- [ ] **Step 4: Update `src/hammer/core.clj`.**
  - Change the ns form to `(ns hammer.core (:require [clojure.string :as str] [clojure.walk :as walk] [hammer.macros :as m]))`.
  - Delete the local `deps-of`, `is?-form?`, `lit?`, `slot-fn` and `binding-spec`.
  - Replace every remaining `lit?` with `m/lit?`, `deps-of` with `m/deps-of`, and `binding-spec` with `m/binding-spec`.
  - In `defc`, replace the three `when` checks with `(m/check-slots! "defc" cname props bindings)`.

  Verify: `grep -n "defn- deps-of\|defn- lit?\|defn- binding-spec\|(lit? \|(deps-of \|(binding-spec " src/hammer/core.clj` prints nothing.

- [ ] **Step 5: Create `src/hammer/app.cljs` and point `core.cljs` at it**

```clojure
(ns hammer.app
  "The event API shared by every variant facade (hammer.core, hammer.canvas, hammer.gpu)."
  (:require [hammer.events :as events]))

(def reg-event events/reg-event)
(def reg-fx events/reg-fx)
(def dispatch events/dispatch)
(def dispatch-sync events/dispatch-sync)

(defn is?
  "Only valid as a whole binding init of defc/defdraw/defloop: (is? path v) is
  true iff the db value at path is = to v. The macros rewrite the form; calling
  it throws."
  [_path _v]
  (throw (js/Error. "hammer: is? is only valid as a whole binding init")))
```

In `src/hammer/core.cljs`, require `[hammer.app :as app]` instead of `[hammer.events :as events]`. Replace the four `def`s and the `is?` defn with `(def reg-event app/reg-event)`, `(def reg-fx app/reg-fx)`, `(def dispatch app/dispatch)`, `(def dispatch-sync app/dispatch-sync)` and `(def is? app/is?)`. Keep `mount!`: it still needs `events/set-db!`, so keep `[hammer.events :as events]` as well.

- [ ] **Step 6: Run the tests and check they pass**

Run: `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|failures"`
Expected: `0 failures, 0 errors`. The test count rises by 2. The `defc: …` messages in `defc_test` still match.

- [ ] **Step 7: Commit**

```bash
git add src/hammer/macros.clj src/hammer/app.cljs src/hammer/core.clj src/hammer/core.cljs test/hammer/app_test.cljs
git commit -m "refactor: share binding compilation (hammer.macros) and the event API (hammer.app)" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 2: Per-component runners and the DOM host hook

**Files:**
- Modify: `src/hammer/cells.cljs` (`Host`, `Comp` gains `host`, dispatching runner), `src/hammer/dom.cljs` (register its runner via cells, handle hosted `:comp` vnodes)
- Test: `test/hammer/host_test.cljs`

**Interfaces:**
- Produces: `(deftype hammer.cells/Host [run create destroy])`.
  - `run: (fn [inst])` is called by the scheduler when the instance is dirty, and by `hammer.dom` after a prop change. It must clear `(.-dirty inst)`.
  - `create: (fn [inst render el] → DOM node)`. `render` is `(fn [hiccup] → node)` (plain hiccup only) or nil. `el` is an existing element to adopt, or nil.
  - `destroy: (fn [inst])` must call `cells/destroy!`.
- Produces: `hammer.cells/component` gains a 6-arity `[cname nprops specs body-deps body host]`; `hammer.cells/host [comp] → Host or nil`; `hammer.cells/set-default-runner! [f]`.
- `hammer.dom`: a `:comp` vnode whose component has a host stores the host's node in `(.-el vnode)`.

- [ ] **Step 1: Write the failing test** `test/hammer/host_test.cljs`

```clojure
(ns hammer.host-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.cells :as cells]
            [hammer.core :refer [defc]]
            [hammer.dom :as dom]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(def log (atom []))

(def fake
  "A hosted component with one prop and one path binding [:v]."
  (cells/component
   "fake" 1 [{:kind :path :deps [] :f (fn [] [:v])}] [0 1] (fn [p v] [p v])
   (cells/Host.
    (fn [inst] (set! (.-dirty inst) false) (cells/refresh! inst)
      (swap! log conj [:run (vec (.-vals inst))]))
    (fn [inst _render _el]
      (swap! log conj [:create (vec (.-vals inst))])
      (let [n (js/document.createElement "i")]
        (set! (.-textContent n) (str (aget (.-vals inst) 0)))
        n))
    (fn [inst] (swap! log conj [:destroy (aget (.-vals inst) 0)]) (cells/destroy! inst)))))

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defc holder [] [ids [:ids]]
  [:ul (for [id ids] ^{:key id} [fake id])])

(use-fixtures :each {:before (fn [] (t/reset-app!) (reset! log []))})

(deftest hosted-component-lifecycle
  (reset! state/app-db {:ids [1 2 3] :v :a})
  (let [el (js/document.createElement "div")]
    (dom/mount! [holder] el)
    (is (= [[:create [1 :a]] [:create [2 :a]] [:create [3 :a]]] @log))
    (is (= "<ul><i>1</i><i>2</i><i>3</i></ul>" (.-innerHTML el)))
    (let [[n1 _ n3] (js/Array.from (.. el -firstChild -children))]
      (reset! log [])
      (events/dispatch-sync [::set :v :b])
      (is (= #{[:run [1 :b]] [:run [2 :b]] [:run [3 :b]]} (set @log)) "path change runs the host")
      (reset! log [])
      (events/dispatch-sync [::set :ids [3 1]])
      (is (= [[:destroy 2]] @log))
      (is (= [n3 n1] (vec (js/Array.from (.. el -firstChild -children)))) "keyed move keeps host nodes"))))
```

- [ ] **Step 2: Run it and check it fails**

Run: `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|failures|host"`
Expected: a compile warning or error that `cells/Host` is undefined, or a failure in `hosted-component-lifecycle`.

- [ ] **Step 3: Implement it in `cells.cljs`**

```clojure
;; A component that renders itself (e.g. a canvas) instead of through hammer.dom.
;; run: (fn [inst]) on marks and prop changes, must clear (.-dirty inst);
;; create: (fn [inst render el] → node); destroy: (fn [inst]) calls destroy!.
(deftype Host [run create destroy])

(deftype Comp [cname nprops specs body-deps body ^:mutable renders host])
```

Change `component` to two arities:

```clojure
(defn component
  "Built by defc (no host) or defdraw/defloop (with a Host). specs: one
  {:kind :path|:eq|:expr, :deps [slot], :f fn} per binding (an :eq f returns
  the path, its :g the compared value); body-deps: the slots the body names."
  ([cname nprops specs body-deps body] (component cname nprops specs body-deps body nil))
  ([cname nprops specs body-deps body host]
   (Comp. cname nprops
          (to-array (map (fn [{:keys [kind deps f g]}] (Spec. kind (to-array deps) f g)) specs))
          (to-array body-deps) body 0 host)))

(defn host "The Host of component c, or nil." [^Comp c] (.-host c))
```

At the end of `cells.cljs`, add:

```clojure
(defonce ^:private default-run (volatile! nil))

(defn set-default-runner!
  "f runs dirty instances whose component has no Host (hammer.dom registers it)."
  [f]
  (vreset! default-run f))

(sched/set-runner!
 (fn [^Instance inst]
   (if-let [^Host h (.-host ^Comp (.-comp inst))]
     ((.-run h) inst)
     (when-let [r @default-run] (r inst)))))
```

- [ ] **Step 4: Implement it in `dom.cljs`**
  - Replace `(sched/set-runner! (fn [^cells/Instance inst] …))` at the end with `(cells/set-default-runner! (fn [^cells/Instance inst] …))`, keeping the same body.
  - Add `host-render` to the `(declare …)` list, and define it after `create!`:

```clojure
(defn- host-render
  "Given to Host create: builds plain hiccup (no components) into a node."
  [hiccup]
  (create! (normalize hiccup) 0))
```

  - `node-of`:

```clojure
(defn- node-of [^VNode v]
  (if (and (keyword-identical? (.-t v) :comp) (nil? (.-el v)))
    (node-of (.-vnode ^cells/Instance (.-inst v)))
    (.-el v)))
```

  - `create!`, the `:comp` branch:

```clojure
    :comp (let [c (.-comp v)
                inst (cells/create c (.-args v) 1 (inc depth))]
            (set! (.-inst v) inst)
            (if-let [^cells/Host h (cells/host c)]
              (let [n ((.-create h) inst host-render nil)]
                (set! (.-el v) n)
                n)
              (mount-inst! inst)))
```

  - `unmount!`, the `:comp` branch:

```clojure
    :comp (let [inst (.-inst v)]
            (if-let [^cells/Host h (cells/host (.-comp v))]
              ((.-destroy h) inst)
              (do (unmount! (.-vnode ^cells/Instance inst))
                  (cells/destroy! inst))))
```

  - `patch!`, the `:comp` branch:

```clojure
      :comp (let [inst (.-inst old)]
              (set! (.-inst nu) inst)
              (set! (.-el nu) (.-el old))
              (when (cells/set-props! inst (.-args nu) 1)
                (if-let [^cells/Host h (cells/host (.-comp nu))]
                  ((.-run h) inst)
                  (update-inst! inst))))
```

- [ ] **Step 5: Run the tests and check they pass**

Run: `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|failures"`
Expected: all green, including `hosted-component-lifecycle`.

- [ ] **Step 6: Commit**

```bash
git add src/hammer/cells.cljs src/hammer/dom.cljs test/hammer/host_test.cljs
git commit -m "feat: per-component runners and a host hook for self-rendering components" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 3: `hammer.draw` runtime and `hammer.canvas/defdraw`

**Files:**
- Create: `src/hammer/draw.cljs`, `src/hammer/canvas.clj`, `src/hammer/canvas.cljs`, `test/hammer/fake_canvas.cljs`, `test/hammer/canvas_test.cljs`
- Modify: `src/hammer/macros.clj` (add `draw-def`), `src/hammer/testing.cljs` (`frame!`, `use-fake-frames!`, and `reset-app!` also unmounts draw roots)

**Interfaces:**
- Consumes: `cells/Host`, `cells/component` (6-arity), `cells/host`, `cells/refresh!`, `cells/render`, `cells/destroy!`, and `m/check-slots!`, `m/binding-spec`, `m/deps-of` from Tasks 1 and 2.
- Produces: `(hammer.draw/component cname nprops specs body-deps body kind loop?)` (emitted by the macros). The body returns `#js [opts draw-fn]`.
- Produces: `(deftype hammer.draw/Backend [setup! draw-arg resized! teardown!])`.
  - `setup!: (fn [st render] → node)`
  - `draw-arg: (fn [st] → first draw-fn arg, or nil to skip)`
  - `resized!: (fn [st])`
  - `teardown!: (fn [st])`
- Produces: `hammer.draw/register-backend! [kind backend]`, `mount!`, `unmount-all!`, `frame! [ts]`, `set-raf! [f]`, `queue! [st]`, `dispose! [st]`, `states [kind] → js array`.
- Produces: `hammer.draw/State` fields read by backends: `canvas`, `ctx` (mutable), `dpr`, `w`, `h`, `opts`, `ext` (mutable, backend-private).
- Produces: `hammer.canvas/defdraw` and `defloop` (macros), plus `hammer.canvas/reg-event reg-fx dispatch dispatch-sync is? mount!`.
- Produces: `hammer.testing/frame! [ms]` and `use-fake-frames! []`.

- [ ] **Step 1: The recording fake context** `test/hammer/fake_canvas.cljs`

```clojure
(ns hammer.fake-canvas
  "jsdom has no canvas contexts: getContext(\"2d\") returns a recording fake."
  (:require [hammer.test-env]))

(def ops-2d ["clearRect" "fillRect" "setTransform" "beginPath" "arc" "fill" "stroke"])

(defonce log (atom []))

(defn- fake-2d [^js canvas]
  (let [o #js {:canvas canvas}]
    (doseq [m ops-2d]
      (aset o m (fn [& args] (swap! log conj (into [(keyword m) (.-id canvas)] args)))))
    o))

(defonce installed
  (let [proto (.. js/window -HTMLCanvasElement -prototype)
        orig (.-getContext proto)]
    (set! (.-getContext proto)
          (fn [kind]
            (this-as ^js canvas
              (if (= kind "2d")
                (or (.-__fake2d canvas)
                    (let [o (fake-2d canvas)] (set! (.-__fake2d canvas) o) o))
                (.call orig canvas kind)))))
    true))

(defn ops
  "Recorded calls on the canvas with id, as [op & args]."
  [id]
  (into [] (comp (filter #(= id (second %))) (map #(into [(first %)] (drop 2 %)))) @log))
```

- [ ] **Step 2: Write the failing tests** `test/hammer/canvas_test.cljs`

```clojure
(ns hammer.canvas-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas :as fake]
            [hammer.core :as core :refer [defc]]
            [hammer.canvas :as cv :refer [defdraw]]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (reset! fake/log []))})

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defn- div [] (js/document.createElement "div"))
(defn- fills [id] (filterv #(= :fillRect (first %)) (fake/ops id)))

(defdraw bars [color id]
  [n [:n]]
  {:size [100 50] :attrs {:id id}}
  (fn [ctx {:keys [h]}]
    (set! (.-fillStyle ctx) color)
    (.fillRect ctx 0 0 n h)))

(deftest draws-once-per-frame-and-only-on-change
  (reset! state/app-db {:n 1})
  (cv/mount! [bars "red" "b"] (div))
  (is (empty? @fake/log) "nothing before the frame")
  (t/frame! 16)
  (is (= [[:setTransform 1 0 0 1 0 0] [:fillRect 0 0 1 50]] (fake/ops "b")))
  (reset! fake/log [])
  (events/dispatch [::set :n 5])
  (events/dispatch [::set :n 7])
  (t/frame! 32)
  (is (= [[:fillRect 0 0 7 50]] (fills "b")) "two events, one draw, latest value")
  (reset! fake/log [])
  (t/frame! 48)
  (is (empty? @fake/log) "no change, no redraw"))

(deftest backing-store-follows-size-and-dpr
  (set! (.-devicePixelRatio js/globalThis) 2)
  (try
    (reset! state/app-db {:n 1})
    (let [host (div)]
      (cv/mount! [bars "red" "b"] host)
      (t/frame! 16)
      (let [c (.-firstChild host)]
        (is (= "CANVAS" (.-tagName c)))
        (is (= [200 100] [(.-width c) (.-height c)]))
        (is (= ["100px" "50px"] [(.. c -style -width) (.. c -style -height)]))
        (is (= [:setTransform 2 0 0 2 0 0] (first (fake/ops "b"))))))
    (finally (js-delete js/globalThis "devicePixelRatio"))))

(deftest two-instances-are-independent
  (reset! state/app-db {:n 3})
  (let [a (div) b (div)]
    (cv/mount! [bars "red" "a"] a)
    (cv/mount! [bars "blue" "b2"] b)
    (t/frame! 16)
    (is (= [[:fillRect 0 0 3 50]] (fills "a") (fills "b2")))
    (is (= "red" (.-fillStyle (.getContext (.-firstChild a) "2d"))))
    (is (= "blue" (.-fillStyle (.getContext (.-firstChild b) "2d"))))))

(deftest unmount-before-frame-draws-nothing
  (reset! state/app-db {:n 1})
  (let [host (div)]
    (cv/mount! [bars "red" "u"] host)
    (t/frame! 16)
    (reset! fake/log [])
    (events/dispatch [::set :n 9])
    (t/flush!)
    (t/reset-app!)
    (t/frame! 32)
    (is (empty? @fake/log))))

(def calls (atom []))

(defdraw boom [] [n [:n]] {:size [10 10] :attrs {:id "boom"}}
  (fn [_ _] (swap! calls conj n) (when (= n 2) (throw (js/Error. "boom")))))

(deftest a-throwing-draw-is-logged-and-retried
  (reset! state/app-db {:n 1})
  (reset! calls [])
  (let [orig js/console.error errs (atom 0)]
    (set! js/console.error (fn [& _] (swap! errs inc)))
    (try
      (cv/mount! [boom] (div))
      (t/frame! 16)
      (events/dispatch [::set :n 2]) (t/frame! 32)
      (events/dispatch [::set :n 3]) (t/frame! 48)
      (is (= [1 2 3] @calls))
      (is (= 1 @errs))
      (finally (set! js/console.error orig)))))

(def res-log (atom []))

(defdraw with-res [] [n [:n]]
  {:size [10 10]
   :init (fn [_ctx {:keys [w]}] (swap! res-log conj [:init w]) {:r n})
   :dispose (fn [r] (swap! res-log conj [:dispose r]))}
  (fn [_ctx _info res] (swap! res-log conj [:draw res n])))

(deftest init-runs-once-dispose-on-remount
  (reset! state/app-db {:n 1})
  (reset! res-log [])
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [with-res] c)
    (t/frame! 16)
    (events/dispatch [::set :n 2]) (t/frame! 32)
    (is (= [[:init 10] [:draw {:r 1} 1] [:draw {:r 1} 2]] @res-log))
    (reset! res-log [])
    (cv/mount! [with-res] c)                ; same canvas again (hot reload)
    (t/frame! 48)
    (is (= [[:dispose {:r 1}] [:init 10] [:draw {:r 2} 2]] @res-log))))

(defdraw dot [id] [on? (cv/is? [:sel] id)] {:size [10 10] :attrs {:id (str "d" id)}}
  (fn [ctx _] (set! (.-fillStyle ctx) (if on? "red" "gray")) (.fillRect ctx 0 0 10 10)))

(defc dots [] [ids [:ids]] [:div (for [id ids] ^{:key id} [dot id])])

(deftest embedded-in-defc-keyed-and-is?
  (reset! state/app-db {:ids [1 2 3] :sel nil})
  (let [host (div)]
    (core/mount! [dots] host)
    (t/frame! 16)
    (is (= 3 (.-length (.querySelectorAll host "canvas"))))
    (reset! fake/log [])
    (events/dispatch [::set :sel 2])
    (t/frame! 32)
    (is (= #{"d2"} (set (map second @fake/log))) "only the dot whose is? flipped redraws")
    (let [[c1 _ c3] (js/Array.from (.querySelectorAll host "canvas"))]
      (events/dispatch [::set :ids [3 1]])
      (t/frame! 48)
      (is (= [c3 c1] (vec (js/Array.from (.querySelectorAll host "canvas"))))))))
```

- [ ] **Step 3: Run them and check they fail**

Run: `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|No such namespace|failures"`
Expected: `No such namespace: hammer.canvas`.

- [ ] **Step 4: Add `draw-def` to `src/hammer/macros.clj`**

```clojure
(defn draw-def
  "Expansion of defdraw/defloop for backend kind (:canvas or :gpu).
  more is [opts? draw-fn]; opts, when present, is a literal map."
  [env macro kind loop? cname props bindings more]
  (check-slots! macro cname props bindings)
  (let [[opts draw] (case (count more)
                      1 [nil (first more)]
                      2 (if (map? (first more))
                          more
                          (throw (ex-info (str macro ": opts must be a literal map") {:name cname})))
                      (throw (ex-info (str macro ": expected [props] [bindings] opts? draw-fn") {:name cname})))
        pairs (partition 2 bindings)
        slots (into (vec props) (map first pairs))]
    `(def ~cname
       (hammer.draw/component
        ~(str cname)
        ~(count props)
        ~(vec (map-indexed (fn [j pair]
                             (binding-spec env (subvec slots 0 (+ (count props) j)) pair))
                           pairs))
        ~(deps-of slots [opts draw])
        (fn ~slots (cljs.core/array ~opts ~draw))
        ~kind
        ~loop?))))
```

- [ ] **Step 5: Create `src/hammer/draw.cljs`**

```clojure
(ns hammer.draw
  "Runtime shared by draw components (hammer.canvas, hammer.gpu): the draw
  runner, the frame queue and loop, and the canvas host (size, DPR, attrs,
  events). A Backend supplies the drawing context. A draw instance keeps its
  State in the instance's vnode field."
  (:require [clojure.string :as str]
            [hammer.cells :as cells]
            [hammer.events :as events]))

;; setup!: (fn [st render] → node) once the canvas exists; draw-arg: (fn [st])
;; → first draw-fn arg, nil to skip this draw; resized!: (fn [st]) after the
;; backing store changed; teardown!: (fn [st]) on unmount.
(deftype Backend [setup! draw-arg resized! teardown!])

(defonce ^:private backends #js {})

(defn register-backend! [kind ^Backend b] (aset backends (name kind) b))

(deftype State [inst backend kind loop? canvas order
                ^:mutable opts ^:mutable f ^:mutable ctx ^:mutable res ^:mutable inited
                ^:mutable w ^:mutable h ^:mutable dpr
                ^:mutable t ^:mutable last ^:mutable n ^:mutable running
                listeners ^:mutable observer ^:mutable attrs ^:mutable alive ^:mutable ext])

(defonce ^:private seq-no (volatile! 0))
(defonce ^:private all (js/Set.))      ; alive states
(defonce ^:private queued (js/Set.))   ; states to draw at the next frame
(defonce ^:private loops (js/Set.))    ; mounted defloop states

(deftype Clock [^:mutable pending ^:mutable raf])
(defonce ^:private clock (Clock. false nil))

(declare frame!)

(defn- cname [^State st] (.-cname ^cells/Comp (.-comp ^cells/Instance (.-inst st))))

(defn- request! []
  (when-not (.-pending clock)
    (set! (.-pending clock) true)
    (if-let [r (.-raf clock)] (r frame!) (js/requestAnimationFrame frame!))))

(defn set-raf!
  "Test hook: f replaces requestAnimationFrame (called with the frame fn); nil restores it."
  [f]
  (set! (.-raf clock) f)
  (set! (.-pending clock) false))

(defn queue! "Draws st at the next frame." [^State st] (.add queued st) (request!))

(defn states "Alive states of backend kind." [kind]
  (.filter (js/Array.from all) (fn [^State st] (keyword-identical? kind (.-kind st)))))

;; ---- canvas host: size, attrs, events

(defn- set-css! [^js el k v] (.setProperty (.-style el) k v))

(defn- observe!
  "Auto size: the canvas fills its CSS box and follows it."
  [^State st]
  (let [^js c (.-canvas st)]
    (set-css! c "display" "block")
    (set-css! c "width" "100%")
    (set-css! c "height" "100%")
    (set! (.-w st) (.-clientWidth c))
    (set! (.-h st) (.-clientHeight c))
    (set! (.-observer st)
          (if (exists? js/ResizeObserver)
            (doto (js/ResizeObserver.
                   (fn [^js entries]
                     (when-not (:size (.-opts st))
                       (let [r (.-contentRect (aget entries 0))]
                         (set! (.-w st) (.-width r))
                         (set! (.-h st) (.-height r))
                         (queue! st)))))
              (.observe c))
            #js {:disconnect (fn [])}))))

(defn- apply-size! [^State st]
  (if-let [[w h] (:size (.-opts st))]
    (do (set! (.-w st) w)
        (set! (.-h st) h)
        (set-css! (.-canvas st) "width" (str w "px"))
        (set-css! (.-canvas st) "height" (str h "px")))
    (when-not (.-observer st) (observe! st))))

(defn- apply-attrs! [^State st]
  (let [^js c (.-canvas st)
        old (.-attrs st)
        nu (:attrs (.-opts st))]
    (when-not (identical? old nu)
      (doseq [[k v] nu :when (not= v (get old k))]
        (case k
          :class (set! (.-className c) (if (coll? v) (str/join " " (remove nil? v)) (str v)))
          :style (doseq [[sk sv] v] (set-css! c (name sk) (str sv)))
          (if (nil? v) (.removeAttribute c (name k)) (.setAttribute c (name k) (str v)))))
      (doseq [[k _] old :when (not (contains? nu k))]
        (case k
          :class (set! (.-className c) "")
          :style nil
          (.removeAttribute c (name k))))
      (set! (.-attrs st) nu))))

(defn- local-xy [^js c ^js e]
  (when (number? (.-clientX e))
    (let [r (.getBoundingClientRect c)]
      [(- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r))])))

(defn- handle! [^State st k ^js e]
  (when-let [h (get (.-opts st) k)]
    (let [xy (local-xy (.-canvas st) e)]
      (cond
        (vector? h) (events/dispatch (if xy (into h xy) h))
        (fn? h) (h e (when xy {:x (nth xy 0) :y (nth xy 1)}))))))

(defn- sync-listeners!
  "One listener per :on-<type> key of opts; listeners map keyed by type string."
  [^State st]
  (let [^js ls (.-listeners st)
        ^js c (.-canvas st)
        want (into #{} (filter #(str/starts-with? (name %) "on-")) (keys (.-opts st)))]
    (doseq [k want
            :let [t (subs (name k) 3)]
            :when (not (.has ls t))]
      (let [f (fn [e] (handle! st k e))]
        (.set ls t f)
        (.addEventListener c t f)))
    (.forEach ls (fn [f t]
                   (when-not (contains? want (keyword (str "on-" t)))
                     (.removeEventListener c t f)
                     (.delete ls t))))))

;; ---- drawing

(defn- sync-size! [^State st]
  (let [dpr (or (.-devicePixelRatio js/globalThis) 1)
        ^js c (.-canvas st)
        bw (js/Math.ceil (* (.-w st) dpr))
        bh (js/Math.ceil (* (.-h st) dpr))]
    (set! (.-dpr st) dpr)
    (when (or (not= bw (.-width c)) (not= bh (.-height c)))
      (set! (.-width c) bw)
      (set! (.-height c) bh)
      ((.-resized! ^Backend (.-backend st)) st))))

(defn- advance!
  "Loop timing for a running loop at frame time ts; returns dt (0 otherwise)."
  [^State st ts]
  (if (and (.-loop? st) (.-running st))
    (let [dt (if (nil? (.-last st)) 0 (min (get (.-opts st) :max-dt 100) (- ts (.-last st))))]
      (set! (.-last st) ts)
      (set! (.-t st) (+ (.-t st) dt))
      (set! (.-n st) (inc (.-n st)))
      dt)
    0))

(defn- info [^State st dt]
  (let [m {:w (.-w st) :h (.-h st) :dpr (.-dpr st)}]
    (if (.-loop? st) (assoc m :t (.-t st) :dt dt :n (.-n st)) m)))

(defn- init! [^State st arg i]
  (if-let [init (:init (.-opts st))]
    (try
      (set! (.-res st) (init arg i))
      (set! (.-inited st) true)
      (catch :default e
        (js/console.error "hammer: init failed in" (cname st) e)
        (set! (.-inited st) :failed)))
    (set! (.-inited st) true)))

(defn dispose!
  "Runs :dispose for an initialized state and marks it for a new :init."
  [^State st]
  (when (true? (.-inited st))
    (when-let [d (:dispose (.-opts st))]
      (try (d (.-res st))
           (catch :default e (js/console.error "hammer: dispose failed in" (cname st) e)))))
  (set! (.-res st) nil)
  (set! (.-inited st) false))

(defn- draw! [^State st ts]
  (when (.-alive st)
    (let [dt (advance! st ts)]
      (sync-size! st)
      (let [f (.-f st)
            arg (when (and f (pos? (.-w st)) (pos? (.-h st)))
                  ((.-draw-arg ^Backend (.-backend st)) st))]
        (when arg
          (let [i (info st dt)]
            (when (false? (.-inited st)) (init! st arg i))
            (when (true? (.-inited st))
              (try
                (if (contains? (.-opts st) :init) (f arg i (.-res st)) (f arg i))
                (catch :default e
                  (js/console.error "hammer: draw failed in" (cname st) e))))))))))

(defn- some-running? []
  (let [r (volatile! false)]
    (.forEach loops (fn [^State st] (when (.-running st) (vreset! r true))))
    @r))

(defn frame!
  "One animation frame at time ts (ms): draws every queued state and every
  running loop once, in mount order, then requests another frame while a loop
  runs."
  [ts]
  (set! (.-pending clock) false)
  (let [s (js/Set. queued)]
    (.clear queued)
    (.forEach loops (fn [^State st] (when (.-running st) (.add s st))))
    (.forEach (.sort (js/Array.from s) (fn [^State a ^State b] (- (.-order a) (.-order b))))
              (fn [st] (draw! st ts))))
  (when (or (pos? (.-size queued)) (some-running?)) (request!)))

;; ---- instances

(defn- rerender!
  "Re-evaluates opts and the draw fn from the instance's current bindings."
  [^State st]
  (try
    (let [out (cells/render (.-inst st))]
      (set! (.-opts st) (or (aget out 0) {}))
      (set! (.-f st) (aget out 1)))
    (catch :default e (js/console.error "hammer: render failed in" (cname st) e)))
  (apply-size! st)
  (apply-attrs! st)
  (sync-listeners! st)
  (when (.-loop? st)
    (let [r (boolean (get (.-opts st) :run? true))]
      (when-not r (set! (.-last st) nil))
      (set! (.-running st) r)))
  (queue! st))

(defn- run!
  "Host run: recompute bindings; if the draw fn or opts depend on a change,
  re-evaluate them and queue a draw."
  [^cells/Instance inst]
  (set! (.-dirty inst) false)
  (when (.-mounted inst)
    (when (try (cells/refresh! inst)
               (catch :default e
                 (js/console.error "hammer: render failed in" (.-cname ^cells/Comp (.-comp inst)) e)
                 false))
      (rerender! (.-vnode inst)))))

(defonce ^:private resize-hooked (volatile! false))

(defn- hook-resize!
  "Window resize (also fired on zoom and DPR changes): redraw everything."
  []
  (when-not @resize-hooked
    (vreset! resize-hooked true)
    (.addEventListener js/window "resize" (fn [_] (.forEach all (fn [st] (queue! st)))))))

(defn- create-host [^cells/Instance inst kind loop? render el]
  (let [backend (aget backends (name kind))]
    (when-not backend
      (throw (js/Error. (str "hammer: no " (name kind) " backend loaded (require hammer." (name kind) ")"))))
    (let [st (State. inst backend kind loop? (or el (js/document.createElement "canvas")) (vswap! seq-no inc)
                     {} nil nil nil false
                     0 0 1
                     0 nil 0 false
                     (js/Map.) nil nil true nil)]
      (set! (.-vnode inst) st)
      (.add all st)
      (when loop? (.add loops st))
      (hook-resize!)
      (rerender! st)
      ((.-setup! ^Backend backend) st render))))

(defn- destroy! [^cells/Instance inst]
  (let [^State st (.-vnode inst)
        ^js c (.-canvas st)]
    (set! (.-alive st) false)
    (.delete all st)
    (.delete queued st)
    (.delete loops st)
    (.forEach (.-listeners st) (fn [f t] (.removeEventListener c t f)))
    (.clear (.-listeners st))
    (some-> ^js (.-observer st) (.disconnect))
    (dispose! st)
    ((.-teardown! ^Backend (.-backend st)) st)
    (cells/destroy! inst)))

(defn component
  "Built by defdraw/defloop: a hammer component drawn by backend kind."
  [cname nprops specs body-deps body kind loop?]
  (cells/component cname nprops specs body-deps body
                   (cells/Host. run!
                                (fn [inst render el] (create-host inst kind loop? render el))
                                destroy!)))

;; ---- standalone mounting

(defonce ^:private roots (js/Map.))

(defn- destroy-inst! [^cells/Instance inst]
  ((.-destroy ^cells/Host (cells/host (.-comp inst))) inst))

(defn mount!
  "Mounts a draw component vector on el: an existing <canvas> is adopted,
  anything else gets a canvas inside. With db, replaces app-db first."
  ([hiccup ^js el]
   (let [c (nth hiccup 0 nil)
         ^cells/Host h (when (cells/component? c) (cells/host c))]
     (when-not h (throw (js/Error. "hammer: mount! takes a draw component vector, e.g. [chart]")))
     (when-let [old (.get roots el)] (destroy-inst! old))
     (let [canvas? (= "CANVAS" (.-tagName el))
           inst (cells/create c hiccup 1 1)]
       (when-not canvas? (set! (.-textContent el) ""))
       (let [n ((.-create h) inst nil (when canvas? el))]
         (when-not canvas? (.appendChild el n)))
       (.set roots el inst))))
  ([hiccup el db]
   (events/set-db! db)
   (mount! hiccup el)))

(defn unmount-all!
  "Unmounts every standalone root."
  []
  (.forEach roots (fn [inst _] (destroy-inst! inst)))
  (.clear roots))
```

- [ ] **Step 6: Create `src/hammer/canvas.clj` and `src/hammer/canvas.cljs`**

```clojure
(ns hammer.canvas
  (:require [hammer.macros :as m]))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn): a Canvas 2D component
  redrawn (at the next animation frame) when a binding its opts or draw-fn name
  changes. draw-fn: (fn [ctx info]) or, with :init, (fn [ctx info res])."
  [cname props bindings & more]
  (m/draw-def &env "defdraw" :canvas false cname props bindings more))

(defmacro defloop
  "Like defdraw, but redrawn every animation frame while mounted and :run? is
  truthy; info also has :t :dt :n."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :canvas true cname props bindings more))
```

```clojure
(ns hammer.canvas
  "Canvas 2D facade: the event API, defdraw/defloop and mount!."
  (:require-macros [hammer.canvas])
  (:require [hammer.app :as app]
            [hammer.draw :as draw]))

(def reg-event app/reg-event)
(def reg-fx app/reg-fx)
(def dispatch app/dispatch)
(def dispatch-sync app/dispatch-sync)
(def is? app/is?)
(def mount! draw/mount!)

(draw/register-backend!
 :canvas
 (draw/Backend.
  (fn [^draw/State st _render]
    (set! (.-ctx st) (.getContext ^js (.-canvas st) "2d"))
    (.-canvas st))
  (fn [^draw/State st]
    (when-let [^js ctx (.-ctx st)]
      (let [d (.-dpr st)] (.setTransform ctx d 0 0 d 0 0))
      ctx))
  (fn [_] nil)
  (fn [_] nil)))
```

- [ ] **Step 7: Update `src/hammer/testing.cljs`.** Add `[hammer.draw :as draw]` to the requires, add these two fns, and make `reset-app!` call `(draw/unmount-all!)` before `(dom/unmount-all!)`:

```clojure
(defn use-fake-frames!
  "Draw components get frames only from frame!, never from requestAnimationFrame."
  []
  (draw/set-raf! (fn [_] nil)))

(defn frame!
  "Flushes pending events and updates, then runs one draw frame at time ms."
  [ms]
  (flush!)
  (draw/frame! ms))
```

- [ ] **Step 8: Run the tests and check they pass**

Run: `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|failures"`
Expected: all green, including the 7 new `hammer.canvas-test` tests.

- [ ] **Step 9: Commit**

```bash
git add src/hammer/macros.clj src/hammer/draw.cljs src/hammer/canvas.clj src/hammer/canvas.cljs src/hammer/testing.cljs test/hammer/fake_canvas.cljs test/hammer/canvas_test.cljs
git commit -m "feat: hammer.canvas defdraw on a shared draw runtime" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 4: `defloop`

**Files:**
- Test: `test/hammer/loop_test.cljs`. The implementation already exists from Task 3 (`advance!`, `:run?`, loops set). This task proves it and fixes any gaps.

**Interfaces:**
- Consumes: `hammer.canvas/defloop`, `hammer.testing/frame!`, `use-fake-frames!`, `hammer.draw/set-raf!`.

- [ ] **Step 1: Write the tests** `test/hammer/loop_test.cljs`

```clojure
(ns hammer.loop-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas :as fake]
            [hammer.canvas :as cv :refer [defloop defdraw]]
            [hammer.draw :as draw]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(def seen (atom []))
(def raf-calls (atom 0))

(use-fixtures :each {:before (fn []
                               (t/reset-app!)
                               (draw/set-raf! (fn [_] (swap! raf-calls inc)))
                               (reset! raf-calls 0)
                               (reset! seen [])
                               (reset! fake/log []))})

(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defloop ticker [id] [paused? [:paused?]]
  {:size [10 10] :run? (not paused?) :max-dt 50}
  (fn [_ {:keys [t dt n]}] (swap! seen conj [id t dt n])))

(defn- div [] (js/document.createElement "div"))

(deftest runs-every-frame-with-capped-dt
  (reset! state/app-db {:paused? false})
  (cv/mount! [ticker :a] (div))
  (t/frame! 1000) (t/frame! 1016) (t/frame! 2000)
  (is (= [[:a 0 0 1] [:a 16 16 2] [:a 66 50 3]] @seen) "dt capped at :max-dt"))

(deftest pause-freezes-t-and-redraws-on-change-with-dt-0
  (reset! state/app-db {:paused? false :x 1})
  (cv/mount! [ticker :a] (div))
  (t/frame! 0) (t/frame! 16)
  (events/dispatch [::set :paused? true])
  (t/frame! 32)
  (is (= [:a 16 0 2] (last @seen)) "the pause itself redraws once with dt 0, n unchanged")
  (reset! seen [])
  (t/frame! 48) (t/frame! 64)
  (is (empty? @seen) "paused: no frames")
  (events/dispatch [::set :paused? false])
  (t/frame! 500) (t/frame! 516)
  (is (= [[:a 16 0 3] [:a 32 16 4]] @seen) "resume: first dt 0, t continues"))

(deftest idle-app-requests-no-frames
  (reset! state/app-db {:paused? true})
  (cv/mount! [ticker :a] (div))
  (t/frame! 0)
  (reset! raf-calls 0)
  (t/frame! 16)
  (is (zero? @raf-calls) "no running loop, nothing queued: no rAF"))

(deftest two-loops-share-one-frame-request
  (reset! state/app-db {:paused? false})
  (cv/mount! [ticker :a] (div))
  (cv/mount! [ticker :b] (div))
  (t/frame! 0)
  (reset! raf-calls 0)
  (t/frame! 16)
  (is (= 1 @raf-calls))
  (is (= #{[:a 16 16 2] [:b 16 16 2]} (set (take-last 2 @seen)))))

(defloop crashy [] [] {:size [10 10]}
  (fn [_ {:keys [n]}] (swap! seen conj [:crashy n]) (throw (js/Error. "x"))))

(defdraw steady [] [x [:x]] {:size [10 10]} (fn [_ _] (swap! seen conj [:steady x])))

(deftest a-throwing-loop-keeps-running-and-others-draw
  (reset! state/app-db {:x 1})
  (let [orig js/console.error]
    (set! js/console.error (fn [& _]))
    (try
      (cv/mount! [crashy] (div))
      (cv/mount! [steady] (div))
      (t/frame! 0) (t/frame! 16) (t/frame! 32)
      (is (= [[:crashy 1] [:steady 1] [:crashy 2] [:crashy 3]] @seen))
      (finally (set! js/console.error orig)))))
```

- [ ] **Step 2: Run them.** Run: `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|failures"`. If they're green, go to Step 4. If a test fails, fix `hammer.draw` (Step 3), without weakening the test.
- [ ] **Step 3: Fix if needed.** Likely spots: `rerender!` must clear `.-last` on pause so the first resumed frame has `dt 0`; `frame!` must only re-request while `some-running?` or the queue is non-empty.
- [ ] **Step 4: Commit**

```bash
git add test/hammer/loop_test.cljs src/hammer/draw.cljs
git commit -m "test: defloop timing, pause/resume, shared frames, error isolation" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 5: Canvas events (`:on-*`)

**Files:**
- Test: `test/hammer/draw_events_test.cljs`. The implementation (`sync-listeners!`, `handle!`, `local-xy`) is from Task 3.

- [ ] **Step 1: Write the tests**

```clojure
(ns hammer.draw-events-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas]
            [hammer.canvas :as cv :refer [defdraw]]
            [hammer.events :as events]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!))})

(def got (atom []))
(events/reg-event ::pick (fn [db x y] {:db (assoc db :picked [x y])}))
(events/reg-event ::set (fn [db k v] {:db (assoc db k v)}))

(defdraw clicky [] [fn? [:fn?]]
  {:size [100 100]
   :on-click (if fn? (fn [_e xy] (swap! got conj xy)) [::pick])}
  (fn [_ _]))

(defn- click! [^js c x y]
  (.dispatchEvent c (new (.-MouseEvent js/window) "click" #js {:clientX x :clientY y :bubbles true})))

(deftest vector-handler-dispatches-local-xy
  (reset! state/app-db {:fn? false})
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [clicky] c)
    (click! c 12 34)
    (t/flush!)
    (is (= [12 34] (:picked @state/app-db)))))

(deftest fn-handler-gets-event-and-xy-and-updates-live
  (reset! state/app-db {:fn? false})
  (reset! got [])
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [clicky] c)
    (events/dispatch [::set :fn? true])
    (t/frame! 16)
    (click! c 5 6)
    (is (= [{:x 5 :y 6}] @got))))

(deftest remount-does-not-duplicate-listeners
  (reset! state/app-db {:fn? true})
  (reset! got [])
  (let [c (js/document.createElement "canvas")]
    (cv/mount! [clicky] c)
    (cv/mount! [clicky] c)
    (click! c 1 1)
    (is (= 1 (count @got)))))
```

- [ ] **Step 2: Run them.** Run `npm test`. They should pass. If not, fix `hammer.draw` without weakening the tests.
- [ ] **Step 3: Commit**

```bash
git add test/hammer/draw_events_test.cljs src/hammer/draw.cljs
git commit -m "test: canvas :on-* handlers with local coordinates" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 6: Auto size, zero size, resize

**Files:**
- Test: `test/hammer/draw_size_test.cljs`. The implementation (`observe!`, `sync-size!`, `hook-resize!`) is from Task 3.

- [ ] **Step 1: Write the tests.** They use a fake `ResizeObserver` installed on `globalThis` per test.

```clojure
(ns hammer.draw-size-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [hammer.test-env]
            [hammer.fake-canvas]
            [hammer.canvas :as cv :refer [defdraw]]
            [hammer.state :as state]
            [hammer.testing :as t]))

(def observers (atom []))

(defn- fake-ro [cb]
  (this-as ^js o
    (set! (.-cb o) cb)
    (set! (.-observe o) (fn [el] (swap! observers conj o) (set! (.-el o) el)))
    (set! (.-disconnect o) (fn [] (set! (.-gone o) true)))
    o))

(defn- resize! [w h]
  (doseq [^js o @observers]
    ((.-cb o) #js [#js {:contentRect #js {:width w :height h}}])))

(use-fixtures :each {:before (fn []
                               (t/reset-app!) (t/use-fake-frames!) (reset! observers [])
                               (set! (.-ResizeObserver js/globalThis) fake-ro))
                     :after (fn [] (js-delete js/globalThis "ResizeObserver"))})

(def sizes (atom []))

(defdraw auto [] [] (fn [_ {:keys [w h]}] (swap! sizes conj [w h])))

(deftest zero-size-skips-drawing-until-positive
  (reset! sizes [])
  (let [host (js/document.createElement "div")]
    (cv/mount! [auto] host)
    (t/frame! 16)
    (is (empty? @sizes) "0×0: no draw")
    (resize! 120 80)
    (t/frame! 32)
    (is (= [[120 80]] @sizes))
    (let [c (.-firstChild host)]
      (is (= [120 80] [(.-width c) (.-height c)]))
      (is (= "100%" (.. c -style -width))))))

(deftest unmount-disconnects-the-observer
  (let [host (js/document.createElement "div")]
    (cv/mount! [auto] host)
    (t/reset-app!)
    (is (true? (.-gone ^js (first @observers))))))

(deftest window-resize-redraws
  (reset! sizes [])
  (cv/mount! [auto] (js/document.createElement "div"))
  (resize! 10 10)
  (t/frame! 16)
  (reset! sizes [])
  (.dispatchEvent js/window (new (.-Event js/window) "resize"))
  (t/frame! 32)
  (is (= [[10 10]] @sizes)))
```

- [ ] **Step 2: Run them.** Run `npm test`. They should pass. If not, fix `hammer.draw` without weakening the tests.
- [ ] **Step 3: Commit**

```bash
git add test/hammer/draw_size_test.cljs src/hammer/draw.cljs
git commit -m "test: auto size via ResizeObserver, zero size, window resize" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 7: `hammer.gpu`

**Files:**
- Create: `src/hammer/gpu.clj`, `src/hammer/gpu.cljs`, `test/hammer/fake_gpu.cljs`, `test/hammer/gpu_test.cljs`

**Interfaces:**
- Consumes: `hammer.draw/Backend`, `register-backend!`, `queue!`, `dispose!`, `states`, `State` fields `canvas`, `ctx` and `ext`, and `m/draw-def`.
- Produces: `hammer.gpu/defdraw` and `defloop` (macros); `reg-event reg-fx dispatch dispatch-sync is? mount!`; `(pass gpu {:clear [r g b a]} f)`; `reset-device!` (test hook).
- The draw fn's first argument is `{:device :queue :context :format :view}`.

- [ ] **Step 1: The WebGPU stub** `test/hammer/fake_gpu.cljs`

```clojure
(ns hammer.fake-gpu
  "A stub navigator.gpu recording what hammer does with it."
  (:require [hammer.test-env]))

(defonce log (atom []))
(defonce lose! (atom nil))

(defn- device []
  (let [lost (js/Promise. (fn [res _] (reset! lose! #(res #js {:reason "unknown"}))))]
    #js {:queue #js {:submit (fn [cmds] (swap! log conj [:submit (alength cmds)]))}
         :lost lost
         :createCommandEncoder
         (fn []
           #js {:beginRenderPass (fn [^js d]
                                   (let [a (aget (.-colorAttachments d) 0)]
                                     (swap! log conj [:pass (.-loadOp a) (.. a -clearValue -a)]))
                                   #js {:end (fn [] (swap! log conj [:end]))
                                        :draw (fn [n] (swap! log conj [:draw n]))})
                :finish (fn [] #js {})})}))

(defn install!
  "mode: :ok, :no-adapter or :missing."
  [mode]
  (reset! log [])
  (let [gpu (case mode
              :missing js/undefined
              #js {:getPreferredCanvasFormat (fn [] "bgra8unorm")
                   :requestAdapter (fn []
                                     (js/Promise.resolve
                                      (when (= mode :ok)
                                        #js {:requestDevice (fn [] (swap! log conj [:device])
                                                              (js/Promise.resolve (device)))})))})]
    (js/Object.defineProperty js/navigator "gpu" #js {:value gpu :configurable true :writable true}))
  (let [proto (.. js/window -HTMLCanvasElement -prototype)
        prev (.-getContext proto)]
    (set! (.-getContext proto)
          (fn [kind]
            (this-as ^js c
              (if (= kind "webgpu")
                (or (.-__fakegpu c)
                    (let [o #js {:configure (fn [^js d] (swap! log conj [:configure (.-format d)]))
                                 :unconfigure (fn [] (swap! log conj [:unconfigure]))
                                 :getCurrentTexture (fn [] #js {:createView (fn [] #js {:view true})})}]
                      (set! (.-__fakegpu c) o) o))
                (.call prev c kind)))))))

(defn settle
  "Calls f after pending promise callbacks (device setup) have run."
  [f]
  (js/setTimeout f 0))
```

- [ ] **Step 2: Write the failing tests** `test/hammer/gpu_test.cljs`

```clojure
(ns hammer.gpu-test
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [hammer.test-env]
            [hammer.fake-gpu :as fg]
            [hammer.core :as core :refer [defc]]
            [hammer.gpu :as gpu :refer [defdraw]]
            [hammer.state :as state]
            [hammer.testing :as t]))

(use-fixtures :each {:before (fn [] (t/reset-app!) (t/use-fake-frames!) (gpu/reset-device!))})

(def inits (atom 0))
(def unsupported (atom []))

(defdraw tri [] [n [:n]]
  {:size [10 10]
   :init (fn [_g _] (swap! inits inc) :pipe)
   :dispose (fn [_] (swap! fg/log conj [:dispose]))
   :fallback [:p "needs WebGPU"]
   :on-unsupported (fn [r] (swap! unsupported conj r))}
  (fn [g _ res]
    (gpu/pass g {:clear [0 0 0 1]} (fn [p] (.draw p n)))))

(defc page [] [] [:div [tri]])

(deftest draws-after-device-is-ready
  (async done
    (fg/install! :ok)
    (reset! state/app-db {:n 3})
    (reset! inits 0)
    (let [c (js/document.createElement "canvas")]
      (gpu/mount! [tri] c)
      (t/frame! 16)
      (is (not-any? #(= :pass (first %)) @fg/log) "no device yet: no draw")
      (fg/settle
       (fn []
         (t/frame! 32)
         (is (= [[:device] [:configure "bgra8unorm"] [:pass "clear" 1] [:draw 3] [:end] [:submit 1]] @fg/log))
         (is (= 1 @inits))
         (done))))))

(deftest unsupported-renders-fallback-and-reports
  (async done
    (fg/install! :missing)
    (reset! unsupported [])
    (let [orig js/console.error]
      (set! js/console.error (fn [& _]))
      (let [host (js/document.createElement "div")]
        (core/mount! [page] host)
        (fg/settle
         (fn []
           (set! js/console.error orig)
           (is (= "<div><span style=\"display: contents;\"><p>needs WebGPU</p></span></div>" (.-innerHTML host)))
           (is (= 1 (count @unsupported)))
           (done)))))))

(deftest no-adapter-is-unsupported-too
  (async done
    (fg/install! :no-adapter)
    (reset! unsupported [])
    (let [orig js/console.error]
      (set! js/console.error (fn [& _]))
      (gpu/mount! [tri] (js/document.createElement "canvas"))
      (fg/settle (fn [] (set! js/console.error orig) (is (= 1 (count @unsupported))) (done))))))

(deftest device-loss-disposes-reinits-and-redraws
  (async done
    (fg/install! :ok)
    (reset! state/app-db {:n 1})
    (reset! inits 0)
    (gpu/mount! [tri] (js/document.createElement "canvas"))
    (fg/settle
     (fn []
       (t/frame! 16)
       (reset! fg/log [])
       (@fg/lose!)
       (fg/settle
        (fn []
          (t/frame! 32)
          (is (= [[:dispose] [:device] [:configure "bgra8unorm"] [:pass "clear" 1] [:draw 1] [:end] [:submit 1]]
                 @fg/log))
          (is (= 2 @inits))
          (done)))))))
```

- [ ] **Step 3: Run them and check they fail.** Run: `npm test 2>&1 | grep -E "No such namespace|FAIL|ERROR|failures"`. Expected: `No such namespace: hammer.gpu`.

- [ ] **Step 4: Create `src/hammer/gpu.clj`.** It's the same as `canvas.clj`, with `ns hammer.gpu`, the kind `:gpu`, and docstrings that say WebGPU and `(fn [gpu info])`:

```clojure
(ns hammer.gpu
  (:require [hammer.macros :as m]))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn): a WebGPU component
  redrawn when a binding its opts or draw-fn name changes. draw-fn:
  (fn [gpu info]) or, with :init, (fn [gpu info res]); gpu is
  {:device :queue :context :format :view}."
  [cname props bindings & more]
  (m/draw-def &env "defdraw" :gpu false cname props bindings more))

(defmacro defloop
  "Like defdraw, but redrawn every animation frame while mounted and :run? is
  truthy; info also has :t :dt :n."
  [cname props bindings & more]
  (m/draw-def &env "defloop" :gpu true cname props bindings more))
```

- [ ] **Step 5: Create `src/hammer/gpu.cljs`**

```clojure
(ns hammer.gpu
  "WebGPU facade: the event API, defdraw/defloop, mount!, pass. One device per
  page, requested on first mount; unsupported browsers get :fallback /
  :on-unsupported; after device loss every component is disposed, re-inited
  and redrawn."
  (:require-macros [hammer.gpu])
  (:require [hammer.app :as app]
            [hammer.draw :as draw]))

(def reg-event app/reg-event)
(def reg-fx app/reg-fx)
(def dispatch app/dispatch)
(def dispatch-sync app/dispatch-sync)
(def is? app/is?)
(def mount! draw/mount!)

;; status: :idle :pending :ready :unsupported
(deftype Dev [^:mutable status ^:mutable device ^:mutable format ^:mutable reason waiting])
(defonce ^:private dev (Dev. :idle nil nil nil (js/Set.)))

(defn reset-device!
  "Test hook: forget the device and any waiting components."
  []
  (set! (.-status dev) :idle)
  (set! (.-device dev) nil)
  (.clear (.-waiting dev)))

(defn- configure! [^draw/State st]
  (let [^js ctx (.getContext ^js (.-canvas st) "webgpu")]
    (.configure ctx #js {:device (.-device dev) :format (.-format dev) :alphaMode "premultiplied"})
    (set! (.-ctx st) ctx)
    (draw/queue! st)))

(defn- fallback! [^draw/State st]
  (let [opts (.-opts st)
        ^js ext (.-ext st)]
    (when-let [f (:on-unsupported opts)] (f (.-reason dev)))
    (when-let [h (:fallback opts)]
      (when-let [render (some-> ext .-render)]
        (let [^js wrap (.-wrap ext)]
          (set! (.-textContent wrap) "")
          (.appendChild wrap (render h)))))))

(defn- unsupported! [reason]
  (set! (.-status dev) :unsupported)
  (set! (.-reason dev) reason)
  (js/console.error "hammer: WebGPU unavailable:" reason)
  (.forEach (.-waiting dev) fallback!)
  (.clear (.-waiting dev)))

(declare acquire!)

(defn- lost! []
  (doseq [st (draw/states :gpu)]
    (draw/dispose! st)
    (set! (.-ctx ^draw/State st) nil)
    (.add (.-waiting dev) st))
  (set! (.-status dev) :idle)
  (set! (.-device dev) nil)
  (acquire!))

(defn- ready! [^js device format]
  (set! (.-status dev) :ready)
  (set! (.-device dev) device)
  (set! (.-format dev) format)
  (.then (.-lost device) (fn [^js info]
                           (when (and (identical? device (.-device dev)) (not= "destroyed" (.-reason info)))
                             (lost!))))
  (.forEach (.-waiting dev) configure!)
  (.clear (.-waiting dev)))

(defn- acquire! []
  (when (keyword-identical? (.-status dev) :idle)
    (set! (.-status dev) :pending)
    (let [^js gpu (.-gpu js/navigator)]
      (if-not gpu
        (unsupported! "navigator.gpu is missing")
        (-> (.requestAdapter gpu)
            (.then (fn [^js a]
                     (if-not a
                       (unsupported! "no WebGPU adapter")
                       (.then (.requestDevice a)
                              (fn [d] (ready! d (.getPreferredCanvasFormat gpu)))))))
            (.catch (fn [e] (unsupported! (str e)))))))))

(draw/register-backend!
 :gpu
 (draw/Backend.
  (fn [^draw/State st render]
    (let [wrap (when render
                 (let [s (js/document.createElement "span")]
                   (.setProperty (.-style s) "display" "contents")
                   (.appendChild s (.-canvas st))
                   s))]
      (set! (.-ext st) #js {:wrap wrap :render render})
      (acquire!)
      (case (.-status dev)
        :ready (configure! st)
        :unsupported (fallback! st)
        (.add (.-waiting dev) st))
      (or wrap (.-canvas st))))
  (fn [^draw/State st]
    (when-let [^js ctx (.-ctx st)]
      (when (keyword-identical? (.-status dev) :ready)
        (let [^js d (.-device dev)]
          {:device d :queue (.-queue d) :context ctx :format (.-format dev)
           :view (.createView (.getCurrentTexture ctx))}))))
  (fn [_] nil)
  (fn [^draw/State st]
    (.delete (.-waiting dev) st)
    (some-> ^js (.-ctx st) (.unconfigure)))))

(defn pass
  "One render pass on (:view gpu) that clears to clear ([r g b a], default
  transparent black): calls (f pass), ends it and submits."
  [{:keys [device queue view]} {:keys [clear]} f]
  (let [[r g b a] (or clear [0 0 0 0])
        ^js enc (.createCommandEncoder ^js device)
        ^js p (.beginRenderPass enc #js {:colorAttachments
                                         #js [#js {:view view :loadOp "clear" :storeOp "store"
                                                   :clearValue #js {:r r :g g :b b :a a}}]})]
    (f p)
    (.end p)
    (.submit ^js queue #js [(.finish enc)])))
```

- [ ] **Step 6: Run the tests and check they pass.** Run: `npm test 2>&1 | grep -E "FAIL|ERROR|Ran|failures"`. Expected: all green. If `unsupported-renders-fallback-and-reports` fails only on the exact `innerHTML` string, compare against jsdom's serialization of `display: contents` and fix the expected string. Don't drop the assertion.

- [ ] **Step 7: Commit**

```bash
git add src/hammer/gpu.clj src/hammer/gpu.cljs test/hammer/fake_gpu.cljs test/hammer/gpu_test.cljs
git commit -m "feat: hammer.gpu defdraw/defloop with device lifecycle and pass helper" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 8: Three bundles and `bb sizes`

**Files:**
- Create: `size/size/dom.cljs`, `size/size/canvas.cljs`, `size/size/gpu.cljs`
- Modify: `shadow-cljs.edn` (add `"size"` to `:source-paths`, three builds), `bb.edn` (`sizes` task, require `clojure.java.io`), `.gitignore` (`target/` already covers outputs)

- [ ] **Step 1: The three apps**

```clojure
(ns size.dom
  (:require [hammer.core :refer [defc reg-event mount!]]))
(reg-event :inc (fn [db] {:db (update db :n inc)}))
(defc app [] [n [:n]] [:button {:on-click [:inc]} n])
(defn main [] (mount! [app] (js/document.getElementById "app") {:n 0}))
```

```clojure
(ns size.canvas
  (:require [hammer.canvas :refer [defdraw defloop reg-event mount!]]))
(reg-event :inc (fn [db] {:db (update db :n inc)}))
(defdraw bar [] [n [:n]] {:size [100 20] :on-click [:inc]}
  (fn [ctx {:keys [w h]}] (.clearRect ctx 0 0 w h) (.fillRect ctx 0 0 n h)))
(defloop spin [] [] {:size [20 20]}
  (fn [ctx {:keys [t]}] (.clearRect ctx 0 0 20 20) (.fillRect ctx 0 0 (mod (/ t 10) 20) 20)))
(defn main []
  (mount! [bar] (js/document.getElementById "a") {:n 1})
  (mount! [spin] (js/document.getElementById "b")))
```

```clojure
(ns size.gpu
  (:require [hammer.gpu :as gpu :refer [defdraw reg-event mount!]]))
(reg-event :inc (fn [db] {:db (update db :n inc)}))
(defdraw clear [] [n [:n]] {:size [100 100] :on-click [:inc]}
  (fn [g _] (gpu/pass g {:clear [(/ (mod n 10) 10) 0 0 1]} (fn [_]))))
(defn main [] (mount! [clear] (js/document.getElementById "a") {:n 0}))
```

- [ ] **Step 2: Add the builds to `shadow-cljs.edn`.** Add `"size"` to `:source-paths` and these to `:builds`:

```clojure
:size-dom {:target :browser :output-dir "target/size/size-dom" :asset-path "."
           :modules {:main {:init-fn size.dom/main}}}
:size-canvas {:target :browser :output-dir "target/size/size-canvas" :asset-path "."
              :modules {:main {:init-fn size.canvas/main}}}
:size-gpu {:target :browser :output-dir "target/size/size-gpu" :asset-path "."
           :modules {:main {:init-fn size.gpu/main}}}
```

- [ ] **Step 3: Add the `bb sizes` task.** Add `[clojure.java.io :as io]` to `:requires`, then:

```clojure
  sizes
  {:doc "Builds the dom/canvas/gpu size apps, prints raw and gzip sizes, and fails if a bundle contains another variant's namespaces"
   :task (let [forbidden {"size-dom" ["$hammer$draw$" "$hammer$canvas$" "$hammer$gpu$"]
                          "size-canvas" ["$hammer$dom$" "$hammer$gpu$"]
                          "size-gpu" ["$hammer$dom$" "$hammer$canvas$"]}
               out #(str "target/size/" % "/main.js")
               gz (fn [f] (let [bos (java.io.ByteArrayOutputStream.)]
                            (with-open [z (java.util.zip.GZIPOutputStream. bos)]
                              (io/copy (io/file f) z))
                            (.size bos)))
               builds (sort (keys forbidden))]
           (apply shell "npx shadow-cljs release" (concat builds ["--pseudo-names"]))
           (let [bad (for [b builds, n (forbidden b) :when (str/includes? (slurp (out b)) n)] [b n])]
             (apply shell "npx shadow-cljs release" builds)
             (doseq [b builds]
               (println (format "%-12s %8d B  %7d B gzip" b (fs/size (out b)) (gz (out b)))))
             (when (seq bad)
               (println "FAIL: forbidden namespaces:" (vec bad))
               (System/exit 1))))}
```

- [ ] **Step 4: Check that the task catches a violation.** Temporarily add `[hammer.dom]` to the `size.canvas` ns requires and run `bb sizes`. It must print `FAIL` and exit 1. Revert the change.
- [ ] **Step 5: Run it.** Run `bb sizes`. Expected: three lines of sizes, no `FAIL`, exit 0. Also run `npm test`: still green.
- [ ] **Step 6: Commit**

```bash
git add size shadow-cljs.edn bb.edn
git commit -m "build: dom/canvas/gpu size bundles and bb sizes namespace checks" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 9: Examples and docs

**Files:**
- Create: `examples/canvas/public/index.html`, `examples/canvas/src/canvas_demo/core.cljs`, `examples/gpu/public/index.html`, `examples/gpu/src/gpu_demo/core.cljs`
- Modify: `shadow-cljs.edn` (builds `canvas-demo` and `gpu-demo`, and their source paths), `README.md` (a "Canvas and WebGPU" section), `.claude/skills/hammer-internals/SKILL.md` (files table plus a draw section), `.gitignore` (`examples/*/public/js/`)

- [ ] **Step 1: The canvas example**: a `defdraw` scatter chart with click-to-select (`:on-click [:pick]` plus a pick handler that selects the nearest point) and a `defloop` of bouncing balls with a pause button. It mounts inside a `defc` page.

```clojure
(ns canvas-demo.core
  (:require [hammer.core :refer [defc reg-event mount!]]
            [hammer.canvas :refer [defdraw defloop]]))

(defn- rand-pts [n] (into {} (map (fn [i] [i {:x (rand-int 600) :y (rand-int 300)}])) (range n)))

(reg-event :pick (fn [db x y]
                   (let [[id] (apply min-key (fn [[_ p]] (+ (Math/abs (- x (:x p))) (Math/abs (- y (:y p)))))
                                     (:pts db))]
                     {:db (assoc db :sel id)})))
(reg-event :toggle (fn [db] {:db (update db :paused? not)}))

(defdraw scatter [] [pts [:pts] sel [:sel]]
  {:size [600 300] :on-click [:pick] :attrs {:style {:border "1px solid #ccc"}}}
  (fn [ctx {:keys [w h]}]
    (.clearRect ctx 0 0 w h)
    (doseq [[id {:keys [x y]}] pts]
      (set! (.-fillStyle ctx) (if (= id sel) "crimson" "steelblue"))
      (.fillRect ctx (- x 2) (- y 2) 5 5))))

(defn- step [balls dt w h]
  (mapv (fn [{:keys [x y vx vy] :as b}]
          (let [x (+ x (* vx dt)) y (+ y (* vy dt))]
            (assoc b :x x :y y
                   :vx (if (or (< x 0) (> x w)) (- vx) vx)
                   :vy (if (or (< y 0) (> y h)) (- vy) vy))))
        balls))

(defloop balls [] [world (atom (vec (repeatedly 200 #(hash-map :x (rand 600) :y (rand 200)
                                                                 :vx (- (rand 0.4) 0.2) :vy (- (rand 0.4) 0.2)))))
                   paused? [:paused?]]
  {:size [600 200] :run? (not paused?)}
  (fn [ctx {:keys [w h dt]}]
    (swap! world step dt w h)
    (.clearRect ctx 0 0 w h)
    (set! (.-fillStyle ctx) "darkorange")
    (doseq [{:keys [x y]} @world] (.fillRect ctx x y 3 3))))

(defc page [] [paused? [:paused?]]
  [:div
   [:h2 "Scatter (click a point)"] [scatter]
   [:h2 "Balls"] [:button {:on-click [:toggle]} (if paused? "Resume" "Pause")] [balls]])

(defn ^:export main []
  (mount! [page] (js/document.getElementById "app") {:pts (rand-pts 500) :sel nil :paused? false}))
```

- [ ] **Step 2: The gpu example**: a `defdraw` that clears to a colour derived from a db counter, with a button that increments it, `:fallback [:p "This demo needs WebGPU (Chrome, Edge, iOS Safari 26+)."]`, and one `defloop` that pulses the clear colour. Use only `gpu/pass` with no pipeline, since shader helpers are out of scope.

```clojure
(ns gpu-demo.core
  (:require [hammer.core :refer [defc reg-event mount!]]
            [hammer.gpu :as gpu :refer [defdraw defloop]]))

(reg-event :inc (fn [db] {:db (update db :n inc)}))

(defdraw swatch [] [n [:n]]
  {:size [200 100] :fallback [:p "This demo needs WebGPU (Chrome, Edge, iOS Safari 26+)."]}
  (fn [g _] (gpu/pass g {:clear [(/ (mod n 10) 10) 0.2 0.6 1]} (fn [_]))))

(defloop pulse [] [] {:size [200 100] :fallback [:p "No WebGPU."]}
  (fn [g {:keys [t]}] (let [v (/ (inc (Math/sin (/ t 300))) 2)] (gpu/pass g {:clear [v v 0.2 1]} (fn [_])))))

(defc page [] [n [:n]]
  [:div [:button {:on-click [:inc]} (str "Colour " n)] [swatch] [pulse]])

(defn ^:export main [] (mount! [page] (js/document.getElementById "app") {:n 0}))
```

- [ ] **Step 3: HTML and builds.** Each `index.html` has `<div id="app"></div><script src="/js/main.js"></script>`. In `shadow-cljs.edn`, add `"examples/canvas/src" "examples/gpu/src"` to `:source-paths` and:

```clojure
:canvas-demo {:target :browser :output-dir "examples/canvas/public/js" :asset-path "/js"
              :modules {:main {:init-fn canvas-demo.core/main}}}
:gpu-demo {:target :browser :output-dir "examples/gpu/public/js" :asset-path "/js"
           :modules {:main {:init-fn gpu-demo.core/main}}}
```

Check with `npx shadow-cljs release canvas-demo gpu-demo`: 0 warnings.

- [ ] **Step 4: Docs.**
  - **README "Canvas and WebGPU":** the `defdraw` / `defloop` shape, the options table from the spec (`:size :run? :max-dt :init :dispose :on-* :fallback :on-unsupported :attrs`), the draw-fn arguments, mounting in DOM versus on a canvas, `bb sizes`, and a one-line note that 3D is a TODO.
  - **`SKILL.md`:** add `draw.cljs`, `canvas.cljs`, `gpu.cljs`, `app.cljs` and `macros.clj` to the files table, plus a short "Draw components" section: host hook, per-component runner, draws only in `frame!`, and State kept in the instance's vnode field.
- [ ] **Step 5: Manual check (report the result; not automated).** Serve with `npx shadow-cljs watch canvas-demo` and open http://localhost:8290 (add `:dev-http {8290 "examples/canvas/public" 8291 "examples/gpu/public"}`). Check: clicking selects a point, and pause/resume works. In Chrome the gpu demo shows colours; in Firefox it shows the fallback text.
- [ ] **Step 6: Commit**

```bash
git add examples/canvas examples/gpu shadow-cljs.edn README.md .claude/skills/hammer-internals/SKILL.md .gitignore
git commit -m "docs: canvas and gpu examples, README and skill sections" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETwa1ReVzrnYsQi7Hq4iwh"
```

---

### Task 10: No regression for DOM apps

**Files:** none in the repo. It uses the benchmark fork at `/home/soeren/repos/private/js-framework-benchmark` (local tooling, not committed).

- [ ] **Step 1: Full suite and sizes.** `npm test` (all green), `bb sizes` (exit 0), `bb loc` (report the number).
- [ ] **Step 2: Build the benchmark entry against this branch.**

```bash
cd /home/soeren/repos/private/js-framework-benchmark
./make-hammer-variant.sh canvas-draw
cp frameworks/keyed/hammer-p2-eq-sub/src/demo/main.cljs frameworks/keyed/hammer-canvas-draw/src/demo/main.cljs
(cd frameworks/keyed/hammer-canvas-draw && rm -rf .shadow-cljs/builds && npx shadow-cljs release app)
cd webdriver-ts && npm run isKeyed -- --headless --framework keyed/hammer-canvas-draw
npm run bench -- --headless --count 10 --benchmark 01_ 03_ 04_ 07_ 09_ --framework keyed/hammer-p2-slim keyed/hammer-canvas-draw
cd .. && python3 compare.py hammer-p2-slim hammer-canvas-draw
```

  Expected: the keyed check passes, and every row is within noise of `hammer-p2-slim` (about ±5% total). The benchmark server must be running (`npm start` in the fork) first.
- [ ] **Step 3: Report the numbers** in the final summary. If a row regresses beyond noise, profile it (`webdriver-ts/profile-scratch.mjs`) and fix it before finishing. The likely suspect is the scheduler's runner dispatch in `cells.cljs`.
