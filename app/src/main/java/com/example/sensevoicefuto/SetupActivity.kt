package com.example.sensevoicefuto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SetupActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var senseVoiceButton: Button
    private lateinit var llmButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { textSize = 17f }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val permission = Button(this).apply {
            text = "1. 允许麦克风"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100) }
        }
        senseVoiceButton = Button(this).apply {
            text = "2. 下载 SenseVoice 模型（约 240MB）"
            setOnClickListener { installSenseVoice() }
        }
        llmButton = Button(this).apply {
            text = "3. 下载智能整理模型 Qwen3 0.6B Q4（约 430MB）"
            setOnClickListener { installLlm() }
        }
        val enable = Button(this).apply {
            text = "4. 打开系统输入法设置并启用 SenseVoice Voice Input"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        val test = Button(this).apply {
            text = "5. 测试语音识别"
            setOnClickListener { startActivity(Intent(this@SetupActivity, RecognizeActivity::class.java)) }
        }
        val note = TextView(this).apply {
            text = "v0.3：实时预览仍只跑 SenseVoice；你按“停止并输入”后，如果已安装 Qwen3 0.6B，本地小模型会一次性负责标点、断句、口头词/重复清理和明确的自我修正。两个模型下载完成后，识别和整理都可离线运行。智能整理模型是可选的；没装时自动退回原来的保守清理。"
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }

        listOf(status, progress, permission, senseVoiceButton, llmButton, enable, test, note)
            .forEach { root.addView(it) }
        setContentView(root)
        refreshStatus()
    }

    private fun refreshStatus() {
        val sv = if (ModelInstaller.isInstalled(this)) "已安装 ✓" else "未安装"
        val llm = if (LlmModelInstaller.isInstalled(this)) "已安装 ✓" else "未安装"
        status.text = "SenseVoice：$sv\n智能整理：$llm"
        senseVoiceButton.isEnabled = !ModelInstaller.isInstalled(this)
        llmButton.isEnabled = !LlmModelInstaller.isInstalled(this)
    }

    private fun setDownloading(downloading: Boolean) {
        senseVoiceButton.isEnabled = !downloading && !ModelInstaller.isInstalled(this)
        llmButton.isEnabled = !downloading && !LlmModelInstaller.isInstalled(this)
    }

    private fun installSenseVoice() {
        scope.launch {
            try {
                setDownloading(true)
                progress.progress = 0
                status.text = "正在下载 SenseVoice…"
                ModelInstaller.install(this@SetupActivity) { p ->
                    runOnUiThread {
                        progress.progress = p
                        status.text = "正在下载 SenseVoice… $p%"
                    }
                }
                progress.progress = 100
                Toast.makeText(this@SetupActivity, "SenseVoice 安装完成", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                status.text = "SenseVoice 安装失败：${t.message}"
            } finally {
                setDownloading(false)
                refreshStatus()
            }
        }
    }

    private fun installLlm() {
        scope.launch {
            try {
                setDownloading(true)
                progress.progress = 0
                status.text = "正在下载智能整理模型…"
                LlmModelInstaller.install(this@SetupActivity) { p ->
                    runOnUiThread {
                        progress.progress = p
                        status.text = "正在下载智能整理模型… $p%"
                    }
                }
                progress.progress = 100
                Toast.makeText(this@SetupActivity, "智能整理模型安装完成", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                status.text = "智能整理模型安装失败：${t.message}"
            } finally {
                setDownloading(false)
                refreshStatus()
            }
        }
    }
}
