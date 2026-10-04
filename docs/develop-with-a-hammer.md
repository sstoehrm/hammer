# Developing with a hammer

How to build an app with hammer: setup, the API, components, events, the DOM,
errors, testing, and Canvas 2D / WebGL2 drawing. For *why* it is fast, see
[performance.md](performance.md).

## Setup

hammer is a git dependency and needs no npm packages of its own. In `deps.edn`,
pin a release tag and the commit it points at (`git rev-parse --short v0.1.0`,
or the release page on GitHub):

```clojure
io.github.sstoehrm/hammer {:git/url "git@github.com:sstoehrm/hammer.git"
                           :git/tag "v0.1.0"
                           :git/sha "<short sha of v0.1.0>"}
```

The repository is private for now, so the SSH URL needs a GitHub key with
access to it.

With shadow-cljs, set `:deps true` in `shadow-cljs.edn` so it reads `deps.edn`
(and put `thheller/shadow-cljs` there too). Require `hammer.core`; the build's
`:init-fn` calls `mount!` once. Any optimization level works, `:advanced`
included.

If you build with an AI agent, install the `hammer-app` skill: it holds this
document's essentials in a form agents use well.

    /plugin marketplace add sstoehrm/hammer       # Claude Code
    /plugin install hammer-app@hammer

    codex plugin marketplace add sstoehrm/hammer  # Codex
    codex plugin add hammer-app@hammer

## The basics

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
| `(defc name [props] [bindings] body)` | see above; keys via `^{:key k}` (or `:key` in an element's attrs) |
| `(mount! hiccup el db)` | set db, render into `el` |
| `(mount! hiccup el)` | render into `el`, keeping the current db |
| `(on-error! (fn [{:keys [level message error]}]))` | replaces the console as the place hammer reports to; `nil` restores it |

Hiccup: `:on-<dom-event>` takes an event vector or fn (anything else is ignored,
with a warning in dev builds); `:ref` fn gets the element,
and `nil` on removal, so write it as `#(some-> % .focus)` rather than assuming a
non-nil element. Handlers run from one capture-phase listener per event type on
the `mount!` container, so `(.-currentTarget e)` is that container; a fn
handler gets the element it is on as a second argument instead:
`(fn [e el] …)`. Handlers are always called with these two arguments: a
one-arg `(fn [e] …)` ignores the second, but a multi-arity fn needs a 2-arity,
and an optional second parameter receives the element. Attribute values: `nil` removes the
attribute, `true`/`false` add or remove it (`:disabled true` → `disabled=""`),
except for `draggable`, `spellcheck`, `contenteditable`, `writingsuggestions`
and `aria-*`, which take
the strings `"true"`/`"false"`; anything else is written with `str`.
`:class` string or collection;
`:style` map of CSS property names (`:background-color`; dev builds warn on
`:backgroundColor`) or a CSS string. SVG works: `[:svg {:viewBox "0 0 10 10"} [:circle {:r 5}]]`
creates SVG elements from `:svg` down (a `foreignObject`'s children are HTML
again), in components and on every update. Attribute names keep their case
(`:viewBox`). Use `:href`, not `:xlink:href`. A canvas component inside an
`:svg` needs a `:foreignObject` around it.

Errors: hammer does not throw for a failing handler, fx, render or draw, or for
a mistake like a missing handler or a handler that returns `db` instead of
`{:db db}`. It reports it (`level` `:error` or `:warn`, a `"hammer: …"`
`message`, the caught `error` or `nil`) and keeps the rest of the app running.
The default reporter is the browser console; `on-error!` replaces it, e.g. to
send errors to a service or show them on the page.

Literal hiccup in a `defc` body compiles to templates: the static structure is
built once and cloned per instance, and an update writes only the changed
dynamic parts. Hiccup built by other functions, passed as a prop or given to
`mount!` is diffed as plain data, with the same result.

A global atom deref'd in a component, `[:span (count @cart)]`, is tracked: the
component re-renders when `cart` changes, as if you had bound it (`[c cart]`, then
`@c`). The code is compiled as written and reads `@cart` itself; hammer only adds a
watch on the atom `cart` held when the instance was created. This covers `@g` written
in the body and binding inits, where `g` is a var of your own. Not covered, so bind
the atom yourself (`[c cart]`): derefs inside any `fn`/`#(…)`, including render-time
ones like `(map (fn [x] … @cart …) xs)` (event handlers run later anyway), derefs in
helper functions the body calls, and `(deref (f))`. `@^:once config` reads without
tracking. `@app-db` warns at compile time: read the db through path bindings. For
per-row state such as a selection, prefer `(is? [:selected] id)` over a global atom:
every row that derefs the atom re-renders when it changes.

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
hammer facade (`hammer.core`, `hammer.app`, `hammer.canvas`, `hammer.gl`) or
an alias of one. Anywhere else, e.g. nested inside another expression, calling
`is?` throws.

The 2-arity `mount!` renders without touching `app-db`; call it from a
`^:dev/after-load` hook so a hot reload re-renders with whatever db state the
running app already has, instead of resetting it.

## HTTP

`(:require [hammer.http])` registers the `:http` effect, built on `fetch`. Key
names follow re-frame's http-fx where they mean the same; it has no
dependencies, and apps that don't require it pay nothing (it adds about 2.8 KB
gzip when used).

```clojure
(reg-event :load (fn [_ q] {:http {:uri "/api/items" :params {:q q}
                                   :abort-key :search
                                   :on-success [:loaded] :on-failure [:failed]}}))
(reg-event :loaded (fn [db items] {:db (assoc db :items items)}))
(reg-event :save (fn [db] {:http {:method :post :uri "/api/items" :body (:draft db)
                                  :headers {"Authorization" (str "Bearer " (:token db))}
                                  :on-success [:saved]}}))
```

| Key | Default | |
|---|---|---|
| `:method` | `:get` | any HTTP method |
| `:uri` | required | |
| `:params` | — | always the query string (also for POST, unlike re-frame's http-fx), placed before any `#fragment`; a sequential value repeats the key, `nil` is skipped |
| `:body` | — | clj data or a plain `#js` object is sent as JSON with `Content-Type: application/json`; a string, `FormData`, `Blob`, … as is. Not allowed with GET/HEAD (reported). |
| `:headers` | — | a map of header → value, or a `js/Headers` |
| `:timeout` | none | ms; fails with `:failure :timeout` |
| `:abort-key` | — | a newer request with the same key aborts the older one, which then dispatches nothing |
| `:response-format` | `:json` | `:json`, `:text`, `:blob`, or `:raw` (the `Response`) |
| `:keywords?` | `true` | keywordize JSON keys |
| `:fetch-options` | — | merged into fetch's init: `:credentials`, `:mode`, `:cache`, … |
| `:on-success` | — | event vector; the body is appended (`nil` for an empty body) |
| `:on-failure` | — | event vector; `{:uri :status :status-text :failure :response}` is appended. `:failure` is `:error` (non-2xx, `:response` is the parsed body), `:network`, `:timeout` or `:parse`. Without it, the failure is reported through `on-error!` and fails `flush!` in tests. |

A vector of request maps runs each; a `nil` request is skipped. A malformed
request (no `:uri`, an `:on-success` that is not a vector, an unknown
`:response-format`) is reported and not sent. A request aborted by a newer one
with the same `:abort-key` dispatches nothing, even if its response was already
on the way. To add auth to every request, or to stub
requests in tests, replace fetch: `(hammer.http/set-fetch! (fn [url init]
promise-of-Response))`; `nil` restores `js/fetch`.

## Tracks

A track dispatches an event when the values at db paths change, without a
component: react to a filter change by loading data, keep a URL in sync, save
a draft. `(:require [hammer.track])` registers two effects:

```clojure
(reg-event :open (fn [_] {:hammer.track/register
                          {:id :reload :path [:filters]
                           :event-fn (fn [filters] [:load filters])}}))  ; nil: dispatch nothing
(reg-event :close (fn [_] {:hammer.track/dispose {:id :reload}}))
```

- `:path`, or `:paths` for several: `event-fn` then gets one value per path.
- `:dispatch-first?` (default `true`) also dispatches for the values at
  registration; `false` waits for the first change.
- A value counts as changed when it is not `=` to the last one. Changes are
  batched: several events in one tick fire the track once, with the final
  values (a path set 1 → 2 → 1 within one tick does not fire).
- Registering an id again replaces that track, so re-registering in a hot
  reload hook runs the new `event-fn`.
- An `event-fn` whose event changes the track's own path loops; `flush!`
  stops it in tests ("did not settle"), a browser would keep going.
- Both effects take a map or a vector of maps; `hammer.track/register!` and
  `dispose!` do the same outside an event.
- A track is a component instance without a body or DOM: its paths subscribe in
  the path trie like any binding, so only tracks whose paths changed run, in the
  render flush after the event that changed them. The event it dispatches runs
  next.
- `hammer.testing/reset-app!` disposes every track.

## Tubes

Tubes send event vectors between the app and a server over a WebSocket, both
ways: throw `[:say-hello "x"]` at the server, and let the server push events
back. Inspired by [pneumatic-tubes](https://github.com/drapanjanas/pneumatic-tubes);
this is the client side only, with its own small protocol, so any server that
speaks it works. `(:require [hammer.tubes])` registers three effects:

```clojure
(reg-event :init (fn [_] {:hammer.tubes/create {:url "ws://localhost:9090/ws"
                                                :params {:token "abc"}       ; → ?token=abc
                                                :on-connect [:online]        ; event vector or fn
                                                :on-disconnect [:offline]}}))
(reg-event :say-hello (fn [db name] {:db (assoc db :greeting name)
                                     :hammer.tubes/send [:say-hello name]})) ; to the server
(reg-event :say-hello-processed (fn [db] ...))                               ; pushed by the server
(reg-event :logout (fn [_] {:hammer.tubes/destroy {}}))
```

- **Protocol:** one text frame per event, the event vector as EDN (`pr-str`
  out, the EDN reader in, nothing is evaluated). Send data that prints as EDN.
- **Incoming:** every event from the server is dispatched; `:on-receive (fn
  [event])` replaces that, e.g. to accept only some events.
- **Outgoing:** `:hammer.tubes/send` takes an event vector, or `{:id :event}`.
  While disconnected, events are queued and go out in order on the next connect.
- **Reconnect:** a dropped connection reconnects after a random backoff whose
  maximum grows by 1 s per attempt up to 30 s (`:backoff (fn [attempt] ms)`
  replaces it), reset after a successful connect. `:hammer.tubes/destroy` closes
  for good, without `:on-disconnect`.
- **Several tubes:** `:id` on each effect (default `:default`); creating an id
  again replaces that tube.
- **Errors:** unreadable or non-event frames, a send to no tube and a missing
  `:url` are reported through `on-error!`.
- **Testing:** `(hammer.tubes/set-websocket! (fn [url] fake-socket))` swaps in a
  fake WebSocket; `reset-app!` destroys every tube.
- **Size:** about 14 KB gzip in apps that require it, nearly all of it the EDN
  reader; nothing otherwise.

A matching server, here with http-kit (not shipped with hammer):

```clojure
(require '[org.httpkit.server :as http] '[clojure.edn :as edn])

(defonce clients (atom #{}))

(defn ws-handler [req]
  (http/as-channel req
    {:on-open    (fn [ch] (swap! clients conj ch))
     :on-close   (fn [ch _] (swap! clients disj ch))
     :on-receive (fn [ch msg]
                   (let [[id & args] (edn/read-string msg)]
                     (case id
                       :say-hello (http/send! ch (pr-str [:say-hello-processed (first args)]))
                       nil)))}))

(defn push-all! [event]                 ; dispatch on every connected client
  (doseq [ch @clients] (http/send! ch (pr-str event))))
```

## Testing

`hammer.testing` provides:

- `(flush!)` — drains queued events, then renders until nothing is dirty
  (synchronous equivalent of the event + render microtasks). `flush!` alone
  never draws `defdraw`/`defloop` components — they only draw at a frame.
  It then throws if hammer reported an error since the last `flush!`
  (`ex-info` with `{:errors [...]}`); warnings never throw. Loading
  `hammer.testing` turns this on, whatever `on-error!` is set to.
- `(expect-errors f)` — runs `(f)` and returns the reports made during it
  instead of letting them fail `flush!`; for tests of error behaviour.
- `(check-errors!)` — the same check on its own; `frame!` runs it after its
  draw frame. Use it (e.g. as an `:after` fixture) in tests that only call
  `dispatch-sync`, which never throws for a reported error.
- `(renders c)` / `(reset-renders! & cs)` — a component's render count since
  the last reset; use to assert that only the expected components re-rendered.
- `(reset-app!)` — unmounts every root, empties `app-db` and drops unchecked
  reports; use as a `:before` fixture between tests.
- `(use-fake-frames!)` — replaces `requestAnimationFrame` so `defdraw`/`defloop`
  components draw only when the test calls `frame!`, never on a real animation
  frame; add it to the same `:before` fixture as `reset-app!` in any test that
  touches `hammer.canvas`/`hammer.gl`.
- `(frame! ms)` — `flush!` plus one draw frame at time `ms`: drains events,
  renders, then draws every queued or running `defdraw`/`defloop` instance
  once, as `requestAnimationFrame` would at time `ms`.

## Canvas and WebGL2

`hammer.canvas` (Canvas 2D) and `hammer.gl` (WebGL2) add two more component
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
| `:init` | all | `(fn [ctx-or-gl info] res)`, run once before the first draw (`hammer.gl`: again after `webglcontextrestored`). |
| `:dispose` | all | `(fn [res])`, run on unmount (`hammer.gl`: also on `webglcontextlost`, before re-init on restore). |
| `:context-attrs` | `hammer.gl` | Map passed to `getContext("webgl2", attrs)`, e.g. `{:antialias false :alpha false}`. Read once at setup, since context attributes are fixed at creation. |
| `:on-*` | all | Canvas DOM events, e.g. `:on-click`, `:on-pointermove`. A fn gets `(e {:x :y})` in canvas-local CSS pixels; an event vector is dispatched with `x y` appended, e.g. `[:pick]` → `[:pick 120 48]`. |
| `:fallback` | `hammer.gl` | Hiccup rendered instead of the canvas when WebGL2 is unavailable (DOM embedding only). **Static: plain hiccup only** — no components, no `:on-*` handlers, no `:ref`. It is rendered once through the DOM renderer's internal host-render and is never mounted or unmounted as a component tree, so nothing in it is reactive. |
| `:on-unsupported` | `hammer.gl` | `(fn [reason])` called when WebGL2 is unavailable. |
| `:attrs` | all | Extra attributes for the `<canvas>` element (`:class`, `:style`, `:aria-label`, …). Values follow the DOM attribute rules: `nil`/`false` remove, `true` writes an empty attribute, `aria-*` and `draggable` take `"true"`/`"false"`. `:class` and `:style` take a string or collection and a map. |

The draw fn's arguments:

- `hammer.canvas`: `(fn [ctx info])`, or `(fn [ctx info res])` when `:init` is
  given. `ctx` is the `CanvasRenderingContext2D`, already scaled by
  `devicePixelRatio` so you draw in CSS pixels; hammer never clears it.
- `hammer.gl`: `(fn [gl info])`, or `(fn [gl info res])` with `:init`. `gl` is
  the raw `WebGL2RenderingContext`; hammer calls `gl.viewport(0, 0,
  drawingBufferWidth, drawingBufferHeight)` before each draw (the counterpart
  of Canvas 2D's DPR scaling) and never clears it.
- `info` is `{:w :h :dpr}` in CSS pixels; for `defloop` it also has `{:t :dt :n}`:
  elapsed time in ms since the loop started, the capped delta since the last
  frame, and a frame counter.
- `res` is whatever `:init` returned.

**Mounting:** a draw component used inside `defc` hiccup (`[chart]`, keys work
as usual) renders its own `<canvas>` in place. To mount one standalone on a
canvas, without `hammer.core`/`hammer.dom` in the bundle, use
`hammer.canvas/mount!` or `hammer.gl/mount!` — same shape as `hammer.core/mount!`,
taking `(hiccup el)` or `(hiccup el db)`; `el` is an existing `<canvas>`
(adopted as-is) or a container (a canvas is created inside it). All three
`mount!`s share one root registry: mounting any of them on an element first
unmounts whatever another one mounted there.

`bb sizes` builds the three size-check bundles (`hammer.core`-only,
`hammer.canvas`-only, `hammer.gl`-only), prints each one's raw and gzip size,
and checks each build's shadow `manifest.edn` `:sources` for the other
variants' namespaces (dom bundle must not carry `hammer.draw`/`hammer.canvas`/`hammer.gl`,
and so on), failing with exit 1 if one leaks in.

3D (meshes, cameras, materials, a scene graph on top of `hammer.gl`) is a TODO,
not part of this API.

See `examples/canvas` (a scatter chart with click-to-select, plus bouncing
balls with a pause button) and `examples/gl` (a WebGL2 colour swatch and a
pulsing loop, plus a shader-drawn triangle, with a fallback message when
WebGL2 is unavailable).

WebGL2 needs no flags; each `hammer.gl` component owns a context and browsers
cap live contexts at about 16 per page. Chromium evicts the oldest context
when the cap is exceeded; the component stays blank until the browser
restores it, which Chromium does only after another WebGL context has been
garbage-collected — possibly much later. Keep live `hammer.gl` components
well under the cap.

## Working on hammer itself

`npm install`, `npm test`, `npx shadow-cljs watch todomvc` → http://localhost:8280

`npx shadow-cljs watch canvas-demo` → http://localhost:8290, `npx shadow-cljs watch
gl-demo` → http://localhost:8291 (`examples/canvas`, `examples/gl`).

Without shadow-cljs: `examples/counter` uses `deps.edn` and figwheel-main, with hammer
as a `:local/root` dep (`cd examples/counter && clj -M:dev` → http://localhost:9500).

| Task | |
|---|---|
| `npm test` | the test suite (shadow-cljs `:node-test`, jsdom) |
| `bb loc` | non-blank, non-comment lines in `src/hammer` |
| `bb sizes` | builds the dom/canvas/gl size apps, prints raw and gzip sizes, fails if a bundle carries another variant's namespaces |
| `bb tokens` | TodoMVC token count, hammer vs re-frame |
| `bb bench-canvas` | the canvas benchmark ([bench/canvas](../bench/canvas/README.md)) |

CI (`.github/workflows/ci.yml`) runs the tests, `bb sizes`, the release builds
and a jar build on every push to `main` and every pull request.

**Releasing:** tag a version and push the tag:

    git tag v0.1.0 && git push origin v0.1.0

`.github/workflows/release.yml` then checks the tag, runs the tests, builds
`target/hammer-0.1.0.jar` and creates a GitHub release with the jar attached.
Apps use the tag through the git coordinate above.

Clojars is optional and off: the workflow deploys `io.github.sstoehrm/hammer`
there only when the repository secrets `CLOJARS_USERNAME` and
`CLOJARS_PASSWORD` (a Clojars deploy token) are set. A Clojars jar is public,
source included.
Locally: `bb jar 0.1.0`, or `clojure -T:build deploy :version '"0.1.0"'`.

The repository-local `hammer-internals` skill (`.claude/skills/`) explains the
internals to an agent working on hammer itself.
