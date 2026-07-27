package io.github.neboyang.voicechanger.demo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
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
import io.github.neboyang.voicechanger.RVCModelManager
import io.github.neboyang.voicechanger.RVCRealtime
import io.github.neboyang.voicechanger.SilkToolchain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: RVCModelManager
    private val rvcRealtime = RVCRealtime()
    private lateinit var tvStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvF0Key: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvExportStatus: TextView
    private lateinit var modelList: ChipGroup
    private lateinit var sliderF0Key: Slider
    private lateinit var sliderLatency: Slider
    private lateinit var btnRvcRealtime: MaterialButton
    private lateinit var btnRecordRvc: MaterialButton
    private var isRecording = false
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var currentModelDir: File? = null

    private val storageIntentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshModelList() }
    private val storagePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) refreshModelList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modelManager = RVCModelManager(this)
        tvStatus = findViewById(R.id.tvStatus)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvF0Key = findViewById(R.id.tvF0Key)
        tvLatency = findViewById(R.id.tvLatency)
        tvExportStatus = findViewById(R.id.tvExportStatus)
        modelList = findViewById(R.id.modelList)
        sliderF0Key = findViewById(R.id.sliderF0Key)
        sliderLatency = findViewById(R.id.sliderLatency)
        btnRvcRealtime = findViewById(R.id.btnRvcRealtime)
        btnRecordRvc = findViewById(R.id.btnRecordRvc)

        sliderF0Key.addOnChangeListener { _, value, _ ->
            tvF0Key.text = getString(R.string.rvc_f0_key, value.toInt())
            rvcRealtime.f0UpKey = value.toInt()
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
                if (currentModelDir == null) {
                    Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                }
                rvcRealtime.onError = { t -> runOnUiThread { Toast.makeText(this, "错误: ${t.message}", Toast.LENGTH_LONG).show() } }
                withRecordPermission { rvcRealtime.start() }
            }
        }

        lifecycleScope.launch {
            rvcRealtime.isRunning.collect { running ->
                btnRvcRealtime.text = if (running) "停止" else "开始 AI 实时变声"
                tvStatus.text = if (running) "🎧 运行中…" else "就绪"
            }
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

        btnRecordRvc.setOnClickListener {
            if (isRecording) { isRecording = false; btnRecordRvc.text = "录制并处理变声" }
            else {
                if (currentModelDir == null) { Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                withRecordPermission { startRvcRecording() }
            }
        }

        // ===================== 导出按钮 =====================
        findViewById<MaterialButton>(R.id.btnExportSlk).setOnClickListener {
            val input = android.widget.EditText(this).apply { hint = "输入 QQ 号码"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            AlertDialog.Builder(this)
                .setTitle("导出到 QQ 语音")
                .setMessage("确保已在 QQ 发了一条语音消息")
                .setView(input)
                .setPositiveButton("导出") { _, _ ->
                    val qqNum = input.text.toString().trim()
                    if (qqNum.isEmpty()) { Toast.makeText(this, "请输入 QQ 号码", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val rvcDir = File("/sdcard/rvc")
                            val wavs = rvcDir.listFiles { f -> f.name.endsWith(".wav") }
                            if (wavs.isNullOrEmpty()) {
                                withContext(Dispatchers.Main) { tvExportStatus.text = "没有找到音频，请先录制并处理变声" }; return@launch
                            }
                            val wav = wavs.maxByOrNull { it.lastModified() }!!

                            // 检查 Root
                            val hasRoot = try {
                                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo OK"))
                                val ok = p.inputStream.bufferedReader().readLine() == "OK"
                                p.destroy(); ok
                            } catch (_: Exception) { false }

                            if (hasRoot) {
                                // Root：找到最新文件
                                val base = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/$qqNum/ptt"
                                val fCmd = "find '$base' -mindepth 2 -type f \\( -name '*.amr' -o -name '*.slk' \\) -printf '%T@ %p\\n' 2>/dev/null | sort -n | tail -1"
                                val fp = Runtime.getRuntime().exec(arrayOf("su", "-c", fCmd))
                                val line = fp.inputStream.bufferedReader().readText().trim(); fp.waitFor()
                                // 调试：存文件
                                try { java.io.File("/sdcard/rvc_debug.txt").writeText("find: $line\ncmd: $fCmd") } catch(_:Exception){}
                                if (line.isEmpty()) {
                                    withContext(Dispatchers.Main) { tvExportStatus.text = "未找到 QQ 语音，请先在 QQ 发一条语音" }; return@launch
                                }
                                val target = line.substringAfter(" ")
                                // 直接 cp WAV 覆盖（不管格式，QQ 能不能播放看它自己）
                                val cp = "cp '${wav.absolutePath}' '$target' && chown \$(stat -c %U '$target'):\$(stat -c %G '$target') '$target' && chmod \$(stat -c %a '$target') '$target'"
                                Runtime.getRuntime().exec(arrayOf("su", "-c", cp)).waitFor()
                                withContext(Dispatchers.Main) {
                                    tvExportStatus.text = "✅ 已替换, 回 QQ 播放"
                                    Toast.makeText(this@MainActivity, "✅ 已替换", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                // 无 Root：保存到 /sdcard/rvc/ + 教程
                                val manual = """
                                    无 Root 权限，手动替换：
                                    1. QQ 发一条语音
                                    2. 用 MT 管理器打开:
                                       /storage/emulated/0/Android/data/com.tencent.mobileqq/
                                       Tencent/MobileQQ/$qqNum/ptt/
                                    3. 找到最新语音文件，记住文件名
                                    4. 把 /sdcard/rvc/${wav.name} 重命名为同样文件名
                                    5. 复制覆盖，权限 644
                                """.trimIndent()
                                withContext(Dispatchers.Main) {
                                    tvExportStatus.text = "已保存: ${wav.absolutePath}\n\n$manual"
                                }
                            }
                        } catch (e: Exception) { withContext(Dispatchers.Main) { tvExportStatus.text = "导出失败: ${e.message}" } }
                    }
                }.setNegativeButton("取消", null).show()
        }

        modelManager.scanLocalModels()
        refreshModelList()
    }

    // ---- AMR 编码 ----
    private fun encodeAmr(wavFile: File): String? {
        return try {
            val wavBytes = wavFile.readBytes()
            val sr = ByteBuffer.wrap(wavBytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val off = if (wavBytes[0] == 0x52.toByte()) 44 else 0
            val raw = wavBytes.copyOfRange(off, wavBytes.size)
            val srcLen = raw.size / 2; val dstLen = srcLen * 8000 / sr
            val pcm = ByteArray(dstLen * 2)
            val srcBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val dstBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until dstLen) dstBuf.put(srcBuf.get(i * sr / 8000))

            val codec = android.media.MediaCodec.createEncoderByType("audio/3gpp")
            val mediaFmt = android.media.MediaFormat()
            mediaFmt.setString(android.media.MediaFormat.KEY_MIME, "audio/3gpp")
            mediaFmt.setInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, 8000)
            mediaFmt.setInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT, 1)
            mediaFmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 12200)
            mediaFmt.setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, 0)
            codec.configure(mediaFmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val bi = android.media.MediaCodec.BufferInfo()
            val inIdx = codec.dequeueInputBuffer(-1)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)!!
                buf.clear(); buf.put(pcm)
                codec.queueInputBuffer(inIdx, 0, pcm.size, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val oIdx = codec.dequeueOutputBuffer(bi, 10000)
                if (oIdx < 0) continue
                if (bi.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                if (bi.size > 0) {
                    val ob = codec.getOutputBuffer(oIdx)!!
                    val d = ByteArray(bi.size); ob.position(bi.offset); ob.get(d, 0, bi.size)
                    out.write(d)
                }
                codec.releaseOutputBuffer(oIdx, false)
            }
            codec.stop(); codec.release()
            val f = File(cacheDir, "v.amr"); f.writeBytes(out.toByteArray()); out.close()
            f.absolutePath
        } catch (e: Exception) {
            try {
                java.io.File("/sdcard/rvc_amr_error.txt").writeText(android.util.Log.getStackTraceString(e))
            } catch (_: Exception) {}
            null
        }
    }

    // ---- 录音 ----
    private fun startRvcRecording() {
        val sr = 48000
        val bufSize = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        audioRecord!!.startRecording()
        isRecording = true; btnRecordRvc.text = "停止录制"
        tvStatus.text = "● 录音中…"
        recordThread = Thread({
            val pcmList = mutableListOf<ShortArray>()
            val buf = ShortArray(bufSize)
            while (isRecording) { val read = audioRecord!!.read(buf, 0, buf.size); if (read > 0) pcmList.add(buf.copyOf(read)) }
            audioRecord!!.stop(); audioRecord!!.release(); audioRecord = null
            val total = pcmList.sumOf { it.size }
            val floatAudio = FloatArray(total); var off = 0
            for (arr in pcmList) for (s in arr) floatAudio[off++] = s / 32768f
            tvStatus.post { tvStatus.text = "⏳ 处理中…" }
            val result = rvcRealtime.engine.infer(FloatArray(floatAudio.size / 3) { floatAudio[it * 3] }, rvcRealtime.f0UpKey)
            if (result != null && result.isNotEmpty()) {
                File("/sdcard/rvc").mkdirs()
                val outFile = File("/sdcard/rvc", "voice_${System.currentTimeMillis()}.wav")
                io.github.neboyang.voicechanger.WavFile.write(outFile, result, 40000)
                tvStatus.post { tvStatus.text = "✅ 已保存: $outFile" }
                tvExportStatus.post { tvExportStatus.text = "已生成变声音频，可点击导出" }
            } else tvStatus.post { tvStatus.text = "❌ 处理失败" }
            btnRecordRvc.post { btnRecordRvc.text = "录制并处理变声" }
        }, "rvc-record")
        recordThread!!.start()
    }

    // ---- 权限 ----
    private fun checkStoragePermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = android.net.Uri.parse("package:$packageName")
            storageIntentLauncher.launch(intent)
        } else storagePermLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) pendingAction?.invoke(); pendingAction = null }
    private var pendingAction: (() -> Unit)? = null

    private fun withRecordPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pendingAction = action; permLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }

    // ---- 列表 ----
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

    override fun onDestroy() { super.onDestroy(); rvcRealtime.release() }
}
