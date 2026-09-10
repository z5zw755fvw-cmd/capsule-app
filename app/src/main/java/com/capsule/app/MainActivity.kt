package com.capsule.app

import android.Manifest
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
            val startStop = { if(isRec) stopAll(status, liveText, topInfo) else startAll(status, liveText, topInfo) }
            recCard.setOnClickListener { startStop() }
            findViewById<Button>(R.id.btnExportAll).setOnClickListener { exportAll() }
            findViewById<Button>(R.id.btnDelAllAud).setOnClickListener {
                if(capsules.any { it.hasAudio }){ capsules.forEach { it.audioPath?.let { p-> File(p).delete() }; it.audioPath=null; it.hasAudio=false }; adapter.notifyDataSetChanged(); updateTop(topInfo); Toast.makeText(this,"已删全部音频",Toast.LENGTH_SHORT).show() }
            }
            findViewById<Button>(R.id.btnClear).setOnClickListener { capsules.forEach { it.audioPath?.let { p-> File(p).delete() } }; capsules.clear(); adapter.notifyDataSetChanged(); updateTop(topInfo) }
        } catch(e: Exception){ Log.e("capsule", "onCreate crash", e); Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show() }
    }
    private fun checkPerm(){ if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100) } }
    private fun laNow():String{ val sdf=SimpleDateFormat("MM/dd HH:mm:ss", Locale.US); sdf.timeZone=TimeZone.getTimeZone("America/Los_Angeles"); return "洛杉矶 ${sdf.format(Date())}" }
    private fun getKey():String = getSharedPreferences("caps", MODE_PRIVATE).getString("gemini_key","") ?: ""
    private fun maskedKey(k:String):String = if(k.length<=4) "••••" else "••••${k.takeLast(4)}"
    private fun maybeShowKeyDialog(first:Boolean){ if(first && getKey().isNotEmpty()) return; if(first && getKey().isEmpty()) showKeyDialog() }
    private fun showKeyDialog(){
        val cur = getKey(); val et = EditText(this); et.hint = "粘贴 Gemini API Key"; et.setText(cur); et.textSize = 12f
        AlertDialog.Builder(this).setTitle("Gemini Key 设置").setMessage("当前 Key: ${if(cur.isEmpty()) "未设" else maskedKey(cur)}\n已用 3.6-flash 模型").setView(et)
            .setPositiveButton("保存"){_,_-> val k=et.text.toString().trim(); if(k.isNotEmpty()){ getSharedPreferences("caps", MODE_PRIVATE).edit().putString("gemini_key", k).apply(); Toast.makeText(this,"Key 已保存 ${maskedKey(k)}",Toast.LENGTH_SHORT).show(); updateTop(findViewById(R.id.topInfo)) } }
            .setNegativeButton("取消",null).show()
    }
    private fun updateTop(topInfo:TextView){
        val key=getKey(); val km=if(key.isEmpty()) "未设Key" else maskedKey(key); val recInfo=if(isRec) "录制 ${currentSizeKB}KB ${interimFinal.length}字 err=$lastErrorCode" else "就绪"
        topInfo.text="v33-下载版 • $recInfo • 已存${capsules.size}条 • $km"
    }
    private fun buildSpeechIntent()= android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false); putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }
    private fun startAll(status:TextView, liveText:TextView, topInfo:TextView){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ checkPerm(); return }
        if(!SpeechRecognizer.isRecognitionAvailable(this)){ Toast.makeText(this,"本机不支持语音识别",Toast.LENGTH_LONG).show(); return }
        try{
            interimFinal=""; lastErrorCode=-1; liveText.text=""; status.text="启动识别(在线)..."
            speechRec=SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                setRecognitionListener(object: RecognitionListener{
                    override fun onReadyForSpeech(p:Bundle?){ status.text="听着呢(在线)..." }
                    override fun onBeginningOfSpeech(){ status.text="识别中..."; lastErrorCode=0; updateTop(topInfo) }
                    override fun onRmsChanged(v:Float){}; override fun onBufferReceived(b:ByteArray?){}
                    override fun onEndOfSpeech(){ status.text="处理语音... err=$lastErrorCode" }
                    override fun onError(e:Int){ Log.e("capsule","onError:$e"); lastErrorCode=e; status.text="本地识别重试..."; liveText.text="本地识别($e)，结束时用 Gemini 3.6 转写"; updateTop(topInfo); if(isRec){ android.os.Handler(mainLooper).postDelayed({ restartSpeech() }, 500) } }
                    override fun onResults(r:Bundle?){ val list=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(!list.isNullOrEmpty()){ val best=list[0]; if(best.isNotBlank()){ interimFinal+=best; liveText.text=interimFinal; updateTop(topInfo) } }; if(isRec) restartSpeech() }
                    override fun onPartialResults(p:Bundle?){ val list=p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(!list.isNullOrEmpty()){ liveText.text=interimFinal+list[0] } }
                    override fun onEvent(t:Int,b:Bundle?){}
                }); startListening(buildSpeechIntent())
            }
            val dir=File(getExternalFilesDir(null), "capsules"); if(!dir.exists()) dir.mkdirs()
            audioFile=File(dir, "cap_${System.currentTimeMillis()}.m4a")
            recorder=MediaRecorder().apply{ setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioChannels(1); setAudioSamplingRate(16000); setAudioEncodingBitRate(32000); setOutputFile(audioFile!!.absolutePath); prepare(); start() }
            isRec=true; currentSizeKB=0; status.text="录写中(在线)...再点结束"
            scope.launch { while(isRec){ audioFile?.let { currentSizeKB=(it.length()/1024).toInt() }; updateTop(topInfo); delay(500) } }
        }catch(e:Exception){ Log.e("capsule","startAll failed",e) }
    }
    private fun restartSpeech(){ if(!isRec) return; try{ speechRec?.startListening(buildSpeechIntent()) }catch(_:Exception){} }
    private fun stopAll(status:TextView, liveText:TextView, topInfo:TextView){
        isRec=false; try{ speechRec?.stopListening(); speechRec?.destroy() }catch(_:Exception){}; speechRec=null
        try{ recorder?.stop(); recorder?.release() }catch(_:Exception){}; recorder=null
        status.text="处理中..."; val file=audioFile; val localText=interimFinal.ifBlank { liveText.text.toString().let { if(it.contains("本地识别")) "" else it } }
        if(file==null || !file.exists()){ status.text="点一下开始"; return }
        val size=(file.length()/1024).toInt()
        scope.launch {
            val key=getKey(); var finalText=localText
            if(key.isNotEmpty()){ status.text="AI 用 3.6-flash 转写中..."; liveText.text="正在用 Gemini 3.6 转文字..."; finalText=transcribeWithGemini(file, key, localText) }
            if(finalText.isBlank() || finalText.startsWith("Gemini失败")){
                if(!finalText.startsWith("Gemini失败")) finalText=localText.ifBlank { "（识别为空，${size}KB）" }
            }
            val cap=Capsule(System.currentTimeMillis().toString(), finalText, file.absolutePath, true, laNow(), size)
            capsules.add(0, cap); adapter.notifyItemInserted(0); updateTop(topInfo); status.text="已保存，点一下开始"; liveText.text="就绪"; interimFinal=""; currentSizeKB=0
        }
    }
    private suspend fun transcribeWithGemini(audioFile:File, apiKey:String, hint:String):String = withContext(Dispatchers.IO){
        try{
            val b64=android.util.Base64.encodeToString(audioFile.readBytes(), android.util.Base64.NO_WRAP)
            val json=JSONObject().apply{
                put("contents", org.json.JSONArray().put(JSONObject().apply{
                    put("parts", org.json.JSONArray().apply{
                        put(JSONObject().apply{ put("inline_data", JSONObject().apply{ put("mime_type","audio/mp4"); put("data", b64) }) })
                        put(JSONObject().apply{ put("text","本地初步识别：${hint.take(200)}。请把中文语音转成最终文字，只返回文字。") })
                    })
                }))
            }
            val models = listOf("gemini-3.6-flash", "gemini-2.5-flash", "gemini-1.5-flash", "gemini-flash-latest")
            var lastErr=""
            for(model in models){
                try{
                    val req=Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                        .post(json.toString().toRequestBody("application/json".toMediaType())).build()
                    val resp=client.newCall(req).execute(); val body=resp.body?.string()?:""
                    if(!resp.isSuccessful){ lastErr="HTTP ${resp.code} ${body.take(300)}"; if(resp.code==404) continue else return@withContext "Gemini失败: $lastErr" }
                    val obj=JSONObject(body); val txt=obj.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?:""
                    if(txt.isNotBlank()) return@withContext txt.trim()
                }catch(e:Exception){ lastErr=e.message?:"unknown" }
            }
            return@withContext "Gemini失败: $lastErr"
        }catch(e:Exception){ "Gemini失败: ${e.message}" }
    }

    // 新的导出逻辑：存到 Download 目录
    private fun saveToDownloads(fileName:String, content:String): Boolean {
        return try{
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                val values = ContentValues().apply{
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if(uri != null){
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    Toast.makeText(this, "已保存到 下载/$fileName", Toast.LENGTH_LONG).show()
                    // 分享
                    val share = android.content.Intent(android.content.Intent.ACTION_VIEW).apply{
                        setDataAndType(uri, "text/plain"); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    // 直接分享选择器
                    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply{
                        type="text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(sendIntent, "导出成功：$fileName"))
                    true
                } else false
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if(!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName); f.writeText(content)
                Toast.makeText(this, "已保存到 下载/$fileName", Toast.LENGTH_LONG).show()
                true
            }
        } catch(e:Exception){ Log.e("capsule","saveToDownloads failed",e); Toast.makeText(this,"保存失败 ${e.message}",Toast.LENGTH_LONG).show(); false }
    }

    private fun exportAll(){
        if(capsules.isEmpty()){ Toast.makeText(this,"没有记录",Toast.LENGTH_SHORT).show(); return }
        val content = capsules.reversed().joinToString("\n\n"){ "${it.laTime} • ${it.sizeKB}KB\n${it.text}" }
        val fileName = "闪念胶囊_${SimpleDateFormat("yyyy-MM-dd_HH-mm").format(Date())}.txt"
        saveToDownloads(fileName, content)
    }

    class Ad(private val items:MutableList<Capsule>, val onChange:()->Unit): RecyclerView.Adapter<Ad.VH>(){
        class VH(v:android.view.View): RecyclerView.ViewHolder(v){ val meta=v.findViewById<TextView>(R.id.itemMeta); val txt=v.findViewById<TextView>(R.id.itemText); val seek=v.findViewById<SeekBar>(R.id.seek); val play=v.findViewById<Button>(R.id.btnPlay); val delAud=v.findViewById<Button>(R.id.btnDelAud); val expOne=v.findViewById<Button>(R.id.btnExportOne); val del=v.findViewById<TextView>(R.id.btnDel); var player:MediaPlayer?=null }
        override fun onCreateViewHolder(p:android.view.ViewGroup, t:Int)=VH(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_capsule, p, false))
        override fun getItemCount()=items.size
        override fun onBindViewHolder(h:VH, pos:Int){
            val c=items[pos]; h.meta.text="${c.laTime} • ${c.sizeKB}KB • ${c.text.length}字 • ${if(c.hasAudio) "有音频" else "已释放"}"; h.txt.text=c.text
            h.txt.setOnClickListener{ val et=EditText(h.itemView.context); et.setText(c.text); AlertDialog.Builder(h.itemView.context).setTitle("编辑文字").setView(et).setPositiveButton("保存"){_,_-> c.text=et.text.toString(); h.txt.text=c.text; h.meta.text="${c.laTime} • ${c.sizeKB}KB • ${c.text.length}字 • ${if(c.hasAudio) "有音频" else "已释放"}" }.show() }
            h.play.setOnClickListener{ c.audioPath?.let{ path-> try{ h.player?.release(); h.player=MediaPlayer().apply{ setDataSource(path); prepare(); start() } }catch(_:Exception){} } }
            h.delAud.setOnClickListener{ c.audioPath?.let{ File(it).delete() }; c.audioPath=null; c.hasAudio=false; h.meta.text="${c.laTime} • ${c.sizeKB}KB • ${c.text.length}字 • 已释放"; onChange() }
            h.expOne.setOnClickListener{
                // 单条也存到下载
                val fileName="胶囊_${c.id}.txt"; val content="${c.laTime}\n${c.text}"
                val ctx=h.itemView.context as MainActivity
                ctx.saveToDownloads(fileName, content)
            }
            h.del.setOnClickListener{ c.audioPath?.let{ File(it).delete() }; items.removeAt(pos); notifyDataSetChanged(); onChange() }
        }
    }
}
