---
name: hammer-app
description: Use when writing a web app UI with the hammer ClojureScript library (hammer.core, hammer.canvas, hammer.gl) — setup, components, db paths, events, effects, DOM attributes, forms and inputs, event handlers, routing, keyed lists, mounting, canvas and WebGL2 drawing (defdraw, defloop), errors, testing.
---

# hammer for app authors

re-frame-style events without React or subscriptions. A component names the
app-db paths it reads and re-renders only when one of them changes.

This sheet covers hammer's whole public API (`defc`, `is?`, `reg-event`,
`reg-fx`, `dispatch`, `dispatch-sync`, `mount!`, `on-error!`, and `hammer.testing`)
and every DOM behaviour an
app depends on. Everything below was checked against hammer's source and in a
browser, so you should not need to read hammer's source.

## Setup

- hammer is a git dependency in `deps.edn` and needs no npm packages of its
  own: `io.github.sstoehrm/hammer {:git/url "git@github.com:sstoehrm/hammer.git"
  :git/sha "<commit>"}`.
- With shadow-cljs, `:deps true` in `shadow-cljs.edn` makes shadow-cljs read
  `deps.edn`, so hammer and `thheller/shadow-cljs` go there.
- Require `hammer.core`; the build's `:init-fn` calls `mount!` once.
- Any optimization level works, `:advanced` included.

## Example

```clojure
(ns app.core
  (:require [hammer.core :refer [defc reg-event reg-fx dispatch mount!]]))

(reg-event :add (fn [db title] {:db (update db :todos conj {:title title})}))

(defc todo-row [i]                        ; props
  [todo [:todos i]                        ; vector literal = db path
   open (atom false)                      ; atom = local state, created once
   cls  (when (:done todo) "line-through")] ; anything else = derived
  [:li {:class cls :on-click #(swap! open not)} (:title todo)])

(defc page [] [todos [:todos]]            ; the bindings vector is required, even []
  [:ul (for [i (range (count todos))] ^{:key i} [todo-row i])])

(defn init []
  (mount! [page] (js/document.getElementById "app") {:todos []}))
```

## Components: `(defc name [props] [bindings] body)`

| Binding init | Meaning |
|---|---|
| `[:a b]` vector literal | db path, `(get-in db [:a b])`; may use props and earlier bindings: `[:items id]` |
| `(is? [:selected] id)` as the whole init | `true` iff the value at the path `=` id; re-renders only the rows that flip |
| `(atom x)` | local state; the init runs once per instance, later prop changes are ignored |
| anything else | derived from the props and earlier bindings it names |

- `(vector a b)` is a value; `[a b]` is always a path.
- The body re-renders only when a binding it names changes, or when a global
  atom it derefs directly changes: `[:span (count @cart)]` and
  `[n (count @cart)]` track `cart` (the code still reads `@cart` itself).
  Not tracked, so bind the atom yourself (`[c cart]`, then `@c`): a deref
  inside any `fn`/`#(…)` (including `(map (fn [x] … @cart) xs)`) or inside a
  helper function, `(deref (f))`, and vars defined after the component.
  `@^:once config` reads without tracking.
- `@app-db` is a compile warning: read the db through path bindings
  (`[todos [:todos]]`). For per-row state like a selection, prefer
  `(is? [:selected] id)` over a global atom: every row that derefs the atom
  re-renders when it changes.
- Use a component as `[comp arg1 arg2]` in hiccup. Its args are compared with
  `=`; an inline `fn` arg is never `=`, so the child re-renders every time.
- The props and bindings vectors are both required: a missing one is a compile
  error (`defc page: missing bindings vector, write (defc page [] [] body)`).

## Events and effects

- `(reg-event :id (fn [db & args] effect-map))`: the handler gets the db and the
  event's arguments, **not** the event vector. `[:save 1 "x"]` calls `(f db 1 "x")`.
- It must return an effect map: `{:db new-db}`, plus optionally `:dispatch [ev]`
  (one event) and any `reg-fx` key. `:db` is applied first, then `:dispatch`,
  then the other keys. Returning `nil` does nothing. Returning the db itself
  (no `:db`, `:dispatch` or registered fx key) runs nothing and reports
  "handler for :id returned no known effect keys (…)": wrap it in `{:db …}`.
  A key with no `reg-fx` reports "no fx registered for :k".
- `(reg-fx :k (fn [value] ...))` registers side effects for key `:k` in an effect
  map. An fx is not an event: `(dispatch [:k])` on an fx id reports
  "no event handler for :k (:k is an fx: return {:k value} from an event handler)".
- `(dispatch [:id & args])` queues an event (microtask) and works from
  anywhere: handlers, promise callbacks, `js/window` listeners, timers.
  `dispatch-sync` runs it and renders now, and throws inside a handler.
- HTTP pattern:

```clojure
(reg-fx :http (fn [{:keys [method url body on-ok]}]
  (-> (js/fetch url (clj->js (cond-> {:method (or method "GET")}
                               body (assoc :headers {"Content-Type" "application/json"}
                                           :body (js/JSON.stringify (clj->js body))))))
      (.then #(.json %))
      (.then #(dispatch (conj on-ok (js->clj % :keywordize-keys true)))))))
(reg-event :load (fn [_] {:http {:url "/api/items" :on-ok [:loaded]}}))
(reg-event :loaded (fn [db items] {:db (assoc db :items items)}))
```

## Hiccup and DOM attributes

- Tags accept `:div#id.cls`. `:class` takes a string or a collection (`nil`s
  are dropped). Any other keyword attribute is written as is, e.g.
  `:data-testid`, `:aria-label`, `:type`, `:href`.
- Children: strings and numbers render as text, `nil` and `false` render
  nothing, a seq (`for`, `map`) is spliced in.
- Attribute values: `nil` removes the attribute; `true`/`false` add an
  **empty** attribute or remove it (`:disabled true` → `disabled=""`,
  `:disabled false` → no attribute), except `draggable`, `spellcheck`,
  `contenteditable`, `writingsuggestions` and every `aria-*`, which get the
  strings `"true"`/`"false"` (`:aria-expanded false` → `aria-expanded="false"`).
  Anything else is written with `str`.
- `:style` is a map with CSS property names, `{:width (str pct "%")
  :background-color "red"}` (dev builds warn on `:backgroundColor`), or a CSS
  string, `"color: red"`. Map values are written with `str`; a key missing in
  the next render is removed.
- SVG: `[:svg {:viewBox "0 0 10 10"} [:circle {:r 5}]]` creates SVG elements
  from `:svg` down, `foreignObject` content is HTML again; a component whose
  root is `[:circle]` works inside an `:svg`. Attribute names keep their case.
- `:ref` gets the element after insertion and `nil` on removal:
  `:ref #(some-> % .focus)`.

## Forms and inputs

- `:value`, `:checked`, `:selected` are set as DOM **properties** and compared
  with the live element, so keep the current value in the db (or a local atom).
- Text input: `[:input {:value title :on-input #(dispatch [:set-title (.. % -target -value)])}]`.
- Checkbox: `[:input {:type "checkbox" :checked done? :on-change #(dispatch [:toggle id])}]`.
- `<select>`: `[:select {:value picked :on-change #(dispatch [:pick (.. % -target -value)])} …]`
  selects the right option; the options are created before the value is set.
- `[:option {:value "a"} "a"]` always writes `value="a"`; `{:value nil}` writes
  `value=""` (a "none" placeholder option).
- Form submit: `[:form {:on-submit #(do (.preventDefault %) (dispatch [:save]))} …]`.
- File input: `:on-change #(-> (.. % -target -files) (aget 0) (.text) (.then (fn [t] (dispatch [:csv t]))))`.

## Event handlers (`:on-<event>`)

- An event vector is dispatched **exactly as written**; nothing from the DOM
  event is appended. `:on-click [:delete id]` is fine; anything that needs the
  DOM event or the input's value needs a fn.
- A fn is called with the DOM event and the element the handler is on:
  `(fn [e el] …)`; `(fn [e] …)` and `#(…)` work too, but a multi-arity fn needs
  a 2-arity. Handlers are delegated from the mount container, so
  `(.-currentTarget e)` is the container, not the element: use `el`.
  `(.preventDefault e)` and `(.stopPropagation e)` work.
- Drag and drop: `:draggable true`, and call `(.preventDefault e)` in
  `:on-dragover` so `:on-drop` fires.

## Routing

hammer has no router. Keep the path in the db and render by it:

```clojure
(reg-fx :push-url (fn [path] (.pushState js/history nil "" path)))
(reg-event :navigate (fn [db path] {:db (assoc db :route path) :push-url path}))
(reg-event :route-changed (fn [db path] {:db (assoc db :route path)}))

(defc app [] [route [:route]]
  (case route "/summary" [summary-page] [list-page]))

;; links: [:a {:href "/summary" :on-click #(do (.preventDefault %) (dispatch [:navigate "/summary"]))} "Summary"]
;; in init, before mount!, with :route (.-pathname js/location) in the initial db:
(.addEventListener js/window "popstate" #(dispatch [:route-changed (.-pathname js/location)]))
```

The server must answer such paths with the same `index.html`.

## Lists

Key list items with metadata, `^{:key id} [row id]` or `^{:key id} [:li …]`, or,
for an element, with `:key` in its attrs: `[:li {:key id} …]` (not written as an
attribute; metadata wins). A component vector takes only the metadata form. Keys
must be on every item and unique; otherwise hammer falls back to index diffing
and warns (duplicates always, a list mixing keyed and unkeyed items in dev
builds).

## Mounting

`(mount! hiccup el db)` sets the db and renders into `el` (replacing its
content); `(mount! hiccup el)` keeps the current db. Call it once: later db
changes re-render the affected components by themselves.

## Canvas and WebGL2: `defdraw`, `defloop`

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

## Where mistakes show up

hammer never throws for a failing handler, fx or render, or for a mistake like
a missing handler: it reports it and keeps the old DOM. Reports go to the
**browser console** (`hammer: …`) unless the app replaced the reporter:

```clojure
(on-error! (fn [{:keys [level message error]}] ...))   ; level :error or :warn
(on-error! nil)                                         ; back to the console
```

At compile time, `@app-db` in a component warns
(`:hammer.macros/app-db-deref`, with the line). At runtime the messages are:
"no event handler for :x", "no fx registered for :k", "handler for :x
returned no known effect keys (…) - did it return db instead of {:db db}, or miss
a reg-fx?", "handler must return an effect map", "event must be a vector",
"event handler failed [:x …]", "fx failed :k", "render failed in <component>",
"update failed in <component>", ":ref failed", "duplicate keys", "some list items
have no key", and in dev builds ":on-click must be an event vector or a fn, got
:save". Check the console first when the UI does not react; in tests,
hammer.testing makes them fail the test (below).

## Testing

`hammer.testing` (node or browser tests, e.g. shadow-cljs `:node-test` with jsdom):

- `(t/reset-app!)` as a `:before` fixture: unmounts every root, empties the db.
- `(t/flush!)` runs queued events and renders synchronously, then **throws** if
  hammer reported an error since the last check (`ex-data` has `:errors`).
  `dispatch-sync` alone never throws for a report: end such tests with
  `(t/check-errors!)`, or use it as an `:after` fixture.
- `(t/expect-errors f)` runs `f` and returns the reports
  (`[{:level :message :error}]`) instead of failing; for testing error paths.
- `(t/renders comp)` / `(t/reset-renders! comp)`: render counts, to check that
  only the expected components re-rendered.
- Draw components: `(t/use-fake-frames!)` in the `:before` fixture, then
  `(t/frame! ms)` flushes and draws one frame at time `ms` (and throws like
  `flush!`). Node has no canvas: stub `getContext` on the element.
- Handlers and fxs are global and `reset-app!` keeps them: in tests that
  register their own, use namespaced ids (`::add`) so test namespaces can't
  overwrite each other's.

```clojure
;; (:require [cljs.test :refer [deftest is use-fixtures]] [hammer.testing :as t] …)
(use-fixtures :each {:before t/reset-app!})
(deftest add-todo
  (let [el (js/document.createElement "div")]
    (mount! [page] el {:todos []})
    (dispatch [:add "milk"])
    (t/flush!)
    (is (= "milk" (.-textContent (.querySelector el "li"))))))
```
