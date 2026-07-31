package io.github.neboyang.voicechanger.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class OneBotClient(
    private val baseUrl: String,
    private val token: String? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun headers() = Request.Builder().apply {
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
    }

    /** 发送语音到群 */
    suspend fun sendGroupRecord(groupId: Long, filePath: String, text: String? = null): Boolean {
        return callApi(
            "send_group_msg",
            JSONObject().apply {
                put("group_id", groupId)
                put("message", buildMessage(filePath, text))
            }
        )
    }

    /** 发送语音给好友 */
    suspend fun sendPrivateRecord(userId: Long, filePath: String, text: String? = null): Boolean {
        return callApi(
            "send_private_msg",
            JSONObject().apply {
                put("user_id", userId)
                put("message", buildMessage(filePath, text))
            }
        )
    }

    private fun buildMessage(filePath: String, text: String?): JSONArray {
        val arr = JSONArray()
        if (!text.isNullOrBlank()) {
            arr.put(JSONObject().apply {
                put("type", "text")
                put("data", JSONObject().apply { put("text", text) })
            })
        }
        arr.put(JSONObject().apply {
            put("type", "record")
            put("data", JSONObject().apply { put("file", filePath) })
        })
        return arr
    }

    private suspend fun callApi(action: String, body: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.trimEnd('/') + "/" + action
            val req = headers()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext false
                val json = try { JSONObject(text) } catch (_: Exception) { null }
                if (json == null) return@withContext resp.isSuccessful
                // OneBot v11 成功返回 {"status":"ok","retcode":0}
                val statusOk = json.optString("status") == "ok"
                val retOk = json.optInt("retcode", -1) == 0
                return@withContext statusOk || retOk
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** 将音频拷贝到 QQ 可读取的共享路径（NapCat 与 App 同设备，用 /sdcard 路径即可） */
        fun toSdcardPath(file: File): String = file.absolutePath
    }
}
