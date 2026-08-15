package com.example.sensevoicefuto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object LlmModelInstaller {
    const val MODEL_FILE_NAME = "Qwen3-0.6B-Q4_0.gguf"
    private const val MODEL_URL =
        "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_0.gguf?download=true"
    private const val MIN_EXPECTED_BYTES = 400_000_000L

    fun modelDir(context: Context): File = File(context.filesDir, "models/qwen3-0.6b-q4")
    fun modelFile(context: Context): File = File(modelDir(context), MODEL_FILE_NAME)

    fun isInstalled(context: Context): Boolean = modelFile(context).length() >= MIN_EXPECTED_BYTES

    suspend fun install(
        context: Context,
        progress: (Int) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        dir.mkdirs()

        val finalFile = modelFile(context)
        if (finalFile.length() >= MIN_EXPECTED_BYTES) {
            progress(100)
            return@withContext
        }

        val partFile = File(dir, "$MODEL_FILE_NAME.part")
        partFile.delete()

        val conn = openFollowingRedirects(MODEL_URL)
        try {
            val total = conn.contentLengthLong
            conn.inputStream.buffered().use { input ->
                partFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            progress(((done * 100L) / total).toInt().coerceIn(0, 99))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        check(partFile.length() >= MIN_EXPECTED_BYTES) {
            "智能整理模型下载不完整：${partFile.length() / 1_000_000} MB"
        }

        if (finalFile.exists()) finalFile.delete()
        check(partFile.renameTo(finalFile)) { "无法保存智能整理模型" }
        progress(100)
    }

    private fun openFollowingRedirects(sourceUrl: String): HttpURLConnection {
        var current = URL(sourceUrl)
        repeat(8) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 90_000
                setRequestProperty("User-Agent", "SenseVoice-FUTO/0.3")
            }
            val code = conn.responseCode
            if (code in 200..299) return conn

            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                    ?: error("模型下载重定向缺少 Location")
                conn.disconnect()
                current = URL(current, location)
                return@repeat
            }

            val message = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
            conn.disconnect()
            error("模型下载失败：HTTP $code${message?.take(160)?.let { " - $it" } ?: ""}")
        }
        error("模型下载重定向次数过多")
    }
}
