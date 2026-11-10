package com.vyrox.runtime

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small RFB 3.8 client for the localhost-only x11vnc session created by VYROX.
 * Supports Raw rectangles and DesktopSize updates.
 */
class VncRfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5900
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var framebuffer: Bitmap? = null

    fun connectAsync(
        onFrame: (Bitmap) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val activeSocket = connectWithRetry(onStatus)
                socket = activeSocket
                val input = DataInputStream(BufferedInputStream(activeSocket.getInputStream()))
                val stream = DataOutputStream(BufferedOutputStream(activeSocket.getOutputStream()))
                output = stream
                handshake(input, stream, onStatus)
                receiveLoop(input, stream, onFrame)
            } catch (exception: Exception) {
                if (running.get()) onError(exception.message ?: "VNC connection failed.")
            } finally {
                running.set(false)
                runCatching { socket?.close() }
                socket = null
                output = null
            }
        }, "vyrox-vnc-client").apply { isDaemon = true; start() }
    }

    fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        val bitmap = framebuffer ?: return
        val safeX = x.coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        synchronized(writeLock) {
            val stream = output ?: return
            runCatching {
                stream.writeByte(5)
                stream.writeByte(buttonMask and 0xFF)
                stream.writeShort(safeX)
                stream.writeShort(safeY)
                stream.flush()
            }.onFailure { close() }
        }
    }

    fun sendKeySym(keySym: Int, down: Boolean) {
        synchronized(writeLock) {
            val stream = output ?: return
            runCatching {
                stream.writeByte(4)
                stream.writeByte(if (down) 1 else 0)
                stream.writeShort(0)
                stream.writeInt(keySym)
                stream.flush()
            }.onFailure { close() }
        }
    }

    fun sendText(value: String) {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val keySym = if (codePoint <= 0xFF) codePoint else 0x01000000 or codePoint
            sendKeySym(keySym, true)
            sendKeySym(keySym, false)
            index += Character.charCount(codePoint)
        }
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
    }

    private fun connectWithRetry(onStatus: (String) -> Unit): Socket {
        var lastError: Exception? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (!running.get()) throw IOException("VNC connection was cancelled.")
            val candidate = Socket()
            try {
                onStatus("Connecting to the Windows desktop (${attempt + 1}/$CONNECT_ATTEMPTS)...")
                candidate.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                candidate.tcpNoDelay = true
                candidate.soTimeout = SOCKET_READ_TIMEOUT_MS
                return candidate
            } catch (exception: Exception) {
                lastError = exception
                runCatching { candidate.close() }
                Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }
        throw IOException("Could not connect to the local Windows desktop viewer.", lastError)
    }

    private fun handshake(
        input: DataInputStream,
        stream: DataOutputStream,
        onStatus: (String) -> Unit
    ) {
        val serverVersion = ByteArray(12)
        input.readFully(serverVersion)
        val versionText = String(serverVersion, Charsets.US_ASCII)
        if (!versionText.startsWith("RFB 003.")) {
            throw IOException("The local desktop server returned an unsupported RFB version.")
        }
        stream.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        stream.flush()

        val securityCount = input.readUnsignedByte()
        if (securityCount == 0) {
            val reasonLength = input.readInt().coerceIn(0, MAX_REASON_BYTES)
            val reason = ByteArray(reasonLength)
            input.readFully(reason)
            throw IOException(String(reason, Charsets.UTF_8))
        }
        val securityTypes = ByteArray(securityCount)
        input.readFully(securityTypes)
        if (!securityTypes.contains(1.toByte())) {
            throw IOException("The local VNC server did not offer localhost-only no-password mode.")
        }
        stream.writeByte(1)
        stream.flush()
        val securityResult = input.readInt()
        if (securityResult != 0) {
            val reasonLength = input.readInt().coerceIn(0, MAX_REASON_BYTES)
            val reason = ByteArray(reasonLength)
            input.readFully(reason)
            throw IOException(String(reason, Charsets.UTF_8))
        }

        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) {
            throw IOException("The VNC desktop dimensions are invalid or too large.")
        }
        val serverPixelFormat = ByteArray(16)
        input.readFully(serverPixelFormat)
        val nameLength = input.readInt().coerceIn(0, MAX_NAME_BYTES)
        val desktopName = ByteArray(nameLength)
        input.readFully(desktopName)

        framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        sendPixelFormat(stream)
        sendEncodings(stream)
        requestFrame(stream, width, height, incremental = false)
        onStatus("Connected to " + String(desktopName, Charsets.UTF_8))
    }

    private fun sendPixelFormat(stream: DataOutputStream) {
        stream.writeByte(0)
        stream.writeByte(0)
        stream.writeByte(0)
        stream.writeByte(0)
        stream.writeByte(32)
        stream.writeByte(24)
        stream.writeByte(0)
        stream.writeByte(1)
        stream.writeShort(255)
        stream.writeShort(255)
        stream.writeShort(255)
        stream.writeByte(16)
        stream.writeByte(8)
        stream.writeByte(0)
        stream.writeByte(0)
        stream.writeByte(0)
        stream.writeByte(0)
        stream.flush()
    }

    private fun sendEncodings(stream: DataOutputStream) {
        stream.writeByte(2)
        stream.writeByte(0)
        stream.writeShort(2)
        stream.writeInt(ENCODING_RAW)
        stream.writeInt(ENCODING_DESKTOP_SIZE)
        stream.flush()
    }

    private fun requestFrame(
        stream: DataOutputStream,
        width: Int,
        height: Int,
        incremental: Boolean
    ) {
        synchronized(writeLock) {
            stream.writeByte(3)
            stream.writeByte(if (incremental) 1 else 0)
            stream.writeShort(0)
            stream.writeShort(0)
            stream.writeShort(width)
            stream.writeShort(height)
            stream.flush()
        }
    }

    private fun receiveLoop(
        input: DataInputStream,
        stream: DataOutputStream,
        onFrame: (Bitmap) -> Unit
    ) {
        while (running.get()) {
            when (input.readUnsignedByte()) {
                SERVER_FRAMEBUFFER_UPDATE -> readFramebufferUpdate(input, stream, onFrame)
                SERVER_SET_COLOUR_MAP -> {
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                    val count = input.readUnsignedShort()
                    skipFully(input, count.toLong() * 6L)
                }
                SERVER_BELL -> Unit
                SERVER_CUT_TEXT -> {
                    skipFully(input, 3)
                    skipFully(input, input.readInt().toLong().coerceIn(0L, MAX_CUT_TEXT_BYTES))
                }
                else -> throw IOException("The local VNC server sent an unsupported message.")
            }
        }
    }

    private fun readFramebufferUpdate(
        input: DataInputStream,
        stream: DataOutputStream,
        onFrame: (Bitmap) -> Unit
    ) {
        input.readUnsignedByte()
        val rectangleCount = input.readUnsignedShort()
        repeat(rectangleCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                ENCODING_RAW -> readRawRectangle(input, x, y, width, height)
                ENCODING_COPY_RECT -> readCopyRectangle(input, x, y, width, height)
                ENCODING_DESKTOP_SIZE -> resizeFramebuffer(width, height)
                else -> throw IOException("Unsupported VNC rectangle encoding: $encoding")
            }
        }
        val current = framebuffer ?: throw IOException("The VNC framebuffer is unavailable.")
        onFrame(current.copy(Bitmap.Config.ARGB_8888, false))
        requestFrame(stream, current.width, current.height, incremental = true)
    }

    private fun readRawRectangle(input: DataInputStream, x: Int, y: Int, width: Int, height: Int) {
        val bitmap = framebuffer ?: throw IOException("The VNC framebuffer is unavailable.")
        validateRectangle(bitmap, x, y, width, height)
        val rowBytes = width.toLong() * 4L
        if (rowBytes * height > MAX_RECTANGLE_BYTES) {
            throw IOException("The VNC frame rectangle exceeds the safe byte limit.")
        }
        val bytes = ByteArray(rowBytes.toInt())
        val pixels = IntArray(width)
        for (row in 0 until height) {
            input.readFully(bytes)
            for (column in 0 until width) {
                val offset = column * 4
                val blue = bytes[offset].toInt() and 0xFF
                val green = bytes[offset + 1].toInt() and 0xFF
                val red = bytes[offset + 2].toInt() and 0xFF
                pixels[column] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
            bitmap.setPixels(pixels, 0, width, x, y + row, width, 1)
        }
    }

    private fun readCopyRectangle(input: DataInputStream, x: Int, y: Int, width: Int, height: Int) {
        val bitmap = framebuffer ?: throw IOException("The VNC framebuffer is unavailable.")
        val sourceX = input.readUnsignedShort()
        val sourceY = input.readUnsignedShort()
        validateRectangle(bitmap, x, y, width, height)
        validateRectangle(bitmap, sourceX, sourceY, width, height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, sourceX, sourceY, width, height)
        bitmap.setPixels(pixels, 0, width, x, y, width, height)
    }

    private fun resizeFramebuffer(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) {
            throw IOException("The VNC desktop resize dimensions are invalid.")
        }
        framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun validateRectangle(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {
        if (
            width <= 0 || height <= 0 || x < 0 || y < 0 ||
            x + width > bitmap.width || y + height > bitmap.height
        ) throw IOException("The VNC server sent an invalid framebuffer rectangle.")
    }

    private fun skipFully(input: DataInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw IOException("Unexpected end of VNC stream.")
                remaining -= 1
            }
        }
    }

    private companion object {
        const val CONNECT_ATTEMPTS = 30
        const val CONNECT_TIMEOUT_MS = 1_500
        const val CONNECT_RETRY_DELAY_MS = 500L
        const val SOCKET_READ_TIMEOUT_MS = 0
        const val MAX_REASON_BYTES = 4 * 1024
        const val MAX_NAME_BYTES = 4 * 1024
        const val MAX_CUT_TEXT_BYTES = 1024L * 1024L
        const val MAX_PIXELS = 16_000_000L
        const val MAX_RECTANGLE_BYTES = 64L * 1024L * 1024L
        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_DESKTOP_SIZE = -223
        const val SERVER_FRAMEBUFFER_UPDATE = 0
        const val SERVER_SET_COLOUR_MAP = 1
        const val SERVER_BELL = 2
        const val SERVER_CUT_TEXT = 3
    }
}
