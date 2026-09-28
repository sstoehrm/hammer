#!/usr/bin/env node
// hammer canvas benchmark runner. See bench/canvas/README.md.
//
//   node bench/canvas/run.mjs [--scenario a,b] [--iterations N] [--variants hammer,vanilla]
//                             [--warmups N] [--window-ms MS] [--throttle X] [--dpr D]
//
// Needs the release builds (npx shadow-cljs release bench-canvas bench-dom bench-gl,
// or `bb bench-canvas`, which builds and then runs this). Markdown goes to stdout,
// progress to stderr. Exit 1 on a pixel-parity mismatch or a page error.

import puppeteer from 'puppeteer-core';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');
const PUBLIC = path.join(HERE, 'public');
const OUT = path.join(ROOT, 'target/bench');
const CHROME = process.env.CHROME || '/snap/bin/chromium';

// ---- options

function parseArgs(argv) {
  const o = { scenario: null, iterations: 20, warmups: 3, variants: ['vanilla', 'hammer'],
    windowMs: 5000, loopWarmupMs: 1000, throttle: 4, dpr: 1,
    swiftshader: process.env.HAMMER_SWIFTSHADER === '1' };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i], v = () => {
      if (i + 1 >= argv.length) throw new Error(`missing value for ${a}`);
      return argv[++i];
    };
    switch (a) {
      case '--scenario': case '--scenarios': o.scenario = v().split(','); break;
      case '--iterations': o.iterations = parseInt(v(), 10); break;
      case '--warmups': o.warmups = parseInt(v(), 10); break;
      case '--variants': o.variants = v().split(','); break;
      case '--window-ms': o.windowMs = parseInt(v(), 10); break;
      case '--throttle': o.throttle = parseFloat(v()); break;
      case '--dpr': o.dpr = parseFloat(v()); break;
      case '--enable-unsafe-swiftshader': o.swiftshader = true; break;
      case '-h': case '--help':
        console.log('usage: node bench/canvas/run.mjs [--scenario a,b] [--iterations N] [--variants hammer,vanilla]\n' +
          '       [--warmups N] [--window-ms MS] [--throttle X] [--dpr D] [--enable-unsafe-swiftshader]\n' +
          'scenarios: ' + SCENARIOS.map(s => s.name).join(', '));
        process.exit(0);
        break;
      default: throw new Error(`unknown argument ${a}`);
    }
  }
  // vanilla first: it is the baseline every delta refers to
  o.variants = ['vanilla', 'hammer'].filter(x => o.variants.includes(x));
  if (!o.variants.length) throw new Error('--variants must include hammer and/or vanilla');
  return o;
}

// ---- scenarios

const TABLE_OPS = ['create', 'update', 'select', 'swap', 'clear'];

const SCENARIOS = [
  { name: 'table-1k', kind: 'ops', build: 'bench-canvas', vanilla: 'table.js', app: 'table', n: 1000,
    ops: TABLE_OPS, pixels: true, desc: 'defdraw, 1000 rows (rect + fillText label), full redraw per change' },
  { name: 'rects-10k', kind: 'ops', build: 'bench-canvas', vanilla: 'table.js', app: 'rects', n: 10000,
    ops: TABLE_OPS, pixels: true, desc: 'defdraw, 10000 filled rects, full redraw per change' },
  { name: 'loop-1k', kind: 'loop', build: 'bench-canvas', vanilla: 'loop.js', app: 'loop', n: 1000,
    desc: 'defloop, 1000 moving rects, per-frame state in volatile!' },
  { name: 'loop-10k', kind: 'loop', build: 'bench-canvas', vanilla: 'loop.js', app: 'loop', n: 10000,
    desc: 'defloop, 10000 moving rects, per-frame state in volatile!' },
  { name: 'loop-100k', kind: 'loop', build: 'bench-canvas', vanilla: 'loop.js', app: 'loop', n: 100000,
    desc: 'defloop, 100000 moving rects, per-frame state in volatile!' },
  { name: 'loop-atom-10k', kind: 'loop', build: 'bench-canvas', vanilla: 'loop.js', app: 'loop-atom', n: 10000,
    desc: 'defloop, 10000 moving rects, per-frame state in a watched atom + swap! (the pattern the README warns against); vanilla is the same code as loop-10k' },
  { name: 'many-canvases-1k', kind: 'ops', build: 'bench-dom', vanilla: 'many.js', app: 'many', n: 1000,
    ops: ['create', 'update', 'select', 'clear'], pixels: true,
    desc: '1000 20x20 defdraw cells in a keyed hammer.core list (colour from [:colors id], highlight from (is? [:sel] id)); vanilla: 1000 hand-managed <canvas>' },
  { name: 'gl-points', kind: 'ops', build: 'bench-gl', vanilla: 'gl.js', app: 'gl', n: 100000,
    ops: ['create', 'update', 'clear'], pixels: true, gl: true,
    desc: 'hammer.gl defdraw, 100000 points (gl.POINTS), uniform colour; preserveDrawingBuffer ' +
      'is on in both variants (for readPixels parity), so absolute times include the ' +
      'preserved-buffer copy' },
];

// once: run (unmeasured, unthrottled) before throttling; each: run (unmeasured) before every iteration
function opPlan(op, throttle) {
  switch (op) {
    case 'create': return { each: 'clear', throttle: 1 };
    case 'clear': return { each: 'create', throttle: 1 };
    default: return { once: 'create', throttle: throttle }; // update, select, swap
  }
}

// On a GPU-less machine, headless Chromium's automatic fallback to software
// (SwiftShader) WebGL is deprecated ("Automatic fallback to software WebGL
// has been deprecated") and a future Chromium may drop it, at which point
// gl-points would report "skipped (no WebGL2 context)" instead of running on
// SwiftShader as it does today. --enable-unsafe-swiftshader (opt-in via
// HAMMER_SWIFTSHADER=1 or this script's own --enable-unsafe-swiftshader flag,
// default off -- a real GPU doesn't need it) restores that software renderer
// explicitly. See bench/canvas/README.md.
const BASE_ARGS = ['--js-flags=--expose-gc', '--disable-renderer-backgrounding', '--disable-background-timer-throttling',
  '--disable-backgrounding-occluded-windows', '--no-first-run', '--disable-extensions', '--hide-scrollbars'];
const VIEWPORT = { width: 1400, height: 1500 };

// ---- static server (deterministic: fixed files, no caching)

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.map': 'application/json', '.json': 'application/json', '.edn': 'text/plain' };

function serve() {
  const srv = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    if (u.pathname === '/favicon.ico') { res.writeHead(204); res.end(); return; }
    if (u.pathname === '/') {
      res.writeHead(200, { 'content-type': TYPES['.html'], 'cache-control': 'no-store' });
      res.end('<!doctype html><meta charset="utf-8"><title>blank</title>');
      return;
    }
    const [base, rel] = u.pathname.startsWith('/out/')
      ? [OUT, u.pathname.slice('/out/'.length)] : [PUBLIC, u.pathname.slice(1)];
    const file = path.resolve(base, decodeURIComponent(rel));
    if (!file.startsWith(base + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404); res.end('not found'); return;
    }
    res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream',
      'cache-control': 'no-store' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise(resolve => srv.listen(0, '127.0.0.1', () => resolve(srv)));
}

// ---- in-page functions (serialized by puppeteer)

// trigger → rAF → rAF → setTimeout(0), identical for both variants: hammer's
// frame! is requested from the trigger's own microtask (dispatch's drain,
// then the scheduler's flush), both of which run before the browser's next
// rAF callback, so hammer always draws in the *first* rAF after the trigger
// -- same as vanilla, which requests that frame synchronously. The second
// rAF is belt and braces (kept symmetric for both variants rather than
// trusted to always be redundant); either way both draws land in the first
// frame. The trigger runs in its own setTimeout task, not synchronously in
// page.evaluate: CDP's ScriptDuration does not count script run directly by
// Runtime.callFunctionOn, so vanilla's synchronous op work (DOM creation,
// getContext, textContent = '') would go unmeasured while hammer's
// (deferred to a microtask by dispatch) would count.
const MEASURE = (op, k) => new Promise(resolve => {
  setTimeout(() => {
    const t0 = performance.now();
    window.bench.run(op, k);
    requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(() => resolve(performance.now() - t0), 0)));
  }, 0);
});

const GC = () => { if (window.gc) window.gc(); };

// FNV-1a over every canvas's backing store (2D via getImageData, WebGL2 via
// readPixels), in document order.
const PIXEL_HASH = () => {
  let h = 0x811c9dc5, count = 0;
  for (const c of document.querySelectorAll('canvas')) {
    const ctx2d = c.getContext('2d');
    if (ctx2d) {
      count++;
      h = Math.imul(h ^ c.width, 16777619); h = Math.imul(h ^ c.height, 16777619);
      if (c.width && c.height) {
        const d = new Uint32Array(ctx2d.getImageData(0, 0, c.width, c.height).data.buffer);
        for (let i = 0; i < d.length; i++) h = Math.imul(h ^ d[i], 16777619);
      }
      continue;
    }
    const gl = c.getContext('webgl2');
    if (gl) {
      count++;
      const w = gl.drawingBufferWidth, gh = gl.drawingBufferHeight;
      h = Math.imul(h ^ w, 16777619); h = Math.imul(h ^ gh, 16777619);
      if (w && gh) {
        const buf = new Uint8Array(w * gh * 4);
        gl.readPixels(0, 0, w, gh, gl.RGBA, gl.UNSIGNED_BYTE, buf);
        const d = new Uint32Array(buf.buffer);
        for (let i = 0; i < d.length; i++) h = Math.imul(h ^ d[i], 16777619);
      }
      continue;
    }
    return null;
  }
  return `${count}:${(h >>> 0).toString(16)}`;
};

// Records consecutive rAF timestamp deltas (and draws / draw-fn re-evaluations,
// and Long Animation Frames) for ms milliseconds.
const LOOP_WINDOW = (ms) => new Promise(resolve => {
  const deltas = [], loaf = [];
  let po = null;
  if (PerformanceObserver.supportedEntryTypes.includes('long-animation-frame')) {
    po = new PerformanceObserver(l => { for (const e of l.getEntries()) loaf.push(e.duration); });
    po.observe({ type: 'long-animation-frame' });
  }
  const d0 = window.bench.draws(), r0 = window.bench.renders();
  let last = null, t0 = null;
  function f(ts) {
    if (t0 === null) t0 = ts;
    if (last !== null) deltas.push(ts - last);
    last = ts;
    if (ts - t0 >= ms) {
      if (po) { for (const e of po.takeRecords()) loaf.push(e.duration); po.disconnect(); }
      resolve({ deltas, loaf: po ? loaf : null, draws: window.bench.draws() - d0,
        renders: window.bench.renders() - r0, elapsed: ts - t0 });
    } else requestAnimationFrame(f);
  }
  requestAnimationFrame(f);
});

// ---- stats + formatting

const sorted = xs => [...xs].sort((a, b) => a - b);
const median = xs => { const s = sorted(xs); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const p95 = xs => { const s = sorted(xs); return s[Math.max(0, Math.ceil(0.95 * s.length) - 1)]; };
const mean = xs => xs.reduce((a, b) => a + b, 0) / xs.length;
const f2 = x => (x === null || x === undefined || Number.isNaN(x)) ? '—' : x.toFixed(2);
function delta(x, base) {
  if (base === undefined || base === null || x === null || x === undefined) return '—';
  const d = x - base;
  const pct = base !== 0 ? ` (${d >= 0 ? '+' : ''}${(100 * d / base).toFixed(1)}%)` : '';
  return `${d >= 0 ? '+' : ''}${d.toFixed(2)}${pct}`;
}
const log = (...a) => console.error(...a);

async function metrics(cdp) {
  const { metrics: ms } = await cdp.send('Performance.getMetrics');
  const m = {};
  for (const x of ms) m[x.name] = x.value;
  return m;
}

// ---- page lifecycle

async function openPage(browser, base, sc, variant, opts) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(String(e.message || e)));
  page.on('console', m => {
    if (m.type() === 'error') errors.push(m.text() + (m.location()?.url ? ` (${m.location().url})` : ''));
  });
  await page.setViewport({ ...VIEWPORT, deviceScaleFactor: opts.dpr });
  const src = variant === 'hammer' ? `/out/${sc.build}/main.js` : `/vanilla/${sc.vanilla}`;
  const q = new URLSearchParams({ app: sc.app, n: String(sc.n), src });
  await page.goto(`${base}/page.html?${q}`, { waitUntil: 'load' });
  await page.waitForFunction(() => window.bench && (window.bench.unsupported || window.bench.draws() > 0),
    { timeout: 30000 });
  const cdp = await page.createCDPSession();
  await cdp.send('Performance.enable');
  return { context, page, cdp, errors };
}

async function runOp(browser, base, sc, variant, op, opts) {
  const plan = opPlan(op, opts.throttle);
  const { context, page, cdp, errors } = await openPage(browser, base, sc, variant, opts);
  let k = 0;
  if (plan.once) await page.evaluate(MEASURE, plan.once, k++);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: plan.throttle });
  const wall = [], script = [], task = [];
  for (let i = 0; i < opts.warmups + opts.iterations; i++) {
    if (plan.each) await page.evaluate(MEASURE, plan.each, k++);
    await page.evaluate(GC);
    const m0 = await metrics(cdp);
    const ms = await page.evaluate(MEASURE, op, k++);
    const m1 = await metrics(cdp);
    if (i >= opts.warmups) {
      wall.push(ms);
      script.push((m1.ScriptDuration - m0.ScriptDuration) * 1000);
      task.push((m1.TaskDuration - m0.TaskDuration) * 1000);
    }
  }
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
  const hash = sc.pixels ? await page.evaluate(PIXEL_HASH) : null;
  const draws = await page.evaluate(() => window.bench.draws());
  await context.close();
  return { op, throttle: plan.throttle, wall, script, task, hash, draws, errors };
}

async function runLoop(browser, base, sc, variant, opts) {
  const { context, page, cdp, errors } = await openPage(browser, base, sc, variant, opts);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
  await page.evaluate(ms => new Promise(r => setTimeout(r, ms)), opts.loopWarmupMs);
  await page.evaluate(GC);
  const m0 = await metrics(cdp);
  const w = await page.evaluate(LOOP_WINDOW, opts.windowMs);
  const m1 = await metrics(cdp);
  await context.close();
  const frames = w.draws;
  return {
    frames, elapsed: w.elapsed,
    frameMean: mean(w.deltas), frameP95: p95(w.deltas),
    scriptPerFrame: frames ? (m1.ScriptDuration - m0.ScriptDuration) * 1000 / frames : null,
    taskPerFrame: frames ? (m1.TaskDuration - m0.TaskDuration) * 1000 / frames : null,
    loafCount: w.loaf ? w.loaf.length : null, loafMean: w.loaf && w.loaf.length ? mean(w.loaf) : null,
    rendersPerFrame: frames ? w.renders / frames : null,
    errors,
  };
}

// ---- scenario drivers → markdown

// Alternate which variant runs first (by op index / scenario index) so warm-up
// or thermal drift over a run doesn't always favour the same variant.
const runOrder = (variants, i) => (i % 2 ? [...variants].reverse() : variants);

async function scenarioOps(browser, base, sc, opts, out) {
  const rows = [];
  let bad = false;
  const errs = [];
  for (const [i, op] of sc.ops.entries()) {
    const res = {};
    for (const v of runOrder(opts.variants, i)) {
      log(`  ${sc.name} ${op} ${v}`);
      res[v] = await runOp(browser, base, sc, v, op, opts);
      for (const e of res[v].errors) errs.push(`${v} ${op}: ${e}`);
    }
    const b = res.vanilla;
    for (const v of opts.variants) {
      const r = res[v];
      const wm = median(r.wall), sm = median(r.script), tm = median(r.task);
      let px = '';
      if (v === 'hammer' && sc.pixels && b) {
        px = r.hash !== null && r.hash === b.hash ? 'ok' : `MISMATCH (${r.hash} vs ${b.hash})`;
        if (px !== 'ok') bad = true;
      } else if (!sc.pixels) px = 'n/a';
      rows.push(`| ${op} | ${r.throttle}x | ${v} | ${f2(wm)} | ${f2(p95(r.wall))} | ${v === 'vanilla' ? '—' : delta(wm, b && median(b.wall))} ` +
        `| ${f2(sm)} | ${f2(p95(r.script))} | ${v === 'vanilla' ? '—' : delta(sm, b && median(b.script))} ` +
        `| ${f2(tm)} | ${v === 'vanilla' ? '—' : delta(tm, b && median(b.task))} | ${px} |`);
    }
  }
  out.push(`## ${sc.name}\n\n${sc.desc}. n=${sc.n}. All times in ms.\n`);
  out.push('| op | CPU throttle | variant | wall median | wall p95 | Δ wall vs vanilla | script median | script p95 | Δ script vs vanilla | task median | Δ task vs vanilla | pixel parity |');
  out.push('|---|---|---|---|---|---|---|---|---|---|---|---|');
  out.push(...rows);
  if (errs.length) { bad = true; out.push('', '**Page errors:**', ...errs.map(e => `- ${e}`)); }
  out.push('');
  return bad;
}

async function scenarioLoop(browser, base, sc, opts, out, idx) {
  const res = {};
  const errs = [];
  for (const v of runOrder(opts.variants, idx)) {
    log(`  ${sc.name} ${v}`);
    res[v] = await runLoop(browser, base, sc, v, opts);
    for (const e of res[v].errors) errs.push(`${v}: ${e}`);
  }
  const b = res.vanilla;
  out.push(`## ${sc.name}\n\n${sc.desc}. n=${sc.n}. CPU throttle 1x (none). ` +
    `${opts.windowMs} ms window after ${opts.loopWarmupMs} ms warm-up. All times in ms.\n`);
  out.push('| variant | frames drawn | frame Δt mean | frame Δt p95 | Δ mean vs vanilla | script / frame | Δ script vs vanilla | task / frame | Δ task vs vanilla | LoAF (>50 ms) count | LoAF mean | draw-fn re-evals / frame |');
  out.push('|---|---|---|---|---|---|---|---|---|---|---|---|');
  for (const v of opts.variants) {
    const r = res[v];
    const d = (x, y) => v === 'vanilla' ? '—' : delta(x, b && y);
    out.push(`| ${v} | ${r.frames} | ${f2(r.frameMean)} | ${f2(r.frameP95)} | ${d(r.frameMean, b?.frameMean)} ` +
      `| ${f2(r.scriptPerFrame)} | ${d(r.scriptPerFrame, b?.scriptPerFrame)} | ${f2(r.taskPerFrame)} | ${d(r.taskPerFrame, b?.taskPerFrame)} ` +
      `| ${r.loafCount ?? 'n/a'} | ${f2(r.loafMean)} | ${v === 'vanilla' ? '—' : f2(r.rendersPerFrame)} |`);
  }
  if (errs.length) out.push('', '**Page errors:**', ...errs.map(e => `- ${e}`));
  out.push('');
  return errs.length > 0;
}

async function glSupported(browser, base) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  await page.goto(`${base}/`);
  const ok = await page.evaluate(() => !!document.createElement('canvas').getContext('webgl2'));
  await context.close();
  return ok;
}

async function canvasRenderer(browser, base) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  await page.goto(`${base}/`);
  const r = await page.evaluate(() => {
    const gl = document.createElement('canvas').getContext('webgl2');
    if (!gl) return 'no WebGL2';
    const e = gl.getExtension('WEBGL_debug_renderer_info');
    return e ? gl.getParameter(e.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
  });
  await context.close();
  return r;
}

// ---- main

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const chosen = opts.scenario
    ? opts.scenario.map(n => SCENARIOS.find(s => s.name === n) || (() => { throw new Error(`unknown scenario ${n}`); })())
    : SCENARIOS;
  for (const b of new Set(chosen.map(s => s.build))) {
    if (opts.variants.includes('hammer') && !fs.existsSync(path.join(OUT, b, 'main.js'))) {
      throw new Error(`missing target/bench/${b}/main.js — run \`npx shadow-cljs release bench-canvas bench-dom bench-gl\` (or \`bb bench-canvas\`)`);
    }
  }
  if (!fs.existsSync(CHROME)) throw new Error(`Chromium not found at ${CHROME} (set CHROME)`);

  const srv = await serve();
  const base = `http://127.0.0.1:${srv.address().port}`;
  const swiftshaderArgs = opts.swiftshader ? ['--enable-unsafe-swiftshader'] : [];
  const launch = extra => puppeteer.launch({ executablePath: CHROME, headless: true,
    args: [...BASE_ARGS, ...swiftshaderArgs, ...extra], protocolTimeout: 600000 });

  const out = [];
  let failed = false;

  const browser = await launch([]);
  const version = await browser.version();
  const renderer = await canvasRenderer(browser, base);
  const cpus = os.cpus();
  const load = os.loadavg().map(x => x.toFixed(2)).join(' ');

  const header = [
    '# hammer canvas benchmark', '',
    `- Browser: ${version}, headless (${CHROME}); WebGL renderer: ${renderer}`,
    `- Node ${process.version}; ${cpus.length}x ${cpus[0]?.model?.trim()}; load average at start: ${load}`,
    `- Viewport ${VIEWPORT.width}x${VIEWPORT.height}, devicePixelRatio ${opts.dpr}`,
    `- Ops: ${opts.warmups} warm-ups + ${opts.iterations} measured iterations per op, fresh browser context (page) per op and variant, variant order alternating per op; gc() before each iteration`,
    `- CPU throttle (CDP Emulation.setCPUThrottlingRate): ${opts.throttle}x for update/select/swap; 1x (none) for create/clear and all loops`,
    `- wall = trigger (page.evaluate → its own setTimeout(0) task → bench.run) until rAF → rAF → setTimeout(0), same for both variants. Frame-paced (60 Hz, ~16.7 ms frames): it depends on where in the frame the trigger lands, so ops that fit in one frame read 1-2 frames for both variants; compare script/task for those`,
    `- script / task = CDP Performance.getMetrics ScriptDuration / TaskDuration delta around each measured trigger (main-thread time, not quantized)`,
    `- Loops: consecutive rAF timestamp deltas over a ${opts.windowMs} ms window; script/task per frame = metric delta over the window / frames drawn`,
    `- pixel parity: FNV hash of every canvas's backing store after the last iteration (2D via getImageData, WebGL2 via readPixels), hammer vs vanilla`,
    '',
  ];

  try {
    let glOk = null;
    for (const [idx, sc] of chosen.entries()) {
      log(`scenario ${sc.name}`);
      if (sc.gl) {
        if (glOk === null) glOk = await glSupported(browser, base);
        if (!glOk) { out.push(`## ${sc.name}\n\nskipped (no WebGL2 context)\n`); continue; }
        // headless CI/dev machines often have no GPU, so Chromium falls back to a
        // software (SwiftShader) WebGL2 renderer; absolute times aren't comparable
        // to a hardware run, so flag it in the scenario's own description.
        if (/swiftshader/i.test(renderer)) sc.desc += ' (SOFTWARE renderer)';
      }
      const bad = sc.kind === 'loop'
        ? await scenarioLoop(browser, base, sc, opts, out, idx)
        : await scenarioOps(browser, base, sc, opts, out);
      failed = failed || bad;
    }
  } finally {
    await browser.close();
  }

  srv.close();
  console.log([...header, ...out].join('\n'));
  if (failed) { log('FAIL: pixel mismatch or page errors (see output)'); process.exit(1); }
}

main().catch(e => { console.error(e); process.exit(1); });
