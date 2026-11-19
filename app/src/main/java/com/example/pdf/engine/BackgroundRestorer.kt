package com.example.pdf.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.example.pdf.model.BackgroundMode
import com.example.pdf.model.BackgroundSample
import com.example.pdf.model.NormRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Samples surrounding pixels outside a text bounding box to reconstruct the underlying
 * surface cleanly. Distinguishes pure white, uniform solid colors, and complex textured/gradient
 * backgrounds (generating an edge-interpolated patch with an explicit honesty note).
 */
object BackgroundRestorer {

  fun analyzeRegion(pageBitmap: Bitmap?, normBounds: NormRect): BackgroundSample {
    if (pageBitmap == null || pageBitmap.isRecycled || pageBitmap.width < 8 || pageBitmap.height < 8) {
      return BackgroundSample(
        mode = BackgroundMode.SOLID_WHITE,
        fillColorArgb = Color.WHITE,
      )
    }

    val w = pageBitmap.width
    val h = pageBitmap.height

    val leftPx = (normBounds.left * w).toInt().coerceIn(0, w - 1)
    val topPx = (normBounds.top * h).toInt().coerceIn(0, h - 1)
    val rightPx = (normBounds.right * w).toInt().coerceIn(leftPx + 1, w)
    val bottomPx = (normBounds.bottom * h).toInt().coerceIn(topPx + 1, h)

    val ringPad = max(3, min(8, (bottomPx - topPx) / 3))
    val outerLeft = (leftPx - ringPad).coerceAtLeast(0)
    val outerTop = (topPx - ringPad).coerceAtLeast(0)
    val outerRight = (rightPx + ringPad).coerceAtMost(w - 1)
    val outerBottom = (bottomPx + ringPad).coerceAtMost(h - 1)

    val borderPixels = ArrayList<Int>(128)

    // Top and bottom strips just outside text box
    for (x in outerLeft..outerRight step 2) {
      if (outerTop < topPx) borderPixels.add(pageBitmap.getPixel(x, outerTop))
      if (outerBottom > bottomPx - 1) borderPixels.add(pageBitmap.getPixel(x, outerBottom))
    }
    // Left and right strips just outside text box
    for (y in outerTop..outerBottom step 2) {
      if (outerLeft < leftPx) borderPixels.add(pageBitmap.getPixel(outerLeft, y))
      if (outerRight > rightPx - 1) borderPixels.add(pageBitmap.getPixel(outerRight, y))
    }

    if (borderPixels.isEmpty()) {
      return BackgroundSample(mode = BackgroundMode.SOLID_WHITE, fillColorArgb = Color.WHITE)
    }

    // Filter out dark outlier pixels (adjacent border lines or nearby glyphs) by keeping the
    // brighter 70% cluster when luminance varies sharply, or median across all samples.
    val sortedByLum = borderPixels.sortedBy { luminance(it) }
    val trimStart = (sortedByLum.size * 0.25f).toInt().coerceAtMost(sortedByLum.lastIndex)
    val cleanSamples = sortedByLum.subList(trimStart, sortedByLum.size)

    val medianColor = cleanSamples[cleanSamples.size / 2]
    val rMean = cleanSamples.map { Color.red(it) }.average()
    val gMean = cleanSamples.map { Color.green(it) }.average()
    val bMean = cleanSamples.map { Color.blue(it) }.average()

    var varianceSum = 0.0
    for (c in cleanSamples) {
      val dr = Color.red(c) - rMean
      val dg = Color.green(c) - gMean
      val db = Color.blue(c) - bMean
      varianceSum += (dr * dr + dg * dg + db * db) / 3.0
    }
    val stdDev = sqrt(varianceSum / cleanSamples.size.coerceAtLeast(1))

    val medR = Color.red(medianColor)
    val medG = Color.green(medianColor)
    val medB = Color.blue(medianColor)

    // Pure white or near-white paper check
    if (stdDev < 10.0 && medR >= 242 && medG >= 242 && medB >= 242) {
      return BackgroundSample(
        mode = BackgroundMode.SOLID_WHITE,
        fillColorArgb = Color.WHITE,
      )
    }

    // Uniform solid color card/badge check
    if (stdDev < 15.0) {
      return BackgroundSample(
        mode = BackgroundMode.SOLID_COLOR,
        fillColorArgb = Color.rgb(medR, medG, medB),
      )
    }

    // Complex/gradient/textured background: synthesize clean directional interpolation patch
    val patchW = (rightPx - leftPx).coerceIn(4, 360)
    val patchH = (bottomPx - topPx).coerceIn(4, 140)
    val patch = buildInterpolatedPatch(
      source = pageBitmap,
      leftPx = leftPx,
      topPx = topPx,
      rightPx = rightPx,
      bottomPx = bottomPx,
      patchW = patchW,
      patchH = patchH,
      fallbackColor = medianColor,
    )

    return BackgroundSample(
      mode = BackgroundMode.COMPLEX_INTERPOLATED,
      fillColorArgb = medianColor,
      patchBitmap = patch,
      confidenceNote = "Complex background interpolated from surrounding edges",
    )
  }

  /**
   * Estimates the primary ink color inside an OCR-detected text region by finding
   * pixels that contrast most strongly against the sampled background color.
   */
  fun estimateForegroundInkColor(
    pageBitmap: Bitmap?,
    normBounds: NormRect,
    backgroundColor: Int,
  ): Int {
    if (pageBitmap == null || pageBitmap.isRecycled) return Color.rgb(20, 24, 28)
    val w = pageBitmap.width
    val h = pageBitmap.height
    val leftPx = (normBounds.left * w).toInt().coerceIn(0, w - 1)
    val topPx = (normBounds.top * h).toInt().coerceIn(0, h - 1)
    val rightPx = (normBounds.right * w).toInt().coerceIn(leftPx + 1, w)
    val bottomPx = (normBounds.bottom * h).toInt().coerceIn(topPx + 1, h)

    val bgR = Color.red(backgroundColor)
    val bgG = Color.green(backgroundColor)
    val bgB = Color.blue(backgroundColor)

    val candidates = ArrayList<Pair<Int, Int>>()
    val stepX = max(1, (rightPx - leftPx) / 28)
    val stepY = max(1, (bottomPx - topPx) / 14)

    for (y in topPx until bottomPx step stepY) {
      for (x in leftPx until rightPx step stepX) {
        val px = pageBitmap.getPixel(x, y)
        val dist = abs(Color.red(px) - bgR) + abs(Color.green(px) - bgG) + abs(Color.blue(px) - bgB)
        if (dist > 60) {
          candidates.add(px to dist)
        }
      }
    }

    if (candidates.isEmpty()) {
      return if (luminance(backgroundColor) < 0.45) Color.WHITE else Color.rgb(15, 23, 42)
    }

    // Take the upper-quartile strongest ink pixels to avoid anti-aliased edge wash
    val strong = candidates.sortedByDescending { it.second }
    val takeCount = max(1, strong.size / 3)
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    for (i in 0 until takeCount) {
      val c = strong[i].first
      rSum += Color.red(c)
      gSum += Color.green(c)
      bSum += Color.blue(c)
    }
    return Color.rgb((rSum / takeCount).toInt(), (gSum / takeCount).toInt(), (bSum / takeCount).toInt())
  }

  private fun buildInterpolatedPatch(
    source: Bitmap,
    leftPx: Int,
    topPx: Int,
    rightPx: Int,
    bottomPx: Int,
    patchW: Int,
    patchH: Int,
    fallbackColor: Int,
  ): Bitmap {
    val srcW = source.width
    val srcH = source.height
    val sampleTopY = (topPx - 2).coerceIn(0, srcH - 1)
    val sampleBottomY = (bottomPx + 1).coerceIn(0, srcH - 1)
    val sampleLeftX = (leftPx - 2).coerceIn(0, srcW - 1)
    val sampleRightX = (rightPx + 1).coerceIn(0, srcW - 1)

    val pixels = IntArray(patchW * patchH)
    val boxW = (rightPx - leftPx).coerceAtLeast(1)
    val boxH = (bottomPx - topPx).coerceAtLeast(1)

    for (py in 0 until patchH) {
      val v = (py + 0.5f) / patchH.toFloat()
      val sy = (topPx + v * boxH).toInt().coerceIn(0, srcH - 1)
      val cLeft = source.getPixel(sampleLeftX, sy)
      val cRight = source.getPixel(sampleRightX, sy)

      for (px in 0 until patchW) {
        val u = (px + 0.5f) / patchW.toFloat()
        val sx = (leftPx + u * boxW).toInt().coerceIn(0, srcW - 1)
        val cTop = source.getPixel(sx, sampleTopY)
        val cBottom = source.getPixel(sx, sampleBottomY)

        // Blend vertical (top-bottom) and horizontal (left-right) edge interpolations
        val vR = Color.red(cTop) * (1f - v) + Color.red(cBottom) * v
        val vG = Color.green(cTop) * (1f - v) + Color.green(cBottom) * v
        val vB = Color.blue(cTop) * (1f - v) + Color.blue(cBottom) * v

        val hR = Color.red(cLeft) * (1f - u) + Color.red(cRight) * u
        val hG = Color.green(cLeft) * (1f - u) + Color.green(cRight) * u
        val hB = Color.blue(cLeft) * (1f - u) + Color.blue(cRight) * u

        // Weight vertical edges higher for wide single-line text strips
        val vWeight = 0.72f
        val hWeight = 0.28f
        val r = (vR * vWeight + hR * hWeight).toInt().coerceIn(0, 255)
        val g = (vG * vWeight + hG * hWeight).toInt().coerceIn(0, 255)
        val b = (vB * vWeight + hB * hWeight).toInt().coerceIn(0, 255)

        pixels[py * patchW + px] =
          if (r == 0 && g == 0 && b == 0 && luminance(fallbackColor) > 0.5) fallbackColor
          else Color.rgb(r, g, b)
      }
    }

    return Bitmap.createBitmap(pixels, patchW, patchH, Bitmap.Config.ARGB_8888)
  }

  private fun luminance(argb: Int): Double {
    val r = Color.red(argb) / 255.0
    val g = Color.green(argb) / 255.0
    val b = Color.blue(argb) / 255.0
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
  }
}
