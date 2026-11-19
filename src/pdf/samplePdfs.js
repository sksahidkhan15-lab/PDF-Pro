import 'regenerator-runtime/runtime.js';
import fontkit from '@pdf-lib/fontkit';
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib';

export async function generateSamplePdfBytes(kind, fontBuffers) {
  if (kind === 'ticket') return buildTicketPdf(fontBuffers);
  if (kind === 'bilingual') return buildBilingualPdf(fontBuffers);
  if (kind === 'scanned') return buildScannedOcrPdf();
  throw new Error(`Unknown sample PDF kind: ${kind}`);
}

async function buildTicketPdf(fontBuffers) {
  const doc = await PDFDocument.create();
  doc.registerFontkit(fontkit);
  const sans = fontBuffers?.sans ? await doc.embedFont(fontBuffers.sans) : await doc.embedFont(StandardFonts.HelveticaBold);
  const mono = fontBuffers?.mono ? await doc.embedFont(fontBuffers.mono) : await doc.embedFont(StandardFonts.CourierBold);

  const page1 = doc.addPage([595, 842]);
  const h = page1.getHeight();
  const w = page1.getWidth();

  // Emerald header banner
  page1.drawRectangle({ x: 40, y: h - 140, width: w - 80, height: 88, color: rgb(15 / 255, 118 / 255, 110 / 255) });
  page1.drawText('EXPRESS RAIL E-TICKET', { x: 60, y: h - 92, size: 20, font: sans, color: rgb(1, 1, 1) });
  page1.drawText('Confirmed Electronic Reservation Slip', {
    x: 60,
    y: h - 116,
    size: 11,
    font: sans,
    color: rgb(209 / 255, 250 / 255, 229 / 255),
  });

  // Soft Mint card
  page1.drawRectangle({ x: 40, y: h - 270, width: w - 80, height: 110, color: rgb(236 / 255, 253 / 255, 245 / 255) });
  page1.drawText('BOOKING STATUS / COACH / BERTH', { x: 60, y: h - 190, size: 11, font: sans, color: rgb(0.28, 0.33, 0.41) });
  page1.drawText('CNF/B3/2/MIDDLE', { x: 60, y: h - 224, size: 22, font: mono, color: rgb(6 / 255, 78 / 255, 59 / 255) });
  page1.drawText('Tap the code above to replace with MIDDAA', { x: 60, y: h - 250, size: 11, font: sans, color: rgb(15 / 255, 118 / 255, 110 / 255) });

  page1.drawText('PASSENGER NAME', { x: 60, y: h - 320, size: 11, font: sans, color: rgb(0.4, 0.45, 0.55) });
  page1.drawText('Aarav Rahman', { x: 60, y: h - 344, size: 16, font: sans, color: rgb(0.06, 0.09, 0.16) });
  page1.drawText('PNR NUMBER', { x: 320, y: h - 320, size: 11, font: sans, color: rgb(0.4, 0.45, 0.55) });
  page1.drawText('4829105374', { x: 320, y: h - 344, size: 16, font: mono, color: rgb(0.06, 0.09, 0.16) });

  page1.drawRectangle({ x: 60, y: h - 430, width: 190, height: 44, color: rgb(209 / 255, 250 / 255, 229 / 255) });
  page1.drawText('TOTAL FARE: $48.50', { x: 76, y: h - 413, size: 14, font: sans, color: rgb(6 / 255, 78 / 255, 59 / 255) });

  const page2 = doc.addPage([595, 842]);
  page2.drawText('RETURN ITINERARY & NOTES', { x: 60, y: h - 80, size: 18, font: sans, color: rgb(15 / 255, 118 / 255, 110 / 255) });
  page2.drawText('Platform Gate: West Concourse B4', { x: 60, y: h - 125, size: 13, font: sans, color: rgb(0.06, 0.09, 0.16) });
  page2.drawText('Departure Time: 08:45 AM', { x: 60, y: h - 155, size: 13, font: sans, color: rgb(0.06, 0.09, 0.16) });

  return await doc.save();
}

async function buildBilingualPdf(fontBuffers) {
  const doc = await PDFDocument.create();
  doc.registerFontkit(fontkit);
  const sans = fontBuffers?.sans ? await doc.embedFont(fontBuffers.sans) : await doc.embedFont(StandardFonts.Helvetica);
  const serif = fontBuffers?.serif ? await doc.embedFont(fontBuffers.serif) : await doc.embedFont(StandardFonts.TimesRoman);
  const bengali = fontBuffers?.bengali ? await doc.embedFont(fontBuffers.bengali) : sans;

  const page = doc.addPage([595, 842]);
  const h = page.getHeight();
  const w = page.getWidth();

  page.drawRectangle({ x: 44, y: h - 120, width: w - 88, height: 68, color: rgb(6 / 255, 78 / 255, 59 / 255) });
  page.drawText('বাংলা ও ইংরেজি নমুনা নথি', { x: 64, y: h - 84, size: 18, font: bengali, color: rgb(1, 1, 1) });
  page.drawText('Bilingual Typography & Style Matching Test', { x: 64, y: h - 104, size: 11, font: sans, color: rgb(0.65, 0.95, 0.81) });

  page.drawText('শিরোনাম: ডিজিটাল সেবা কেন্দ্র', { x: 64, y: h - 170, size: 15, font: bengali, color: rgb(0.06, 0.09, 0.16) });
  page.drawText('ঠিকানা: ঢাকা, বাংলাদেশ', { x: 64, y: h - 200, size: 13, font: bengali, color: rgb(0.2, 0.25, 0.33) });

  page.drawText('Executive Summary (Serif Style)', { x: 64, y: h - 255, size: 16, font: serif, color: rgb(15 / 255, 118 / 255, 110 / 255) });
  page.drawText('All edits preserve font style, color, and page structure.', { x: 64, y: h - 285, size: 12, font: sans, color: rgb(0.12, 0.16, 0.23) });

  return await doc.save();
}

async function buildScannedOcrPdf() {
  const canvas = document.createElement('canvas');
  canvas.width = 1190;
  canvas.height = 1684;
  const ctx = canvas.getContext('2d');

  const grad = ctx.createLinearGradient(0, 0, canvas.width, canvas.height);
  grad.addColorStop(0, '#f6f9f7');
  grad.addColorStop(1, '#e6efe9');
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  ctx.fillStyle = '#dceee6';
  ctx.fillRect(90, 120, canvas.width - 180, 175);

  ctx.fillStyle = '#064e3b';
  ctx.font = 'bold 52px sans-serif';
  ctx.fillText('SCANNED OFFICE INVOICE', 130, 215);

  ctx.fillStyle = '#0f766e';
  ctx.font = '28px sans-serif';
  ctx.fillText('Image-Only PDF Page (Detected via OCR)', 130, 265);

  ctx.fillStyle = '#0f172a';
  ctx.font = '34px sans-serif';
  ctx.fillText('Invoice Number: INV-9042', 130, 420);
  ctx.fillText('Client: Horizon Studio Ltd', 130, 500);
  ctx.fillText('Amount Due: $1,250.00', 130, 580);
  ctx.fillText('Status: PENDING APPROVAL', 130, 660);

  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
  const pngBytes = new Uint8Array(await blob.arrayBuffer());

  const doc = await PDFDocument.create();
  const page = doc.addPage([595, 842]);
  const pngImg = await doc.embedPng(pngBytes);
  page.drawImage(pngImg, { x: 0, y: 0, width: page.getWidth(), height: page.getHeight() });
  return await doc.save();
}
