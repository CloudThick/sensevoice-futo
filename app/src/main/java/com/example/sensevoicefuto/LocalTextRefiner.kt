package com.example.sensevoicefuto

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Final-pass local text editor. It never participates in real-time ASR.
 *
 * SenseVoice remains responsible for speech recognition. After the user presses
 * "停止并输入", this optional 0.6B model gets one chance to clean punctuation,
 * fillers, repetitions and explicit self-corrections. If anything looks unsafe,
 * we fall back to the conservative TextCleaner result.
 */
object LocalTextRefiner {
    private val mutex = Mutex()

    private val systemPrompt = """
/no_think
你是一个严格的“语音输入转写整理器”，不是聊天机器人。
你的唯一任务：把 ASR 原始转写整理成用户真正想输入的文字。

必须遵守：
1. 只输出整理后的正文，不解释、不回答内容、不加标题、不加引号。
2. 保持原意，不总结、不扩写，不添加用户没有说过的事实、观点或信息。
3. 删除无意义口头填充词，例如“嗯、呃、额、那个、就是”等，但只有在它们明显只是口头停顿时才删。
4. 删除明显的连续重复和口误重复。
5. 对明确的自我修正按用户最后的说法处理，例如“不是……应该是……”“我重说……”；如果是否修正不明确，就保留原文。
6. 根据完整上下文补自然的标点和断句。中文使用中文标点，英文使用英文标点。
7. 数字、日期、时间、金额、网址、邮箱、代码、型号、专有名词尽量原样保留。
8. 不要仅因为你觉得另一个词更通顺就擅自替换 ASR 的实词；不确定时宁可少改。
9. 不输出思考过程。
""".trimIndent()

    suspend fun refine(context: Context, rawText: String, language: String): String = mutex.withLock {
        val raw = rawText.trim()
        val fallback = TextCleaner.clean(raw)
        if (raw.isBlank() || !LlmModelInstaller.isInstalled(context)) return@withLock fallback

        val engine = AiChat.getInferenceEngine(context.applicationContext)

        try {
            awaitUsableState(engine)

            // Every dictation is an isolated editing request. Unload any stale model/history first.
            when (engine.state.value) {
                is InferenceEngine.State.ModelReady -> engine.cleanUp()
                is InferenceEngine.State.Error -> engine.cleanUp()
                else -> Unit
            }
            awaitInitialized(engine)

            engine.loadModel(LlmModelInstaller.modelFile(context).absolutePath)
            engine.setSystemPrompt(systemPrompt)

            val langLabel = if (language.startsWith("en")) "English" else "中文"
            val userPrompt = """
/no_think
语言：$langLabel
下面 <asr> 标签内是语音识别原文。请严格按系统规则整理，只输出最终正文。
<asr>
$raw
</asr>
""".trimIndent()

            val maxTokens = (raw.length * 2 + 64).coerceIn(128, 512)
            val out = StringBuilder()
            withTimeout(30_000L) {
                engine.sendUserPrompt(userPrompt, predictLength = maxTokens).collect { token ->
                    out.append(token)
                }
            }

            val candidate = sanitizeModelOutput(out.toString())
            validateOrFallback(raw, fallback, candidate)
        } catch (_: Throwable) {
            fallback
        } finally {
            runCatching {
                when (engine.state.value) {
                    is InferenceEngine.State.ModelReady,
                    is InferenceEngine.State.Error -> engine.cleanUp()
                    else -> Unit
                }
            }
        }
    }

    private suspend fun awaitUsableState(engine: InferenceEngine) {
        when (val current = engine.state.value) {
            is InferenceEngine.State.Initialized,
            is InferenceEngine.State.ModelReady -> return
            is InferenceEngine.State.Error -> {
                engine.cleanUp()
                return
            }
            else -> Unit
        }

        val state = withTimeout(15_000L) {
            engine.state.first {
                it is InferenceEngine.State.Initialized ||
                    it is InferenceEngine.State.ModelReady ||
                    it is InferenceEngine.State.Error
            }
        }
        if (state is InferenceEngine.State.Error) throw state.exception
    }

    private suspend fun awaitInitialized(engine: InferenceEngine) {
        if (engine.state.value is InferenceEngine.State.Initialized) return
        val state = withTimeout(10_000L) {
            engine.state.first {
                it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
            }
        }
        if (state is InferenceEngine.State.Error) throw state.exception
    }

    private fun sanitizeModelOutput(text: String): String {
        var s = text
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)^.*?</think>"), "")
            .trim()

        s = s.replace(Regex("^(整理后的?(正文|文本)?|最终(正文|文本|结果)|结果|输出)\\s*[:：]\\s*"), "")
            .trim()

        if (s.length >= 2) {
            val pairs = listOf('“' to '”', '"' to '"', '「' to '」', '『' to '』')
            for ((left, right) in pairs) {
                if (s.first() == left && s.last() == right) {
                    s = s.substring(1, s.length - 1).trim()
                    break
                }
            }
        }
        return s
    }

    private fun validateOrFallback(raw: String, fallback: String, candidate: String): String {
        if (candidate.isBlank()) return fallback
        if (candidate.contains("作为AI") || candidate.contains("作为 AI") ||
            candidate.startsWith("抱歉") || candidate.startsWith("我无法") ||
            candidate.contains("<asr>") || candidate.contains("```")) {
            return fallback
        }

        val rawCore = raw.count { !it.isWhitespace() && !it.isPunctuationLike() }.coerceAtLeast(1)
        val candidateCore = candidate.count { !it.isWhitespace() && !it.isPunctuationLike() }
        val ratio = candidateCore.toDouble() / rawCore.toDouble()
        if (ratio < 0.25 || ratio > 1.80) return fallback

        // Protect explicit numeric information from being silently changed or dropped.
        val numbers = Regex("\\d+(?:[.:/\\-]\\d+)*").findAll(raw).map { it.value }.toList()
        if (numbers.any { it !in candidate }) return fallback

        // Protect URLs and e-mail-like strings when the ASR happened to produce them correctly.
        val literals = Regex("(?:https?://\\S+|www\\.\\S+|[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})")
            .findAll(raw).map { it.value.trimEnd('，', '。', ',', '.', '！', '!', '？', '?') }.toList()
        if (literals.any { it !in candidate }) return fallback

        return candidate
    }

    private fun Char.isPunctuationLike(): Boolean = this in "，。！？；：、,.!?;:…—-（）()[]【】“”‘’\"'"
}
