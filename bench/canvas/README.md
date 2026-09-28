# Canvas benchmark

Measures hammer's own overhead for Canvas 2D / WebGL2 drawing: every scenario
has a hammer variant (`hammer.canvas` / `hammer.gl`) and a hand-written
vanilla JS variant that issues the same drawing calls in the same order.
The delta between the two is hammer: events, the path trie, the scheduler,
`draw.cljs`'s frame loop and host, and reading the db's persistent data.

## Run

```sh
bb bench-canvas                                   # build + all scenarios, 20 iterations
bb bench-canvas --scenario table-1k --iterations 3
node bench/canvas/run.mjs --scenario loop-10k,loop-atom-10k   # after a build
```

`bb bench-canvas` release-builds `bench-canvas`, `bench-dom` and `bench-gl`
(shadow-cljs.edn, output in `target/bench/`), fails if `bench-canvas` or
`bench-gl` contains `hammer.core`/`hammer.dom` (or the other backend), and
then runs `run.mjs` with the arguments passed through.

| Option | Default | |
|---|---|---|
| `--scenario a,b` | all | names from the table below |
| `--iterations N` | 20 | measured iterations per op (ops scenarios) |
| `--warmups N` | 3 | unrecorded iterations before those |
| `--variants hammer,vanilla` | both | vanilla is the baseline for every delta |
| `--window-ms MS` | 5000 | loop measurement window (after a 1 s warm-up) |
| `--throttle X` | 4 | CPU throttle for update/select/swap |
| `--dpr D` | 1 | `deviceScaleFactor` (exercises the `ceil(w * dpr)` backing store) |
| `--enable-unsafe-swiftshader` | off | pass Chromium's `--enable-unsafe-swiftshader` flag (also via `HAMMER_SWIFTSHADER=1`) |

Browser: `/snap/bin/chromium`, headless, override with `CHROME=/path/to/chrome`.
On a GPU-less machine, headless Chromium's automatic fallback to software
(SwiftShader) WebGL is deprecated ("Automatic fallback to software WebGL has
been deprecated. Please use the --enable-unsafe-swiftshader flag") and a
future Chromium may drop it, at which point `gl-points` would report
`skipped (no WebGL2 context)` instead of running on SwiftShader as it does
today. Pass `--enable-unsafe-swiftshader` (or set `HAMMER_SWIFTSHADER=1`) to
opt into that flag and keep `gl-points` working; it's off by default since a
machine with a real GPU doesn't need it.
Output: a markdown header (browser version, WebGL renderer, CPU, load average,
throttle, iterations, DPR) and one table per scenario on stdout; progress on
stderr. Exit 1 on a pixel-parity mismatch or a page error.

The load average in the header matters: run on an otherwise idle machine.

## Scenarios

| Scenario | hammer | Ops / metric |
|---|---|---|
| `table-1k` | one `defdraw`, 1000 rows: `fillRect` + `fillText` label each, 10 columns | create, update every 10th label, select a row, swap rows 1 and n-2, clear |
| `rects-10k` | one `defdraw`, 10000 `fillRect`s (no text, it would dominate) | same ops (update = recolour every 10th) |
| `loop-1k` / `loop-10k` / `loop-100k` | `defloop`, N moving 3x3 rects, state in `(volatile! …)` | frame time + script time per frame over 5 s |
| `loop-atom-10k` | same as `loop-10k` but state in `(atom …)` + `swap!` (the pattern the README warns against) | same; vanilla is identical to `loop-10k`'s |
| `many-canvases-1k` | 1000 20x20 `defdraw` cells in a keyed `hammer.core` `defc` list; colour from `[:colors id]` (a map keyed by id, hammer's trie fast path), highlight from `(is? [:sel] id)` | create, update one cell's colour, select one cell, clear |
| `gl-points` | `hammer.gl` `defdraw`, 100000 points (`gl.POINTS`), colour uniform | create, update colour, clear. Skipped (`skipped (no WebGL2 context)`) without one |

All data comes from `public/common/data.js` (seeded mulberry32, js-framework-benchmark
word lists), loaded by both variants; layout constants live there too. No
runtime randomness. `create` builds its data inside the op in both variants
(hammer converts the rows into a vector of maps for the db — that conversion
is part of what is measured).

Only `many-canvases-1k` uses `hammer.core`; everything else is built from
`hammer.canvas` or `hammer.gl` alone.

## Measurement

**Ops** (table, rects, many-canvases, gl-points). Fresh browser context (page) per
op and variant; which variant runs first alternates per op (per scenario for
loops). `create` and `clear` run the opposite op (unmeasured) before
each iteration; `update`/`select`/`swap` run one `create` first, unthrottled.
Then 3 warm-ups + N iterations, `gc()` (`--expose-gc`) before each. Reported:
median and p95, delta vs vanilla.

- **wall**: `bench.run(op, k)` (via `page.evaluate`, in a `setTimeout(…, 0)`
  task: hammer `dispatch`es, vanilla mutates its state and requests a frame) until
  `requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(done, 0)))`,
  identical for both. hammer's `frame!` is requested from the trigger's own
  microtask (dispatch's drain, then the scheduler's flush), both of which run
  before the browser's next rAF callback, so hammer draws in the same first
  rAF as vanilla, which requests its frame synchronously; the second rAF is
  just belt and braces, kept symmetric for both variants rather than relying
  on that always holding. This is frame-paced (60 Hz): for ops that fit in a
  frame both variants read one to two frames and the delta is mostly phase
  noise.
- **script / task**: CDP `Performance.getMetrics` `ScriptDuration` /
  `TaskDuration` delta around each trigger. Main-thread time, not
  frame-quantized; this is the column that shows overhead for small ops.
  `ScriptDuration` does not count script that CDP's `Runtime.callFunctionOn`
  runs synchronously, which is why the trigger runs in its own task: called
  directly from `page.evaluate`, vanilla's synchronous op work (creating
  canvases, `getContext`, `textContent = ''`) went uncounted while hammer's,
  deferred to a microtask by `dispatch`, counted. Promise reaction jobs are
  not counted either (5 ms of work in a `.then` reads ~0.05 ms), which is why
  `hammer.events/dispatch` drains via `queueMicrotask`.
- **CPU throttle**: CDP `Emulation.setCPUThrottlingRate` 4x for update,
  select and swap; none for create, clear and loops. Printed per row.
- `select` targets a different row every iteration (`k` is passed through),
  so hammer's `=` check never skips a draw that vanilla does.
- **pixel parity**: after the last iteration, an FNV hash of every canvas's
  backing store (2D via `getImageData`, WebGL2 via `readPixels`), hammer vs
  vanilla. `MISMATCH` means the two variants do not draw the same thing (not
  applicable to loops, whose positions depend on frame timing).

**Loops**. Fresh page, 1 s warm-up, then a 5 s window: consecutive rAF
timestamp deltas (mean and p95), frames drawn, script and task time per frame
(`Performance.getMetrics` delta over the window / frames drawn), Long
Animation Frame entries (> 50 ms), and how often the draw fn expression was
re-evaluated per frame (0 for `volatile!`, ~1 for the atom: the watch marks
the instance every frame). At 60 Hz, frame time only discriminates once the
work exceeds the frame budget (`loop-100k`); for 1k/10k look at script/frame.

## Parity rules (vanilla)

The vanilla files in `public/vanilla/` mirror what `hammer.draw` + the
`:canvas` backend do per draw:

1. Backing store synced every draw: `ceil(css * devicePixelRatio)`, assigned
   only on change; CSS size set with `style.width/height` in px.
2. `ctx.setTransform(dpr, 0, 0, dpr, 0, 0)` before each draw; nothing clears
   the canvas except the draw code's own `clearRect`.
3. The same calls in the same order (`clearRect`, state setters, `fillRect`,
   `fillText`, …) with the same arguments.
4. Drawing only inside `requestAnimationFrame`, never synchronously in the
   trigger; state changes only request a frame (one pending frame at a time).
5. Loops: `dt = min(100, ts - last)`, 0 on the first frame (hammer's
   `:max-dt` default); the same typed-array state and step function, which
   returns a fresh wrapper each frame in both variants (so the atom variant's
   watch really fires). many-canvases: only affected canvases redraw, in
   mount order. gl-points: same program and buffer, `preserveDrawingBuffer:
   true` on both (so the pixel-parity `readPixels` after the last iteration
   sees the drawn frame, not a browser-cleared one), viewport set before
   every draw (hammer's counterpart of `setTransform`), vertex upload
   (`bufferSubData`) only when the points changed, uniform upload and one
   clear + `drawArrays(POINTS)` per draw.

hammer-side code is written tight (`dotimes`/`nth`, `identical?` for id
compares, typed arrays for loop state) so the delta is framework overhead
rather than codegen. The `defdraw` scenarios necessarily iterate persistent
db data in the draw fn; that is inherent to hammer's model and part of the
number.

## Files

| Path | |
|---|---|
| `RESULTS.md` | the last full run, with machine and noise notes |
| `run.mjs` | runner: static server (`/` → `public/`, `/out/` → `target/bench/`), puppeteer-core, stats, markdown |
| `public/page.html` | one page for all variants: loads `common/data.js`, then `?src=` |
| `public/common/data.js` | seeded data + layout constants shared by both variants |
| `public/vanilla/*.js` | vanilla baselines (plain JS, no build) |
| `src/bench_canvas/*.cljs` | hammer variants: `main` (bench-canvas build: table, rects, loops), `many` (bench-dom), `gl` (bench-gl) |
