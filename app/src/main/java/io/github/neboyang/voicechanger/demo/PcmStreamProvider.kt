package io.github.neboyang.voicechanger.demo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mo.glassmic.GlamicBridge

class PcmStreamProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "只支持只读" }
        val sampleRate = uri.getQueryParameter("sr")?.toIntOrNull() ?: 48000
        val channels = uri.getQueryParameter("ch")?.toIntOrNull() ?: 1
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        GlamicBridge.attachConsumer(
            sampleRate = sampleRate,
            channels = channels,
            writeFd = writeSide
        )
        return readSide
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
