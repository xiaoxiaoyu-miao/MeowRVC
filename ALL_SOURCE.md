# MeowRVC (喵喵RVC) - 源码全文

AI 实时变声器 for Android。基于 RVC + ONNX Runtime，支持扬声器外放让微信/QQ 拾取变声。

GitHub: https://github.com/xiaoxiaoyu-miao/MeowRVC

---

## 目录

1. [项目结构说明](#项目结构说明)
2. [主界面 - MainActivity.kt](#1-mainactivitykt)
3. [悬浮窗服务 - FloatMicService.kt](#2-floatmicservicekt)
4. [推理引擎 - RVCOnnxEngine.kt](#3-rvconxnenginekt)
5. [实时音频 - RVCRealtime.kt](#4-rvcrealtimekt)
6. [模型管理 - RVCModelManager.kt](#5-rvcmodelmanagerkt)
7. [WAV 读写 - WavFile.kt](#6-wavfilekt)
8. [主界面布局 - activity_main.xml](#7-activity_mainxml)
9. [悬浮窗布局 - float_mic.xml](#8-float_micxml)
10. [字符串资源 - strings.xml](#9-stringsxml)
11. [主 Manifest - app/AndroidManifest.xml](#10-appandroidmanifestxml)
12. [库 Manifest - voicechanger/AndroidManifest.xml](#11-voicechangerandroidmanifestxml)

---

## 项目结构说明

```
VoiceChanger/
├── app/                                    # 主应用模块
│   ├── build.gradle.kts                    # 构建配置
│   └── src/main/
│       ├── AndroidManifest.xml             # 权限 + Activity 声明
│       ├── java/.../demo/MainActivity.kt   # 主界面
│       └── res/
│           ├── drawable/icon.png           # 应用图标
│           ├── layout/activity_main.xml    # 主界面布局
│           └── values/strings.xml          # 文字资源
├── voicechanger/                           # 库模块
│   ├── build.gradle.kts
│   ├── libs/onnxruntime.jar               # ONNX Runtime (需自行下载)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                            # C++ (已空, 仅占位)
│       ├── java/.../voicechanger/
│       │   ├── FloatMicService.kt          # 悬浮窗 + 录音 + 外放
│       │   ├── RVCOnnxEngine.kt            # AI 推理引擎 (核心)
│       │   ├── RVCRealtime.kt              # 实时音频流水线
│       │   ├── RVCModelManager.kt          # 模型扫描管理
│       │   ├── WavFile.kt                  # WAV 文件读写
│       │   ├── VoiceRecorder.kt            # (遗留) 录音器
│       │   ├── VoicePlayer.kt              # (遗留) 播放器
│       │   └── ...                         # 其他遗留文件
│       └── res/layout/float_mic.xml        # 悬浮窗布局
├── build.gradle.kts                        # 根构建配置
├── settings.gradle.kts                     # 模块声明
├── gradle.properties                       # Gradle 属性
└── local.properties                        # SDK/NDK 路径
```

### 核心依赖 (需手动下载)

| 文件 | 来源 | 作用 |
|------|------|------|
| `voicechanger/libs/onnxruntime.jar` | onnxruntime-android-1.18.0.aar 中提取 | ONNX Runtime Java API |
| `voicechanger/src/main/jniLibs/arm64-v8a/libonnxruntime.so` | 同上 | ONNX Runtime 原生库 |
| `voicechanger/src/main/jniLibs/arm64-v8a/libonnxruntime4j_jni.so` | 同上 | JNI 桥接库 |

下载地址: https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.18.0/

---

## 1. MainActivity.kt

主界面：模型选择、音高调节、延时调节、实时变声、启动悬浮窗。

```kotlin

EOF
# Read each source file and append
echo "" >> ALL_SOURCE.md

# MainActivity.kt
echo '```kotlin' >> ALL_SOURCE.md
cat app/src/main/java/io/github/neboyang/voicechanger/demo/MainActivity.kt >> ALL_SOURCE.md
echo '```' >> ALL_SOURCE.md

# FloatMicService.kt
echo -e "\n## 2. FloatMicService.kt\n\n悬浮窗服务：录音、RVC 处理、扬声器外放、自动检测麦克风占用。\n\n\`\`\`kotlin" >> ALL_SOURCE.md
cat voicechanger/src/main/java/io/github/neboyang/voicechanger/FloatMicService.kt >> ALL_SOURCE.md
echo '```' >> ALL_SOURCE.md

# RVCOnnxEngine.kt
echo -e "\n## 3. RVCOnnxEngine.kt\n\nAI 推理引擎核心：加载 ONNX 模型，执行 HuBERT → TextEncoder → Flow → Generator 全链路。\n\n\`\`\`kotlin" >> ALL_SOURCE.md
cat voicechanger/src/main/java/io/github/neboyang/voicechanger/RVCOnnxEngine.kt >> ALL_SOURCE.md
echo '```' >> ALL_SOURCE.md

# RVCRealtime.kt
echo -e "\n## 4. RVCRealtime.kt\n\n实时音频流水线：AudioRecord 麦克风 → 降采样 → RVC 推理 → AudioTrack 扬声器。\n\n\`\`\`kotlin" >> ALL_SOURCE.md
cat voicechanger/src/main/java/io/github/neboyang/voicechanger/RVCRealtime.kt >> ALL_SOURCE.md
echo '```' >> ALL_SOURCE.md

echo "Files concatenated"


## 1. MainActivity.kt

主界面：模型选择、音高调节、延时调节、实时变声、权限管理。

```kotlin
package io.github.neboyang.voicechanger.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import io.github.neboyang.voicechanger.FloatMicService
import io.github.neboyang.voicechanger.RVCModelManager
import io.github.neboyang.voicechanger.RVCRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: RVCModelManager
    private val rvcRealtime = RVCRealtime()
    private lateinit var tvStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvF0Key: TextView
    private lateinit var tvLatency: TextView
    private lateinit var modelList: ChipGroup
    private lateinit var sliderF0Key: Slider
    private lateinit var sliderLatency: Slider
    private lateinit var btnRvcRealtime: MaterialButton
    private lateinit var btnFloat: MaterialButton
    private var currentModelDir: File? = null

    private val storageIntentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshModelList() }
    private val storagePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) refreshModelList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modelManager = RVCModelManager(this)

        // 启动时请求所有必要权限
        requestAllPermissions()

        tvStatus = findViewById(R.id.tvStatus)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvF0Key = findViewById(R.id.tvF0Key)
        tvLatency = findViewById(R.id.tvLatency)
        modelList = findViewById(R.id.modelList)
        sliderF0Key = findViewById(R.id.sliderF0Key)
        sliderLatency = findViewById(R.id.sliderLatency)
        btnRvcRealtime = findViewById(R.id.btnRvcRealtime)
        btnFloat = findViewById(R.id.btnFloat)

        sliderF0Key.addOnChangeListener { _, value, _ ->
            tvF0Key.text = getString(R.string.rvc_f0_key, value.toInt())
            rvcRealtime.f0UpKey = value.toInt()
            io.github.neboyang.voicechanger.FloatMicService.f0UpKeyRef = value.toInt()
        }
        tvF0Key.text = getString(R.string.rvc_f0_key, 0)

        sliderLatency.addOnChangeListener { _, value, _ ->
            tvLatency.text = getString(R.string.rvc_latency, value)
            rvcRealtime.latencyMs = (value * 1000).toInt()
        }
        tvLatency.text = getString(R.string.rvc_latency, 1.0)

        btnRvcRealtime.setOnClickListener {
            if (rvcRealtime.isRunning.value) rvcRealtime.stop()
            else {
                if (currentModelDir == null) { Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                rvcRealtime.onError = { t -> runOnUiThread { Toast.makeText(this, "错误: ${t.message}", Toast.LENGTH_LONG).show() } }
                withRecordPermission { rvcRealtime.start() }
            }
        }

        lifecycleScope.launch {
            rvcRealtime.isRunning.collect { running ->
                btnRvcRealtime.text = if (running) "停止" else "开始 AI 实时变声"
                tvStatus.text = if (running) "运行中…" else "就绪"
            }
        }

        // 悬浮窗按钮
        btnFloat.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "请允许悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                Toast.makeText(this, "请允许通知权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            FloatMicService.engineRef = rvcRealtime.engine
            FloatMicService.start(this)
            Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnRefreshModels).setOnClickListener {
            if (!checkStoragePermission()) { requestStoragePermission(); return@setOnClickListener }
            refreshModelList()
        }
        findViewById<MaterialButton>(R.id.btnDeleteModel).setOnClickListener {
            val dir = currentModelDir ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("删除模型")
                .setMessage("确定删除「${dir.name}」？")
                .setPositiveButton("删除") { _, _ -> modelManager.deleteModel(dir.name); currentModelDir = null; refreshModelList() }
                .setNegativeButton("取消", null).show()
        }

        modelManager.scanLocalModels()
        refreshModelList()
    }

    private fun refreshModelList() {
        modelManager.scanLocalModels()
        val models = modelManager.models.value
        modelList.removeAllViews()
        if (models.isEmpty()) { tvModelStatus.setText(R.string.rvc_no_models); btnRvcRealtime.isEnabled = false; return }
        models.forEach { info -> modelList.addView(Chip(this).apply { text = info.name; isCheckable = true; setOnClickListener { loadSelectedModel(info) } }) }
        (modelList.getChildAt(0) as? Chip)?.isChecked = true; loadSelectedModel(models.first())
    }

    private fun loadSelectedModel(info: RVCModelManager.ModelInfo) {
        val dir = modelManager.getModelDir(info.id)
        if (!modelManager.isModelDownloaded(info.id)) return
        tvModelStatus.text = getString(R.string.rvc_model_loading)
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = rvcRealtime.loadModel(dir)
            withContext(Dispatchers.Main) {
                if (ok) { currentModelDir = dir; tvModelStatus.text = getString(R.string.rvc_model_loaded, info.name); btnRvcRealtime.isEnabled = true }
                else tvModelStatus.setText(R.string.rvc_model_failed)
            }
        }
    }

    private fun checkStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:$packageName") })
        } else storagePermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requestAllPermissions() {
        // 录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        // 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) pendingAction?.invoke(); pendingAction = null }
    private var pendingAction: (() -> Unit)? = null

    private fun withRecordPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingAction = action; permLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }

    override fun onDestroy() { super.onDestroy(); rvcRealtime.release() }
}

```


## 2. FloatMicService.kt

悬浮窗服务：录音、RVC 处理、扬声器外放、AudioRecordingCallback 检测麦克风占用。

```kotlin
package io.github.neboyang.voicechanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloatMicService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var floatView: View
    private var tvStatus: TextView? = null
    private var isRecording = false
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var engine: RVCOnnxEngine? = null
    private var lastPlayTime = 0L
    private var isOurRecording = false
    private var audioManager: AudioManager? = null

    companion object {
        var engineRef: RVCOnnxEngine? = null
        var f0UpKeyRef: Int = 0
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, FloatMicService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, FloatMicService::class.java)) }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            if (isOurRecording) return
            val active = configs?.any { it.clientAudioSource == MediaRecorder.AudioSource.MIC ||
                it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION } == true
            if (active) {
                tvStatus?.text = "检测到录音"
                playLatest()
            }
        }
    }

    private fun playLatest() {
        if (System.currentTimeMillis() - lastPlayTime < 3000) return
        lastPlayTime = System.currentTimeMillis()

        Thread({
            try {
                val dir = File("/sdcard/rvc")
                val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }
                if (wavs.isNullOrEmpty()) { tvStatus?.post { tvStatus?.text = "没有音频" }; return@Thread }
                val f = wavs.maxByOrNull { it.lastModified() }!!
                val d = f.readBytes()
                val sr = ByteBuffer.wrap(d, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val ch = ByteBuffer.wrap(d, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val bits = ByteBuffer.wrap(d, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val off = if (d[0] == 0x52.toByte()) 44 else 0
                val pcm = d.copyOfRange(off, d.size)
                val cm = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val enc = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
                val dur = pcm.size.toLong() * 1000 / (sr * ch * bits / 8)

                val bufSize = AudioTrack.getMinBufferSize(sr, cm, enc)
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(enc).setSampleRate(sr).setChannelMask(cm).build())
                    .setBufferSizeInBytes(bufSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                if (tr.state != AudioTrack.STATE_INITIALIZED) { tvStatus?.post { tvStatus?.text = "播放失败" }; return@Thread }

                Handler(Looper.getMainLooper()).post {
                    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (Build.VERSION.SDK_INT >= 31) {
                        val speaker = audioManager?.availableCommunicationDevices
                            ?.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null) audioManager?.setCommunicationDevice(speaker)
                        else audioManager?.isSpeakerphoneOn = true
                    } else {
                        audioManager?.isSpeakerphoneOn = true
                    }
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC,
                        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15, 0)
                }
                Thread.sleep(300)

                tr.play()
                var offset = 0
                while (offset < pcm.size) {
                    val chunk = minOf(pcm.size - offset, bufSize)
                    tr.write(pcm, offset, chunk)
                    offset += chunk
                }

                tvStatus?.post { tvStatus?.text = "外放中…" }
                val maxWait = dur + 2000
                val started = System.currentTimeMillis()
                while (System.currentTimeMillis() - started < maxWait) {
                    if (tr.playState != AudioTrack.PLAYSTATE_PLAYING) break
                    Thread.sleep(50)
                }
                tr.stop(); tr.release()

                Handler(Looper.getMainLooper()).post {
                    if (Build.VERSION.SDK_INT >= 31) audioManager?.clearCommunicationDevice()
                    audioManager?.isSpeakerphoneOn = false
                    audioManager?.mode = AudioManager.MODE_NORMAL
                }
                tvStatus?.post { tvStatus?.text = "点录制" }
            } catch (e: Exception) {
                Log.e("RVC", "playLatest", e)
            }
        }).start()
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotifChannel()
        startForeground(1001, Notification.Builder(this, "rvc_float")
            .setContentTitle("MeowRVC").setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build())

        floatView = LayoutInflater.from(this).inflate(R.layout.float_mic, null)
        tvStatus = floatView.findViewById(R.id.tvFloatStatus)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START; p.x = 100; p.y = 200
        wm.addView(floatView, p)

        var dx = 0f; var dy = 0f
        floatView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX - p.x; dy = ev.rawY - p.y; false }
                MotionEvent.ACTION_MOVE -> { p.x = (ev.rawX - dx).toInt(); p.y = (ev.rawY - dy).toInt(); wm.updateViewLayout(floatView, p); true }
                else -> false
            }
        }

        floatView.findViewById<View>(R.id.btnFloatClose)?.setOnClickListener { stopSelf() }

        floatView.setOnClickListener {
            if (engine?.isLoaded() != true) {
                Thread {
                    for (i in 0..10) { if (engine?.isLoaded() == true) break; engine = engineRef; Thread.sleep(1000) }
                    floatView.post { tvStatus?.text = if (engine?.isLoaded() == true) "点录制" else "未加载" }
                }.start(); return@setOnClickListener
            }
            if (isRecording) { isRecording = false; isOurRecording = false; recordThread?.join(3000); tvStatus?.text = "点录制" }
            else startRecord()
        }

        floatView.setOnLongClickListener { playLatest(); true }

        engine = engineRef
        tvStatus?.text = if (engine?.isLoaded() == true) "点录制" else "等待模型…"
        if (engine?.isLoaded() != true) Thread {
            for (i in 0..10) { if (engine?.isLoaded() == true) break; engine = engineRef; Thread.sleep(1000) }
            floatView.post { tvStatus?.text = if (engine?.isLoaded() == true) "点录制" else "未加载" }
        }.start()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 23) {
            audioManager?.registerAudioRecordingCallback(recordingCallback, null)
            tvStatus?.text = "点录制(自动检测)"
        } else {
            tvStatus?.text = "点录制"
        }
    }

    private fun startRecord() {
        val sr = 48000
        val bs = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs * 2)
        audioRecord!!.startRecording(); isRecording = true; isOurRecording = true; tvStatus?.text = "录音中…"

        recordThread = Thread({
            val list = mutableListOf<ShortArray>(); val buf = ShortArray(bs)
            while (isRecording) { val r = audioRecord!!.read(buf, 0, buf.size); if (r > 0) list.add(buf.copyOf(r)) }
            audioRecord!!.stop(); audioRecord!!.release(); audioRecord = null
            val total = list.sumOf { it.size }; val fa = FloatArray(total); var o = 0
            for (a in list) for (s in a) fa[o++] = s / 32768f
            tvStatus?.post { tvStatus?.text = "处理中…" }
            val inp = FloatArray(fa.size / 3) { fa[it * 3] }
            val res = engine?.infer(inp, f0UpKeyRef)
            if (res != null && res.isNotEmpty()) {
                File("/sdcard/rvc").mkdirs()
                io.github.neboyang.voicechanger.WavFile.write(File("/sdcard/rvc", "voice_${System.currentTimeMillis()}.wav"), res, 40000)
                tvStatus?.post { tvStatus?.text = "已保存" }
            } else tvStatus?.post { tvStatus?.text = "处理失败" }
        }, "float-record").also { it.start() }
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel("rvc_float", "MeowRVC", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        isRecording = false; recordThread?.join(2000)
        if (Build.VERSION.SDK_INT >= 23) audioManager?.unregisterAudioRecordingCallback(recordingCallback)
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { wm.removeView(floatView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}

```


## 3. RVCOnnxEngine.kt

AI 推理引擎核心：加载 ONNX 模型(hubert / text_encoder / flow / generator)，执行全链路推理，支持 NNAPI/NPU/GPU。

```kotlin
package io.github.neboyang.voicechanger

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * RVC inference engine using ONNX Runtime (NNAPI/GPU capable).
 */
class RVCOnnxEngine {
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private var loaded = false

    var targetSr = 40000
        private set

    fun load(modelDir: File): Boolean {
        try {
            val cfgFile = File(modelDir, "config.json")
            if (cfgFile.exists()) {
                val json = org.json.JSONObject(cfgFile.readText())
                targetSr = json.optInt("target_sr", 40000)
            }
            // Provider priority: NNAPI > Xnnpack > CPU
            val available = OrtEnvironment.getAvailableProviders().toList()
            Log.e("RVC", "Available providers: $available")
            val opts = OrtSession.SessionOptions()
            opts.addConfigEntry("session.intra_op.allow_spinning", "1")
            // Try NNAPI first
            opts.addConfigEntry("session.set_providers", "NnapiExecutionProvider,XnnpackExecutionProvider,CPUExecutionProvider")

            for (name in listOf("hubert", "text_encoder", "flow", "generator")) {
                val path = File(modelDir, "$name.onnx")
                if (!path.exists()) {
                    Log.e("RVC", "Missing: $path")
                    return false
                }
                sessions[name] = env.createSession(path.absolutePath, opts)
                Log.e("RVC", "Loaded $name")
            }
            loaded = true
            return true
        } catch (e: Exception) {
            Log.e("RVC", "Load failed", e)
            loaded = false
            return false
        }
    }

    fun isLoaded() = loaded

    fun unload() {
        stopServer()
        sessions.values.forEach { it.close() }; sessions.clear(); loaded = false
    }

    /** Start a TCP server for LSPosed module to connect to */
    private var serverThread: Thread? = null
    private var serverSock: java.net.ServerSocket? = null

    fun startServer(port: Int = 8181) {
        stopServer()
        serverThread = Thread({
            try {
                serverSock = java.net.ServerSocket(port)
                Log.e("RVC", "Server listening on $port")
                while (true) {
                    val client = serverSock!!.accept()
                    Thread({
                        try {
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream()))
                            val writer = java.io.OutputStreamWriter(client.getOutputStream())
                            while (true) {
                                val line = reader.readLine() ?: break
                                val req = org.json.JSONObject(line)
                                val audioArr = req.getJSONArray("audio")
                                val audio = FloatArray(audioArr.length()) { audioArr.getDouble(it).toFloat() }
                                val key = req.optInt("f0_up_key", 0)
                                val result = infer(audio, key) ?: FloatArray(0)
                                val resp = org.json.JSONObject().apply {
                                    put("audio", result.toList())
                                    put("sr", targetSr)
                                }
                                writer.write(resp.toString() + "\n")
                                writer.flush()
                            }
                        } catch (_: Exception) {}
                        finally { try { client.close() } catch (_: Exception) {} }
                    }, "rvc-srv-$port").start()
                }
            } catch (_: Exception) {}
        }, "rvc-server").apply { isDaemon = true; start() }
    }

    fun stopServer() {
        try { serverSock?.close() } catch (_: Exception) {}
        serverSock = null
        serverThread?.join(1000)
        serverThread = null
    }

    /**
     * Process audio (16kHz mono) through RVC.
     */
    fun infer(audio: FloatArray, f0UpKey: Int = 0): FloatArray? {
        if (!loaded || audio.size < 320) return null
        try {
            val maxFrames = 50
            val hop = 160
            val totalFrames = audio.size / hop
            val allOutput = mutableListOf<FloatArray>()

            for (seg in 0 until (totalFrames + maxFrames - 1) / maxFrames) {
                try {
                val start = seg * maxFrames * hop
                val segSamples = minOf(maxFrames * hop, audio.size - start)
                if (segSamples < hop * 2) continue
                val segAudio = audio.copyOfRange(start, start + segSamples)
                val sl = minOf(segSamples / hop, maxFrames)
                if (sl <= 0) continue

                // HuBERT
                val hubertIn = OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(segAudio), longArrayOf(1, segAudio.size.toLong()))
                val hubertOut = sessions["hubert"]!!.run(mapOf("input_values" to hubertIn))
                val feat = (hubertOut.get("features").get() as OnnxTensor).floatBuffer
                val featArr = FloatArray(feat.remaining()).also { feat.get(it) }
                val featDim = 768
                val T = featArr.size / featDim
                if (T < 1) continue

                // Interpolate to sl frames
                val interp = FloatArray(sl * featDim)
                for (t in 0 until sl) {
                    val srcT = (t * T / sl).coerceIn(0, T - 1)
                    val srcPos = (srcT * featDim).coerceAtMost(featArr.size - featDim)
                    System.arraycopy(featArr, srcPos, interp, t * featDim, featDim)
                }

                // F0 autocorrelation
                val f0 = FloatArray(sl)
                for (t in 0 until sl) {
                    val s = t * hop; val end = minOf(s + 640, segSamples)
                    if (end - s < 320) break
                    var rms = 0f; for (i in s until end) rms += segAudio[i] * segAudio[i]
                    rms = kotlin.math.sqrt(rms / (end - s))
                    if (rms < 0.005f) continue
                    var bestCorr = 0f; var bestLag = 0
                    for (lag in 16000/1100..minOf(16000/50, (end-s)/2)) {
                        var corr = 0f; for (i in 0 until end-s-lag) corr += segAudio[s+i] * segAudio[s+i+lag]
                        if (corr > bestCorr) { bestCorr = corr; bestLag = lag }
                    }
                    if (bestCorr > rms * rms * (end-s) * 0.3f) f0[t] = 16000f / bestLag
                }
                // Interpolate unvoiced
                var last = -1
                for (i in 0 until sl) {
                    if (f0[i] > 0) { last = i }
                    else if (last >= 0) { f0[i] = f0[last] }
                }
                val shift = Math.pow(2.0, (f0UpKey / 12.0)).toFloat()
                for (i in 0 until sl) f0[i] *= shift

                // Pitch to mel
                val f0Mel = FloatArray(sl) {
                    val f = kotlin.math.max(f0[it].toDouble(), 1e-5)
                    val mel = 1127.0 * Math.log(f / 700.0 + 1.0)
                    val fmn = 1127.0 * Math.log(50.0 / 700.0 + 1.0)
                    val fmx = 1127.0 * Math.log(1100.0 / 700.0 + 1.0)
                    ((mel - fmn) * 254.0 / (fmx - fmn) + 1.0).toFloat().coerceIn(1f, 255f)
                }

                // TextEncoder
                val phone = OnnxTensor.createTensor(env, FloatBuffer.wrap(interp),
                    longArrayOf(1, sl.toLong(), featDim.toLong()))
                val pitch = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(f0Mel.map { it.toLong() }.toLongArray()), longArrayOf(1, sl.toLong()))
                val lengths = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sl.toLong())), longArrayOf(1))

                val teOut = sessions["text_encoder"]!!.run(mapOf("phone" to phone, "pitch" to pitch, "lengths" to lengths))
                val m = (teOut.get("m").get() as OnnxTensor).floatBuffer
                val logs = (teOut.get("logs").get() as OnnxTensor).floatBuffer
                val xMask = (teOut.get("x_mask").get() as OnnxTensor).floatBuffer
                val mArr = FloatArray(m.remaining()).also { m.get(it) }
                val logsArr = FloatArray(logs.remaining()).also { logs.get(it) }
                val maskArr = FloatArray(xMask.remaining()).also { xMask.get(it) }

                // Sample z_p
                val C = 192; val rand = java.util.Random()
                val zP = FloatArray(C * sl) { i ->
                    val noise = rand.nextFloat() * 2f - 1f
                    (mArr[i] + kotlin.math.exp(logsArr[i]) * noise * 0.66666f) * maskArr[i % sl]
                }

                // Flow
                val g = FloatArray(256)
                val flowOut = sessions["flow"]!!.run(mapOf(
                    "z_p" to OnnxTensor.createTensor(env, FloatBuffer.wrap(zP), longArrayOf(1, C.toLong(), sl.toLong())),
                    "x_mask" to OnnxTensor.createTensor(env, FloatBuffer.wrap(maskArr), longArrayOf(1, 1, sl.toLong())),
                    "g" to OnnxTensor.createTensor(env, FloatBuffer.wrap(g), longArrayOf(1, 256, 1))))
                val z = (flowOut.get("z").get() as OnnxTensor).floatBuffer
                val zArr = FloatArray(z.remaining()).also { z.get(it) }

                // Generator
                val zMasked = FloatArray(C * sl) { zArr[it] * maskArr[it % sl] }
                val genOut = sessions["generator"]!!.run(mapOf(
                    "z" to OnnxTensor.createTensor(env, FloatBuffer.wrap(zMasked), longArrayOf(1, C.toLong(), sl.toLong())),
                    "f0" to OnnxTensor.createTensor(env, FloatBuffer.wrap(f0), longArrayOf(1, sl.toLong())),
                    "g" to OnnxTensor.createTensor(env, FloatBuffer.wrap(g), longArrayOf(1, 256, 1))))
                val genAudio = (genOut.get("audio").get() as OnnxTensor).floatBuffer
                val audioArr = FloatArray(genAudio.remaining()).also { genAudio.get(it) }
                allOutput.add(audioArr)
                } catch (e: Exception) {
                    Log.e("RVC", "Segment $seg failed", e)
                }
            }
            val total = allOutput.sumOf { it.size }
            val result = FloatArray(total)
            var offset = 0
            for (arr in allOutput) { System.arraycopy(arr, 0, result, offset, arr.size); offset += arr.size }
            return result
        } catch (e: Exception) {
            Log.e("RVC", "Infer failed", e)
            return null
        }
    }
}

```


## 4. RVCRealtime.kt

实时音频流水线：AudioRecord 采集麦克风 → 降采样 48k→16k → RVC 推理 → 升采样 40k→48k → AudioTrack 扬声器。

```kotlin
package io.github.neboyang.voicechanger

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class RVCRealtime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    val engine = RVCOnnxEngine()
    private var captureThread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var modelLoaded = false

    var f0UpKey: Int = 0
    var latencyMs: Int = 1000
    var onError: ((Throwable) -> Unit)? = null

    fun loadModel(modelDir: File): Boolean {
        modelLoaded = engine.load(modelDir)
        if (modelLoaded) {
            engine.startServer()  // Start LSPosed socket server
        }
        return modelLoaded
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (!modelLoaded) { onError?.invoke(java.lang.IllegalStateException("Model not loaded")); return }
        if (_isRunning.value) return

        val sr = 48000
        val recBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf * 4)

        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(playBuf * 8).build()

        _isRunning.value = true
        record!!.startRecording()
        track!!.play()

        captureThread = Thread({
            val accumSize = sr * latencyMs / 1000
            val accum = FloatArray(accumSize)
            var idx = 0
            val shortBuf = ShortArray(4096)

            while (_isRunning.value) {
                val read = record!!.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) continue
                for (i in 0 until read) { if (idx < accum.size) accum[idx++] = shortBuf[i] / 32768f }
                if (idx >= accum.size) {
                    idx = 0
                    try {
                        val inp = FloatArray(accum.size / 3) { accum[it * 3] }
                        val result = engine.infer(inp, f0UpKey)
                        if (result != null && result.isNotEmpty()) {
                            val outLen = result.size * 48 / 40
                            val outShort = ShortArray(outLen)
                            for (i in 0 until outLen) {
                                val si = ((i.toLong() * result.size) / outLen).toInt().coerceIn(0, result.size - 1)
                                val s32 = (result[si] * 32768f).toInt()
                                outShort[i] = s32.coerceIn(-32768, 32767).toShort()
                            }
                            track!!.write(outShort, 0, outShort.size)
                        }
                    } catch (e: Exception) { onError?.invoke(e) }
                }
            }
        }, "rvc-realtime")
        captureThread!!.start()
    }

    fun stop() {
        _isRunning.value = false
        captureThread?.join(2000); captureThread = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release(); record = null
        try { track?.stop() } catch (_: Exception) {}
        track?.release(); track = null
    }

    fun release() { stop(); engine.unload() }
}

```


## 5. RVCModelManager.kt

模型文件管理：扫描 /sdcard/models/ 下的模型文件夹，解析 config.json。

```kotlin
package io.github.neboyang.voicechanger

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipFile

/**
 * Downloads and manages RVC model files.
 * Models are stored in context.filesDir/rvc_models/<modelId>/
 */
class RVCModelManager(private val context: Context) {

    data class ModelInfo(
        val id: String,
        val name: String,
        val version: String = "v2",
        val ifF0: Boolean = true,
        val featDim: Int = 768,
        val targetSr: Int = 40000,
        val interChannels: Int = 1024,
        val speakerCount: Int = 1,
        val ginChannels: Int = 256,
    )

    val modelsDir: File get() = File("/sdcard/models")

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    fun getModelDir(modelId: String): File = File(modelsDir, modelId)

    fun isModelDownloaded(modelId: String): Boolean {
        val dir = getModelDir(modelId)
        return dir.exists() && File(dir, "config.json").exists()
    }

    fun scanLocalModels() {
        if (!modelsDir.exists()) {
            _models.value = emptyList()
            return
        }
        val list = modelsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val configFile = File(dir, "config.json")
            if (!configFile.exists()) return@mapNotNull null
            try {
                val config = org.json.JSONObject(configFile.readText())
                ModelInfo(
                    id = dir.name,
                    name = config.optString("name", dir.name),
                    version = config.optString("version", "v2"),
                    ifF0 = config.optBoolean("f0", true),
                    featDim = config.optInt("feat_dim", 768),
                    targetSr = config.optInt("target_sr", 40000),
                    interChannels = config.optInt("inter_channels", 1024),
                    speakerCount = config.optInt("speaker_count", 1),
                    ginChannels = config.optInt("gin_channels", 256),
                )
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
        _models.value = list
    }

    /**
     * Download an RVC model pack from a URL.
     * The ZIP should contain: hubert.mnn, rmvpe.mnn, text_encoder.mnn, flow.mnn,
     * generator.mnn, config.json
     */
    suspend fun downloadModel(modelId: String, url: String) = withContext(Dispatchers.IO) {
        val dir = getModelDir(modelId)
        dir.mkdirs()

        val zipFile = File(dir, "model.zip")
        val urlConnection = URL(url).openConnection()
        val totalBytes = urlConnection.contentLengthLong
        val inputStream = urlConnection.getInputStream()
        val outputStream = FileOutputStream(zipFile)
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalRead = 0L

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            if (totalBytes > 0) {
                _downloadProgress.value = totalRead.toFloat() / totalBytes
            }
        }
        outputStream.close()
        inputStream.close()

        // Extract ZIP
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val targetFile = File(dir, entry.name)
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
        zipFile.delete()
        _downloadProgress.value = 1f
        scanLocalModels()
    }

    fun deleteModel(modelId: String) {
        getModelDir(modelId).deleteRecursively()
        scanLocalModels()
    }

    companion object {
        // Default model source URLs (community models)
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/"
    }
}

```


## 6. WavFile.kt

WAV 文件读写：支持 RIFF WAV 头解析，float[] 读写。

```kotlin
package io.github.neboyang.voicechanger

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV 数据段及音频参数。 */
data class WavInfo(
    val config: AudioConfig,
    val dataOffset: Long,
    val dataLength: Long,
)

/** RIFF/PCM WAV 的读写工具。 */
object WavFile {

    /** 本库生成的标准 PCM WAV 头长度。输入 WAV 不要求固定为该长度。 */
    const val HEADER_SIZE = 44

    /** 生成 44 字节 WAV 头。[dataLength] 为 PCM 数据段的字节数。 */
    fun header(dataLength: Int, config: AudioConfig): ByteArray {
        require(dataLength >= 0 && dataLength % config.bytesPerFrame == 0) {
            "PCM 数据长度必须按帧对齐: $dataLength"
        }
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataLength)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(config.channels.toShort())
        buf.putInt(config.sampleRate)
        buf.putInt(config.bytesPerSecond)
        buf.putShort(config.bytesPerFrame.toShort())
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLength)
        return buf.array()
    }

    /** 将裸 PCM 文件包装为 WAV 文件。 */
    fun pcmToWav(pcm: File, wav: File, config: AudioConfig) {
        val dataLength = pcm.length()
        require(dataLength <= Int.MAX_VALUE - 36) { "PCM 文件过大: $dataLength 字节" }
        require(dataLength % config.bytesPerFrame == 0L) { "PCM 文件末尾不是完整音频帧" }
        wav.parentFile?.mkdirs()
        wav.outputStream().buffered().use { out ->
            out.write(header(dataLength.toInt(), config))
            pcm.inputStream().buffered().use { it.copyTo(out) }
        }
    }

    /** 判断文件是否具有 RIFF/WAVE 魔数。更严格的格式校验请使用 [parse]。 */
    fun isWav(file: File): Boolean {
        if (file.length() < 12) return false
        val head = ByteArray(12)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < head.size) {
                val read = input.read(head, offset, head.size - offset)
                if (read < 0) return false
                offset += read
            }
        }
        return String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    /**
     * 解析 PCM WAV 的 chunk，支持 `LIST`、`JUNK`、扩展 `fmt ` 等非固定 44 字节头。
     * 当前处理链只接受 16-bit PCM、单/双声道。
     */
    fun parse(file: File): WavInfo {
        require(isWav(file)) { "不是有效的 RIFF/WAVE 文件: $file" }
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(12)
            var config: AudioConfig? = null
            var dataOffset = -1L
            var dataLength = -1L

            while (raf.filePointer + 8 <= raf.length()) {
                val idBytes = ByteArray(4)
                raf.readFully(idBytes)
                val chunkId = String(idBytes, Charsets.US_ASCII)
                val chunkSize = readUInt32Le(raf)
                val chunkStart = raf.filePointer
                val chunkEnd = chunkStart + chunkSize
                require(chunkEnd >= chunkStart && chunkEnd <= raf.length()) {
                    "WAV chunk 越界: $chunkId, size=$chunkSize"
                }

                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16) { "WAV fmt chunk 过短: $chunkSize" }
                        val audioFormat = readUInt16Le(raf)
                        val channels = readUInt16Le(raf)
                        val sampleRate = readUInt32Le(raf)
                        val byteRate = readUInt32Le(raf)
                        val blockAlign = readUInt16Le(raf)
                        val bitsPerSample = readUInt16Le(raf)
                        require(audioFormat == 1) { "仅支持 PCM WAV，format=$audioFormat" }
                        require(bitsPerSample == 16) { "仅支持 16-bit WAV，实际为 $bitsPerSample-bit" }
                        require(sampleRate <= Int.MAX_VALUE) { "WAV 采样率越界: $sampleRate" }
                        val parsed = AudioConfig(sampleRate.toInt(), channels)
                        require(blockAlign == parsed.bytesPerFrame) { "WAV blockAlign 不一致: $blockAlign" }
                        require(byteRate == parsed.bytesPerSecond.toLong()) { "WAV byteRate 不一致: $byteRate" }
                        config = parsed
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataLength = chunkSize
                    }
                }

                if (config != null && dataOffset >= 0) break
                raf.seek(chunkEnd + (chunkSize and 1L))
            }

            val parsedConfig = requireNotNull(config) { "WAV 缺少 fmt chunk" }
            require(dataOffset >= 0) { "WAV 缺少 data chunk" }
            require(dataLength % parsedConfig.bytesPerFrame == 0L) { "WAV data 长度不是完整音频帧" }
            return WavInfo(parsedConfig, dataOffset, dataLength)
        }
    }

    /**
     * Read WAV file as float array (normalized to [-1, 1]).
     * Returns (samples, sampleRate).
     */
    fun read(file: File): Pair<FloatArray, Int> {
        val info = parse(file)
        val config = info.config
        val frameCount = info.dataLength / config.bytesPerFrame
        val sampleCount = frameCount * config.channels
        val data = ShortArray(sampleCount.toInt())

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            val bytes = ByteArray(info.dataLength.toInt())
            raf.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().get(data)
        }

        // Convert to mono float [-1, 1]
        val mono = FloatArray(frameCount.toInt())
        if (config.channels == 1) {
            for (i in mono.indices) mono[i] = data[i] / 32768f
        } else {
            for (i in mono.indices) {
                mono[i] = (data[i * 2] + data[i * 2 + 1]) / 65536f
            }
        }
        return Pair(mono, config.sampleRate)
    }

    /**
     * Write float array as 16-bit mono WAV.
     */
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val shortData = ShortArray(samples.size)
        for (i in samples.indices) {
            val s = (samples[i] * 32768f).toInt()
            shortData[i] = s.coerceIn(-32768, 32767).toShort()
        }

        val pcmSize = shortData.size * 2
        val headerBytes = header(pcmSize, AudioConfig(sampleRate, 1))

        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            out.write(headerBytes)
            val buf = ByteBuffer.allocate(pcmSize).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(shortData)
            out.write(buf.array())
        }
    }

    private fun readUInt16Le(raf: RandomAccessFile): Int = try {
        java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xffff
    } catch (e: EOFException) {
        throw IllegalArgumentException("WAV 文件意外结束", e)
    }

    private fun readUInt32Le(raf: RandomAccessFile): Long = try {
        Integer.reverseBytes(raf.readInt()).toLong() and 0xffff_ffffL
    } catch (e: EOFException) {
        throw IllegalArgumentException("WAV 文件意外结束", e)
    }
}

```


## 7. activity_main.xml

主界面布局：模型列表、音高滑块、延时滑块、实时变声按钮、悬浮窗启动按钮。

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="20dp">

        <!-- ========== 状态栏 ========== -->
        <TextView
            android:id="@+id/tvStatus"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/status_idle"
            android:textAppearance="?attr/textAppearanceTitleMedium" />

        <!-- ========== 模式切换 ==========
        <com.google.android.material.chip.ChipGroup
            android:id="@+id/modeSwitch"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            app:selectionRequired="true"
            app:singleSelection="true">

            <com.google.android.material.chip.Chip
                android:id="@+id/modeClassic"
                style="@style/Widget.Material3.Chip.Filter"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:checked="true"
                android:text="@string/mode_classic" />

            <com.google.android.material.chip.Chip
                android:id="@+id/modeAi"
                style="@style/Widget.Material3.Chip.Filter"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/mode_ai" />
        </com.google.android.material.chip.ChipGroup>

        <LinearLayout
            android:id="@+id/classicPanel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:visibility="gone"> -->

        <!-- ================================================================
         AI 模式 (RVC)
         ================================================================ -->
        <LinearLayout
            android:id="@+id/aiPanel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:orientation="vertical">

            <!-- 模型管理 -->
            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:text="@string/rvc_model_title"
                android:textAppearance="?attr/textAppearanceTitleSmall" />

            <TextView
                android:id="@+id/tvModelHelp"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="@string/rvc_help"
                android:textAppearance="?attr/textAppearanceBodySmall"
                android:textColor="?attr/colorOnSurfaceVariant" />

            <com.google.android.material.chip.ChipGroup
                android:id="@+id/modelList"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                app:selectionRequired="true"
                app:singleSelection="true" />

            <!-- 模型状态 -->
            <TextView
                android:id="@+id/tvModelStatus"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="@string/rvc_no_model"
                android:textAppearance="?attr/textAppearanceBodySmall" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:orientation="horizontal">

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnRefreshModels"
                    style="@style/Widget.Material3.Button.TonalButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="@string/rvc_refresh" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnDeleteModel"
                    style="@style/Widget.Material3.Button.TonalButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:layout_weight="1"
                    android:text="@string/rvc_delete" />
            </LinearLayout>

            <!-- AI 参数 -->
            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:text="@string/rvc_params"
                android:textAppearance="?attr/textAppearanceTitleSmall" />

            <TextView
                android:id="@+id/tvF0Key"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp" />

            <com.google.android.material.slider.Slider
                android:id="@+id/sliderF0Key"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:value="0"
                android:valueFrom="-15"
                android:valueTo="15" />

            <TextView
                android:id="@+id/tvProtect"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />

            <com.google.android.material.slider.Slider
                android:id="@+id/sliderProtect"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:value="0.33"
                android:valueFrom="0.0"
                android:valueTo="1.0" />

            <TextView
                android:id="@+id/tvLatency"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp" />

            <com.google.android.material.slider.Slider
                android:id="@+id/sliderLatency"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:value="1"
                android:valueFrom="0.5"
                android:valueTo="5"
                android:stepSize="0.5" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnFloat"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:text="启动悬浮麦克风" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnRvcRealtime"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:enabled="false"
                android:text="@string/rvc_start" />

            <TextView
                android:id="@+id/tvRvcResult"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:textAppearance="?attr/textAppearanceBodySmall"
                android:textIsSelectable="true" />
        </LinearLayout>

    </LinearLayout>
</ScrollView>

```


## 8. float_mic.xml

悬浮窗布局：状态文字 + 关闭按钮。

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content">

    <TextView
        android:id="@+id/tvFloatStatus"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="加载中…"
        android:textSize="14sp"
        android:textColor="#FFFFFF"
        android:background="#AA000000"
        android:padding="10dp" />

    <TextView
        android:id="@+id/btnFloatClose"
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:layout_gravity="top|end"
        android:text="X"
        android:textSize="12sp"
        android:textColor="#FFFFFF"
        android:background="#CCFF0000"
        android:gravity="center" />

</FrameLayout>

```


## 9. strings.xml

字符串资源。

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">喵喵RVC</string>

    <!-- 状态 -->
    <string name="status_idle">就绪</string>
    <string name="status_recording">● 录音中…</string>
    <string name="status_processing">处理中…</string>
    <string name="status_playing">▶ 播放中…</string>
    <string name="status_done">播放完成</string>

    <!-- 录音按钮 -->
    <string name="btn_record">录音</string>
    <string name="btn_pause">暂停</string>
    <string name="btn_resume">继续</string>
    <string name="btn_stop">停止</string>
    <string name="btn_process">变声并播放</string>

    <!-- AI RVC 模式 -->
    <string name="rvc_model_title">AI 模型</string>
    <string name="rvc_help">将 PC 转换好的 .onnx 文件夹放到 /sdcard/models/ 目录下</string>
    <string name="rvc_refresh">刷新列表</string>
    <string name="rvc_delete">删除模型</string>
    <string name="rvc_no_model">未检测到模型</string>
    <string name="rvc_model_loaded">已加载：%1$s</string>
    <string name="rvc_model_loading">加载模型中…</string>
    <string name="rvc_model_failed">模型加载失败</string>
    <string name="rvc_start">开始 AI 实时变声</string>
    <string name="rvc_stop">停止 AI 实时变声</string>
    <string name="rvc_params">变声参数</string>
    <string name="rvc_f0_key">音高偏移：%1$+d 半音</string>
    <string name="rvc_latency">延时：%1$.1f 秒（越长音质越好）</string>
    <string name="rvc_protect">气息保护：%1$.0f%%</string>
    <string name="rvc_no_models">暂无模型。用 PC 运行 convert_rvc.py 转换 .pth → .onnx，放入此目录</string>
</resources>

```


## 10. app/AndroidManifest.xml

主应用权限声明 + Activity 注册。

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

    <application
        android:usesCleartextTraffic="true"
        android:icon="@drawable/icon"
        android:roundIcon="@drawable/icon"
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.VoiceChanger">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>

```


## 11. voicechanger/AndroidManifest.xml

库模块权限声明 + Service 注册。

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application>
        <service
            android:name=".FloatMicService"
            android:foregroundServiceType="microphone"
            android:exported="false" />
    </application>
</manifest>

```
