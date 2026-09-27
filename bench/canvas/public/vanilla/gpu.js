// Vanilla baseline for gpu-points (?app=gpu): same pipeline, buffers and
// per-draw WebGPU calls as bench-canvas.gpu under hammer.gpu (backing-store
// sync, getCurrentTexture().createView(), vertex writeBuffer only when the
// points changed, uniform writeBuffer, one clear pass with point-list draw,
// submit), drawn in requestAnimationFrame.
(async function () {
  'use strict';
  const D = window.BenchData;
  const G = D.GPU;
  const n = parseInt(new URLSearchParams(location.search).get('n'), 10);

  const el = document.getElementById('app');
  el.textContent = '';
  const canvas = document.createElement('canvas');
  canvas.style.setProperty('width', G.W + 'px');
  canvas.style.setProperty('height', G.H + 'px');
  el.appendChild(canvas);

  const adapter = navigator.gpu && await navigator.gpu.requestAdapter();
  if (!adapter) { window.bench = { unsupported: true, run() {}, draws: () => 0, renders: () => 0 }; return; }
  const device = await adapter.requestDevice();
  const format = navigator.gpu.getPreferredCanvasFormat();
  const ctx = canvas.getContext('webgpu');
  ctx.configure({ device: device, format: format, alphaMode: 'premultiplied' });

  const module = device.createShaderModule({ code: D.GPU_SHADER });
  const pipeline = device.createRenderPipeline({
    layout: 'auto',
    vertex: { module: module, entryPoint: 'vs',
      buffers: [{ arrayStride: 8, attributes: [{ shaderLocation: 0, offset: 0, format: 'float32x2' }] }] },
    fragment: { module: module, entryPoint: 'fs', targets: [{ format: format }] },
    primitive: { topology: 'point-list' },
  });
  const vbuf = device.createBuffer({ size: n * 8, usage: GPUBufferUsage.VERTEX | GPUBufferUsage.COPY_DST });
  const ubuf = device.createBuffer({ size: 16, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST });
  const bind = device.createBindGroup({ layout: pipeline.getBindGroupLayout(0),
    entries: [{ binding: 0, resource: { buffer: ubuf } }] });

  const empty = new Float32Array(0);
  let pts = empty, color = D.gpuColor(0), uploaded = null, pending = false, draws = 0;

  function frame() {
    pending = false;
    const dpr = globalThis.devicePixelRatio || 1;
    const bw = Math.ceil(G.W * dpr), bh = Math.ceil(G.H * dpr);
    if (bw !== canvas.width || bh !== canvas.height) { canvas.width = bw; canvas.height = bh; }
    const view = ctx.getCurrentTexture().createView();
    draws++;
    const q = device.queue;
    if (pts !== uploaded) {
      if (pts.length > 0) q.writeBuffer(vbuf, 0, pts);
      uploaded = pts;
    }
    q.writeBuffer(ubuf, 0, color);
    const enc = device.createCommandEncoder();
    const pass = enc.beginRenderPass({ colorAttachments: [{ view: view, loadOp: 'clear', storeOp: 'store',
      clearValue: { r: 0, g: 0, b: 0, a: 1 } }] });
    pass.setPipeline(pipeline);
    pass.setBindGroup(0, bind);
    pass.setVertexBuffer(0, vbuf);
    pass.draw(pts.length / 2);
    pass.end();
    q.submit([enc.finish()]);
  }

  function request() {
    if (!pending) { pending = true; requestAnimationFrame(frame); }
  }

  const ops = {
    create() { pts = D.gpuPoints(n, D.SEED); },
    update(k) { color = D.gpuColor(k); },
    clear() { pts = empty; },
  };

  request();
  window.bench = { run(op, k) { ops[op](k); request(); }, draws: () => draws, renders: () => 0 };
})();
