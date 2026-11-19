package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdf.engine.PdfExporter
import com.example.pdf.engine.PdfPageRenderer
import com.example.pdf.engine.PdfTextAnalyzer
import com.example.pdf.engine.SamplePdfGenerator
import com.example.pdf.engine.SamplePdfKind
import com.example.pdf.model.ExportVerificationResult
import com.example.pdf.model.LocalProjectSummary
import com.example.pdf.model.OcrStatus
import com.example.pdf.model.PdfPageModel
import com.example.pdf.model.PdfTextSegment
import com.example.pdf.model.TextReplacementEdit
import com.example.pdf.storage.LocalProjectStore
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PdfEditorUiState(
  val isLoading: Boolean = false,
  val loadingMessage: String = "",
  val errorMessage: String? = null,
  val activeProjectId: String? = null,
  val documentName: String = "",
  val workingPdfFile: File? = null,
  val pages: List<PdfPageModel> = emptyList(),
  val currentPageIndex: Int = 0,
  val zoomScale: Float = 1.0f,
  val selectedSegmentId: String? = null,
  val draftText: String = "",
  val undoStack: List<TextReplacementEdit> = emptyList(),
  val redoStack: List<TextReplacementEdit> = emptyList(),
  val recentProjects: List<LocalProjectSummary> = emptyList(),
  val isExporting: Boolean = false,
  val exportResult: ExportVerificationResult? = null,
  val statusBannerMessage: String? = null,
) {
  val hasOpenDocument: Boolean get() = workingPdfFile != null && pages.isNotEmpty()
  val canUndo: Boolean get() = undoStack.isNotEmpty()
  val canRedo: Boolean get() = redoStack.isNotEmpty()
  val editedCount: Int get() = pages.sumOf { p -> p.segments.count { it.isEdited } }

  val selectedSegment: PdfTextSegment?
    get() = selectedSegmentId?.let { id ->
      pages.asSequence().flatMap { it.segments.asSequence() }.firstOrNull { it.id == id }
    }
}

class PdfEditorViewModel(application: Application) : AndroidViewModel(application) {

  private val appContext = application.applicationContext
  private val projectStore = LocalProjectStore(appContext)
  val pageRenderer = PdfPageRenderer()

  private val _uiState = MutableStateFlow(PdfEditorUiState())
  val uiState: StateFlow<PdfEditorUiState> = _uiState.asStateFlow()

  private val refinedBackgroundPages = HashSet<Int>()

  init {
    refreshRecentProjects()
  }

  fun refreshRecentProjects() {
    viewModelScope.launch {
      val list = projectStore.listRecentProjects()
      _uiState.update { it.copy(recentProjects = list) }
    }
  }

  fun openPdfFromUri(uri: Uri) {
    viewModelScope.launch {
      _uiState.update {
        it.copy(isLoading = true, loadingMessage = "Analyzing PDF structure & text...", errorMessage = null)
      }
      val created = projectStore.createProjectFromUri(uri)
      created.onSuccess { (projectId, file) ->
        loadProjectFile(projectId, file, resolveNameFromProject(projectId) ?: "Document.pdf")
      }.onFailure { err ->
        _uiState.update {
          it.copy(isLoading = false, errorMessage = err.message ?: "Failed to read PDF file.")
        }
      }
    }
  }

  fun openSamplePdf(kind: SamplePdfKind) {
    viewModelScope.launch {
      _uiState.update {
        it.copy(isLoading = true, loadingMessage = "Preparing ${kind.title}...", errorMessage = null)
      }
      try {
        val sampleDir = File(appContext.cacheDir, "samples")
        val generated = SamplePdfGenerator.createSamplePdf(appContext, kind, sampleDir)
        val created = projectStore.createProjectFromFile(generated, kind.fileName)
        created.onSuccess { (projectId, file) ->
          loadProjectFile(projectId, file, kind.fileName)
        }.onFailure { err ->
          _uiState.update {
            it.copy(isLoading = false, errorMessage = err.message ?: "Failed to prepare sample PDF.")
          }
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(isLoading = false, errorMessage = e.message ?: "Failed to create sample PDF.")
        }
      }
    }
  }

  fun openRecentProject(summary: LocalProjectSummary) {
    viewModelScope.launch {
      val file = projectStore.getProjectOriginalFile(summary.projectId)
      if (!file.exists()) {
        _uiState.update { it.copy(errorMessage = "Cached project file is missing.") }
        refreshRecentProjects()
        return@launch
      }
      _uiState.update {
        it.copy(isLoading = true, loadingMessage = "Restoring ${summary.displayName}...", errorMessage = null)
      }
      loadProjectFile(summary.projectId, file, summary.displayName)
    }
  }

  fun deleteRecentProject(projectId: String) {
    viewModelScope.launch {
      projectStore.deleteProject(projectId)
      refreshRecentProjects()
    }
  }

  fun closeDocument() {
    viewModelScope.launch {
      pageRenderer.clearCache()
      refinedBackgroundPages.clear()
      _uiState.update {
        PdfEditorUiState(recentProjects = it.recentProjects)
      }
      refreshRecentProjects()
    }
  }

  private suspend fun loadProjectFile(projectId: String, pdfFile: File, displayName: String) {
    pageRenderer.clearCache()
    refinedBackgroundPages.clear()

    val analysis = PdfTextAnalyzer.analyzeDocument(appContext, pdfFile)
    analysis.onFailure { err ->
      projectStore.deleteProject(projectId)
      _uiState.update {
        it.copy(
          isLoading = false,
          errorMessage = err.message ?: "Unable to parse this PDF safely.",
        )
      }
      return
    }

    var pages = analysis.getOrThrow()
    val saved = projectStore.loadSavedEdits(projectId)
    val resolvedName = saved?.displayName ?: displayName

    if (saved != null) {
      pages = pages.map { page ->
        val rot = saved.rotationsByPage[page.pageIndex] ?: page.userRotationDeg
        val updatedSegments = page.segments.map { seg ->
          val restoredText = saved.editsBySegmentId[seg.id]
          if (restoredText != null) seg.copy(currentText = restoredText) else seg
        }
        page.copy(userRotationDeg = rot, segments = updatedSegments)
      }
    }

    _uiState.update {
      it.copy(
        isLoading = false,
        loadingMessage = "",
        errorMessage = null,
        activeProjectId = projectId,
        documentName = resolvedName,
        workingPdfFile = pdfFile,
        pages = pages,
        currentPageIndex = 0,
        zoomScale = 1.0f,
        selectedSegmentId = null,
        draftText = "",
        undoStack = emptyList(),
        redoStack = emptyList(),
        exportResult = null,
      )
    }
    projectStore.saveProjectState(projectId, resolvedName, pages)
    refreshRecentProjects()
  }

  private suspend fun resolveNameFromProject(projectId: String): String? {
    return projectStore.loadSavedEdits(projectId)?.displayName
  }

  /**
   * Called once a page bitmap is rendered. Samples actual surrounding pixels for background
   * reconstruction and runs ML Kit OCR automatically if the page has no vector text.
   */
  fun onPageBitmapReady(pageIndex: Int, bitmap: Bitmap) {
    val page = _uiState.value.pages.getOrNull(pageIndex) ?: return

    if (!page.hasVectorText && page.ocrStatus == OcrStatus.SCANNING) {
      viewModelScope.launch {
        val ocrSegments = PdfTextAnalyzer.analyzeScannedPageWithOcr(
          pageBitmap = bitmap,
          pageIndex = pageIndex,
          widthPt = page.widthPt,
          heightPt = page.heightPt,
        )
        val saved = _uiState.value.activeProjectId?.let { projectStore.loadSavedEdits(it) }
        val mergedSegments = ocrSegments.map { seg ->
          val restored = saved?.editsBySegmentId?.get(seg.id)
          if (restored != null) seg.copy(currentText = restored) else seg
        }
        _uiState.update { state ->
          val updatedPages = state.pages.map { p ->
            if (p.pageIndex == pageIndex) {
              p.copy(
                ocrStatus = if (mergedSegments.isEmpty()) OcrStatus.NO_TEXT_FOUND else OcrStatus.COMPLETED,
                segments = mergedSegments,
              )
            } else p
          }
          state.copy(pages = updatedPages)
        }
      }
      return
    }

    if (page.hasVectorText && refinedBackgroundPages.add(pageIndex)) {
      viewModelScope.launch(Dispatchers.Default) {
        val refined = PdfTextAnalyzer.refineBackgroundsFromBitmap(page.segments, bitmap)
        _uiState.update { state ->
          val updatedPages = state.pages.map { p ->
            if (p.pageIndex == pageIndex) p.copy(segments = refined) else p
          }
          state.copy(pages = updatedPages)
        }
      }
    }
  }

  /**
   * Handles a single tap on a PDF page at normalized display coordinates (0..1, 0..1).
   * Converts rotated display coordinates to unrotated PDF space and selects the hit segment
   * without creating duplicates.
   */
  fun onPageTapped(pageIndex: Int, displayNormX: Float, displayNormY: Float) {
    val state = _uiState.value
    val page = state.pages.getOrNull(pageIndex) ?: return

    val (pdfX, pdfY) = displayToPdfNorm(displayNormX, displayNormY, page.userRotationDeg)

    // Prioritize exact hit, then nearest small touch tolerance
    val exactHit = page.segments.firstOrNull {
      it.normBounds.contains(pdfX, pdfY, padX = 0.004f, padY = 0.004f)
    }
    val softHit = exactHit ?: page.segments
      .filter { it.normBounds.contains(pdfX, pdfY, padX = 0.022f, padY = 0.018f) }
      .minByOrNull { seg ->
        abs(seg.normBounds.centerX - pdfX) + abs(seg.normBounds.centerY - pdfY)
      }

    if (softHit == null) {
      // Tapped empty canvas outside text: dismiss editing bar cleanly without losing committed edits
      if (state.selectedSegmentId != null) {
        _uiState.update { it.copy(selectedSegmentId = null, draftText = "") }
      }
      return
    }

    _uiState.update {
      it.copy(
        currentPageIndex = pageIndex,
        selectedSegmentId = softHit.id,
        draftText = softHit.currentText,
      )
    }
  }

  fun onDraftTextChanged(newText: String) {
    _uiState.update { it.copy(draftText = newText) }
  }

  fun confirmCurrentEdit() {
    val state = _uiState.value
    val selected = state.selectedSegment ?: return
    val newText = state.draftText

    if (newText == selected.currentText) {
      _uiState.update { it.copy(selectedSegmentId = null, draftText = "") }
      return
    }

    val editEntry = TextReplacementEdit(
      segmentId = selected.id,
      pageIndex = selected.pageIndex,
      previousText = selected.currentText,
      newText = newText,
    )

    val updatedPages = state.pages.map { page ->
      if (page.pageIndex != selected.pageIndex) page
      else page.copy(
        segments = page.segments.map { seg ->
          if (seg.id == selected.id) seg.copy(currentText = newText) else seg
        }
      )
    }

    _uiState.update {
      it.copy(
        pages = updatedPages,
        selectedSegmentId = null,
        draftText = "",
        undoStack = it.undoStack + editEntry,
        redoStack = emptyList(),
      )
    }
    persistCurrentProject()
  }

  fun restoreSelectedSegmentToOriginal() {
    val selected = _uiState.value.selectedSegment ?: return
    _uiState.update { it.copy(draftText = selected.originalText) }
    confirmCurrentEdit()
  }

  fun dismissEditorPanel() {
    _uiState.update { it.copy(selectedSegmentId = null, draftText = "") }
  }

  fun undo() {
    val state = _uiState.value
    val lastEdit = state.undoStack.lastOrNull() ?: return

    val updatedPages = state.pages.map { page ->
      if (page.pageIndex != lastEdit.pageIndex) page
      else page.copy(
        segments = page.segments.map { seg ->
          if (seg.id == lastEdit.segmentId) seg.copy(currentText = lastEdit.previousText) else seg
        }
      )
    }

    _uiState.update {
      it.copy(
        pages = updatedPages,
        undoStack = it.undoStack.dropLast(1),
        redoStack = it.redoStack + lastEdit,
        selectedSegmentId = null,
        draftText = "",
      )
    }
    persistCurrentProject()
  }

  fun redo() {
    val state = _uiState.value
    val nextEdit = state.redoStack.lastOrNull() ?: return

    val updatedPages = state.pages.map { page ->
      if (page.pageIndex != nextEdit.pageIndex) page
      else page.copy(
        segments = page.segments.map { seg ->
          if (seg.id == nextEdit.segmentId) seg.copy(currentText = nextEdit.newText) else seg
        }
      )
    }

    _uiState.update {
      it.copy(
        pages = updatedPages,
        undoStack = it.undoStack + nextEdit,
        redoStack = it.redoStack.dropLast(1),
        selectedSegmentId = null,
        draftText = "",
      )
    }
    persistCurrentProject()
  }

  fun restoreEntireDocument() {
    val state = _uiState.value
    if (state.pages.isEmpty()) return
    val resetPages = state.pages.map { page ->
      page.copy(
        userRotationDeg = 0,
        segments = page.segments.map { seg -> seg.copy(currentText = seg.originalText) },
      )
    }
    _uiState.update {
      it.copy(
        pages = resetPages,
        undoStack = emptyList(),
        redoStack = emptyList(),
        selectedSegmentId = null,
        draftText = "",
        statusBannerMessage = "Restored original document",
      )
    }
    persistCurrentProject()
  }

  fun setCurrentPage(pageIndex: Int) {
    val maxIdx = (_uiState.value.pages.size - 1).coerceAtLeast(0)
    _uiState.update { it.copy(currentPageIndex = pageIndex.coerceIn(0, maxIdx)) }
  }

  fun zoomIn() {
    _uiState.update { it.copy(zoomScale = (it.zoomScale + 0.25f).coerceAtMost(3.5f)) }
  }

  fun zoomOut() {
    _uiState.update { it.copy(zoomScale = (it.zoomScale - 0.25f).coerceAtLeast(0.6f)) }
  }

  fun fitToScreen() {
    _uiState.update { it.copy(zoomScale = 1.0f) }
  }

  fun setZoomScale(scale: Float) {
    _uiState.update { it.copy(zoomScale = scale.coerceIn(0.6f, 3.5f)) }
  }

  fun rotateCurrentPage() {
    val state = _uiState.value
    val targetIdx = state.currentPageIndex
    val updated = state.pages.map { page ->
      if (page.pageIndex == targetIdx) {
        page.copy(userRotationDeg = (page.userRotationDeg + 90) % 360)
      } else page
    }
    _uiState.update { it.copy(pages = updated) }
    persistCurrentProject()
  }

  fun exportPdfAndVerify() {
    val state = _uiState.value
    val sourceFile = state.workingPdfFile ?: return
    if (state.isExporting) return

    viewModelScope.launch {
      _uiState.update { it.copy(isExporting = true, errorMessage = null) }
      val baseName = state.documentName.removeSuffix(".pdf").removeSuffix(".PDF")
      val exportName = "${baseName}_edited.pdf"
      val tempOut = File(appContext.cacheDir, "exports/$exportName")

      val result = PdfExporter.exportAndVerify(
        context = appContext,
        originalPdf = sourceFile,
        pages = state.pages,
        outputFile = tempOut,
        displayName = exportName,
      )

      result.onSuccess { verification ->
        _uiState.update {
          it.copy(isExporting = false, exportResult = verification)
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            isExporting = false,
            errorMessage = err.message ?: "Failed to export PDF.",
          )
        }
      }
    }
  }

  fun saveExportedPdfToUri(destinationUri: Uri) {
    val result = _uiState.value.exportResult ?: return
    val srcFile = File(result.tempPdfPath)
    if (!srcFile.exists()) return

    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        appContext.contentResolver.openOutputStream(destinationUri)?.use { out ->
          srcFile.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("Cannot write to selected destination.")
      }.onSuccess {
        _uiState.update {
          it.copy(statusBannerMessage = "Saved ${result.displayName} successfully")
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(errorMessage = err.message ?: "Failed to save PDF.")
        }
      }
    }
  }

  fun buildShareIntentForExportedPdf(): Intent? {
    val result = _uiState.value.exportResult ?: return null
    val file = File(result.tempPdfPath)
    if (!file.exists()) return null
    val contentUri = FileProvider.getUriForFile(
      appContext,
      "${appContext.packageName}.fileprovider",
      file,
    )
    return Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_STREAM, contentUri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  fun reopenExportedPdfForInspection() {
    val result = _uiState.value.exportResult ?: return
    val file = File(result.tempPdfPath)
    if (!file.exists()) return

    viewModelScope.launch {
      _uiState.update {
        it.copy(
          exportResult = null,
          isLoading = true,
          loadingMessage = "Reopening exported PDF to inspect output...",
        )
      }
      val created = projectStore.createProjectFromFile(file, result.displayName)
      created.onSuccess { (projectId, storedFile) ->
        loadProjectFile(projectId, storedFile, result.displayName)
      }.onFailure { err ->
        _uiState.update {
          it.copy(isLoading = false, errorMessage = err.message ?: "Failed to reopen PDF.")
        }
      }
    }
  }

  fun dismissExportSheet() {
    _uiState.update { it.copy(exportResult = null) }
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun clearBanner() {
    _uiState.update { it.copy(statusBannerMessage = null) }
  }

  private fun persistCurrentProject() {
    val state = _uiState.value
    val projectId = state.activeProjectId ?: return
    viewModelScope.launch {
      projectStore.saveProjectState(projectId, state.documentName, state.pages)
      refreshRecentProjects()
    }
  }

  override fun onCleared() {
    super.onCleared()
    viewModelScope.launch { pageRenderer.clearCache() }
  }

  companion object {
    fun displayToPdfNorm(dx: Float, dy: Float, rotationDeg: Int): Pair<Float, Float> {
      return when (((rotationDeg % 360) + 360) % 360) {
        90 -> dy to (1f - dx)
        180 -> (1f - dx) to (1f - dy)
        270 -> (1f - dy) to dx
        else -> dx to dy
      }
    }
  }
}
