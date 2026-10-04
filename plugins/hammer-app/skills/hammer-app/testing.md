# hammer.testing

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
