package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.model.ExtractedEmoji
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

enum class UpscaleMode {
    SUPER_RES_4X,   // 超分四倍（逻辑待实现）
    NORMAL,         // 普通放大
    NONE            // 不放大
}

data class DownloadOptions(
    val chromaKey: Boolean = false,
    val upscaleMode: UpscaleMode = UpscaleMode.NORMAL,
    val upscaleMultiplier: Float = 4f
)

object EmojiDownloadProcessor {

    // 色度抠图的颜色容差（0-255，越大越激进地去除背景）
    private const val CHROMA_TOLERANCE = 30

    /**
     * 按 [options] 处理表情并保存到系统下载目录。
     * 未选中任何处理时直接复制原文件；处理后会清理临时文件。
     */
    suspend fun download(
        context: Context,
        emoji: ExtractedEmoji,
        options: DownloadOptions,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val processed = process(context, emoji, options, onProgress)
            saveToDownloads(context, processed, emoji, options)
            if (processed.absolutePath != emoji.filePath) processed.delete()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── 处理管线 ────────────────────────────────────────────────

    private fun process(
        context: Context,
        emoji: ExtractedEmoji,
        options: DownloadOptions,
        onProgress: ((done: Int, total: Int) -> Unit)?
    ): File {
        val doChromaKey = options.chromaKey && emoji.backgroundColor != null
        val doUpscale = options.upscaleMode == UpscaleMode.NORMAL && options.upscaleMultiplier > 1f
        val doSuperRes = options.upscaleMode == UpscaleMode.SUPER_RES_4X

        if (!doChromaKey && !doUpscale && !doSuperRes) {
            return File(emoji.filePath)
        }

        val outDir = File(context.cacheDir, "download_processed").apply { if (!exists()) mkdirs() }
        val id = System.currentTimeMillis().toString()

        return if (emoji.isAnimated) {
            val frames = decodeGifFrames(File(emoji.filePath), emoji)
            if (frames.isEmpty()) return File(emoji.filePath)

            val total = frames.size
            onProgress?.invoke(0, total)
            val processedFrames = frames.mapIndexed { index, frame ->
                val out = processBitmap(context, frame, emoji, options)
                onProgress?.invoke(index + 1, total)
                out
            }
            val file = File(outDir, "download_$id.gif")
            val gifEncoder = GifEncoder()
            gifEncoder.setDelay(1000 / 15)
            gifEncoder.start(file.absolutePath)
            gifEncoder.buildPalette(processedFrames)
            for (frame in processedFrames) gifEncoder.addFrame(frame)
            gifEncoder.finish()
            file
        } else {
            val src = BitmapFactory.decodeFile(emoji.filePath)
                ?: throw IllegalArgumentException("无法解码图片文件")
            onProgress?.invoke(0, 1)
            val processed = processBitmap(context, src, emoji, options)
            onProgress?.invoke(1, 1)
            val ext = outputExt(emoji, options)
            val file = File(outDir, "download_$id.$ext")
            FileOutputStream(file).use { out ->
                if (ext == "png") processed.compress(Bitmap.CompressFormat.PNG, 100, out)
                else processed.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            file
        }
    }

    private fun processBitmap(
        context: Context,
        bitmap: Bitmap,
        emoji: ExtractedEmoji,
        options: DownloadOptions
    ): Bitmap {
        var result = bitmap

        if (options.chromaKey && emoji.backgroundColor != null) {
            result = chromaKey(result, emoji.backgroundColor)
        }

        when (options.upscaleMode) {
            UpscaleMode.NORMAL -> {
                val scale = options.upscaleMultiplier.coerceIn(1f, 16f)
                if (scale > 1f) {
                    result = Bitmap.createScaledBitmap(
                        result,
                        (result.width * scale).toInt().coerceAtLeast(1),
                        (result.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                }
            }
            UpscaleMode.SUPER_RES_4X -> {
                result = RealESRGANUpscaler.upscale(context, result)
            }
            UpscaleMode.NONE -> Unit
        }

        return result
    }

    /** 将背景色（含容差）替换为透明。 */
    private fun chromaKey(src: Bitmap, bgColor: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val br = Color.red(bgColor)
        val bg = Color.green(bgColor)
        val bb = Color.blue(bgColor)

        for (i in pixels.indices) {
            val p = pixels[i]
            if (abs(Color.red(p) - br) <= CHROMA_TOLERANCE &&
                abs(Color.green(p) - bg) <= CHROMA_TOLERANCE &&
                abs(Color.blue(p) - bb) <= CHROMA_TOLERANCE
            ) {
                pixels[i] = Color.TRANSPARENT
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** 用 android.graphics.Movie 逐帧解码 GIF。失败时回退为单帧。 */
    private fun decodeGifFrames(file: File, emoji: ExtractedEmoji): List<Bitmap> {
        val movie = Movie.decodeFile(file.absolutePath)
        if (movie == null) {
            val single = BitmapFactory.decodeFile(file.absolutePath)
            return if (single != null) listOf(single) else emptyList()
        }

        val w = movie.width().coerceAtLeast(1)
        val h = movie.height().coerceAtLeast(1)
        val count = emoji.frameCount.coerceIn(1, 500)

        var duration = movie.duration()
        if (duration <= 0) duration = (count * 67).coerceAtLeast(1)
        val frameDuration = duration / count

        val frames = mutableListOf<Bitmap>()
        for (i in 0 until count) {
            movie.setTime(i * frameDuration)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.BLACK)
            movie.draw(canvas, 0f, 0f)
            frames.add(bmp)
        }
        return frames
    }

    // ── 保存到下载目录 ──────────────────────────────────────────

    private fun outputExt(emoji: ExtractedEmoji, options: DownloadOptions): String {
        val doChromaKey = options.chromaKey && emoji.backgroundColor != null
        return when {
            emoji.isAnimated -> "gif"
            doChromaKey -> "png"
            else -> if (emoji.format == "PNG") "png" else "jpg"
        }
    }

    private fun saveToDownloads(context: Context, srcFile: File, emoji: ExtractedEmoji, options: DownloadOptions) {
        val ext = outputExt(emoji, options)
        val mimeType = when (ext) {
            "gif" -> "image/gif"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
        val displayName = "${emoji.title}.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                srcFile.inputStream().use { inp -> inp.copyTo(out) }
            } ?: throw IllegalStateException("无法写入下载文件")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, displayName)
            srcFile.copyTo(dest, overwrite = true)
        }
    }
}
