package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Extracts the largest emoji/sticker from a chat-app screenshot.
 *
 * Ported from /media/daye/Data/Projects/MemeExtractor/main.py
 *
 * Algorithm:
 *  1. Crop edges: top 10 %, bottom 10 %, left 5 %, right 5 %
 *  2. Sample the middle pixel of the first row → background colour
 *  3. Flood-fill (BFS) all pixels within [COLOR_TOLERANCE] of that colour,
 *     starting from the middle-top → background mask
 *  4. Scan row-by-row for non-background content; group into regions
 *     separated by [MIN_GAP_ROWS] consecutive background rows
 *  5. Pick the largest region by pixel count
 *  6. Trim any remaining background-coloured edges from the crop
 */
object EmojiCropper {

    private const val COLOR_TOLERANCE = 10
    private const val MIN_GAP_ROWS = 10
    private const val MIN_PIXELS = 100
    private const val DENSITY_THRESHOLD = 0.02f

    // ── Public API ──────────────────────────────────────────────

    /**
     * Samples the chat-background colour from the top-middle pixel of the
     * edge-cropped frame.  Returns null if the bitmap is empty.
     *
     * This is the colour later used for chroma-keying when the user chooses
     * to download with the background removed.
     */
    fun detectBackgroundColor(bitmap: Bitmap): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val cropped = cropEdges(bitmap)
        return cropped.getPixel(cropped.width / 2, 0)
    }

    /**
     * Finds the bounding box of the largest emoji in [bitmap].
     * Returns null if no emoji region is detected.
     */
    fun findEmojiBounds(bitmap: Bitmap): Rect? {
        val cropped = cropEdges(bitmap)
        val w = cropped.width
        val h = cropped.height

        val borderColor = cropped.getPixel(w / 2, 0)
        val bgMask = floodFillBackground(cropped, borderColor)
        val regions = scanEmojiRegions(w, h, bgMask)

        if (regions.isEmpty()) return null

        val main = regions.maxByOrNull { it.pixelCount } ?: return null
        if (main.pixelCount < MIN_PIXELS) return null

        // Map back to original bitmap coordinates
        val topPct = (bitmap.height * 0.10).toInt()
        val leftPct = (bitmap.width * 0.05).toInt()

        val trimmed = trimBackgroundEdges(cropped, main, bgMask, borderColor)
        return Rect(
            leftPct + trimmed.left,
            topPct + trimmed.top,
            leftPct + trimmed.right + 1,
            topPct + trimmed.bottom + 1
        )
    }

    /** Applies [bounds] to [bitmap], returning a cropped copy. */
    fun cropToBounds(bitmap: Bitmap, bounds: Rect): Bitmap {
        val w = bounds.width().coerceIn(1, bitmap.width)
        val h = bounds.height().coerceIn(1, bitmap.height)
        val l = bounds.left.coerceIn(0, bitmap.width - w)
        val t = bounds.top.coerceIn(0, bitmap.height - h)
        return Bitmap.createBitmap(bitmap, l, t, w, h)
    }

    // ── Step 1: crop edges ─────────────────────────────────────

    private fun cropEdges(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val top = (h * 0.10).toInt()
        val bottom = (h * 0.90).toInt()
        val left = (w * 0.05).toInt()
        val right = (w * 0.95).toInt()
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    // ── Step 3: flood-fill background ──────────────────────────

    /**
     * BFS from (0, w/2).  Returns a BooleanArray mask where true = background.
     */
    private fun floodFillBackground(bitmap: Bitmap, borderColor: Int): BooleanArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val br = Color.red(borderColor)
        val bg = Color.green(borderColor)
        val bb = Color.blue(borderColor)

        // Pre-compute: is each pixel within tolerance of border colour?
        val isBg = BooleanArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            isBg[i] = abs(Color.red(p) - br) <= COLOR_TOLERANCE &&
                    abs(Color.green(p) - bg) <= COLOR_TOLERANCE &&
                    abs(Color.blue(p) - bb) <= COLOR_TOLERANCE
        }

        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()

        val startIdx = w / 2  // row 0, mid column
        if (isBg[startIdx]) {
            queue.addLast(startIdx)
            visited[startIdx] = true
        }

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val r = idx / w
            val c = idx % w

            // Up
            if (r > 0) { val ni = idx - w; if (isBg[ni] && !visited[ni]) { visited[ni] = true; queue.addLast(ni) } }
            // Down
            if (r < h - 1) { val ni = idx + w; if (isBg[ni] && !visited[ni]) { visited[ni] = true; queue.addLast(ni) } }
            // Left
            if (c > 0) { val ni = idx - 1; if (isBg[ni] && !visited[ni]) { visited[ni] = true; queue.addLast(ni) } }
            // Right
            if (c < w - 1) { val ni = idx + 1; if (isBg[ni] && !visited[ni]) { visited[ni] = true; queue.addLast(ni) } }
        }

        return visited
    }

    // ── Step 4: scan for emoji regions ─────────────────────────

    private data class EmojiRegion(
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val pixelCount: Int
    )

    private fun scanEmojiRegions(w: Int, h: Int, bgMask: BooleanArray): List<EmojiRegion> {
        // Pre-compute: does each row have at least one non-background pixel?
        val rowHasContent = BooleanArray(h)
        for (row in 0 until h) {
            val rowStart = row * w
            var has = false
            for (col in 0 until w) {
                if (!bgMask[rowStart + col]) { has = true; break }
            }
            rowHasContent[row] = has
        }

        // Pre-compute for column queries within a row range
        fun colHasContent(col: Int, topRow: Int, bottomRow: Int): Boolean {
            for (r in topRow..bottomRow) {
                if (!bgMask[r * w + col]) return true
            }
            return false
        }

        val regions = mutableListOf<EmojiRegion>()
        var row = 0

        while (row < h) {
            // Find top: first row with content
            var top = row
            while (top < h && !rowHasContent[top]) top++
            if (top >= h) break

            // Find bottom: scan until MIN_GAP_ROWS consecutive background rows
            var bottom = top
            var consecutiveBg = 0
            var lastContentRow = top
            while (bottom < h) {
                if (!rowHasContent[bottom]) {
                    consecutiveBg++
                    if (consecutiveBg >= MIN_GAP_ROWS) break
                } else {
                    consecutiveBg = 0
                    lastContentRow = bottom
                }
                bottom++
            }

            bottom = if (consecutiveBg >= MIN_GAP_ROWS) lastContentRow
            else min(bottom - 1, h - 1)

            if (bottom < top) { row = top + 1; continue }

            // Find left/right within [top, bottom]
            var left = 0
            var right = w - 1
            while (left < right && !colHasContent(left, top, bottom)) left++
            while (right > left && !colHasContent(right, top, bottom)) right--

            if (left <= right) {
                val count = (bottom - top + 1) * (right - left + 1)
                if (count >= MIN_PIXELS) {
                    regions.add(EmojiRegion(top, left, bottom, right, count))
                }
            }

            row = bottom + 1
        }

        return regions
    }

    // ── Step 6: trim background edges ──────────────────────────

    private fun trimBackgroundEdges(
        bitmap: Bitmap,
        region: EmojiRegion,
        bgMask: BooleanArray,
        borderColor: Int
    ): Rect {
        val w = bitmap.width
        val h = bitmap.height
        val br = Color.red(borderColor)
        val bg = Color.green(borderColor)
        val bb = Color.blue(borderColor)

        // Extract region pixels
        val rw = region.right - region.left + 1
        val rh = region.bottom - region.top + 1
        val pixels = IntArray(rw * rh)
        bitmap.getPixels(pixels, 0, rw, region.left, region.top, rw, rh)

        // Build "is content" mask in region-local coordinates
        val isContent = BooleanArray(rw * rh)
        for (i in pixels.indices) {
            val p = pixels[i]
            isContent[i] = abs(Color.red(p) - br) > COLOR_TOLERANCE ||
                    abs(Color.green(p) - bg) > COLOR_TOLERANCE ||
                    abs(Color.blue(p) - bb) > COLOR_TOLERANCE
        }

        fun rowDensity(r: Int): Float {
            val rowStart = r * rw
            var nz = 0
            for (c in 0 until rw) if (isContent[rowStart + c]) nz++
            return nz.toFloat() / rw
        }

        fun colDensity(c: Int): Float {
            var nz = 0
            for (r in 0 until rh) if (isContent[r * rw + c]) nz++
            return nz.toFloat() / rh
        }

        var top = 0
        var bottom = rh - 1
        var left = 0
        var right = rw - 1

        while (top < bottom && rowDensity(top) < DENSITY_THRESHOLD) top++
        while (bottom > top && rowDensity(bottom) < DENSITY_THRESHOLD) bottom--
        while (left < right && colDensity(left) < DENSITY_THRESHOLD) left++
        while (right > left && colDensity(right) < DENSITY_THRESHOLD) right--

        return Rect(
            region.left + left,
            region.top + top,
            region.left + right,
            region.top + bottom
        )
    }
}
