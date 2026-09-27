// Vanilla baseline for loop-N and loop-atom-N (?app=loop|loop-atom, same
// code for both: there is no atom/volatile distinction without hammer).
// Same per-frame work as bench-canvas.loops: dt = min(100, ts - last) (0 on
// the first frame), backing-store sync, setTransform(dpr...), step (mutates
// the typed arrays, returns a fresh wrapper), clearRect, fillStyle, N fillRects,
// then request the next frame.
(function () {
  'use strict';
  const D = window.BenchData;
  const L = D.LOOP;
  const n = parseInt(new URLSearchParams(location.search).get('n'), 10);

  const el = document.getElementById('app');
  el.textContent = '';
  const canvas = document.createElement('canvas');
  canvas.style.setProperty('width', L.W + 'px');
  canvas.style.setProperty('height', L.H + 'px');
  const ctx = canvas.getContext('2d');
  el.appendChild(canvas);

  let world = D.particles(n, D.SEED), last = null, draws = 0, dpr = 1;

  function syncSize() {
    dpr = globalThis.devicePixelRatio || 1;
    const bw = Math.ceil(L.W * dpr), bh = Math.ceil(L.H * dpr);
    if (bw !== canvas.width || bh !== canvas.height) { canvas.width = bw; canvas.height = bh; }
  }

  function step(p, dt, w, h) {
    const xs = p.xs, ys = p.ys, vxs = p.vxs, vys = p.vys, m = p.n;
    for (let i = 0; i < m; i++) {
      const x = xs[i] + vxs[i] * dt, y = ys[i] + vys[i] * dt;
      xs[i] = x;
      ys[i] = y;
      if (x < 0 || x > w) vxs[i] = -vxs[i];
      if (y < 0 || y > h) vys[i] = -vys[i];
    }
    return { xs: xs, ys: ys, vxs: vxs, vys: vys, n: m };
  }

  function paint(p, w, h) {
    draws++;
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = L.COLOR;
    const xs = p.xs, ys = p.ys, m = p.n;
    for (let i = 0; i < m; i++) ctx.fillRect(xs[i], ys[i], L.SIZE, L.SIZE);
  }

  function frame(ts) {
    const dt = last === null ? 0 : Math.min(100, ts - last);
    last = ts;
    syncSize();
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    world = step(world, dt, L.W, L.H);
    paint(world, L.W, L.H);
    requestAnimationFrame(frame);
  }

  requestAnimationFrame(frame);
  window.bench = { run() {}, draws: () => draws, renders: () => 0 };
})();
