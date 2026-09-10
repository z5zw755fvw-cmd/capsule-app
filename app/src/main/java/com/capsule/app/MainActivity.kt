
package com.capsule.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var interimFinal = ""
    private var currentSizeKB = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val startStop = {
            if(isRec) stopAll(status, liveText, topInfo) else startAll(status, liveText, topInfo)
        }
        recCard.setOnClickListener { startStop() }

        findViewById<Button>(R.id.btnExportAll).setOnClickListener { exportAll() }
        findViewById<Button>(R.id.btnDelAllAud).setOnClickListener {
            if(capsules.any { it.hasAudio }){
                capsules.forEach { it.audioPath?.let { p-> File(p).delete() }; it.audioPath=null; it.hasAudio=false }
                adapter.notifyDataSetChanged(); updateTop(topInfo); Toast.makeText(this,"已删全部音频",Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            capsules.forEach { it.audioPath?.let { p-> File(p).delete() } }
            capsules.clear(); adapter.notifyDataSetChanged(); updateTop(topInfo)
        }
    }

    private fun checkPerm(){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }
    private fun laNow():String{
        val sdf=SimpleDateFormat("MM/dd HH:mm:ss", Locale.US); sdf.timeZone=TimeZone.getTimeZone("America/Los_Angeles"); return "洛杉矶 ${sdf.format(Date())}"
    }
    private fun getKey():String = getSharedPreferences("caps", MODE_PRIVATE).getString("gemini_key","") ?: ""
    private fun maskedKey(k:String):String = if(k.length<=4) "••••" else "••••${k.takeLast(4)}"

    private fun maybeShowKeyDialog(first:Boolean){
        if(first && getKey().isNotEmpty()) return
        if(first && getKey().isEmpty()) showKeyDialog()
    }
    private fun showKeyDialog(){
        val cur = getKey()
        val et = EditText(this); et.hint = "粘贴 Gemini API Key (aistudio.google.com)"; et.setText(cur); et.textSize = 12f
        val msg = if(cur.isEmpty()) "首次使用请输入 Gemini Key\n只存本机，不上传，不公开\n发给朋友时是干净版，朋友自己申请" else "当前 Key: ${maskedKey(cur)}\n只存本机，可修改"
        AlertDialog.Builder(this).setTitle("Gemini Key 设置").setMessage(msg).setView(et)
            .setPositiveButton("保存"){_,_->
                val k = et.text.toString().trim()
                if(k.isNotEmpty()){
                    getSharedPreferences("caps", MODE_PRIVATE).edit().putString("gemini_key", k).apply()
                    Toast.makeText(this,"Key 已保存 ${maskedKey(k)}",Toast.LENGTH_SHORT).show()
                    updateTop(findViewById(R.id.topInfo))
                }
            }
            .setNegativeButton("取消",null).show()
    }
    private fun updateTop(topInfo:TextView){
        val key = getKey()
        val km = if(key.isEmpty()) "未设Key" else maskedKey(key)
        val recInfo = if(isRec) "录制 ${currentSizeKB}KB ${interimFinal.length}字" else "就绪"
        topInfo.text = "v27 • $recInfo • 已存${capsules.size}条 • $km"
    }

    // 开始：原生单声道录 + SpeechRecognizer 边识别边显示
    private fun startAll(status:TextView, liveText:TextView, topInfo:TextView){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ checkPerm(); return }
        try{
            val dir=File(getExternalFilesDir(null), "capsules"); if(!dir.exists()) dir.mkdirs()
            audioFile=File(dir, "cap_${System.currentTimeMillis()}.m4a")
            recorder=MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1); setAudioSamplingRate(16000); setAudioEncodingBitRate(32000)
                setOutputFile(audioFile!!.absolutePath); prepare(); start()
            }
            // 边录边显 用 SpeechRecognizer
            interimFinal=""
            speechRec=SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object: RecognitionListener{
                    override fun onReadyForSpeech(p:Bundle?){ status.text="听着呢..." }
                    override fun onBeginningOfSpeech(){}
                    override fun onRmsChanged(v:Float){}
                    override fun onBufferReceived(b:ByteArray?){}
                    override fun onEndOfSpeech(){}
                    override fun onError(e:Int){ if(isRec){ restartSpeech(liveText, topInfo) } }
                    override fun onResults(r:Bundle?){
                        val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(!list.isNullOrEmpty()){ interimFinal += list[0]; liveText.text = interimFinal; updateTop(topInfo) }
                        if(isRec) restartSpeech(liveText, topInfo)
                    }
                    override fun onPartialResults(p:Bundle?){
                        val list = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(!list.isNullOrEmpty()){ liveText.text = interimFinal + list[0]; updateTop(topInfo) }
                    }
                    override fun onEvent(t:Int,b:Bundle?){}
                })
                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                startListening(intent)
            }
            isRec=true; currentSizeKB=0; status.text="录写中...再点结束"; liveText.text=""; 
            // 定时更新大小
            scope.launch { while(isRec){ audioFile?.let { currentSizeKB = (it.length()/1024).toInt() }; updateTop(topInfo); delay(500) } }
        }catch(e:Exception){ Toast.makeText(this,"开始失败 ${e.message}",Toast.LENGTH_SHORT).show() }
    }
    private fun restartSpeech(liveText:TextView, topInfo:TextView){
        if(!isRec) return
        try{
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRec?.startListening(intent)
        }catch(_:Exception){}
    }
    private fun stopAll(status:TextView, liveText:TextView, topInfo:TextView){
        isRec=false
        try{ speechRec?.stopListening(); speechRec?.destroy() }catch(_:Exception){}
        speechRec=null
        try{ recorder?.stop(); recorder?.release() }catch(_:Exception){}
        recorder=null
        status.text="AI 上下文校正中..."
        val file = audioFile
        val localText = interimFinal.ifBlank { liveText.text.toString() }
        if(file==null || !file.exists()){
            status.text="点一下开始"; return
        }
        val size = (file.length()/1024).toInt()
        scope.launch {
            val key = getKey()
            var finalText = localText
            if(key.isNotEmpty()){
                liveText.text = "Google AI 按上下文纠错中..."
                finalText = transcribeWithGemini(file, key, localText)
            }
            val cap = Capsule(System.currentTimeMillis().toString(), finalText.ifBlank { "（无文字，原声已保留）" }, file.absolutePath, true, laNow(), size)
            capsules.add(0, cap); adapter.notifyItemInserted(0); updateTop(topInfo)
            status.text="已保存，点一下开始"; liveText.text="就绪，点一下开始录写"
            interimFinal=""; currentSizeKB=0
        }
    }
    private suspend fun transcribeWithGemini(audioFile:File, apiKey:String, hint:String):String = withContext(Dispatchers.IO){
        try{
            val b64 = android.util.Base64.encodeToString(audioFile.readBytes(), android.util.Base64.NO_WRAP)
            val json = JSONObject().apply{
                put("contents", org.json.JSONArray().put(JSONObject().apply{
                    put("parts", org.json.JSONArray().apply{
                        put(JSONObject().apply{ put("inline_data", JSONObject().apply{ put("mime_type","audio/mp4"); put("data", b64) }) })
                        put(JSONObject().apply{ put("text","本地初步识别为：${hint.take(200)}。请结合音频，把这段中文语音转成最终文字，要求：1. 根据上下文纠正同音字，短句“开始吧”不要识别成 S8 2. 保留口语，不要乱码 3. 只返回最终文字。") })
                    })
                }))
            }
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                .post(json.toString().toRequestBody("application/json".toMediaType())).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val obj = JSONObject(body)
            val txt = obj.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""
            if(txt.isNotBlank()) txt.trim() else hint
        }catch(e:Exception){ hint.ifBlank { "（AI 校正失败，原声已保留） ${e.message}" } }
    }
    private fun exportAll(){
        if(capsules.isEmpty()){ Toast.makeText(this,"没有记录",Toast.LENGTH_SHORT).show(); return }
        val content = capsules.reversed().joinToString("\n\n"){ "${it.laTime} • ${it.sizeKB}KB\n${it.text}" }
        val file = File(getExternalFilesDir(null), "胶囊_${SimpleDateFormat("yyyy-MM-dd").format(Date())}.txt")
        file.writeText(content); Toast.makeText(this,"已导出 ${file.absolutePath}",Toast.LENGTH_LONG).show()
    }
    class Ad(private val items:MutableList<Capsule>, val onChange:()->Unit): RecyclerView.Adapter<Ad.VH>(){
        class VH(v:android.view.View): RecyclerView.ViewHolder(v){
            val meta=v.findViewById<TextView>(R.id.itemMeta); val txt=v.findViewById<TextView>(R.id.itemText)
            val seek=v.findViewById<SeekBar>(R.id.seek); val play=v.findViewById<Button>(R.id.btnPlay)
            val delAud=v.findViewById<Button>(R.id.btnDelAud); val expOne=v.findViewById<Button>(R.id.btnExportOne); val del=v.findViewById<TextView>(R.id.btnDel)
            var player:MediaPlayer? = null
        }
        override fun onCreateViewHolder(p:android.view.ViewGroup, t:Int)=VH(android.view.LayoutInflater.from(p.context).inflate(R.layout.item_capsule, p, false))
        override fun getItemCount()=items.size
        override fun onBindViewHolder(h:VH, pos:Int){
            val c=items[pos]
            h.meta.text="${c.laTime} • ${c.sizeKB}KB • ${c.text.length}字 • ${if(c.hasAudio) "有音频" else "已释放"}"
            h.txt.text=c.text
            // 文字直接可编辑
            h.txt.setOnClickListener{
                val et=EditText(h.itemView.context); et.setText(c.text)
                AlertDialog.Builder(h.itemView.context).setTitle("编辑文字").setView(et)
                    .setPositiveButton("保存"){_,_-> c.text=et.text.toString(); h.txt.text=c.text; h.meta.text="${c.laTime} • ${c.sizeKB}KB • ${c.text.length}字 • ${if(c.hasAudio) "有音频" else "已释放"}" }.show()
            }
            h.play.setOnClickListener{
                c.audioPath?.let{ path->
                    try{
                        h.player?.release()
                        h.player=MediaPlayer().apply{ setDataSource(path); prepare(); start() }
                    }catch(_:Exception){}
                }
            }
            h.delAud.setOnClickListener{
                c.audioPath?.let{ File(it).delete() }; c.audioPath=null; c.hasAudio=false
                h.meta.text="${c.laTime} • ${c.sizeKB}KB • ${c.text.length}字 • 已释放"; onChange()
            }
            h.expOne.setOnClickListener{
                val f=File(h.itemView.context.getExternalFilesDir(null), "胶囊_${c.id}.txt"); f.writeText("${c.laTime}\n${c.text}"); Toast.makeText(h.itemView.context,"已导出 ${f.name}",Toast.LENGTH_SHORT).show()
            }
            h.del.setOnClickListener{
                c.audioPath?.let{ File(it).delete() }; items.removeAt(pos); notifyDataSetChanged(); onChange()
            }
        }
    }
}
