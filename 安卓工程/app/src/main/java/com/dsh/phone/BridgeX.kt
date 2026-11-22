package com.dsh.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 收件箱里的一条（播报 / 提问 / 排队的回复） */
data class InboxItem(
    val type: String,
    val reply: String,
    val question: String,
    val options: List<String>,
    val askId: String,
    val sessionId: String,
    val parts: List<Part>,
)

data class InboxPage(val seq: Long, val running: Boolean, val queued: Int, val items: List<InboxItem>)

/**
 * 补充接口。Bridge.kt 里只有 chat / upload / ping，这里补：
 *   /say     投递（不阻塞）
 *   /inbox   收件箱
 *   /stop    打断
 *   /answer  回答问题
 * 独立成文件，避免再去动那个已经很长的大文件。
 */
object BridgeX {

    private fun open(prefs: Prefs, path: String, method: String, timeout: Int = 30_000): HttpURLConnection {
        val url = URL(prefs.server.trimEnd('/') + path)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = timeout
            setRequestProperty("Authorization", "Bearer " + prefs.token)
        }
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("服务返回 $code")
        return raw
    }

    suspend fun stop(prefs: Prefs): Boolean = withContext(Dispatchers.IO) {
        val c = open(prefs, "/stop", "POST")
        try {
            c.doOutput = true
            c.outputStream.use { it.write("{}".toByteArray()) }
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }

    suspend fun answer(prefs: Prefs, id: String, answer: String): Boolean = withContext(Dispatchers.IO) {
        val c = open(prefs, "/answer", "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(JSONObject().put("id", id).put("answer", answer).toString().toByteArray(Charsets.UTF_8)) }
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }

    suspend fun inbox(prefs: Prefs, since: Long): InboxPage = withContext(Dispatchers.IO) {
        val c = open(prefs, "/inbox?since=$since", "GET", 15_000)
        try {
            val json = JSONObject(read(c))
            val arr = json.optJSONArray("items")
            val items = mutableListOf<InboxItem>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val opts = mutableListOf<String>()
                    o.optJSONArray("options")?.let { oa -> for (k in 0 until oa.length()) opts.add(oa.optString(k)) }
                    val parts = mutableListOf<Part>()
                    o.optJSONArray("parts")?.let { pa ->
                        for (k in 0 until pa.length()) {
                            val p = pa.optJSONObject(k) ?: continue
                            parts.add(Part(p.optString("kind"), p.optString("name"), p.optString("b64"), p.optLong("size", 0), p.optString("url")))
                        }
                    }
                    items.add(
                        InboxItem(
                            type = o.optString("type", "reply"),
                            reply = o.optString("reply"),
                            question = o.optString("question"),
                            options = opts,
                            askId = o.optString("id"),
                            sessionId = o.optString("sessionId"),
                            parts = parts,
                        )
                    )
                }
            }
            InboxPage(json.optLong("seq", since), json.optBoolean("running", false), json.optInt("queued", 0), items)
        } finally {
            c.disconnect()
        }
    }
}