package com.sivelswhy.multiblur

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class MaskMode { BLUR, MOSAIC, SOLID, IMAGE }

enum class FaceShape { ELLIPSE, BOX }

data class MaskOptions(
    val mode: MaskMode,
    val mosaicSize: Int,
    val faceShape: FaceShape,
    val drawScores: Boolean,
    /** Stretched over each area in IMAGE mode; transparent parts show a blur, never the original. */
    val replacement: Bitmap?,
)

/** Draws a frame with every detection masked. Patches only sample pixels inside the masked area of the
 *  original frame, so nothing readable leaks back in. */
class Masker {
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val blocky = Paint()
    private val black = Paint().apply { color = Color.BLACK }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Draws `source` into `canvas` (same size) with `detections` masked. */
    fun render(canvas: Canvas, source: Bitmap, detections: List<Detection>, options: MaskOptions) {
        canvas.drawBitmap(source, 0f, 0f, null)
        val frame = Box(0f, 0f, source.width.toFloat(), source.height.toFloat())
        for (detection in detections) {
            val clipped = detection.rect.intersect(frame) ?: continue
            // Whole pixels, growing outwards.
            val roi = Rect(floor(clipped.x).toInt(), floor(clipped.y).toInt(), ceil(clipped.maxX).toInt(), ceil(clipped.maxY).toInt())
            if (roi.width() < 2 || roi.height() < 2) continue

            canvas.save()
            canvas.clipRect(roi)
            val r = detection.rect
            if (detection.isFace && options.faceShape == FaceShape.ELLIPSE) {
                // Ellipse inscribed in the (unclipped) detection box, like deface.
                canvas.clipPath(Path().apply { addOval(RectF(r.x, r.y, r.maxX, r.maxY), Path.Direction.CW) })
            }
            when (options.mode) {
                MaskMode.SOLID -> canvas.drawRect(roi, black)
                MaskMode.MOSAIC -> mosaic(canvas, source, roi, max(2, options.mosaicSize))
                MaskMode.IMAGE -> {
                    blur(canvas, source, roi)
                    options.replacement?.let { canvas.drawBitmap(it, null, RectF(r.x, r.y, r.maxX, r.maxY), smooth) }
                }
                MaskMode.BLUR -> blur(canvas, source, roi)
            }
            canvas.restore()
        }
        if (options.drawScores) for (detection in detections) drawScore(canvas, detection, source.width)
    }

    /** Gaussian-like blur with a radius of a fifth of the area's size, like the macOS app: computed on a
     *  copy at most 64 px wide, then stretched back, so it stays cheap and unreadable at any size. */
    private fun blur(canvas: Canvas, source: Bitmap, roi: Rect) {
        val scale = min(1f, BLUR_WORK_SIZE / max(roi.width(), roi.height()).toFloat())
        val w = max(1, (roi.width() * scale).roundToInt())
        val h = max(1, (roi.height() * scale).roundToInt())
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(source, roi, Rect(0, 0, w, h), smooth)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        // Three box blurs approximate a Gaussian of sigma ≈ radius + 0.5.
        val radius = max(1, (max(w, h) / 5f - 0.5f).roundToInt())
        repeat(3) {
            boxBlur(pixels, w, h, radius, horizontal = true)
            boxBlur(pixels, w, h, radius, horizontal = false)
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(small, null, roi, smooth)
        small.recycle()
    }

    /** Blocks of about `block` pixels (deface's --mosaicsize), stretched to fill the area exactly. */
    private fun mosaic(canvas: Canvas, source: Bitmap, roi: Rect, block: Int) {
        val w = max(1, ceil(roi.width() / block.toFloat()).toInt())
        val h = max(1, ceil(roi.height() / block.toFloat()).toInt())
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Only the area itself is sampled.
        Canvas(small).drawBitmap(source, roi, Rect(0, 0, w, h), smooth)
        canvas.drawBitmap(small, null, roi, blocky)
        small.recycle()
    }

    /** Green score label above the box, like deface's --draw-scores. */
    private fun drawScore(canvas: Canvas, detection: Detection, width: Int) {
        val r = detection.rect
        scorePaint.textSize = (r.height * 0.25f).coerceIn(12f, 48f)
        val text = "%.2f".format(detection.score)
        val x = r.x.coerceIn(0f, max(0f, width - scorePaint.measureText(text)))
        canvas.drawText(text, x, max(scorePaint.textSize, r.y - 4), scorePaint)
    }

    private companion object {
        const val BLUR_WORK_SIZE = 64
    }
}

/** One horizontal or vertical box-blur pass over ARGB pixels, edges clamped. */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val length = if (horizontal) width else height
    val lines = if (horizontal) height else width
    val window = 2 * radius + 1
    val r = IntArray(length)
    val g = IntArray(length)
    val b = IntArray(length)
    for (line in 0 until lines) {
        fun index(i: Int) = if (horizontal) line * width + i else i * width + line
        for (i in 0 until length) {
            val p = pixels[index(i)]
            r[i] = (p shr 16) and 0xFF
            g[i] = (p shr 8) and 0xFF
            b[i] = p and 0xFF
        }
        fun at(i: Int) = i.coerceIn(0, length - 1)
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -radius..radius) {
            sr += r[at(i)]
            sg += g[at(i)]
            sb += b[at(i)]
        }
        for (i in 0 until length) {
            pixels[index(i)] = Color.rgb(sr / window, sg / window, sb / window)
            val add = at(i + radius + 1)
            val remove = at(i - radius)
            sr += r[add] - r[remove]
            sg += g[add] - g[remove]
            sb += b[add] - b[remove]
        }
    }
}
