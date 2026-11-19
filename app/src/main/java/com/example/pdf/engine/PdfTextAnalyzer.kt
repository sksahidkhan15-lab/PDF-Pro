package com.example.pdf.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.pdf.model.BackgroundMode
import com.example.pdf.model.BackgroundSample
import com.example.pdf.model.MatchedFontFamily
import com.example.pdf.model.NormRect
import com.example.pdf.model.OcrStatus
import com.example.pdf.model.PdfPageModel
import com.example.pdf.model.PdfPointRect
import com.example.pdf.model.PdfTextSegment
import com.example.pdf.model.PdfTextStyle
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Extracts structured, style-matched text segments from PDF pages via PDFBox-Android,
 * and falls back to on-device ML Kit OCR when a page contains no vector text operators.
 */
object PdfTextAnalyzer {

  private val initialized = AtomicBoolean(false)

  fun ensureInitialized(context: Context) {
    if (initialized.compareAndSet(false, true)) {
      PDFBoxResourceLoader.init(context.applicationContext)
    }
  }

  suspend fun analyzeDocument(
    context: Context,
    pdfFile: File,
  ): Result<List<PdfPageModel>> = withContext(Dispatchers.IO) {
    runCatching {
      ensureInitialized(context)
      if (!pdfFile.exists() || pdfFile.length() < 8L) {
        throw IllegalArgumentException("The selected file is empty or cannot be read.")
      }
      val header = ByteArray(5)
      pdfFile.inputStream().use { it.read(header) }
      if (!String(header, Charsets.US_ASCII).startsWith("%PDF")) {
        throw IllegalArgumentException("Invalid PDF format: missing %PDF header.")
      }

      PDDocument.load(pdfFile).use { document ->
        if (document.isEncrypted) {
          throw IllegalStateException("This PDF is password-protected and cannot be edited locally.")
        }
        val pageCount = document.numberOfPages
        if (pageCount <= 0) {
          throw IllegalStateException("This PDF contains zero pages.")
        }

        val pages = ArrayList<PdfPageModel>(pageCount)
        for (index in 0 until pageCount) {
          val pdPage = document.getPage(index)
          val cropBox = pdPage.cropBox ?: pdPage.mediaBox
          val widthPt = cropBox.width.coerceAtLeast(1f)
          val heightPt = cropBox.height.coerceAtLeast(1f)

          val stripper = SegmentExtractorStripper(
            pageIndex = index,
            pageWidthPt = widthPt,
            pageHeightPt = heightPt,
            cropLowerLeftX = cropBox.lowerLeftX,
            cropLowerLeftY = cropBox.lowerLeftY,
          )
          stripper.startPage = index + 1
          stripper.endPage = index + 1
          stripper.getText(document)

          val rawSegments = stripper.buildSegments()
          val withNeighborBounds = computeSafeNeighborBounds(rawSegments)

          val hasVector = withNeighborBounds.isNotEmpty()
          pages.add(
            PdfPageModel(
              pageIndex = index,
              widthPt = widthPt,
              heightPt = heightPt,
              userRotationDeg = pdPage.rotation,
              hasVectorText = hasVector,
              ocrStatus = if (hasVector) OcrStatus.NOT_NEEDED else OcrStatus.SCANNING,
              segments = withNeighborBounds,
            )
          )
        }
        pages
      }
    }
  }

  /**
   * Refines background samples for vector segments once a page bitmap is rendered,
   * so colored badges/banners or complex backgrounds are accurately sampled.
   */
  fun refineBackgroundsFromBitmap(
    segments: List<PdfTextSegment>,
    pageBitmap: Bitmap?,
  ): List<PdfTextSegment> {
    if (pageBitmap == null || pageBitmap.isRecycled) return segments
    return segments.map { segment ->
      val bg = BackgroundRestorer.analyzeRegion(pageBitmap, segment.normBounds)
      segment.copy(background = bg)
    }
  }

  /**
   * Runs on-device ML Kit OCR on a scanned/image-only PDF page bitmap and produces
   * selectable, style-estimated text segments with background reconstruction.
   */
  suspend fun analyzeScannedPageWithOcr(
    pageBitmap: Bitmap,
    pageIndex: Int,
    widthPt: Float,
    heightPt: Float,
  ): List<PdfTextSegment> = withContext(Dispatchers.Default) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val inputImage = InputImage.fromBitmap(pageBitmap, 0)

    val visionText = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { cont ->
      recognizer.process(inputImage)
        .addOnSuccessListener { result ->
          if (cont.isActive) cont.resume(result)
        }
        .addOnFailureListener {
          if (cont.isActive) cont.resume(null)
        }
    }

    if (visionText == null) return@withContext emptyList()

    val bmpW = pageBitmap.width.toFloat().coerceAtLeast(1f)
    val bmpH = pageBitmap.height.toFloat().coerceAtLeast(1f)
    val segments = ArrayList<PdfTextSegment>()
    var seq = 0

    for (block in visionText.textBlocks) {
      for (line in block.lines) {
        val text = line.text.trim()
        val box = line.boundingBox ?: continue
        if (text.isEmpty() || box.width() < 4 || box.height() < 4) continue

        val normLeft = (box.left / bmpW).coerceIn(0f, 0.99f)
        val normTop = (box.top / bmpH).coerceIn(0f, 0.99f)
        val normRight = (box.right / bmpW).coerceIn(normLeft + 0.005f, 1f)
        val normBottom = (box.bottom / bmpH).coerceIn(normTop + 0.005f, 1f)
        val normBounds = NormRect(normLeft, normTop, normRight, normBottom)

        val bgSample = BackgroundRestorer.analyzeRegion(pageBitmap, normBounds)
        val inkColor = BackgroundRestorer.estimateForegroundInkColor(
          pageBitmap = pageBitmap,
          normBounds = normBounds,
          backgroundColor = bgSample.fillColorArgb,
        )

        val boxHeightPt = normBounds.height * heightPt
        val estFontSizePt = (boxHeightPt * 0.78f).coerceIn(7f, 72f)
        val baselineNormY = normBottom - normBounds.height * 0.18f

        val pdfBounds = PdfPointRect(
          x = normLeft * widthPt,
          yBottom = (1f - normBottom) * heightPt,
          width = normBounds.width * widthPt,
          height = boxHeightPt,
          baselineY = (1f - baselineNormY) * heightPt,
        )

        segments.add(
          PdfTextSegment(
            id = "p${pageIndex}_ocr_${seq++}",
            pageIndex = pageIndex,
            originalText = text,
            currentText = text,
            style = PdfTextStyle(
              fontFamily = detectFontFamily(text, ""),
              originalFontName = "Scanned OCR",
              fontSizePt = estFontSizePt,
              colorArgb = inkColor,
              isBold = boxHeightPt > 18f,
              isItalic = false,
            ),
            normBounds = normBounds,
            baselineNormY = baselineNormY,
            pdfBounds = pdfBounds,
            maxAvailableWidthNorm = (0.96f - normLeft).coerceAtLeast(normBounds.width),
            background = bgSample,
            isOcrDetected = true,
          )
        )
      }
    }

    computeSafeNeighborBounds(segments)
  }

  internal fun computeSafeNeighborBounds(segments: List<PdfTextSegment>): List<PdfTextSegment> {
    if (segments.isEmpty()) return segments
    return segments.map { current ->
      var nearestRight = 0.96f
      for (other in segments) {
        if (other.id == current.id) continue
        val verticalOverlap =
          min(current.normBounds.bottom, other.normBounds.bottom) -
            max(current.normBounds.top, other.normBounds.top)
        if (verticalOverlap > current.normBounds.height * 0.25f &&
          other.normBounds.left > current.normBounds.right - 0.005f
        ) {
          nearestRight = min(nearestRight, other.normBounds.left - 0.01f)
        }
      }
      val maxWidth = (nearestRight - current.normBounds.left)
        .coerceAtLeast(current.normBounds.width)
      current.copy(maxAvailableWidthNorm = maxWidth)
    }
  }

  internal fun detectFontFamily(text: String, fontName: String): MatchedFontFamily {
    if (text.any { it in '\u0980'..'\u09FF' }) {
      return MatchedFontFamily.BENGALI
    }
    val lower = fontName.lowercase()
    return when {
      "courier" in lower || "mono" in lower || "consola" in lower -> MatchedFontFamily.MONO
      "times" in lower || "serif" in lower || "georgia" in lower || "garamond" in lower ->
        MatchedFontFamily.SERIF
      else -> MatchedFontFamily.SANS
    }
  }

  private data class CapturedGlyph(
    val unicode: String,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val baselineY: Float,
    val fontSizePt: Float,
    val spaceWidth: Float,
    val fontName: String,
    val isBold: Boolean,
    val isItalic: Boolean,
    val colorArgb: Int,
    val rotationDeg: Float,
  )

  private class SegmentExtractorStripper(
    private val pageIndex: Int,
    private val pageWidthPt: Float,
    private val pageHeightPt: Float,
    private val cropLowerLeftX: Float,
    private val cropLowerLeftY: Float,
  ) : PDFTextStripper() {

    private val glyphs = ArrayList<CapturedGlyph>(256)

    init {
      sortByPosition = true
    }

    override fun processTextPosition(text: TextPosition) {
      val unicode = text.unicode ?: ""
      if (unicode.isEmpty()) {
        super.processTextPosition(text)
        return
      }

      val font = text.font
      val fontName = font?.name ?: "Helvetica"
      val descriptor = font?.fontDescriptor
      val lowerName = fontName.lowercase()

      val isBold =
        "bold" in lowerName ||
          "black" in lowerName ||
          "heavy" in lowerName ||
          "semibold" in lowerName ||
          "demi" in lowerName ||
          (descriptor?.fontWeight ?: 400f) >= 600f ||
          descriptor?.isForceBold == true

      val isItalic =
        "italic" in lowerName ||
          "oblique" in lowerName ||
          descriptor?.isItalic == true

      val colorRgb = try {
        graphicsState.nonStrokingColor.toRGB()
      } catch (_: Exception) {
        0x000000
      }
      val argb = Color.rgb((colorRgb shr 16) and 0xFF, (colorRgb shr 8) and 0xFF, colorRgb and 0xFF)

      val tm = text.textMatrix
      val rotRad = atan2(tm.shearY.toDouble(), tm.scaleX.toDouble())
      val rotDeg = Math.toDegrees(rotRad).toFloat()

      val fontSizePt = max(
        text.fontSizeInPt,
        abs(tm.scalingFactorY).takeIf { it > 0.5f } ?: text.fontSize,
      ).coerceIn(5f, 160f)

      val glyphH = max(text.heightDir, fontSizePt * 0.72f)
      val left = text.xDirAdj
      val width = max(text.widthDirAdj, fontSizePt * 0.25f)
      val baselineY = text.yDirAdj
      val top = baselineY - glyphH * 1.05f
      val bottom = baselineY + glyphH * 0.25f

      glyphs.add(
        CapturedGlyph(
          unicode = unicode,
          left = left,
          right = left + width,
          top = top,
          bottom = bottom,
          baselineY = baselineY,
          fontSizePt = fontSizePt,
          spaceWidth = max(text.widthOfSpace, fontSizePt * 0.26f),
          fontName = fontName,
          isBold = isBold,
          isItalic = isItalic,
          colorArgb = argb,
          rotationDeg = if (abs(rotDeg) < 1f) 0f else rotDeg,
        )
      )
      super.processTextPosition(text)
    }

    fun buildSegments(): List<PdfTextSegment> {
      if (glyphs.isEmpty()) return emptyList()

      val sorted = glyphs.sortedWith(compareBy({ (it.baselineY / 4f).toInt() }, { it.left }))
      val groups = ArrayList<MutableList<CapturedGlyph>>()

      for (g in sorted) {
        if (g.unicode.isBlank() && groups.isEmpty()) continue
        val lastGroup = groups.lastOrNull()
        if (lastGroup == null) {
          if (g.unicode.isNotBlank()) groups.add(mutableListOf(g))
          continue
        }
        val prev = lastGroup.last()
        val sameBaseline = abs(g.baselineY - prev.baselineY) <= max(prev.fontSizePt, g.fontSizePt) * 0.45f
        val gap = g.left - prev.right
        val sameStyleGroup =
          abs(g.fontSizePt - prev.fontSizePt) <= 1.5f &&
            g.isBold == prev.isBold &&
            g.colorArgb == prev.colorArgb
        val maxJoinGap = max(prev.spaceWidth * 1.45f, prev.fontSizePt * 0.65f)

        if (sameBaseline && sameStyleGroup && gap >= -prev.fontSizePt * 0.4f && gap <= maxJoinGap) {
          lastGroup.add(g)
        } else if (g.unicode.isNotBlank()) {
          groups.add(mutableListOf(g))
        }
      }

      val result = ArrayList<PdfTextSegment>(groups.size)
      var seq = 0
      for (group in groups) {
        val rawStr = buildTextFromGlyphs(group).trim()
        if (rawStr.isEmpty()) continue

        val minLeft = group.minOf { it.left }
        val maxRight = group.maxOf { it.right }
        val minTop = group.minOf { it.top }
        val maxBottom = group.maxOf { it.bottom }
        val avgBaseline = group.map { it.baselineY }.average().toFloat()

        val normLeft = (minLeft / pageWidthPt).coerceIn(0f, 0.99f)
        val normRight = (maxRight / pageWidthPt).coerceIn(normLeft + 0.005f, 1f)
        val normTop = (minTop / pageHeightPt).coerceIn(0f, 0.99f)
        val normBottom = (maxBottom / pageHeightPt).coerceIn(normTop + 0.005f, 1f)
        val normBaseline = (avgBaseline / pageHeightPt).coerceIn(normTop, normBottom)

        val lead = group.firstOrNull { it.unicode.isNotBlank() } ?: group.first()
        val fontFamily = detectFontFamily(rawStr, lead.fontName)

        val pdfBounds = PdfPointRect(
          x = cropLowerLeftX + normLeft * pageWidthPt,
          yBottom = cropLowerLeftY + (1f - normBottom) * pageHeightPt,
          width = (normRight - normLeft) * pageWidthPt,
          height = (normBottom - normTop) * pageHeightPt,
          baselineY = cropLowerLeftY + (1f - normBaseline) * pageHeightPt,
        )

        result.add(
          PdfTextSegment(
            id = "p${pageIndex}_s${seq++}",
            pageIndex = pageIndex,
            originalText = rawStr,
            currentText = rawStr,
            style = PdfTextStyle(
              fontFamily = fontFamily,
              originalFontName = lead.fontName.substringAfter('+'),
              fontSizePt = lead.fontSizePt,
              colorArgb = lead.colorArgb,
              isBold = lead.isBold,
              isItalic = lead.isItalic,
              rotationDeg = lead.rotationDeg,
            ),
            normBounds = NormRect(normLeft, normTop, normRight, normBottom),
            baselineNormY = normBaseline,
            pdfBounds = pdfBounds,
            maxAvailableWidthNorm = (0.96f - normLeft).coerceAtLeast(normRight - normLeft),
            background = BackgroundSample(
              mode = BackgroundMode.SOLID_WHITE,
              fillColorArgb = Color.WHITE,
            ),
            isOcrDetected = false,
          )
        )
      }
      return result
    }

    private fun buildTextFromGlyphs(group: List<CapturedGlyph>): String {
      val sb = StringBuilder()
      for (i in group.indices) {
        val curr = group[i]
        if (i > 0) {
          val prev = group[i - 1]
          val gap = curr.left - prev.right
          if (gap > prev.spaceWidth * 0.42f && !prev.unicode.endsWith(" ") && !curr.unicode.startsWith(" ")) {
            sb.append(' ')
          }
        }
        sb.append(curr.unicode)
      }
      return sb.toString()
    }
  }
}
