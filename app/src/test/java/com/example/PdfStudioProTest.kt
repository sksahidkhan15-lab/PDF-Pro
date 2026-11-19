package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.example.pdf.engine.BackgroundRestorer
import com.example.pdf.engine.PdfExporter
import com.example.pdf.engine.PdfTextAnalyzer
import com.example.pdf.engine.SamplePdfGenerator
import com.example.pdf.engine.SamplePdfKind
import com.example.pdf.model.BackgroundMode
import com.example.pdf.model.MatchedFontFamily
import com.example.pdf.model.NormRect
import com.example.ui.viewmodel.PdfEditorViewModel
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfStudioProTest {

  @Test
  fun `acceptance test replace CNF_B3_2_MIDDLE with MIDDAA and verify clean export`() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val workDir = File(app.cacheDir, "test_acceptance_${System.nanoTime()}").apply { mkdirs() }
    val samplePdf = SamplePdfGenerator.createSamplePdf(app, SamplePdfKind.TICKET_PASS, workDir)

    val analyzed = PdfTextAnalyzer.analyzeDocument(app, samplePdf).getOrThrow()
    assertEquals(2, analyzed.size)

    val seatSegment = analyzed[0].segments.firstOrNull { it.originalText.contains("CNF/B3/2/MIDDLE") }
    assertNotNull("Expected CNF/B3/2/MIDDLE segment in sample ticket PDF", seatSegment)
    seatSegment!!
    assertEquals(MatchedFontFamily.MONO, seatSegment.style.fontFamily)

    val editedPages = analyzed.map { page ->
      if (page.pageIndex != 0) page
      else page.copy(
        segments = page.segments.map { seg ->
          if (seg.id == seatSegment.id) seg.copy(currentText = "MIDDAA") else seg
        }
      )
    }

    val exportedFile = File(workDir, "exported_ticket.pdf")
    val verification = PdfExporter.exportAndVerify(
      context = app,
      originalPdf = samplePdf,
      pages = editedPages,
      outputFile = exportedFile,
      displayName = "exported_ticket.pdf",
    ).getOrThrow()

    assertEquals(2, verification.pageCount)
    assertEquals(1, verification.verifiedReplacementCount)
    assertTrue("Old text must be stripped cleanly from content stream", verification.oldTextRemovedCleanly)

    PDDocument.load(exportedFile).use { reopened ->
      assertEquals(2, reopened.numberOfPages)
      val text = PDFTextStripper().getText(reopened)
      assertTrue("Exported PDF must contain MIDDAA", text.contains("MIDDAA"))
      assertFalse("Exported PDF must NOT retain CNF/B3/2/MIDDLE", text.contains("CNF/B3/2/MIDDLE"))
    }
  }

  @Test
  fun `bilingual bengali and english pdf edit preserves unicode text on reopen`() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val workDir = File(app.cacheDir, "test_bengali_${System.nanoTime()}").apply { mkdirs() }
    val samplePdf = SamplePdfGenerator.createSamplePdf(app, SamplePdfKind.BILINGUAL_DOC, workDir)

    val analyzed = PdfTextAnalyzer.analyzeDocument(app, samplePdf).getOrThrow()
    val bengaliSeg = analyzed[0].segments.firstOrNull { it.originalText.contains("বাংলা") }
    assertNotNull("Expected Bengali text segment", bengaliSeg)
    bengaliSeg!!
    assertEquals(MatchedFontFamily.BENGALI, bengaliSeg.style.fontFamily)

    val editedPages = analyzed.map { page ->
      page.copy(
        segments = page.segments.map { seg ->
          if (seg.id == bengaliSeg.id) seg.copy(currentText = "নতুন বাংলা শিরোনাম") else seg
        }
      )
    }

    val exportedFile = File(workDir, "exported_bengali.pdf")
    val verification = PdfExporter.exportAndVerify(
      context = app,
      originalPdf = samplePdf,
      pages = editedPages,
      outputFile = exportedFile,
      displayName = "exported_bengali.pdf",
    ).getOrThrow()

    assertEquals(1, verification.verifiedReplacementCount)
    PDDocument.load(exportedFile).use { reopened ->
      val text = PDFTextStripper().getText(reopened)
      assertTrue(text.contains("নতুন বাংলা শিরোনাম"))
    }
  }

  @Test
  fun `background restorer classifies white, solid color, and complex gradient backgrounds`() {
    // 1. White background
    val whiteBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    whiteBmp.eraseColor(Color.WHITE)
    val whiteResult = BackgroundRestorer.analyzeRegion(whiteBmp, NormRect(0.2f, 0.2f, 0.6f, 0.4f))
    assertEquals(BackgroundMode.SOLID_WHITE, whiteResult.mode)

    // 2. Solid Mint Card background
    val mintBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    mintBmp.eraseColor(Color.rgb(209, 250, 229))
    val mintResult = BackgroundRestorer.analyzeRegion(mintBmp, NormRect(0.2f, 0.2f, 0.6f, 0.4f))
    assertEquals(BackgroundMode.SOLID_COLOR, mintResult.mode)
    assertEquals(Color.rgb(209, 250, 229), mintResult.fillColorArgb)

    // 3. Complex Gradient background (explicit per-pixel gradient for deterministic JVM Robolectric)
    val gradBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    for (y in 0 until 200) {
      for (x in 0 until 200) {
        val t = (x + y) / 398f
        val r = (15 + t * (240 - 15)).toInt()
        val g = (118 + t * (253 - 118)).toInt()
        val b = (110 + t * (244 - 110)).toInt()
        gradBmp.setPixel(x, y, Color.rgb(r, g, b))
      }
    }
    val gradResult = BackgroundRestorer.analyzeRegion(gradBmp, NormRect(0.2f, 0.2f, 0.8f, 0.5f))
    assertEquals(BackgroundMode.COMPLEX_INTERPOLATED, gradResult.mode)
    assertNotNull(gradResult.patchBitmap)
    assertNotNull(gradResult.confidenceNote)
  }

  @Test
  fun `rotation coordinate mapping roundtrips cleanly`() {
    val (px90, py90) = PdfEditorViewModel.displayToPdfNorm(0.2f, 0.8f, 90)
    assertEquals(0.8f, px90, 0.001f)
    assertEquals(0.8f, py90, 0.001f)

    val (px180, py180) = PdfEditorViewModel.displayToPdfNorm(0.25f, 0.75f, 180)
    assertEquals(0.75f, px180, 0.001f)
    assertEquals(0.25f, py180, 0.001f)

    val (px270, py270) = PdfEditorViewModel.displayToPdfNorm(0.2f, 0.8f, 270)
    assertEquals(0.2f, px270, 0.001f)
    assertEquals(0.2f, py270, 0.001f)
  }

  @Test
  fun `malformed non-pdf file fails gracefully without crashing`() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val badFile = File(app.cacheDir, "corrupt_${System.nanoTime()}.pdf")
    badFile.writeText("NOT A VALID PDF FILE")

    val result = PdfTextAnalyzer.analyzeDocument(app, badFile)
    assertTrue(result.isFailure)
  }
}
