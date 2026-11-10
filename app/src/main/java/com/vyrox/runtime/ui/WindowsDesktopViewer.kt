package com.vyrox.runtime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
    var lastPointer by remember { mutableStateOf(0 to 0) }
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
                val frameWidth = frame.width
                val frameHeight = frame.height
                Canvas(
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                        .pointerInput(frameWidth, frameHeight) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val start = mapToFramebuffer(
                                    down.position,
                                    size.width,
                                    size.height,
                                    frameWidth,
                                    frameHeight
                                )
                                var pressed = start != null
                                if (start != null) {
                                    lastPointer = start.first to start.second
                                    onPointer(1, start.first, start.second)
                                }
                                while (pressed) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val mapped = mapToFramebuffer(
                                        change.position,
                                        size.width,
                                        size.height,
                                        frameWidth,
                                        frameHeight
                                    )
                                    if (mapped != null) {
                                        lastPointer = mapped.first to mapped.second
                                        onPointer(if (change.pressed) 1 else 0, mapped.first, mapped.second)
                                    } else if (!change.pressed) {
                                        onPointer(0, lastPointer.first, lastPointer.second)
                                    }
                                    pressed = change.pressed
                                    change.consume()
                                }
                            }
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
                OutlinedButton(onClick = {
                    onPointer(4, lastPointer.first, lastPointer.second)
                    onPointer(0, lastPointer.first, lastPointer.second)
                }) { Text(vxText("Right click")) }
                OutlinedButton(onClick = {
                    onKeySym(KEY_TAB, true); onKeySym(KEY_TAB, false)
                }) { Text("Tab") }
            }
        }
    }
}

private fun mapToFramebuffer(
    point: Offset,
    canvasWidth: Int,
    canvasHeight: Int,
    frameWidth: Int,
    frameHeight: Int
): Pair<Int, Int>? {
    if (canvasWidth <= 0 || canvasHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
    val scale = min(
        canvasWidth.toFloat() / frameWidth.toFloat(),
        canvasHeight.toFloat() / frameHeight.toFloat()
    )
    val shownWidth = frameWidth * scale
    val shownHeight = frameHeight * scale
    val left = (canvasWidth - shownWidth) / 2f
    val top = (canvasHeight - shownHeight) / 2f
    val x = ((point.x - left) / scale).toInt()
    val y = ((point.y - top) / scale).toInt()
    return if (x in 0 until frameWidth && y in 0 until frameHeight) x to y else null
}

private const val KEY_BACKSPACE = 0xFF08
private const val KEY_ENTER = 0xFF0D
private const val KEY_ESCAPE = 0xFF1B
private const val KEY_TAB = 0xFF09
