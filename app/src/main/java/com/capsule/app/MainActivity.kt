package com.capsule.app

import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class Capsule(var id:String, var text:String, var audioPath:String?, var hasAudio:Boolean, val laTime:String, var sizeKB:Int)

class MainActivity : AppCompatActivity() {
    private var recorder: MediaRecorder? = null
    private var speechRec: SpeechRecognizer? = null
    private var audioFile: File? = null
    private var isRec = false
    private val capsules = mutableListOf<Capsule>()
    private lateinit var adapter: Ad
    private val client = OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var interimFinal = ""
    private var currentSizeKB = 0
    private var lastErrorCode = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            val topInfo = findViewById<TextView>(R.id.topInfo)
            val btnGear = findViewById<TextView>(R.id.btnGear)
            val recCard = findViewById<MaterialCardView>(R.id.recCard)
            val status = findViewById<TextView>(R.id.status)
            val liveText = findViewById<TextView>(R.id.liveText)
            val list = findViewById<RecyclerView>(R.id.list)
            list.layoutManager = LinearLayoutManager(this)
            adapter = Ad(capsules) { updateTop(topInfo) }
            list.adapter = adapter
            checkPerm()
            updateTop(topInfo)
            maybeShowKeyDialog(first = true)
            btnGear.setOnClickListener { showKeyDialog() }
            recCard.setOnClickListener { if(isRec) stopAll(status, liveText, topInfo) else startAll(status, liveText, topInfo) }
            findViewById<Button>(R.id.btnExportAll).setOnClickListener { exportAll() }
            findViewById<Button>(R.id.btnDelAllAud).setOnClickListener {
                if(capsules.any { it.hasAudio }){ capsules.forEach { it.audioPath?.let { p-> File(p).delete() }; it.audioPath=null; it.hasAudio=false }; adapter.notifyDataSetChanged(); updateTop(topInfo) }
            }
            findViewById<Button>(R.id.btnClear).setOnClickListener { capsules.forEach { it.audioPath?.let { p-> File(p).delete() } }; capsules.clear(); adapter.notifyDataSetChanged(); updateTop(topInfo) }
        } catch(e: Exception){ Log.e("capsule", "crash", e) }
    }
    private fun checkPerm(){ if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 100) } }
    private fun laNow():String{ val sdf=SimpleDateFormat("MM/dd HH:mm:ss", Locale.US); sdf.timeZone=TimeZone.getTimeZone("America/Los_Angeles"); return "洛杉矶 ${sdf.format(Date())}" }
    private fun getKey():String = getSharedPreferences("caps", MODE_PRIVATE).getString("gemini_key","") ?: ""
    private fun maskedKey(k:String):String = if(k.length<=4) "••••" else "••••${k.takeLast(4)}"
    private fun maybeShowKeyDialog(first:Boolean){ if(first && getKey().isNotEmpty()) return; if(first && getKey().isEmpty()) showKeyDialog() }
    private fun showKeyDialog(){
        val cur=getKey(); val et=EditText(this); et.setText(cur); et.textSize=12f
        AlertDialog.Builder(this).setTitle("Key设置").setMessage("当前 ${if(cur.isEmpty()) "未设" else maskedKey(cur)} 已用 3.6-flash").setView(et)
            .setPositiveButton("保存"){_,_-> val k=et.text.toString().trim(); if(k.isNotEmpty()){ getSharedPreferences("caps", MODE_PRIVATE).edit().putString("gemini_key", k).apply(); updateTop(findViewById(R.id.topInfo)) } }.setNegativeButton("取消",null).show()
    }
    private fun updateTop(topInfo:TextView){
        val km=if(getKey().isEmpty()) "未设Key" else maskedKey(getKey())
        val recInfo=if(isRec) "录制 ${currentSizeKB}KB" else "就绪"
        topInfo.text="v34-UTF8 • $recInfo • 已存${capsules.size}条 • $km"
    }
    private fun buildSpeechIntent()= android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
    }
    private fun startAll(status:TextView, liveText:TextView, topInfo:TextView){
        if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ checkPerm(); return }
        try{
            interimFinal=""; liveText.text=""; status.text="启动识别..."
            speechRec=SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                setRecognitionListener(object: RecognitionListener{
                    override fun onReadyForSpeech(p:Bundle?){ status.text="听着呢..." }
                    override fun onBeginningOfSpeech(){ status.text="识别中..." }
                    override fun onRmsChanged(v:Float){}; override fun onBufferReceived(b:ByteArray?){}
                    override fun onEndOfSpeech(){ status.text="处理中..." }
                    override fun onError(e:Int){ if(isRec){ android.os.Handler(mainLooper).postDelayed({ restartSpeech() }, 400) } }
                    override fun onResults(r:Bundle?){ val list=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(!list.isNullOrEmpty()){ interimFinal+=list[0]; liveText.text=interimFinal }; if(isRec) restartSpeech() }
                    override fun onPartialResults(p:Bundle?){ val list=p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(!list.isNullOrEmpty()){ liveText.text=interimFinal+list[0] } }
                    override fun onEvent(t:Int,b:Bundle?){}
                }); startListening(buildSpeechIntent())
            }
            val dir=File(getExternalFilesDir(null), "capsules"); if(!dir.exists()) dir.mkdirs()
            audioFile=File(dir, "cap_${System.currentTimeMillis()}.m4a")
            recorder=MediaRecorder().apply{ setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioChannels(1); setAudioSamplingRate(16000); setAudioEncodingBitRate(32000); setOutputFile(audioFile!!.absolutePath); prepare(); start() }
            isRec=true; status.text="录写中..."
            scope.launch { while(isRec){ audioFile?.let { currentSizeKB=(it.length()/1024).toInt() }; updateTop(topInfo); kotlinx.coroutines.delay(500) } }
        }catch(e:Exception){ Log.e("capsule","start failed",e) }
    }
    private fun restartSpeech(){ if(!isRec) return; try{ speechRec?.startListening(buildSpeechIntent()) }catch(_:Exception){} }
    private fun stopAll(status:TextView, liveText:TextView, topInfo:TextView){
        isRec=false; try{ speechRec?.stopListening(); speechRec?.destroy() }catch(_:Exception){}; speechRec=null
        try{ recorder?.stop(); recorder?.release() }catch(_:Exception){}; recorder=null
        val file=audioFile; val localText=interimFinal.ifBlank { liveText.text.toString() }
        if(file==null || !file.exists()) return
        val size=(file.length()/1024).toInt()
        scope.launch {
            var finalText=localText
            val key=getKey()
            if(key.isNotEmpty()){ status.text="AI 3.6 转写中..."; finalText=transcribeWithGemini(file, key, localText) }
            if(finalText.isBlank()) finalText=localText.ifBlank { "（识别为空）" }
            capsules.add(0, Capsule(System.currentTimeMillis().toString(), finalText, file.absolutePath, true, laNow(), size))
            adapter.notifyItemInserted(0); updateTop(topInfo); status.text="已保存"; liveText.text="就绪"; interimFinal=""
        }
    }
    private suspend fun transcribeWithGemini(audioFile:File, apiKey:String, hint:String):String = withContext(Dispatchers.IO){
        try{
            val b64=android.util.Base64.encodeToString(audioFile.readBytes(), android.util.Base64.NO_WRAP)
            val json=JSONObject().apply{
                put("contents", org.json.JSONArray().put(JSONObject().apply{
                    put("parts", org.json.JSONArray().apply{
                        put(JSONObject().apply{ put("inline_data", JSONObject().apply{ put("mime_type","audio/mp4"); put("data", b64) }) })
                        put(JSONObject().apply{ put("text","本地识别：${hint.take(200)}。转成最终中文文字。") })
                    })
                }))
            }
            val models = listOf("gemini-3.6-flash", "gemini-2.5-flash", "gemini-flash-latest")
            for(model in models){
                try{
                    val req=Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                        .post(json.toString().toRequestBody("application/json".toMediaType())).build()
                    val resp=client.newCall(req).execute(); val body=resp.body?.string()?:""
                    if(!resp.isSuccessful && resp.code==404) continue
                    if(!resp.isSuccessful) return@withContext "Gemini失败: HTTP ${resp.code}"
                    val txt=JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?:""
                    if(txt.isNotBlank()) return@withContext txt.trim()
                }catch(_:Exception){}
            }
            "Gemini失败"
        }catch(e:Exception){ "Gemini失败: ${e.message}" }
    }

    // 关键修复：强制 UTF-8 + BOM
    private fun saveToDownloads(fileName:String, content:String){
        try{
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                val values = ContentValues().apply{
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)?.use { os ->
                    // 写入 UTF-8 BOM，解决中文乱码
                    os.write(0xEF); os.write(0xBB); os.write(0xBF)
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                Toast.makeText(this, "已保存到 下载/$fileName (UTF-8)", Toast.LENGTH_LONG).show()
                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply{
                    type="text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(sendIntent, "导出成功：$fileName"))
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if(!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                // 带 BOM 的 UTF-8
                f.outputStream().use { os ->
                    os.write(0xEF); os.write(0xBB); os.write(0xBF)
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "已保存到 下载/$fileName", Toast.LENGTH_LONG).show()
            }
        } catch(e:Exception){ Log.e("capsule","save failed",e); Toast.makeText(this,"保存失败 ${e.message}",Toast.LENGTH_LONG).show() }
    }

    private fun exportAll(){
        if(capsules.isEmpty()){ Toast.makeText(this,"没有记录",Toast.LENGTH_SHORT).show(); return }
        val content = capsules.reversed().joinToString("\n\n"){ "${it.laTime}\n${it.text}\n" }
        val fileName = "闪念胶囊_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())}.txt"
        saveToDownloads(fileName, content)
    }

    class Ad(private val items:MutableList<Capsule>, val onChange:()->Unit): RecyclerView.Adapter<Ad.VH>(){
        class VH(v:android.view.View): RecyclerView.ViewHolder(v){ val meta=v.findViewById<TextView>(R.id.itemMeta); val txt=v.findViewById<TextView>(R.id.itemText); val seek=v.findViewById<SeekBar>(R.id.seek); val play=v.findViewById<Button>(R.id.btnPlay); val delAud=v.findViewById<Button>(R.id.btnDelAud); val expOne=v.findViewById<Button>(R.id.btnExportOne); val del=v.findViewById<TextView>(R.id.btnDel); var player:MediaPlayer?=null }
        override fun onCreateViewHolder(p:android.view.ViewGroup, t:Int)=VH(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_capsule, p, false))
        override fun getItemCount()=items.size
        override fun onBindViewHolder(h:VH, pos:Int){
            val c=items[pos]; h.meta.text="${c.laTime} • ${c.sizeKB}KB • ${c.text.length}字"; h.txt.text=c.text
            h.txt.setOnClickListener{ val et=EditText(h.itemView.context); et.setText(c.text); AlertDialog.Builder(h.itemView.context).setTitle("编辑").setView(et).setPositiveButton("保存"){_,_-> c.text=et.text.toString(); h.txt.text=c.text }.show() }
            h.play.setOnClickListener{ c.audioPath?.let{ path-> try{ h.player?.release(); h.player=MediaPlayer().apply{ setDataSource(path); prepare(); start() } }catch(_:Exception){} } }
            h.delAud.setOnClickListener{ c.audioPath?.let{ File(it).delete() }; c.audioPath=null; c.hasAudio=false; onChange() }
            h.expOne.setOnClickListener{
                val ctx=h.itemView.context as MainActivity
                ctx.saveToDownloads("胶囊_${c.id}.txt", "${c.laTime}\n${c.text}\n")
            }
            h.del.setOnClickListener{ c.audioPath?.let{ File(it).delete() }; items.removeAt(pos); notifyDataSetChanged(); onChange() }
        }
    }
}
