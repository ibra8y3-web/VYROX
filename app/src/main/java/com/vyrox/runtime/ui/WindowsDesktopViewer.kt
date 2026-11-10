package com.vyrox.runtime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun WindowsDesktopViewer(
    frame: Bitmap?,
    status: String,
    onClose: () -> Unit,
    onPointer: (buttonMask: Int, x: Int, y: Int) -> Unit,
    onKeySym: (keySym: Int, down: Boolean) -> Unit,
    onText: (String) -> Unit
) {
    var typing by remember { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF05070C)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF111A2B)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("VYROX Windows Desktop", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(status, color = Color(0xFFB6C9E8), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onClose) { Text(vxText("Close desktop")) }
            }

            if (frame == null) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(status, color = Color.White)
                }
            } else {
                Canvas(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                        .pointerInput(frame) {
                            detectTapGestures(
                                onTap = { point ->
                                    val scale = min(
                                        size.width.toFloat() / frame.width.toFloat(),
                                        size.height.toFloat() / frame.height.toFloat()
                                    )
                                    val shownWidth = frame.width * scale
                                    val shownHeight = frame.height * scale
                                    val left = (size.width - shownWidth) / 2f
                                    val top = (size.height - shownHeight) / 2f
                                    val x = ((point.x - left) / scale).toInt()
                                    val y = ((point.y - top) / scale).toInt()
                                    if (x in 0 until frame.width && y in 0 until frame.height) {
                                        onPointer(1, x, y)
                                        onPointer(0, x, y)
                                    }
                                },
                                onLongPress = { point ->
                                    val scale = min(
                                        size.width.toFloat() / frame.width.toFloat(),
                                        size.height.toFloat() / frame.height.toFloat()
                                    )
                                    val shownWidth = frame.width * scale
                                    val shownHeight = frame.height * scale
                                    val left = (size.width - shownWidth) / 2f
                                    val top = (size.height - shownHeight) / 2f
                                    val x = ((point.x - left) / scale).toInt()
                                    val y = ((point.y - top) / scale).toInt()
                                    if (x in 0 until frame.width && y in 0 until frame.height) {
                                        onPointer(4, x, y)
                                        onPointer(0, x, y)
                                    }
                                }
                            )
                        }
                ) {
                    drawRect(Color.Black)
                    val image = frame.asImageBitmap()
                    val scale = min(size.width / frame.width.toFloat(), size.height / frame.height.toFloat())
                    val width = (frame.width * scale).toInt().coerceAtLeast(1)
                    val height = (frame.height * scale).toInt().coerceAtLeast(1)
                    val left = (size.width.toInt() - width) / 2
                    val top = (size.height.toInt() - height) / 2
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(frame.width, frame.height),
                        dstOffset = IntOffset(left, top),
                        dstSize = IntSize(width, height)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF111A2B)).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BasicTextField(
                    value = typing,
                    onValueChange = { next ->
                        if (next.startsWith(typing) && next.length > typing.length) {
                            onText(next.substring(typing.length))
                        } else if (next.length < typing.length) {
                            repeat(typing.length - next.length) {
                                onKeySym(KEY_BACKSPACE, true)
                                onKeySym(KEY_BACKSPACE, false)
                            }
                        } else if (next != typing) {
                            onKeySym(KEY_BACKSPACE, true)
                            onKeySym(KEY_BACKSPACE, false)
                            onText(next)
                        }
                        typing = next
                    },
                    modifier = Modifier.weight(1f).background(Color(0xFF202B40)).padding(10.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    decorationBox = { inner ->
                        Box {
                            if (typing.isEmpty()) Text(vxText("Tap here to type"), color = Color(0xFF9AAAC4))
                            inner()
                        }
                    }
                )
                OutlinedButton(onClick = {
                    onKeySym(KEY_ENTER, true); onKeySym(KEY_ENTER, false)
                }) { Text(vxText("Enter")) }
                OutlinedButton(onClick = {
                    onKeySym(KEY_ESCAPE, true); onKeySym(KEY_ESCAPE, false)
                }) { Text("Esc") }
            }
        }
    }
}

private const val KEY_BACKSPACE = 0xFF08
private const val KEY_ENTER = 0xFF0D
private const val KEY_ESCAPE = 0xFF1B
