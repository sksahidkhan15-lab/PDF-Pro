import * as pdfjsLib from 'pdfjs-dist';
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import Tesseract from 'tesseract.js';
import { analyzeBackgroundRegion, estimateInkColor, rgbToHex } from './backgroundRestorer.js';

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

export async function loadPdfDocument(bytes) {
  const copy = bytes.slice();
  const header = new TextDecoder('ascii').decode(copy.subarray(0, 5));
  if (!header.startsWith('%PDF')) {
    throw new Error('Invalid PDF file: missing %PDF header.');
  }
  const task = pdfjsLib.getDocument({ data: copy });
  return await task.promise;
}

export async function renderPageToCanvas(pdfDoc, pageIndex, scale = 1.6) {
  const page = await pdfDoc.getPage(pageIndex + 1);
  const viewport = page.getViewport({ scale });
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(viewport.width));
  canvas.height = Math.max(1, Math.round(viewport.height));
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  await page.render({ canvasContext: ctx, viewport }).promise;
  return { canvas, page, viewport };
}

export async function extractVectorSegments(page, pageIndex, renderedCanvas) {
  const viewport = page.getViewport({ scale: 1.0 });
  const pageWidthPt = viewport.width;
  const pageHeightPt = viewport.height;

  const [textContent, colorMap] = await Promise.all([
    page.getTextContent(),
    extractTextOperatorColors(page),
  ]);

  const rawItems = [];
  let textOpIndex = 0;

  for (const item of textContent.items) {
    if (!item || typeof item.str !== 'string') continue;
    const str = item.str;
    if (!str.trim()) {
      textOpIndex++;
      continue;
    }

    const tx = pdfjsLib.Util.transform(viewport.transform, item.transform);
    const fontSizePt = Math.max(6, Math.hypot(item.transform[2], item.transform[3]) || item.height || 12);
    const angleRad = Math.atan2(item.transform[1], item.transform[0]);
    const rotationDeg = Math.abs((angleRad * 180) / Math.PI) < 1 ? 0 : (angleRad * 180) / Math.PI;

    const xDisplay = tx[4];
    const baselineDisplayY = tx[5];
    const widthPt = Math.max(item.width || fontSizePt * 0.5, fontSizePt * 0.25);
    const topDisplay = baselineDisplayY - fontSizePt * 0.92;
    const bottomDisplay = baselineDisplayY + fontSizePt * 0.25;

    let realFontName = item.fontName || 'Helvetica';
    try {
      const fontObj = page.commonObjs.get(item.fontName);
      if (fontObj && fontObj.name) realFontName = fontObj.name;
    } catch {
      // ignore if font metadata is private
    }

    const colorHex = colorMap[Math.min(textOpIndex, Math.max(0, colorMap.length - 1))] || '#0f172a';
    textOpIndex++;

    rawItems.push({
      str,
      left: xDisplay,
      right: xDisplay + widthPt,
      top: topDisplay,
      bottom: bottomDisplay,
      baselineY: baselineDisplayY,
      fontSizePt,
      fontName: realFontName,
      colorHex,
      rotationDeg,
    });
  }

  // Merge closely adjacent items on the same baseline with matching style
  rawItems.sort((a, b) => (Math.round(a.baselineY / 3) - Math.round(b.baselineY / 3)) || (a.left - b.left));
  const groups = [];
  for (const item of rawItems) {
    const prevGroup = groups[groups.length - 1];
    if (!prevGroup) {
      groups.push([item]);
      continue;
    }
    const prev = prevGroup[prevGroup.length - 1];
    const sameBaseline = Math.abs(item.baselineY - prev.baselineY) <= Math.max(prev.fontSizePt, item.fontSizePt) * 0.45;
    const gap = item.left - prev.right;
    const sameStyle = Math.abs(item.fontSizePt - prev.fontSizePt) <= 1.5 && item.colorHex === prev.colorHex;
    const maxGap = prev.fontSizePt * 0.72;

    if (sameBaseline && sameStyle && gap >= -prev.fontSizePt * 0.35 && gap <= maxGap) {
      prevGroup.push(item);
    } else {
      groups.push([item]);
    }
  }

  const segments = groups.map((group, idx) => {
    let joined = '';
    for (let i = 0; i < group.length; i++) {
      if (i > 0) {
        const gap = group[i].left - group[i - 1].right;
        if (gap > group[i - 1].fontSizePt * 0.22 && !joined.endsWith(' ') && !group[i].str.startsWith(' ')) {
          joined += ' ';
        }
      }
      joined += group[i].str;
    }
    const text = joined.trim();
    const minLeft = Math.min(...group.map((g) => g.left));
    const maxRight = Math.max(...group.map((g) => g.right));
    const minTop = Math.min(...group.map((g) => g.top));
    const maxBottom = Math.max(...group.map((g) => g.bottom));
    const avgBaseline = group.reduce((acc, g) => acc + g.baselineY, 0) / group.length;
    const lead = group[0];

    const normBounds = {
      left: clamp(minLeft / pageWidthPt, 0, 0.99),
      top: clamp(minTop / pageHeightPt, 0, 0.99),
      right: clamp(maxRight / pageWidthPt, 0.01, 1),
      bottom: clamp(maxBottom / pageHeightPt, 0.01, 1),
    };
    const baselineNormY = clamp(avgBaseline / pageHeightPt, normBounds.top, normBounds.bottom);

    const style = classifyStyle(text, lead.fontName, lead.fontSizePt, lead.colorHex, lead.rotationDeg);
    const background = analyzeBackgroundRegion(renderedCanvas, normBounds);

    return {
      id: `p${pageIndex}_s${idx}`,
      pageIndex,
      originalText: text,
      currentText: text,
      style,
      normBounds,
      baselineNormY,
      pdfBounds: {
        x: normBounds.left * pageWidthPt,
        yBottom: (1 - normBounds.bottom) * pageHeightPt,
        width: (normBounds.right - normBounds.left) * pageWidthPt,
        height: (normBounds.bottom - normBounds.top) * pageHeightPt,
        baselineY: (1 - baselineNormY) * pageHeightPt,
      },
      maxAvailableWidthNorm: Math.max(normBounds.right - normBounds.left, 0.96 - normBounds.left),
      background,
      isOcrDetected: false,
    };
  });

  return computeSafeNeighborBounds(segments);
}

export async function runOcrOnCanvas(canvas, pageIndex, pageWidthPt, pageHeightPt) {
  const worker = await Tesseract.createWorker('eng');
  try {
    const { data } = await worker.recognize(canvas);
    const lines = data?.lines || [];
    const bmpW = canvas.width || 1;
    const bmpH = canvas.height || 1;
    const segments = [];

    lines.forEach((line, idx) => {
      const text = (line.text || '').trim();
      const b = line.bbox;
      if (!text || !b || b.x1 - b.x0 < 4 || b.y1 - b.y0 < 4) return;

      const normBounds = {
        left: clamp(b.x0 / bmpW, 0, 0.99),
        top: clamp(b.y0 / bmpH, 0, 0.99),
        right: clamp(b.x1 / bmpW, 0.01, 1),
        bottom: clamp(b.y1 / bmpH, 0.01, 1),
      };
      const background = analyzeBackgroundRegion(canvas, normBounds);
      const colorHex = estimateInkColor(canvas, normBounds, background.fillColor);
      const boxHeightPt = (normBounds.bottom - normBounds.top) * pageHeightPt;
      const fontSizePt = clamp(boxHeightPt * 0.78, 7, 72);
      const baselineNormY = normBounds.bottom - (normBounds.bottom - normBounds.top) * 0.18;

      segments.push({
        id: `p${pageIndex}_ocr_${idx}`,
        pageIndex,
        originalText: text,
        currentText: text,
        style: classifyStyle(text, 'Scanned OCR', fontSizePt, colorHex, 0, boxHeightPt > 18),
        normBounds,
        baselineNormY,
        pdfBounds: {
          x: normBounds.left * pageWidthPt,
          yBottom: (1 - normBounds.bottom) * pageHeightPt,
          width: (normBounds.right - normBounds.left) * pageWidthPt,
          height: boxHeightPt,
          baselineY: (1 - baselineNormY) * pageHeightPt,
        },
        maxAvailableWidthNorm: Math.max(normBounds.right - normBounds.left, 0.96 - normBounds.left),
        background,
        isOcrDetected: true,
      });
    });

    return computeSafeNeighborBounds(segments);
  } finally {
    await worker.terminate();
  }
}

export function computeSafeNeighborBounds(segments) {
  return segments.map((curr) => {
    let nearestRight = 0.96;
    const currH = curr.normBounds.bottom - curr.normBounds.top;
    for (const other of segments) {
      if (other.id === curr.id) continue;
      const overlap =
        Math.min(curr.normBounds.bottom, other.normBounds.bottom) -
        Math.max(curr.normBounds.top, other.normBounds.top);
      if (overlap > currH * 0.25 && other.normBounds.left > curr.normBounds.right - 0.005) {
        nearestRight = Math.min(nearestRight, other.normBounds.left - 0.01);
      }
    }
    return {
      ...curr,
      maxAvailableWidthNorm: Math.max(
        curr.normBounds.right - curr.normBounds.left,
        nearestRight - curr.normBounds.left
      ),
    };
  });
}

async function extractTextOperatorColors(page) {
  const colors = [];
  try {
    const opList = await page.getOperatorList();
    const { fnArray, argsArray } = opList;
    let activeColor = '#0f172a';
    const stack = [];

    for (let i = 0; i < fnArray.length; i++) {
      const fn = fnArray[i];
      const args = argsArray[i];
      if (fn === pdfjsLib.OPS.save) {
        stack.push(activeColor);
      } else if (fn === pdfjsLib.OPS.restore) {
        if (stack.length) activeColor = stack.pop();
      } else if (fn === pdfjsLib.OPS.setFillRGBColor && args?.length >= 3) {
        const [r, g, b] = args;
        const scale = r <= 1 && g <= 1 && b <= 1 ? 255 : 1;
        activeColor = rgbToHex(r * scale, g * scale, b * scale);
      } else if (fn === pdfjsLib.OPS.setFillGray && args?.length >= 1) {
        const v = Math.round(args[0] * 255);
        activeColor = rgbToHex(v, v, v);
      } else if (fn === pdfjsLib.OPS.showText || fn === pdfjsLib.OPS.showSpacedText || fn === pdfjsLib.OPS.nextLineShowText) {
        colors.push(activeColor);
      }
    }
  } catch {
    // Fallback cleanly if operator list fails
  }
  return colors;
}

function classifyStyle(text, fontName, fontSizePt, colorHex, rotationDeg = 0, forceBold = false) {
  const hasBengali = /[\u0980-\u09FF]/.test(text);
  const lower = (fontName || '').toLowerCase();
  let family = 'sans';
  if (hasBengali) family = 'bengali';
  else if (lower.includes('courier') || lower.includes('mono') || lower.includes('consola')) family = 'mono';
  else if (lower.includes('times') || lower.includes('serif') || lower.includes('georgia')) family = 'serif';

  const isBold = forceBold || /bold|black|heavy|semibold|demi/.test(lower);
  const isItalic = /italic|oblique/.test(lower);

  return {
    fontFamily: family,
    originalFontName: (fontName || 'Sans').replace(/^[^+]*\+/, ''),
    fontSizePt,
    colorHex,
    isBold,
    isItalic,
    rotationDeg,
  };
}

function clamp(v, min, max) {
  return Math.max(min, Math.min(max, v));
}
