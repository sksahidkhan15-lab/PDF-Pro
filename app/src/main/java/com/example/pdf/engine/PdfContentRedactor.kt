package com.example.pdf.engine

import com.example.pdf.model.PdfTextSegment
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * Strips replaced original text directly from PDF content streams (and nested Form XObjects)
 * so removed text is never left behind as hidden searchable text or overlapping glyphs.
 */
object PdfContentRedactor {

  fun redactSegmentsOnPage(
    document: PDDocument,
    page: PDPage,
    editedSegments: List<PdfTextSegment>,
  ) {
    val vectorEdits = editedSegments.filter { !it.isOcrDetected && it.isEdited }
    if (vectorEdits.isEmpty()) return

    val parser = PDFStreamParser(page)
    parser.parse()
    val tokens = parser.tokens
    val rewritten = rewriteTokenList(tokens, page.resources, vectorEdits)

    val newStream = PDStream(document)
    newStream.createOutputStream(COSName.FLATE_DECODE).use { out ->
      ContentStreamWriter(out).writeTokens(rewritten)
    }
    page.setContents(newStream)

    // Also inspect any Form XObjects referenced in page resources
    redactFormXObjects(page.resources, vectorEdits, visited = HashSet())
  }

  private fun redactFormXObjects(
    resources: PDResources?,
    edits: List<PdfTextSegment>,
    visited: MutableSet<PDFormXObject>,
  ) {
    if (resources == null) return
    val xObjectNames = try {
      resources.xObjectNames
    } catch (_: Exception) {
      return
    }
    for (name in xObjectNames) {
      val xObj = try {
        resources.getXObject(name)
      } catch (_: Exception) {
        null
      }
      if (xObj is PDFormXObject && visited.add(xObj)) {
        try {
          val parser = PDFStreamParser(xObj)
          parser.parse()
          val updated = rewriteTokenList(parser.tokens, xObj.resources ?: resources, edits)
          xObj.contentStream.createOutputStream(COSName.FLATE_DECODE).use { out ->
            ContentStreamWriter(out).writeTokens(updated)
          }
          redactFormXObjects(xObj.resources, edits, visited)
        } catch (_: Exception) {
        }
      }
    }
  }

  private fun rewriteTokenList(
    tokens: List<Any>,
    resources: PDResources?,
    edits: List<PdfTextSegment>,
  ): List<Any> {
    val result = ArrayList<Any>(tokens.size)
    val operandBuffer = ArrayList<Any>(16)

    val ctmStack = ArrayDeque<Matrix>()
    var ctm = Matrix()
    var textMatrix = Matrix()
    var textLineMatrix = Matrix()
    var leading = 0f
    var activeFont: PDFont? = null
    var fontSize = 12f

    for (token in tokens) {
      if (token !is Operator) {
        operandBuffer.add(token)
        continue
      }

      val opName = token.name
      when (opName) {
        "q" -> ctmStack.addLast(ctm.clone())
        "Q" -> if (ctmStack.isNotEmpty()) ctm = ctmStack.removeLast()
        "cm" -> if (operandBuffer.size >= 6) {
          val m = matrixFromOperands(operandBuffer.takeLast(6))
          if (m != null) ctm = m.multiply(ctm)
        }
        "BT" -> {
          textMatrix = Matrix()
          textLineMatrix = Matrix()
        }
        "Tf" -> if (operandBuffer.size >= 2) {
          val fontCos = operandBuffer[operandBuffer.size - 2] as? COSName
          val sizeNum = operandBuffer[operandBuffer.size - 1] as? COSNumber
          if (sizeNum != null) fontSize = sizeNum.floatValue()
          if (fontCos != null && resources != null) {
            activeFont = try {
              resources.getFont(fontCos)
            } catch (_: Exception) {
              null
            }
          }
        }
        "TL" -> if (operandBuffer.isNotEmpty()) {
          (operandBuffer.last() as? COSNumber)?.let { leading = it.floatValue() }
        }
        "Tm" -> if (operandBuffer.size >= 6) {
          val m = matrixFromOperands(operandBuffer.takeLast(6))
          if (m != null) {
            textMatrix = m
            textLineMatrix = m.clone()
          }
        }
        "Td" -> if (operandBuffer.size >= 2) {
          val tx = (operandBuffer[operandBuffer.size - 2] as? COSNumber)?.floatValue() ?: 0f
          val ty = (operandBuffer[operandBuffer.size - 1] as? COSNumber)?.floatValue() ?: 0f
          textLineMatrix = Matrix.getTranslateInstance(tx, ty).multiply(textLineMatrix)
          textMatrix = textLineMatrix.clone()
        }
        "TD" -> if (operandBuffer.size >= 2) {
          val tx = (operandBuffer[operandBuffer.size - 2] as? COSNumber)?.floatValue() ?: 0f
          val ty = (operandBuffer[operandBuffer.size - 1] as? COSNumber)?.floatValue() ?: 0f
          leading = -ty
          textLineMatrix = Matrix.getTranslateInstance(tx, ty).multiply(textLineMatrix)
          textMatrix = textLineMatrix.clone()
        }
        "T*" -> {
          textLineMatrix = Matrix.getTranslateInstance(0f, -leading).multiply(textLineMatrix)
          textMatrix = textLineMatrix.clone()
        }
        "Tj", "'", "\"" -> {
          val strIdx = operandBuffer.indexOfLast { it is COSString }
          if (strIdx >= 0) {
            val cosStr = operandBuffer[strIdx] as COSString
            val decoded = decodeCosString(activeFont, cosStr)
            val worldMatrix = textMatrix.multiply(ctm)
            val posX = worldMatrix.translateX
            val posY = worldMatrix.translateY

            if (shouldRedactOperatorText(decoded, posX, posY, fontSize, edits)) {
              operandBuffer[strIdx] = COSString("")
            }
          }
        }
        "TJ" -> {
          val arrIdx = operandBuffer.indexOfLast { it is COSArray }
          if (arrIdx >= 0) {
            val cosArray = operandBuffer[arrIdx] as COSArray
            val combined = StringBuilder()
            for (i in 0 until cosArray.size()) {
              val item = cosArray.getObject(i)
              if (item is COSString) {
                combined.append(decodeCosString(activeFont, item))
              }
            }
            val decoded = combined.toString()
            val worldMatrix = textMatrix.multiply(ctm)
            val posX = worldMatrix.translateX
            val posY = worldMatrix.translateY

            if (shouldRedactOperatorText(decoded, posX, posY, fontSize, edits)) {
              val cleaned = COSArray()
              cleaned.add(COSString(""))
              operandBuffer[arrIdx] = cleaned
            }
          }
        }
      }

      result.addAll(operandBuffer)
      result.add(token)
      operandBuffer.clear()
    }

    if (operandBuffer.isNotEmpty()) {
      result.addAll(operandBuffer)
    }
    return result
  }

  private fun shouldRedactOperatorText(
    decodedText: String,
    posX: Float,
    posY: Float,
    fontSize: Float,
    edits: List<PdfTextSegment>,
  ): Boolean {
    val cleanOp = decodedText.trim()
    if (cleanOp.isEmpty()) return false

    for (edit in edits) {
      val orig = edit.originalText.trim()
      if (orig.isEmpty()) continue

      val bounds = edit.pdfBounds
      val padY = (edit.style.fontSizePt * 1.4f).coerceAtLeast(14f)
      val padX = (edit.style.fontSizePt * 2.5f).coerceAtLeast(24f)

      val nearVertically = abs(posY - bounds.baselineY) <= padY ||
        (posY >= bounds.yBottom - padY && posY <= bounds.yBottom + bounds.height + padY)
      val nearHorizontally =
        posX >= bounds.x - padX && posX <= bounds.x + bounds.width + padX

      val exactOrContains =
        cleanOp == orig ||
          (cleanOp.length >= 2 && orig.contains(cleanOp) && nearVertically && nearHorizontally) ||
          (orig.length >= 2 && cleanOp.contains(orig) && nearVertically)

      if (exactOrContains) return true
    }
    return false
  }

  private fun decodeCosString(font: PDFont?, cosString: COSString): String {
    if (font == null) return cosString.string ?: ""
    val bytes = cosString.bytes ?: return ""
    val sb = StringBuilder()
    val stream = ByteArrayInputStream(bytes)
    while (stream.available() > 0) {
      try {
        val code = font.readCode(stream)
        val uni = font.toUnicode(code)
        if (uni != null) {
          sb.append(uni)
        } else {
          sb.append(code.toChar())
        }
      } catch (_: Exception) {
        return cosString.string ?: sb.toString()
      }
    }
    return sb.toString()
  }

  private fun matrixFromOperands(operands: List<Any>): Matrix? {
    if (operands.size < 6) return null
    val nums = operands.map { (it as? COSNumber)?.floatValue() ?: return null }
    return Matrix(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
  }
}
