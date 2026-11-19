/**
 * Analyzes surrounding pixels outside a text bounding box on an HTML5 Canvas
 * to reconstruct solid white, solid color, or edge-interpolated complex backgrounds.
 */
export function analyzeBackgroundRegion(canvas, normBounds) {
  if (!canvas || canvas.width < 8 || canvas.height < 8) {
    return { mode: 'SOLID_WHITE', fillColor: '#ffffff', patchCanvas: null, note: null };
  }

  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const w = canvas.width;
  const h = canvas.height;

  const left = Math.max(0, Math.min(w - 1, Math.floor(normBounds.left * w)));
  const top = Math.max(0, Math.min(h - 1, Math.floor(normBounds.top * h)));
  const right = Math.max(left + 1, Math.min(w, Math.ceil(normBounds.right * w)));
  const bottom = Math.max(top + 1, Math.min(h, Math.ceil(normBounds.bottom * h)));

  const pad = Math.max(3, Math.min(8, Math.floor((bottom - top) / 3)));
  const oLeft = Math.max(0, left - pad);
  const oTop = Math.max(0, top - pad);
  const oRight = Math.min(w - 1, right + pad);
  const oBottom = Math.min(h - 1, bottom + pad);

  const regionW = oRight - oLeft + 1;
  const regionH = oBottom - oTop + 1;
  const imgData = ctx.getImageData(oLeft, oTop, regionW, regionH).data;

  const samples = [];
  const sampleAt = (gx, gy) => {
    const lx = gx - oLeft;
    const ly = gy - oTop;
    const idx = (ly * regionW + lx) * 4;
    const r = imgData[idx];
    const g = imgData[idx + 1];
    const b = imgData[idx + 2];
    const lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
    samples.push({ r, g, b, lum });
  };

  for (let x = oLeft; x <= oRight; x += 2) {
    if (oTop < top) sampleAt(x, oTop);
    if (oBottom > bottom - 1) sampleAt(x, oBottom);
  }
  for (let y = oTop; y <= oBottom; y += 2) {
    if (oLeft < left) sampleAt(oLeft, y);
    if (oRight > right - 1) sampleAt(oRight, y);
  }

  if (samples.length === 0) {
    return { mode: 'SOLID_WHITE', fillColor: '#ffffff', patchCanvas: null, note: null };
  }

  samples.sort((a, b) => a.lum - b.lum);
  const clean = samples.slice(Math.min(samples.length - 1, Math.floor(samples.length * 0.25)));
  const median = clean[Math.floor(clean.length / 2)];

  let rSum = 0;
  let gSum = 0;
  let bSum = 0;
  for (const s of clean) {
    rSum += s.r;
    gSum += s.g;
    bSum += s.b;
  }
  const rMean = rSum / clean.length;
  const gMean = gSum / clean.length;
  const bMean = bSum / clean.length;

  let varSum = 0;
  for (const s of clean) {
    varSum += ((s.r - rMean) ** 2 + (s.g - gMean) ** 2 + (s.b - bMean) ** 2) / 3;
  }
  const stdDev = Math.sqrt(varSum / clean.length);
  const hex = rgbToHex(median.r, median.g, median.b);

  if (stdDev < 10 && median.r >= 242 && median.g >= 242 && median.b >= 242) {
    return { mode: 'SOLID_WHITE', fillColor: '#ffffff', patchCanvas: null, note: null };
  }
  if (stdDev < 15) {
    return { mode: 'SOLID_COLOR', fillColor: hex, patchCanvas: null, note: null };
  }

  const patchCanvas = buildInterpolatedPatch(ctx, w, h, left, top, right, bottom);
  return {
    mode: 'COMPLEX_INTERPOLATED',
    fillColor: hex,
    patchCanvas,
    note: 'Complex background reconstructed via edge interpolation',
  };
}

export function estimateInkColor(canvas, normBounds, bgHex) {
  if (!canvas) return '#0f172a';
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const w = canvas.width;
  const h = canvas.height;
  const left = Math.max(0, Math.floor(normBounds.left * w));
  const top = Math.max(0, Math.floor(normBounds.top * h));
  const boxW = Math.max(1, Math.ceil((normBounds.right - normBounds.left) * w));
  const boxH = Math.max(1, Math.ceil((normBounds.bottom - normBounds.top) * h));
  const data = ctx.getImageData(left, top, boxW, boxH).data;
  const bg = hexToRgb(bgHex);

  const candidates = [];
  for (let i = 0; i < data.length; i += 16) {
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const dist = Math.abs(r - bg.r) + Math.abs(g - bg.g) + Math.abs(b - bg.b);
    if (dist > 60) candidates.push({ r, g, b, dist });
  }
  if (candidates.length === 0) return '#0f172a';
  candidates.sort((a, b) => b.dist - a.dist);
  const topN = Math.max(1, Math.floor(candidates.length / 3));
  let rSum = 0;
  let gSum = 0;
  let bSum = 0;
  for (let i = 0; i < topN; i++) {
    rSum += candidates[i].r;
    gSum += candidates[i].g;
    bSum += candidates[i].b;
  }
  return rgbToHex(Math.round(rSum / topN), Math.round(gSum / topN), Math.round(bSum / topN));
}

function buildInterpolatedPatch(ctx, w, h, left, top, right, bottom) {
  const boxW = Math.max(4, right - left);
  const boxH = Math.max(4, bottom - top);
  const topRowY = Math.max(0, top - 2);
  const bottomRowY = Math.min(h - 1, bottom + 1);
  const leftColX = Math.max(0, left - 2);
  const rightColX = Math.min(w - 1, right + 1);

  const topData = ctx.getImageData(left, topRowY, boxW, 1).data;
  const botData = ctx.getImageData(left, bottomRowY, boxW, 1).data;
  const leftData = ctx.getImageData(leftColX, top, 1, boxH).data;
  const rightData = ctx.getImageData(rightColX, top, 1, boxH).data;

  const patch = document.createElement('canvas');
  patch.width = boxW;
  patch.height = boxH;
  const pCtx = patch.getContext('2d');
  const out = pCtx.createImageData(boxW, boxH);

  for (let y = 0; y < boxH; y++) {
    const v = (y + 0.5) / boxH;
    const li = y * 4;
    for (let x = 0; x < boxW; x++) {
      const u = (x + 0.5) / boxW;
      const ti = x * 4;
      const oi = (y * boxW + x) * 4;

      const vR = topData[ti] * (1 - v) + botData[ti] * v;
      const vG = topData[ti + 1] * (1 - v) + botData[ti + 1] * v;
      const vB = topData[ti + 2] * (1 - v) + botData[ti + 2] * v;

      const hR = leftData[li] * (1 - u) + rightData[li] * u;
      const hG = leftData[li + 1] * (1 - u) + rightData[li + 1] * u;
      const hB = leftData[li + 2] * (1 - u) + rightData[li + 2] * u;

      out.data[oi] = Math.round(vR * 0.72 + hR * 0.28);
      out.data[oi + 1] = Math.round(vG * 0.72 + hG * 0.28);
      out.data[oi + 2] = Math.round(vB * 0.72 + hB * 0.28);
      out.data[oi + 3] = 255;
    }
  }
  pCtx.putImageData(out, 0, 0);
  return patch;
}

export function rgbToHex(r, g, b) {
  const toHex = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0');
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

export function hexToRgb(hex) {
  const clean = (hex || '#0f172a').replace('#', '');
  const num = parseInt(clean, 16);
  if (Number.isNaN(num)) return { r: 15, g: 23, b: 42 };
  return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255 };
}
