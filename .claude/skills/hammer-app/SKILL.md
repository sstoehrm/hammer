---
name: hammer-app
description: Use when writing a web app UI with the hammer ClojureScript library (hammer.core) — setup, components, db paths, events, effects, DOM attributes, forms and inputs, event handlers, routing, keyed lists, mounting, errors.
---

# hammer for app authors

re-frame-style events without React or subscriptions. A component names the
app-db paths it reads and re-renders only when one of them changes.

This sheet covers hammer's whole public API (`defc`, `is?`, `reg-event`,
`reg-fx`, `dispatch`, `dispatch-sync`, `mount!`) and every DOM behaviour an
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
- The body re-renders only when a binding it names changes. A raw `@app-db` or
  `@some-atom` in the body never triggers a render: bind it instead.
- Use a component as `[comp arg1 arg2]` in hiccup. Its args are compared with
  `=`; an inline `fn` arg is never `=`, so the child re-renders every time.

## Events and effects

- `(reg-event :id (fn [db & args] effect-map))`: the handler gets the db and the
  event's arguments, **not** the event vector. `[:save 1 "x"]` calls `(f db 1 "x")`.
- It must return an effect map: `{:db new-db}`, plus optionally `:dispatch [ev]`
  (one event) and any `reg-fx` key. `:db` is applied first, then `:dispatch`,
  then the other keys. Returning `nil` does nothing. Returning the db itself
  runs every top-level db key as an effect: always wrap it in `{:db …}`.
- `(reg-fx :k (fn [value] ...))` registers side effects for key `:k` in an effect
  map. An fx is not an event: `(dispatch [:k])` on an fx id fails with
  "no event handler for :k".
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
- Attribute values: `nil`/`false` remove the attribute, `true` writes an
  **empty** attribute (`:disabled true` → `disabled=""`, `:disabled false` →
  no attribute), anything else is written with `str`. Enumerated attributes
  need strings: `:draggable "true"`, not `true`.
- `:style` must be a map with CSS property names: `{:width (str pct "%")
  :background-color "red"}`, not `:backgroundColor`; a style string does not
  work. Values are written with `str`; a key missing in the next render is
  removed.
- `:ref` gets the element after insertion and `nil` on removal:
  `:ref #(some-> % .focus)`.

## Forms and inputs

- `:value`, `:checked`, `:selected` are set as DOM **properties** and compared
  with the live element, so keep the current value in the db (or a local atom).
- Text input: `[:input {:value title :on-input #(dispatch [:set-title (.. % -target -value)])}]`.
- Checkbox: `[:input {:type "checkbox" :checked done? :on-change #(dispatch [:toggle id])}]`.
- `<select>`: `[:select {:value picked :on-change #(dispatch [:pick (.. % -target -value)])} …]`
  selects the right option; the options are created before the value is set.
- `<option>` **quirk**: `[:option {:value "a"} "a"]` writes no `value`
  attribute when the value equals the text (the property already reads "a", so
  hammer skips the write). Selecting still works, but the DOM shows
  `<option>a</option>`. When the markup must contain `value="…"`, use a
  string key: `[:option {"value" "a"} "a"]` (that element is rendered as plain
  data, same result). A value that differs from the text is always written.
- Form submit: `[:form {:on-submit #(do (.preventDefault %) (dispatch [:save]))} …]`.
- File input: `:on-change #(-> (.. % -target -files) (aget 0) (.text) (.then (fn [t] (dispatch [:csv t]))))`.

## Event handlers (`:on-<event>`)

- An event vector is dispatched **exactly as written**; nothing from the DOM
  event is appended. `:on-click [:delete id]` is fine; anything that needs the
  DOM event or the input's value needs a fn.
- A fn gets the DOM event. Handlers are delegated from the mount container, so
  `(.-currentTarget e)` is the container; use `(.-target e)` or close over
  what you need. `(.preventDefault e)` and `(.stopPropagation e)` work.
- Drag and drop: `:draggable "true"`, and call `(.preventDefault e)` in
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

Key list items with metadata: `^{:key id} [row id]` or `^{:key id} [:li …]`.
Keys must be on every item and unique; otherwise hammer falls back to index
diffing and warns in the console. `:key` in the attrs map is not a key.

## Mounting

`(mount! hiccup el db)` sets the db and renders into `el` (replacing its
content); `(mount! hiccup el)` keeps the current db. Call it once: later db
changes re-render the affected components by themselves.

## Where mistakes show up

hammer never throws for these; it logs to the **browser console** with the
prefix `hammer:` and keeps the old DOM:
"no event handler for :x", "no fx registered for :k — did the handler return db
instead of {:db db}?", "handler must return an effect map", "event handler
failed", "fx failed", "update failed in <component>", "duplicate keys". Check
the browser console first when the UI does not react.
