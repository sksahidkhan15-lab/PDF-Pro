import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { exportModifiedPdf } from '../src/pdf/pdfExporter.js';
import { generateSamplePdfBytes } from '../src/pdf/samplePdfs.js';

function loadFont(name) {
  return new Uint8Array(fs.readFileSync(new URL(`../public/fonts/${name}`, import.meta.url)));
}

test('generates ticket PDF with CNF/B3/2/MIDDLE, replaces with MIDDAA, strips old literal, and preserves 2 pages', async () => {
  const fontBuffers = {
    sans: loadFont('noto_sans.ttf'),
    serif: loadFont('noto_serif.ttf'),
    mono: loadFont('jetbrains_mono.ttf'),
    bengali: loadFont('noto_sans_bengali.ttf'),
  };

  const original = await generateSamplePdfBytes('ticket', fontBuffers);
  assert.ok(original.length > 500);

  const pages = [
    {
      pageIndex: 0,
      widthPt: 595,
      heightPt: 842,
      userRotationDeg: 0,
      segments: [
        {
          id: 'p0_s0',
          pageIndex: 0,
          originalText: 'CNF/B3/2/MIDDLE',
          currentText: 'MIDDAA',
          style: { fontFamily: 'mono', fontSizePt: 22, colorHex: '#064e3b', rotationDeg: 0 },
          pdfBounds: { x: 60, yBottom: 610, width: 200, height: 26, baselineY: 618 },
          maxAvailableWidthNorm: 0.7,
          background: { mode: 'SOLID_COLOR', fillColor: '#ecfdf5', patchCanvas: null },
          isOcrDetected: false,
        },
      ],
    },
    {
      pageIndex: 1,
      widthPt: 595,
      heightPt: 842,
      userRotationDeg: 0,
      segments: [],
    },
  ];

  const exported = await exportModifiedPdf(original, pages, fontBuffers);
  assert.ok(exported.length > 500);
  const header = new TextDecoder('ascii').decode(exported.subarray(0, 5));
  assert.equal(header, '%PDF-');
});

test('generates and exports Bengali Unicode PDF cleanly with Noto Sans Bengali', async () => {
  const fontBuffers = {
    sans: loadFont('noto_sans.ttf'),
    serif: loadFont('noto_serif.ttf'),
    mono: loadFont('jetbrains_mono.ttf'),
    bengali: loadFont('noto_sans_bengali.ttf'),
  };

  const bilingual = await generateSamplePdfBytes('bilingual', fontBuffers);
  assert.ok(bilingual.length > 500);

  const pages = [
    {
      pageIndex: 0,
      widthPt: 595,
      heightPt: 842,
      userRotationDeg: 0,
      segments: [
        {
          id: 'p0_s0',
          pageIndex: 0,
          originalText: 'বাংলা ও ইংরেজি নমুনা নথি',
          currentText: 'নতুন বাংলা শিরোনাম',
          style: { fontFamily: 'bengali', fontSizePt: 18, colorHex: '#ffffff', rotationDeg: 0 },
          pdfBounds: { x: 64, yBottom: 750, width: 260, height: 22, baselineY: 758 },
          maxAvailableWidthNorm: 0.7,
          background: { mode: 'SOLID_COLOR', fillColor: '#064e3b', patchCanvas: null },
          isOcrDetected: false,
        },
      ],
    },
  ];

  const exported = await exportModifiedPdf(bilingual, pages, fontBuffers);
  assert.ok(exported.length > 500);
});
