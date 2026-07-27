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
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
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
    private var autoPlayThread: Thread? = null
    private var isRunning = true

    companion object {
        private const val CHANNEL_ID = "rvc_float"
        private const val NOTIF_ID = 1001
        var engineRef: RVCOnnxEngine? = null
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, FloatMicService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, FloatMicService::class.java)) }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("喵喵RVC").setContentText("悬浮窗运行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build())

        val inflater = LayoutInflater.from(this)
        floatView = inflater.inflate(R.layout.float_mic, null)
        tvStatus = floatView.findViewById(R.id.tvFloatStatus)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100; params.y = 200
        wm.addView(floatView, params)

        // 拖拽
        var dx = 0f; var dy = 0f
        floatView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { dx = event.rawX - params.x; dy = event.rawY - params.y; false }
                MotionEvent.ACTION_MOVE -> { params.x = (event.rawX - dx).toInt(); params.y = (event.rawY - dy).toInt(); wm.updateViewLayout(floatView, params); true }
                else -> false
            }
        }

        // ✕ 关闭按钮
        floatView.findViewById<View>(R.id.btnFloatClose)?.setOnClickListener {
            stopSelf()
        }

        // 点击：录制/停止
        floatView.setOnClickListener {
            if (engine?.isLoaded() != true) {
                tvStatus?.text = "⏳ 加载中…"
                Thread { for (i in 0..10) { if (engine?.isLoaded() == true) break; engine = engineRef; if (engine?.isLoaded() == true) break; Thread.sleep(1000) }
                    floatView.post { if (engine?.isLoaded() == true) tvStatus?.text = "🎤 点录制" else tvStatus?.text = "❌ 未加载" } }.start()
                return@setOnClickListener
            }
            if (isRecording) stopRecord()
            else startRecord()
        }

        // 长按：停止录音并外放
        floatView.setOnLongClickListener {
            if (isRecording) {
                isRecording = false
                recordThread?.join(3000)
                tvStatus?.text = "🔊 外放中…"
                playLatest()
            } else {
                playLatest()
            }
            true
        }

        // 加载引擎
        engine = engineRef
        if (engine?.isLoaded() != true) {
            tvStatus?.text = "⏳ 等待模型…"
            Thread {
                for (i in 0..10) { if (engine?.isLoaded() == true) break; Thread.sleep(1000) }
                floatView.post { tvStatus?.text = if (engine?.isLoaded() == true) "🎤 点录制" else "❌ 未加载" }
            }.start()
        } else {
            tvStatus?.text = "🎤 点录制"
        }

        // 后台检测：其他 App 使用麦克风时自动外放
        startMicMonitor()
    }

    // ---- 麦克风占用检测 ----
    private fun startMicMonitor() {
        autoPlayThread = Thread({
            while (isRunning) {
                try {
                    // 尝试创建 AudioRecord，如果失败说明其他 App 在用麦克风
                    val testRec = AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 2048)
                    if (testRec.state != AudioRecord.STATE_INITIALIZED) {
                        // 麦克风被占用 → 自动外放
                        floatView.post { tvStatus?.text = "📢 检测到麦克风被占用，自动外放" }
                        playLatest()
                        Thread.sleep(8000) // 8 秒内不重复触发
                    }
                    testRec.release()
                } catch (_: Exception) {
                    floatView.post { tvStatus?.text = "📢 自动外放" }
                    playLatest()
                    Thread.sleep(8000)
                }
                Thread.sleep(2000) // 每 2 秒检测一次
            }
        }, "mic-monitor").apply { isDaemon = true; start() }
    }

    // ---- 录音 ----
    private fun startRecord() {
        if (engine?.isLoaded() != true) { tvStatus?.text = "❌ 模型未加载"; return }
        val sr = 48000
        val bufSize = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        audioRecord!!.startRecording()
        isRecording = true
        tvStatus?.text = "🔴 录音中…"

        recordThread = Thread({
            val pcmList = mutableListOf<ShortArray>()
            val buf = ShortArray(bufSize)
            while (isRecording) { val r = audioRecord!!.read(buf, 0, buf.size); if (r > 0) pcmList.add(buf.copyOf(r)) }
            audioRecord!!.stop(); audioRecord!!.release(); audioRecord = null
            val total = pcmList.sumOf { it.size }
            val floatAudio = FloatArray(total); var off = 0
            for (arr in pcmList) for (s in arr) floatAudio[off++] = s / 32768f
            tvStatus?.post { tvStatus?.text = "⏳ 处理中…" }
            val inp = FloatArray(floatAudio.size / 3) { floatAudio[it * 3] }
            val result = engine?.infer(inp, 0)
            if (result != null && result.isNotEmpty()) {
                File("/sdcard/rvc").mkdirs()
                io.github.neboyang.voicechanger.WavFile.write(File("/sdcard/rvc", "voice_${System.currentTimeMillis()}.wav"), result, 40000)
                tvStatus?.post { tvStatus?.text = "✅ 已保存" }
            } else tvStatus?.post { tvStatus?.text = "❌ 处理失败" }
        }, "float-record")
        recordThread!!.start()
    }

    private fun stopRecord() {
        isRecording = false; recordThread?.join(3000)
        tvStatus?.text = "🎤 点录制"
    }

    // ---- 外放最新变声 ----
    private fun playLatest() {
        Thread({
            try {
                val dir = File("/sdcard/rvc")
                val wavs = dir.listFiles { f -> f.name.endsWith(".wav") }
                if (wavs.isNullOrEmpty()) { tvStatus?.post { tvStatus?.text = "没有音频" }; return@Thread }
                val f = wavs.maxByOrNull { f2 -> f2.lastModified() }!!
                val data = f.readBytes()
                val sr = ByteBuffer.wrap(data, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val ch = ByteBuffer.wrap(data, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val bits = ByteBuffer.wrap(data, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val off = if (data[0] == 0x52.toByte()) 44 else 0
                val pcm = data.copyOfRange(off, data.size)
                val chMask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val enc = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(enc).setSampleRate(sr).setChannelMask(chMask).build())
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sr, chMask, enc) * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build()

                track.write(pcm, 0, pcm.size)
                track.setVolume(android.media.AudioTrack.getMaxVolume())
                track.play()
                tvStatus?.post { tvStatus?.text = "🔊 外放中…" }
                Thread.sleep(pcm.size.toLong() * 1000 / (sr * ch * bits / 8) + 200)
                track.stop(); track.release()
                tvStatus?.post { tvStatus?.text = "🎤 点录制" }
            } catch (_: Exception) {}
        }, "float-play").start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(CHANNEL_ID, "喵喵RVC", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
        }
    }

    override fun onDestroy() {
        isRunning = false; isRecording = false
        recordThread?.join(2000); autoPlayThread?.join(2000)
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { wm.removeView(floatView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
