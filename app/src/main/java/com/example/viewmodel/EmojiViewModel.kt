package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.example.model.AppColorTheme
import com.example.model.AppTheme
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.EmojiDatabase
import com.example.model.CaptureSettings
import com.example.model.ExtractedEmoji
import com.example.service.FloatingWindowService
import com.example.util.EmojiExtractorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab {
    DASHBOARD,
    GALLERY,
    HELP,
    SETTINGS
}

data class EmojiUiState(
    val currentTab: MainTab = MainTab.DASHBOARD,
    val emojis: List<ExtractedEmoji> = emptyList(),
    val settings: CaptureSettings = CaptureSettings(),
    val isServiceRunning: Boolean = false,
    val isProcessing: Boolean = false,
    val selectedEmojiForDetail: ExtractedEmoji? = null,
    val messageToast: String? = null,
    val showOverlayPermissionDialog: Boolean = false
)

class EmojiViewModel(application: Application) : AndroidViewModel(application) {

    private val db = EmojiDatabase.getDatabase(application)
    private val dao = db.emojiDao()

    private val _uiState = MutableStateFlow(EmojiUiState())
    val uiState: StateFlow<EmojiUiState> = _uiState.asStateFlow()

    companion object {
        private val _globalSettings = MutableStateFlow(CaptureSettings())
        val globalSettings: StateFlow<CaptureSettings> = _globalSettings.asStateFlow()

        private const val PREFS_NAME = "emoji_settings"
        private const val KEY_STATIC_FORMAT = "static_format"
        private const val KEY_THEME = "theme"
        private const val KEY_COLOR_THEME = "color_theme"

        fun loadSettingsFromPrefs(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _globalSettings.value = CaptureSettings(
                staticFormat = prefs.getString(KEY_STATIC_FORMAT, "JPG") ?: "JPG",
                theme = try { AppTheme.valueOf(prefs.getString(KEY_THEME, "SYSTEM") ?: "SYSTEM") } catch (_: Exception) { AppTheme.SYSTEM },
                colorTheme = try { AppColorTheme.valueOf(prefs.getString(KEY_COLOR_THEME, "GREEN") ?: "GREEN") } catch (_: Exception) { AppColorTheme.GREEN }
            )
        }

        fun saveSettingsToPrefs(context: Context, settings: CaptureSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_STATIC_FORMAT, settings.staticFormat)
                .putString(KEY_THEME, settings.theme.name)
                .putString(KEY_COLOR_THEME, settings.colorTheme.name)
                .apply()
            _globalSettings.value = settings
        }
    }

    init {
        // Load persisted settings before anything else
        loadSettingsFromPrefs(getApplication())

        viewModelScope.launch {
            dao.getAllEmojis().collectLatest { list ->
                _uiState.value = _uiState.value.copy(emojis = list)
            }
        }

        viewModelScope.launch {
            FloatingWindowService.isRunningFlow.collectLatest { running ->
                _uiState.value = _uiState.value.copy(isServiceRunning = running)
            }
        }

        // Sync local state with global settings
        viewModelScope.launch {
            globalSettings.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _uiState.value = _uiState.value.copy(currentTab = tab)
    }

    fun openDetail(emoji: ExtractedEmoji) {
        _uiState.value = _uiState.value.copy(selectedEmojiForDetail = emoji)
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(selectedEmojiForDetail = null)
    }

    fun updateSettings(settings: CaptureSettings) {
        saveSettingsToPrefs(getApplication(), settings)
    }

    fun checkOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun refreshOverlayPermissionStatus(context: Context) {
        val hasPermission = checkOverlayPermission(context)
        _uiState.value = _uiState.value.copy(showOverlayPermissionDialog = !hasPermission)
    }

    fun dismissOverlayPermissionDialog() {
        _uiState.value = _uiState.value.copy(showOverlayPermissionDialog = false)
    }

    fun openOverlayPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun toggleService(context: Context) {
        if (!checkOverlayPermission(context)) {
            openOverlayPermissionSettings(context)
            Toast.makeText(context, "请授予“悬浮窗”权限以开启此功能", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(context, FloatingWindowService::class.java)
        if (FloatingWindowService.isRunning) {
            serviceIntent.action = FloatingWindowService.ACTION_STOP_SERVICE
            context.stopService(serviceIntent)
            _uiState.value = _uiState.value.copy(isServiceRunning = false)
        }
    }

    fun startServiceExplicitly(context: Context, resultCode: Int? = null, data: Intent? = null) {
        val serviceIntent = Intent(context, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_START_SERVICE
            if (resultCode != null && data != null) {
                putExtra(FloatingWindowService.EXTRA_RESULT_CODE, resultCode)
                putExtra(FloatingWindowService.EXTRA_DATA, data)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        _uiState.value = _uiState.value.copy(isServiceRunning = true)
    }

    /**
     * Executes Capture Engine pipeline:
     * 1. Accepts single frame or multi-frame sequence
     * 2. Saves to database & local file
     */
    fun performCapture(
        rawFrames: List<Bitmap>? = null,
        sourceApp: String = "截屏"
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)

            try {
                val frames = rawFrames ?: emptyList()
                if (frames.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isProcessing = false)
                    return@launch
                }

                val result = EmojiExtractorEngine.processCapture(
                    context = getApplication(),
                    rawFrames = frames,
                    settings = _uiState.value.settings,
                    sourceApp = sourceApp
                )

                dao.insertEmoji(result)

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    messageToast = "截屏成功！已保存为 ${result.format}"
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    messageToast = "截屏失败: ${e.localizedMessage}"
                )
            }
        }
    }

    fun deleteEmoji(emoji: ExtractedEmoji) {
        viewModelScope.launch {
            dao.deleteEmoji(emoji)
            _uiState.value = _uiState.value.copy(messageToast = "已删除表情包")
        }
    }

    fun clearAllEmojis() {
        viewModelScope.launch {
            dao.clearAll()
            _uiState.value = _uiState.value.copy(messageToast = "已清空表情包库")
        }
    }

    fun clearToastMessage() {
        _uiState.value = _uiState.value.copy(messageToast = null)
    }
}
