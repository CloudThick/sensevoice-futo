package com.example.sensevoicefuto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.withContext

class SetupActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val permission = Button(this).apply {
            text = "1. 允许麦克风"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100) }
        }
        val download = Button(this).apply {
            text = "2. 下载 SenseVoice 模型（约 240MB）"
            setOnClickListener { installModel() }
        }
        val enable = Button(this).apply {
            text = "3. 打开系统输入法设置并启用 SenseVoice Voice Input"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        val test = Button(this).apply {
            text = "4. 测试语音识别"
            setOnClickListener { startActivity(Intent(this@SetupActivity, RecognizeActivity::class.java)) }
        }
        val note = TextView(this).apply {
            text = "第一版：默认强制中文 zh + ITN，本地识别；识别后会保守清理‘嗯/呃/额’和明显连续重复。模型下载完成后，识别本身不需要联网。"
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }
        listOf(status, progress, permission, download, enable, test, note).forEach { root.addView(it) }
        setContentView(root)
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = if (ModelInstaller.isInstalled(this)) "模型：已安装 ✓" else "模型：未安装"
    }

    private fun installModel() {
        scope.launch {
            try {
                status.text = "正在下载模型…"
                ModelInstaller.install(this@SetupActivity) { p ->
                    runOnUiThread { progress.progress = p; status.text = "正在下载模型… $p%" }
                }
                progress.progress = 100
                refreshStatus()
                Toast.makeText(this@SetupActivity, "模型安装完成", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                status.text = "安装失败：${t.message}"
            }
        }
    }
}
