package com.example.sensevoicefuto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelInstaller {
    const val MODEL_DIR_NAME = "sensevoice-2024-07-17-int8"
    private const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

    fun modelDir(context: Context): File = File(context.filesDir, "models/$MODEL_DIR_NAME")

    fun isInstalled(context: Context): Boolean {
        val d = modelDir(context)
        return File(d, "model.int8.onnx").length() > 100_000_000L && File(d, "tokens.txt").length() > 100_000L
    }

    suspend fun install(context: Context, progress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        val dest = modelDir(context)
        dest.mkdirs()
        val archive = File(context.cacheDir, "sensevoice.tar.bz2")

        val conn = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.connect()
        val total = conn.contentLengthLong.coerceAtLeast(1L)
        conn.inputStream.use { input ->
            archive.outputStream().buffered().use { output ->
                val buf = ByteArray(1024 * 256)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    progress(((done * 100L) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        conn.disconnect()

        BufferedInputStream(archive.inputStream()).use { raw ->
            BZip2CompressorInputStream(raw, true).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isFile) continue
                        val name = entry.name.substringAfterLast('/')
                        if (name !in setOf("model.int8.onnx", "tokens.txt", "LICENSE")) continue
                        File(dest, name).outputStream().buffered().use { out -> tar.copyTo(out) }
                    }
                }
            }
        }
        archive.delete()
        check(isInstalled(context)) { "Model extraction did not produce the expected files" }
    }
}
