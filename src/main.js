import { analyzeBackgroundRegion } from './pdf/backgroundRestorer.js';
import {
  extractVectorSegments,
  loadPdfDocument,
  renderPageToCanvas,
  runOcrOnCanvas,
} from './pdf/pdfAnalyzer.js';
import { exportModifiedPdf } from './pdf/pdfExporter.js';
import {
  deleteProjectFromDb,
  getProjectFromDb,
  listProjectsFromDb,
  saveProjectToDb,
} from './pdf/projectStore.js';
import { generateSamplePdfBytes } from './pdf/samplePdfs.js';

const BASE = import.meta.env.BASE_URL || './';

const state = {
  projectId: null,
  fileName: '',
  originalBytes: null,
  pdfDoc: null,
  pages: [],
  pageBitmaps: new Map(), // pageIndex -> offscreen rendered base canvas
  pageDomCanvases: new Map(), // pageIndex -> visible display canvas
  currentPageIndex: 0,
  zoomScale: 1.0,
  selectedSegmentId: null,
  draftText: '',
  undoStack: [],
  redoStack: [],
  fontBuffers: null,
  lastExportedBytes: null,
  lastExportedName: '',
};

const els = {
  btnHome: document.getElementById('btn-home'),
  docTitle: document.getElementById('doc-title'),
  docSubtitle: document.getElementById('doc-subtitle'),
  fileInput: document.getElementById('pdf-file-input'),
  editorControls: document.getElementById('editor-controls'),
  btnUndo: document.getElementById('btn-undo'),
  btnRedo: document.getElementById('btn-redo'),
  btnZoomOut: document.getElementById('btn-zoom-out'),
  btnZoomFit: document.getElementById('btn-zoom-fit'),
  btnZoomIn: document.getElementById('btn-zoom-in'),
  btnRotate: document.getElementById('btn-rotate'),
  btnRestoreDoc: document.getElementById('btn-restore-doc'),
  btnDownload: document.getElementById('btn-download'),
  welcomeView: document.getElementById('welcome-view'),
  dropZone: document.getElementById('drop-zone'),
  recentSection: document.getElementById('recent-section'),
  recentList: document.getElementById('recent-list'),
  editorView: document.getElementById('editor-view'),
  pagesContainer: document.getElementById('pages-container'),
  pageNavPill: document.getElementById('page-nav-pill'),
  btnPrevPage: document.getElementById('btn-prev-page'),
  btnNextPage: document.getElementById('btn-next-page'),
  pageNavLabel: document.getElementById('page-nav-label'),
  bottomPanel: document.getElementById('bottom-edit-panel'),
  matchedColorDot: document.getElementById('matched-color-dot'),
  matchedStyleLabel: document.getElementById('matched-style-label'),
  bgStatusBadge: document.getElementById('bg-status-badge'),
  btnRestoreSegment: document.getElementById('btn-restore-segment'),
  btnClosePanel: document.getElementById('btn-close-panel'),
  editInput: document.getElementById('edit-text-input'),
  btnConfirmEdit: document.getElementById('btn-confirm-edit'),
  loadingOverlay: document.getElementById('loading-overlay'),
  loadingText: document.getElementById('loading-text'),
  exportModal: document.getElementById('export-modal'),
  exportSummary: document.getElementById('export-summary'),
  btnCloseExportModal: document.getElementById('btn-close-export-modal'),
  btnTriggerDownload: document.getElementById('btn-trigger-download'),
  btnReopenExported: document.getElementById('btn-reopen-exported'),
  toastBanner: document.getElementById('toast-banner'),
};

async function initFonts() {
  const fontSpecs = [
    ['Plus Jakarta Sans', 'plus_jakarta_sans.ttf'],
    ['Inter', 'inter.ttf'],
    ['JetBrains Mono', 'jetbrains_mono.ttf'],
    ['Noto Sans', 'noto_sans.ttf'],
    ['Noto Serif', 'noto_serif.ttf'],
    ['Noto Sans Bengali', 'noto_sans_bengali.ttf'],
  ];

  await Promise.all(
    fontSpecs.map(async ([family, file]) => {
      try {
        const ff = new FontFace(family, `url(${BASE}fonts/${file})`);
        await ff.load();
        document.fonts.add(ff);
      } catch {
        // Fallback to system fonts if unavailable
      }
    })
  );

  const loadBuf = async (file) => {
    try {
      const res = await fetch(`${BASE}fonts/${file}`);
      return res.ok ? new Uint8Array(await res.arrayBuffer()) : null;
    } catch {
      return null;
    }
  };

  const [sans, serif, mono, bengali] = await Promise.all([
    loadBuf('noto_sans.ttf'),
    loadBuf('noto_serif.ttf'),
    loadBuf('jetbrains_mono.ttf'),
    loadBuf('noto_sans_bengali.ttf'),
  ]);
  state.fontBuffers = { sans, serif, mono, bengali };
}

function showLoading(message) {
  els.loadingText.textContent = message;
  els.loadingOverlay.classList.remove('hidden');
}

function hideLoading() {
  els.loadingOverlay.classList.add('hidden');
}

let toastTimer = null;
function showToast(msg) {
  els.toastBanner.textContent = msg;
  els.toastBanner.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toastBanner.classList.add('hidden'), 3200);
}

async function openPdfBytes(bytes, fileName, existingProjectId = null, restoredState = null) {
  showLoading('Analyzing PDF structure & text...');
  try {
    const pdfDoc = await loadPdfDocument(bytes);
    const numPages = pdfDoc.numPages;
    if (numPages <= 0) throw new Error('PDF contains no pages.');

    state.projectId = existingProjectId || `proj_${Date.now().toString(36)}`;
    state.fileName = fileName;
    state.originalBytes = bytes;
    state.pdfDoc = pdfDoc;
    state.pages = [];
    state.pageBitmaps.clear();
    state.pageDomCanvases.clear();
    state.currentPageIndex = 0;
    state.zoomScale = 1.0;
    state.selectedSegmentId = null;
    state.draftText = '';
    state.undoStack = [];
    state.redoStack = [];

    for (let i = 0; i < numPages; i++) {
      const { canvas, page } = await renderPageToCanvas(pdfDoc, i, 1.6);
      const vp = page.getViewport({ scale: 1.0 });
      state.pageBitmaps.set(i, canvas);

      let segments = await extractVectorSegments(page, i, canvas);
      let hasVectorText = segments.length > 0;
      let ocrStatus = hasVectorText ? 'NOT_NEEDED' : 'SCANNING';

      if (restoredState?.editsBySegmentId) {
        segments = segments.map((s) => ({
          ...s,
          currentText: restoredState.editsBySegmentId[s.id] ?? s.currentText,
        }));
      }

      state.pages.push({
        pageIndex: i,
        widthPt: vp.width,
        heightPt: vp.height,
        userRotationDeg: restoredState?.rotationsByPage?.[i] || 0,
        hasVectorText,
        ocrStatus,
        segments,
      });
    }

    window.location.hash = '#/editor';
    renderWorkspace();
    await persistProject();

    // Run Tesseract OCR asynchronously on any image-only scanned pages
    for (const pageModel of state.pages) {
      if (!pageModel.hasVectorText && pageModel.ocrStatus === 'SCANNING') {
        const bmp = state.pageBitmaps.get(pageModel.pageIndex);
        if (!bmp) continue;
        try {
          const ocrSegments = await runOcrOnCanvas(bmp, pageModel.pageIndex, pageModel.widthPt, pageModel.heightPt);
          if (restoredState?.editsBySegmentId) {
            ocrSegments.forEach((s) => {
              if (restoredState.editsBySegmentId[s.id] !== undefined) {
                s.currentText = restoredState.editsBySegmentId[s.id];
              }
            });
          }
          pageModel.segments = ocrSegments;
          pageModel.ocrStatus = ocrSegments.length ? 'COMPLETED' : 'NO_TEXT_FOUND';
          updatePageOcrBadge(pageModel.pageIndex);
          redrawSinglePageCanvas(pageModel.pageIndex);
        } catch {
          pageModel.ocrStatus = 'NO_TEXT_FOUND';
          updatePageOcrBadge(pageModel.pageIndex);
        }
      }
    }
  } catch (err) {
    showToast(err?.message || 'Unable to open this PDF file.');
  } finally {
    hideLoading();
  }
}

function renderWorkspace() {
  const hasDoc = Boolean(state.pdfDoc && state.pages.length);
  els.welcomeView.classList.toggle('hidden', hasDoc);
  els.editorView.classList.toggle('hidden', !hasDoc);
  els.editorControls.classList.toggle('hidden', !hasDoc);
  els.btnHome.classList.toggle('hidden', !hasDoc);
  els.pageNavPill.classList.toggle('hidden', !hasDoc || state.selectedSegmentId !== null);

  if (!hasDoc) {
    els.docTitle.textContent = 'PDF Studio Pro';
    els.docSubtitle.textContent = 'Tap any text on your PDF to replace it instantly';
    els.bottomPanel.classList.add('hidden');
    refreshRecentList();
    return;
  }

  const editCount = countEditedSegments();
  els.docTitle.textContent = state.fileName;
  els.docSubtitle.textContent = editCount > 0 ? `${editCount} text replacement(s) active` : 'Tap any text to edit';
  els.btnUndo.disabled = state.undoStack.length === 0;
  els.btnRedo.disabled = state.redoStack.length === 0;
  els.btnZoomFit.textContent = `${Math.round(state.zoomScale * 100)}%`;
  els.pageNavLabel.textContent = `Page ${state.currentPageIndex + 1} / ${state.pages.length}`;
  els.btnPrevPage.disabled = state.currentPageIndex <= 0;
  els.btnNextPage.disabled = state.currentPageIndex >= state.pages.length - 1;

  buildPageDomElements();
  updateBottomEditPanel();
}

function buildPageDomElements() {
  els.pagesContainer.innerHTML = '';
  state.pageDomCanvases.clear();

  const maxDisplayW = Math.min(760, Math.max(280, window.innerWidth - 36));
  const scaledW = Math.round(maxDisplayW * state.zoomScale);

  for (const page of state.pages) {
    const rot = ((page.userRotationDeg % 360) + 360) % 360;
    const isQuarter = rot === 90 || rot === 270;
    const aspect = isQuarter ? page.widthPt / page.heightPt : page.heightPt / page.widthPt;
    const displayW = scaledW;
    const displayH = Math.round(displayW * aspect);

    const shell = document.createElement('div');
    shell.className = 'pdf-page-shell';
    shell.style.width = `${displayW}px`;
    shell.style.height = `${displayH}px`;
    shell.dataset.pageIndex = String(page.pageIndex);

    const canvas = document.createElement('canvas');
    canvas.className = 'pdf-page-canvas';
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(displayW * dpr);
    canvas.height = Math.round(displayH * dpr);
    canvas.style.width = `${displayW}px`;
    canvas.style.height = `${displayH}px`;

    canvas.addEventListener('click', (ev) => {
      const rect = canvas.getBoundingClientRect();
      const dx = (ev.clientX - rect.left) / rect.width;
      const dy = (ev.clientY - rect.top) / rect.height;
      handlePageTap(page.pageIndex, dx, dy);
    });

    shell.appendChild(canvas);
    if (!page.hasVectorText) {
      const pill = document.createElement('span');
      pill.className = 'ocr-pill';
      pill.id = `ocr-pill-${page.pageIndex}`;
      pill.textContent =
        page.ocrStatus === 'SCANNING'
          ? 'Scanning text (OCR)...'
          : page.ocrStatus === 'COMPLETED'
            ? `OCR Ready · ${page.segments.length} lines`
            : 'No selectable text detected';
      shell.appendChild(pill);
    }

    els.pagesContainer.appendChild(shell);
    state.pageDomCanvases.set(page.pageIndex, canvas);
    redrawSinglePageCanvas(page.pageIndex);
  }
}

function updatePageOcrBadge(pageIndex) {
  const pill = document.getElementById(`ocr-pill-${pageIndex}`);
  const page = state.pages[pageIndex];
  if (!pill || !page) return;
  pill.textContent =
    page.ocrStatus === 'COMPLETED'
      ? `OCR Ready · ${page.segments.length} lines`
      : 'No selectable text detected';
}

function redrawSinglePageCanvas(pageIndex) {
  const page = state.pages[pageIndex];
  const displayCanvas = state.pageDomCanvases.get(pageIndex);
  const baseBmp = state.pageBitmaps.get(pageIndex);
  if (!page || !displayCanvas || !baseBmp) return;

  const ctx = displayCanvas.getContext('2d');
  const w = displayCanvas.width;
  const h = displayCanvas.height;
  const rot = ((page.userRotationDeg % 360) + 360) % 360;

  ctx.save();
  ctx.clearRect(0, 0, w, h);

  let logicalW = w;
  let logicalH = h;
  if (rot === 90) {
    ctx.translate(w, 0);
    ctx.rotate(Math.PI / 2);
    logicalW = h;
    logicalH = w;
  } else if (rot === 180) {
    ctx.translate(w, h);
    ctx.rotate(Math.PI);
  } else if (rot === 270) {
    ctx.translate(0, h);
    ctx.rotate(-Math.PI / 2);
    logicalW = h;
    logicalH = w;
  }

  ctx.drawImage(baseBmp, 0, 0, logicalW, logicalH);

  for (const seg of page.segments) {
    const isSelected = seg.id === state.selectedSegmentId;
    const textToDraw = isSelected ? state.draftText : seg.currentText;
    const isEdited = seg.currentText !== seg.originalText;

    if (isSelected || isEdited) {
      const b = seg.normBounds;
      const padX = Math.max(2, Math.min(8, (b.bottom - b.top) * logicalH * 0.14));
      const padY = Math.max(2, Math.min(6, (b.bottom - b.top) * logicalH * 0.1));
      const rx = Math.max(0, b.left * logicalW - padX);
      const ry = Math.max(0, b.top * logicalH - padY);
      const rw = Math.min(logicalW - rx, (b.right - b.left) * logicalW + padX * 2);
      const rh = Math.min(logicalH - ry, (b.bottom - b.top) * logicalH + padY * 2);

      if (seg.background.mode === 'COMPLEX_INTERPOLATED' && seg.background.patchCanvas) {
        ctx.drawImage(seg.background.patchCanvas, rx, ry, rw, rh);
      } else {
        ctx.fillStyle = seg.background.mode === 'SOLID_WHITE' ? '#ffffff' : seg.background.fillColor;
        ctx.fillRect(rx, ry, rw, rh);
      }

      if (textToDraw) {
        drawMatchedCanvasText(ctx, seg, textToDraw, logicalW, logicalH, page.heightPt);
      }
    }

    if (isSelected) {
      const b = seg.normBounds;
      const pad = 4;
      const rx = Math.max(1, b.left * logicalW - pad);
      const ry = Math.max(1, b.top * logicalH - pad);
      const rw = Math.min(logicalW - rx - 1, (b.right - b.left) * logicalW + pad * 2);
      const rh = Math.min(logicalH - ry - 1, (b.bottom - b.top) * logicalH + pad * 2);

      ctx.fillStyle = 'rgba(16, 185, 129, 0.10)';
      ctx.strokeStyle = '#10b981';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.roundRect(rx, ry, rw, rh, 6);
      ctx.fill();
      ctx.stroke();
    }
  }

  ctx.restore();
}

function drawMatchedCanvasText(ctx, seg, text, logicalW, logicalH, pageHeightPt) {
  const st = seg.style;
  const pxPerPt = logicalH / Math.max(1, pageHeightPt);
  let fontSizePx = Math.max(8, st.fontSizePt * pxPerPt);

  const hasBengali = /[\u0980-\u09FF]/.test(text) || st.fontFamily === 'bengali';
  const familyName = hasBengali
    ? "'Noto Sans Bengali', 'Noto Sans', sans-serif"
    : st.fontFamily === 'serif'
      ? "'Noto Serif', Georgia, serif"
      : st.fontFamily === 'mono'
        ? "'JetBrains Mono', monospace"
        : "'Noto Sans', system-ui, sans-serif";

  const weight = st.isBold ? '700' : '400';
  const slant = st.isItalic ? 'italic' : 'normal';
  const setFont = (sz) => {
    ctx.font = `${slant} ${weight} ${sz}px ${familyName}`;
  };

  setFont(fontSizePx);
  const maxAvailPx = Math.max((seg.normBounds.right - seg.normBounds.left) * logicalW, seg.maxAvailableWidthNorm * logicalW);
  const measured = ctx.measureText(text).width;
  if (measured > maxAvailPx && measured > 0) {
    fontSizePx = Math.max(Math.min(8, fontSizePx), fontSizePx * (maxAvailPx / measured));
    setFont(fontSizePx);
  }

  const x = seg.normBounds.left * logicalW;
  const baselineY = seg.baselineNormY * logicalH;

  ctx.save();
  ctx.fillStyle = st.colorHex;
  ctx.textBaseline = 'alphabetic';
  if (Math.abs(st.rotationDeg) > 1) {
    ctx.translate(x, baselineY);
    ctx.rotate((st.rotationDeg * Math.PI) / 180);
    ctx.fillText(text, 0, 0);
  } else {
    ctx.fillText(text, x, baselineY);
  }
  ctx.restore();
}

function displayToPdfNorm(dx, dy, rotDeg) {
  const rot = ((rotDeg % 360) + 360) % 360;
  if (rot === 90) return [dy, 1 - dx];
  if (rot === 180) return [1 - dx, 1 - dy];
  if (rot === 270) return [1 - dy, dx];
  return [dx, dy];
}

function handlePageTap(pageIndex, dx, dy) {
  const page = state.pages[pageIndex];
  if (!page) return;

  const [px, py] = displayToPdfNorm(dx, dy, page.userRotationDeg);
  const contains = (seg, padX, padY) =>
    px >= seg.normBounds.left - padX &&
    px <= seg.normBounds.right + padX &&
    py >= seg.normBounds.top - padY &&
    py <= seg.normBounds.bottom + padY;

  let hit = page.segments.find((s) => contains(s, 0.004, 0.004));
  if (!hit) {
    const near = page.segments.filter((s) => contains(s, 0.022, 0.018));
    near.sort((a, b) => {
      const da = Math.abs((a.normBounds.left + a.normBounds.right) / 2 - px) + Math.abs((a.normBounds.top + a.normBounds.bottom) / 2 - py);
      const db = Math.abs((b.normBounds.left + b.normBounds.right) / 2 - px) + Math.abs((b.normBounds.top + b.normBounds.bottom) / 2 - py);
      return da - db;
    });
    hit = near[0] || null;
  }

  const prevSelectedPage = findSelectedSegment()?.pageIndex;
  if (!hit) {
    state.selectedSegmentId = null;
    state.draftText = '';
    if (prevSelectedPage !== undefined) redrawSinglePageCanvas(prevSelectedPage);
    updateBottomEditPanel();
    return;
  }

  state.currentPageIndex = pageIndex;
  state.selectedSegmentId = hit.id;
  state.draftText = hit.currentText;
  if (prevSelectedPage !== undefined && prevSelectedPage !== pageIndex) {
    redrawSinglePageCanvas(prevSelectedPage);
  }
  redrawSinglePageCanvas(pageIndex);
  updateBottomEditPanel();
}

function findSelectedSegment() {
  if (!state.selectedSegmentId) return null;
  for (const p of state.pages) {
    const found = p.segments.find((s) => s.id === state.selectedSegmentId);
    if (found) return found;
  }
  return null;
}

function updateBottomEditPanel() {
  const seg = findSelectedSegment();
  els.pageNavPill.classList.toggle('hidden', !state.pdfDoc || Boolean(seg));
  if (!seg) {
    els.bottomPanel.classList.add('hidden');
    return;
  }

  els.bottomPanel.classList.remove('hidden');
  els.matchedColorDot.style.backgroundColor = seg.style.colorHex;
  const weight = seg.style.isBold ? 'Bold' : 'Regular';
  els.matchedStyleLabel.textContent = `${seg.style.fontFamily.toUpperCase()} ${weight} · ${Math.round(seg.style.fontSizePt)}pt`;

  els.bgStatusBadge.textContent =
    seg.isOcrDetected && seg.background.mode === 'COMPLEX_INTERPOLATED'
      ? 'OCR · Edge Interpolated'
      : seg.isOcrDetected
        ? 'OCR · BG Reconstructed'
        : seg.background.mode === 'COMPLEX_INTERPOLATED'
          ? 'Complex BG Interpolated'
          : seg.background.mode === 'SOLID_COLOR'
            ? 'Color BG Matched'
            : 'Clean BG';

  const isModified = seg.currentText !== seg.originalText || state.draftText !== seg.originalText;
  els.btnRestoreSegment.classList.toggle('hidden', !isModified);

  if (els.editInput.value !== state.draftText) {
    els.editInput.value = state.draftText;
  }
  els.editInput.focus();
}

async function confirmCurrentEdit() {
  const seg = findSelectedSegment();
  if (!seg) return;

  const newText = state.draftText;
  if (newText !== seg.currentText) {
    state.undoStack.push({
      segmentId: seg.id,
      pageIndex: seg.pageIndex,
      previousText: seg.currentText,
      newText,
    });
    state.redoStack = [];
    seg.currentText = newText;
    await persistProject();
  }

  const pageIdx = seg.pageIndex;
  state.selectedSegmentId = null;
  state.draftText = '';
  redrawSinglePageCanvas(pageIdx);
  renderHeaderCountersOnly();
  updateBottomEditPanel();
}

function renderHeaderCountersOnly() {
  const editCount = countEditedSegments();
  els.docSubtitle.textContent = editCount > 0 ? `${editCount} text replacement(s) active` : 'Tap any text to edit';
  els.btnUndo.disabled = state.undoStack.length === 0;
  els.btnRedo.disabled = state.redoStack.length === 0;
}

function countEditedSegments() {
  return state.pages.reduce((acc, p) => acc + p.segments.filter((s) => s.currentText !== s.originalText).length, 0);
}

async function persistProject() {
  if (!state.projectId || !state.originalBytes) return;
  const editsBySegmentId = {};
  const rotationsByPage = {};
  for (const p of state.pages) {
    if (p.userRotationDeg) rotationsByPage[p.pageIndex] = p.userRotationDeg;
    for (const s of p.segments) {
      if (s.currentText !== s.originalText) {
        editsBySegmentId[s.id] = s.currentText;
      }
    }
  }
  await saveProjectToDb({
    id: state.projectId,
    displayName: state.fileName,
    originalBytes: state.originalBytes,
    pageCount: state.pages.length,
    editCount: Object.keys(editsBySegmentId).length,
    editsBySegmentId,
    rotationsByPage,
    lastModified: Date.now(),
  });
}

async function refreshRecentList() {
  const list = await listProjectsFromDb();
  els.recentSection.classList.toggle('hidden', list.length === 0);
  els.recentList.innerHTML = '';

  for (const proj of list) {
    const row = document.createElement('div');
    row.className = 'recent-row';

    const info = document.createElement('div');
    info.className = 'recent-info';
    info.innerHTML = `<strong>${escapeHtml(proj.displayName)}</strong><span>${proj.pageCount} page(s) · ${proj.editCount} edit(s)</span>`;
    info.addEventListener('click', async () => {
      const full = await getProjectFromDb(proj.id);
      if (full?.originalBytes) {
        openPdfBytes(new Uint8Array(full.originalBytes), full.displayName, full.id, full);
      }
    });

    const delBtn = document.createElement('button');
    delBtn.className = 'icon-btn compact';
    delBtn.type = 'button';
    delBtn.title = 'Delete project';
    delBtn.textContent = '✕';
    delBtn.addEventListener('click', async () => {
      await deleteProjectFromDb(proj.id);
      refreshRecentList();
    });

    row.appendChild(info);
    row.appendChild(delBtn);
    els.recentList.appendChild(row);
  }
}

function escapeHtml(str) {
  return String(str).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function exportAndVerify() {
  if (!state.originalBytes) return;
  showLoading('Exporting & verifying PDF...');
  try {
    const exportedBytes = await exportModifiedPdf(state.originalBytes, state.pages, state.fontBuffers);
    const outName = `${state.fileName.replace(/\.pdf$/i, '')}_edited.pdf`;

    // Programmatically reopen exported PDF via PDF.js to verify page count and text replacement
    const reopened = await loadPdfDocument(exportedBytes);
    let verifiedCount = 0;
    const allEdits = state.pages.flatMap((p) => p.segments.filter((s) => s.currentText !== s.originalText));
    let oldTextRemoved = true;

    for (const p of state.pages) {
      const pageEdits = p.segments.filter((s) => s.currentText !== s.originalText);
      if (pageEdits.length === 0) continue;
      const pdfPage = await reopened.getPage(p.pageIndex + 1);
      const tc = await pdfPage.getTextContent();
      const extracted = tc.items.map((it) => it.str).join(' ');

      for (const ed of pageEdits) {
        if (!ed.currentText.trim() || extracted.includes(ed.currentText.trim())) {
          verifiedCount++;
        }
        const oldClean = ed.originalText.trim();
        if (!ed.isOcrDetected && oldClean.length >= 3 && !ed.currentText.includes(oldClean)) {
          const untouchedMatch = p.segments.some((other) => other.currentText === other.originalText && other.originalText.includes(oldClean));
          if (!untouchedMatch && extracted.includes(oldClean)) {
            oldTextRemoved = false;
          }
        }
      }
    }

    state.lastExportedBytes = exportedBytes;
    state.lastExportedName = outName;
    els.exportSummary.textContent = `Reopened & verified ${reopened.numPages}/${state.pages.length} pages. ${verifiedCount}/${allEdits.length} replacement(s) confirmed in PDF text layer. ${
      oldTextRemoved ? 'Replaced original text stripped cleanly.' : 'Visual background restored over original region.'
    }`;
    els.exportModal.classList.remove('hidden');
  } catch (err) {
    showToast(err?.message || 'Export failed.');
  } finally {
    hideLoading();
  }
}

function triggerFileDownload(bytes, fileName) {
  const blob = new Blob([bytes], { type: 'application/pdf' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
}

function wireEvents() {
  els.fileInput.addEventListener('change', async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const bytes = new Uint8Array(await file.arrayBuffer());
    openPdfBytes(bytes, file.name);
    els.fileInput.value = '';
  });

  ['dragenter', 'dragover'].forEach((evt) => {
    els.dropZone.addEventListener(evt, (e) => {
      e.preventDefault();
      els.dropZone.classList.add('drag-over');
    });
  });
  ['dragleave', 'drop'].forEach((evt) => {
    els.dropZone.addEventListener(evt, (e) => {
      e.preventDefault();
      els.dropZone.classList.remove('drag-over');
    });
  });
  els.dropZone.addEventListener('drop', async (e) => {
    const file = e.dataTransfer?.files?.[0];
    if (!file) return;
    const bytes = new Uint8Array(await file.arrayBuffer());
    openPdfBytes(bytes, file.name);
  });

  document.querySelectorAll('.sample-card').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const kind = btn.dataset.sample;
      showLoading('Generating sample PDF...');
      try {
        const bytes = await generateSamplePdfBytes(kind, state.fontBuffers);
        const names = {
          ticket: 'RailTicket_Reservation.pdf',
          bilingual: 'Bilingual_Notice_BN_EN.pdf',
          scanned: 'Scanned_Receipt_OCR.pdf',
        };
        await openPdfBytes(bytes, names[kind] || 'Sample.pdf');
      } finally {
        hideLoading();
      }
    });
  });

  els.btnHome.addEventListener('click', () => {
    state.pdfDoc = null;
    state.pages = [];
    state.selectedSegmentId = null;
    window.location.hash = '#/';
    renderWorkspace();
  });

  els.editInput.addEventListener('input', (e) => {
    state.draftText = e.target.value;
    const seg = findSelectedSegment();
    if (seg) redrawSinglePageCanvas(seg.pageIndex);
  });

  els.editInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      confirmCurrentEdit();
    } else if (e.key === 'Escape') {
      els.btnClosePanel.click();
    }
  });

  els.btnConfirmEdit.addEventListener('click', confirmCurrentEdit);

  els.btnRestoreSegment.addEventListener('click', () => {
    const seg = findSelectedSegment();
    if (!seg) return;
    state.draftText = seg.originalText;
    els.editInput.value = seg.originalText;
    confirmCurrentEdit();
  });

  els.btnClosePanel.addEventListener('click', () => {
    const pageIdx = findSelectedSegment()?.pageIndex;
    state.selectedSegmentId = null;
    state.draftText = '';
    if (pageIdx !== undefined) redrawSinglePageCanvas(pageIdx);
    updateBottomEditPanel();
  });

  els.btnUndo.addEventListener('click', async () => {
    const last = state.undoStack.pop();
    if (!last) return;
    state.redoStack.push(last);
    const page = state.pages[last.pageIndex];
    const seg = page?.segments.find((s) => s.id === last.segmentId);
    if (seg) seg.currentText = last.previousText;
    state.selectedSegmentId = null;
    redrawSinglePageCanvas(last.pageIndex);
    renderHeaderCountersOnly();
    updateBottomEditPanel();
    await persistProject();
  });

  els.btnRedo.addEventListener('click', async () => {
    const next = state.redoStack.pop();
    if (!next) return;
    state.undoStack.push(next);
    const page = state.pages[next.pageIndex];
    const seg = page?.segments.find((s) => s.id === next.segmentId);
    if (seg) seg.currentText = next.newText;
    state.selectedSegmentId = null;
    redrawSinglePageCanvas(next.pageIndex);
    renderHeaderCountersOnly();
    updateBottomEditPanel();
    await persistProject();
  });

  els.btnZoomIn.addEventListener('click', () => {
    state.zoomScale = Math.min(3.0, +(state.zoomScale + 0.25).toFixed(2));
    renderWorkspace();
  });

  els.btnZoomOut.addEventListener('click', () => {
    state.zoomScale = Math.max(0.6, +(state.zoomScale - 0.25).toFixed(2));
    renderWorkspace();
  });

  els.btnZoomFit.addEventListener('click', () => {
    state.zoomScale = 1.0;
    renderWorkspace();
  });

  els.btnRotate.addEventListener('click', async () => {
    const page = state.pages[state.currentPageIndex];
    if (!page) return;
    page.userRotationDeg = (page.userRotationDeg + 90) % 360;
    renderWorkspace();
    await persistProject();
  });

  els.btnRestoreDoc.addEventListener('click', async () => {
    for (const p of state.pages) {
      p.userRotationDeg = 0;
      for (const s of p.segments) s.currentText = s.originalText;
    }
    state.undoStack = [];
    state.redoStack = [];
    state.selectedSegmentId = null;
    renderWorkspace();
    await persistProject();
    showToast('Restored original PDF document.');
  });

  els.btnPrevPage.addEventListener('click', () => {
    if (state.currentPageIndex > 0) {
      state.currentPageIndex--;
      els.pageNavLabel.textContent = `Page ${state.currentPageIndex + 1} / ${state.pages.length}`;
      const shell = els.pagesContainer.children[state.currentPageIndex];
      shell?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  });

  els.btnNextPage.addEventListener('click', () => {
    if (state.currentPageIndex < state.pages.length - 1) {
      state.currentPageIndex++;
      els.pageNavLabel.textContent = `Page ${state.currentPageIndex + 1} / ${state.pages.length}`;
      const shell = els.pagesContainer.children[state.currentPageIndex];
      shell?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  });

  els.btnDownload.addEventListener('click', exportAndVerify);
  els.btnCloseExportModal.addEventListener('click', () => els.exportModal.classList.add('hidden'));
  els.btnTriggerDownload.addEventListener('click', () => {
    if (state.lastExportedBytes) triggerFileDownload(state.lastExportedBytes, state.lastExportedName);
  });
  els.btnReopenExported.addEventListener('click', () => {
    els.exportModal.classList.add('hidden');
    if (state.lastExportedBytes) openPdfBytes(state.lastExportedBytes, state.lastExportedName);
  });

  window.addEventListener('hashchange', () => {
    if (window.location.hash !== '#/editor' && state.pdfDoc) {
      state.pdfDoc = null;
      state.pages = [];
      state.selectedSegmentId = null;
      renderWorkspace();
    }
  });
}

(async function bootstrap() {
  await initFonts();
  wireEvents();
  if (window.location.hash === '#/editor' && !state.pdfDoc) {
    window.location.hash = '#/';
  }
  await refreshRecentList();
})();
