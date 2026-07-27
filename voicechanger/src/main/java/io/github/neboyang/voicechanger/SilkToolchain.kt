package io.github.neboyang.voicechanger

import android.content.Context
import java.io.File

object SilkToolchain {
    private const val TOOL_DIR = "/data/local/tool"
    private var ready = false

    fun isReady() = ready

    fun install(context: Context): Boolean {
        if (ready) return true
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p $TOOL_DIR && chmod 755 $TOOL_DIR")).waitFor()
            val encBytes = context.assets.open("silkenc").readBytes()
            val libBytes = context.assets.open("libsilk.so").readBytes()
            val tmpEnc = File(context.cacheDir, "se_tmp")
            val tmpLib = File(context.cacheDir, "sl_tmp.so")
            tmpEnc.writeBytes(encBytes); tmpLib.writeBytes(libBytes)
            val cmd = "cp ${tmpEnc.absolutePath} $TOOL_DIR/silkenc && cp ${tmpLib.absolutePath} $TOOL_DIR/libsilk.so && chmod 755 $TOOL_DIR/silkenc $TOOL_DIR/libsilk.so && chown shell:shell $TOOL_DIR/silkenc $TOOL_DIR/libsilk.so"
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
            tmpEnc.delete(); tmpLib.delete()
            ready = true; return true
        } catch (_: Exception) { return false }
    }

    fun convertWavToSlk(wavPath: String): String? {
        if (!ready) return null
        val slkPath = wavPath.replace(".wav", ".slk")
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "LD_LIBRARY_PATH=$TOOL_DIR $TOOL_DIR/silkenc $wavPath $slkPath"))
            p.waitFor(); return if (p.exitValue() == 0) slkPath else null
        } catch (_: Exception) { return null }
    }
}
