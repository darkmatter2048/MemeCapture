package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppColorTheme
import com.example.model.AppTheme
import com.example.model.CaptureSettings
import com.example.ui.theme.*
import com.example.viewmodel.EmojiUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: EmojiUiState,
    onUpdateSettings: (CaptureSettings) -> Unit
) {
    val settings = state.settings
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // 1. Theme Mode Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("显示模式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { themeMode ->
                        FilterChip(
                            selected = settings.theme == themeMode,
                            onClick = { onUpdateSettings(settings.copy(theme = themeMode)) },
                            label = {
                                Text(
                                    text = when (themeMode) {
                                        AppTheme.SYSTEM -> "跟随系统"
                                        AppTheme.LIGHT -> "明亮"
                                        AppTheme.DARK -> "深色"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Theme Color Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("主题配色", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AppColorTheme.entries.forEach { colorMode ->
                        val themeLabel = when (colorMode) {
                            AppColorTheme.GREEN -> "清新绿"
                            AppColorTheme.BLUE -> "天空蓝"
                            AppColorTheme.PURPLE -> "优雅紫"
                            AppColorTheme.ORANGE -> "活力橙"
                            AppColorTheme.PINK -> "樱花粉"
                        }
                        
                        val themeColor = when (colorMode) {
                            AppColorTheme.GREEN -> Color(0xFF386B01)
                            AppColorTheme.BLUE -> Color(0xFF0061A4)
                            AppColorTheme.PURPLE -> Color(0xFF6750A4)
                            AppColorTheme.ORANGE -> Color(0xFF8B5000)
                            AppColorTheme.PINK -> Color(0xFF9C4146)
                        }

                        FilterChip(
                            selected = settings.colorTheme == colorMode,
                            onClick = { onUpdateSettings(settings.copy(colorTheme = colorMode)) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(themeColor)
                                )
                            },
                            label = {
                                Text(text = themeLabel, fontSize = 12.sp)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                selectedLeadingIconColor = Color.Unspecified
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Static Format Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("截屏保存格式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("JPG", "PNG").forEach { format ->
                        FilterChip(
                            selected = settings.staticFormat == format,
                            onClick = { onUpdateSettings(settings.copy(staticFormat = format)) },
                            label = { Text(format, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
    }
}
