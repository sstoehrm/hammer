// Vanilla baseline for many-canvases-1k (?app=many): n plain 20x20 <canvas>
// elements in a div.grid, updated by hand. Only the affected canvases are
// redrawn (update: 1, select: old + new), in id order, in requestAnimationFrame,
// with the same calls per canvas as bench-canvas.many's `cell`:
// backing-store sync, setTransform(dpr...), clearRect, fillStyle, fillRect.
(function () {
  'use strict';
  const D = window.BenchData;
  const M = D.MANY;
  const n = parseInt(new URLSearchParams(location.search).get('n'), 10);

  const el = document.getElementById('app');
  el.textContent = '';
  const grid = document.createElement('div');
  grid.className = 'grid';
  el.appendChild(grid);

  let cells = [], sel = null, pending = false, draws = 1; // 1: the empty mount counts as ready
  const dirty = new Set();

  function drawCell(id) {
    const cell = cells[id - 1];
    if (!cell) return;
    const c = cell.canvas, ctx = cell.ctx;
    const dpr = globalThis.devicePixelRatio || 1;
    const bw = Math.ceil(M.S * dpr), bh = Math.ceil(M.S * dpr);
    if (bw !== c.width || bh !== c.height) { c.width = bw; c.height = bh; }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    draws++;
    ctx.clearRect(0, 0, M.S, M.S);
    ctx.fillStyle = sel === id ? M.HL : cell.color;
    ctx.fillRect(2, 2, M.S - 4, M.S - 4);
  }

  function frame() {
    pending = false;
    const ids = Array.from(dirty).sort((a, b) => a - b);
    dirty.clear();
    for (let i = 0; i < ids.length; i++) drawCell(ids[i]);
  }

  function request() {
    if (!pending && dirty.size > 0) { pending = true; requestAnimationFrame(frame); }
  }

  const idAt = (k) => D.selectIndex(k, n) + 1;

  const ops = {
    create() {
      const colors = D.cellColors(n, D.SEED);
      cells = new Array(n);
      sel = null;
      for (let i = 0; i < n; i++) {
        const c = document.createElement('canvas');
        c.style.setProperty('width', M.S + 'px');
        c.style.setProperty('height', M.S + 'px');
        const ctx = c.getContext('2d');
        grid.appendChild(c);
        cells[i] = { canvas: c, ctx: ctx, color: colors[i] };
        dirty.add(i + 1);
      }
    },
    update(k) {
      const id = idAt(k);
      cells[id - 1].color = M.UPD;
      dirty.add(id);
    },
    select(k) {
      const id = idAt(k);
      if (id === sel) return;
      if (sel !== null) dirty.add(sel);
      sel = id;
      dirty.add(id);
    },
    clear() {
      grid.textContent = '';
      cells = [];
      sel = null;
      dirty.clear();
    },
  };

  window.bench = {
    run(op, k) { ops[op](k); request(); },
    draws: () => draws,
    renders: () => 0,
  };
})();
