import 'regenerator-runtime/runtime.js';
import fontkit from '@pdf-lib/fontkit';
import { PDFArray, PDFDocument, PDFName, PDFRawStream, StandardFonts, decodePDFRawStream, degrees, rgb } from 'pdf-lib';
import { hexToRgb } from './backgroundRestorer.js';

/**
 * Exports a valid modified PDF using pdf-lib:
 * - Preserves untouched pages, images, dimensions, and unaffected text.
 * - Strips replaced text from decoded content streams when possible so old text is not retained.
 * - Restores backgrounds (white, solid color, or edge-interpolated patch PNG).
 * - Embeds Noto Sans / Noto Serif / JetBrains Mono / Noto Sans Bengali Unicode fonts.
 */
export async function exportModifiedPdf(originalBytes, pages, fontBuffers) {
  const pdfDoc = await PDFDocument.load(originalBytes);
  pdfDoc.registerFontkit(fontkit);

  const fontCache = new Map();
  const loadEmbeddedFont = async (familyKey) => {
    if (fontCache.has(familyKey)) return fontCache.get(familyKey);
    const buf = fontBuffers?.[familyKey];
    let embedded;
    if (buf) {
      embedded = await pdfDoc.embedFont(buf, { subset: true });
    } else {
      const std =
        familyKey === 'serif'
          ? StandardFonts.TimesRoman
          : familyKey === 'mono'
            ? StandardFonts.Courier
            : StandardFonts.Helvetica;
      embedded = await pdfDoc.embedFont(std);
    }
    fontCache.set(familyKey, embedded);
    return embedded;
  };

  const pdfPages = pdfDoc.getPages();

  for (const pageModel of pages) {
    if (pageModel.pageIndex >= pdfPages.length) continue;
    const pdfPage = pdfPages[pageModel.pageIndex];

    if (pageModel.userRotationDeg) {
      const currentRot = pdfPage.getRotation().angle || 0;
      pdfPage.setRotation(degrees((currentRot + pageModel.userRotationDeg) % 360));
    }

    const edits = pageModel.segments.filter((s) => s.currentText !== s.originalText);
    if (edits.length === 0) continue;

    // 1. Attempt content-stream redaction for replaced vector strings
    redactStringsInPageContentStream(pdfDoc, pdfPage, edits);

    // 2. Restore backgrounds & draw matched replacement text
    for (const seg of edits) {
      const b = seg.pdfBounds;
      const padX = Math.max(1, Math.min(4, seg.style.fontSizePt * 0.14));
      const padY = Math.max(1, Math.min(4, seg.style.fontSizePt * 0.12));

      if (seg.background.mode === 'COMPLEX_INTERPOLATED' && seg.background.patchCanvas) {
        const pngBytes = await canvasToPngBytes(seg.background.patchCanvas);
        const pngImg = await pdfDoc.embedPng(pngBytes);
        pdfPage.drawImage(pngImg, {
          x: b.x - padX,
          y: b.yBottom - padY,
          width: b.width + padX * 2,
          height: b.height + padY * 2,
        });
      } else {
        const bgRgb = hexToRgb(seg.background.mode === 'SOLID_WHITE' ? '#ffffff' : seg.background.fillColor);
        pdfPage.drawRectangle({
          x: b.x - padX,
          y: b.yBottom - padY,
          width: b.width + padX * 2,
          height: b.height + padY * 2,
          color: rgb(bgRgb.r / 255, bgRgb.g / 255, bgRgb.b / 255),
        });
      }

      if (!seg.currentText) continue;

      const needsBengali = /[\u0980-\u09FF]/.test(seg.currentText) || seg.style.fontFamily === 'bengali';
      const targetFamily = needsBengali ? 'bengali' : seg.style.fontFamily || 'sans';
      const font = await loadEmbeddedFont(targetFamily);

      const maxAvailWidthPt = Math.max(b.width, seg.maxAvailableWidthNorm * pdfPage.getWidth());
      let fontSize = seg.style.fontSizePt;
      try {
        const measured = font.widthOfTextAtSize(seg.currentText, fontSize);
        if (measured > maxAvailWidthPt && measured > 0) {
          fontSize = Math.max(Math.min(6, fontSize), fontSize * (maxAvailWidthPt / measured));
        }
      } catch {
        // ignore width check if glyph metrics unavailable
      }

      const fg = hexToRgb(seg.style.colorHex);
      pdfPage.drawText(seg.currentText, {
        x: b.x,
        y: b.baselineY,
        size: fontSize,
        font,
        color: rgb(fg.r / 255, fg.g / 255, fg.b / 255),
        rotate: seg.style.rotationDeg ? degrees(-seg.style.rotationDeg) : undefined,
      });
    }
  }

  return await pdfDoc.save();
}

function redactStringsInPageContentStream(pdfDoc, pdfPage, edits) {
  const vectorEdits = edits.filter((e) => !e.isOcrDetected && e.originalText.trim().length > 0);
  if (vectorEdits.length === 0) return;

  try {
    const contents = pdfPage.node.Contents();
    if (!contents) return;

    const streamRefs = [];
    if (contents instanceof PDFArray) {
      for (let i = 0; i < contents.size(); i++) {
        streamRefs.push(contents.get(i));
      }
    } else {
      streamRefs.push(pdfPage.node.get(PDFName.of('Contents')));
    }

    const decoder = new TextDecoder('latin1');
    const encoder = new TextEncoder();

    for (const ref of streamRefs) {
      const stream = pdfDoc.context.lookup(ref);
      if (!(stream instanceof PDFRawStream)) continue;

      const decodedBytes = decodePDFRawStream(stream).decode();
      let contentStr = decoder.decode(decodedBytes);
      let modified = false;

      for (const edit of vectorEdits) {
        const escaped = escapePdfLiteral(edit.originalText.trim());
        if (escaped.length >= 2 && contentStr.includes(`(${escaped})`)) {
          contentStr = contentStr.split(`(${escaped})`).join('()');
          modified = true;
        }
      }

      if (modified) {
        const newStream = pdfDoc.context.flateStream(encoder.encode(contentStr));
        pdfDoc.context.assign(ref, newStream);
      }
    }
  } catch {
    // Non-fatal fallback: visual restoration still covers original region cleanly
  }
}

function escapePdfLiteral(str) {
  return str.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
}

async function canvasToPngBytes(canvas) {
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
  return new Uint8Array(await blob.arrayBuffer());
}
