package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.pdf.engine.PdfPageRenderer
import com.example.pdf.model.BackgroundMode
import com.example.pdf.model.MatchedFontFamily
import com.example.pdf.model.OcrStatus
import com.example.pdf.model.PdfPageModel
import com.example.pdf.model.PdfTextSegment
import java.io.File
import kotlin.math.abs
import kotlin.math.min

@Composable
fun PdfPageSurface(
  pdfFile: File,
  page: PdfPageModel,
  pageWidthDp: Dp,
  pageRenderer: PdfPageRenderer,
  selectedSegmentId: String?,
  draftText: String,
  onBitmapReady: (Int, Bitmap) -> Unit,
  onTapPage: (Int, Float, Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var pageBitmap by remember(pdfFile.absolutePath, page.pageIndex) {
    mutableStateOf<Bitmap?>(null)
  }

  val rotNormalized = ((page.userRotationDeg % 360) + 360) % 360
  val isQuarterTurn = rotNormalized == 90 || rotNormalized == 270
  val displayAspect = if (isQuarterTurn) {
    (page.heightPt / page.widthPt).coerceIn(0.4f, 2.5f)
  } else {
    (page.widthPt / page.heightPt).coerceIn(0.4f, 2.5f)
  }

  LaunchedEffect(pdfFile.absolutePath, page.pageIndex) {
    val bmp = pageRenderer.renderPage(pdfFile, page.pageIndex, targetWidthPx = 1240)
    pageBitmap = bmp
    if (bmp != null) {
      onBitmapReady(page.pageIndex, bmp)
    }
  }

  val fontCache = remember { PdfTypefaceCache(context) }

  Box(
    modifier = modifier
      .width(pageWidthDp)
      .aspectRatio(displayAspect)
      .shadow(elevation = 6.dp, shape = RoundedCornerShape(4.dp))
      .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(4.dp))
      .testTag("pdf_page_${page.pageIndex}"),
  ) {
    val bmp = pageBitmap
    if (bmp == null || bmp.isRecycled) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(32.dp),
          color = MaterialTheme.colorScheme.primary,
          strokeWidth = 3.dp,
        )
      }
    } else {
      Canvas(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(page.pageIndex, rotNormalized, page.segments.size) {
            detectTapGestures { offset ->
              val nx = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
              val ny = (offset.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
              onTapPage(page.pageIndex, nx, ny)
            }
          }
      ) {
        val viewW = size.width
        val viewH = size.height

        drawIntoCanvas { canvas ->
          val native = canvas.nativeCanvas
          native.save()

          val logicalW: Float
          val logicalH: Float
          when (rotNormalized) {
            90 -> {
              native.translate(viewW, 0f)
              native.rotate(90f)
              logicalW = viewH
              logicalH = viewW
            }
            180 -> {
              native.translate(viewW, viewH)
              native.rotate(180f)
              logicalW = viewW
              logicalH = viewH
            }
            270 -> {
              native.translate(0f, viewH)
              native.rotate(270f)
              logicalW = viewH
              logicalH = viewW
            }
            else -> {
              logicalW = viewW
              logicalH = viewH
            }
          }

          // 1. Base rendered PDF page bitmap
          val destRect = RectF(0f, 0f, logicalW, logicalH)
          val srcRect = Rect(0, 0, bmp.width, bmp.height)
          native.drawBitmap(bmp, srcRect, destRect, null)

          // 2. Live background restoration + matched text rendering for edited or active segments
          for (segment in page.segments) {
            val isSelected = segment.id == selectedSegmentId
            val displayText = if (isSelected) draftText else segment.currentText
            val shouldRenderReplacement = isSelected || segment.isEdited

            if (shouldRenderReplacement) {
              drawSegmentBackgroundRestore(native, segment, logicalW, logicalH)
              drawSegmentLiveText(
                native = native,
                segment = segment,
                text = displayText,
                logicalW = logicalW,
                logicalH = logicalH,
                pageHeightPt = page.heightPt,
                fontCache = fontCache,
              )
            }

            if (isSelected) {
              drawSelectionHalo(native, segment, logicalW, logicalH)
            }
          }

          native.restore()
        }
      }
    }

    if (page.ocrStatus == OcrStatus.SCANNING) {
      Surface(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
        shape = RoundedCornerShape(999.dp),
      ) {
        Text(
          text = "Scanning text (OCR)...",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
      }
    } else if (!page.hasVectorText && page.ocrStatus == OcrStatus.COMPLETED) {
      Surface(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        shape = RoundedCornerShape(999.dp),
      ) {
        Text(
          text = "OCR Ready · ${page.segments.size} lines",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
    }
  }
}

private fun drawSegmentBackgroundRestore(
  native: android.graphics.Canvas,
  segment: PdfTextSegment,
  logicalW: Float,
  logicalH: Float,
) {
  val b = segment.normBounds
  val padX = (b.height * logicalH * 0.14f).coerceIn(2f, 8f)
  val padY = (b.height * logicalH * 0.10f).coerceIn(2f, 6f)
  val rect = RectF(
    (b.left * logicalW - padX).coerceAtLeast(0f),
    (b.top * logicalH - padY).coerceAtLeast(0f),
    (b.right * logicalW + padX).coerceAtMost(logicalW),
    (b.bottom * logicalH + padY).coerceAtMost(logicalH),
  )

  val bg = segment.background
  if (bg.mode == BackgroundMode.COMPLEX_INTERPOLATED && bg.patchBitmap != null && !bg.patchBitmap.isRecycled) {
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    native.drawBitmap(bg.patchBitmap, null, rect, paint)
  } else {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = if (bg.mode == BackgroundMode.SOLID_WHITE) Color.WHITE else bg.fillColorArgb
    }
    native.drawRect(rect, paint)
  }
}

private fun drawSegmentLiveText(
  native: android.graphics.Canvas,
  segment: PdfTextSegment,
  text: String,
  logicalW: Float,
  logicalH: Float,
  pageHeightPt: Float,
  fontCache: PdfTypefaceCache,
) {
  if (text.isEmpty()) return

  val style = segment.style
  val pxPerPt = logicalH / pageHeightPt.coerceAtLeast(1f)
  var textSizePx = (style.fontSizePt * pxPerPt).coerceAtLeast(8f)

  val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = style.colorArgb
    textSize = textSizePx
    typeface = fontCache.resolve(text, style.fontFamily, style.isBold, style.isItalic)
    isFakeBoldText = style.isBold
  }

  // Auto-fit replacement text inside safe horizontal margin so neighboring text is never overlapped
  val maxAvailPx = (segment.maxAvailableWidthNorm * logicalW)
    .coerceAtLeast(segment.normBounds.width * logicalW)
  val measured = paint.measureText(text)
  if (measured > maxAvailPx && measured > 0f) {
    val ratio = maxAvailPx / measured
    textSizePx = (textSizePx * ratio).coerceAtLeast(min(8f, textSizePx))
    paint.textSize = textSizePx
  }

  val startX = segment.normBounds.left * logicalW
  val baselineY = segment.baselineNormY * logicalH

  native.save()
  if (abs(style.rotationDeg) > 1f) {
    native.rotate(style.rotationDeg, startX, baselineY)
  }
  native.drawText(text, startX, baselineY, paint)
  native.restore()
}

private fun drawSelectionHalo(
  native: android.graphics.Canvas,
  segment: PdfTextSegment,
  logicalW: Float,
  logicalH: Float,
) {
  val b = segment.normBounds
  val pad = 6f
  val rect = RectF(
    (b.left * logicalW - pad).coerceAtLeast(2f),
    (b.top * logicalH - pad).coerceAtLeast(2f),
    (b.right * logicalW + pad).coerceAtMost(logicalW - 2f),
    (b.bottom * logicalH + pad).coerceAtMost(logicalH - 2f),
  )

  val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    color = Color.argb(26, 16, 185, 129)
  }
  val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 3f
    color = Color.rgb(16, 185, 129)
  }
  native.drawRoundRect(rect, 8f, 8f, fillPaint)
  native.drawRoundRect(rect, 8f, 8f, strokePaint)
}

private class PdfTypefaceCache(private val context: Context) {
  private val baseSans: Typeface = loadOrDefault(R.font.noto_sans, Typeface.SANS_SERIF)
  private val baseSerif: Typeface = loadOrDefault(R.font.noto_serif, Typeface.SERIF)
  private val baseMono: Typeface = loadOrDefault(R.font.jetbrains_mono, Typeface.MONOSPACE)
  private val baseBengali: Typeface = loadOrDefault(R.font.noto_sans_bengali, baseSans)

  private fun loadOrDefault(resId: Int, fallback: Typeface): Typeface {
    return try {
      ResourcesCompat.getFont(context, resId) ?: fallback
    } catch (_: Exception) {
      fallback
    }
  }

  fun resolve(
    text: String,
    family: MatchedFontFamily,
    isBold: Boolean,
    isItalic: Boolean,
  ): Typeface {
    val hasBengali = text.any { it in '\u0980'..'\u09FF' } || family == MatchedFontFamily.BENGALI
    val base = when {
      hasBengali -> baseBengali
      family == MatchedFontFamily.SERIF -> baseSerif
      family == MatchedFontFamily.MONO -> baseMono
      else -> baseSans
    }
    val styleFlags = when {
      isBold && isItalic -> Typeface.BOLD_ITALIC
      isBold -> Typeface.BOLD
      isItalic -> Typeface.ITALIC
      else -> Typeface.NORMAL
    }
    return Typeface.create(base, styleFlags)
  }
}
