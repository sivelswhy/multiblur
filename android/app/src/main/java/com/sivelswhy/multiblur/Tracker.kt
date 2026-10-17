package com.sivelswhy.multiblur

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Follows plates and faces across video frames so each object keeps exactly one steady mask, and that
 *  mask keeps following the object for a few frames when the detector misses it. Port of Tracker.swift. */
class Tracker(private val maxMissed: Int) {
    private class Track(
        val id: Int,
        var detection: Detection,
        /** The detected box (without the mask's margin), used for matching. */
        var core: Box,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var missed: Int = 0,
        /** Frames in which the object was actually detected. */
        var hits: Int = 1,
    )

    private data class Pair(val track: Int, val detection: Int, val score: Float, val close: Boolean = true)

    private var tracks = mutableListOf<Track>()
    private var nextID = 0

    /** Forgets every track, e.g. at a cut between shots. */
    fun reset() = tracks.clear()

    /** Feeds one frame's detections; returns the areas to mask in that frame. */
    fun update(detections: List<Detection>): List<Detection> {
        // Predict where each tracked object is now.
        for (t in tracks) {
            t.detection = t.detection.copy(rect = t.detection.rect.offset(t.vx, t.vy))
            t.core = t.core.offset(t.vx, t.vy)
        }

        // Candidate pairs, compared on detected boxes (not enlarged masks): clear overlap, or, for fast
        // movers, a nearby center when there's no other candidate on either side (never between neighbors).
        val overlapping = mutableListOf<Pair>()
        val nearby = mutableListOf<Pair>()
        tracks.forEachIndexed { t, track ->
            detections.forEachIndexed { d, detection ->
                if (detection.isFace != track.detection.isFace) return@forEachIndexed
                val core = detection.core
                val overlap = track.core.iou(core)
                if (overlap >= MINIMUM_OVERLAP) {
                    overlapping += Pair(t, d, overlap)
                } else {
                    val distance = hypot(track.core.midX - core.midX, track.core.midY - core.midY)
                    val size = maxOf(track.core.width, track.core.height, core.width, core.height)
                    if (distance < size) nearby += Pair(t, d, 0.05f * (1 - distance / size), distance < 0.5f * size)
                }
            }
        }
        // Close centers (under half a face) are accepted; farther ones (up to a face) only when there's
        // no other candidate on either side, so a track never reaches over to a neighbor.
        val candidates = overlapping + nearby
        val accepted = nearby.filter { pair ->
            pair.close || (candidates.count { it.track == pair.track } == 1 && candidates.count { it.detection == pair.detection } == 1)
        }
        val matchedTracks = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<Int>()
        for (pair in (overlapping + accepted).sortedByDescending { it.score }) {
            if (pair.track in matchedTracks || pair.detection in matchedDetections) continue
            matchedTracks += pair.track
            matchedDetections += pair.detection
            follow(tracks[pair.track], detections[pair.detection])
        }

        tracks.forEachIndexed { t, track ->
            if (t in matchedTracks) return@forEachIndexed
            // Unseen: keep the size and let the motion fade out, so the mask can't fly off or zoom.
            track.missed += 1
            track.vx *= MISSED_VELOCITY_DECAY
            track.vy *= MISSED_VELOCITY_DECAY
        }
        // Objects seen only once or twice (crowds, flickering false detections) are kept briefly; well
        // established ones keep the full memory.
        tracks.removeAll { it.missed > min(maxMissed, 2 + 2 * it.hits) }
        detections.forEachIndexed { d, detection ->
            if (d !in matchedDetections) tracks += Track(nextID++, detection, detection.core)
        }
        return tracks.map { it.detection.copy(id = it.id) }
    }

    private fun follow(track: Track, detection: Detection) {
        val predicted = track.detection.rect
        val measured = detection.rect
        // The center follows the detection exactly: smoothing it makes masks lag behind moving faces.
        val cx = measured.midX
        val cy = measured.midY
        val width = max(predicted.width + SIZE_RESPONSE * (measured.width - predicted.width), measured.width * MINIMUM_COVERAGE)
        val height = max(predicted.height + SIZE_RESPONSE * (measured.height - predicted.height), measured.height * MINIMUM_COVERAGE)

        // Velocity from the motion between detections, capped to a plausible speed per frame.
        val previousX = predicted.midX - track.vx
        val previousY = predicted.midY - track.vy
        val limit = 0.3f * max(width, height)
        track.vx = (0.5f * track.vx + 0.5f * (cx - previousX)).coerceIn(-limit, limit)
        track.vy = (0.5f * track.vy + 0.5f * (cy - previousY)).coerceIn(-limit, limit)
        track.detection = detection.copy(rect = Box(cx - width / 2, cy - height / 2, width, height))
        track.core = detection.core
        track.missed = 0
        track.hits += 1
    }

    private companion object {
        /** Minimum overlap between a track's predicted box and a detection to continue the track. */
        const val MINIMUM_OVERLAP = 0.1f
        /** How much a new detection changes the mask's size: detectors' box sizes flicker from frame to frame. */
        const val SIZE_RESPONSE = 0.3f
        /** A smoothed mask is never smaller than this share of the current detection, so lag can't uncover it. */
        const val MINIMUM_COVERAGE = 0.85f
        /** Share of the velocity kept for each frame without a detection. */
        const val MISSED_VELOCITY_DECAY = 0.8f
    }
}

/** Spots hard cuts between video shots, so tracking restarts instead of handing one person's track to
 *  whoever stands at the same place in the next shot. Port of CutDetector.swift. */
class CutDetector {
    private val small = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(small)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(WIDTH * HEIGHT)
    private var previous: IntArray? = null
    private val recentChanges = ArrayDeque<Double>()

    /** Feeds the next frame; true when it starts a new shot. */
    fun isCut(frame: Bitmap): Boolean {
        canvas.drawBitmap(frame, null, Rect(0, 0, WIDTH, HEIGHT), paint)
        small.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val gray = IntArray(pixels.size) { i ->
            val p = pixels[i]
            (Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114).toInt()
        }
        val last = previous
        previous = gray
        if (last == null) return false

        var sum = 0L
        for (i in gray.indices) sum += abs(gray[i] - last[i])
        val change = sum.toDouble() / (gray.size * 255)
        val usual = if (recentChanges.isEmpty()) 0.0 else recentChanges.average()
        recentChanges.addLast(change)
        if (recentChanges.size > MEMORY) recentChanges.removeFirst()
        val cut = change > MINIMUM_CHANGE && change > SPIKE * (usual + 0.01)
        if (cut) recentChanges.clear()
        return cut
    }

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 18
        const val SPIKE = 6.0
        const val MINIMUM_CHANGE = 0.08
        const val MEMORY = 8
    }
}
