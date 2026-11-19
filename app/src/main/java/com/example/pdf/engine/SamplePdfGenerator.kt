package com.example.pdf.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.example.R
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SamplePdfKind(
  val fileName: String,
  val title: String,
  val subtitle: String,
  val badge: String,
) {
  TICKET_PASS(
    fileName = "RailTicket_Reservation.pdf",
    title = "Boarding & Ticket Pass",
    subtitle = "Includes CNF/B3/2/MIDDLE seat code, colored badges & 2 pages",
    badge = "One-Tap Test",
  ),
  BILINGUAL_DOC(
    fileName = "Bilingual_Notice_BN_EN.pdf",
    title = "Bengali & English Notice",
    subtitle = "Tests Bengali script, Serif/Sans styles & multi-size typography",
    badge = "Multilingual",
  ),
  SCANNED_INVOICE(
    fileName = "Scanned_Receipt_OCR.pdf",
    title = "Scanned Invoice (OCR)",
    subtitle = "Image-only PDF page triggering on-device OCR & BG reconstruction",
    badge = "OCR Scan",
  ),
}

/**
 * Generates real, valid PDF documents on-device for instant interactive testing of
 * one-tap text editing, Bengali Unicode embedding, multi-page export, and scanned OCR.
 */
object SamplePdfGenerator {

  suspend fun createSamplePdf(
    context: Context,
    kind: SamplePdfKind,
    destDir: File,
  ): File = withContext(Dispatchers.IO) {
    PdfTextAnalyzer.ensureInitialized(context)
    destDir.mkdirs()
    val outFile = File(destDir, kind.fileName)

    when (kind) {
      SamplePdfKind.TICKET_PASS -> generateTicketPassPdf(context, outFile)
      SamplePdfKind.BILINGUAL_DOC -> generateBilingualPdf(context, outFile)
      SamplePdfKind.SCANNED_INVOICE -> generateScannedInvoicePdf(outFile)
    }
    outFile
  }

  private fun generateTicketPassPdf(context: Context, outFile: File) {
    PDDocument().use { doc ->
      val notoSans = context.resources.openRawResource(R.font.noto_sans).use {
        PDType0Font.load(doc, it, true)
      }
      val jetBrainsMono = context.resources.openRawResource(R.font.jetbrains_mono).use {
        PDType0Font.load(doc, it, true)
      }

      // Page 1: Boarding Pass with CNF/B3/2/MIDDLE
      val page1 = PDPage(PDRectangle.A4)
      doc.addPage(page1)
      val w = page1.mediaBox.width
      val h = page1.mediaBox.height

      PDPageContentStream(doc, page1).use { cs ->
        // Emerald Header Banner
        cs.setNonStrokingColor(15, 118, 110)
        cs.addRect(40f, h - 140f, w - 80f, 88f)
        cs.fill()

        drawText(cs, notoSans, 20f, Color.WHITE, 60f, h - 92f, "EXPRESS RAIL E-TICKET")
        drawText(cs, notoSans, 11f, Color.rgb(209, 250, 229), 60f, h - 116f, "Confirmed Electronic Reservation Slip")

        // Soft Mint Card
        cs.setNonStrokingColor(236, 253, 245)
        cs.addRect(40f, h - 270f, w - 80f, 110f)
        cs.fill()

        drawText(cs, notoSans, 11f, Color.rgb(71, 85, 105), 60f, h - 190f, "BOOKING STATUS / COACH / BERTH")
        drawText(cs, jetBrainsMono, 22f, Color.rgb(6, 78, 59), 60f, h - 224f, "CNF/B3/2/MIDDLE")
        drawText(cs, notoSans, 11f, Color.rgb(15, 118, 110), 60f, h - 250f, "Tap the code above to replace with MIDDAA")

        // White lower table rows
        drawText(cs, notoSans, 12f, Color.rgb(100, 116, 139), 60f, h - 320f, "PASSENGER NAME")
        drawText(cs, notoSans, 16f, Color.rgb(15, 23, 42), 60f, h - 344f, "Aarav Rahman")

        drawText(cs, notoSans, 12f, Color.rgb(100, 116, 139), 320f, h - 320f, "PNR NUMBER")
        drawText(cs, jetBrainsMono, 16f, Color.rgb(15, 23, 42), 320f, h - 344f, "4829105374")

        drawText(cs, notoSans, 12f, Color.rgb(100, 116, 139), 60f, h - 400f, "ORIGIN STATION")
        drawText(cs, notoSans, 15f, Color.rgb(15, 23, 42), 60f, h - 424f, "Central Terminal")

        drawText(cs, notoSans, 12f, Color.rgb(100, 116, 139), 320f, h - 400f, "DESTINATION")
        drawText(cs, notoSans, 15f, Color.rgb(15, 23, 42), 320f, h - 424f, "Green Valley Junction")

        // Colored Fare Badge
        cs.setNonStrokingColor(209, 250, 229)
        cs.addRect(60f, h - 500f, 190f, 44f)
        cs.fill()
        drawText(cs, notoSans, 14f, Color.rgb(6, 78, 59), 76f, h - 483f, "TOTAL FARE: $48.50")
      }

      // Page 2: Return Schedule
      val page2 = PDPage(PDRectangle.A4)
      doc.addPage(page2)
      PDPageContentStream(doc, page2).use { cs ->
        drawText(cs, notoSans, 18f, Color.rgb(15, 118, 110), 60f, h - 80f, "RETURN ITINERARY & NOTES")
        drawText(cs, notoSans, 13f, Color.rgb(15, 23, 42), 60f, h - 125f, "Platform Gate: West Concourse B4")
        drawText(cs, notoSans, 13f, Color.rgb(15, 23, 42), 60f, h - 155f, "Departure Time: 08:45 AM")
        drawText(cs, notoSans, 12f, Color.rgb(71, 85, 105), 60f, h - 195f, "Please carry a valid government photo ID during boarding.")
      }

      doc.save(outFile)
    }
  }

  private fun generateBilingualPdf(context: Context, outFile: File) {
    PDDocument().use { doc ->
      val notoSans = context.resources.openRawResource(R.font.noto_sans).use {
        PDType0Font.load(doc, it, true)
      }
      val notoSerif = context.resources.openRawResource(R.font.noto_serif).use {
        PDType0Font.load(doc, it, true)
      }
      val notoBengali = context.resources.openRawResource(R.font.noto_sans_bengali).use {
        PDType0Font.load(doc, it, true)
      }

      val page = PDPage(PDRectangle.A4)
      doc.addPage(page)
      val w = page.mediaBox.width
      val h = page.mediaBox.height

      PDPageContentStream(doc, page).use { cs ->
        cs.setNonStrokingColor(6, 78, 59)
        cs.addRect(44f, h - 120f, w - 88f, 68f)
        cs.fill()

        drawText(cs, notoBengali, 18f, Color.WHITE, 64f, h - 84f, "বাংলা ও ইংরেজি নমুনা নথি")
        drawText(cs, notoSans, 11f, Color.rgb(167, 243, 208), 64f, h - 104f, "Bilingual Typography & Style Matching Test")

        drawText(cs, notoBengali, 15f, Color.rgb(15, 23, 42), 64f, h - 170f, "শিরোনাম: ডিজিটাল সেবা কেন্দ্র")
        drawText(cs, notoBengali, 13f, Color.rgb(51, 65, 85), 64f, h - 200f, "ঠিকানা: ঢাকা, বাংলাদেশ")

        drawText(cs, notoSerif, 16f, Color.rgb(15, 118, 110), 64f, h - 255f, "Executive Summary (Serif Style)")
        drawText(cs, notoSans, 12f, Color.rgb(30, 41, 59), 64f, h - 285f, "All edits preserve font style, color, and page structure.")
        drawText(cs, notoSans, 12f, Color.rgb(30, 41, 59), 64f, h - 315f, "Reference Code: DOC-2026-BN-09")
      }

      doc.save(outFile)
    }
  }

  private fun generateScannedInvoicePdf(outFile: File) {
    val bmpW = 1240
    val bmpH = 1754
    val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Subtle scanned paper gradient background (triggers complex background interpolation)
    val bgPaint = Paint().apply {
      shader = LinearGradient(
        0f,
        0f,
        bmpW.toFloat(),
        bmpH.toFloat(),
        Color.rgb(246, 249, 247),
        Color.rgb(232, 240, 236),
        Shader.TileMode.CLAMP,
      )
    }
    canvas.drawRect(0f, 0f, bmpW.toFloat(), bmpH.toFloat(), bgPaint)

    val cardPaint = Paint().apply { color = Color.rgb(220, 238, 230) }
    canvas.drawRect(90f, 130f, bmpW - 90f, 310f, cardPaint)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(6, 78, 59)
      textSize = 54f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    canvas.drawText("SCANNED OFFICE INVOICE", 130f, 225f, titlePaint)

    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 118, 110)
      textSize = 30f
      typeface = Typeface.DEFAULT
    }
    canvas.drawText("Image-Only PDF Page (Detected via OCR)", 130f, 275f, subPaint)

    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 36f
      typeface = Typeface.DEFAULT
    }
    canvas.drawText("Invoice Number: INV-9042", 130f, 440f, bodyPaint)
    canvas.drawText("Client: Horizon Studio Ltd", 130f, 520f, bodyPaint)
    canvas.drawText("Amount Due: $1,250.00", 130f, 600f, bodyPaint)
    canvas.drawText("Status: PENDING APPROVAL", 130f, 680f, bodyPaint)

    PDDocument().use { doc ->
      val page = PDPage(PDRectangle.A4)
      doc.addPage(page)
      val pdImage = LosslessFactory.createFromImage(doc, bitmap)
      PDPageContentStream(doc, page).use { cs ->
        cs.drawImage(pdImage, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
      }
      doc.save(outFile)
    }
    bitmap.recycle()
  }

  private fun drawText(
    cs: PDPageContentStream,
    font: PDType0Font,
    sizePt: Float,
    argb: Int,
    x: Float,
    y: Float,
    text: String,
  ) {
    cs.beginText()
    cs.setFont(font, sizePt)
    cs.setNonStrokingColor(Color.red(argb), Color.green(argb), Color.blue(argb))
    cs.newLineAtOffset(x, y)
    cs.showText(text)
    cs.endText()
  }
}
