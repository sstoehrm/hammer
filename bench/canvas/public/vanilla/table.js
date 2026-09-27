// Vanilla baseline for table-1k (?app=table) and rects-10k (?app=rects).
// Issues the same Canvas calls in the same order as bench-canvas.table /
// bench-canvas.rects under hammer.canvas: per frame, backing-store sync
// (ceil(css * dpr)), setTransform(dpr, 0, 0, dpr, 0, 0), then the draw.
// State changes only request a frame; drawing happens in requestAnimationFrame.
(function () {
  'use strict';
  const D = window.BenchData;
  const p = new URLSearchParams(location.search);
  const app = p.get('app');
  const n = parseInt(p.get('n'), 10);
  const L = app === 'table' ? D.TABLE : D.RECTS;

  const el = document.getElementById('app');
  el.textContent = '';
  const canvas = document.createElement('canvas');
  canvas.style.setProperty('width', L.W + 'px');
  canvas.style.setProperty('height', L.H + 'px');
  const ctx = canvas.getContext('2d');
  el.appendChild(canvas);

  let items = [], sel = null, pending = false, draws = 0, dpr = 1;

  function syncSize() {
    dpr = globalThis.devicePixelRatio || 1;
    const bw = Math.ceil(L.W * dpr), bh = Math.ceil(L.H * dpr);
    if (bw !== canvas.width || bh !== canvas.height) { canvas.width = bw; canvas.height = bh; }
  }

  function drawTable() {
    const T = D.TABLE;
    ctx.clearRect(0, 0, T.W, T.H);
    ctx.font = T.FONT;
    ctx.textBaseline = 'middle';
    for (let i = 0; i < items.length; i++) {
      const r = items[i];
      const x = Math.floor(i / T.RPC) * T.CW, y = (i % T.RPC) * T.CH;
      ctx.fillStyle = r.id === sel ? T.HL : (i % 2 === 0 ? T.ROW_A : T.ROW_B);
      ctx.fillRect(x, y, T.CW - 1, T.CH - 1);
      ctx.fillStyle = T.TEXT;
      ctx.fillText(r.label, x + 3, y + T.CH / 2);
    }
  }

  function drawRects() {
    const R = D.RECTS, P = D.PALETTE;
    ctx.clearRect(0, 0, R.W, R.H);
    for (let i = 0; i < items.length; i++) {
      const r = items[i];
      ctx.fillStyle = r.id === sel ? R.HL : P[r.c];
      ctx.fillRect(r.x, r.y, r.w, r.h);
    }
  }

  const draw = app === 'table' ? drawTable : drawRects;

  function frame() {
    pending = false;
    syncSize();
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    draws++;
    draw();
  }

  function request() {
    if (!pending) { pending = true; requestAnimationFrame(frame); }
  }

  const ops = {
    create() {
      items = app === 'table' ? D.tableRows(n, D.SEED) : D.rects(n, D.SEED);
      sel = null;
    },
    update() {
      if (app === 'table') for (let i = 0; i < items.length; i += 10) items[i].label += ' !!!';
      else for (let i = 0; i < items.length; i += 10) items[i].c = (items[i].c + 1) % D.PALETTE.length;
    },
    select(k) { sel = items[D.selectIndex(k, items.length)].id; },
    swap() {
      const j = items.length - 2;
      if (items.length > j && j > 1) { const t = items[1]; items[1] = items[j]; items[j] = t; }
    },
    clear() { items = []; sel = null; },
  };

  request(); // the initial (empty) draw, like hammer's mount
  window.bench = {
    run(op, k) { ops[op](k); request(); },
    draws: () => draws,
    renders: () => 0,
  };
})();
