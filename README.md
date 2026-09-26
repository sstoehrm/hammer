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

`defc` recognizes it by symbol: unqualified `is?`, `hammer.core/is?`, or
`alias/is?` where the alias names `hammer.core`. Anywhere else, e.g. nested
inside another expression, calling `is?` throws.

The 2-arity `mount!` renders without touching `app-db`; call it from a
`^:dev/after-load` hook so a hot reload re-renders with whatever db state the
running app already has, instead of resetting it.

## Testing

`hammer.testing` provides:

- `(flush!)` — drains queued events, then renders until nothing is dirty
  (synchronous equivalent of the event + render microtasks).
- `(renders c)` / `(reset-renders! & cs)` — a component's render count since
  the last reset; use to assert that only the expected components re-rendered.
- `(reset-app!)` — unmounts every root and empties `app-db`; use as a
  `:before` fixture between tests.

## Measured

- Core size (`bb loc`): 1078 lines
- TodoMVC tokens vs re-frame (`bb tokens`): 52.5% fewer. Same features (add, toggle,
  toggle all, edit, delete, clear completed, filters, localStorage); re-frame's
  example also validates the db with spec and routes with secretary, ours
  validates nothing and routes with one `hashchange` listener.

## Develop

`npm install`, `npm test`, `npx shadow-cljs watch todomvc` → http://localhost:8280

Without shadow-cljs: `examples/counter` uses `deps.edn` and figwheel-main, with hammer
as a `:local/root` dep (`cd examples/counter && clj -M:dev` → http://localhost:9500).
