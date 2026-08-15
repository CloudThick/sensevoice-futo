package com.example.sensevoicefuto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

object AudioCapture {
    const val SAMPLE_RATE = 16000

    suspend fun recordUntilSilence(
        context: Context,
        onLevel: (Float) -> Unit = {},
        maxSeconds: Int = 30,
    ): FloatArray = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("Microphone permission not granted")
        }

        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, 4096)
        )

        val out = FloatBuffer()
        val shorts = ShortArray(1024)
        val floats = FloatArray(1024)
        var heardSpeech = false
        var silenceMs = 0L
        var elapsedMs = 0L
        val frameMs = 1024L * 1000L / SAMPLE_RATE

        try {
            recorder.startRecording()
            while (elapsedMs < maxSeconds * 1000L) {
                val n = recorder.read(shorts, 0, shorts.size)
                if (n <= 0) continue

                var power = 0.0
                for (i in 0 until n) {
                    val f = shorts[i] / 32768.0f
                    floats[i] = f
                    power += f * f
                }
                val rms = sqrt(power / n).toFloat()
                onLevel(rms)
                out.add(floats, n)

                if (rms > 0.018f) {
                    heardSpeech = true
                    silenceMs = 0L
                } else if (heardSpeech && rms < 0.010f) {
                    silenceMs += frameMs
                } else if (heardSpeech) {
                    silenceMs = 0L
                }

                elapsedMs += frameMs
                if (heardSpeech && silenceMs >= 950L) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        out.toArray()
    }

    /**
     * Manual recording mode for the voice IME.
     *
     * It never stops because of silence. The caller decides when to stop, while periodic
     * snapshots are emitted for simulated real-time recognition. A hard time limit exists
     * only as a safety guard against an accidentally abandoned recording session.
     */
    suspend fun recordUntilStopped(
        context: Context,
        shouldStop: () -> Boolean,
        onLevel: (Float) -> Unit = {},
        onSnapshot: (FloatArray) -> Unit = {},
        partialEveryMs: Long = 550L,
        maxSeconds: Int = 60,
    ): FloatArray = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("Microphone permission not granted")
        }

        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, 4096)
        )

        val out = FloatBuffer()
        val shorts = ShortArray(1024)
        val floats = FloatArray(1024)
        val frameMs = 1024L * 1000L / SAMPLE_RATE
        var elapsedMs = 0L
        var lastSnapshotMs = 0L
        var heardSpeech = false

        try {
            recorder.startRecording()
            while (!shouldStop() && elapsedMs < maxSeconds * 1000L) {
                val n = recorder.read(shorts, 0, shorts.size)
                if (n <= 0) continue

                var power = 0.0
                for (i in 0 until n) {
                    val f = shorts[i] / 32768.0f
                    floats[i] = f
                    power += f * f
                }
                val rms = sqrt(power / n).toFloat()
                onLevel(rms)
                out.add(floats, n)

                // Only start partial decoding after we have actually heard speech. This avoids
                // displaying junk while the user is still preparing to talk.
                if (rms > 0.012f) heardSpeech = true

                elapsedMs += frameMs
                if (heardSpeech && elapsedMs - lastSnapshotMs >= partialEveryMs) {
                    lastSnapshotMs = elapsedMs
                    onSnapshot(out.toArray())
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        out.toArray()
    }
}
