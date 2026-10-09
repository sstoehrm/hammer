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
Other namespaces have their own page in this skill's directory, read only when
you use them: `tracks-tubes.md` (`hammer.track`: events on db changes,
`hammer.tubes`: events over a WebSocket), `canvas.md` (`hammer.canvas`,
`hammer.gl`: `defdraw`, `defloop`), `testing.md` (`hammer.testing`).

## Setup

- hammer is a git dependency in `deps.edn` and needs no npm packages of its
  own: `io.github.sstoehrm/hammer {:git/url "git@github.com:sstoehrm/hammer.git"
  :git/tag "<tag>" :git/sha "<short sha of the tag>"}`.
- With shadow-cljs, `:deps true` in `shadow-cljs.edn` makes shadow-cljs read
  `deps.edn`, so hammer and `thheller/shadow-cljs` go there. Don't add
  `org.clojure/clojurescript`: shadow-cljs brings the version it needs, and a
  pin overrides it: shadow-cljs 3.5.4 needs 1.12.145, and with a 1.12.42 pin
  the build fails with "No such var: ana/elide-to-string?".
- Require `hammer.core`; the build's `:init-fn` calls `mount!` once.
- Any optimization level works, `:advanced` included. Under `:advanced`, hint
  JS objects in your own interop with `^js` (`(fn [^js file] (.-name file))`),
  or their property names get renamed.

## Example

```clojure
(ns app.core
  (:require [hammer.core :refer [defc reg-event reg-fx dispatch mount!]]
            [hammer.http]))                ; registers the :http effect

(reg-event :load (fn [_] {:http {:uri "/api/todos" :on-success [:loaded]}}))
(reg-event :loaded (fn [db todos] {:db (assoc db :todos (vec todos))}))
(reg-event :add (fn [db title] {:db (update db :todos conj {:title title})}))

(defc todo-row [i]                        ; props: plain symbols
  [todo [:todos i]                        ; vector literal = db path
   open (atom false)                      ; atom = local state, created once
   cls  (when (:done todo) "line-through")] ; anything else = derived
  [:li {:class cls :on-click #(swap! open not)} (:title todo)])

(defc page [] [todos [:todos]]            ; the bindings vector is required, even []
  [:ul (for [i (range (count todos))] ^{:key i} [todo-row i])])

(defn init []
  (mount! [page] (js/document.getElementById "app") {:todos []})
  (dispatch [:load]))
```

## Components: `(defc name [props] [bindings] body)`

| Binding init | Meaning |
|---|---|
| `[:a b]` vector literal | db path, `(get-in db [:a b])`; may use props and earlier bindings: `[:items id]` |
| `(is? [:selected] id)` as the whole init | `true` iff the value at the path `=` id; re-renders only the rows that flip |
| `(atom x)` | local state; the init runs once per instance, later prop changes are ignored |
| anything else | derived from the props and earlier bindings it names |

- Props and binding names must be plain symbols: no destructuring.
  `(defc bar [{:keys [label total]}] [] …)` fails to compile with "props and
  binding names must be plain symbols". Take the map and derive:
  `(defc bar [row] [label (:label row) total (:total row)] …)`. Destructuring
  inside the body (`let`, `fn` args) is fine.
- `(vector a b)` is a value; `[a b]` is always a path.
- The body re-renders only when a binding it names changes, or when a global
  atom it derefs directly changes: `[:span (count @cart)]` and
  `[n (count @cart)]` track `cart` (the code still reads `@cart` itself).
  Not tracked, so bind the atom yourself (`[c cart]`, then `@c`): a deref
  inside any `fn`/`#(…)` (including `(map (fn [x] … @cart) xs)`) or inside a
  helper function, `(deref (f))`, and vars defined after the component.
  `@^:once config` reads without tracking.
- Binding names count wherever they appear in an init or the body, inside
  `fn`/`#(…)` too: `[shown (filterv #(visible? % search) cards)]` recomputes
  when `search` or `cards` changes. Only derefs of global atoms inside fns
  go untracked (above).
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
## HTTP: the `:http` effect

`(:require [hammer.http])` registers the `:http` effect (fetch-based). Use it
for every request, uploads included, instead of a `reg-fx` around `js/fetch`:

```clojure
(reg-event :search (fn [_ q] {:http {:uri "/api/items" :params {:q q}   ; GET, params → query
                                     :on-success [:loaded] :on-failure [:failed]}}))
(reg-event :save (fn [db] {:http {:method :post :uri "/api/items" :body (:draft db) ; clj → JSON
                                  :on-success [:saved] :on-failure [:failed]}}))
(reg-event :upload (fn [_ file] {:http {:method :post :uri "/api/import" :body file ; File as is
                                        :headers {"Content-Type" "text/csv"}
                                        :on-success [:imported] :on-failure [:failed]}}))
(reg-event :failed (fn [db {:keys [status response]}] {:db (assoc db :error response)}))
```

- `:body`: a map, vector, seq or set is sent as JSON (`Content-Type` set for
  you); a string, `js/File` or `js/Blob` is sent as is, with the `:headers`
  you give. A GET with a `:body` is rejected: use `:params`.
- The response is appended to the `:on-success` event, JSON keywordized
  (`:keywords? false` keeps string keys); a 204 or empty body gives `nil`.
- `:on-failure` gets `{:uri :status :status-text :failure :response}`:
  `:failure` is `:error` for non-2xx, with the parsed error body in
  `:response`, or `:network`, `:timeout`, `:parse`. Without `:on-failure` the
  failure is reported (and fails tests).
- Other keys: `:timeout`, `:response-format` (`:json` default, `:text`,
  `:blob`, `:raw`), `:abort-key` (a newer request with the same key cancels
  the older one, silently), `:fetch-options` (merged into fetch's options:
  `{:keepalive true}` lets a save finish when the page reloads or navigates
  right after it, `{:credentials "include"}` sends cookies cross-origin; the
  method, headers, body and abort signal come from the request map). A vector
  of request maps runs each.
- In tests, stub with `(hammer.http/set-fetch! (fn [url init] promise))`.

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
  root is `[:circle]` works inside an `:svg`. Attribute names keep their case;
  use `:href`, not `:xlink:href`. A canvas component inside an `:svg` needs a
  `:foreignObject` around it.
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
- File input: `:on-change #(dispatch [:upload (aget (.. % -target -files) 0)])`, then
  send the file with `:http` (above), or read it with `(.text file)`.

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

## Large data and hot loops

- Large data you only read (a layout result, a scene to hit-test) can stay a
  plain JS value under one db key. It is compared by reference: a new
  reference re-renders the components that bind the key, an in-place mutation
  re-renders nothing. A path can't reach a JS object's fields (`get` returns
  `nil`), so bind the key and read the fields in the body or a derived
  binding. Convert to persistent data only what you `assoc` into or bind
  paths inside.
- Scratch state that never reaches the db (an accumulator in a hot loop, the
  sets built while walking a graph) stays mutable: a local JS object or
  array, a `volatile!`, or transients, not an atom of a persistent map with a
  `swap!` per step. When porting plain-JS code, look for exactly that.

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
hammer.testing makes them fail the test (`testing.md`).
