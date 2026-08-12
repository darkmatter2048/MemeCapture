package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.EmojiDatabase
import com.example.model.AppTheme
import com.example.ui.theme.MyApplicationTheme
import com.example.util.EmojiExtractorEngine
import com.example.util.ScreenCaptureManager
import com.example.viewmodel.EmojiViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var recordingOverlayView: View? = null
    private var isCapturing = false
    
    // Manual Lifecycle management for Compose in Service
    private val serviceLifecycleOwner = object : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

    companion object {
        const val ACTION_START_SERVICE = "ACTION_START_FLOATING_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_FLOATING_SERVICE"
        const val ACTION_TRIGGER_CAPTURE = "com.example.ACTION_TRIGGER_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val CHANNEL_ID = "emoji_extractor_channel"
        const val NOTIFICATION_ID = 1001

        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()

        val isRunning: Boolean get() = _isRunning.value
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        createFloatingWindow()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
        } else if (intent?.action == ACTION_START_SERVICE) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (resultCode != 0 && data != null) {
                com.example.util.ScreenCaptureManager.init(this, resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun performCaptureAndSave() {
        if (isCapturing) {
            Toast.makeText(applicationContext, "正在处理中，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        isCapturing = true
        floatingView?.visibility = View.INVISIBLE

        serviceLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(200)

            try {
                var success = false
                var message = ""

                // ── Phase 1: Quick sample (~1.5 s) to detect animation ──
                val sampleFrames = withContext(Dispatchers.Default) {
                    val frames = mutableListOf<Bitmap>()
                    val deadline = System.currentTimeMillis() + 1500
                    while (System.currentTimeMillis() < deadline) {
                        ScreenCaptureManager.captureSingle()
                            ?.let { downscaleBitmap(it) }
                            ?.let { frames.add(it) }
                        kotlinx.coroutines.delay(1000L / 15)
                    }
                    frames
                }

                val isAnimated = sampleFrames.size >= 6 &&
                        EmojiExtractorEngine.isAnimated(sampleFrames)

                if (!isAnimated) {
                    // ── Static: save directly ────────────────────
                    if (sampleFrames.isNotEmpty()) {
                        val result = EmojiExtractorEngine.processCapture(
                            context = this@FloatingWindowService,
                            rawFrames = sampleFrames,
                            settings = EmojiViewModel.globalSettings.value
                        )
                        val db = EmojiDatabase.getDatabase(this@FloatingWindowService)
                        db.emojiDao().insertEmoji(result)
                        success = true
                        message = "截屏成功！"
                    }
                } else {
                    // ── Animated: show recording overlay ─────────
                    val stopFlag = java.util.concurrent.atomic.AtomicBoolean(true)
                    showRecordingOverlay(stopFlag)

                    val recordedFrames = withContext(Dispatchers.Default) {
                        val frames = mutableListOf<Bitmap>()
                        val frameInterval = 1000L / 15
                        val deadline = System.currentTimeMillis() + 60_000
                        val maxFrames = 300  // safety: ~20 s at 15 fps
                        while (stopFlag.get() && System.currentTimeMillis() < deadline && frames.size < maxFrames) {
                            ScreenCaptureManager.captureSingle()
                                ?.let { downscaleBitmap(it) }
                                ?.let { frames.add(it) }
                            kotlinx.coroutines.delay(frameInterval)
                        }
                        frames
                    }

                    dismissRecordingOverlay()

                    if (recordedFrames.isNotEmpty()) {
                        val result = EmojiExtractorEngine.processCapture(
                            context = this@FloatingWindowService,
                            rawFrames = recordedFrames,
                            settings = EmojiViewModel.globalSettings.value
                        )
                        val db = EmojiDatabase.getDatabase(this@FloatingWindowService)
                        db.emojiDao().insertEmoji(result)
                        success = true
                        message = if (result.isAnimated) "检测到动态内容，已自动提取周期片段！" else "截屏成功！"
                    }
                }

                if (success) {
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled — don't toast, just let finally clean up
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(applicationContext, "保存失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                dismissRecordingOverlay()
                floatingView?.visibility = View.VISIBLE
                isCapturing = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Full-screen recording overlay
    // ═══════════════════════════════════════════════════════════════

    private fun showRecordingOverlay(stopFlag: java.util.concurrent.atomic.AtomicBoolean) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val composeView = ComposeView(this).apply {
            // Tree owners are required for collectAsState() and Compose lifecycle
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setContent {
                val settings by EmojiViewModel.globalSettings.collectAsState()
                MyApplicationTheme(
                    appTheme = settings.theme,
                    colorTheme = settings.colorTheme
                ) {
                    RecordingOverlay(onStop = { stopFlag.set(false) })
                }
            }
        }

        recordingOverlayView = composeView
        windowManager?.addView(composeView, lp)
    }

    private fun dismissRecordingOverlay() {
        val view = recordingOverlayView ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        recordingOverlayView = null
    }

    @Composable
    private fun RecordingOverlay(onStop: () -> Unit) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar — 6% screen height, tappable to stop recording.
            // The rest of the screen is fully transparent so the user can
            // watch the animation while recording.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.06f)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable { onStop() },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "点击此处结束录制",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    private fun createFloatingWindow() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // The root view added to WindowManager MUST have the TreeOwners set
        val root = object : FrameLayout(this) {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        initialTouchX = ev.rawX
                        initialTouchY = ev.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - initialTouchX).toInt()
                        val dy = (ev.rawY - initialTouchY).toInt()
                        
                        if (abs(dx) > 10 || abs(dy) > 10 || isDragging) {
                            isDragging = true
                            lp.x = initialX + dx
                            lp.y = initialY + dy
                            windowManager?.updateViewLayout(this, lp)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) return true
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }

        // Crucial: Set owners on the root view that is passed to WindowManager
        root.setViewTreeLifecycleOwner(serviceLifecycleOwner)
        root.setViewTreeViewModelStoreOwner(serviceLifecycleOwner)
        root.setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)

        val composeView = ComposeView(this).apply {
            setContent {
                val settings by EmojiViewModel.globalSettings.collectAsState()
                
                MyApplicationTheme(
                    appTheme = settings.theme,
                    colorTheme = settings.colorTheme
                ) {
                    FloatingCapsule(
                        onCapture = {
                            Toast.makeText(applicationContext, "正在截屏...", Toast.LENGTH_SHORT).show()
                            performCaptureAndSave()
                        },
                        onClose = { stopSelf() }
                    )
                }
            }
        }

        root.addView(composeView)
        floatingView = root

        try {
            windowManager?.addView(floatingView, lp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Scales [bitmap] down to at most 512 px on the longest side, keeping
     * aspect ratio.  Original is recycled.  This keeps memory usage bounded
     * during recording — full-screen RGBA captures can be 10+ MB each.
     */
    private fun downscaleBitmap(bitmap: Bitmap): Bitmap {
        val maxDim = 512
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap

        val scale = maxDim.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    @Composable
    private fun FloatingCapsule(
        onCapture: () -> Unit,
        onClose: () -> Unit
    ) {
        Surface(
            modifier = Modifier.clip(CircleShape),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = onCapture,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Crop,
                        contentDescription = "提取",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "表情包提取悬浮服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持表情包提取悬浮窗运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("表情包提取器运行中")
            .setContentText("悬浮窗服务已开启，点击提取图标捕获表情")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenCaptureManager.stop()
        serviceLifecycleOwner.onDestroy()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
        }
        if (recordingOverlayView != null) {
            windowManager?.removeView(recordingOverlayView)
        }
        _isRunning.value = false
    }
}
