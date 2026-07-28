package io.github.neboyang.voicechanger

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RVC inference engine using ONNX Runtime (NNAPI/GPU capable).
 */
class RVCOnnxEngine {
    companion object {
        const val HUBERT_SR = 16000
        const val FEAT_DIM = 768
        const val RMVPE_MEL_BINS = 128
        const val RMVPE_OUTPUT_BINS = 360
        const val RMVPE_FFT_SIZE = 1024
        const val RMVPE_SPECTRUM_BINS = RMVPE_FFT_SIZE / 2 + 1
        const val RMVPE_HOP_SIZE = 160
        const val MIN_RMVPE_FRAMES = 32
        const val RMVPE_FRAME_ALIGNMENT = 32
        const val RMVPE_MEL_FMIN = 30.0
        const val RMVPE_MEL_FMAX = 8000.0
        const val RMVPE_CENTS_BASE = 1997.3794084376191
        const val RMVPE_CENTS_PER_BIN = 20.0
        const val RMVPE_VOICED_THRESHOLD = 0.03f
        const val RMVPE_LOG_EPSILON = 1e-6
    }

    val env = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private var loaded = false

    /** 查询可用后端列表，返回 (mode, 显示名) 对 */
    fun getAvailableBackends(): List<Pair<Int, String>> {
        val available = OrtEnvironment.getAvailableProviders().map { it.name }.toSet()
        val list = mutableListOf<Pair<Int, String>>()
        if (available.any { "nnapi" in it.lowercase() }) list.add(2 to "NPU")
        if (available.any { "xnnpack" in it.lowercase() }) list.add(1 to "加速CPU")
        list.add(0 to "CPU")
        return list
    }

    var targetSr = 40000
        private set

    /** 降噪级别 0~5（0=关） */
    var noiseLevel: Int = 0

    /** 后端选择: 0=CPU, 1=XNNPACK, 2=QNN(NPU) */
    @Volatile var backendMode: Int = 2

    /** 自适应降噪门控 (dB) 0=关闭 */
    var noiseGateDb: Double = 0.0

    /** 输出降噪开关 */
    var outputDenoiseEnabled: Boolean = false

    /** 音域滤波开关 */
    var vocalRangeFilterEnabled: Boolean = false

    /** F0 中值滤波半径 */
    var filterRadius: Int = 3

    /** 是否使用 RMVPE（否则用自相关 F0） */
    var useRmvpe: Boolean = true

    /** 索引路径 */
    var indexPath: String? = null

    /** 索引融合比例 0~1 */
    var indexRate: Double = 0.0

    /** 无声保护 0~1 */
    var protectRate: Double = 0.33

    /** RMS 混合率 0~1 */
    var rmsMixRate: Double = 0.25

    /** 外放补偿 EQ 级别 0~5（0=关，5=最强补偿） */
    var eqLevel: Int = 0

    /** 外放音量 0.0~1.0 */
    var volume: Float = 0.8f

    /** 当前推理后端 */
    var backendInfo: String = "未知"
        private set

    private var loadedIndex: FeatureIndex? = null
    private val rnd = java.util.Random()

    private var isCombinedModel = false
    private var voiceModelName: String = "generator" // used in infer()

    fun load(modelDir: File): Boolean {
        try {
            // 清除旧 session 缓存
            sessions.values.forEach { it.close() }; sessions.clear()
            isCombinedModel = false; loaded = false
            loadedIndex = null
            val cfgFile = File(modelDir, "config.json")
            if (cfgFile.exists()) {
                val json = org.json.JSONObject(cfgFile.readText())
                targetSr = json.optInt("target_sr", 40000)
            }
            backendInfo = detectActualBackend(modelDir)
            val opts = OrtSession.SessionOptions()
            opts.setSessionLogVerbosityLevel(0)
            opts.addConfigEntry("session.intra_op.allow_spinning", "1")
            opts.addConfigEntry("qnn_context_priority", "high")
            opts.addConfigEntry("qnn_htp_performance_mode", "sustained_high_performance")

            val available = OrtEnvironment.getAvailableProviders().map { it.name }.toSet()
            val providers = when (backendMode) {
                0 -> "CPUExecutionProvider"
                1 -> if (available.any { "xnnpack" in it.lowercase() }) "XnnpackExecutionProvider,CPUExecutionProvider" else "CPUExecutionProvider"
                else -> {
                    buildString {
                        if (available.any { "nnapi" in it.lowercase() }) append("NnapiExecutionProvider,")
                        if (available.any { "xnnpack" in it.lowercase() }) append("XnnpackExecutionProvider,")
                        append("CPUExecutionProvider")
                    }
                }
            }
            opts.addConfigEntry("session.set_providers", providers)

            // hubert (required)
            val hubertPath = File(modelDir, "hubert.onnx")
            if (!hubertPath.exists()) { Log.e("RVC", "Missing hubert.onnx"); return false }
            sessions["hubert"] = env.createSession(hubertPath.absolutePath, opts)
            Log.e("RVC", "Loaded hubert")

            // rmvpe (optional)
            val rmvpePath = File(modelDir, "rmvpe.onnx")
            if (rmvpePath.exists()) {
                sessions["rmvpe"] = env.createSession(rmvpePath.absolutePath, opts)
                Log.e("RVC", "Loaded rmvpe")
            } else {
                Log.e("RVC", "rmvpe.onnx missing, will use autocorrelation F0")
            }

            // Detect combined voice model (any .onnx that is not hubert/rmvpe/split names)
            val splitNames = setOf("hubert.onnx", "rmvpe.onnx", "text_encoder.onnx", "flow.onnx", "generator.onnx")
            val combinedFiles = modelDir.listFiles { f -> f.extension == "onnx" && f.name !in splitNames }
            if (combinedFiles != null && combinedFiles.size == 1) {
                sessions["voice"] = env.createSession(combinedFiles[0].absolutePath, opts)
                isCombinedModel = true
                voiceModelName = "voice"
                Log.e("RVC", "Loaded combined voice model: ${combinedFiles[0].name}")
            }

            if (!isCombinedModel) {
                // Fall back to split models
                for (name in listOf("text_encoder", "flow", "generator")) {
                    val path = File(modelDir, "$name.onnx")
                    if (!path.exists()) { Log.e("RVC", "Missing: $path"); return false }
                    sessions[name] = env.createSession(path.absolutePath, opts)
                    Log.e("RVC", "Loaded $name")
                }
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

        // 降噪门控
        if (noiseLevel > 0) {
            val frameHop = 160
            val threshold = floatArrayOf(0.005f, 0.01f, 0.02f, 0.04f, 0.08f)[noiseLevel - 1]
            val attenuate = 0.1f
            for (t in 0 until audio.size / frameHop) {
                val start = t * frameHop
                val end = minOf(start + frameHop, audio.size)
                var energy = 0f
                for (i in start until end) energy += audio[i] * audio[i]
                energy = kotlin.math.sqrt(energy / (end - start))
                if (energy < threshold) {
                    for (i in start until end) audio[i] *= attenuate
                }
            }
        }

        try {
            val tTot = System.nanoTime()
            val maxFrames = 128  // 32 倍数，NPU 友好
            val hop = 160
            val totalFrames = audio.size / hop
            val allOutput = mutableListOf<FloatArray>()

            for (seg in 0 until (totalFrames + maxFrames - 1) / maxFrames) {
                try {
                val start = seg * maxFrames * hop
                val segSamples = minOf(maxFrames * hop, audio.size - start)
                if (segSamples < hop * 2) continue
                // 固定形状：补零到 maxFrames * hop，避免 NPU 反复重编译
                val segAudio = FloatArray(maxFrames * hop) { i -> if (i < segSamples) audio[start + i] else 0f }
                val sl = maxFrames  // 固定 50 帧

                // HuBERT (auto-detect input format)
                val tHu = System.nanoTime()
                val hubertSess = sessions["hubert"]!!
                val hubertInputs = hubertSess.inputNames
                val hubertOut = if (hubertInputs.contains("source")) {
                    val padded = FloatArray(segAudio.size) { segAudio[it] }
                    val mask = ByteArray(segAudio.size) { 0 }
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(padded), longArrayOf(1, segAudio.size.toLong())).use { src ->
                        OnnxTensor.createTensor(env, ByteBuffer.wrap(mask), longArrayOf(1, segAudio.size.toLong()), OnnxJavaType.BOOL).use { pm ->
                            hubertSess.run(mapOf("source" to src, "padding_mask" to pm))
                        }
                    }
                } else {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(segAudio), longArrayOf(1, segAudio.size.toLong())).use { src ->
                        hubertSess.run(mapOf("input_values" to src))
                    }
                }
                val featTensor = try {
                    hubertOut.get("features").get() as OnnxTensor
                } catch (_: Exception) {
                    hubertOut[0] as OnnxTensor
                }
                val feat = featTensor.floatBuffer
                val featArr = FloatArray(feat.remaining()).also { feat.get(it) }
                val featDim = 768
                val T = featArr.size / featDim
                if (T < 1) continue
                Log.e("RVC", "  hubert ${(System.nanoTime()-tHu)/1_000_000}ms")

                // Interpolate to sl frames
                val interp = FloatArray(sl * FEAT_DIM)
                for (t in 0 until sl) {
                    val srcT = (t * T / sl).coerceIn(0, T - 1)
                    val srcPos = (srcT * FEAT_DIM).coerceAtMost(featArr.size - FEAT_DIM)
                    System.arraycopy(featArr, srcPos, interp, t * FEAT_DIM, FEAT_DIM)
                }

                // F0: RMVPE or autocorrelation fallback
                val tF0 = System.nanoTime()
                val pitchf = if (sessions.containsKey("rmvpe") && useRmvpe) {
                    extractRmvpePitch(segAudio, sl)
                } else {
                    autocorrelateF0(segAudio, segSamples, sl, hop)
                }
                val filteredPitchf = applyMedianFilter(pitchf, filterRadius)
                Log.e("RVC", "  f0 ${(System.nanoTime()-tF0)/1_000_000}ms")

                // Index fusion
                val phone = if (loadedIndex != null && indexRate > 0.0) {
                    fuseIndex(interp, sl, loadedIndex!!, indexRate)
                } else interp
                val blended = applyProtect(phone, interp, filteredPitchf, protectRate)

                val shift = Math.pow(2.0, (f0UpKey / 12.0)).toFloat()
                val shiftedPitchf = FloatArray(sl) { filteredPitchf[it] * shift }

                if (isCombinedModel) {
                    val tVc = System.nanoTime()
                    val pitchMel = shiftedPitchf.map { hz -> melToCoarse(hz) }.toLongArray()
                    val inputs = linkedMapOf<String, OnnxTensor>(
                        "phone" to OnnxTensor.createTensor(env, FloatBuffer.wrap(blended),
                            longArrayOf(1, sl.toLong(), FEAT_DIM.toLong())),
                        "phone_lengths" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sl.toLong())), longArrayOf(1)),
                        "pitch" to OnnxTensor.createTensor(env, LongBuffer.wrap(pitchMel), longArrayOf(1, sl.toLong())),
                        "nsff0" to OnnxTensor.createTensor(env, FloatBuffer.wrap(shiftedPitchf), longArrayOf(1, sl.toLong())),
                        "sid" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1)),
                    )
                    val vcOut = sessions["voice"]!!.run(inputs)
                    val audioTensor = vcOut[0] as OnnxTensor
                    val audioArr = FloatArray(audioTensor.floatBuffer.remaining()).also { audioTensor.floatBuffer.get(it) }
                    // Report sample rate from model output
                    try { targetSr = (vcOut[1] as OnnxTensor).intBuffer.remaining().let { (vcOut[1] as OnnxTensor).intBuffer.get() } } catch (_: Exception) {}
                    inputs.values.forEach { it.close() }; vcOut.close()
                    Log.e("RVC", "  voice ${(System.nanoTime()-tVc)/1_000_000}ms")
                    allOutput.add(audioArr)
                } else {
                    // Split models: TextEncoder + Flow + Generator
                    val tTe = System.nanoTime()
                    val phoneTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(blended),
                        longArrayOf(1, sl.toLong(), FEAT_DIM.toLong()))
                    val pitchMel = shiftedPitchf.map { hz -> melToCoarse(hz) }.toLongArray()
                    val pitchTensor = OnnxTensor.createTensor(env,
                        LongBuffer.wrap(pitchMel), longArrayOf(1, sl.toLong()))
                    val lengths = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sl.toLong())), longArrayOf(1))

                    val teOut = sessions["text_encoder"]!!.run(mapOf("phone" to phoneTensor, "pitch" to pitchTensor, "lengths" to lengths))
                    val m = (teOut.get("m").get() as OnnxTensor).floatBuffer
                    val logs = (teOut.get("logs").get() as OnnxTensor).floatBuffer
                    val xMask = (teOut.get("x_mask").get() as OnnxTensor).floatBuffer
                    val mArr = FloatArray(m.remaining()).also { m.get(it) }
                    val logsArr = FloatArray(logs.remaining()).also { logs.get(it) }
                    val maskArr = FloatArray(xMask.remaining()).also { xMask.get(it) }
                    Log.e("RVC", "  text_encoder ${(System.nanoTime()-tTe)/1_000_000}ms")

                    val C = 192; val rand = java.util.Random()
                    val zP = FloatArray(C * sl) { i ->
                        val noise = rand.nextFloat() * 2f - 1f
                        (mArr[i] + kotlin.math.exp(logsArr[i]) * noise * 0.66666f) * maskArr[i % sl]
                    }

                    val tFlow = System.nanoTime()
                    val g = FloatArray(256)
                    val flowOut = sessions["flow"]!!.run(mapOf(
                        "z_p" to OnnxTensor.createTensor(env, FloatBuffer.wrap(zP), longArrayOf(1, C.toLong(), sl.toLong())),
                        "x_mask" to OnnxTensor.createTensor(env, FloatBuffer.wrap(maskArr), longArrayOf(1, 1, sl.toLong())),
                        "g" to OnnxTensor.createTensor(env, FloatBuffer.wrap(g), longArrayOf(1, 256, 1))))
                    val z = (flowOut.get("z").get() as OnnxTensor).floatBuffer
                    val zArr = FloatArray(z.remaining()).also { z.get(it) }
                    Log.e("RVC", "  flow ${(System.nanoTime()-tFlow)/1_000_000}ms")

                    val tGen = System.nanoTime()
                    val zMasked = FloatArray(C * sl) { zArr[it] * maskArr[it % sl] }
                    val genOut = sessions["generator"]!!.run(mapOf(
                        "z" to OnnxTensor.createTensor(env, FloatBuffer.wrap(zMasked), longArrayOf(1, C.toLong(), sl.toLong())),
                        "f0" to OnnxTensor.createTensor(env, FloatBuffer.wrap(shiftedPitchf), longArrayOf(1, sl.toLong())),
                        "g" to OnnxTensor.createTensor(env, FloatBuffer.wrap(g), longArrayOf(1, 256, 1))))
                    val genAudio = (genOut.get("audio").get() as OnnxTensor).floatBuffer
                    val audioArr = FloatArray(genAudio.remaining()).also { genAudio.get(it) }
                    Log.e("RVC", "  generator ${(System.nanoTime()-tGen)/1_000_000}ms")
                    allOutput.add(audioArr)
                }
                } catch (e: Exception) {
                    Log.e("RVC", "Segment $seg failed", e)
                }
            }
            val total = allOutput.sumOf { it.size }
            val result = FloatArray(total)
            var offset = 0
            for (arr in allOutput) { System.arraycopy(arr, 0, result, offset, arr.size); offset += arr.size }

            val output = applyOutputProcessing(result)
            val vol = volume.coerceIn(0f, 1f)
            if (vol < 1f) { for (i in output.indices) output[i] *= vol }
            Log.e("RVC", "  total ${(System.nanoTime()-tTot)/1_000_000}ms")
            return output
        } catch (e: Exception) {
            Log.e("RVC", "Infer failed", e)
            return null
        }
    }

    // ──────────────────────────────────────────────
    // 输出后处理
    // ──────────────────────────────────────────────
    private fun applyOutputProcessing(audio: FloatArray): FloatArray {
        var out = audio

        // 外放补偿 EQ
        if (eqLevel > 0) {
            val intensity = eqLevel * 0.15f
            for (i in out.size - 1 downTo 1) {
                out[i] = out[i] - intensity * out[i - 1]
            }
            var peak = 0f
            for (i in out.indices) { val a = abs(out[i]); if (a > peak) peak = a }
            if (peak > 0.95f) { val s = 0.95f / peak; for (i in out.indices) out[i] *= s }
        }

        // 自适应降噪门控
        if (noiseGateDb > 0.0) {
            out = adaptiveNoiseGate(out, noiseGateDb, targetSr)
        }

        // 输出降噪
        if (outputDenoiseEnabled) {
            out = outputDenoise(out, if (noiseGateDb > 0.0) noiseGateDb else 35.0, targetSr)
        }

        // 音域滤波
        if (vocalRangeFilterEnabled) {
            out = vocalRangeFilter(out, targetSr)
        }

        // RMS 混合
        out = rmsMix(out, noiseGateDb, rmsMixRate, targetSr)

        // 软限制器
        out = softLimiter(out)

        return out
    }

    // ──────────────────────────────────────────────
    // 输入降噪 (adaptive noise gate)
    // ──────────────────────────────────────────────
    private fun adaptiveNoiseGate(audio: FloatArray, thresholdDb: Double, sampleRate: Int): FloatArray {
        if (audio.isEmpty() || thresholdDb <= 0.0) return audio
        val frameSize = (sampleRate / 50).coerceAtLeast(1) // 更大帧长减少抖动
        val output = FloatArray(audio.size)
        var gain = 1.0f
        val att = 0.05f; val rel = 0.02f // 更慢攻击/释放防泵动
        val minGain = 0.1f
        val kneeDb = 12.0
        var offset = 0
        while (offset < audio.size) {
            val end = min(offset + frameSize, audio.size)
            var sum = 0.0
            for (i in offset until end) sum += audio[i].toDouble() * audio[i]
            val rms = sqrt(sum / (end - offset))
            val frameDb = 20.0 * log10(rms.coerceAtLeast(1e-10)) + 100.0
            val targetGain = if (frameDb >= thresholdDb) 1.0f
            else if (frameDb >= thresholdDb - kneeDb) {
                val r = ((frameDb - (thresholdDb - kneeDb)) / kneeDb).toFloat().coerceIn(0f, 1f)
                minGain + (1f - minGain) * r * r
            } else minGain
            gain += (targetGain - gain) * (if (targetGain > gain) att else rel)
            for (i in offset until end) output[i] = audio[i] * gain
            offset = end
        }
        return output
    }

    // ──────────────────────────────────────────────
    // 输出降噪
    // ──────────────────────────────────────────────
    private fun outputDenoise(audio: FloatArray, thresholdDb: Double, sampleRate: Int): FloatArray {
        if (audio.isEmpty()) return audio
        val frameSize = (sampleRate / 100).coerceAtLeast(1)
        var noiseFloorDb = 100.0
        var gain = 1.0f
        val output = FloatArray(audio.size)
        val att = 0.05f; val rel = 0.02f
        val minGain = 0.1f
        val marginDb = 8.0; val floorRise = 0.25
        val kneeDb = 12.0
        var offset = 0
        while (offset < audio.size) {
            val end = min(offset + frameSize, audio.size)
            var sum = 0.0
            for (i in offset until end) sum += audio[i].toDouble() * audio[i]
            val rms = sqrt(sum / (end - offset))
            val frameDb = 20.0 * log10(rms.coerceAtLeast(1e-10)) + 100.0
            noiseFloorDb = min(noiseFloorDb + floorRise, frameDb)
            if (frameDb < noiseFloorDb) noiseFloorDb = frameDb
            val adaptiveThreshold = max(thresholdDb, noiseFloorDb + marginDb)
            val targetGain = if (frameDb >= adaptiveThreshold) 1.0f
            else if (frameDb >= adaptiveThreshold - kneeDb) {
                val r = ((frameDb - (adaptiveThreshold - kneeDb)) / kneeDb).toFloat().coerceIn(0f, 1f)
                minGain + (1f - minGain) * r * r
            } else minGain
            gain += (targetGain - gain) * (if (targetGain > gain) att else rel)
            for (i in offset until end) output[i] = audio[i] * gain
            offset = end
        }
        return output
    }

    // ──────────────────────────────────────────────
    // 音域滤波
    // ──────────────────────────────────────────────
    private fun vocalRangeFilter(audio: FloatArray, sampleRate: Int): FloatArray {
        if (audio.isEmpty() || sampleRate <= 0) return audio
        var out = BiquadFilter.highPass(sampleRate, 60.0).process(audio)
        out = BiquadFilter.highShelf(sampleRate, 2500.0, -4.5).process(out)
        return BiquadFilter.peaking(sampleRate, 3400.0, 1.5).process(out)
    }

    // ──────────────────────────────────────────────
    // RMS 混合
    // ──────────────────────────────────────────────
    private fun rmsMix(converted: FloatArray, noiseGateDb: Double, rate: Double, sampleRate: Int): FloatArray {
        val r = rate.coerceIn(0.0, 1.0).toFloat()
        if (r == 0f || converted.isEmpty()) return converted
        val srcRms = maxOf(rmsLevel(converted), 1e-3f) // 防除零
        val gain = 1f + (0.5f / srcRms - 1f) * r
        return FloatArray(converted.size) { converted[it] * gain.coerceIn(0.1f, 5f) }
    }

    private fun rmsLevel(audio: FloatArray): Float {
        var sum = 0.0; for (s in audio) sum += s * s
        return sqrt(sum / audio.size).toFloat()
    }

    // ──────────────────────────────────────────────
    // 软限制器
    // ──────────────────────────────────────────────
    private fun softLimiter(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio
        val out = FloatArray(audio.size)
        val knee = 0.88f; val curve = 0.18f; val ceiling = 0.8912509f
        for (i in audio.indices) {
            val s = audio[i]; val m = abs(s)
            if (m <= knee) out[i] = s
            else {
                val over = m - knee
                val limited = knee + over / (1f + over / curve)
                out[i] = if (s < 0f) -limited.coerceAtMost(ceiling) else limited.coerceAtMost(ceiling)
            }
        }
        return out
    }

    // ──────────────────────────────────────────────
    // Biquad 滤波器
    // ──────────────────────────────────────────────
    private class BiquadFilter(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0
        private val b0 = b0; private val b1 = b1; private val b2 = b2
        private val a1 = a1; private val a2 = a2

        fun process(audio: FloatArray): FloatArray {
            val out = FloatArray(audio.size)
            for (i in audio.indices) {
                val input = audio[i].toDouble()
                val v = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                out[i] = v.toFloat()
                x2 = x1; x1 = input; y2 = y1; y1 = v
            }
            return out
        }

        companion object {
            private fun params(sr: Int, f: Double, q: Double): Pair<Double, Double> {
                val w0 = 2.0 * PI * f.coerceIn(10.0, sr * 0.45) / sr
                return Pair(cos(w0), sin(w0) / (2.0 * q.coerceAtLeast(0.1)))
            }

            fun highPass(sr: Int, f: Double, q: Double = 0.707): BiquadFilter {
                val (cosW0, alpha) = params(sr, f, q)
                val b0 = (1.0 + cosW0) / 2.0; val b1 = -(1.0 + cosW0); val b2 = b0
                val a0 = 1.0 + alpha; val a1 = -2.0 * cosW0; val a2 = 1.0 - alpha
                return BiquadFilter(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun highShelf(sr: Int, f: Double, gainDb: Double, q: Double = 0.707): BiquadFilter {
                val (cosW0, alpha) = params(sr, f, q)
                val a = Math.pow(10.0, gainDb / 40.0); val sq = sqrt(a)
                val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sq * alpha)
                val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                val b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sq * alpha)
                val a0 = (a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sq * alpha
                val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                val a2 = (a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sq * alpha
                return BiquadFilter(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun peaking(sr: Int, f: Double, gainDb: Double, q: Double = 0.707): BiquadFilter {
                val (cosW0, alpha) = params(sr, f, q)
                val a = Math.pow(10.0, gainDb / 40.0)
                val b0 = 1.0 + alpha * a; val b1 = -2.0 * cosW0; val b2 = 1.0 - alpha * a
                val a0 = 1.0 + alpha / a; val a1 = -2.0 * cosW0; val a2 = 1.0 - alpha / a
                return BiquadFilter(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }

    // ──────────────────────────────────────────────
    // RMVPE F0 提取
    // ──────────────────────────────────────────────
    private fun extractRmvpePitch(audio16k: FloatArray, targetFrames: Int): FloatArray {
        val frameCount = ceil(targetFrames / RMVPE_FRAME_ALIGNMENT.toDouble()).toInt() * RMVPE_FRAME_ALIGNMENT
        val mel = buildRmvpeMel(audio16k, frameCount)
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel),
            longArrayOf(1, RMVPE_MEL_BINS.toLong(), frameCount.toLong()))
        val result = sessions["rmvpe"]!!.run(mapOf("input" to input))
        val output = (result.get("output").get() as OnnxTensor).floatBuffer
        val arr = FloatArray(output.remaining()).also { output.get(it) }
        input.close(); result.close()

        val pitchf = FloatArray(targetFrames)
        for (f in 0 until targetFrames) {
            val srcF = min(f, frameCount - 1)
            val off = srcF * RMVPE_OUTPUT_BINS
            var bestBin = 0; var bestConf = 0f
            for (b in 0 until RMVPE_OUTPUT_BINS) {
                val conf = arr.getOrElse(off + b) { 0f }
                if (conf > bestConf) { bestConf = conf; bestBin = b }
            }
            pitchf[f] = if (bestConf >= RMVPE_VOICED_THRESHOLD) rmvpeBinToFreq(bestBin) else 0f
        }
        return pitchf
    }

    private fun rmvpeBinToFreq(bin: Int): Float {
        return (10.0 * Math.pow(2.0, (RMVPE_CENTS_BASE + RMVPE_CENTS_PER_BIN * bin) / 1200.0)).toFloat()
    }

    private fun buildRmvpeMel(audio: FloatArray, frameCount: Int): FloatArray {
        val window = FloatArray(RMVPE_FFT_SIZE) { (0.5 - 0.5 * cos(2.0 * PI * it / RMVPE_FFT_SIZE)).toFloat() }
        val filterbank = buildMelFilterbank()
        val mel = FloatArray(RMVPE_MEL_BINS * frameCount)
        for (f in 0 until frameCount) {
            val center = f * RMVPE_HOP_SIZE
            val start = center - RMVPE_FFT_SIZE / 2
            val fb = FloatArray(RMVPE_FFT_SIZE) { i ->
                val idx = start + i
                if (idx in audio.indices) audio[idx] * window[i] else 0f
            }
            val spec = powerSpectrum(fb)
            for (mb in 0 until RMVPE_MEL_BINS) {
                var energy = 0.0
                val fo = mb * RMVPE_SPECTRUM_BINS
                for (sb in 0 until RMVPE_SPECTRUM_BINS) energy += spec[sb] * filterbank[fo + sb]
                mel[mb * frameCount + f] = log10(max(energy, RMVPE_LOG_EPSILON)).toFloat()
            }
        }
        return mel
    }

    private fun buildMelFilterbank(): FloatArray {
        val w = FloatArray(RMVPE_MEL_BINS * RMVPE_SPECTRUM_BINS)
        val melMin = hzToMel(RMVPE_MEL_FMIN); val melMax = hzToMel(RMVPE_MEL_FMAX)
        val mPts = DoubleArray(RMVPE_MEL_BINS + 2) { melMin + (melMax - melMin) * it / (RMVPE_MEL_BINS + 1) }
        val hzPts = mPts.map { melToHz(it) }
        val binPts = hzPts.map { ((RMVPE_FFT_SIZE + 1) * it / HUBERT_SR).roundToInt().coerceIn(0, RMVPE_SPECTRUM_BINS - 1) }
        for (mb in 0 until RMVPE_MEL_BINS) {
            val left = binPts[mb]; val center = max(left + 1, binPts[mb + 1])
            val right = max(center + 1, binPts[mb + 2])
            val off = mb * RMVPE_SPECTRUM_BINS
            for (b in left until center) w[off + b] = (b - left).toFloat() / (center - left)
            for (b in center until min(right, RMVPE_SPECTRUM_BINS)) w[off + b] = (right - b).toFloat() / (right - center)
        }
        return w
    }

    private fun powerSpectrum(samples: FloatArray): DoubleArray {
        val real = DoubleArray(RMVPE_FFT_SIZE) { samples[it].toDouble() }
        val imag = DoubleArray(RMVPE_FFT_SIZE)
        fft(real, imag)
        return DoubleArray(RMVPE_SPECTRUM_BINS) { real[it] * real[it] + imag[it] * imag[it] }
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        var j = 0; val n = real.size
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = real[i]; real[i] = real[j]; real[j] = t; val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenR = cos(ang); val wLenI = sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1.0; var wI = 0.0
                for (k in 0 until len / 2) {
                    val even = i + k; val odd = even + len / 2
                    val oddR = real[odd] * wR - imag[odd] * wI
                    val oddI = real[odd] * wI + imag[odd] * wR
                    real[odd] = real[even] - oddR; imag[odd] = imag[even] - oddI
                    real[even] += oddR; imag[even] += oddI
                    val nwR = wR * wLenR - wI * wLenI; wI = wR * wLenI + wI * wLenR; wR = nwR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    // ──────────────────────────────────────────────
    // Autocorrelation F0 (fallback when no RMVPE)
    // ──────────────────────────────────────────────
    private fun autocorrelateF0(audio: FloatArray, segSamples: Int, sl: Int, hop: Int): FloatArray {
        val f0 = FloatArray(sl)
        for (t in 0 until sl) {
            val s = t * hop; val end = min(s + 640, segSamples)
            if (end - s < 320) break
            var rms = 0f; for (i in s until end) rms += audio[i] * audio[i]
            rms = sqrt(rms / (end - s))
            if (rms < 0.005f) continue
            var bestCorr = 0f; var bestLag = 0
            for (lag in HUBERT_SR / 1100..min(HUBERT_SR / 50, (end - s) / 2)) {
                var corr = 0f; for (i in 0 until end - s - lag) corr += audio[s + i] * audio[s + i + lag]
                if (corr > bestCorr) { bestCorr = corr; bestLag = lag }
            }
            if (bestCorr > rms * rms * (end - s) * 0.3f) f0[t] = HUBERT_SR.toFloat() / bestLag
        }
        var last = -1
        for (i in 0 until sl) { if (f0[i] > 0) last = i; else if (last >= 0) f0[i] = f0[last] }
        return f0
    }

    // ──────────────────────────────────────────────
    // 中值滤波
    // ──────────────────────────────────────────────
    private fun applyMedianFilter(pitchf: FloatArray, radius: Int): FloatArray {
        if (radius <= 0) return pitchf
        val out = FloatArray(pitchf.size)
        for (i in pitchf.indices) {
            val start = max(0, i - radius); val end = min(pitchf.lastIndex, i + radius)
            val voiced = mutableListOf<Float>()
            for (f in start..end) { if (pitchf[f] > 0f) voiced.add(pitchf[f]) }
            out[i] = if (voiced.isEmpty()) 0f else voiced.sorted()[voiced.size / 2]
        }
        return out
    }

    // ──────────────────────────────────────────────
    // Index 索引融合
    // ──────────────────────────────────────────────
    data class FeatureIndex(val features: FloatArray, val frameCount: Int)

    fun loadIndex(path: String?) {
        if (path == null) { loadedIndex = null; return }
        try {
            val bytes = File(path).readBytes()
            val magic = "URVCIDX1".toByteArray(Charsets.US_ASCII)
            require(bytes.size >= magic.size + 4) { "无效索引" }
            require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "请使用 mobile.index 格式" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(magic.size)
            val fc = buf.int
            require(fc > 0) { "索引帧数为空" }
            val features = FloatArray(fc * FEAT_DIM)
            for (i in features.indices) features[i] = buf.float
            loadedIndex = FeatureIndex(features, fc)
            Log.e("RVC", "Loaded index: $fc frames")
        } catch (e: Exception) {
            loadedIndex = null; Log.e("RVC", "Index load failed", e)
        }
    }

    private fun fuseIndex(phone: FloatArray, frameCount: Int, index: FeatureIndex, rate: Double): FloatArray {
        val r = rate.coerceIn(0.0, 1.0).toFloat()
        if (r == 0f) return phone
        val fused = FloatArray(phone.size)
        for (f in 0 until frameCount) {
            val off = f * FEAT_DIM
            var best = 0; var bestDist = Double.POSITIVE_INFINITY
            for (ifr in 0 until index.frameCount) {
                val ioff = ifr * FEAT_DIM; var dist = 0.0
                for (k in 0 until FEAT_DIM) { val d = phone[off + k] - index.features[ioff + k]; dist += d * d }
                if (dist < bestDist) { bestDist = dist; best = ifr }
            }
            val ioff = best * FEAT_DIM
            for (k in 0 until FEAT_DIM) fused[off + k] = phone[off + k] * (1f - r) + index.features[ioff + k] * r
        }
        return fused
    }

    private fun applyProtect(phone: FloatArray, original: FloatArray, pitchf: FloatArray, rate: Double): FloatArray {
        val r = rate.coerceIn(0.0, 1.0).toFloat()
        if (r == 0f) return phone
        val out = FloatArray(phone.size)
        for (f in pitchf.indices) {
            val uv = if (pitchf[f] <= 0f) r else 0f
            val off = f * FEAT_DIM
            for (k in 0 until FEAT_DIM) out[off + k] = phone[off + k] * (1f - uv) + original[off + k] * uv
        }
        return out
    }

    private fun melToCoarse(f: Float): Long {
        if (f <= 0f) return 1L
        val mel = 1127.0 * Math.log(f / 700.0 + 1.0)
        val fmn = 1127.0 * Math.log(50.0 / 700.0 + 1.0)
        val fmx = 1127.0 * Math.log(1100.0 / 700.0 + 1.0)
        return ((mel - fmn) * 254.0 / (fmx - fmn) + 1.0).toLong().coerceIn(1L, 255L)
    }

    private fun coarsePitch(f0: Float): Long = melToCoarse(f0)

    // ──────────────────────────────────────────────
    // 后端检测
    // ──────────────────────────────────────────────
    private fun detectActualBackend(modelDir: File): String {
        val available = OrtEnvironment.getAvailableProviders().map { it.name }.toSet()
        Log.e("RVC", "Available providers: $available")
        val result = when (backendMode) {
            0 -> "CPU"
            1 -> if (available.any { "xnnpack" in it.lowercase() }) "XNNPACK" else "CPU"
            else -> {
                when {
                    available.any { "nnapi" in it.lowercase() } -> "NNAPI (NPU)"
                    available.any { "xnnpack" in it.lowercase() } -> "XNNPACK"
                    else -> "CPU"
                }
            }
        }
        Log.e("RVC", "Actual backend: $result")
        return result
    }
}
