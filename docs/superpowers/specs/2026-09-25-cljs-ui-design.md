# cljs-ui — design

Date: 2026-09-25
Status: draft, awaiting review
Figure: `.blend/specs/2026-09-25-cljs-ui.edn` (serve with `simpleviz`)

## Goal

A ClojureScript UI framework that combines re-frame's event model with a
Svelte-like component-level dirty tracking, without React and without a
template compiler. It must be token efficient in two senses:

1. **App code** is short: little boilerplate, one way to do each thing.
2. **Framework source** is small enough to read into context in one go.

`cljs-ui` is a working name.

## Success criteria

| Criterion | Measure |
|---|---|
| App code is token efficient | TodoMVC in cljs-ui uses ≥ 30% fewer tokens than the re-frame reference TodoMVC, same tokenizer |
| Framework is small | Core (events, trie, `defc`, diff) ≤ ~800 LOC |
| Update cost follows the change | 1000 mounted rows, toggle one: exactly 1 component render (asserted by test) |

## Non-goals (v1)

SSR, routing, devtools, animations/transitions, lifecycle hooks beyond `:ref`,
coeffects, interceptors.

## Architecture

```
DOM event ─▶ dispatch ─▶ queue ─▶ handler [db & args] ─▶ effect map
                                                           │
                          ┌── :db ──▶ app-db reset! + trie/notify old new
                          ├── :dispatch ─▶ queue
                          └── other ─▶ reg-fx
trie/notify ─▶ stale path cells ─▶ dirty instances ─▶ scheduler (microtask, parents first)
  ─▶ recompute cells ─▶ changed? ─▶ render ─▶ keyed hiccup diff ─▶ DOM patch
```

Units, each testable on its own:

| Unit | Responsibility | Depends on |
|---|---|---|
| `state` | `app-db` atom and the root trie node | `trie` |
| `events` | registry, queue, effect application | `state`, `trie`, `cells`, `scheduler` |
| `trie` | path subscriptions, change propagation with pruning | — |
| `cells` | per-instance binding cells, lazy recompute, change detection | `state`, `trie`, `scheduler` |
| `scheduler` | dirty set, depth-ordered flush; calls a runner that `dom` installs | — |
| `dom` | hiccup normalization, keyed diff, patch, mount/unmount | `cells`, `events`, `scheduler` |
| `core` | public API (`defc` macro, re-exports) | all |
| `testing` | `flush!`, render counters, `reset-app!` | `events`, `scheduler`, `dom` |

## Public API

Six vars in one namespace: `reg-event`, `reg-fx`, `dispatch`, `dispatch-sync`,
`defc`, `mount!`.

```clojure
(reg-event :todo/add
  (fn [db text] {:db (update db :todos conj text)
                 :dispatch [:save]}))
(reg-fx :http (fn [opts] ...))
(dispatch [:todo/add "milk"])        ; queued, drained in a microtask
(dispatch-sync [:todo/add "milk"])   ; immediate, then synchronous render flush

(defc todo-item [id]                 ; positional props
  [todo  [:todos id]                 ; path binding
   edit? (atom false)                ; local binding
   cls   (when (:done todo) "done")] ; derived binding, deps: #{todo}
  [:li {:class cls :on-click [:todo/toggle id]}
   (:text todo)])

(defc app []
  [ids [:todo-ids]]
  [:ul (for [id ids] ^{:key id} [todo-item id])])

(mount! [app] (js/document.getElementById "app") initial-db)
```

### Events

- Handler signature: `(fn [db & args] effect-map)`. `args` is the event vector
  without its id. No coeffects, no interceptors.
- Built-in effects: `:db` (new db) and `:dispatch` (one event vector).
  Everything else, including delayed dispatch, is a `reg-fx`.
- Effect order: `:db`, then `:dispatch`, then remaining keys in unspecified order.
- A `reg-fx` fn is called with the effect's value.

### `defc`

`(defc name [props*] [bindings*] body)`. The bindings vector is let-like; the
kind of each binding is decided by the form of its init expression:

| Init form | Kind | Behaviour |
|---|---|---|
| vector literal (`[:todos id]`) | path | value = `(get-in db path)`; subscribes the path in the trie; elements may reference props and are re-resolved when those props change |
| `(atom ...)` or other `IWatchable` | local | created once per instance at mount; `add-watch` marks the cell stale; the symbol is bound to the atom (deref in body) |
| anything else | derived | macro collects symbols referring to props or earlier bindings → dependency set; re-run only when a dependency changed |

Props and binding names must be distinct plain symbols; `defc` throws at
compile time otherwise. A vector literal is always a path; build a vector
value with `(vector a b)`.

The kind is decided at macro time for vector literals; everything else is
decided at mount by evaluating the init once: an `IWatchable` result is a
local, otherwise the expression is derived. A derived expression reads
only its dependencies, so it can never observe stale db data. Symbol
collection over-approximates (e.g. shadowed names); the cost is at most an
extra recompute.

A local binding does not follow later changes to the props it was created
from (same as Reagent form-2). This is documented behaviour.

### Hiccup

- Tags: `:div`, `:div.a.b#id`. Children: elements, strings, numbers, seqs
  (flattened), `nil`/`false` (skipped), `[component & args]`.
- `:on-<event>`: `<event>` is the literal DOM event name (`:on-click`, `:on-keydown`,
  `:on-dblclick`, `:on-input`). An event vector → `(dispatch v)`; a fn → called
  with the DOM event.
- `:ref`: fn called with the element after insertion and with `nil` on removal.
- `:class`: string or collection of strings. `:style`: map, applied per key.
- `value`, `checked`, `selected` are set as DOM properties; everything else
  via `setAttribute`.
- Keys: `^{:key k}` metadata only. Keyed diffing applies when every child has a
  key and keys are unique; duplicate keys log a warning and fall back to the
  index diff.

## Runtime

### Event loop

- `dispatch` pushes onto a JS array; if no drain is pending, schedules one
  microtask to drain the queue.
- Processing an event: look up handler, `(apply h @app-db args)`, apply effects.
- Applying `:db`: `old = @app-db`, `reset!`, then `(trie/notify old new)`.
  There is no `add-watch` on app-db; the fx runner is the only writer.
- `dispatch-sync`: processes the event immediately, then runs a synchronous
  flush. Calling it from inside a handler throws.

### Path trie

- Node: `{children: Map<key,node>, cells: Set<cell>, refs: int}`.
- Mount registers each path cell's path; unmount unregisters. Nodes are
  ref-counted and removed when empty.
- `notify(old, new, node)`: for each child key `k`, let `o = (get old k)`,
  `n = (get new k)`. If `(identical? o n)`, skip the branch. Otherwise mark
  the child's cells stale, add their instances to the dirty set, recurse.
- Cost: `identical?` checks over the subscribed siblings of every changed
  node. Toggling one of 1000 rows costs ~1000 `identical?` checks at
  `[:todos]` and marks one cell stale.

### Cells and change detection

- Each instance holds an array of cells in binding order:
  props, path, local, derived. A derived cell stores its dependency indices.
- Marking is eager (trie notify, atom watch, new props); computation is lazy
  and happens at flush:
  1. stale path cells re-read `(get-in db path)`;
  2. derived cells whose dependencies changed re-run, in binding order;
  3. each new value is compared with `identical?`, then `=`.
- The instance renders only if at least one cell's value changed. A changed
  local atom always counts as changed.

### Scheduler

- A set of dirty instances; one microtask flush after the event drain.
- Flush order: by depth, parents first. When a parent's diff passes new props
  to a child, the child is updated inline and removed from the dirty set.
- Instances unmounted during the flush are skipped.

## Rendering

Each instance retains its last normalized hiccup, annotated with DOM node
references, as the diff baseline. This is a VDOM scoped to one component;
only dirty instances are diffed, never the whole tree.

| Case | Action |
|---|---|
| Different tag or component | unmount old, mount new |
| Same tag | patch attrs that are not `=` |
| Children, unkeyed | diff by index |
| Children, all keyed | patch common prefix/suffix, match the middle by key, move only nodes outside the longest increasing subsequence |
| Same component, all args `identical?` | skip |
| Same component, some arg changed | set new props, recompute and render inline |

- Events: one real listener per element per event type, reading the current
  handler from a slot on the element. Changing a handler never calls
  `addEventListener`/`removeEventListener`.
- `value` is written only when it differs from `el.value` (keeps the cursor).
- Unmount is depth-first: unregister trie paths, remove atom watches,
  call `:ref` with `nil`.

## Error handling

| Failure | Behaviour |
|---|---|
| Unknown event id or fx key | `console.error`, skip, continue; the fx message hints "did the handler return db instead of {:db db}?" |
| Dispatched value is not a vector | `console.error`, skip |
| Handler throws | log event + error, db unchanged, continue with the queue |
| Render throws | log component name, keep that instance's previous DOM, continue other instances |
| `dispatch-sync` inside a handler | throw |

## Testing

All tests drive rendering through `cljs-ui.testing/flush!`; no real microtask timing.
Run with `npm test` (`shadow-cljs compile test && node target/test.js`; the
node process exits non-zero on failure, shadow's `:autorun` does not).

| Layer | Env | Covers |
|---|---|---|
| events | pure CLJS | effect order, error handling, `dispatch-sync` guard |
| trie | pure CLJS | pruning, visit counts, register/unregister, ref counts |
| cells | jsdom | recompute only on dependency change, render counts |
| dom | jsdom | attrs, keyed moves, focus/cursor preserved, `:ref` calls |
| criteria | jsdom | 1000 rows, toggle one → exactly 1 render |

## Tooling

- shadow-cljs: `:node-test` build on jsdom; `:browser` build for the TodoMVC example.
- `bb tokens`: fetches the re-frame TodoMVC (`day8/re-frame`, `examples/todomvc`,
  pinned commit `1a1bf1df`), strips comments from both sides, counts tokens with
  `@anthropic-ai/tokenizer` via a node script. Exits non-zero below 30% saving.
- `bb loc`: counts non-blank, non-comment lines in `src/cljs_ui`; exits non-zero above 800.
- Pinned (verified 2026-09-25): shadow-cljs 3.5.3, jsdom 30.1.1,
  @anthropic-ai/tokenizer 0.0.4; Node 24, Java 21.
