package io.github.neboyang.voicechanger.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import io.github.neboyang.voicechanger.RVCModelManager
import io.github.neboyang.voicechanger.RVCRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var modelManager: RVCModelManager
    private val rvcRealtime = RVCRealtime()
    private lateinit var settings: SettingsManager
    private lateinit var tvStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvF0Key: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvNoise: TextView
    private lateinit var tvEq: TextView
    private lateinit var tvBackend: TextView
    private lateinit var modelList: ChipGroup
    private lateinit var sliderF0Key: Slider
    private lateinit var sliderProtect: Slider
    private lateinit var tvProtect: TextView
    private lateinit var sliderLatency: Slider
    private lateinit var sliderNoise: Slider
    private lateinit var sliderEq: Slider
    private lateinit var sliderNoiseGate: Slider
    private lateinit var tvNoiseGate: TextView
    private lateinit var sliderIndexRate: Slider
    private lateinit var tvIndexRate: TextView
    private lateinit var tvIndexPath: TextView
    private lateinit var switchDenoise: SwitchCompat
    private lateinit var switchVocalRange: SwitchCompat
    private lateinit var pitchSelector: ChipGroup
    private lateinit var sliderCrossfade: Slider
    private lateinit var tvCrossfade: TextView
    private lateinit var sliderVadEnergy: Slider
    private lateinit var tvVadEnergy: TextView
    private lateinit var sliderVadFrames: Slider
    private lateinit var tvVadFrames: TextView
    private lateinit var sliderOverlap: Slider
    private lateinit var tvOverlap: TextView
    private lateinit var btnRvcRealtime: MaterialButton
    private lateinit var btnRecordToFile: MaterialButton
    private lateinit var btnCloud: MaterialButton
    private lateinit var btnLocalConvert: MaterialButton
    private lateinit var etBotUrl: android.widget.EditText
    private lateinit var etBotToken: android.widget.EditText
    private lateinit var etBotGroup: android.widget.EditText
    private lateinit var tvBotStatus: TextView
    private lateinit var btnBotSend: MaterialButton
    private var lastConvertedFile: File? = null
    private var botServer: OneBotWsServer? = null
    private var currentModelDir: File? = null
    private var currentIndexPath: String? = null

    private val cloudModelPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) cloudRvc?.handlePickedUri(uri)
        }

    private var cloudRvc: ReplicateCloudRvc? = null

    private val sendAudioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val targetId = etBotGroup.text.toString().trim().toLongOrNull()
                val isPrivate = findViewById<com.google.android.material.chip.Chip>(R.id.chipBotPrivate).isChecked
                if (targetId == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "请先输入群号或QQ号", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val conn = botServer?.getActiveConnections()?.firstOrNull()
                if (conn == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Lagrange 未连接，请先启动 Lagrange", Toast.LENGTH_LONG).show() }
                    return@launch
                }
                try {
                    val input = contentResolver.openInputStream(uri) ?: run {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "无法读取所选文件", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    // 复制到 /sdcard/rvc，保证 Lagrange 能读到
                    val outDir = File("/sdcard/rvc"); outDir.mkdirs()
                    val outFile = File(outDir, "send_${System.currentTimeMillis()}.${fileExtOf(uri) ?: "wav"}")
                    input.use { it.copyTo(outFile.outputStream()) }
                    withContext(Dispatchers.Main) { tvBotStatus.text = "发送中: ${outFile.name}" }
                    botServer!!.sendGroupRecord(conn, if (isPrivate) -targetId else targetId, outFile.absolutePath)
                    withContext(Dispatchers.Main) {
                        tvBotStatus.text = "已发送到${if (isPrivate) "QQ号" else "群"} $targetId"
                        Toast.makeText(this@MainActivity, "语音已发送", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { tvBotStatus.text = "发送失败: ${e.message}" }
                }
            }
        }

    private fun fileExtOf(uri: Uri): String? =
        contentResolver.getType(uri)?.substringAfterLast("/")?.let { mapOf("wav" to "wav", "x-wav" to "wav", "mpeg" to "mp3", "x-ms-wma" to "wma").getOrElse(it) { "wav" } }

    private val localAudioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null || currentModelDir == null) return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val input = contentResolver.openInputStream(uri) ?: return@launch
                    val tmpFile = File(cacheDir, "local_input_${System.currentTimeMillis()}.wav")
                    input.use { it.copyTo(tmpFile.outputStream()) }
                    val bytes = tmpFile.readBytes()
                    if (bytes.size < 44) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "无效 WAV", Toast.LENGTH_SHORT).show() }; return@launch }
                    val sampleRate = java.nio.ByteBuffer.wrap(bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    val channels = java.nio.ByteBuffer.wrap(bytes, 22, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
                    val dataOff = if (bytes[0] == 0x52.toByte()) 44 else 0
                    val pcm = bytes.copyOfRange(dataOff, bytes.size)
                    val floatPcm = FloatArray(pcm.size / 2) { java.nio.ByteBuffer.wrap(pcm, it * 2, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short / 32768f }
                    val mono = if (channels == 1) floatPcm else FloatArray(floatPcm.size / 2) { (floatPcm[it * 2] + floatPcm[it * 2 + 1]) / 2f }
                    val sr16k = if (sampleRate == 16000) mono else {
                        val ratio = sampleRate.toDouble() / 16000
                        FloatArray((mono.size / ratio).toInt().coerceAtLeast(1)) { mono[(it * ratio).toInt().coerceIn(0, mono.size - 1)] }
                    }
                    tmpFile.delete()
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "转换中...", Toast.LENGTH_SHORT).show() }
                    val result = rvcRealtime.engine.infer(sr16k, rvcRealtime.f0UpKey)
                    if (result != null) {
                        val outFile = File("/sdcard/rvc", "converted_${System.currentTimeMillis()}.wav")
                        outFile.parentFile?.mkdirs()
                        io.github.neboyang.voicechanger.WavFile.write(outFile, result, rvcRealtime.engine.targetSr)
                        lastConvertedFile = outFile
                        btnBotSend.isEnabled = true
                        withContext(Dispatchers.Main) { tvStatus.text = "已保存: ${outFile.name}"; Toast.makeText(this@MainActivity, "转换完成", Toast.LENGTH_LONG).show() }
                    }
                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "失败: ${e.message}", Toast.LENGTH_SHORT).show() } }
            }
        }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val path = uri.path ?: return@registerForActivityResult
            android.util.Log.e("RVC", "Folder URI: $uri path: $path")
            // content://.../tree/primary:models/2888 → /sdcard/models/2888
            val sdcardPath = path.substringAfter("primary:").substringBefore("/document").substringBefore("/tree").trimEnd('/')
            val folderPath = "/sdcard/$sdcardPath"
            android.util.Log.e("RVC", "Resolved folder: $folderPath")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dir = java.io.File(folderPath)
                    if (!dir.exists()) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "路径不存在: $folderPath", Toast.LENGTH_LONG).show() }
                        return@launch
                    }
                    val onnxFiles = dir.listFiles { f -> f.extension == "onnx" && f.name !in setOf("hubert.onnx", "rmvpe.onnx") }
                    android.util.Log.e("RVC", "Files in dir: ${dir.list()?.joinToString()}")
                    if (onnxFiles.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "未找到 ONNX 模型，请确保文件夹内有 .onnx 文件", Toast.LENGTH_LONG).show() }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "下载基础模型中...", Toast.LENGTH_LONG).show() }
                    val info = modelManager.importFromFolder(folderPath)
                    if (info != null) {
                        refreshModelList()
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "已导入: ${info.name}", Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }

    private val storageIntentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshModelList() }
    private val storagePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) refreshModelList() }

    private val indexPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            // Copy to app cache to get a file path
            try {
                val input = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
                val cacheFile = File(cacheDir, "selected_index.index")
                input.use { inp -> cacheFile.outputStream().use { inp.copyTo(it) } }
                currentIndexPath = cacheFile.absolutePath
                rvcRealtime.engine.loadIndex(cacheFile.absolutePath)
                tvIndexPath.text = "索引: ${cacheFile.name}"
            } catch (e: Exception) {
                Toast.makeText(this, "索引加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modelManager = RVCModelManager(this)
        settings = SettingsManager(this)

        // 启动时请求所有必要权限
        requestAllPermissions()

        tvStatus = findViewById(R.id.tvStatus)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvF0Key = findViewById(R.id.tvF0Key)
        tvProtect = findViewById(R.id.tvProtect)
        tvLatency = findViewById(R.id.tvLatency)
        tvNoise = findViewById(R.id.tvNoise)
        tvEq = findViewById(R.id.tvEq)
        tvNoiseGate = findViewById(R.id.tvNoiseGate)
        tvIndexRate = findViewById(R.id.tvIndexRate)
        tvIndexPath = findViewById(R.id.tvIndexPath)
        tvBackend = findViewById(R.id.tvBackend)
        modelList = findViewById(R.id.modelList)
        sliderF0Key = findViewById(R.id.sliderF0Key)
        sliderProtect = findViewById(R.id.sliderProtect)
        sliderLatency = findViewById(R.id.sliderLatency)
        sliderNoise = findViewById(R.id.sliderNoise)
        sliderEq = findViewById(R.id.sliderEq)
        sliderNoiseGate = findViewById(R.id.sliderNoiseGate)
        sliderIndexRate = findViewById(R.id.sliderIndexRate)
        switchDenoise = findViewById(R.id.switchDenoise)
        switchVocalRange = findViewById(R.id.switchVocalRange)
        pitchSelector = findViewById(R.id.pitchSelector)
        tvCrossfade = findViewById(R.id.tvCrossfade)
        sliderCrossfade = findViewById(R.id.sliderCrossfade)
        tvVadEnergy = findViewById(R.id.tvVadEnergy)
        sliderVadEnergy = findViewById(R.id.sliderVadEnergy)
        tvVadFrames = findViewById(R.id.tvVadFrames)
        sliderVadFrames = findViewById(R.id.sliderVadFrames)
        tvOverlap = findViewById(R.id.tvOverlap)
        sliderOverlap = findViewById(R.id.sliderOverlap)

        // 恢复保存的设置
        val saved = settings.loadSettings()
        rvcRealtime.engine.backendMode = saved.backendMode
        rvcRealtime.f0UpKey = saved.f0UpKey
        rvcRealtime.latencyMs = (saved.latency * 1000).toInt()
        rvcRealtime.engine.noiseLevel = saved.noiseLevel
        rvcRealtime.engine.eqLevel = saved.eqLevel
        rvcRealtime.engine.noiseGateDb = saved.noiseGateDb.toDouble()
        rvcRealtime.engine.outputDenoiseEnabled = saved.outputDenoise
        rvcRealtime.engine.vocalRangeFilterEnabled = saved.vocalRangeFilter
        rvcRealtime.engine.indexRate = saved.indexRate.toDouble()
        rvcRealtime.engine.protectRate = saved.protectRate.toDouble()
        rvcRealtime.engine.filterRadius = saved.filterRadius
        rvcRealtime.engine.volume = saved.volume
        sliderF0Key.value = saved.f0UpKey.toFloat()
        sliderProtect.value = saved.protectRate
        sliderLatency.value = saved.latency
        sliderNoise.value = saved.noiseLevel.toFloat()
        sliderEq.value = saved.eqLevel.toFloat()
        sliderNoiseGate.value = saved.noiseGateDb
        switchDenoise.isChecked = saved.outputDenoise
        switchVocalRange.isChecked = saved.vocalRangeFilter
        rvcRealtime.engine.pitchExtractor = saved.pitchExtractor
        when (saved.pitchExtractor) {
            1 -> pitchSelector.check(R.id.pitchRmvpe)
            2 -> pitchSelector.check(R.id.pitchAuto)
            else -> pitchSelector.check(R.id.pitchFcpe)
        }
        sliderIndexRate.value = saved.indexRate
        tvF0Key.text = getString(R.string.rvc_f0_key, saved.f0UpKey)
        tvProtect.text = getString(R.string.rvc_protect, "%.2f".format(saved.protectRate))
        tvLatency.text = getString(R.string.rvc_latency, saved.latency)
        tvNoise.text = getString(R.string.rvc_noise, saved.noiseLevel)
        tvEq.text = getString(R.string.rvc_eq, saved.eqLevel)
        tvNoiseGate.text = "降噪门控: ${saved.noiseGateDb.toInt()}dB"
        tvIndexRate.text = "索引融合: %.2f".format(saved.indexRate)
        sliderCrossfade.value = saved.crossfadeSamples.toFloat()
        sliderVadEnergy.value = saved.vadEnergyThreshold
        sliderVadFrames.value = saved.vadSilenceFrames.toFloat()
        sliderOverlap.value = saved.overlapDivisor.toFloat()
        rvcRealtime.engine.crossfadeSamples = saved.crossfadeSamples
        rvcRealtime.engine.vadEnergyThreshold = saved.vadEnergyThreshold
        rvcRealtime.engine.vadSilenceFrames = saved.vadSilenceFrames
        rvcRealtime.engine.overlapDivisor = saved.overlapDivisor
        tvCrossfade.text = "淡入淡出: ${saved.crossfadeSamples} 采样"
        tvVadEnergy.text = "VAD 阈值: ${saved.vadEnergyThreshold.toInt()}"
        tvVadFrames.text = "VAD 静音帧: ${saved.vadSilenceFrames} (${saved.vadSilenceFrames * 160 / 48000}秒)"
        tvOverlap.text = if (saved.overlapDivisor == 0) "录音重叠: 关闭" else "录音重叠: 1/${saved.overlapDivisor} (${100 / saved.overlapDivisor}%)"


        btnRvcRealtime = findViewById(R.id.btnRvcRealtime)
        btnCloud = findViewById(R.id.btnCloud)
        btnRecordToFile = findViewById(R.id.btnRecordToFile)
        btnLocalConvert = findViewById(R.id.btnLocalConvert)
        etBotUrl = findViewById(R.id.etBotUrl)
        etBotToken = findViewById(R.id.etBotToken)
        etBotGroup = findViewById(R.id.etBotGroup)
        tvBotStatus = findViewById(R.id.tvBotStatus)
        btnBotSend = findViewById(R.id.btnBotSend)

        etBotUrl.setText(settings.getString("botUrl", "127.0.0.1"))
        etBotToken.setText(settings.getString("botToken", ""))
        etBotGroup.setText(settings.getString("botGroup", ""))

        // 启动 OneBot WS 服务器（Lagrange ReverseWebSocket 默认连 2536）
        startBotServer()

        val botTargetGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.botTargetGroup)
        val chipBotGroup = findViewById<com.google.android.material.chip.Chip>(R.id.chipBotGroup)
        val chipBotPrivate = findViewById<com.google.android.material.chip.Chip>(R.id.chipBotPrivate)
        if (settings.getBoolean("botTargetPrivate", false)) chipBotPrivate.isChecked = true
        botTargetGroup.setOnCheckedStateChangeListener { _, _ ->
            settings.save("botTargetPrivate", chipBotPrivate.isChecked)
        }

        btnBotSend.setOnClickListener {
            sendAudioPicker.launch(arrayOf("audio/*"))
        }

        val backendSwitch = findViewById<com.google.android.material.chip.ChipGroup>(R.id.backendSwitch)
        fun reloadWithBackend(mode: Int) {
            rvcRealtime.engine.backendMode = mode; settings.save("backendMode", mode)
            android.util.Log.e("RVC", "Backend switch to mode=$mode")
            val dir = currentModelDir ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                android.util.Log.e("RVC", "Reloading model with backendMode=$mode")
                val ok = rvcRealtime.engine.load(dir)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        tvBackend.text = "后端: ${rvcRealtime.engine.backendInfo}"
                    } else {
                        Toast.makeText(this@MainActivity, "切换后端失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        // 动态生成后端选择器
        val backends = rvcRealtime.engine.getAvailableBackends()
        backendSwitch.removeAllViews()
        for ((mode, label) in backends) {
            val chip = com.google.android.material.chip.Chip(this, null, com.google.android.material.R.style.Widget_Material3_Chip_Filter)
            chip.text = label
            chip.isCheckable = true
            chip.setOnClickListener { reloadWithBackend(mode) }
            if (mode == rvcRealtime.engine.backendMode) chip.isChecked = true
            backendSwitch.addView(chip)
        }

        sliderF0Key.addOnChangeListener { _, value, _ ->
            tvF0Key.text = getString(R.string.rvc_f0_key, value.toInt())
            rvcRealtime.f0UpKey = value.toInt(); settings.save("f0UpKey", value.toInt())
        }

        sliderLatency.addOnChangeListener { _, value, _ ->
            tvLatency.text = getString(R.string.rvc_latency, value)
            rvcRealtime.latencyMs = (value * 1000).toInt(); settings.save("latency", value)
        }

        sliderNoise.addOnChangeListener { _, value, _ ->
            val level = value.toInt()
            tvNoise.text = getString(R.string.rvc_noise, level)
            rvcRealtime.engine.noiseLevel = level; settings.save("noiseLevel", level)
        }

        sliderEq.addOnChangeListener { _, value, _ ->
            val level = value.toInt()
            tvEq.text = getString(R.string.rvc_eq, level)
            rvcRealtime.engine.eqLevel = level; settings.save("eqLevel", level)
        }

        sliderProtect.addOnChangeListener { _, value, _ ->
            tvProtect.text = getString(R.string.rvc_protect, "%.2f".format(value))
            rvcRealtime.engine.protectRate = value.toDouble(); settings.save("protectRate", value)
        }

        sliderNoiseGate.addOnChangeListener { _, value, _ ->
            tvNoiseGate.text = "降噪门控: ${value.toInt()}dB"
            rvcRealtime.engine.noiseGateDb = value.toDouble(); settings.save("noiseGateDb", value)
        }

        switchDenoise.setOnCheckedChangeListener { _, checked ->
            rvcRealtime.engine.outputDenoiseEnabled = checked; settings.save("outputDenoise", checked)
        }

        switchVocalRange.setOnCheckedChangeListener { _, checked ->
            rvcRealtime.engine.vocalRangeFilterEnabled = checked; settings.save("vocalRangeFilter", checked)
        }
        pitchSelector.setOnCheckedStateChangeListener { group, _ ->
            val mode = when (group.checkedChipId) {
                R.id.pitchRmvpe -> 1
                R.id.pitchAuto -> 2
                else -> 0
            }
            rvcRealtime.engine.pitchExtractor = mode; settings.save("pitchExtractor", mode)
        }
        sliderCrossfade.addOnChangeListener { _, value, _ ->
            val v = value.toInt(); rvcRealtime.engine.crossfadeSamples = v; settings.save("crossfadeSamples", v)
            tvCrossfade.text = "淡入淡出: $v 采样"
        }
        sliderVadEnergy.addOnChangeListener { _, value, _ ->
            rvcRealtime.engine.vadEnergyThreshold = value; settings.save("vadEnergyThreshold", value)
            tvVadEnergy.text = "VAD 阈值: ${value.toInt()}"
        }
        sliderVadFrames.addOnChangeListener { _, value, _ ->
            val v = value.toInt(); rvcRealtime.engine.vadSilenceFrames = v; settings.save("vadSilenceFrames", v)
            tvVadFrames.text = "VAD 静音帧: $v (${v * 160 / 48000}秒)"
        }
        sliderOverlap.addOnChangeListener { _, value, _ ->
            val v = value.toInt(); rvcRealtime.engine.overlapDivisor = v; settings.save("overlapDivisor", v)
            tvOverlap.text = if (v == 0) "录音重叠: 关闭" else "录音重叠: 1/$v (${100 / v}%)"
        }

        sliderIndexRate.addOnChangeListener { _, value, _ ->
            tvIndexRate.text = "索引融合: %.2f".format(value)
            rvcRealtime.engine.indexRate = value.toDouble(); settings.save("indexRate", value)
        }
        tvIndexRate.text = "索引融合: 0.00"

        findViewById<MaterialButton>(R.id.btnSelectIndex).setOnClickListener {
            indexPicker.launch(arrayOf("*/*"))
        }

        btnRvcRealtime.setOnClickListener {
            if (currentModelDir == null) { Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (rvcRealtime.runMode.value == io.github.neboyang.voicechanger.RVCRealtime.RunMode.REALTIME ||
                rvcRealtime.runMode.value == io.github.neboyang.voicechanger.RVCRealtime.RunMode.VAD) {
                rvcRealtime.stop()
            } else {
                rvcRealtime.audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                rvcRealtime.onError = { t -> runOnUiThread { Toast.makeText(this, "错误: ${t.message}", Toast.LENGTH_LONG).show() } }
                withRecordPermission { rvcRealtime.start() }
            }
        }

        // 录音 → 变声 → 保存文件（不外放）
        btnRecordToFile.setOnClickListener {
            if (currentModelDir == null) { Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (rvcRealtime.runMode.value == io.github.neboyang.voicechanger.RVCRealtime.RunMode.RECORD_TO_FILE) {
                rvcRealtime.stop()
                Toast.makeText(this, "已停止，文件保存中…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dir = File("/sdcard/rvc"); dir.mkdirs()
            val outFile = File(dir, "record_${System.currentTimeMillis()}.wav")
            rvcRealtime.onError = { t -> runOnUiThread { Toast.makeText(this, "错误: ${t.message}", Toast.LENGTH_LONG).show() } }
            rvcRealtime.onRecordSaved = { f ->
                lastConvertedFile = f
                runOnUiThread {
                    tvStatus.text = "已保存: ${f.name}"
                    Toast.makeText(this, "变声文件已保存: ${f.name}", Toast.LENGTH_LONG).show()
                }
            }
            withRecordPermission { rvcRealtime.startRecordToFile(outFile) }
        }

        val modeSwitch = findViewById<com.google.android.material.chip.ChipGroup>(R.id.modeSwitch)
        fun applyMode() {
            val realtime = modeSwitch.checkedChipId == R.id.modeRealtime
            rvcRealtime.realtimeMode = realtime
            // 实时模式不需要"录音不外放"
            btnRecordToFile.visibility = if (realtime) android.view.View.GONE else android.view.View.VISIBLE
        }
        modeSwitch.setOnCheckedStateChangeListener { _, _ ->
            applyMode()
        }
        applyMode()

        btnRvcRealtime.text = "开始"

        lifecycleScope.launch {
            rvcRealtime.runMode.collect { mode ->
                when (mode) {
                    io.github.neboyang.voicechanger.RVCRealtime.RunMode.REALTIME -> {
                        btnRvcRealtime.text = "实时变声中…停止"
                        tvStatus.text = "实时变声运行中"
                    }
                    io.github.neboyang.voicechanger.RVCRealtime.RunMode.VAD -> {
                        btnRvcRealtime.text = "录音变声中…停止"
                        tvStatus.text = "录音变声运行中"
                    }
                    io.github.neboyang.voicechanger.RVCRealtime.RunMode.RECORD_TO_FILE -> {
                        btnRecordToFile.text = "录音中…点击停止保存"
                        tvStatus.text = "录音保存运行中"
                    }
                    io.github.neboyang.voicechanger.RVCRealtime.RunMode.IDLE -> {
                        val realtime = rvcRealtime.realtimeMode
                        btnRvcRealtime.text = if (realtime) "开始实时变声" else "开始录音变声"
                        btnRecordToFile.text = "录音变声并保存文件（不外放）"
                        tvStatus.text = "就绪"
                    }
                }
            }
        }

        // 云端变声按钮
        btnCloud.setOnClickListener {
            val dir = File("/sdcard/rvc")
            val latest = dir.listFiles { f -> f.name.endsWith(".wav") }?.maxByOrNull { it.lastModified() }
            cloudRvc = ReplicateCloudRvc(this)
            cloudRvc?.show(latest, cloudModelPicker)
        }

        btnLocalConvert.setOnClickListener {
            if (currentModelDir == null) { Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            localAudioPicker.launch(arrayOf("audio/*"))
        }

        findViewById<MaterialButton>(R.id.btnImportOnnx).setOnClickListener {
            if (!checkStoragePermission()) { requestStoragePermission(); return@setOnClickListener }
            folderPicker.launch(null)
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
                if (ok) { currentModelDir = dir; tvModelStatus.text = getString(R.string.rvc_model_loaded, info.name); btnRvcRealtime.isEnabled = true; btnRecordToFile.isEnabled = true; btnLocalConvert.isEnabled = true; tvBackend.text = "后端: ${rvcRealtime.engine.backendInfo}" }
                else tvModelStatus.setText(R.string.rvc_model_failed)
            }
        }
    }

    private fun startBotServer() {
        if (botServer != null) return
        val token = etBotToken.text.toString().trim()
        botServer = OneBotWsServer(
            port = 2536,
            token = token,
            onStatus = { status -> runOnUiThread { tvBotStatus.text = status } },
            onVoiceMessage = { data, target ->
                handleBotVoice(data, target)
            }
        )
        botServer?.setReuseAddr(true)
        botServer?.start()
        tvBotStatus.text = "WS 服务器启动中 (2536)…"
    }

    /** 收到群里语音 → 变声 → 发回 */
    private fun handleBotVoice(data: org.json.JSONObject, target: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = data.optString("file", "")
                val url = data.optString("url", "")
                runOnUiThread { tvBotStatus.text = "收到语音，变声中…" }

                // 优先用 file（本地路径），否则下载 url
                var audioFile: File? = null
                if (file.startsWith("http")) {
                    audioFile = downloadToCache(url.ifBlank { file })
                } else if (file.isNotBlank()) {
                    val f = File(file)
                    if (f.exists()) audioFile = f
                }
                if (audioFile == null) {
                    runOnUiThread { tvBotStatus.text = "无法获取语音文件" }
                    return@launch
                }

                // 读取音频 → 16k → RVC
                val wav = decodeToFloat(audioFile)
                if (wav == null || wav.first.size < 320) {
                    runOnUiThread { tvBotStatus.text = "语音解析失败" }
                    return@launch
                }
                val result = rvcRealtime.engine.infer(wav.first, rvcRealtime.f0UpKey)
                if (result == null || result.isEmpty()) {
                    runOnUiThread { tvBotStatus.text = "变声失败：模型未加载？" }
                    return@launch
                }

                val outFile = File("/sdcard/rvc", "qq_${System.currentTimeMillis()}.wav")
                outFile.parentFile?.mkdirs()
                io.github.neboyang.voicechanger.WavFile.write(outFile, result, rvcRealtime.engine.targetSr)
                lastConvertedFile = outFile

                val conn = botServer?.getActiveConnections()?.firstOrNull()
                if (conn != null) {
                    botServer!!.sendGroupRecord(conn, target, outFile.absolutePath, "✨ 变声完成")
                    runOnUiThread { tvBotStatus.text = "已发回变声语音" }
                }
            } catch (e: Exception) {
                runOnUiThread { tvBotStatus.text = "变声处理异常: ${e.message}" }
                android.util.Log.e("RVC", "Bot voice fail", e)
            }
        }
    }

    /** 下载 url 到缓存 */
    private fun downloadToCache(url: String): File? {
        return try {
            val req = okhttp3.Request.Builder().url(url).build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val tmp = File(cacheDir, "bot_voice_${System.currentTimeMillis()}.silk")
            resp.body?.byteStream()?.use { it.copyTo(tmp.outputStream()) }
            resp.close()
            tmp
        } catch (e: Exception) { null }
    }

    /** 解析音频文件为 16kHz float（支持 wav；silk 需先转 wav） */
    private fun decodeToFloat(file: File): Pair<FloatArray, Int>? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44 || bytes[0] != 0x52.toByte()) return null
            val sampleRate = java.nio.ByteBuffer.wrap(bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            val channels = java.nio.ByteBuffer.wrap(bytes, 22, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
            val dataOff = 44
            val pcm = bytes.copyOfRange(dataOff, bytes.size)
            val floatPcm = FloatArray(pcm.size / 2) { java.nio.ByteBuffer.wrap(pcm, it * 2, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short / 32768f }
            val mono = if (channels == 1) floatPcm else FloatArray(floatPcm.size / 2) { (floatPcm[it * 2] + floatPcm[it * 2 + 1]) / 2f }
            val sr16k = if (sampleRate == 16000) mono else {
                val ratio = sampleRate.toDouble() / 16000
                FloatArray((mono.size / ratio).toInt().coerceAtLeast(1)) { mono[(it * ratio).toInt().coerceIn(0, mono.size - 1)] }
            }
            sr16k to 16000
        } catch (e: Exception) { null }
    }

    private fun findLatestConverted(): File? {
        val dir = File("/sdcard/rvc")
        return dir.listFiles { f -> f.name.endsWith(".wav") }?.maxByOrNull { it.lastModified() }
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

    override fun onDestroy() {
        super.onDestroy()
        rvcRealtime.release()
        try { botServer?.stop(1000) } catch (_: Exception) {}
        botServer = null
    }
}
