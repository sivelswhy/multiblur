package com.sivelswhy.multiblur

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Result(
    /** The anonymized copy, saved in the gallery. */
    val uri: Uri,
    val name: String,
    val mime: String,
    val bytes: Long,
    /** Plates and faces found, or -1 when they weren't searched for. */
    val plates: Int,
    val faces: Int,
    /** A string resource telling the user something about the output, e.g. the sound was removed. */
    val warning: Int? = null,
)

class Callbacks(
    val progress: (Float) -> Unit,
    /** Receives a small copy of the processed frame from time to time, for a live preview. */
    val preview: (Bitmap) -> Unit,
    val isCancelled: () -> Boolean,
)

/** Anonymizes photos and videos and saves the results to the gallery (Pictures/MultiBlur, Movies/MultiBlur). */
class Processor(private val context: Context, private val detector: Detector) {
    private val resolver = context.contentResolver

    private fun outputName(source: Uri, extension: String) =
        "${displayName(context, source).substringBeforeLast('.')}_anonymized.$extension"

    fun processImage(uri: Uri, options: Options, replacement: Bitmap?, callbacks: Callbacks): Result {
        val image = decodeImage(context, uri)
        val found = detector.detect(image, options.detectOptions(tiled = options.smallObjects != SmallObjects.OFF))
        val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        Masker().render(Canvas(output), image, found.detections, maskOptions(options, replacement))
        image.recycle()
        callbacks.preview(thumbnail(output))

        // Same format as the original when possible; a re-encoded bitmap carries no EXIF or GPS metadata.
        val (format, mime, extension) = when (resolver.getType(uri)) {
            "image/png" -> Triple(Bitmap.CompressFormat.PNG, "image/png", "png")
            "image/webp" -> Triple(Bitmap.CompressFormat.WEBP_LOSSY, "image/webp", "webp")
            else -> Triple(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg")
        }
        val name = outputName(uri, extension)
        val saved = save(name, mime, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Pictures/MultiBlur") { stream ->
            output.compress(format, 92, stream)
        }
        output.recycle()
        callbacks.progress(1f)
        return Result(saved.first, name, mime, saved.second, count(options.maskPlates, found.plates), count(options.maskFaces, found.faces))
    }

    /** Decodes with MediaCodec, masks each upright frame, re-encodes it as H.264 and copies the AAC
     *  sound as is. */
    fun processVideo(uri: Uri, options: Options, replacement: Bitmap?, callbacks: Callbacks): Result {
        val temp = File.createTempFile("multiblur", ".mp4", context.cacheDir)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            // Android's MP4 reader rejects some files outright, e.g. with an untagged cover image track.
            if (extractor.trackCount == 0) throw IllegalStateException(context.getString(R.string.error_unreadable_video))
            val videoTrack = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).mime.startsWith("video/") }
                ?: throw IllegalStateException(context.getString(R.string.error_no_video_track))
            val inputFormat = extractor.getTrackFormat(videoTrack)
            extractor.selectTrack(videoTrack)

            val rotation = inputFormat.int(MediaFormat.KEY_ROTATION) ?: 0
            val sourceWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val sideways = rotation == 90 || rotation == 270
            // Frames are rotated upright before detection, and written upright. Encoders want even sizes.
            val width = (if (sideways) sourceHeight else sourceWidth) and 1.inv()
            val height = (if (sideways) sourceWidth else sourceHeight) and 1.inv()
            val frameRate = inputFormat.int(MediaFormat.KEY_FRAME_RATE)?.takeIf { it > 0 } ?: 30
            val duration = inputFormat.long(MediaFormat.KEY_DURATION)?.takeIf { it > 0 } ?: 1L

            decoder = MediaCodec.createDecoderByType(inputFormat.mime).apply {
                inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                configure(inputFormat, null, null, 0)
                start()
            }
            val bitRate = inputFormat.int(MediaFormat.KEY_BIT_RATE)?.let { (it * 1.2).toInt() } ?: (width * height * frameRate / 5)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, max(bitRate, 4_000_000).coerceAtMost(60_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            val mux = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }

            // The sound is copied without re-encoding; the MP4 muxer only takes AAC from the usual codecs.
            var warning: Int? = null
            val audio = if (options.keepAudio) {
                val audioExtractor = MediaExtractor().apply { setDataSource(context, uri, null) }
                val track = (0 until audioExtractor.trackCount).firstOrNull { audioExtractor.getTrackFormat(it).mime.startsWith("audio/") }
                when {
                    track == null -> null.also { audioExtractor.release() }
                    audioExtractor.getTrackFormat(track).mime != MediaFormat.MIMETYPE_AUDIO_AAC -> {
                        warning = R.string.warning_audio_dropped
                        audioExtractor.release()
                        null
                    }
                    else -> {
                        audioExtractor.selectTrack(track)
                        val format = audioExtractor.getTrackFormat(track)
                        Triple(audioExtractor, mux.addTrack(format), format.int(MediaFormat.KEY_MAX_INPUT_SIZE) ?: (1 shl 20))
                    }
                }
            } else null

            val tracker = Tracker(max(3, (frameRate * TRACK_MEMORY).roundToInt()))
            val cuts = CutDetector()
            val masker = Masker()
            val detect = options.detectOptions(tiled = options.smallObjects == SmallObjects.ALL)
            val mask = maskOptions(options, replacement)
            val plateIDs = mutableSetOf<Int>()
            val faceIDs = mutableSetOf<Int>()
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val outputCanvas = Canvas(output)
            val pixels = IntArray(width * height)
            var lastPreview = 0L

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var videoOut = -1

            fun drainEncoder() {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, 0)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoOut = mux.addTrack(encoder.outputFormat)
                            mux.start()
                        }
                        index >= 0 -> {
                            val buffer = encoder.getOutputBuffer(index)!!
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !config && videoOut >= 0) mux.writeSampleData(videoOut, buffer, info)
                            encoder.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                                return
                            }
                        }
                        else -> return
                    }
                }
            }

            /** Hands a frame (or the end of the stream) to the encoder, draining it while it's full. */
            fun encode(timeUs: Long, endOfStream: Boolean) {
                while (true) {
                    val index = encoder.dequeueInputBuffer(TIMEOUT)
                    if (index < 0) {
                        drainEncoder()
                        continue
                    }
                    if (endOfStream) {
                        encoder.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        output.getPixels(pixels, 0, width, 0, 0, width, height)
                        writeYUV(encoder.getInputImage(index)!!, pixels, width, height)
                        encoder.queueInputBuffer(index, 0, width * height * 3 / 2, timeUs, 0)
                    }
                    return
                }
            }

            while (!encoderDone) {
                if (callbacks.isCancelled()) throw CancellationException()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                if (!decoderDone) {
                    val index = decoder.dequeueOutputBuffer(info, TIMEOUT)
                    if (index >= 0) {
                        val timeUs = info.presentationTimeUs
                        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val frame = if (info.size > 0) decoder.getOutputImage(index)?.use { readRGB(it) } else null
                        decoder.releaseOutputBuffer(index, false)
                        if (frame != null) {
                            val upright = if (rotation == 0) frame else
                                Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, false)
                                    .also { frame.recycle() }
                            val found = detector.detect(upright, detect)
                            if (cuts.isCut(upright)) tracker.reset()
                            val tracked = tracker.update(found.detections)
                            // Objects are counted once they've been actually detected in a frame.
                            for (d in tracked) if (found.detections.any { it.core === d.core }) (if (d.isFace) faceIDs else plateIDs) += d.id
                            masker.render(outputCanvas, upright, tracked, mask)
                            upright.recycle()
                            encode(timeUs, endOfStream = false)
                            callbacks.progress((timeUs.toFloat() / duration).coerceIn(0f, 1f))
                            if (SystemClock.elapsedRealtime() - lastPreview > PREVIEW_INTERVAL) {
                                lastPreview = SystemClock.elapsedRealtime()
                                callbacks.preview(thumbnail(output))
                            }
                        }
                        if (endOfStream) {
                            decoderDone = true
                            encode(timeUs, endOfStream = true)
                        }
                    }
                }
                drainEncoder()
                if (decoderDone && !encoderDone) {
                    // Wait for the encoder's last frames.
                    SystemClock.sleep(2)
                }
            }
            output.recycle()
            check(videoOut >= 0) { context.getString(R.string.error_no_frames) }

            audio?.let { (audioExtractor, track, maxSize) ->
                val buffer = ByteBuffer.allocateDirect(maxSize)
                val sample = MediaCodec.BufferInfo()
                while (true) {
                    if (callbacks.isCancelled()) throw CancellationException()
                    val size = audioExtractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    sample.set(0, size, audioExtractor.sampleTime,
                        if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(track, buffer, sample)
                    audioExtractor.advance()
                }
                audioExtractor.release()
            }
            mux.stop()
            mux.release()
            muxer = null

            val name = outputName(uri, "mp4")
            val saved = save(name, "video/mp4", MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/MultiBlur") { stream ->
                temp.inputStream().use { it.copyTo(stream) }
            }
            callbacks.progress(1f)
            return Result(saved.first, name, "video/mp4", saved.second, count(options.maskPlates, plateIDs.size), count(options.maskFaces, faceIDs.size), warning)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            runCatching { muxer?.release() }
            extractor.release()
            temp.delete()
        }
    }

    /** Writes a new gallery item; it only becomes visible once complete. Returns its URI and size. */
    private fun save(name: String, mime: String, collection: Uri, folder: String, write: (java.io.OutputStream) -> Unit): kotlin.Pair<Uri, Long> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException(context.getString(R.string.error_save))
        try {
            resolver.openOutputStream(uri)!!.use(write)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        return uri to bytes
    }

    /** -1 when that kind of object wasn't searched for. */
    private fun count(searched: Boolean, found: Int) = if (searched) found else -1

    private fun maskOptions(options: Options, replacement: Bitmap?) =
        MaskOptions(options.mode, options.mosaicSize, options.faceShape, options.drawScores, replacement)

    companion object {
        /** Video: how long a tracked object stays masked after the detector last saw it. */
        private const val TRACK_MEMORY = 0.4
        private const val PREVIEW_INTERVAL = 300L
        private const val TIMEOUT = 10_000L
        private const val MAX_PIXELS = 50_000_000L

        fun isVideo(context: Context, uri: Uri) = context.contentResolver.getType(uri)?.startsWith("video/") == true

        fun displayName(context: Context, uri: Uri): String =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "file"

        /** Decodes an image upright (EXIF orientation applied), as a software bitmap. */
        fun decodeImage(context: Context, uri: Uri, maxSide: Int = 0): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = info.size.width to info.size.height
                if (maxSide > 0 && max(w, h) > maxSide) {
                    val scale = maxSide.toFloat() / max(w, h)
                    decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                } else if (w.toLong() * h > MAX_PIXELS) {
                    // Very large photos are halved, so they fit in memory.
                    decoder.setTargetSampleSize(2)
                }
            }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }

        fun decodeThumbnail(context: Context, uri: Uri) = decodeImage(context, uri, maxSide = 320)

        fun thumbnail(bitmap: Bitmap): Bitmap {
            val scale = min(1f, 320f / max(bitmap.width, bitmap.height))
            return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
        }
    }
}

private val MediaFormat.mime get() = getString(MediaFormat.KEY_MIME) ?: ""

private fun MediaFormat.int(key: String) = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.long(key: String) = if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

/** A decoded YUV 4:2:0 frame → ARGB bitmap (BT.601, video range). */
private fun readRGB(image: Image): Bitmap {
    val crop = image.cropRect
    val width = crop.width()
    val height = crop.height()
    val (yPlane, uPlane, vPlane) = image.planes
    val y = yPlane.buffer.toBytes()
    val u = uPlane.buffer.toBytes()
    val v = vPlane.buffer.toBytes()
    val yRow = yPlane.rowStride
    val yPixel = yPlane.pixelStride
    val uvRow = uPlane.rowStride
    val uvPixel = uPlane.pixelStride
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        val sy = row + crop.top
        val yBase = sy * yRow
        val uvBase = (sy / 2) * uvRow
        for (col in 0 until width) {
            val sx = col + crop.left
            val c = ((y[yBase + sx * yPixel].toInt() and 0xFF) - 16) * 298
            val uvIndex = uvBase + (sx / 2) * uvPixel
            val d = (u[uvIndex].toInt() and 0xFF) - 128
            val e = (v[uvIndex].toInt() and 0xFF) - 128
            val r = ((c + 409 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((c + 516 * d + 128) shr 8).coerceIn(0, 255)
            pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/** ARGB pixels → the encoder's YUV 4:2:0 input image (BT.601, video range). */
private fun writeYUV(image: Image, pixels: IntArray, width: Int, height: Int) {
    val (yPlane, uPlane, vPlane) = image.planes
    val y = yPlane.buffer
    val u = uPlane.buffer
    val v = vPlane.buffer
    val yRow = yPlane.rowStride
    val yPixel = yPlane.pixelStride
    val uvRow = uPlane.rowStride
    val uvPixel = uPlane.pixelStride
    for (row in 0 until height) {
        for (col in 0 until width) {
            val p = pixels[row * width + col]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            y.put(row * yRow + col * yPixel, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
            if (row % 2 == 0 && col % 2 == 0) {
                val index = (row / 2) * uvRow + (col / 2) * uvPixel
                u.put(index, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                v.put(index, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
    }
}

private fun ByteBuffer.toBytes(): ByteArray {
    val bytes = ByteArray(remaining())
    duplicate().get(bytes)
    return bytes
}
