package com.example.ui.components

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun ManualSelectionOverlay(
    onConfirmCrop: (Rect) -> Unit,
    onCancel: () -> Unit
) {
    var offsetX by remember { mutableStateOf(100f) }
    var offsetY by remember { mutableStateOf(300f) }
    var boxWidth by remember { mutableStateOf(500f) }
    var boxHeight by remember { mutableStateOf(500f) }

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
    ) {
        // Semi-transparent cutout canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw dimmed background with cutout
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                size = size
            )

            // Draw selection box cutout (transparent inside)
            drawRect(
                color = Color.Transparent,
                topLeft = Offset(offsetX, offsetY),
                size = Size(boxWidth, boxHeight),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
            )

            // Draw dashed border around selection box
            drawRect(
                color = primaryColor,
                topLeft = Offset(offsetX, offsetY),
                size = Size(boxWidth, boxHeight),
                style = Stroke(
                    width = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f)
                )
            )
        }

        // Draggable Center Selection Area
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(
                    with(LocalDensity.current) { boxWidth.toDp() },
                    with(LocalDensity.current) { boxHeight.toDp() }
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        )

        // Resizable Bottom-Right Handle
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (offsetX + boxWidth - 20).roundToInt(),
                        (offsetY + boxHeight - 20).roundToInt()
                    )
                }
                .size(40.dp)
                .background(primaryColor, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        boxWidth = (boxWidth + dragAmount.x).coerceAtLeast(150f)
                        boxHeight = (boxHeight + dragAmount.y).coerceAtLeast(150f)
                    }
                }
        )

        // Floating Control Bar above crop frame
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = "拖动框选表情区域 (${boxWidth.roundToInt()}x${boxHeight.roundToInt()})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Button(
                    onClick = {
                        val rect = Rect(
                            offsetX.roundToInt(),
                            offsetY.roundToInt(),
                            (offsetX + boxWidth).roundToInt(),
                            (offsetY + boxHeight).roundToInt()
                        )
                        onConfirmCrop(rect)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = "确认")
                    Text("确认裁剪", fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
