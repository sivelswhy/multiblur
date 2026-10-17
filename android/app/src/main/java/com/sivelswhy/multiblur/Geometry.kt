package com.sivelswhy.multiblur

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A box in pixels, top-left origin. */
data class Box(val x: Float, val y: Float, val width: Float, val height: Float) {
    val area get() = max(0f, width) * max(0f, height)
    val midX get() = x + width / 2
    val midY get() = y + height / 2
    val maxX get() = x + width
    val maxY get() = y + height

    fun offset(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)

    fun intersect(other: Box): Box? {
        val x0 = max(x, other.x)
        val y0 = max(y, other.y)
        val w = min(maxX, other.maxX) - x0
        val h = min(maxY, other.maxY) - y0
        return if (w > 0 && h > 0) Box(x0, y0, w, h) else null
    }

    fun iou(other: Box): Float {
        val inter = intersect(other)?.area ?: return 0f
        return inter / (area + other.area - inter)
    }

    /** deface's mask scale: each side grows by (scale - 1) × the box size. */
    fun enlarged(scale: Float): Box {
        val s = scale - 1
        return Box(x - width * s, y - height * s, width * (1 + 2 * s), height * (1 + 2 * s))
    }
}

/** A detected area, already enlarged by its mask scale. */
data class Detection(
    val rect: Box,
    val score: Float,
    val isFace: Boolean,
    /** The detected box itself, before the mask scale enlarges it. Tracking compares these: enlarged
     *  masks of faces side by side overlap, which let a track jump to the neighbor. */
    val core: Box,
    /** Track identifier in videos. */
    val id: Int = -1,
)

data class Found(val rect: Box, val score: Float)

/** Tiles of `size` overlapping by 25%; none when the image is barely larger than a tile. */
fun tiles(width: Int, height: Int, size: Int): List<Box> {
    if (max(width, height) <= size * 1.25f) return emptyList()
    val step = size * 0.75f
    fun starts(length: Int): List<Float> {
        if (length <= size) return listOf(0f)
        val count = ceil((length - size) / step).toInt() + 1
        return (0 until count).map { min(it * step, (length - size).toFloat()).roundToInt().toFloat() }
    }
    return starts(height).flatMap { y ->
        starts(width).map { x -> Box(x, y, min(size, width).toFloat(), min(size, height).toFloat()) }
    }
}

/** Keeps the best of overlapping boxes, including a partial box cut by a tile edge inside a full one. */
fun suppressDuplicates(found: List<Found>): List<Found> {
    val kept = mutableListOf<Found>()
    for (candidate in found.sortedByDescending { it.score }) {
        val duplicate = kept.any { other ->
            val inter = other.rect.intersect(candidate.rect)?.area ?: return@any false
            other.rect.iou(candidate.rect) >= 0.4f || inter / min(other.rect.area, candidate.rect.area) >= 0.7f
        }
        if (!duplicate) kept += candidate
    }
    return kept
}

/** Plain non-maximum suppression at `threshold` IoU. */
fun nonMaximumSuppression(found: List<Found>, threshold: Float): List<Found> {
    val kept = mutableListOf<Found>()
    for (box in found.sortedByDescending { it.score }) {
        if (kept.none { it.rect.iou(box.rect) >= threshold }) kept += box
    }
    return kept
}
