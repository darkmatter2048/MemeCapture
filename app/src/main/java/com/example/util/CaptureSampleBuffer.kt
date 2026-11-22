package com.example.util

import android.graphics.Bitmap
import android.graphics.Rect

/** Keeps one full-size frame for saving and small frames for motion analysis. */
class CaptureSampleBuffer : AutoCloseable {
    val analysisFrames = mutableListOf<Bitmap>()
    var latestFullFrame: Bitmap? = null
        private set
    private var analysisBounds: Rect? = null

    fun add(fullFrame: Bitmap) {
        val preview = try {
            if (analysisFrames.isEmpty()) {
                analysisBounds = EmojiCropper.findFullResolutionBounds(fullFrame)?.apply {
                    val margin = maxOf(8, minOf(width(), height()) / 10)
                    inset(-margin, -margin)
                    intersect(0, 0, fullFrame.width, fullFrame.height)
                }
            }
            val cropped = analysisBounds?.let { EmojiCropper.cropToBounds(fullFrame, it) } ?: fullFrame
            val maxDim = if (analysisBounds == null) 512 else 256
            try {
                val longestSide = maxOf(cropped.width, cropped.height)
                if (longestSide <= maxDim) {
                    if (cropped === fullFrame) cropped.copy(Bitmap.Config.ARGB_8888, false) else cropped
                } else {
                    val scale = maxDim.toFloat() / longestSide
                    Bitmap.createScaledBitmap(
                        cropped,
                        (cropped.width * scale).toInt().coerceAtLeast(1),
                        (cropped.height * scale).toInt().coerceAtLeast(1),
                        true
                    ).also { if (cropped !== fullFrame) cropped.recycle() }
                }
            } catch (e: Exception) {
                if (cropped !== fullFrame) cropped.recycle()
                throw e
            }
        } catch (e: Exception) {
            fullFrame.recycle()
            throw e
        }

        latestFullFrame?.recycle()
        latestFullFrame = fullFrame
        analysisFrames.add(preview)
    }

    override fun close() {
        analysisFrames.forEach { it.recycle() }
        analysisFrames.clear()
        latestFullFrame?.recycle()
        latestFullFrame = null
        analysisBounds = null
    }
}
