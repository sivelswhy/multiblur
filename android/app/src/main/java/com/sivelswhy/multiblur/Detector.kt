package com.sivelswhy.multiblur

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DetectOptions(
    val plates: Boolean,
    val faces: Boolean,
    val plateConfidence: Float,
    val faceThreshold: Float,
    val plateMaskScale: Float,
    val faceMaskScale: Float,
    /** Faces are analyzed at up to this many pixels per side (deface's --scale). */
    val faceMaxSide: Int,
    /** Extra passes on overlapping tiles to find small or distant objects. */
    val tiled: Boolean,
)

class DetectionResult(val detections: List<Detection>, val plates: Int, val faces: Int)

enum class ModelPhase { WAITING, LOADING, READY, FAILED }

/** Finds license plates (YOLOv11) and faces (CenterFace from deface) with ONNX Runtime, on the CPU. */
class Detector private constructor(private val env: OrtEnvironment, private val plates: OrtSession, private val faces: OrtSession) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val plateBitmap = Bitmap.createBitmap(PLATE_INPUT, PLATE_INPUT, Bitmap.Config.ARGB_8888)
    private val plateCanvas = Canvas(plateBitmap)
    private val platePixels = IntArray(PLATE_INPUT * PLATE_INPUT)
    private var faceBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    private var facePixels = IntArray(32 * 32)

    fun detect(source: Bitmap, options: DetectOptions): DetectionResult {
        val frame = Box(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val plates = if (options.plates) {
            search(source, frame, if (options.tiled) PLATE_TILE else null) { detectPlates(source, it, options.plateConfidence) }
        } else emptyList()
        // Faces are already analyzed at up to 2048 px: tiles only help larger images at full resolution.
        val faceTile = if (options.tiled && options.faceMaxSide >= FACE_MAX_SIDE) FACE_MAX_SIDE else null
        val faces = if (options.faces) {
            search(source, frame, faceTile) { detectFaces(source, it, options.faceThreshold, options.faceMaxSide) }
        } else emptyList()
        return DetectionResult(
            plates.map { Detection(it.rect.enlarged(options.plateMaskScale), it.score, false, it.rect) } +
                faces.map { Detection(it.rect.enlarged(options.faceMaskScale), it.score, true, it.rect) },
            plates.size,
            faces.size,
        )
    }

    /** Runs `detect` on the whole frame and, when `tileSize` is set and the frame is larger, on overlapping
     *  tiles too; duplicates found in several passes are merged. */
    private fun search(source: Bitmap, frame: Box, tileSize: Int?, detect: (Box) -> List<Found>): List<Found> {
        val found = detect(frame).toMutableList()
        if (tileSize == null) return found
        for (tile in tiles(source.width, source.height, tileSize)) found += detect(tile)
        return suppressDuplicates(found)
    }

    /** YOLO on a letterboxed 640 × 640 copy of `region`; boxes in frame pixels. */
    private fun detectPlates(source: Bitmap, region: Box, confidence: Float): List<Found> {
        val size = PLATE_INPUT
        val scale = size / max(region.width, region.height)
        val drawnWidth = region.width * scale
        val drawnHeight = region.height * scale
        val padX = (size - drawnWidth) / 2
        val padY = (size - drawnHeight) / 2
        plateCanvas.drawColor(Color.rgb(114, 114, 114)) // Ultralytics' letterbox color
        plateCanvas.drawBitmap(source, region.toRect(), RectF(padX, padY, padX + drawnWidth, padY + drawnHeight), paint)
        plateBitmap.getPixels(platePixels, 0, size, 0, 0, size, size)
        val input = planarRGB(platePixels, 1f / 255)

        val found = mutableListOf<Found>()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            plates.run(mapOf(plates.inputNames.first() to tensor)).use { outputs ->
                val output = outputs[0] as OnnxTensor
                val data = output.floatBuffer
                val count = output.info.shape[2].toInt()
                for (i in 0 until count) {
                    val score = data[4 * count + i]
                    if (score < confidence) continue
                    val cx = data[i]
                    val cy = data[count + i]
                    val w = data[2 * count + i]
                    val h = data[3 * count + i]
                    found += Found(
                        Box(region.x + (cx - w / 2 - padX) / scale, region.y + (cy - h / 2 - padY) / scale, w / scale, h / scale),
                        score,
                    )
                }
            }
        }
        return nonMaximumSuppression(found, PLATE_IOU)
    }

    /** CenterFace on `region`, resized so both sides are multiples of 32 like deface. Port of FaceDetector.swift. */
    private fun detectFaces(source: Bitmap, region: Box, threshold: Float, maxSide: Int): List<Found> {
        val fit = min(1f, maxSide / max(region.width, region.height))
        val width = (ceil(region.width * fit / 32).toInt() * 32)
        val height = (ceil(region.height * fit / 32).toInt() * 32)
        val scaleX = width / region.width
        val scaleY = height / region.height
        if (faceBitmap.width != width || faceBitmap.height != height) {
            faceBitmap.recycle()
            faceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            facePixels = IntArray(width * height)
        }
        Canvas(faceBitmap).drawBitmap(source, region.toRect(), RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        faceBitmap.getPixels(facePixels, 0, width, 0, 0, width, height)
        val input = planarRGB(facePixels, 1f)

        val found = mutableListOf<Found>()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
            faces.run(mapOf(faces.inputNames.first() to tensor)).use { outputs ->
                val heatmap = outputs[0] as OnnxTensor
                val heat = heatmap.floatBuffer
                val scales = (outputs[1] as OnnxTensor).floatBuffer
                val offsets = (outputs[2] as OnnxTensor).floatBuffer
                val rows = heatmap.info.shape[2].toInt()
                val cols = heatmap.info.shape[3].toInt()
                val plane = rows * cols
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        val score = heat[i]
                        if (score <= threshold) continue
                        val s0 = exp(scales[i]) * 4
                        val s1 = exp(scales[plane + i]) * 4
                        val o0 = offsets[i]
                        val o1 = offsets[plane + i]
                        val x1 = ((c + o1 + 0.5f) * 4 - s1 / 2).coerceIn(0f, width.toFloat())
                        val y1 = ((r + o0 + 0.5f) * 4 - s0 / 2).coerceIn(0f, height.toFloat())
                        val x2 = min(x1 + s1, width.toFloat())
                        val y2 = min(y1 + s0, height.toFloat())
                        found += Found(Box(region.x + x1 / scaleX, region.y + y1 / scaleY, (x2 - x1) / scaleX, (y2 - y1) / scaleY), score)
                    }
                }
            }
        }
        return nonMaximumSuppression(found, FACE_NMS)
    }

    private fun Box.toRect() = Rect(x.roundToInt(), y.roundToInt(), (x + width).roundToInt(), (y + height).roundToInt())

    /** ARGB pixels → planar RGB floats ([R…, G…, B…]), multiplied by `factor`. */
    private fun planarRGB(pixels: IntArray, factor: Float): FloatBuffer {
        val count = pixels.size
        val buffer = ByteBuffer.allocateDirect(3 * count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until count) {
            val p = pixels[i]
            buffer.put(i, ((p shr 16) and 0xFF) * factor)
            buffer.put(count + i, ((p shr 8) and 0xFF) * factor)
            buffer.put(2 * count + i, (p and 0xFF) * factor)
        }
        return buffer
    }

    companion object {
        private const val PLATE_INPUT = 640
        private const val PLATE_TILE = 960
        private const val PLATE_IOU = 0.45f
        private const val FACE_NMS = 0.3f
        /** Faces are never analyzed above this size, like the CoreML model of the macOS app. */
        const val FACE_MAX_SIDE = 2048

        /** Loads both models from the APK's assets, reporting each one's phase. */
        fun load(context: Context, onPhase: (plates: Boolean, phase: ModelPhase) -> Unit): Detector {
            val env = OrtEnvironment.getEnvironment()
            // Fully offline: no usage events (the app has no Internet permission anyway).
            runCatching { env.setTelemetry(false) }
            fun session(name: String, plates: Boolean): OrtSession {
                onPhase(plates, ModelPhase.LOADING)
                try {
                    val bytes = context.assets.open("models/$name").use { it.readBytes() }
                    val options = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    return env.createSession(bytes, options).also { onPhase(plates, ModelPhase.READY) }
                } catch (error: Throwable) {
                    onPhase(plates, ModelPhase.FAILED)
                    throw error
                }
            }
            return Detector(env, session("plates.onnx", true), session("centerface.onnx", false))
        }
    }
}
