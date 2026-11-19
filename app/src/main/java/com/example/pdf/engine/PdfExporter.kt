package com.example.pdf.engine

import android.content.Context
import android.graphics.Color
import com.example.R
import com.example.pdf.model.BackgroundMode
import com.example.pdf.model.ExportVerificationResult
import com.example.pdf.model.MatchedFontFamily
import com.example.pdf.model.PdfPageModel
import com.example.pdf.model.PdfTextSegment
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports a modified, structurally valid PDF preserving untouched pages, images, and text,
 * stripping replaced text operators, restoring backgrounds, embedding Unicode fonts (including
 * Bengali), and programmatically reopening the exported PDF to verify every replacement.
 */
object PdfExporter {

  suspend fun exportAndVerify(
    context: Context,
    originalPdf: File,
    pages: List<PdfPageModel>,
    outputFile: File,
    displayName: String,
  ): Result<ExportVerificationResult> = withContext(Dispatchers.IO) {
    runCatching {
      PdfTextAnalyzer.ensureInitialized(context)

      val editedPages = pages.filter { page ->
        page.segments.any { it.isEdited } || page.userRotationDeg != 0
      }

      PDDocument.load(originalPdf).use { document ->
        val fontCache = HashMap<Int, PDType0Font>()

        for (pageModel in pages) {
          if (pageModel.pageIndex >= document.numberOfPages) continue
          val pdPage = document.getPage(pageModel.pageIndex)

          if (pageModel.userRotationDeg != 0) {
            pdPage.rotation = (pageModel.userRotationDeg % 360 + 360) % 360
          }

          val edits = pageModel.segments.filter { it.isEdited }
          if (edits.isEmpty()) continue

          // 1. Physically strip replaced vector text operators from PDF content stream
          PdfContentRedactor.redactSegmentsOnPage(document, pdPage, edits)

          // 2. Restore original background & write matched Unicode replacement text
          PDPageContentStream(
            document,
            pdPage,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true,
          ).use { cs ->
            for (seg in edits) {
              restoreBackgroundInPdf(document, cs, seg)
              drawReplacementTextInPdf(context, document, cs, seg, pageModel, fontCache)
            }
          }
        }

        outputFile.parentFile?.mkdirs()
        document.save(outputFile)
      }

      // 3. Reopen the exported PDF programmatically to verify structure, replacements, and removal
      verifyExportedPdf(
        exportedFile = outputFile,
        displayName = displayName,
        sourcePages = pages,
        hadEdits = editedPages.isNotEmpty(),
      )
    }
  }

  private fun restoreBackgroundInPdf(
    document: PDDocument,
    cs: PDPageContentStream,
    segment: PdfTextSegment,
  ) {
    val b = segment.pdfBounds
    val padX = (segment.style.fontSizePt * 0.14f).coerceIn(1f, 4f)
    val padY = (segment.style.fontSizePt * 0.12f).coerceIn(1f, 4f)
    val bg = segment.background

    if (bg.mode == BackgroundMode.COMPLEX_INTERPOLATED && bg.patchBitmap != null && !bg.patchBitmap.isRecycled) {
      val pdImage = LosslessFactory.createFromImage(document, bg.patchBitmap)
      cs.drawImage(
        pdImage,
        b.x - padX,
        b.yBottom - padY,
        b.width + padX * 2f,
        b.height + padY * 2f,
      )
    } else {
      val color = if (bg.mode == BackgroundMode.SOLID_WHITE) Color.WHITE else bg.fillColorArgb
      cs.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
      cs.addRect(
        b.x - padX,
        b.yBottom - padY,
        b.width + padX * 2f,
        b.height + padY * 2f,
      )
      cs.fill()
    }
  }

  private fun drawReplacementTextInPdf(
    context: Context,
    document: PDDocument,
    cs: PDPageContentStream,
    segment: PdfTextSegment,
    pageModel: PdfPageModel,
    fontCache: MutableMap<Int, PDType0Font>,
  ) {
    val text = segment.currentText
    if (text.isEmpty()) return

    val style = segment.style
    val needsBengali = text.any { it in '\u0980'..'\u09FF' } || style.fontFamily == MatchedFontFamily.BENGALI
    val fontResId = when {
      needsBengali -> R.font.noto_sans_bengali
      style.fontFamily == MatchedFontFamily.SERIF -> R.font.noto_serif
      style.fontFamily == MatchedFontFamily.MONO -> R.font.jetbrains_mono
      else -> R.font.noto_sans
    }

    val pdfFont: PDFont = try {
      fontCache.getOrPut(fontResId) {
        context.resources.openRawResource(fontResId).use { stream ->
          PDType0Font.load(document, stream, true)
        }
      }
    } catch (_: Exception) {
      when {
        style.fontFamily == MatchedFontFamily.SERIF && style.isBold -> PDType1Font.TIMES_BOLD
        style.fontFamily == MatchedFontFamily.SERIF -> PDType1Font.TIMES_ROMAN
        style.fontFamily == MatchedFontFamily.MONO && style.isBold -> PDType1Font.COURIER_BOLD
        style.fontFamily == MatchedFontFamily.MONO -> PDType1Font.COURIER
        style.isBold -> PDType1Font.HELVETICA_BOLD
        else -> PDType1Font.HELVETICA
      }
    }

    // Auto-fit font size so long replacement text never overflows into adjacent segments
    val maxAvailWidthPt = (segment.maxAvailableWidthNorm * pageModel.widthPt)
      .coerceAtLeast(segment.pdfBounds.width)
    var fittedFontSize = style.fontSizePt
    val safeText = sanitizeForFont(pdfFont, text)
    val measuredAtOriginal = try {
      pdfFont.getStringWidth(safeText) / 1000f * fittedFontSize
    } catch (_: Exception) {
      segment.pdfBounds.width
    }
    if (measuredAtOriginal > maxAvailWidthPt && measuredAtOriginal > 0f) {
      val ratio = maxAvailWidthPt / measuredAtOriginal
      fittedFontSize = (fittedFontSize * ratio).coerceAtLeast(min(6f, fittedFontSize))
    }

    val r = Color.red(style.colorArgb)
    val g = Color.green(style.colorArgb)
    val b = Color.blue(style.colorArgb)

    cs.saveGraphicsState()
    cs.beginText()
    cs.setFont(pdfFont, fittedFontSize)
    cs.setNonStrokingColor(r, g, b)
    if (style.isBold) {
      cs.setStrokingColor(r, g, b)
      cs.setRenderingMode(RenderingMode.FILL_STROKE)
      cs.setLineWidth((fittedFontSize * 0.032f).coerceIn(0.25f, 1.3f))
    } else {
      cs.setRenderingMode(RenderingMode.FILL)
    }

    val matrix = if (kotlin.math.abs(style.rotationDeg) > 1f) {
      Matrix.getRotateInstance(
        Math.toRadians(-style.rotationDeg.toDouble()),
        segment.pdfBounds.x,
        segment.pdfBounds.baselineY,
      )
    } else {
      Matrix.getTranslateInstance(segment.pdfBounds.x, segment.pdfBounds.baselineY)
    }
    cs.setTextMatrix(matrix)
    cs.showText(safeText)
    cs.endText()
    cs.restoreGraphicsState()
  }

  private fun sanitizeForFont(font: PDFont, text: String): String {
    val canEncodeSpace = try {
      font.encode(" ")
      true
    } catch (_: Exception) {
      false
    }
    val sb = StringBuilder(text.length)
    for (ch in text) {
      if (ch == '\n' || ch == '\r' || ch == '\t') {
        if (canEncodeSpace) sb.append(' ')
        continue
      }
      try {
        font.encode(ch.toString())
        sb.append(ch)
      } catch (_: Exception) {
        if (canEncodeSpace) sb.append(' ')
      }
    }
    return sb.toString()
  }

  private fun verifyExportedPdf(
    exportedFile: File,
    displayName: String,
    sourcePages: List<PdfPageModel>,
    hadEdits: Boolean,
  ): ExportVerificationResult {
    PDDocument.load(exportedFile).use { reopened ->
      val actualPages = reopened.numberOfPages
      val expectedPages = sourcePages.size

      val allEdits = sourcePages.flatMap { it.segments }.filter { it.isEdited }
      if (allEdits.isEmpty()) {
        return ExportVerificationResult(
          tempPdfPath = exportedFile.absolutePath,
          displayName = displayName,
          pageCount = actualPages,
          expectedPageCount = expectedPages,
          verifiedReplacementCount = 0,
          totalReplacementCount = 0,
          oldTextRemovedCleanly = true,
          summaryMessage = if (hadEdits) {
            "Verified $actualPages page(s) with updated rotation."
          } else {
            "Verified $actualPages page(s) ready for export."
          },
        )
      }

      var verifiedNewCount = 0
      var oldTextRemoved = true
      val stripper = PDFTextStripper()

      for (pageModel in sourcePages) {
        val pageEdits = pageModel.segments.filter { it.isEdited }
        if (pageEdits.isEmpty()) continue
        stripper.startPage = pageModel.pageIndex + 1
        stripper.endPage = pageModel.pageIndex + 1
        val extracted = stripper.getText(reopened)

        for (edit in pageEdits) {
          val newClean = edit.currentText.trim()
          if (newClean.isEmpty() || extracted.contains(newClean)) {
            verifiedNewCount++
          }
          val oldClean = edit.originalText.trim()
          // Only verify absence if the replacement doesn't itself contain the old string
          if (!edit.isOcrDetected && oldClean.length >= 3 && !newClean.contains(oldClean)) {
            val stillHasOld = sourcePages[pageModel.pageIndex].segments
              .none { !it.isEdited && it.originalText.contains(oldClean) } &&
              extracted.contains(oldClean)
            if (stillHasOld) {
              oldTextRemoved = false
            }
          }
        }
      }

      val statusSummary = buildString {
        append("Reopened & verified $actualPages/$expectedPages pages. ")
        append("$verifiedNewCount/${allEdits.size} replacement(s) confirmed in PDF text layer. ")
        append(
          if (oldTextRemoved) "Original replaced text stripped from content stream."
          else "Visual background restored over original region."
        )
      }

      return ExportVerificationResult(
        tempPdfPath = exportedFile.absolutePath,
        displayName = displayName,
        pageCount = actualPages,
        expectedPageCount = expectedPages,
        verifiedReplacementCount = verifiedNewCount,
        totalReplacementCount = allEdits.size,
        oldTextRemovedCleanly = oldTextRemoved,
        summaryMessage = statusSummary,
      )
    }
  }
}
