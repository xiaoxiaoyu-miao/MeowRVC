package io.github.neboyang.voicechanger

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import io.mo.glassmic.RvcActive

class RuntimeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val pkg = selectionArgs?.firstOrNull() ?: ""
        val source = if (RvcActive.get()) "FILE" else "REAL_MIC"
        return MatrixCursor(arrayOf("source", "group_id", "audio_id", "global_switch"))
            .apply { addRow(arrayOf(source, "", "", 1)) }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/runtime"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null
}
