package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.BottomTextEditPanel
import com.example.ui.screens.ExportSuccessSheet
import com.example.ui.screens.PdfEditorWorkspace
import com.example.ui.screens.PdfStudioTopBar
import com.example.ui.screens.WelcomeWorkspace
import com.example.ui.theme.PdfStudioTheme
import com.example.ui.viewmodel.PdfEditorViewModel

class MainActivity : ComponentActivity() {

  private val viewModel: PdfEditorViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
      intent.data?.let { viewModel.openPdfFromUri(it) }
    }

    setContent {
      PdfStudioTheme {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }

        val openPdfLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
          if (uri != null) {
            viewModel.openPdfFromUri(uri)
          }
        }

        val savePdfLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.CreateDocument("application/pdf"),
        ) { uri ->
          if (uri != null) {
            viewModel.saveExportedPdfToUri(uri)
          }
        }

        LaunchedEffect(state.errorMessage) {
          val err = state.errorMessage
          if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
          }
        }

        LaunchedEffect(state.statusBannerMessage) {
          val msg = state.statusBannerMessage
          if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
          }
        }

        BackHandler(enabled = state.selectedSegmentId != null || state.hasOpenDocument) {
          if (state.selectedSegmentId != null) {
            viewModel.dismissEditorPanel()
          } else if (state.hasOpenDocument) {
            viewModel.closeDocument()
          }
        }

        Scaffold(
          modifier = Modifier.fillMaxSize(),
          snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
              Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(12.dp),
              )
            }
          },
          topBar = {
            PdfStudioTopBar(
              hasDocument = state.hasOpenDocument,
              documentName = state.documentName,
              zoomScale = state.zoomScale,
              canUndo = state.canUndo,
              canRedo = state.canRedo,
              editedCount = state.editedCount,
              isExporting = state.isExporting,
              onBackHome = { viewModel.closeDocument() },
              onOpenPdf = { openPdfLauncher.launch(arrayOf("application/pdf")) },
              onUndo = { viewModel.undo() },
              onRedo = { viewModel.redo() },
              onZoomIn = { viewModel.zoomIn() },
              onZoomOut = { viewModel.zoomOut() },
              onFitToScreen = { viewModel.fitToScreen() },
              onRotatePage = { viewModel.rotateCurrentPage() },
              onRestoreOriginal = { viewModel.restoreEntireDocument() },
              onExportPdf = { viewModel.exportPdfAndVerify() },
            )
          },
        ) { innerPadding ->
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(innerPadding),
          ) {
            val openFile = state.workingPdfFile
            if (!state.hasOpenDocument || openFile == null) {
              WelcomeWorkspace(
                recentProjects = state.recentProjects,
                onOpenPdfClick = { openPdfLauncher.launch(arrayOf("application/pdf")) },
                onOpenSample = { kind -> viewModel.openSamplePdf(kind) },
                onOpenRecent = { project -> viewModel.openRecentProject(project) },
                onDeleteRecent = { id -> viewModel.deleteRecentProject(id) },
              )
            } else {
              PdfEditorWorkspace(
                pdfFile = openFile,
                pages = state.pages,
                currentPageIndex = state.currentPageIndex,
                zoomScale = state.zoomScale,
                selectedSegmentId = state.selectedSegmentId,
                draftText = state.draftText,
                pageRenderer = viewModel.pageRenderer,
                onZoomChange = { viewModel.setZoomScale(it) },
                onPageChanged = { viewModel.setCurrentPage(it) },
                onBitmapReady = { pageIdx, bmp -> viewModel.onPageBitmapReady(pageIdx, bmp) },
                onTapPage = { pageIdx, nx, ny -> viewModel.onPageTapped(pageIdx, nx, ny) },
              )

              BottomTextEditPanel(
                selectedSegment = state.selectedSegment,
                draftText = state.draftText,
                onDraftChanged = { viewModel.onDraftTextChanged(it) },
                onConfirm = { viewModel.confirmCurrentEdit() },
                onRestoreOriginal = { viewModel.restoreSelectedSegmentToOriginal() },
                onDismiss = { viewModel.dismissEditorPanel() },
                modifier = Modifier.align(Alignment.BottomCenter),
              )
            }

            if (state.isLoading || state.isExporting) {
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
              ) {
                Surface(
                  shape = MaterialTheme.shapes.medium,
                  color = MaterialTheme.colorScheme.surface,
                  shadowElevation = 8.dp,
                ) {
                  Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(36.dp),
                      color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                      text = if (state.isExporting) "Exporting & verifying PDF..." else state.loadingMessage,
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                  }
                }
              }
            }
          }
        }

        state.exportResult?.let { verification ->
          ExportSuccessSheet(
            result = verification,
            onSaveToDevice = { savePdfLauncher.launch(verification.displayName) },
            onSharePdf = {
              viewModel.buildShareIntentForExportedPdf()?.let { shareIntent ->
                startActivity(Intent.createChooser(shareIntent, "Share Edited PDF"))
              }
            },
            onReopenToInspect = { viewModel.reopenExportedPdfForInspection() },
            onDismiss = { viewModel.dismissExportSheet() },
          )
        }
      }
    }
  }
}
