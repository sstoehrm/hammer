<img src="assets/hammer.svg" alt="" width="96" height="96">

# hammer

If all you have is a hammer, everything looks like a nail. This hammer is a
ClojureScript frontend library, so every problem looks like a web page, which
is convenient, because that's the only kind of nail it can hit.

## Why

Because I wanted a really fast ClojureScript frontend library that can compete
with the other frameworks. It is also token efficient if you develop with the
skill: see [stack-cap-bench](https://github.com/sstoehrm/stack-cap-bench) (trust
me, it is not faked ;)).

Why is it fast? I have literally no clue: Claude developed and optimized it,
but it seems to be somewhat legit. What it does to be fast, and the numbers, are
in [docs/performance.md](docs/performance.md).

## Features

- re-frame-style events and effects: `reg-event`, `reg-fx`, `dispatch`, effect
  maps, without React and without subscriptions, and a built-in `:http` effect
  (`hammer.http`, fetch-based, no dependencies).
- Components name the app-db paths they read. A path trie re-renders exactly the
  components whose paths changed, and each one diffs only its own hiccup.
- `is?` bindings for selections: only the rows whose result flips re-render.
- Tracks (`hammer.track`): dispatch an event when db paths change, without a
  component, through the same path trie.
- Tubes (`hammer.tubes`): event vectors to and from a server over a WebSocket,
  as EDN, with queueing and reconnect.
- Local state (`atom` bindings), derived bindings that recompute only when what
  they use changes, and global atoms deref'd in a component tracked
  automatically.
- Literal hiccup compiles to templates, cloned per instance, with only the
  changed parts written on update.
- Keyed lists with minimal DOM moves, delegated event handlers, forms that never
  fight the user's typing.
- Canvas 2D and WebGL2 components (`defdraw`, `defloop`) with the same
  reactivity, drawing once per animation frame.
- Mistakes are reported, never thrown at the user: `on-error!` to route them,
  and tests fail on them (`hammer.testing`).
- No npm dependencies; a git dep for shadow-cljs or plain `deps.edn`;
  `:advanced` builds.
- A Claude Code and Codex plugin, `hammer-app`, that teaches your agent the
  API: `/plugin marketplace add sstoehrm/hammer`, then
  `/plugin install hammer-app@hammer`.

## Does it work?

No clue. Use with caution.

- [docs/develop-with-a-hammer.md](docs/develop-with-a-hammer.md): setup, API,
  testing, canvas, and working on hammer itself.

## TODO

- [ ] First release: tag `v0.1.0` (GitHub release with the jar; use it via the
  git tag).
- [ ] Publish on Clojars once the git releases are verified.
- [ ] 3D on top of `hammer.gl`: meshes, cameras, materials, a scene graph.

## Special thanks

To [re-frame](https://github.com/day8/re-frame) for the API inspiration, and to
Artūr Girenko for [pneumatic-tubes](https://github.com/drapanjanas/pneumatic-tubes),
the idea behind `hammer.tubes`.
