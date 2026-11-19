package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.pdf.engine.PdfPageRenderer
import com.example.pdf.model.PdfPageModel
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun PdfEditorWorkspace(
  pdfFile: File,
  pages: List<PdfPageModel>,
  currentPageIndex: Int,
  zoomScale: Float,
  selectedSegmentId: String?,
  draftText: String,
  pageRenderer: PdfPageRenderer,
  onZoomChange: (Float) -> Unit,
  onPageChanged: (Int) -> Unit,
  onBitmapReady: (Int, android.graphics.Bitmap) -> Unit,
  onTapPage: (Int, Float, Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val hScrollState = rememberScrollState()
  val scope = rememberCoroutineScope()

  val visiblePage by remember {
    derivedStateOf { listState.firstVisibleItemIndex }
  }

  LaunchedEffect(visiblePage) {
    if (visiblePage in pages.indices) {
      onPageChanged(visiblePage)
    }
  }

  val transformState = rememberTransformableState { zoomChange, _, _ ->
    onZoomChange((zoomScale * zoomChange).coerceIn(0.6f, 3.5f))
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .transformable(state = transformState),
  ) {
    val containerWidth = maxWidth
    val basePageWidth = (containerWidth - 28.dp).coerceAtMost(760.dp)
    val scaledPageWidth = (basePageWidth * zoomScale).coerceAtLeast(240.dp)
    val bottomPadding = if (selectedSegmentId != null) 168.dp else 76.dp

    Box(
      modifier = Modifier
        .fillMaxSize()
        .horizontalScroll(hScrollState),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .widthIn(min = containerWidth)
          .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(
          start = 14.dp,
          end = 14.dp,
          top = 16.dp,
          bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        items(pages, key = { it.pageIndex }) { page ->
          PdfPageSurface(
            pdfFile = pdfFile,
            page = page,
            pageWidthDp = scaledPageWidth,
            pageRenderer = pageRenderer,
            selectedSegmentId = selectedSegmentId,
            draftText = draftText,
            onBitmapReady = onBitmapReady,
            onTapPage = onTapPage,
          )
        }
      }
    }

    // Floating Page Navigation Pill (hidden while BottomTextEditPanel is open)
    if (selectedSegmentId == null && pages.isNotEmpty()) {
      Surface(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = 14.dp)
          .testTag("page_navigation_pill"),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
          1.dp,
          MaterialTheme.colorScheme.outline,
        ),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          IconButton(
            onClick = {
              val prev = (currentPageIndex - 1).coerceAtLeast(0)
              scope.launch { listState.animateScrollToItem(prev) }
            },
            enabled = currentPageIndex > 0,
            modifier = Modifier.size(40.dp).testTag("prev_page_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.ChevronLeft,
              contentDescription = "Previous page",
            )
          }

          Text(
            text = "Page ${currentPageIndex + 1} / ${pages.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp),
          )

          IconButton(
            onClick = {
              val next = (currentPageIndex + 1).coerceAtMost(pages.lastIndex)
              scope.launch { listState.animateScrollToItem(next) }
            },
            enabled = currentPageIndex < pages.lastIndex,
            modifier = Modifier.size(40.dp).testTag("next_page_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.ChevronRight,
              contentDescription = "Next page",
            )
          }
        }
      }
    }
  }
}
