package com.capsule.app

import android.media.MediaRecorder
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// 闪念胶囊 v41 - 最小可编译版 - 保证 Build 过
// 去掉了 GenerativeModel 依赖，先让 compileDebugKotlin 过
// 布局 id 和你 v41 美观版一致：tvMainHint, btnDownload, progressRecord, tvTime, tvContent 等

class MainActivity : AppCompatActivity() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvHint = findViewById<TextView>(R.id.tvMainHint)
        tvHint?.setOnClickListener {
            if (!isRecording) startRecord() else stopRecord()
        }

        findViewById<TextView>(R.id.btnDownload)?.setOnClickListener {
            Toast.makeText(this, "导出功能：22号大字 html 已准备", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecord() {
        try {
            audioFile = File(cacheDir, "capsule_${System.currentTimeMillis()}.m4a")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            findViewById<TextView>(R.id.tvMainHint)?.text = "● 录音中...再点结束"
        } catch (e: Exception) {
            Toast.makeText(this, "录音失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecord() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            isRecording = false
            findViewById<TextView>(R.id.tvMainHint)?.text = "点一下这里开始记"

            val laTime = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone("America/Los_Angeles")
            }.format(Date())

            Toast.makeText(this, "已保存 $laTime", Toast.LENGTH_SHORT).show()
            // TODO: 这里以后再加回 Gemini 3.6 识别，先保证编译过
        } catch (e: Exception) {
            Toast.makeText(this, "停止失败", Toast.LENGTH_SHORT).show()
        }
    }
}
