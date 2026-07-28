package io.github.neboyang.voicechanger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 简化版：直接调用 FloatMicService 的录音→处理→保存流程 */
class RVCRealtime {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    val engine = RVCOnnxEngine()
    var f0UpKey: Int = 0
    var latencyMs: Int = 1000
    var onError: ((Throwable) -> Unit)? = null

    fun loadModel(modelDir: File): Boolean {
        val ok = engine.load(modelDir)
        if (ok) engine.startServer()
        return ok
    }

    fun start() {
        if (!engine.isLoaded()) { onError?.invoke(java.lang.IllegalStateException("Model not loaded")); return }
        _isRunning.value = true
    }

    fun stop() { _isRunning.value = false }

    fun release() { stop(); engine.unload() }
}
