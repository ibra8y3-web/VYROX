package com.vyrox.runtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives raw 48 kHz signed 16-bit stereo PCM from the PulseAudio monitor
 * inside PRoot and plays it through Android AudioTrack.
 */
class PcmAudioBridge(private val port: Int = 4472) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var track: AudioTrack? = null

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        } catch (exception: Exception) {
            running.set(false)
            throw IOException("Could not reserve the local Windows audio bridge port.", exception)
        }
        Thread({ receivePcm() }, "vyrox-audio-bridge").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running.set(false)
        runCatching { client?.close() }
        runCatching { server?.close() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        client = null
        server = null
        track = null
    }

    private fun receivePcm() {
        try {
            val activeServer = server ?: return
            val connection = activeServer.accept()
            if (!running.get()) {
                connection.close()
                return
            }
            client = connection
            connection.tcpNoDelay = true
            val minimumBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimumBuffer <= 0) throw IOException("Android does not support the requested PCM format.")
            val audio = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minimumBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = audio
            audio.play()
            connection.getInputStream().use { input ->
                val buffer = ByteArray(PCM_BUFFER_BYTES)
                while (running.get()) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    var offset = 0
                    while (offset < count && running.get()) {
                        val written = audio.write(buffer, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) throw IOException("Android audio output stopped accepting PCM samples.")
                        offset += written
                    }
                }
            }
        } catch (_: Exception) {
            // Desktop execution remains usable if Android audio is unavailable.
        } finally {
            close()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val PCM_BUFFER_BYTES = 32 * 1024
    }
}
