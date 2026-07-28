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
        var noiseLevelRef: Int = 0
        var eqLevelRef: Int = 0
        var volumeRef: Float = 0.8f
        var noiseGateDbRef: Double = 0.0
        var outputDenoiseRef: Boolean = false
        var vocalRangeFilterRef: Boolean = false
        var indexRateRef: Double = 0.0
        var indexPathRef: String? = null
        var protectRateRef: Double = 0.33
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, FloatMicService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, FloatMicService::class.java)) }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            if (isOurRecording) return
            val active = configs?.any { it.clientAudioSource == MediaRecorder.AudioSource.MIC ||
                it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION } == true
            if (active) {
                tvStatus?.text = "检测到录音，自动变声中"
                if (autoRecThread == null) startAutoRecord()
            } else {
                tvStatus?.text = "录音结束"
                stopAutoRecord()
            }
        }
    }

    private var autoRecThread: Thread? = null
    private var autoRunning = false

    private fun startAutoRecord() {
        if (autoRunning) return
        autoRunning = true
        val sr = 48000
        val bs = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096) * 4
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs)
        try { android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true } catch (_: Exception) {}
        rec.startRecording()

        val playBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(playBuf * 8).build()
        if (Build.VERSION.SDK_INT >= 31) {
            val spk = audioManager?.availableCommunicationDevices?.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (spk != null) audioManager?.setCommunicationDevice(spk) else audioManager?.isSpeakerphoneOn = true
        } else audioManager?.isSpeakerphoneOn = true
        track.play()

        autoRecThread = Thread({
            val e = engine; if (e == null) { rec.stop(); rec.release(); track.stop(); track.release(); autoRunning = false; return@Thread }
            val buf = ShortArray(4096); val hop = 160; val frames = 50
            val accum = FloatArray(frames * hop * 3); var idx = 0
            while (autoRunning && !Thread.interrupted()) {
                val r = rec.read(buf, 0, buf.size); if (r <= 0) continue
                for (i in 0 until r) { if (idx < accum.size) accum[idx++] = buf[i] / 32768f }
                if (idx >= accum.size) {
                    idx = 0
                    val inp = FloatArray(accum.size / 3) { accum[it * 3] }
                    try {
                        val res = e.infer(inp, f0UpKeyRef)
                        if (res != null && res.isNotEmpty()) {
                            val outLen = res.size * 48 / 40
                            val outShort = ShortArray(outLen)
                            val vol = volumeRef.coerceIn(0f, 1f)
                            for (i in 0 until outLen) {
                                val si = ((i.toLong() * res.size) / outLen).toInt().coerceIn(0, res.size - 1)
                                val s32 = ((res[si] * vol) * 32768f).toInt()
                                outShort[i] = s32.coerceIn(-32768, 32767).toShort()
                            }
                            track.write(outShort, 0, outShort.size)
                        }
                    } catch (_: Exception) {}
                }
            }
            try { rec.stop(); rec.release() } catch (_: Exception) {}
            try { track.stop(); track.release() } catch (_: Exception) {}
            autoRunning = false
        }, "float-auto").also { it.start() }
    }

    private fun stopAutoRecord() {
        autoRunning = false
        autoRecThread?.join(2000)
        autoRecThread = null
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

                tr.setVolume(engine?.volume ?: 0.8f)
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
                    for (i in 0..10) { if (engine?.isLoaded() == true) break; engine = engineRef; engine?.apply { noiseLevel = noiseLevelRef; eqLevel = eqLevelRef; volume = volumeRef; noiseGateDb = noiseGateDbRef; outputDenoiseEnabled = outputDenoiseRef; vocalRangeFilterEnabled = vocalRangeFilterRef; indexRate = indexRateRef; protectRate = protectRateRef; loadIndex(indexPathRef) }; Thread.sleep(1000) }
                    floatView.post { tvStatus?.text = if (engine?.isLoaded() == true) "点录制" else "未加载" }
                }.start(); return@setOnClickListener
            }
            if (isRecording) { isRecording = false; isOurRecording = false; recordThread?.join(3000); tvStatus?.text = "点录制" }
            else startRecord()
        }

        floatView.setOnLongClickListener { playLatest(); true }

        engine = engineRef
        engine?.apply { noiseLevel = noiseLevelRef; eqLevel = eqLevelRef; volume = volumeRef; noiseGateDb = noiseGateDbRef; outputDenoiseEnabled = outputDenoiseRef; vocalRangeFilterEnabled = vocalRangeFilterRef; indexRate = indexRateRef; protectRate = protectRateRef; loadIndex(indexPathRef) }
        tvStatus?.text = if (engine?.isLoaded() == true) "点录制" else "等待模型…"
        if (engine?.isLoaded() != true) Thread {
            for (i in 0..10) { if (engine?.isLoaded() == true) break; engine = engineRef; engine?.apply { noiseLevel = noiseLevelRef; eqLevel = eqLevelRef; volume = volumeRef; noiseGateDb = noiseGateDbRef; outputDenoiseEnabled = outputDenoiseRef; vocalRangeFilterEnabled = vocalRangeFilterRef; indexRate = indexRateRef; protectRate = protectRateRef; loadIndex(indexPathRef) }; Thread.sleep(1000) }
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
        // 开启声学回声消除（过滤扬声器回授 + 其他 App 声音）
        try { android.media.audiofx.AcousticEchoCanceler.create(audioRecord!!.audioSessionId)?.enabled = true } catch (_: Exception) {}
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
