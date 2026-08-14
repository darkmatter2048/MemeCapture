package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A standard-compliant GIF encoder with proper LZW compression and
 * adaptive 256-colour palette built from the actual frame data.
 */
class GifEncoder {
    private var width = 0
    private var height = 0
    private var delay = 0          // in centiseconds
    private var out: OutputStream? = null
    private var firstFrame = true

    // ── Colour palette ──────────────────────────────────────────
    // palette[rgb] = 24-bit colour (0x00RRGGBB) for index [0..255]
    private var palette: IntArray? = null
    // 32×32×32 → palette-index lookup table for fast pixel mapping
    private var lut: ByteArray? = null

    // ── LZW bit-stream state ────────────────────────────────────
    private val blockBuf = ByteArray(256)
    private var blockPos = 0
    private var bitBuf = 0
    private var bitsInBuf = 0

    fun start(path: String): Boolean {
        return try {
            out = BufferedOutputStream(FileOutputStream(path))
            writeString("GIF89a")
            true
        } catch (e: IOException) {
            false
        }
    }

    /** @param ms delay in milliseconds (will be rounded to centiseconds) */
    fun setDelay(ms: Int) {
        delay = (ms + 5) / 10
    }

    /**
     * Builds an adaptive 256-colour palette from [frames] and pre-computes
     * a 32×32×32 lookup table for fast pixel→index mapping.
     *
     * Must be called after [start] and before the first [addFrame].
     */
    fun buildPalette(frames: List<Bitmap>) {
        // ── Build 32×32×32 histogram (5-bit per channel) ────────
        val hist = IntArray(32768)  // 32^3
        for (frame in frames) {
            val w = frame.width
            val h = frame.height
            val pixels = IntArray(w * h)
            frame.getPixels(pixels, 0, w, 0, 0, w, h)
            // Sample every 4th pixel for speed（跳过透明像素，避免污染调色板）
            for (i in pixels.indices step 4) {
                val c = pixels[i]
                if (Color.alpha(c) < 128) continue
                val r = (Color.red(c) shr 3)     // 0 … 31
                val g = (Color.green(c) shr 3)
                val b = (Color.blue(c) shr 3)
                hist[(r shl 10) or (g shl 5) or b]++
            }
        }

        // ── Pick top 256 histogram buckets as palette entries ────
        data class Bucket(val idx: Int, val count: Int)

        val buckets = hist.indices
            .map { Bucket(it, hist[it]) }
            .filter { it.count > 0 }
            .sortedByDescending { it.count }
            .take(255)  // 索引 0 保留给透明色

        val pal = IntArray(256)
        pal[0] = 0  // 透明色索引（设置了透明标志后此颜色不显示）
        for ((i, b) in buckets.withIndex()) {
            val r = ((b.idx shr 10) and 0x1F) shl 3
            val g = ((b.idx shr 5) and 0x1F) shl 3
            val bl = (b.idx and 0x1F) shl 3
            pal[i + 1] = Color.rgb(r, g, bl)
        }
        // Pad remainder with black
        for (i in (buckets.size + 1) until 256) pal[i] = 0

        palette = pal

        // ── Build 32×32×32 → palette-index LUT ──────────────────
        val lookup = ByteArray(32768)
        for (i in 0 until 32768) {
            val r5 = ((i shr 10) and 0x1F) shl 3
            val g5 = ((i shr 5) and 0x1F) shl 3
            val b5 = (i and 0x1F) shl 3

            var best = 1
            var bestDist = Int.MAX_VALUE
            for (j in 1 until 256) {
                val pc = pal[j]
                val dr = r5 - Color.red(pc)
                val dg = g5 - Color.green(pc)
                val db = b5 - Color.blue(pc)
                val dist = dr * dr + dg * dg + db * db
                if (dist < bestDist) { bestDist = dist; best = j }
            }
            lookup[i] = best.toByte()
        }
        lut = lookup
    }

    fun addFrame(bitmap: Bitmap): Boolean {
        if (out == null) return false
        val lookup = lut ?: return false
        return try {
            width = bitmap.width
            height = bitmap.height

            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Map each pixel to nearest palette index via LUT
            val indexed = ByteArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                if (Color.alpha(c) < 128) {
                    indexed[i] = 0  // 透明 → 索引 0
                } else {
                    val r5 = Color.red(c) shr 3
                    val g5 = Color.green(c) shr 3
                    val b5 = Color.blue(c) shr 3
                    indexed[i] = lookup[(r5 shl 10) or (g5 shl 5) or b5]
                }
            }

            if (firstFrame) {
                writeLogicalScreenDescriptor()
                writeGlobalColorTable()
                writeNetscapeExtension()
            }

            writeGraphicControlExtension()
            writeImageDescriptor()
            writeLzwImageData(indexed)

            firstFrame = false
            true
        } catch (e: IOException) {
            false
        }
    }

    fun finish(): Boolean {
        if (out == null) return false
        return try {
            out?.write(0x3B)   // GIF trailer
            out?.flush()
            out?.close()
            out = null
            true
        } catch (e: IOException) {
            false
        }
    }

    // ── GIF structure writers ───────────────────────────────────

    private fun writeString(s: String) {
        for (ch in s) out?.write(ch.code)
    }

    private fun writeShort(value: Int) {
        out?.write(value and 0xFF)
        out?.write((value shr 8) and 0xFF)
    }

    private fun writeLogicalScreenDescriptor() {
        writeShort(width)
        writeShort(height)
        // Packed: globalColorTableFlag(1) | colorRes(3) | sort(1) | size(3)
        // 256 colours → size = 7  →  0xF7
        out?.write(0x80 or 0x70 or 7)
        out?.write(0)  // background colour index
        out?.write(0)  // pixel aspect ratio (1:1)
    }

    /** Writes the adaptive 256-entry colour palette. */
    private fun writeGlobalColorTable() {
        val pal = palette ?: return
        val table = ByteArray(256 * 3)
        for (i in 0 until 256) {
            val c = pal[i]
            table[i * 3] = Color.red(c).toByte()
            table[i * 3 + 1] = Color.green(c).toByte()
            table[i * 3 + 2] = Color.blue(c).toByte()
        }
        out?.write(table)
    }

    /** Netscape 2.0 looping extension — tells decoders to loop forever. */
    private fun writeNetscapeExtension() {
        out?.write(0x21)
        out?.write(0xFF)
        out?.write(11)
        writeString("NETSCAPE2.0")
        out?.write(3)
        out?.write(1)
        writeShort(0)  // loop forever
        out?.write(0)
    }

    private fun writeGraphicControlExtension() {
        out?.write(0x21)
        out?.write(0xF9)
        out?.write(4)
        out?.write(0x01)  // packed: bit0 = 透明色标志
        writeShort(delay)
        out?.write(0)  // 透明色索引 = 0
        out?.write(0)
    }

    private fun writeImageDescriptor() {
        out?.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        out?.write(0)  // no local colour table
    }

    // ═══════════════════════════════════════════════════════════════
    //  LZW COMPRESSION  (GIF-compliant)
    // ═══════════════════════════════════════════════════════════════

    private fun writeLzwImageData(indexedPixels: ByteArray) {
        val minCodeSize = 8
        out?.write(minCodeSize)

        val clearCode = 1 shl minCodeSize   // 256
        val endCode = clearCode + 1          // 257
        val maxTableSize = 4096

        resetBlockBuffer()

        var nextCode = endCode + 1           // 258 = first free slot
        var codeSize = minCodeSize + 1       // start at 9 bits

        writeCode(clearCode, codeSize)

        if (indexedPixels.isEmpty()) {
            writeCode(endCode, codeSize)
            flushRemainingBits()
            return
        }

        val dictPrefix = IntArray(maxTableSize)
        val dictByte = ByteArray(maxTableSize)
        val hashSize = 8192
        val hashCodes = IntArray(hashSize) { -1 }

        var prefix = indexedPixels[0].toInt() and 0xFF

        for (i in 1 until indexedPixels.size) {
            val byteVal = indexedPixels[i].toInt() and 0xFF
            val hashIdx = hashLookup(hashCodes, dictPrefix, dictByte, prefix, byteVal)
            val foundCode = if (hashIdx >= 0) hashCodes[hashIdx] else -1

            if (foundCode >= 0) {
                prefix = foundCode
            } else {
                writeCode(prefix, codeSize)

                if (nextCode < maxTableSize) {
                    dictPrefix[nextCode] = prefix
                    dictByte[nextCode] = byteVal.toByte()
                    val insertAt = if (hashIdx >= 0) hashIdx else findHashSlot(hashCodes, prefix, byteVal)
                    if (insertAt >= 0) hashCodes[insertAt] = nextCode
                    nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    writeCode(clearCode, codeSize)
                    hashCodes.fill(-1)
                    nextCode = endCode + 1
                    codeSize = minCodeSize + 1
                }

                prefix = byteVal
            }
        }

        writeCode(prefix, codeSize)
        writeCode(endCode, codeSize)
        flushRemainingBits()
    }

    // ── Hash table helpers ──────────────────────────────────────

    private fun hashLookup(
        codes: IntArray,
        prefixes: IntArray,
        bytes: ByteArray,
        prefix: Int,
        byteVal: Int
    ): Int {
        var h = ((prefix shl 8) xor byteVal) % codes.size
        if (h < 0) h += codes.size
        var probe = 0
        while (probe < codes.size) {
            val idx = (h + probe) % codes.size
            val entry = codes[idx]
            if (entry < 0) return idx
            if (prefixes[entry] == prefix && bytes[entry].toInt() and 0xFF == byteVal) {
                return idx
            }
            probe++
        }
        return -1
    }

    private fun findHashSlot(codes: IntArray, prefix: Int, byteVal: Int): Int {
        var h = ((prefix shl 8) xor byteVal) % codes.size
        if (h < 0) h += codes.size
        var probe = 0
        while (probe < codes.size) {
            val idx = (h + probe) % codes.size
            if (codes[idx] < 0) return idx
            probe++
        }
        return -1
    }

    // ── Bit-level output ────────────────────────────────────────

    private fun writeCode(code: Int, codeSize: Int) {
        var remaining = code
        var bits = codeSize
        while (bits > 0) {
            val free = 8 - bitsInBuf
            val take = minOf(bits, free)
            bitBuf = bitBuf or ((remaining and ((1 shl take) - 1)) shl bitsInBuf)
            bitsInBuf += take
            if (bitsInBuf == 8) {
                writeByteToBlock(bitBuf)
                bitBuf = 0
                bitsInBuf = 0
            }
            remaining = remaining shr take
            bits -= take
        }
    }

    private fun writeByteToBlock(b: Int) {
        blockBuf[blockPos++] = b.toByte()
        if (blockPos == 255) {
            flushBlock()
        }
    }

    private fun flushBlock() {
        if (blockPos > 0) {
            out?.write(blockPos)
            out?.write(blockBuf, 0, blockPos)
            blockPos = 0
        }
    }

    private fun flushRemainingBits() {
        if (bitsInBuf > 0) {
            writeByteToBlock(bitBuf)
            bitBuf = 0
            bitsInBuf = 0
        }
        flushBlock()
        out?.write(0)  // zero-length sub-block = end of image data
    }

    private fun resetBlockBuffer() {
        blockPos = 0
        bitBuf = 0
        bitsInBuf = 0
    }
}
