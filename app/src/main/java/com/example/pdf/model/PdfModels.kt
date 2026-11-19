package com.example.pdf.model

import android.graphics.Bitmap

enum class MatchedFontFamily(val label: String) {
  SANS("Sans"),
  SERIF("Serif"),
  MONO("Mono"),
  BENGALI("Bengali Sans"),
}

enum class TextSegmentAlignment {
  LEFT,
  CENTER,
  RIGHT,
}

enum class BackgroundMode {
  SOLID_WHITE,
  SOLID_COLOR,
  COMPLEX_INTERPOLATED,
}

enum class OcrStatus {
  NOT_NEEDED,
  SCANNING,
  COMPLETED,
  NO_TEXT_FOUND,
}

data class NormRect(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
) {
  val width: Float get() = (right - left).coerceAtLeast(0.001f)
  val height: Float get() = (bottom - top).coerceAtLeast(0.001f)
  val centerX: Float get() = (left + right) * 0.5f
  val centerY: Float get() = (top + bottom) * 0.5f

  fun contains(normX: Float, normY: Float, padX: Float = 0.012f, padY: Float = 0.012f): Boolean {
    return normX in (left - padX)..(right + padX) && normY in (top - padY)..(bottom + padY)
  }
}

data class PdfPointRect(
  val x: Float,
  val yBottom: Float,
  val width: Float,
  val height: Float,
  val baselineY: Float,
)

data class PdfTextStyle(
  val fontFamily: MatchedFontFamily,
  val originalFontName: String,
  val fontSizePt: Float,
  val colorArgb: Int,
  val isBold: Boolean,
  val isItalic: Boolean,
  val letterSpacingPt: Float = 0f,
  val rotationDeg: Float = 0f,
  val alignment: TextSegmentAlignment = TextSegmentAlignment.LEFT,
) {
  fun summaryLabel(): String {
    val weight = if (isBold) "Bold" else "Regular"
    val italicTag = if (isItalic) " Italic" else ""
    return "${fontFamily.label} $weight$italicTag · ${fontSizePt.toInt()}pt"
  }
}

data class BackgroundSample(
  val mode: BackgroundMode,
  val fillColorArgb: Int,
  val patchBitmap: Bitmap? = null,
  val confidenceNote: String? = null,
)

data class PdfTextSegment(
  val id: String,
  val pageIndex: Int,
  val originalText: String,
  val currentText: String,
  val style: PdfTextStyle,
  val normBounds: NormRect,
  val baselineNormY: Float,
  val pdfBounds: PdfPointRect,
  val maxAvailableWidthNorm: Float,
  val background: BackgroundSample,
  val isOcrDetected: Boolean = false,
) {
  val isEdited: Boolean
    get() = currentText != originalText
}

data class PdfPageModel(
  val pageIndex: Int,
  val widthPt: Float,
  val heightPt: Float,
  val userRotationDeg: Int = 0,
  val hasVectorText: Boolean = true,
  val ocrStatus: OcrStatus = OcrStatus.NOT_NEEDED,
  val segments: List<PdfTextSegment> = emptyList(),
)

data class TextReplacementEdit(
  val segmentId: String,
  val pageIndex: Int,
  val previousText: String,
  val newText: String,
)

data class LocalProjectSummary(
  val projectId: String,
  val displayName: String,
  val lastModifiedEpochMs: Long,
  val pageCount: Int,
  val editCount: Int,
)

data class ExportVerificationResult(
  val tempPdfPath: String,
  val displayName: String,
  val pageCount: Int,
  val expectedPageCount: Int,
  val verifiedReplacementCount: Int,
  val totalReplacementCount: Int,
  val oldTextRemovedCleanly: Boolean,
  val summaryMessage: String,
)
