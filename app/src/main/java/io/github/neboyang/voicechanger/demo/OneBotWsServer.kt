package io.github.neboyang.voicechanger.demo

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * OneBot v11 ReverseWebSocket 服务器
 *
 * Lagrange.OneBot 配置为 ReverseWebSocket 时会作为客户端连接到这里，
 * App 在此监听接收事件，并通过同一连接调用 send_group_msg 等 API。
 */
class OneBotWsServer(
    port: Int = 2536,
    private val token: String? = null,
    private val onStatus: ((String) -> Unit)? = null,
    private val onVoiceMessage: ((JSONObject, Long) -> Unit)? = null,
) : WebSocketServer(InetSocketAddress(port)) {

    private val connected = AtomicInteger(0)
    private val echoId = AtomicInteger(0)

    override fun onStart() {
        Log.i("OneBotWS", "OneBot WS 服务器已启动: port=${address.port}")
        onStatus?.invoke("WS 服务器已启动 (${address.port})")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        // 校验 AccessToken（可选）
        val clientToken = handshake.getFieldValue("Authorization")
            .removePrefix("Bearer ").trim()
        if (token != null && token.isNotBlank() && clientToken != token) {
            Log.w("OneBotWS", "Token 校验失败，拒绝连接")
            conn.close(4001, "invalid token")
            return
        }
        connected.incrementAndGet()
        Log.i("OneBotWS", "Lagrange 已连接: ${conn.remoteSocketAddress}")
        onStatus?.invoke("Lagrange 已连接")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            val postType = json.optString("post_type")
            if (postType == "message") {
                handleMessageEvent(conn, json)
            } else if (postType == "meta_event") {
                // 心跳/生命周期事件，忽略
            }
        } catch (e: Exception) {
            Log.e("OneBotWS", "解析消息失败: ${e.message}")
        }
    }

    private fun handleMessageEvent(conn: WebSocket, event: JSONObject) {
        val msg = event.optJSONArray("message") ?: return
        val raw = event.optString("raw_message")
        val groupId = event.optLong("group_id", -1)
        val userId = event.optLong("user_id", -1)

        Log.i("OneBotWS", "收到消息: group=$groupId user=$userId raw=$raw")

        // #变声 命令：发送最新本地变声文件
        if (raw.trim().startsWith("#变声")) {
            val dir = java.io.File("/sdcard/rvc")
            val latest = dir.listFiles { f -> f.name.endsWith(".wav") }?.maxByOrNull { it.lastModified() }
            if (latest != null) {
                sendGroupRecord(conn, groupId, latest.absolutePath, "✨ 最新变声文件: ${latest.name}")
            } else {
                sendReply(conn, event, "本地没有变声文件")
            }
            return
        }

        // 找到语音 record 段
        for (i in 0 until msg.length()) {
            val seg = msg.getJSONObject(i)
            if (seg.optString("type") == "record") {
                val data = seg.optJSONObject("data") ?: continue
                val file = data.optString("file")
                val url = data.optString("url")
                onVoiceMessage?.invoke(
                    JSONObject().put("file", file).put("url", url),
                    if (groupId > 0) groupId else -userId
                )
                return
            }
        }
    }

    /** 发送文本回复 */
    private fun sendReply(conn: WebSocket, event: JSONObject, text: String) {
        val msgType = event.optString("message_type")
        val params = JSONObject().apply {
            put("message", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("data", JSONObject().apply { put("text", text) })
            }))
            put("message_type", msgType)
            if (msgType == "group") put("group_id", event.optLong("group_id", -1))
            else put("user_id", event.optLong("user_id", -1))
        }
        callApi(conn, "send_msg", params)
    }

    /** 发送语音到群/好友（target: 群号>0 或 -好友号） */
    fun sendGroupRecord(conn: WebSocket, target: Long, filePath: String, text: String? = null) {
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
        val params = JSONObject().apply {
            put("message", arr)
            put("message_type", if (target > 0) "group" else "private")
            if (target > 0) put("group_id", target)
            else put("user_id", -target)
        }
        val action = if (target > 0) "send_group_msg" else "send_private_msg"
        callApi(conn, action, params)
    }

    /** 通过 WS 调用 OneBot API（带 echo，异步发送） */
    private fun callApi(conn: WebSocket, action: String, params: JSONObject) {
        val req = JSONObject().apply {
            put("action", action)
            put("params", params)
            put("echo", echoId.incrementAndGet().toString())
        }
        conn.send(req.toString())
        Log.i("OneBotWS", "调用 API: $action echo=${req.optString("echo")}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        connected.decrementAndGet()
        Log.i("OneBotWS", "Lagrange 断开: code=$code reason=$reason")
        onStatus?.invoke("Lagrange 已断开")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("OneBotWS", "WS 错误: ${ex.message}")
    }

    fun hasConnection(): Boolean = connected.get() > 0

    fun getActiveConnections(): List<WebSocket> = connections.filter { it.isOpen }
}
