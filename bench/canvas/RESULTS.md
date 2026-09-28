# hammer canvas benchmark — results

Full run of `node bench/canvas/run.mjs` (all scenarios, defaults) at commit
6ed3a1a (`canvas/draw`), 2026-09-27.

- Machine: AMD Ryzen AI MAX+ 395 (32 threads), 30 GB RAM, Linux 7.0.0-34; otherwise idle (load average in the header below)
- Browser: Chromium 153.0.8010.47 (snap), headless
- CPU throttle: 4x for update/select/swap, none for create/clear and loops
- Iterations: 3 warm-ups + 20 measured per op; loops: 1 s warm-up + 5 s window
- Noise: expect ±5–10% between runs on the script column; loops varied ±10% run to
  run at 100k. Rows whose script time is below ~1 ms at 4x (many-canvases
  update/select, the clears) are dominated by per-trigger noise: vanilla's
  many-canvases update/select median ranged 0.09–0.56 ms across runs, so
  their ratios are not meaningful — compare the absolute deltas.
- Not comparable with numbers taken before these two accounting fixes:
  1. the trigger runs in its own `setTimeout(0)` task (bench commit 3d83c50):
     script that CDP's `Runtime.callFunctionOn` runs synchronously is not in
     `ScriptDuration`, so vanilla's synchronous op work (creating canvases,
     `getContext`, `textContent = ''`) used to go uncounted;
  2. `hammer.events/dispatch` drains via `queueMicrotask` (6ed3a1a): Promise
     reaction jobs are not in `ScriptDuration` either, so hammer's event
     handlers, `set-db!` and trie notify used to go uncounted.
  Both variants' op work is now in the script column.

Runner output:

- Browser: Chrome/153.0.8010.47, headless (/snap/bin/chromium); WebGL renderer: ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver)
- Node v24.21.0; 32x AMD RYZEN AI MAX+ 395 w/ Radeon 8060S; load average at start: 2.96 2.00 1.95
- Viewport 1400x1500, devicePixelRatio 1
- Ops: 3 warm-ups + 20 measured iterations per op, fresh browser context (page) per op and variant, variant order alternating per op; gc() before each iteration
- CPU throttle (CDP Emulation.setCPUThrottlingRate): 4x for update/select/swap; 1x (none) for create/clear and all loops
- wall = trigger (page.evaluate → its own setTimeout(0) task → bench.run) until rAF → rAF → setTimeout(0), same for both variants. Frame-paced (60 Hz, ~16.7 ms frames): it depends on where in the frame the trigger lands, so ops that fit in one frame read 1-2 frames for both variants; compare script/task for those
- script / task = CDP Performance.getMetrics ScriptDuration / TaskDuration delta around each measured trigger (main-thread time, not quantized)
- Loops: consecutive rAF timestamp deltas over a 5000 ms window; script/task per frame = metric delta over the window / frames drawn
- pixel parity: FNV hash of every 2D canvas's backing store after the last iteration, hammer vs vanilla

## table-1k

defdraw, 1000 rows (rect + fillText label), full redraw per change. n=1000. All times in ms.

| op | CPU throttle | variant | wall median | wall p95 | Δ wall vs vanilla | script median | script p95 | Δ script vs vanilla | task median | Δ task vs vanilla | pixel parity |
|---|---|---|---|---|---|---|---|---|---|---|---|
| create | 1x | vanilla | 28.75 | 29.30 | — | 2.40 | 2.60 | — | 6.16 | — |  |
| create | 1x | hammer | 28.95 | 29.80 | +0.20 (+0.7%) | 2.87 | 3.35 | +0.48 (+19.8%) | 6.53 | +0.38 (+6.1%) | ok |
| update | 4x | vanilla | 24.75 | 26.80 | — | 5.77 | 7.28 | — | 16.20 | — |  |
| update | 4x | hammer | 23.75 | 26.80 | -1.00 (-4.0%) | 9.08 | 11.48 | +3.32 (+57.5%) | 19.69 | +3.48 (+21.5%) | ok |
| select | 4x | vanilla | 25.20 | 26.80 | — | 4.89 | 5.46 | — | 14.94 | — |  |
| select | 4x | hammer | 24.75 | 26.90 | -0.45 (-1.8%) | 5.70 | 7.61 | +0.81 (+16.6%) | 16.18 | +1.24 (+8.3%) | ok |
| swap | 4x | vanilla | 23.65 | 25.60 | — | 5.17 | 5.99 | — | 16.58 | — |  |
| swap | 4x | hammer | 23.95 | 25.90 | +0.30 (+1.3%) | 6.18 | 7.11 | +1.02 (+19.7%) | 16.85 | +0.27 (+1.6%) | ok |
| clear | 1x | vanilla | 29.30 | 29.90 | — | 0.13 | 0.16 | — | 1.52 | — |  |
| clear | 1x | hammer | 28.80 | 29.50 | -0.50 (-1.7%) | 0.30 | 0.60 | +0.17 (+129.0%) | 1.68 | +0.17 (+11.1%) | ok |

## rects-10k

defdraw, 10000 filled rects, full redraw per change. n=10000. All times in ms.

| op | CPU throttle | variant | wall median | wall p95 | Δ wall vs vanilla | script median | script p95 | Δ script vs vanilla | task median | Δ task vs vanilla | pixel parity |
|---|---|---|---|---|---|---|---|---|---|---|---|
| create | 1x | vanilla | 29.90 | 30.80 | — | 4.28 | 4.75 | — | 8.79 | — |  |
| create | 1x | hammer | 29.55 | 30.00 | -0.35 (-1.2%) | 5.82 | 6.46 | +1.53 (+35.8%) | 9.91 | +1.11 (+12.7%) | ok |
| update | 4x | vanilla | 24.85 | 26.40 | — | 4.53 | 5.55 | — | 20.46 | — |  |
| update | 4x | hammer | 23.45 | 27.00 | -1.40 (-5.6%) | 9.18 | 9.95 | +4.65 (+102.8%) | 23.37 | +2.91 (+14.2%) | ok |
| select | 4x | vanilla | 24.70 | 27.20 | — | 4.97 | 6.21 | — | 21.34 | — |  |
| select | 4x | hammer | 25.35 | 32.10 | +0.65 (+2.6%) | 8.20 | 11.93 | +3.23 (+64.9%) | 20.90 | -0.44 (-2.1%) | ok |
| swap | 4x | vanilla | 25.05 | 27.00 | — | 4.45 | 5.23 | — | 18.15 | — |  |
| swap | 4x | hammer | 25.05 | 30.00 | +0.00 (+0.0%) | 8.10 | 8.55 | +3.65 (+81.9%) | 21.78 | +3.63 (+20.0%) | ok |
| clear | 1x | vanilla | 29.20 | 29.80 | — | 0.12 | 0.26 | — | 1.54 | — |  |
| clear | 1x | hammer | 28.25 | 28.90 | -0.95 (-3.3%) | 0.34 | 0.44 | +0.22 (+179.3%) | 1.85 | +0.32 (+20.6%) | ok |

## loop-1k

defloop, 1000 moving rects, per-frame state in volatile!. n=1000. CPU throttle 1x (none). 5000 ms window after 1000 ms warm-up. All times in ms.

| variant | frames drawn | frame Δt mean | frame Δt p95 | Δ mean vs vanilla | script / frame | Δ script vs vanilla | task / frame | Δ task vs vanilla | LoAF (>50 ms) count | LoAF mean | draw-fn re-evals / frame |
|---|---|---|---|---|---|---|---|---|---|---|---|
| vanilla | 302 | 16.67 | 16.80 | — | 0.23 | — | 1.23 | — | 0 | — | — |
| hammer | 302 | 16.67 | 16.80 | -0.00 (-0.0%) | 0.30 | +0.07 (+32.4%) | 1.29 | +0.06 (+5.1%) | 0 | — | 0.00 |

## loop-10k

defloop, 10000 moving rects, per-frame state in volatile!. n=10000. CPU throttle 1x (none). 5000 ms window after 1000 ms warm-up. All times in ms.

| variant | frames drawn | frame Δt mean | frame Δt p95 | Δ mean vs vanilla | script / frame | Δ script vs vanilla | task / frame | Δ task vs vanilla | LoAF (>50 ms) count | LoAF mean | draw-fn re-evals / frame |
|---|---|---|---|---|---|---|---|---|---|---|---|
| vanilla | 302 | 16.67 | 16.80 | — | 1.47 | — | 4.55 | — | 0 | — | — |
| hammer | 302 | 16.67 | 16.80 | +0.00 (+0.0%) | 1.38 | -0.09 (-6.1%) | 4.80 | +0.25 (+5.5%) | 0 | — | 0.00 |

## loop-100k

defloop, 100000 moving rects, per-frame state in volatile!. n=100000. CPU throttle 1x (none). 5000 ms window after 1000 ms warm-up. All times in ms.

| variant | frames drawn | frame Δt mean | frame Δt p95 | Δ mean vs vanilla | script / frame | Δ script vs vanilla | task / frame | Δ task vs vanilla | LoAF (>50 ms) count | LoAF mean | draw-fn re-evals / frame |
|---|---|---|---|---|---|---|---|---|---|---|---|
| vanilla | 143 | 35.44 | 50.00 | — | 32.45 | — | 35.67 | — | 0 | — | — |
| hammer | 153 | 33.00 | 33.40 | -2.44 (-6.9%) | 29.94 | -2.51 (-7.7%) | 33.14 | -2.53 (-7.1%) | 0 | — | 0.00 |

## loop-atom-10k

defloop, 10000 moving rects, per-frame state in a watched atom + swap! (the pattern the README warns against); vanilla is the same code as loop-10k. n=10000. CPU throttle 1x (none). 5000 ms window after 1000 ms warm-up. All times in ms.

| variant | frames drawn | frame Δt mean | frame Δt p95 | Δ mean vs vanilla | script / frame | Δ script vs vanilla | task / frame | Δ task vs vanilla | LoAF (>50 ms) count | LoAF mean | draw-fn re-evals / frame |
|---|---|---|---|---|---|---|---|---|---|---|---|
| vanilla | 302 | 16.67 | 16.70 | — | 1.26 | — | 4.53 | — | 0 | — | — |
| hammer | 302 | 16.67 | 16.70 | -0.00 (-0.0%) | 1.42 | +0.16 (+13.0%) | 3.92 | -0.61 (-13.4%) | 0 | — | 1.00 |

## many-canvases-1k

1000 20x20 defdraw cells in a keyed hammer.core list (colour from [:colors id], highlight from (is? [:sel] id)); vanilla: 1000 hand-managed <canvas>. n=1000. All times in ms.

| op | CPU throttle | variant | wall median | wall p95 | Δ wall vs vanilla | script median | script p95 | Δ script vs vanilla | task median | Δ task vs vanilla | pixel parity |
|---|---|---|---|---|---|---|---|---|---|---|---|
| create | 1x | vanilla | 78.30 | 93.30 | — | 18.98 | 23.29 | — | 75.52 | — |  |
| create | 1x | hammer | 82.90 | 96.50 | +4.60 (+5.9%) | 22.93 | 25.24 | +3.95 (+20.8%) | 80.95 | +5.43 (+7.2%) | ok |
| update | 4x | vanilla | 13.15 | 17.70 | — | 0.09 | 0.81 | — | 11.17 | — |  |
| update | 4x | hammer | 12.80 | 13.70 | -0.35 (-2.7%) | 1.21 | 1.96 | +1.12 (+1205.9%) | 12.96 | +1.79 (+16.0%) | ok |
| select | 4x | vanilla | 12.15 | 16.90 | — | 0.27 | 0.73 | — | 12.18 | — |  |
| select | 4x | hammer | 14.55 | 37.80 | +2.40 (+19.8%) | 1.16 | 2.05 | +0.89 (+330.6%) | 15.86 | +3.68 (+30.2%) | ok |
| clear | 1x | vanilla | 30.60 | 32.40 | — | 0.69 | 0.87 | — | 2.56 | — |  |
| clear | 1x | hammer | 27.40 | 31.80 | -3.20 (-10.5%) | 1.70 | 2.00 | +1.01 (+146.4%) | 3.64 | +1.08 (+42.3%) | ok |

## gl-points (replaces gpu-points)

`gpu-points` (`hammer.gpu`/WebGPU) was replaced by `gl-points` (`hammer.gl`/WebGL2):
see `docs/superpowers/specs/2026-09-28-webgl2-backend-design.md` for why (WebGPU is
not usable by default in Firefox or on Linux Chromium). The table below is a
**single run** (`node bench/canvas/run.mjs --scenario gl-points`, default 3
warm-ups + 20 measured iterations) — one invocation, not repeated to gauge
run-to-run noise the way the note above does for the full suite — bench code as
of 8600a37, runner from 5fe1982 (`canvas/webgl2`), 2026-09-28, same machine as
the header above (Radeon 8060S). Headless Chromium used its software (SwiftShader) WebGL2 renderer rather
than that GPU (headless Chromium falls back to software rendering by default,
without `--use-angle=vulkan`/`--use-gl` flags), so absolute times are not
comparable to a hardware run and are noted as such below; `preserveDrawingBuffer` is
on for both variants (`readPixels` pixel-parity needs it), so absolute times include
that copy's cost.

hammer.gl defdraw, 100000 points (gl.POINTS), uniform colour; preserveDrawingBuffer is on in both variants (for readPixels parity), so absolute times include the preserved-buffer copy (SOFTWARE renderer). n=100000. All times in ms.

| op | CPU throttle | variant | wall median | wall p95 | Δ wall vs vanilla | script median | script p95 | Δ script vs vanilla | task median | Δ task vs vanilla | pixel parity |
|---|---|---|---|---|---|---|---|---|---|---|---|
| create | 1x | vanilla | 40.55 | 42.90 | — | 4.51 | 5.13 | — | 32.55 | — |  |
| create | 1x | hammer | 41.85 | 44.00 | +1.30 (+3.2%) | 4.59 | 6.07 | +0.08 (+1.8%) | 32.87 | +0.32 (+1.0%) | ok |
| update | 4x | vanilla | 32.00 | 37.90 | — | 0.20 | 0.88 | — | 31.65 | — | |
| update | 4x | hammer | 32.65 | 37.30 | +0.65 (+2.0%) | 1.26 | 1.71 | +1.06 (+524.2%) | 33.12 | +1.47 (+4.7%) | ok |
| clear | 1x | vanilla | 18.40 | 24.20 | — | 0.09 | 0.15 | — | 2.54 | — | |
| clear | 1x | hammer | 18.90 | 23.50 | +0.50 (+2.7%) | 0.27 | 0.33 | +0.17 (+186.6%) | 2.78 | +0.24 (+9.6%) | ok |

