package com.example.sensevoicefuto

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SenseVoiceInputMethodService : InputMethodService() {
    private var scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var cancelButton: Button

    private val stopRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var partialJob: Job? = null
    private var partialChannel: Channel<FloatArray>? = null
    private var hasComposition = false
    private var recording = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        status = TextView(this).apply {
            text = "SenseVoice"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        preview = TextView(this).apply {
            text = ""
            textSize = 16f
            gravity = Gravity.CENTER
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        startButton = Button(this).apply {
            text = "开始"
            setOnClickListener { startManualRecording() }
        }
        stopButton = Button(this).apply {
            text = "停止并输入"
            isEnabled = false
            setOnClickListener { requestStop(commit = true) }
        }
        cancelButton = Button(this).apply {
            text = "取消"
            setOnClickListener {
                if (recording) requestStop(commit = false) else switchBack()
            }
        }

        buttons.addView(startButton)
        buttons.addView(stopButton)
        buttons.addView(cancelButton)

        root.addView(status)
        root.addView(preview)
        root.addView(progress)
        root.addView(buttons)
        return root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        stopRequested.set(true)
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job())
        stopRequested.set(false)
        cancelRequested.set(false)
        recording = false
        hasComposition = false

        if (!ModelInstaller.isInstalled(this)) {
            status.text = "请先打开应用下载 SenseVoice 模型"
            preview.text = ""
            startButton.isEnabled = false
            stopButton.isEnabled = false
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "请先打开应用授予麦克风权限"
            preview.text = ""
            startButton.isEnabled = false
            stopButton.isEnabled = false
            return
        }

        status.text = "点击“开始”后说话"
        preview.text = "识别结果会在这里和输入框中实时更新"
        progress.visibility = View.GONE
        startButton.isEnabled = true
        stopButton.isEnabled = false
        cancelButton.isEnabled = true
    }

    private fun startManualRecording() {
        if (recording) return
        if (!ModelInstaller.isInstalled(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        stopRequested.set(false)
        cancelRequested.set(false)
        recording = true
        hasComposition = false
        status.text = "正在听…"
        preview.text = "开始说话吧"
        progress.visibility = View.GONE
        startButton.isEnabled = false
        stopButton.isEnabled = true
        cancelButton.isEnabled = true

        val language = currentLanguage()
        val channel = Channel<FloatArray>(capacity = Channel.CONFLATED)
        partialChannel = channel

        partialJob = scope.launch(Dispatchers.Default) {
            for (snapshot in channel) {
                if (stopRequested.get()) break
                if (snapshot.isEmpty()) continue

                val raw = runCatching {
                    SenseVoiceEngine.recognize(this@SenseVoiceInputMethodService, snapshot, language)
                }.getOrNull() ?: continue

                if (stopRequested.get()) break
                val cleaned = TextCleaner.clean(raw)
                if (cleaned.isBlank()) continue

                withContext(Dispatchers.Main) {
                    if (!recording || stopRequested.get()) return@withContext
                    preview.text = cleaned
                    currentInputConnection?.setComposingText(cleaned, 1)
                    hasComposition = true
                }
            }
        }

        recordingJob = scope.launch {
            try {
                val samples = AudioCapture.recordUntilStopped(
                    context = this@SenseVoiceInputMethodService,
                    shouldStop = { stopRequested.get() },
                    onSnapshot = { snapshot -> channel.trySend(snapshot) },
                    partialEveryMs = 550L,
                    maxSeconds = 60,
                )

                channel.close()
                partialJob?.cancelAndJoin()

                if (cancelRequested.get()) {
                    clearComposition()
                    switchBack()
                    return@launch
                }

                recording = false
                startButton.isEnabled = false
                stopButton.isEnabled = false
                cancelButton.isEnabled = false
                progress.visibility = View.VISIBLE
                status.text = "正在完成最终识别…"

                val raw = SenseVoiceEngine.recognize(
                    this@SenseVoiceInputMethodService,
                    samples,
                    language
                )

                val cleaned = if (LlmModelInstaller.isInstalled(this@SenseVoiceInputMethodService)) {
                    status.text = "正在用本地小模型整理文字…"
                    LocalTextRefiner.refine(
                        this@SenseVoiceInputMethodService,
                        raw,
                        language
                    )
                } else {
                    TextCleaner.clean(raw)
                }

                progress.visibility = View.GONE
                if (cleaned.isNotBlank()) {
                    preview.text = cleaned
                    if (hasComposition) {
                        currentInputConnection?.setComposingText(cleaned, 1)
                        currentInputConnection?.finishComposingText()
                    } else {
                        currentInputConnection?.commitText(cleaned, 1)
                    }
                    hasComposition = false
                } else {
                    clearComposition()
                }
                switchBack()
            } catch (t: Throwable) {
                recording = false
                progress.visibility = View.GONE
                startButton.isEnabled = true
                stopButton.isEnabled = false
                cancelButton.isEnabled = true
                status.text = "失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun requestStop(commit: Boolean) {
        if (!recording) return
        cancelRequested.set(!commit)
        stopRequested.set(true)
        stopButton.isEnabled = false
        startButton.isEnabled = false
        status.text = if (commit) "正在停止…" else "正在取消…"
        if (commit) progress.visibility = View.VISIBLE
    }

    private fun clearComposition() {
        if (!hasComposition) return
        currentInputConnection?.setComposingText("", 1)
        currentInputConnection?.finishComposingText()
        hasComposition = false
    }

    private fun currentLanguage(): String {
        val localeTag = if (Build.VERSION.SDK_INT >= 24) {
            currentInputEditorInfo?.hintLocales?.get(0)?.toLanguageTag().orEmpty()
        } else {
            ""
        }
        if (localeTag.lowercase(Locale.ROOT).startsWith("en")) return "en"
        if (localeTag.lowercase(Locale.ROOT).startsWith("zh")) return "zh"

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        @Suppress("DEPRECATION")
        val subtypeLocale = imm.currentInputMethodSubtype?.locale.orEmpty()
        return if (subtypeLocale.lowercase(Locale.ROOT).startsWith("en")) "en" else "zh"
    }

    private fun switchBack() {
        recording = false
        stopRequested.set(true)
        if (Build.VERSION.SDK_INT >= 28) {
            switchToPreviousInputMethod()
        } else {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            @Suppress("DEPRECATION")
            imm.switchToLastInputMethod(window.window?.attributes?.token)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRequested.set(true)
        recording = false
        partialChannel?.close()
        scope.cancel()
        super.onFinishInputView(finishingInput)
    }
}
