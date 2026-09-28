// Seeded, deterministic data + layout shared by the hammer and vanilla
// variants. Loaded as a plain script before either variant, so both draw
// exactly the same things. No Math.random anywhere in the benchmark.
(function () {
  'use strict';

  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  // js-framework-benchmark's word lists
  const A = ['pretty', 'large', 'big', 'small', 'tall', 'short', 'long', 'handsome', 'plain', 'quaint',
    'clean', 'elegant', 'easy', 'angry', 'crazy', 'helpful', 'mushy', 'odd', 'unsightly', 'adorable',
    'important', 'inexpensive', 'cheap', 'expensive', 'fancy'];
  const C = ['red', 'yellow', 'blue', 'green', 'pink', 'brown', 'purple', 'brown', 'white', 'black', 'orange'];
  const N = ['table', 'chair', 'house', 'bbq', 'desk', 'car', 'pony', 'cookie', 'sandwich', 'burger',
    'pizza', 'mouse', 'keyboard'];

  const PALETTE = ['#4e79a7', '#f28e2b', '#e15759', '#76b7b2', '#59a14f', '#edc948', '#b07aa1', '#ff9da7'];

  const SEED = 1234567;

  // Layouts (CSS pixels). Both variants read these.
  const TABLE = { W: 1300, H: 1400, COLS: 10, RPC: 100, CW: 130, CH: 14,
    FONT: '10px sans-serif', ROW_A: '#eef1f6', ROW_B: '#ffffff', TEXT: '#222222', HL: '#ffcc00' };
  const RECTS = { W: 1300, H: 1400, HL: '#000000' };
  const LOOP = { W: 1300, H: 800, SIZE: 3, COLOR: '#e8590c' };
  const MANY = { S: 20, HL: '#e03131', UPD: '#000000' };
  const GL = { W: 1024, H: 768 };

  function pick(r, xs) { return xs[(r() * xs.length) | 0]; }

  // [{id, label}] — ids 1..n, same every call for the same seed
  function tableRows(n, seed) {
    const r = mulberry32(seed);
    const out = new Array(n);
    for (let i = 0; i < n; i++) out[i] = { id: i + 1, label: pick(r, A) + ' ' + pick(r, C) + ' ' + pick(r, N) };
    return out;
  }

  // [{id, x, y, w, h, c}] — c is a PALETTE index
  function rects(n, seed) {
    const r = mulberry32(seed);
    const out = new Array(n);
    for (let i = 0; i < n; i++) {
      const w = 4 + Math.floor(r() * 16), h = 4 + Math.floor(r() * 16);
      out[i] = { id: i + 1, x: Math.floor(r() * (RECTS.W - w)), y: Math.floor(r() * (RECTS.H - h)),
        w: w, h: h, c: (r() * PALETTE.length) | 0 };
    }
    return out;
  }

  // Loop state: typed arrays (positions px, velocities px/ms)
  function particles(n, seed) {
    const r = mulberry32(seed);
    const xs = new Float64Array(n), ys = new Float64Array(n), vxs = new Float64Array(n), vys = new Float64Array(n);
    for (let i = 0; i < n; i++) {
      xs[i] = r() * LOOP.W; ys[i] = r() * LOOP.H;
      vxs[i] = r() * 0.4 - 0.2; vys[i] = r() * 0.4 - 0.2;
    }
    return { xs: xs, ys: ys, vxs: vxs, vys: vys, n: n };
  }

  // One PALETTE colour per cell, ids 1..n
  function cellColors(n, seed) {
    const r = mulberry32(seed);
    const out = new Array(n);
    for (let i = 0; i < n; i++) out[i] = pick(r, PALETTE);
    return out;
  }

  // n points in clip space, interleaved x,y
  function glPoints(n, seed) {
    const r = mulberry32(seed);
    const a = new Float32Array(n * 2);
    for (let i = 0; i < a.length; i++) a[i] = r() * 2 - 1;
    return a;
  }

  // RGBA colour for gl-points update k (always differs from k-1)
  function glColor(k) {
    return new Float32Array([((k * 37) % 100) / 100, 0.6, ((k * 11) % 100) / 100, 1]);
  }

  // Index of the row to select at iteration k (consecutive k never repeat)
  function selectIndex(k, n) { return (((k * 37) % n) + n) % n; }

  // GLSL ES 3.00: point-cloud shader shared by hammer.gl and vanilla gl.js
  const GL_VS = `#version 300 es
layout(location=0) in vec2 pos;
void main() {
  gl_Position = vec4(pos, 0.0, 1.0);
  gl_PointSize = 1.0;
}
`;
  const GL_FS = `#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 outColor;
void main() { outColor = u_color; }
`;

  window.BenchData = { mulberry32, tableRows, rects, particles, cellColors, glPoints, glColor, selectIndex,
    PALETTE, SEED, TABLE, RECTS, LOOP, MANY, GL, GL_VS, GL_FS };
})();
