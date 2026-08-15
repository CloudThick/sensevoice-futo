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
import kotlinx.coroutines.launch
import java.util.Locale

class SenseVoiceInputMethodService : InputMethodService() {
    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var status: TextView

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        status = TextView(this).apply { text = "SenseVoice"; textSize = 18f }
        val spinner = ProgressBar(this)
        val cancel = Button(this).apply { text = "取消"; setOnClickListener { switchBack() } }
        root.addView(status); root.addView(spinner); root.addView(cancel)
        return root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job())

        if (!ModelInstaller.isInstalled(this)) {
            status.text = "请先打开应用下载 SenseVoice 模型"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "请先打开应用授予麦克风权限"
            return
        }

        scope.launch {
            try {
                status.text = "请说话…"
                val samples = AudioCapture.recordUntilSilence(this@SenseVoiceInputMethodService)
                status.text = "正在识别…"
                val raw = SenseVoiceEngine.recognize(this@SenseVoiceInputMethodService, samples, currentLanguage())
                val cleaned = TextCleaner.clean(raw)
                currentInputConnection?.commitText(cleaned, 1)
                switchBack()
            } catch (t: Throwable) {
                status.text = "失败：${t.message}"
            }
        }
    }

    private fun currentLanguage(): String {
        val localeTag = if (Build.VERSION.SDK_INT >= 24) {
            currentInputEditorInfo?.hintLocales?.get(0)?.toLanguageTag().orEmpty()
        } else ""
        if (localeTag.lowercase().startsWith("en")) return "en"
        @Suppress("DEPRECATION")
        val subtypeLocale = currentInputMethodSubtype?.locale.orEmpty()
        return if (subtypeLocale.lowercase(Locale.ROOT).startsWith("en")) "en" else "zh"
    }

    private fun switchBack() {
        if (Build.VERSION.SDK_INT >= 28) {
            switchToPreviousInputMethod()
        } else {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            @Suppress("DEPRECATION")
            imm.switchToLastInputMethod(window.window?.attributes?.token)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        scope.cancel()
        super.onFinishInputView(finishingInput)
    }
}
