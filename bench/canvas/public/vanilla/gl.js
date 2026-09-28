// Vanilla baseline for gl-points (?app=gl): same program, buffer and per-draw
// WebGL2 calls as bench-canvas.gl under hammer.gl (backing-store sync,
// viewport, vertex bufferSubData only when the points changed, uniform
// upload, one clear + drawArrays(POINTS) per draw), drawn in
// requestAnimationFrame.
(function () {
  'use strict';
  const D = window.BenchData;
  const G = D.GL;
  const n = parseInt(new URLSearchParams(location.search).get('n'), 10);

  const el = document.getElementById('app');
  el.textContent = '';
  const canvas = document.createElement('canvas');
  canvas.style.setProperty('width', G.W + 'px');
  canvas.style.setProperty('height', G.H + 'px');
  el.appendChild(canvas);

  // preserveDrawingBuffer: readPixels (pixel parity in run.mjs) needs the
  // drawing buffer kept around after the browser would otherwise clear it on
  // composite; the hammer variant requests the same attr (:context-attrs).
  const gl = canvas.getContext('webgl2', { preserveDrawingBuffer: true });
  if (!gl) { window.bench = { unsupported: true, run() {}, draws: () => 0, renders: () => 0 }; return; }

  function shader(type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    return s;
  }

  const prog = gl.createProgram();
  const vs = shader(gl.VERTEX_SHADER, D.GL_VS);
  const fs = shader(gl.FRAGMENT_SHADER, D.GL_FS);
  const buf = gl.createBuffer();
  gl.attachShader(prog, vs);
  gl.attachShader(prog, fs);
  gl.linkProgram(prog);
  gl.deleteShader(vs);
  gl.deleteShader(fs);
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, n * 8, gl.DYNAMIC_DRAW);
  const uloc = gl.getUniformLocation(prog, 'u_color');

  const empty = new Float32Array(0);
  let pts = empty, color = D.glColor(0), uploaded = null, pending = false, draws = 0;

  function frame() {
    pending = false;
    const dpr = globalThis.devicePixelRatio || 1;
    const bw = Math.ceil(G.W * dpr), bh = Math.ceil(G.H * dpr);
    if (bw !== canvas.width || bh !== canvas.height) { canvas.width = bw; canvas.height = bh; }
    gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight);
    draws++;
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    if (pts !== uploaded) {
      if (pts.length > 0) gl.bufferSubData(gl.ARRAY_BUFFER, 0, pts);
      uploaded = pts;
    }
    gl.useProgram(prog);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.uniform4fv(uloc, color);
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.drawArrays(gl.POINTS, 0, pts.length / 2);
  }

  function request() {
    if (!pending) { pending = true; requestAnimationFrame(frame); }
  }

  const ops = {
    create() { pts = D.glPoints(n, D.SEED); },
    update(k) { color = D.glColor(k); },
    clear() { pts = empty; },
  };

  request();
  window.bench = { run(op, k) { ops[op](k); request(); }, draws: () => draws, renders: () => 0 };
})();
