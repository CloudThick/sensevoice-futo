package com.example.sensevoicefuto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RecognizeActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        status = TextView(this).apply { text = "准备录音…"; textSize = 18f }
        val spinner = ProgressBar(this)
        val cancel = Button(this).apply { text = "取消"; setOnClickListener { setResult(RESULT_CANCELED); finish() } }
        root.addView(status); root.addView(spinner); root.addView(cancel)
        setContentView(root)

        if (!ModelInstaller.isInstalled(this)) {
            status.text = "请先打开 SenseVoice Voice Input 并下载模型"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 200)
        } else startRecognition()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecognition()
        else { setResult(RESULT_CANCELED); finish() }
    }

    private fun requestedLanguage(): String {
        val raw = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE).orEmpty().lowercase()
        return if (raw.startsWith("en")) "en" else "zh"
    }

    private fun startRecognition() {
        scope.launch {
            try {
                status.text = "请说话…（停顿约 1 秒自动结束）"
                val samples = AudioCapture.recordUntilSilence(this@RecognizeActivity)
                status.text = "正在识别…"
                val raw = SenseVoiceEngine.recognize(this@RecognizeActivity, samples, requestedLanguage())
                val cleaned = TextCleaner.clean(raw)
                val out = Intent().apply {
                    putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(cleaned))
                    putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1.0f))
                }
                setResult(RESULT_OK, out)
                finish()
            } catch (t: Throwable) {
                status.text = "识别失败：${t.message}"
            }
        }
    }
}
