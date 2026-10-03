# hammer error reporting (design)

Figure: `.blend/specs/2026-10-03-error-reporting.edn`.

## Goal

hammer keeps going when something fails (one broken handler or component must
not take down the batch), but today every failure is a `console.error` /
`console.warn` only. Apps cannot see them, tests stay green while hammer logs
errors, and several mistakes fail silently. This change routes every report
through one function, lets apps and tests observe them, and fixes the silent
cases listed in the `hammer-app` skill (PR #23).

## 1. One reporter, an app hook, a test collector

- New namespace `hammer.log` (no hammer dependencies, so `hammer.events`,
  `hammer.cells`, `hammer.dom`, `hammer.scheduler`, `hammer.draw`,
  `hammer.canvas` and `hammer.gl` can all require it).
- `(report! level message error)`: `level` is `:error` or `:warn`, `message`
  the full `"hammer: …"` string, `error` the caught value or `nil`. It builds
  `{:level :message :error}`, pushes it to the test collector when that is
  active, then calls the reporter. Every existing `console.error`/`console.warn`
  in `src/hammer` goes through it. Throws hammer raises on purpose (`is?`
  outside a binding, `dispatch-sync` inside a handler, `flush!` not settling,
  missing draw backend, `mount!` without a draw component, host create) stay
  throws.
- The default reporter calls `console.error`/`console.warn` with `message`, plus
  `error` when non-nil.
- `(on-error! f)` replaces the reporter; `(on-error! nil)` restores the default.
  `f` gets the map. If `f` throws, the default reporter logs both the original
  report and that throw. Exported from `hammer.app`, so `hammer.core`,
  `hammer.canvas` and `hammer.gl` have it.
- Test collector: inactive until `hammer.testing` is loaded. Independent of
  `on-error!`, so an app's reporter never hides errors from tests.
  - `hammer.testing/flush!`: after settling, if any `:error` entries were
    collected (including ones reported before the call, e.g. by `mount!` or
    `dispatch-sync`), takes all entries and throws
    `(ex-info "hammer: N error(s) reported: <first message>" {:errors [...]})`.
    `:warn` entries are collected but never cause a throw.
  - `(hammer.testing/expect-errors f)` runs `(f)` and returns the vector of
    entries collected during it (both levels); they are not left for `flush!`.
    Errors already pending when it is called stay pending.
  - `hammer.testing/frame!` checks the same way after its draw frame.
  - `(hammer.testing/check-errors!)` is that check on its own (for tests that only
    use `dispatch-sync`, e.g. as an `:after` fixture).
  - `hammer.testing/reset-app!` also clears the collector.

## 2. Silent mistakes made visible

| # | Case | Change |
|---|---|---|
| 2 | handler returns `db` | A non-empty effect map with no `:db`, no `:dispatch` and no key registered with `reg-fx` logs one error, `hammer: handler for :id returned no known effect keys (:a :b) - did it return db instead of {:db db}, or miss a reg-fx?`, and runs nothing. (The same map is what a handler returns when its only fx was never registered, hence both hints.) Otherwise unchanged. |
| 3 | `dispatch` on an fx id | `no event handler for :k` gets the hint `(:k is an fx: return {:k value} from an event handler)` when `:k` is registered with `reg-fx`. |
| 4 | some list items unkeyed | In dev builds (`goog.DEBUG`), when a kid list has both keyed and unkeyed items, warn `hammer: some list items have no key, falling back to index diff` (like the duplicate-key warning). A list with no keys stays silent. |
| 5 | `:key` in the attrs map | Honoured as the element's key (`^{:key}` metadata wins when both are given) and not written as an attribute, in `normalize` and in compiled templates. Components still take keys only from metadata. |
| 6 | `:style` string / camelCase | A string `:style` is written to `style.cssText`; going from a string to a map clears `cssText` first. In dev builds, a map key containing an upper-case letter warns `hammer: :style keys are CSS names, got :backgroundColor` and is still passed to `setProperty` (which ignores it). |
| 7 | `<option>` value | On an `option` element `:value` is written as the `value` attribute (compared with `getAttribute`; `nil` writes `value=""`, as for an input), so the markup always has it. Other elements keep the property. |

Out of scope: passing the element to `:on-*` fns (`currentTarget` is read-only).

## Testing

Each case gets a failing test first in the existing test namespace for its area.
`hammer.test-util/capture-errors`/`capture-warnings` wrap `expect-errors`, so
tests that trigger errors on purpose do not leak them into a later `flush!`.
`npm test` and `bb sizes` stay green.

## Docs

README, `hammer-internals` skill (gotchas, testing, file table). The
`hammer-app` skill on PR #23 is updated after this merges.
