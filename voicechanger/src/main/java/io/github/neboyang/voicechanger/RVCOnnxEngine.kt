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

    /** 降噪级别 0~5（0=关） */
    var noiseLevel: Int = 0

    /** 外放补偿 EQ 级别 0~5（0=关，5=最强补偿） */
    var eqLevel: Int = 0

    /** 当前推理后端 */
    var backendInfo: String = "未知"
        private set

    fun load(modelDir: File): Boolean {
        try {
            val cfgFile = File(modelDir, "config.json")
            if (cfgFile.exists()) {
                val json = org.json.JSONObject(cfgFile.readText())
                targetSr = json.optInt("target_sr", 40000)
            }
            // Provider priority: NNAPI > Xnnpack > CPU
            val available = OrtEnvironment.getAvailableProviders().map { it.name }
            Log.e("RVC", "Available providers: $available")
            backendInfo = when {
                "NnapiExecutionProvider" in available -> "NNAPI (NPU/GPU)"
                "XnnpackExecutionProvider" in available -> "XNNPACK (CPU 加速)"
                else -> "CPU"
            }
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

            // 外放补偿 EQ（扬声器→空气→微信麦克风 频响补偿）
            if (eqLevel > 0) {
                val intensity = eqLevel * 0.15f // 0.15 ~ 0.75
                // 高通预加重：补偿高频衰减 + 减少低频浑浊
                for (i in result.size - 1 downTo 1) {
                    result[i] = result[i] - intensity * result[i - 1]
                }
                // 限制防止爆音
                var peak = 0f
                for (i in result.indices) { val a = kotlin.math.abs(result[i]); if (a > peak) peak = a }
                if (peak > 0.95f) {
                    val scale = 0.95f / peak
                    for (i in result.indices) result[i] *= scale
                }
            }

            return result
        } catch (e: Exception) {
            Log.e("RVC", "Infer failed", e)
            return null
        }
    }
}
