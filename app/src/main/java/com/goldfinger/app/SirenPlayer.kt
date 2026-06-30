package com.goldfinger.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates the alarm/siren sound in real time (no audio file needed).
 * A carrier tone sweeps between two frequencies via a slow LFO - a classic
 * siren. Streamed from a background thread; start/stop freely.
 */
class SirenPlayer {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        val sampleRate = 44100
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()

        val chunk = ShortArray(1024)
        val twoPi = 2.0 * PI
        val lfoFreq = 2.0
        val fLow = 600.0
        val fHigh = 1150.0
        val amp = 0.6 * Short.MAX_VALUE
        var phase = 0.0
        var lfoPhase = 0.0

        while (running) {
            for (i in chunk.indices) {
                lfoPhase += twoPi * lfoFreq / sampleRate
                val lfo = (sin(lfoPhase) + 1.0) / 2.0
                val freq = fLow + (fHigh - fLow) * lfo
                phase += twoPi * freq / sampleRate
                chunk[i] = (sin(phase) * amp).toInt().toShort()
            }
            track.write(chunk, 0, chunk.size)
        }

        try {
            track.stop()
        } catch (_: Exception) {
        }
        track.release()
    }
}
