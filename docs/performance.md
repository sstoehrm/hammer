# Why hammer is fast

hammer was written and tuned by Claude, one measured experiment at a time:
each idea became a branch, was benchmarked against the previous best in
[js-framework-benchmark](https://github.com/krausest/js-framework-benchmark),
and was kept only when it was faster. The ideas below are the ones that
survived. The branch `performance-baseline` holds the build every later change
is compared to.

## Do as little work as the change needs

Most frameworks re-run a component tree and diff the result. hammer works out,
for each db change, which component instances read the part that changed, and
touches only those.

**Components name what they read.** A `defc` lists the db paths it reads as
bindings (`[todo [:todos id]]`). There are no subscriptions to compute and no
top-down re-render: a path is the subscription.

**A path trie finds the readers.** Every path binding registers in one trie.
After an event, hammer walks the trie alongside the old and new db and descends
into a key only when `(get old k)` is not `identical?` to `(get new k)`.
ClojureScript's persistent data shares unchanged structure, so a whole
unchanged subtree is skipped by a single pointer comparison. For a map with
many keys, it diffs the two hash-map tries directly: changing one entry out of
a thousand costs about as much as changing one out of ten.

**`is?` marks only what flips.** `(is? [:selected] id)` is true when the value
at the path equals `id`. Moving a selection in a 1,000-row table marks exactly
two rows, the old and the new one, whatever the row count. A plain
`[:selected]` binding would mark all thousand.

**Each instance diffs only itself.** A component keeps its last rendered hiccup
and diffs against that alone. A child component is a boundary: if its arguments
are `=` to last time, it is not rendered at all.

**Render only for a binding the body uses.** Bindings recompute in order, and
only when something they name changed. The body renders only if a binding it
names actually changed (`=`), so a change that only feeds an unused binding
costs no render.

## Make the work that remains cheap

**Templates instead of hiccup diffing.** Literal hiccup in a `defc` body is
compiled by the macro into a template: the static part is built into real DOM
once and `cloneNode`d for each instance, and only the dynamic parts ("holes":
a text, an attribute, a class, a child list) are kept as values. An update
compares hole by hole (`identical?`) and writes only the holes that changed. The
static parts are never diffed.

**One batch per tick.** `dispatch` queues events in a microtask; all events
of a tick run first, then one render pass updates the marked instances,
parents before children, before the browser paints. Ten events in a row render
once.

**Keyed lists move little.** The keyed diff patches the unchanged prefix and
suffix in place, matches the middle by key, and moves only the items outside
the longest run that kept its order (a longest increasing subsequence). New
items go in through one `DocumentFragment`; clearing a list is one
`textContent = ""`.

**One listener per event type.** `:on-*` handlers are not attached per
element. Each mount root has one capture listener per event type, which walks
from the target outward to the handlers.

**Forms are compared with the live element.** `:value`, `:checked` and
`:selected` are written as properties, and only when the live element differs,
so an update never resets what the user is typing.

**Canvas draws once per frame.** `defdraw` and `defloop` never draw during an
update: they queue for the next animation frame, where every queued instance
draws once. Per-frame state lives in a `volatile!`, which nothing watches.

## Numbers

<!-- js-framework-benchmark table: filled in from the current run -->

Other measurements:

- **Size:** `bb sizes` builds a minimal app per variant with `:advanced`. The
  DOM build is about 132 KB raw and 32 KB gzip, `cljs.core` included. Each
  bundle carries only its own variant's namespaces. The core is 2,316 lines
  (`bb loc`).
- **Canvas:** [bench/canvas/RESULTS.md](../bench/canvas/RESULTS.md) compares
  hammer's `defdraw`/`defloop` with hand-written JS issuing the same draw
  calls.
- **Tokens:** hammer's TodoMVC is 52.5% fewer tokens than re-frame's
  (`bb tokens`). [stack-cap-bench](https://github.com/sstoehrm/stack-cap-bench)
  measures what a coding agent spends building the same projects in different
  stacks, hammer with its skill among them.
