package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "extracted_emojis")
data class ExtractedEmoji(
    @PrimaryKey val id: String,
    val title: String,
    val filePath: String,
    val thumbnailPath: String = "",
    val isAnimated: Boolean,
    val format: String, // "JPG", "PNG", "GIF", "WEBP"
    val width: Int,
    val height: Int,
    val frameCount: Int = 1,
    val minPeriodMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceApp: String = "微信", // "微信", "QQ", "手动框选", "测试模拟"
    val sizeBytes: Long = 0L,
    val backgroundColor: Int? = null // 提取时记录的背景色（色度抠图用），旧数据可能为 null
)

enum class CaptureMode {
    AUTO,
    MANUAL
}

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AppColorTheme {
    GREEN,
    BLUE,
    PURPLE,
    ORANGE,
    PINK
}

data class CaptureSettings(
    val staticFormat: String = "JPG", // "JPG" or "PNG"
    val theme: AppTheme = AppTheme.SYSTEM,
    val colorTheme: AppColorTheme = AppColorTheme.GREEN
)
