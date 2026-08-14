package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.model.ExtractedEmoji
import com.example.util.DownloadOptions
import com.example.util.UpscaleMode

@Composable
fun DownloadOptionsDialog(
    emoji: ExtractedEmoji,
    onDismiss: () -> Unit,
    onConfirm: (DownloadOptions) -> Unit
) {
    var chromaKey by remember { mutableStateOf(false) }
    var upscaleMode by remember { mutableStateOf(UpscaleMode.NORMAL) }
    var multiplierText by remember { mutableStateOf("4") }
    var menuExpanded by remember { mutableStateOf(false) }

    // 未记录背景色的旧数据无法抠图
    val chromaKeyAvailable = emoji.backgroundColor != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载选项") },
        text = {
            Column {
                // 色度抠图开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "色度抠图",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = chromaKey && chromaKeyAvailable,
                        onCheckedChange = { chromaKey = it },
                        enabled = chromaKeyAvailable
                    )
                }
                if (!chromaKeyAvailable) {
                    Text(
                        text = "该表情未记录背景色，无法抠图",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 放大方式下拉菜单
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "放大方式：${upscaleMode.label()}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("超分四倍") },
                        onClick = { upscaleMode = UpscaleMode.SUPER_RES_4X; menuExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text("普通放大") },
                        onClick = { upscaleMode = UpscaleMode.NORMAL; menuExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text("不放大") },
                        onClick = { upscaleMode = UpscaleMode.NONE; menuExpanded = false }
                    )
                }

                // 普通放大时显示倍数输入，默认 4 倍
                if (upscaleMode == UpscaleMode.NORMAL) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = multiplierText,
                        onValueChange = { new ->
                            multiplierText = new.filter { it.isDigit() || it == '.' }
                        },
                        label = { Text("放大倍数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val multiplier = multiplierText.toFloatOrNull()?.coerceIn(1f, 16f) ?: 4f
                    onConfirm(
                        DownloadOptions(
                            chromaKey = chromaKey && chromaKeyAvailable,
                            upscaleMode = upscaleMode,
                            upscaleMultiplier = multiplier
                        )
                    )
                }
            ) {
                Text("开始下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun UpscaleMode.label(): String = when (this) {
    UpscaleMode.SUPER_RES_4X -> "超分四倍"
    UpscaleMode.NORMAL -> "普通放大"
    UpscaleMode.NONE -> "不放大"
}
