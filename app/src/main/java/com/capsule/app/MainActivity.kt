、package com.capsule.app

import android.content.ContentValues
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
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class Capsule(var id:String, var text:String, var audioPath:String?, var hasAudio:Boolean, val laTime:String, var sizeKB:Int, var note:String="")

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val topInfo = findViewById<TextView>(R.id.topInfo)
        val btnGear = findViewById<TextView>(R.id.btnGear)
        val status = findViewById<TextView>(R.id.status)
        val liveText = findViewById<TextView>(R.id.liveText)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = Ad(capsules) { updateTop(topInfo) }
        list.adapter = adapter
        updateTop(topInfo)
        maybeShowKeyDialog(first=true)
        btnGear.setOnClickListener { showKeyDialog() }
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.recCard).setOnClickListener {
            if(isRec) stopAll(status, liveText, topInfo) else startAll(status, liveText, topInfo)
        }
        findViewById<Button>(R.id.btnExportAll).setOnClickListener { exportAll() }
        findViewById<Button>(R.id.btnDelAllAud).setOnClickListener {
            capsules.forEach { it.audioPath?.let { p-> File(p).delete() }; it.audioPath=null; it.hasAudio=false }
            adapter.notifyDataSetChanged(); updateTop(topInfo)
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            capsules.forEach { it.audioPath?.let { p-> File(p).delete() } }; capsules.clear(); adapter.notifyDataSetChanged(); updateTop(topInfo)
        }
    }
    private fun laNow():String{ val sdf=SimpleDateFormat("MM/dd HH:mm:ss", Locale.US); sdf.timeZone=TimeZone.getTimeZone("America/Los_Angeles"); return "洛杉矶 ${sdf.format(Date())}" }
    private fun getKey():String = getSharedPreferences("caps", MODE_PRIVATE).getString("gemini_key","") ?: ""
    private fun maskedKey(k:String):String = if(k.length<=4) "••••" else "••••${k.takeLast(4)}"
    private fun maybeShowKeyDialog(first:Boolean){ if(first && getKey().isNotEmpty()) return; if(first && getKey().isEmpty()) showKeyDialog() }
    private fun showKeyDialog(){
        val cur=getKey(); val et=EditText(this); et.setText(cur); et.textSize=12f
        AlertDialog.Builder(this).setTitle("Key设置 - 3.6保留").setView(et)
            .setPositiveButton("保存"){_,_-> val k=et.text.toString().trim(); if(k.isNotEmpty()){ getSharedPreferences("caps", MODE_PRIVATE).edit().putString("gemini_key", k).apply(); updateTop(findViewById(R.id.topInfo)) } }.setNegativeButton("取消",null).show()
    }
    private fun updateTop(topInfo:TextView){
        topInfo.text="v40-备注整洁版 • ${if(isRec) "录制" else "就绪"} • 已存${capsules.size}条"
    }
    private fun buildSpeechIntent()= android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    private fun startAll(status:TextView, liveText:TextView, topInfo:TextView){
        try{
            interimFinal=""; liveText.text=""; status.text="听着呢..."
            speechRec=SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                setRecognitionListener(object: RecognitionListener{
                    override fun onReadyForSpeech(p:Bundle?){}; override fun onBeginningOfSpeech(){}
                    override fun onRmsChanged(v:Float){}; override fun onBufferReceived(b:ByteArray?){}
                    override fun onEndOfSpeech(){}; override fun onError(e:Int){ if(isRec) android.os.Handler(mainLooper).postDelayed({ restartSpeech() }, 400) }
                    override fun onResults(r:Bundle?){ val list=r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(!list.isNullOrEmpty()){ interimFinal+=list[0]; liveText.text=interimFinal }; if(isRec) restartSpeech() }
                    override fun onPartialResults(p:Bundle?){ val list=p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(!list.isNullOrEmpty()){ liveText.text=interimFinal+list[0] } }
                    override fun onEvent(t:Int,b:Bundle?){}
                }); startListening(buildSpeechIntent())
            }
            val dir=File(getExternalFilesDir(null), "capsules"); if(!dir.exists()) dir.mkdirs()
            audioFile=File(dir, "cap_${System.currentTimeMillis()}.m4a")
            recorder=MediaRecorder().apply{ setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioChannels(1); setAudioSamplingRate(16000); setAudioEncodingBitRate(32000); setOutputFile(audioFile!!.absolutePath); prepare(); start() }
            isRec=true
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
            if(key.isNotEmpty()){ status.text="Gemini 3.6 转写中..."; finalText=transcribeWithGemini36(file, key, localText) }
            if(finalText.isBlank()) finalText=localText.ifBlank { "（空）" }
            capsules.add(0, Capsule(System.currentTimeMillis().toString(), finalText, file.absolutePath, true, laNow(), size, ""))
            adapter.notifyItemInserted(0); updateTop(topInfo); status.text="已保存 (3.6)"; liveText.text="就绪"; interimFinal=""
        }
    }
    private suspend fun transcribeWithGemini36(audioFile:File, apiKey:String, hint:String):String = withContext(Dispatchers.IO){
        try{
            val b64=android.util.Base64.encodeToString(audioFile.readBytes(), android.util.Base64.NO_WRAP)
            val json=JSONObject().apply{
                put("contents", org.json.JSONArray().put(JSONObject().apply{
                    put("parts", org.json.JSONArray().apply{
                        put(JSONObject().apply{ put("inline_data", JSONObject().apply{ put("mime_type","audio/mp4"); put("data", b64) }) })
                        put(JSONObject().apply{ put("text","本地识别：${hint.take(200)}。转成最终中文。") })
                    })
                }))
            }
            for(model in listOf("gemini-3.6-flash", "gemini-2.5-flash", "gemini-flash-latest")){
                try{
                    val req=Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                        .post(json.toString().toRequestBody("application/json".toMediaType())).build()
                    val resp=client.newCall(req).execute(); val body=resp.body?.string()?:""
                    if(!resp.isSuccessful && resp.code==404) continue
                    val txt=JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?:""
                    if(txt.isNotBlank()) return@withContext txt.trim()
                }catch(_:Exception){}
            }
            hint
        }catch(_:Exception){ hint }
    }
    private fun toHtml(content:String):String{
        val esc = content.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        return """<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-size:22px;line-height:1.7;padding:18px;white-space:pre-wrap}</style></head><body>${esc.replace("\n","<br>")}</body></html>"""
    }
    private fun saveHtmlToDownloads(fileName:String, htmlContent:String){
        try{
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                val values = ContentValues().apply{
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName); put(MediaStore.Downloads.MIME_TYPE, "text/html")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)?.use { it.write(htmlContent.toByteArray(Charsets.UTF_8)) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
                Toast.makeText(this, "已保存 $fileName", Toast.LENGTH_LONG).show()
            }
        }catch(_:Exception){}
    }
    private fun exportAll(){
        if(capsules.isEmpty()) return
        val raw = capsules.reversed().joinToString("\n\n"){ "${it.laTime}\n${it.text}${if(it.note.isNotBlank()) "\n备注: ${it.note}" else ""}" }
        saveHtmlToDownloads("闪念胶囊_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())}.html", toHtml(raw))
    }
    class Ad(private val items:MutableList<Capsule>, val onChange:()->Unit): RecyclerView.Adapter<Ad.VH>(){
        class VH(v:android.view.View): RecyclerView.ViewHolder(v){
            val meta=v.findViewById<TextView>(R.id.itemMeta); val txt=v.findViewById<TextView>(R.id.itemText); val note=v.findViewById<TextView>(R.id.itemNote)
            val play=v.findViewById<Button>(R.id.btnPlay); val delAud=v.findViewById<Button>(R.id.btnDelAud); val expOne=v.findViewById<Button>(R.id.btnExportOne)
            val btnNote=v.findViewById<TextView>(R.id.btnNote); val del=v.findViewById<TextView>(R.id.btnDel); var player:MediaPlayer?=null
        }
        override fun onCreateViewHolder(p:android.view.ViewGroup, t:Int)=VH(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_capsule, p, false))
        override fun getItemCount()=items.size
        override fun onBindViewHolder(h:VH, pos:Int){
            val c=items[pos]; h.meta.text=c.laTime; h.txt.text=c.text
            if(c.note.isNotBlank()){ h.note.text=c.note; h.note.visibility=View.VISIBLE; h.btnNote.text="改备注" } else { h.note.visibility=View.GONE; h.btnNote.text="+备注" }

            // 点正文编辑文字，长按加备注
            h.txt.setOnClickListener{ val et=EditText(h.itemView.context); et.setText(c.text); AlertDialog.Builder(h.itemView.context).setTitle("编辑正文").setView(et).setPositiveButton("保存"){_,_-> c.text=et.text.toString(); h.txt.text=c.text }.show() }
            h.txt.setOnLongClickListener{ showNoteDialog(h,c); true }
            h.note.setOnClickListener{ showNoteDialog(h,c) }
            h.btnNote.setOnClickListener{ showNoteDialog(h,c) }

            h.play.setOnClickListener{ c.audioPath?.let{ path-> try{ h.player?.release(); h.player=MediaPlayer().apply{ setDataSource(path); prepare(); start() } }catch(_:Exception){} } }
            h.delAud.setOnClickListener{ c.audioPath?.let{ File(it).delete() }; c.audioPath=null; c.hasAudio=false; onChange() }
            h.expOne.setOnClickListener{
                val ctx=h.itemView.context as MainActivity
                val raw="${c.laTime}\n${c.text}${if(c.note.isNotBlank()) "\n备注: ${c.note}" else ""}"
                ctx.saveHtmlToDownloads("胶囊_${c.id}.html", ctx.toHtml(raw))
            }
            h.del.setOnClickListener{ c.audioPath?.let{ File(it).delete() }; items.removeAt(pos); notifyDataSetChanged(); onChange() }
        }
        private fun showNoteDialog(h:VH, c:Capsule){
            val et=EditText(h.itemView.context); et.setText(c.note); et.hint="写点备注，比如背景、待办..."
            AlertDialog.Builder(h.itemView.context).setTitle("备注").setView(et)
                .setPositiveButton("保存"){_,_->
                    c.note=et.text.toString().trim()
                    if(c.note.isNotBlank()){ h.note.text=c.note; h.note.visibility=View.VISIBLE; h.btnNote.text="改备注" }
                    else { h.note.visibility=View.GONE; h.btnNote.text="+备注" }
                }.setNegativeButton("取消",null)
                .setNeutralButton("清空"){_,_-> c.note=""; h.note.visibility=View.GONE; h.btnNote.text="+备注" }
                .show()
        }
    }
}
