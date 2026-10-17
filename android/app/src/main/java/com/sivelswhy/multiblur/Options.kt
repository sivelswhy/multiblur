package com.sivelswhy.multiblur

import android.content.Context

enum class SmallObjects { OFF, PHOTOS, ALL }

/** Every user-facing setting. Defaults mirror the macOS app's (and deface's where an equivalent exists). */
data class Options(
    val maskPlates: Boolean = true,
    val maskFaces: Boolean = true,
    val mode: MaskMode = MaskMode.BLUR,
    val mosaicSize: Int = 20,
    val faceShape: FaceShape = FaceShape.ELLIPSE,
    val drawScores: Boolean = false,
    val plateConfidence: Float = 0.4f,
    val faceThreshold: Float = 0.2f,
    val plateMaskScale: Float = 1.15f,
    val faceMaskScale: Float = 1.3f,
    /** 0 = full resolution (up to 2048 px), otherwise the longest side faces are analyzed at. Phones
     *  run the models on the CPU, so videos default to 1280 px. */
    val faceResolution: Int = 1280,
    val smallObjects: SmallObjects = SmallObjects.PHOTOS,
    val keepAudio: Boolean = true,
) {
    fun detectOptions(tiled: Boolean) = DetectOptions(
        plates = maskPlates,
        faces = maskFaces,
        plateConfidence = plateConfidence,
        faceThreshold = faceThreshold,
        plateMaskScale = plateMaskScale,
        faceMaskScale = faceMaskScale,
        faceMaxSide = if (faceResolution == 0) Detector.FACE_MAX_SIDE else faceResolution,
        tiled = tiled,
    )

    fun save(context: Context) {
        prefs(context).edit()
            .putBoolean("maskPlates", maskPlates)
            .putBoolean("maskFaces", maskFaces)
            .putString("mode", mode.name)
            .putInt("mosaicSize", mosaicSize)
            .putString("faceShape", faceShape.name)
            .putBoolean("drawScores", drawScores)
            .putFloat("plateConfidence", plateConfidence)
            .putFloat("faceThreshold", faceThreshold)
            .putFloat("plateMaskScale", plateMaskScale)
            .putFloat("faceMaskScale", faceMaskScale)
            .putInt("faceResolution", faceResolution)
            .putString("smallObjects", smallObjects.name)
            .putBoolean("keepAudio", keepAudio)
            .apply()
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("options", Context.MODE_PRIVATE)

        /** Saved values over the defaults, so settings added later keep their default. */
        fun load(context: Context): Options {
            val p = prefs(context)
            val d = Options()
            return Options(
                maskPlates = p.getBoolean("maskPlates", d.maskPlates),
                maskFaces = p.getBoolean("maskFaces", d.maskFaces),
                mode = runCatching { MaskMode.valueOf(p.getString("mode", null)!!) }.getOrDefault(d.mode),
                mosaicSize = p.getInt("mosaicSize", d.mosaicSize),
                faceShape = runCatching { FaceShape.valueOf(p.getString("faceShape", null)!!) }.getOrDefault(d.faceShape),
                drawScores = p.getBoolean("drawScores", d.drawScores),
                plateConfidence = p.getFloat("plateConfidence", d.plateConfidence),
                faceThreshold = p.getFloat("faceThreshold", d.faceThreshold),
                plateMaskScale = p.getFloat("plateMaskScale", d.plateMaskScale),
                faceMaskScale = p.getFloat("faceMaskScale", d.faceMaskScale),
                faceResolution = p.getInt("faceResolution", d.faceResolution),
                smallObjects = runCatching { SmallObjects.valueOf(p.getString("smallObjects", null)!!) }.getOrDefault(d.smallObjects),
                keepAudio = p.getBoolean("keepAudio", d.keepAudio),
            )
        }
    }
}
