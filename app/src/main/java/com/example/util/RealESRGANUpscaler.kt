package com.example.util

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * Real-ESRGAN 4× 超分，基于 ONNX Runtime。
 *
 * 模型：Real-ESRGAN-General-x4v3.onnx（固定 128×128 输入，512×512 输出）。
 * 因为输入尺寸固定，任意尺寸图片都通过「分块 + 拼接」处理，重叠区域做羽化
 * 融合以消除接缝。透明通道（alpha）用双线性放大单独处理，再与模型输出合成。
 *
 * 移植自 DPS3 后端 upscaler.py 的 tile-and-stitch 算法。
 */
object RealESRGANUpscaler {

    private const val MODEL_ASSET = "Real-ESRGAN-General-x4v3.onnx"
    private const val DEFAULT_TILE_SIZE = 128
    private const val DEFAULT_SCALE = 4
    private const val TILE_PAD_RATIO = 0.125f

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName = "input"
    private var tileSize = DEFAULT_TILE_SIZE
    private var scale = DEFAULT_SCALE

    private val tilePad get() = (tileSize * TILE_PAD_RATIO).toInt()

    /** 加载模型（幂等，线程安全）。 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (session != null) return

        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            setInterOpNumThreads(2)
        }
        val s = environment.createSession(modelBytes, options)

        // 从会话信息中读取固定输入/输出尺寸
        s.inputInfo.values.firstOrNull()?.info?.let { info ->
            if (info is TensorInfo && info.shape.size >= 4 && info.shape[2] > 0 && info.shape[3] > 0) {
                tileSize = info.shape[2].toInt()
            }
        }
        s.outputInfo.values.firstOrNull()?.info?.let { info ->
            if (info is TensorInfo && info.shape.size >= 4 && info.shape[2] > 0 && tileSize > 0) {
                scale = (info.shape[2].toInt() / tileSize).coerceAtLeast(1)
            }
        }
        s.inputInfo.keys.firstOrNull()?.let { inputName = it }

        session = s
        env = environment
    }

    /** 将 [src] 放大 [scale] 倍并返回新 Bitmap。 */
    fun upscale(context: Context, src: Bitmap): Bitmap {
        ensureLoaded(context)
        val s = session ?: throw IllegalStateException("Real-ESRGAN 模型未加载")
        val e = env ?: throw IllegalStateException("ONNX 环境未初始化")

        val w = src.width
        val h = src.height
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)

        var hasAlpha = false
        for (p in srcPixels) {
            if ((p ushr 24) < 255) { hasAlpha = true; break }
        }

        val stride = tileSize - tilePad
        val pads = padForTiling(h, w)
        val padTop = pads[0]
        val padBottom = pads[1]
        val padLeft = pads[2]
        val padRight = pads[3]
        val paddedW = w + padLeft + padRight
        val paddedH = h + padTop + padBottom

        // 反射填充（BORDER_REFLECT_101 等价实现）
        val padded = IntArray(paddedW * paddedH)
        for (y in 0 until paddedH) {
            val sy = reflectIndex(y - padTop, h)
            val dstRow = y * paddedW
            val srcRow = sy * w
            for (x in 0 until paddedW) {
                val sx = reflectIndex(x - padLeft, w)
                padded[dstRow + x] = srcPixels[srcRow + sx]
            }
        }

        val outW = paddedW * scale
        val outH = paddedH * scale
        val accR = FloatArray(outW * outH)
        val accG = FloatArray(outW * outH)
        val accB = FloatArray(outW * outH)
        val weight = FloatArray(outW * outH)

        val outTile = tileSize * scale
        val outPad = tilePad * scale
        val outHwTile = outTile * outTile
        val mask = buildFeatherMask(outTile, outPad)

        val tileArea = tileSize * tileSize
        val input = FloatArray(3 * tileArea)

        for (ty in 0..(paddedH - tileSize) step stride) {
            for (tx in 0..(paddedW - tileSize) step stride) {
                // 填充 NCHW [0,1] 输入
                for (yy in 0 until tileSize) {
                    val rowBase = (ty + yy) * paddedW + tx
                    for (xx in 0 until tileSize) {
                        val px = padded[rowBase + xx]
                        val idx = yy * tileSize + xx
                        input[idx] = ((px shr 16) and 0xFF) / 255f
                        input[tileArea + idx] = ((px shr 8) and 0xFF) / 255f
                        input[2 * tileArea + idx] = (px and 0xFF) / 255f
                    }
                }

                val out = runTile(s, e, input)
                val oy = ty * scale
                val ox = tx * scale
                for (yy in 0 until outTile) {
                    val srcRow = yy * outTile
                    val dstRow = (oy + yy) * outW + ox
                    for (xx in 0 until outTile) {
                        val si = srcRow + xx
                        val m = mask[si]
                        val di = dstRow + xx
                        accR[di] += out[si] * m
                        accG[di] += out[outHwTile + si] * m
                        accB[di] += out[2 * outHwTile + si] * m
                        weight[di] += m
                    }
                }
            }
        }

        // 归一化并裁剪回原始尺寸 ×scale
        val outWFinal = w * scale
        val outHFinal = h * scale
        val cropLeft = padLeft * scale
        val cropTop = padTop * scale
        val result = IntArray(outWFinal * outHFinal)
        for (y in 0 until outHFinal) {
            val srcY = cropTop + y
            for (x in 0 until outWFinal) {
                val idx = srcY * outW + (cropLeft + x)
                val wt = weight[idx]
                // 模型输出为 [0,1] 浮点，先夹紧到 [0,1] 再 ×255（对应参考实现 _postprocess）
                val r = if (wt > 0f) ((accR[idx] / wt).coerceIn(0f, 1f) * 255f).toInt() else 0
                val g = if (wt > 0f) ((accG[idx] / wt).coerceIn(0f, 1f) * 255f).toInt() else 0
                val b = if (wt > 0f) ((accB[idx] / wt).coerceIn(0f, 1f) * 255f).toInt() else 0
                result[y * outWFinal + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // 透明通道单独放大并合成
        if (hasAlpha) {
            val alphaOut = upscaleAlpha(src, outWFinal, outHFinal)
            for (i in result.indices) {
                val a = (alphaOut[i] ushr 24) and 0xFF
                result[i] = (a shl 24) or (result[i] and 0x00FFFFFF)
            }
        }

        val output = Bitmap.createBitmap(outWFinal, outHFinal, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, outWFinal, 0, 0, outWFinal, outHFinal)
        return output
    }

    // ── 单块推理 ────────────────────────────────────────────────

    private fun runTile(s: OrtSession, e: OrtEnvironment, input: FloatArray): FloatArray {
        // input 已按 NCHW（R/G/B 平面）排列，直接包装为 [1,3,H,W]
        val tensor = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, tileSize.toLong(), tileSize.toLong())
        )
        val result = s.run(mapOf(inputName to tensor))
        try {
            val outTensor = result.get(0) as OnnxTensor
            val buffer = outTensor.floatBuffer
            val outArr = FloatArray(buffer.remaining())
            buffer.get(outArr)
            return outArr
        } finally {
            result.close()
            tensor.close()
        }
    }

    // ── 分块 / 羽化辅助 ─────────────────────────────────────────

    /** 计算使 (padded - tileSize) % stride == 0 的四边填充量。 */
    private fun padForTiling(h: Int, w: Int): IntArray {
        val stride = tileSize - tilePad
        fun padded(original: Int): Int {
            var size = tileSize
            while (size < original) size += stride
            return size
        }
        val needH = padded(h)
        val needW = padded(w)
        val padH = needH - h
        val padW = needW - w
        val top = padH / 2
        val bottom = padH - top
        val left = padW / 2
        val right = padW - left
        return intArrayOf(top, bottom, left, right)
    }

    /** 反射索引（np.pad reflect / BORDER_REFLECT_101 等价）。 */
    private fun reflectIndex(i: Int, n: Int): Int {
        if (n <= 1) return 0
        val period = 2 * (n - 1)
        var idx = i % period
        if (idx < 0) idx += period
        if (idx >= n) idx = period - idx
        return idx
    }

    /** 单块输出的边缘羽化权重 mask（与参考实现 np.linspace 一致）。 */
    private fun buildFeatherMask(size: Int, fade: Int): FloatArray {
        val mask = FloatArray(size * size) { 1f }
        if (fade <= 1) return mask
        val denom = (fade - 1).toFloat()
        for (y in 0 until size) {
            var vy = 1f
            if (y < fade) vy = y / denom
            else if (y >= size - fade) vy = (size - 1 - y) / denom
            for (x in 0 until size) {
                var v = vy
                if (x < fade) v *= x / denom
                else if (x >= size - fade) v *= (size - 1 - x) / denom
                mask[y * size + x] = v
            }
        }
        return mask
    }

    /** 用双线性放大 alpha 通道。 */
    private fun upscaleAlpha(src: Bitmap, outW: Int, outH: Int): IntArray {
        val w = src.width
        val h = src.height
        val srcPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val alphaPixels = IntArray(w * h)
        for (i in srcPixels.indices) {
            val a = (srcPixels[i] ushr 24) and 0xFF
            alphaPixels[i] = (a shl 24) or 0x00FFFFFF
        }
        val alphaBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        alphaBmp.setPixels(alphaPixels, 0, w, 0, 0, w, h)
        val scaled = Bitmap.createScaledBitmap(alphaBmp, outW, outH, true)
        val out = IntArray(outW * outH)
        scaled.getPixels(out, 0, outW, 0, 0, outW, outH)
        alphaBmp.recycle()
        scaled.recycle()
        return out
    }
}
