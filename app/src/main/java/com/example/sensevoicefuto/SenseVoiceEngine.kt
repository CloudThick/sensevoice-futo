package com.example.sensevoicefuto

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object SenseVoiceEngine {
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var recognizerLanguage: String? = null
    private val decodeMutex = Mutex()

    private fun build(context: Context, language: String): OfflineRecognizer {
        val dir = ModelInstaller.modelDir(context)
        val model = File(dir, "model.int8.onnx").absolutePath
        val tokens = File(dir, "tokens.txt").absolutePath

        val cfg = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model,
                    language = language,
                    useInverseTextNormalization = true,
                ),
                tokens = tokens,
                numThreads = 4,
                debug = false,
                provider = "cpu",
            )
        )
        return OfflineRecognizer(assetManager = null, config = cfg)
    }

    @Synchronized
    private fun get(context: Context, language: String): OfflineRecognizer {
        if (recognizer == null || recognizerLanguage != language) {
            recognizer?.release()
            recognizer = build(context.applicationContext, language)
            recognizerLanguage = language
        }
        return recognizer!!
    }

    suspend fun recognize(context: Context, samples: FloatArray, language: String): String =
        withContext(Dispatchers.Default) {
            decodeMutex.withLock {
                check(ModelInstaller.isInstalled(context)) { "SenseVoice model is not installed" }
                val r = get(context, language)
                val stream = r.createStream()
                try {
                    stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
                    r.decode(stream)
                    r.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        }
}
