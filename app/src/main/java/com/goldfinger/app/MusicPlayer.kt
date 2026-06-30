package com.goldfinger.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Generates tense build-up music in real time (no audio file needed) - played
 * while players hold and wait for the alarm.
 *
 * Three layers create a ticking-time-bomb feel:
 *   - a pounding bass pulse on every beat (drives the tempo),
 *   - a fast minor arpeggio (unease),
 *   - percussive ticks on every sixteenth (urgency).
 * Intensity ramps up slowly so the pressure grows the longer the round lasts.
 */
class MusicPlayer {

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
        val bufSize = maxOf(minBuf, sampleRate / 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

        val rnd = Random(1)
        val twoPi = 2.0 * PI
        val bpm = 150.0
        val beatLen = 60.0 / bpm
        val stepLen = beatLen / 4.0
        val baseFreq = 220.0

        val pattern = intArrayOf(
            0, 12, 7, 3,
            0, 12, 7, 3,
            0, 12, 8, 5,
            0, 15, 10, 7
        )

        val chunk = ShortArray(1024)
        var sampleIndex = 0L
        var phaseMel = 0.0
        var phaseBass = 0.0
        var lastStep = -1
        var melFreq = baseFreq

        while (running) {
            for (i in chunk.indices) {
                val t = sampleIndex / sampleRate.toDouble()

                val step = (t / stepLen).toInt()
                val stepPos = (t / stepLen) - step
                if (step != lastStep) {
                    melFreq = baseFreq * 2.0.pow(pattern[step % pattern.size] / 12.0)
                    lastStep = step
                }

                val melEnv = exp(-stepPos * 7.0)
                phaseMel += twoPi * melFreq / sampleRate
                val mel = sin(phaseMel) * melEnv * 0.22

                val beat = (t / beatLen).toInt()
                val beatPos = (t / beatLen) - beat
                val bassEnv = exp(-beatPos * 5.0)
                phaseBass += twoPi * (baseFreq / 2.0) / sampleRate
                val bass = sin(phaseBass) * bassEnv * 0.38

                val hatEnv = exp(-stepPos * 45.0)
                val hat = (rnd.nextDouble() * 2.0 - 1.0) * hatEnv * 0.10

                val intensity = (0.7 + 0.3 * (t / 20.0)).coerceAtMost(1.0)

                var out = (mel + bass + hat) * intensity
                if (out > 1.0) out = 1.0 else if (out < -1.0) out = -1.0
                chunk[i] = (out * 0.85 * Short.MAX_VALUE).toInt().toShort()
                sampleIndex++
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
