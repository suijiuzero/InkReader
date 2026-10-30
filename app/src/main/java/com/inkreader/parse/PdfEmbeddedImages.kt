package com.inkreader.parse

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap

internal object PdfEmbeddedImages {
    data class Item(val text: String, val y: Float)
    fun collect(document: PDDocument, media: EmbeddedImages, decode: Boolean): List<List<Item>> {
        val cached = IdentityHashMap<PDImage, String>()
        var placements = 0
        return document.pages.map { page ->
            val items = ArrayList<Item>()
            val engine = object : PDFGraphicsStreamEngine(page) {
                private var point = PointF()
                override fun drawImage(image: PDImage) {
                    ParseCancellation.checkpoint()
                    if (++placements > 10000) throw ParseException(ParseErrorCode.TOO_LARGE)
                    val pixels = if (image.suffix.equals("jpg", true) || image.suffix.equals("jpeg", true)) 128L * 1024 * 1024 else 64L * 1024 * 1024
                    if (image.width.toLong() * image.height > pixels) throw ParseException(ParseErrorCode.TOO_LARGE)
                    val matrix = graphicsState.currentTransformationMatrix
                    val corners = listOf(matrix.transformPoint(0f, 0f), matrix.transformPoint(0f, 1f),
                        matrix.transformPoint(1f, 0f), matrix.transformPoint(1f, 1f))
                    val crop = page.cropBox
                    fun top(p: PointF): Float {
                        val x = p.x - crop.lowerLeftX; val y = p.y - crop.lowerLeftY
                        return when ((page.rotation % 360 + 360) % 360) { 90 -> x; 180 -> y; 270 -> crop.width - x; else -> crop.height - y }
                    }
                    val token = if (!decode) "〔图片〕" else cached[image] ?: run {
                        val value = try {
                            var sample = 1
                            while (image.width / sample > 2048 || image.height / sample > 2048 ||
                                image.width.toLong() * image.height / sample / sample > 4L * 1024 * 1024) sample *= 2
                            val bitmap = image.getImage(null, sample)
                            val bytes = ByteArrayOutputStream()
                            check(bitmap.compress(if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, bytes))
                            media.add(bytes.toByteArray()) ?: "〔图片〕"
                            // PDFBox may cache this bitmap for another draw; do not recycle it here.
                        } catch (e: ParseException) { throw e }
                        catch (e: java.util.concurrent.CancellationException) { throw e }
                        catch (_: Exception) { "〔图片〕" }
                        cached[image] = value; value
                    }
                    items += Item(token, corners.minOf(::top))
                }
                override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) { point = p0 }
                override fun clip(rule: Path.FillType) = Unit
                override fun moveTo(x: Float, y: Float) { point = PointF(x, y) }
                override fun lineTo(x: Float, y: Float) { point = PointF(x, y) }
                override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { point = PointF(x3, y3) }
                override fun getCurrentPoint() = point
                override fun closePath() = Unit
                override fun endPath() = Unit
                override fun strokePath() = Unit
                override fun fillPath(rule: Path.FillType) = Unit
                override fun fillAndStrokePath(rule: Path.FillType) = Unit
                override fun shadingFill(name: COSName) = Unit
            }
            engine.processPage(page)
            items.sortedBy { it.y }
        }
    }
}
