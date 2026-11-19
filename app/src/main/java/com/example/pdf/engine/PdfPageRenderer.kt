package com.example.pdf.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hardware-accelerated PDF page renderer using Android's PdfRenderer with an LRU memory cache
 * so zoom/typing operations never re-render untouched pages unnecessarily.
 */
class PdfPageRenderer {

  private val mutex = Mutex()
  private var currentFilePath: String? = null
  private var parcelFd: ParcelFileDescriptor? = null
  private var pdfRenderer: PdfRenderer? = null

  // ~28 MB memory cache cap for fast smooth scrolling on mobile devices
  private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  suspend fun renderPage(
    pdfFile: File,
    pageIndex: Int,
    targetWidthPx: Int = 1240,
  ): Bitmap? = withContext(Dispatchers.IO) {
    val safeWidth = targetWidthPx.coerceIn(320, 1800)
    val cacheKey = "${pdfFile.absolutePath}_${pdfFile.lastModified()}_p${pageIndex}_w$safeWidth"
    cache.get(cacheKey)?.let { cached ->
      if (!cached.isRecycled) return@withContext cached
    }

    mutex.withLock {
      cache.get(cacheKey)?.let { cached ->
        if (!cached.isRecycled) return@withLock cached
      }

      try {
        ensureRendererOpen(pdfFile)
        val renderer = pdfRenderer ?: return@withLock null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withLock null

        renderer.openPage(pageIndex).use { page ->
          val aspect = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
          val targetHeight = (safeWidth * aspect).roundToInt().coerceAtLeast(1)
          val bitmap = Bitmap.createBitmap(safeWidth, targetHeight, Bitmap.Config.ARGB_8888)
          bitmap.eraseColor(Color.WHITE)
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          cache.put(cacheKey, bitmap)
          bitmap
        }
      } catch (_: Exception) {
        null
      }
    }
  }

  private fun ensureRendererOpen(pdfFile: File) {
    val key = "${pdfFile.absolutePath}_${pdfFile.lastModified()}"
    if (currentFilePath == key && pdfRenderer != null) return
    closeInternal()
    val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    parcelFd = fd
    pdfRenderer = PdfRenderer(fd)
    currentFilePath = key
  }

  suspend fun clearCache() {
    mutex.withLock {
      cache.evictAll()
      closeInternal()
    }
  }

  private fun closeInternal() {
    try {
      pdfRenderer?.close()
    } catch (_: Exception) {
    }
    try {
      parcelFd?.close()
    } catch (_: Exception) {
    }
    pdfRenderer = null
    parcelFd = null
    currentFilePath = null
  }
}
