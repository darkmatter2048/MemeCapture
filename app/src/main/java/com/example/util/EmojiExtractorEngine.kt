package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.CaptureSettings
import com.example.model.ExtractedEmoji
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs

object EmojiExtractorEngine {

    // ── Analysis grid ───────────────────────────────────────────
    private const val GRID_W = 40
    private const val GRID_H = 40

    private const val MIN_FRAMES_FOR_ANIMATION = 6
    private const val MIN_CYCLE_FRAMES = 3

    // ── Thresholds (normalised 0…1) ─────────────────────────────
    // Motion detection: any adjacent pair above this → animated
    private const val MOTION_THRESHOLD = 0.0005f

    // Threshold for two frames to be considered "the same pose".
    // Must accommodate rendering noise (≈0.003) while rejecting a real
    // pose change (typically 0.01+).  Used in autocorrelation scoring.
    private const val MATCH_THRESHOLD = 0.007f

    // ── Online-recording safety limit ───────────────────────────
    // No hard cap on cycle length, but a generous safety net so we
    // don't loop forever on a static screen.  600 frames ≈ 40 s @ 15 fps.
    private const val SAFETY_FRAME_LIMIT = 600

    // How often we re-run autocorrelation (in frames).
    private const val CHECK_EVERY_N_FRAMES = 4

    // If no adjacent-pair motion is seen after this many frames, the
    // content is static — bail out immediately (≈2 s @ 15 fps).
    private const val STATIC_CHECK_FRAMES = 30

    // Internal holder: full-size bitmap + pre-scaled pixels for analysis
    private class Slot(val bitmap: Bitmap, val pixels: IntArray)

    // ═══════════════════════════════════════════════════════════
    //  ONLINE RECORDING + CYCLE DETECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * Records frames continuously at [fps] while detecting the minimum
     * repeating cycle **online** via progressive autocorrelation.
     *
     * Algorithm:
     *  1. Capture frames continuously into a buffer.
     *  2. Every [CHECK_EVERY_N_FRAMES] new frames, compute a **period score**
     *     for every candidate period *k* (from [MIN_CYCLE_FRAMES] up to
     *     buffer.size / 2).
     *  3. The period score for *k* is the average frame difference across ALL
     *     adjacent cycle-segments in the buffer — not just against frame 0.
     *     This makes it immune to rendering noise on any single frame.
     *  4. If the best period scores below [MATCH_THRESHOLD] in two consecutive
     *     checks → the cycle is confirmed → stop recording.
     *  5. If no cycle emerges, keep recording up to [SAFETY_FRAME_LIMIT].
     *
     * @param captureFrame  lambda that returns the next screen frame (or null
     *                      if none is available yet — null frames are skipped).
     * @param fps           target frames per second.
     * @return the confirmed cycle (animated) or all captured frames (fallback).
     */
    suspend fun recordAndExtractCycle(
        captureFrame: suspend () -> Bitmap?,
        fps: Int = 15
    ): List<Bitmap> = withContext(Dispatchers.Default) {

        val slots = mutableListOf<Slot>()
        val frameInterval = 1000L / fps
        val skipTopRows = (GRID_H * 0.10).toInt()
        val cellsPerFrame = GRID_W * (GRID_H - skipTopRows)
        val maxPossibleDiff = (cellsPerFrame * 255L * 3).toFloat()

        var lastCheckSize = 0              // buffer size at last autocorrelation run
        var lastBestPeriod = -1            // best period from previous check
        var consistentCount = 0            // how many consecutive checks agreed
        var hasSeenMotion = false          // set true when any adjacent pair differs

        var nextCaptureTime = System.currentTimeMillis()

        while (slots.size < SAFETY_FRAME_LIMIT) {

            // ── Capture one frame ───────────────────────────────
            val bitmap = captureFrame()
            if (bitmap != null) {
                val scaled = Bitmap.createScaledBitmap(bitmap, GRID_W, GRID_H, false)
                val pixels = IntArray(GRID_W * GRID_H)
                scaled.getPixels(pixels, 0, GRID_W, 0, 0, GRID_W, GRID_H)
                scaled.recycle()
                slots.add(Slot(bitmap, pixels))
            }

            // Consistent frame timing
            nextCaptureTime += frameInterval
            val sleepMs = nextCaptureTime - System.currentTimeMillis()
            if (sleepMs > 0) delay(sleepMs)

            // ── Static early-exit ───────────────────────────────
            // Compare the last two adjacent frames; if any pair shows
            // motion the content is potentially animated.
            if (!hasSeenMotion && slots.size >= 2) {
                val prev = slots[slots.size - 2].pixels
                val curr = slots[slots.size - 1].pixels
                val d = frameDiffNorm(prev, curr, skipTopRows, maxPossibleDiff)
                if (d > MOTION_THRESHOLD) hasSeenMotion = true
            }
            if (slots.size >= STATIC_CHECK_FRAMES && !hasSeenMotion) {
                // Definitely static — stop now, don't wait for safety limit
                return@withContext slots.map { it.bitmap }
            }

            // Don't even try with too little data
            if (slots.size < MIN_FRAMES_FOR_ANIMATION * 2) continue

            // Only re-run autocorrelation every N new frames to save CPU
            if (slots.size - lastCheckSize < CHECK_EVERY_N_FRAMES) continue
            lastCheckSize = slots.size

            // ── Autocorrelation scan ────────────────────────────
            val best = computeBestPeriod(slots, skipTopRows, maxPossibleDiff)

            if (best.period > 0 && best.score < MATCH_THRESHOLD) {
                if (best.period == lastBestPeriod) {
                    consistentCount++
                    if (consistentCount >= 2) {
                        // ✅ Confirmed — return one cycle
                        return@withContext slots.take(best.period).map { it.bitmap }
                    }
                } else {
                    lastBestPeriod = best.period
                    consistentCount = 1
                }
            } else {
                // No good period yet — reset tracking
                lastBestPeriod = -1
                consistentCount = 0
            }
        }

        // Safety limit reached — return everything we captured
        slots.map { it.bitmap }
    }

    // ── Autocorrelation helpers ─────────────────────────────────

    /** Result of [computeBestPeriod]. */
    private class PeriodResult(val period: Int, val score: Float)

    /**
     * Scans all candidate periods *k* ∈ [[MIN_CYCLE_FRAMES], n/2] and returns
     * the one with the lowest average frame-difference across adjacent cycles.
     *
     * For each period *k*, we chop the buffer into segments of length *k* and
     * compare every segment with the next one frame-by-frame.  The average
     * across ALL such pairs is the period score.  This uses the entire buffer
     * — not just frame 0 — so it is robust against a single noisy frame.
     */
    private fun computeBestPeriod(
        slots: List<Slot>,
        skipTopRows: Int,
        maxPossibleDiff: Float
    ): PeriodResult {
        val n = slots.size
        val maxPeriod = n / 2
        if (maxPeriod < MIN_CYCLE_FRAMES) return PeriodResult(-1, Float.MAX_VALUE)

        var bestPeriod = -1
        var bestScore = Float.MAX_VALUE

        for (k in MIN_CYCLE_FRAMES..maxPeriod) {
            // Need at least 2 full cycles worth of data to evaluate k
            if (n < 2 * k) continue

            var totalScore = 0f
            var pairs = 0

            var seg = 0
            while ((seg + 2) * k <= n) {
                val baseA = seg * k
                val baseB = (seg + 1) * k
                for (i in 0 until k) {
                    totalScore += frameDiffNorm(
                        slots[baseA + i].pixels,
                        slots[baseB + i].pixels,
                        skipTopRows, maxPossibleDiff
                    )
                    pairs++
                }
                seg++
            }

            if (pairs == 0) continue
            val avgScore = totalScore / pairs

            if (avgScore < bestScore) {
                bestScore = avgScore
                bestPeriod = k
            }
        }

        return PeriodResult(bestPeriod, bestScore)
    }

    // ═══════════════════════════════════════════════════════════
    //  ENCODING  (post-recording)
    // ═══════════════════════════════════════════════════════════

    /**
     * Encodes captured frames into either a static image or an animated GIF.
     *
     * If [rawFrames] is already a verified cycle (from [recordAndExtractCycle]),
     * [analyzeIsAnimated] will trivially confirm it as animated and
     * [detectMinimumPeriod] (fallback) will return it as-is.
     */
    suspend fun processCapture(
        context: Context,
        rawFrames: List<Bitmap>,
        settings: CaptureSettings = CaptureSettings(),
        sourceApp: String = "截屏"
    ): ExtractedEmoji = withContext(Dispatchers.IO) {
        val sampleFrame = rawFrames.firstOrNull()
            ?: throw IllegalArgumentException("Frame sequence cannot be empty")

        val isAnimated = analyzeIsAnimated(rawFrames)

        val outputDir = File(context.filesDir, "extracted_emojis").apply {
            if (!exists()) mkdirs()
        }
        val id = UUID.randomUUID().toString()

        // ── Auto-crop emoji from chat background ─────────────────
        val cropBounds = EmojiCropper.findEmojiBounds(sampleFrame)
        val cropFrame: (Bitmap) -> Bitmap = { bmp ->
            if (cropBounds != null) EmojiCropper.cropToBounds(bmp, cropBounds) else bmp
        }

        if (!isAnimated || rawFrames.size < MIN_FRAMES_FOR_ANIMATION) {
            val format = settings.staticFormat
            val ext = if (format == "PNG") "png" else "jpg"
            val file = File(outputDir, "capture_$id.$ext")

            val outFrame = cropFrame(sampleFrame)
            FileOutputStream(file).use { out ->
                if (format == "PNG") {
                    outFrame.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    outFrame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }

            ExtractedEmoji(
                id = id,
                title = "截图_${System.currentTimeMillis() % 10000}",
                filePath = file.absolutePath,
                thumbnailPath = file.absolutePath,
                isAnimated = false,
                format = format,
                width = outFrame.width,
                height = outFrame.height,
                frameCount = 1,
                minPeriodMs = 0L,
                timestamp = System.currentTimeMillis(),
                sourceApp = sourceApp,
                sizeBytes = file.length()
            )
        } else {
            val (minCycleFrames, minPeriodMs) = detectMinimumPeriod(rawFrames)
            val croppedFrames = minCycleFrames.map { cropFrame(it) }

            val file = File(outputDir, "capture_$id.gif")
            val gifEncoder = GifEncoder()
            gifEncoder.setDelay(1000 / 15)
            gifEncoder.start(file.absolutePath)
            gifEncoder.buildPalette(croppedFrames)
            for (frame in croppedFrames) {
                gifEncoder.addFrame(frame)
            }
            gifEncoder.finish()

            val thumbFile = File(outputDir, "thumb_$id.jpg")
            FileOutputStream(thumbFile).use { out ->
                croppedFrames.first().compress(Bitmap.CompressFormat.JPEG, 60, out)
            }

            ExtractedEmoji(
                id = id,
                title = "录制_${System.currentTimeMillis() % 10000}",
                filePath = file.absolutePath,
                thumbnailPath = thumbFile.absolutePath,
                isAnimated = true,
                format = "GIF",
                width = croppedFrames.first().width,
                height = croppedFrames.first().height,
                frameCount = croppedFrames.size,
                minPeriodMs = minPeriodMs,
                timestamp = System.currentTimeMillis(),
                sourceApp = sourceApp,
                sizeBytes = file.length()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  FALLBACK: batch analysis (used when online detection fails)
    // ═══════════════════════════════════════════════════════════

    /**
     * Public entry point for quick animation detection.
     * Captures a short sample and returns true if the content appears animated.
     */
    fun isAnimated(frames: List<Bitmap>): Boolean = analyzeIsAnimated(frames)

    private fun analyzeIsAnimated(frames: List<Bitmap>): Boolean {
        if (frames.size < MIN_FRAMES_FOR_ANIMATION) return false

        val skipTopRows = (GRID_H * 0.10).toInt()
        if (skipTopRows >= GRID_H) return false

        val scaled = frames.map {
            Bitmap.createScaledBitmap(it, GRID_W, GRID_H, false)
        }

        var anyMotion = false

        for (pairIdx in 0 until frames.size - 1) {
            val p1 = IntArray(GRID_W * GRID_H)
            val p2 = IntArray(GRID_W * GRID_H)
            scaled[pairIdx].getPixels(p1, 0, GRID_W, 0, 0, GRID_W, GRID_H)
            scaled[pairIdx + 1].getPixels(p2, 0, GRID_W, 0, 0, GRID_W, GRID_H)

            var totalDiff = 0L
            var cells = 0

            for (y in skipTopRows until GRID_H) {
                for (x in 0 until GRID_W) {
                    val idx = y * GRID_W + x
                    totalDiff += cellDiff(p1[idx], p2[idx])
                    cells++
                }
            }

            val normDiff = totalDiff.toFloat() / (cells * 255L * 3)
            if (normDiff > MOTION_THRESHOLD) {
                anyMotion = true
                break
            }
        }

        scaled.forEach { it.recycle() }
        return anyMotion
    }

    /**
     * Fallback cycle detection — used when [recordAndExtractCycle] hit the
     * safety limit without confirming a cycle.
     *
     * Reuses the same autocorrelation logic: pre-compute pixel arrays, run
     * [computeBestPeriod], and return the best cycle.
     */
    private fun detectMinimumPeriod(frames: List<Bitmap>): Pair<List<Bitmap>, Long> {
        if (frames.size <= MIN_CYCLE_FRAMES) {
            return Pair(frames, (frames.size * 67L))
        }

        val skipTopRows = (GRID_H * 0.10).toInt()
        val cellsPerFrame = GRID_W * (GRID_H - skipTopRows)
        val maxPossibleDiff = (cellsPerFrame * 255L * 3).toFloat()

        // Build Slot list from bitmap frames
        val slots = frames.map { bitmap ->
            val scaled = Bitmap.createScaledBitmap(bitmap, GRID_W, GRID_H, false)
            val pixels = IntArray(GRID_W * GRID_H)
            scaled.getPixels(pixels, 0, GRID_W, 0, 0, GRID_W, GRID_H)
            scaled.recycle()
            Slot(bitmap, pixels)
        }

        val best = computeBestPeriod(slots, skipTopRows, maxPossibleDiff)

        return if (best.period > 0 && best.score < MATCH_THRESHOLD * 2f) {
            Pair(frames.subList(0, best.period), (best.period * 67L))
        } else {
            Pair(frames, (frames.size * 67L))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  PIXEL HELPERS
    // ═══════════════════════════════════════════════════════════

    private fun cellDiff(c1: Int, c2: Int): Int =
        abs(Color.red(c1) - Color.red(c2)) +
        abs(Color.green(c1) - Color.green(c2)) +
        abs(Color.blue(c1) - Color.blue(c2))

    /**
     * Normalised per-cell RGB difference between two 40×40 pixel arrays.
     * Returns a value in [0, 1] where 0 = identical, 1 = maximally different.
     */
    private fun frameDiffNorm(
        p1: IntArray,
        p2: IntArray,
        skipTopRows: Int,
        maxPossibleDiff: Float
    ): Float {
        var totalDiff = 0L
        for (y in skipTopRows until GRID_H) {
            val rowStart = y * GRID_W
            for (x in 0 until GRID_W) {
                val idx = rowStart + x
                totalDiff += cellDiff(p1[idx], p2[idx])
            }
        }
        return totalDiff.toFloat() / maxPossibleDiff
    }
}
