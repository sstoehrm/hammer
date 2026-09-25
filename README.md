# cljs-ui

re-frame's events, without React or subscriptions. Components declare the db
paths they read; a path trie marks exactly the components whose paths changed,
and each re-renders by diffing only its own hiccup.

```clojure
(require '[cljs-ui.core :refer [defc reg-event reg-fx dispatch dispatch-sync mount!]])

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

Hiccup: `:on-<dom-event>` takes an event vector or fn; `:ref` fn gets the element
(and `nil` on removal); `:class` string or collection; `:style` map.

An `(atom ...)` binding is created once per instance and does not follow later
prop changes. A vector literal binding is always a path; use `(vector a b)` for
a vector value.

## Measured

- Core size (`bb loc`): 604 lines
- TodoMVC tokens vs re-frame (`bb tokens`): 52.5% fewer. Same features (add, toggle,
  toggle all, edit, delete, clear completed, filters, localStorage); re-frame's
  example also validates the db with spec and routes with secretary, ours
  validates nothing and routes with one `hashchange` listener.

## Develop

`npm install`, `npm test`, `npx shadow-cljs watch todomvc` → http://localhost:8280
