package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.model.CaptureSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EmojiCaptureResolutionTest {

    @Test
    fun smallMotionInsideEmojiRegionIsDetectedAsAnimated() {
        val frames = List(8) { index ->
            Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                val left = 40 + index
                for (y in 50 until 70) for (x in left until left + 20) setPixel(x, y, Color.BLACK)
            }
        }
        try {
            assertTrue(EmojiExtractorEngine.isAnimatedSample(frames))
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    @Test
    fun lastFullResolutionSampleCanBeSavedWithoutAnotherCapture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sample = CaptureSampleBuffer()
        val first = createScreen()
        val last = createScreen()
        try {
            sample.add(first)
            sample.add(last)
            assertTrue(first.isRecycled)
            assertEquals(2, sample.analysisFrames.size)
            assertTrue(sample.analysisFrames.all { maxOf(it.width, it.height) <= 512 })
            assertTrue(!last.isRecycled)

            val result = EmojiExtractorEngine.processCapture(context, listOf(sample.latestFullFrame!!))
            try {
                assertTrue(File(result.filePath).exists())
                assertTrue(result.width >= 280)
            } finally {
                File(result.filePath).delete()
            }
        } finally {
            sample.close()
        }
        assertTrue(last.isRecycled)
    }

    @Test
    fun fullResolutionBoundsAreMappedBackFromPreview() {
        val screen = createScreen()
        try {
            val bounds = EmojiCropper.findFullResolutionBounds(screen)!!
            assertTrue(bounds.width() >= 280)
            assertTrue(bounds.height() >= 280)
            assertTrue(bounds.left <= 390 && bounds.right >= 690)
            assertTrue(bounds.top <= 900 && bounds.bottom >= 1200)
        } finally {
            screen.recycle()
        }
    }

    @Test
    fun staticPngPreservesSourcePixelDetail() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val screen = createScreen()
        try {
            val result = EmojiExtractorEngine.processCapture(
                context,
                listOf(screen),
                CaptureSettings(staticFormat = "PNG")
            )
            try {
                val bounds = EmojiCropper.findFullResolutionBounds(screen)!!
                assertTrue(result.width >= 280)
                assertTrue(result.height >= 280)
                val output = BitmapFactory.decodeFile(result.filePath)
                try {
                    assertEquals(screen.getPixel(500, 1000), output.getPixel(500 - bounds.left, 1000 - bounds.top))
                    assertEquals(screen.getPixel(501, 1000), output.getPixel(501 - bounds.left, 1000 - bounds.top))
                } finally {
                    output.recycle()
                }
            } finally {
                File(result.filePath).delete()
            }
        } finally {
            screen.recycle()
        }
    }

    @Test
    fun animatedFramesKeepTheirCropSizeAndUseLosslessThumbnail() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val frames = List(6) { index ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index % 2 == 0) Color.RED else Color.BLUE)
            }
        }
        try {
            val result = EmojiExtractorEngine.processCapture(
                context,
                frames,
                framesAlreadyCropped = true,
                originalBackgroundColor = Color.WHITE
            )
            try {
                assertTrue(result.isAnimated)
                assertEquals(64, result.width)
                assertEquals(64, result.height)
                assertEquals(Color.WHITE, result.backgroundColor)
                assertTrue(result.thumbnailPath.endsWith(".png"))
                val thumbnail = BitmapFactory.decodeFile(result.thumbnailPath)
                try {
                    assertEquals(Color.RED, thumbnail.getPixel(32, 32))
                } finally {
                    thumbnail.recycle()
                }
            } finally {
                File(result.filePath).delete()
                File(result.thumbnailPath).delete()
            }
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    @Test
    fun animatedOutputSkipsRepeatedStartupFrame() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val frameColors = listOf(colors[0], colors[0]) + colors + colors
        val frames = frameColors.map { color ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }
        try {
            val result = EmojiExtractorEngine.processCapture(
                context,
                frames,
                framesAlreadyCropped = true,
                originalBackgroundColor = Color.WHITE,
                forceAnimated = true
            )
            try {
                assertEquals(3, result.frameCount)
            } finally {
                File(result.filePath).delete()
                File(result.thumbnailPath).delete()
            }
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    private fun createScreen(): Bitmap {
        val screen = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        screen.eraseColor(Color.WHITE)
        for (y in 900 until 1200) {
            for (x in 390 until 690) {
                screen.setPixel(x, y, if ((x + y) % 2 == 0) Color.RED else Color.BLUE)
            }
        }
        return screen
    }
}
