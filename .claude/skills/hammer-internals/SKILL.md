---
name: hammer-internals
description: Use when reading, modifying, debugging, or building apps with the hammer ClojureScript UI framework (src/hammer) — components re-rendering too often or not at all, defc binding kinds (path, atom, derived), event handlers and effects, render order, keyed lists, defdraw/defloop canvas or WebGPU draw components, or changing dom/cells/trie/scheduler/draw/canvas/gpu code.
---

# hammer internals

## Overview

re-frame-style events without React or subscriptions. Components name the db paths
they read. A path trie marks only the instances whose paths changed. Each instance
diffs only its own hiccup, and child components are diff boundaries. The public API
and an example are in `README.md`; this skill covers what only the source shows.

## Files

| File | Responsibility |
|---|---|
| `core.clj` | `defc` macro: classifies bindings, computes deps by symbol name, compiles literal hiccup to templates |
| `macros.clj` | binding compilation shared by `defc` and `defdraw`/`defloop`: `binding-spec`, `deps-of`, `is?-form?` (recognizes `is?` unqualified or qualified on any facade — `hammer.core`, `hammer.app`, `hammer.canvas`, `hammer.gpu` — or an alias of one), `draw-def` (shared expansion for `defdraw`/`defloop` in `canvas.clj`/`gpu.clj`) |
| `core.cljs` | DOM facade: re-exports `hammer.app`, `defc`, `mount!` (2-arity keeps db) |
| `app.cljs` | shared event API used by every facade: `reg-event`, `reg-fx`, `dispatch`, `dispatch-sync`, `is?` |
| `events.cljs` | handler/fx registry, microtask queue, `set-db!`, effect processing |
| `trie.cljs` | path subscriptions; `notify!` walks only changed branches; per-node `is?` index value → cells |
| `cells.cljs` | `Instance` slots (`vals`, plus a `Cell` holding path/value/stale flag per path, `is?` or local slot): `create`, `set-props!`, `refresh!`, `render`, `destroy!`; `Comp`'s optional `Host` (`run`/`create`/`destroy`) lets `dom.cljs` delegate a component to a non-DOM renderer, and the single `sched/set-runner!` dispatches to it or to the default (`dom/update-inst!`); also the root registry (`unmount-root!`/`set-root!`/`unmount-roots!`, container el → unmount fn) shared by `dom/mount!` and `draw/mount!` |
| `scheduler.cljs` | dirty set, one microtask flush, sorted by depth |
| `dom.cljs` | hiccup → VNode, create/patch/unmount, attrs, keyed diff, roots, `:tpl` templates and holes; a `:comp` vnode whose component has a `Host` delegates create/patch/unmount to it instead of the normal DOM path (see Draw components) |
| `draw.cljs` | shared draw runtime for `hammer.canvas`/`hammer.gpu`: the per-instance runner (`run-host!`), the rAF queue and shared frame loop (`frame!`), the canvas host (size/DPR sync, `ResizeObserver`, `:on-*` listeners, `:attrs`), `Backend`/`register-backend!`, and standalone `mount!` for a bare canvas |
| `canvas.cljs` | Canvas 2D facade: re-exports `hammer.app` and `draw/mount!`, registers the `:canvas` backend (`getContext "2d"`, `setTransform` for DPR); `canvas.clj` has its thin `defdraw`/`defloop` macros (call `macros/draw-def`) |
| `gpu.cljs` | WebGPU facade: one adapter/device per page, `:fallback`/`:on-unsupported` handling, device-loss recovery, `gpu/pass`; registers the `:gpu` backend; `gpu.clj` has its `defdraw`/`defloop` macros |
| `state.cljs` | `app-db` atom and root trie node |
| `testing.cljs` | sync `flush!`, render counters, `reset-app!` |

## Update pipeline

1. `dispatch` queues the event, and `drain!` runs in a microtask. `dispatch-sync` processes now and then `sched/flush!`, and throws if called inside a handler. Its `:dispatch` effect is still queued, so that event renders later.
2. `process!` calls `(apply handler @app-db (rest ev))` (the args, without the event id). `nil` is ignored. With an effect map it applies `:db` → `set-db!`, then `:dispatch` (one event), then every other key through `run-fx!`.
3. `set-db!` → `trie/notify!`: marks the cells at each visited node and descends into child `k` only when `(get old k)` is not `identical?` to `(get new k)`. So `[:todos]` fires on any todo change, `[:todos 1]` only when that entry changes, and `[]` on every change. This relies on structural sharing. A node with more than 16 children under two PersistentHashMaps visits only the keys a structural diff of the two tries reports (identical sub-nodes skipped, so one changed entry of 1000 costs O(1)), unless a sample of 8 children says most values changed (wholesale replace), where looking up every child is cheaper. A visited node's `is?` index marks only the cells compared to the old value and to the new value, and none when the two are `=`; cost is independent of how many instances compare against that path. Children and `is?` values are keyed with value semantics but without persistent maps where possible: numbers/strings/booleans/nil in a js/Map by value, keywords in a js/Map by fqn (a runtime `(keyword "a")` finds `:a`; `"a"` never does), anything else in a persistent map (children) or a `(hash v)`-bucketed js/Map (`is?` values). A node's cells are nil, one cell, or a js/Set.
4. `cells/mark!` sets the `Cell`'s stale flag → `sched/schedule!` queues the instance once → one `queueMicrotask` flush, after the event drain (also a microtask) so a batch of events renders once, before paint.
5. `flush!` sorts the dirty instances by depth (parents first; ties keep schedule order) and runs the runner (`dom/update-inst!`, or a draw component's `Host`'s `run` — see Draw components) on those still dirty. Anything marked during the flush runs in a follow-up microtask.
6. `refresh!` recomputes stale slots in binding order. A prop, path or derived slot counts as changed only if its new value is not `=` to the old one. A marked `:local` slot always counts as changed, so its dependents recompute. The body renders only if a slot it names (by symbol, as for deps) changed; a slot that only feeds later bindings, or a prop the body never names, recomputes without a render.
7. On a change, the body renders → `normalize` → `patch!` against the instance's own previous vnode. A compiled body yields a `:tpl` vnode (see below), patched hole by hole.
8. A child `:comp` vnode is a boundary. The child instance is reused, `set-props!` compares args with `=`, and on a change `update-inst!` runs on the child immediately. That clears the child's dirty flag, so the flush skips it (one render per flush).

## Compiled templates

`defc` compiles every literal element vector in hiccup position: the body's
value, the tails of `if`/`if-not`/`if-let`/`if-some`/`when*`/`let`/`do`/`cond`/`case`,
the body of `for`, and children of such elements. Each becomes a template
(`hammer.dom/template`, a `def` named `<comp>__tplN`): static skeleton hiccup
built once and `cloneNode`d per instance, plus a VNode `:tpl` whose `attrs` holds
the hole values (in post-order: kids before their element's attrs), `kids` the
hole nodes (resolved by generated firstChild/nextSibling code), `inst` the
per-hole regions. Update writes only holes whose value is not `identical?`;
`:value/:checked/:selected` are re-applied against the live element every render.

| Position | Compiled as |
|---|---|
| literal string/number/keyword/`true` child, literal attr value | static in the skeleton (`nil`/`false` children vanish) |
| other child expression | kid hole: text node for scalars, a region of vnodes for vectors/seqs/templates (switches at runtime) |
| sole child of its element | kid hole owning the element (no anchor); otherwise the hole's text node is the region's end anchor |
| `:class`, `:style`, other attrs with non-literal value | attr hole (`:class` joined with the tag's classes) |
| `:on-*`, `:ref`, `:value/:checked/:selected` | always holes (expandos/properties aren't cloned) |
| non-literal second item `[:td x]` | kid hole, but the whole template renders as plain hiccup whenever `x` is a map |
| attrs map with non-keyword keys, non-keyword tag | plain hiccup (children still compiled) |
| `[c & args]` with a symbol head (component vector) | a `:comp` VNode built in place (key from `^{:key}`, args = the vector) when `c` is a component at runtime; otherwise the plain vector |

Not compiled: binding inits, component args, args of any other call, and
`hammer.dom/mount!` hiccup. A compiled vnode is mutable, so these positions
keep plain data (a value reused twice must not be one DOM node).

## defc binding kinds

| Init form | Kind | Decided | Recomputed when |
|---|---|---|---|
| positional prop | `:prop` | — | parent passes a non-`=` arg |
| vector literal `[:a id]` | `:path` | macro time | path's deps change (re-registers in the trie) or the trie marks it |
| `(is? path v)` (whole init) | `:eq` | macro time, by symbol (`is?`, or qualified on any facade — `hammer.core`, `hammer.app`, `hammer.canvas`, `hammer.gpu` — or an alias of one) | path's or `v`'s deps change (re-registers) or the trie marks it; value is `(= (get-in db path) v)` |
| evaluates to an `IWatchable` | `:local` | runtime, at `create` | never re-run; watch marks when the value is no longer `identical?` |
| anything else | `:derived` | runtime, at `create` | a named dep (prop or earlier binding) changed |

Deps are the slot symbols that appear anywhere in the init form (by name, so a shadowing
`let` counts too). A derived binding never tracks `app-db` or `@global`. Read the db
through a path binding; bind a global atom itself (`g some-atom`) to get a watched `:local`.

`defdraw`/`defloop` (`hammer.canvas`, `hammer.gpu`) share this same binding compilation
(`hammer.macros`), so the same table applies to their `props`/`bindings`.

## Draw components

`defdraw`/`defloop` build a component whose `Comp` carries a `hammer.cells/Host`
(`run`/`create`/`destroy`) instead of relying on `hammer.dom`'s own render path —
this is the **host hook**: a `:comp` vnode is unchanged, but wherever `hammer.dom`
would create, patch or unmount the instance itself, it first checks `cells/host` on
the component and, when one is present, delegates instead: `create!` calls
`(create host inst host-render nil)` for the node; `patch!` calls `set-props!` and,
if anything changed, `(run host inst)` in place of `update-inst!`; `unmount!` calls
`(destroy host inst)` in place of `unmount!`+`cells/destroy!`. `hammer.dom` never
requires `hammer.draw`; it only knows this `Host` shape.

Each draw instance's `Host`'s `run` is `hammer.draw/run-host!` — the **per-component
runner** the scheduler calls instead of `dom/update-inst!` (`hammer.cells`'s single
`sched/set-runner!` dispatches on whether the instance's `Comp` has a host). On a mark,
the scheduler's flush calls `run-host!`, which calls `cells/refresh!` (props, path,
`is?` and derived slots, same as `defc`) and, if a slot the opts or draw fn name
changed, re-evaluates them and `queue!`s the instance — it never draws in the
microtask.

Draw components **only draw in `frame!`**: one shared `requestAnimationFrame` loop
(requested only while something is queued or a mounted `defloop` has `:run?` truthy)
that, each frame, advances every running loop's `:t`/`:dt`/`:n`, then calls `draw!` on
every queued or running instance once, in mount order (`State.order`, an increasing
sequence number), before the browser paints.

State (the canvas element, DPR, size, listeners, the current opts/draw fn, `:init`
result, loop timing) is kept in `hammer.draw/State`, held in **the instance's `vnode`
field** — `defc` uses that same field for its DOM vnode, so a draw `Comp`'s host just
repurposes it for a different kind of per-instance state, with no new slot on
`cells/Instance`.

## Gotchas

| Symptom | Cause |
|---|---|
| Child re-renders on every parent render | inline `fn` prop is never `=`; pass an event vector or bind the fn in the parent |
| `(atom x)` ignores new `x` | the init runs once per instance; remount via a `^{:key}` change in a fully keyed list |
| `(vector a b)` vs `[a b]` | the literal is a path; the call is a value |
| "no fx registered for :k — did the handler return db" (logged, no throw) | handler returned `db`: `:db` is never set, every top-level key runs as an fx (a key matching a registered fx **runs it**), and a `:dispatch` key gets dispatched |
| List items keep the wrong DOM | keyed diff needs **every** kid keyed, with unique keys; otherwise index diff (+ warn) |
| Input value "fights" typing | `:value/:checked/:selected` are compared to the live element, so the db must hold the current value |
| `:ref` gets `nil` | called with `nil` on unmount; refs run after insertion into the document |
| Body shows stale global/db state | the body re-runs only when a slot it names changes; a raw `@global` or `@app-db` in the body never triggers one. Bind it as a slot |
| Extra empty text node in `childNodes` | a `nil` kid hole, or a hiccup-valued hole among siblings, keeps its (empty) text node; invisible to `innerHTML`/`children`/`:empty` |
| Throw doesn't crash the app | binding init → nil slot; body throw → old DOM kept; the runner catches per instance. Check the console for `hammer:` |

## Testing

Use `hammer.testing/flush!` (drain + flush, max 10 rounds) instead of awaiting
microtasks. Use `renders`/`reset-renders!` to assert which components re-rendered and
`reset-app!` as a `:before` fixture. Run with `npm test`; `bb loc` reports the core size.
