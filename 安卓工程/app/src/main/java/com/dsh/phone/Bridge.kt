package com.dsh.phone

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** 桥接返回的一个附件。b64 为空表示只是个文件气泡，没有内容可显示。 */
data class Part(val kind: String, val name: String, val b64: String, val size: Long = 0, val url: String = "")

/** 一轮的完整结果：正文 + 附件。 */
data class ChatResult(val reply: String, val sessionId: String, val parts: List<Part>)

/** 本地保存的设置：桥接地址、口令、会话 id、昵称、头像、聊天历史。 */
class Prefs(context: Context) {
    val sp = context.getSharedPreferences("dsh", Context.MODE_PRIVATE)
    val dir: File = context.filesDir

    var server: String
        get() = sp.getString("server", "http://192.168.1.100:19401") ?: ""
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var sessionId: String
        get() = sp.getString("sessionId", "") ?: ""
        set(v) = sp.edit().putString("sessionId", v).apply()

    /** 我的 ID / 昵称 —— 她也知道这个名字。 */
    var myName: String
        get() = sp.getString("myName", "我") ?: "我"
        set(v) = sp.edit().putString("myName", v.trim().ifBlank { "我" }).apply()

    var herName: String
        get() = sp.getString("herName", "小鱼") ?: "小鱼"
        set(v) = sp.edit().putString("herName", v.trim().ifBlank { "小鱼" }).apply()

    fun resetSession() {
        sp.edit().remove("sessionId").apply()
    }

    fun configured(): Boolean = server.isNotBlank() && token.isNotBlank()

    // ── 头像：用户选的图复制到 filesDir ──
    fun avatarFile(who: String): File = File(dir, "avatar_$who.jpg")

    fun saveAvatar(context: Context, who: String, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            avatarFile(who).outputStream().use { out -> input.copyTo(out) }
        }
        true
    } catch (e: Exception) {
        false
    }

    // ── 历史：最近 300 条 ──
    fun loadHistory(): List<Msg> {
        val raw = sp.getString("history", "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Msg(
                    fromMe = o.optBoolean("me"),
                    text = o.optString("text"),
                    at = o.optLong("at"),
                    kind = runCatching { MKind.valueOf(o.optString("kind", "TEXT")) }.getOrDefault(MKind.TEXT),
                    payload = o.optString("payload"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveHistory(list: List<Msg>) {
        val arr = org.json.JSONArray()
        list.takeLast(300).forEach {
            arr.put(JSONObject().apply {
                put("me", it.fromMe)
                put("text", it.text)
                put("at", it.at)
                put("kind", it.kind.name)
                put("payload", it.payload)
            })
        }
        sp.edit().putString("history", arr.toString()).apply()
    }
}

object Bridge {

    private fun conn(prefs: Prefs, path: String, method: String, timeout: Int): HttpURLConnection {
        val url = URL(prefs.server.trimEnd('/') + path)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = timeout
            setRequestProperty("Authorization", "Bearer " + prefs.token)
        }
    }

    /** 发一句话，返回 (回复, 会话 id)。 */
    suspend fun chat(prefs: Prefs, text: String): ChatResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("text", text)
            put("who", prefs.myName)
            if (prefs.sessionId.isNotBlank()) put("sessionId", prefs.sessionId)
        }.toString()

        val c = conn(prefs, "/chat", "POST", 320_000).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                throw IOException("服务返回 $code" + if (msg.isNotBlank()) "：$msg" else "")
            }
            val json = JSONObject(raw)
            val reply = json.optString("reply")
            val sid = json.optString("sessionId")
            val parts = mutableListOf<Part>()
            json.optJSONArray("parts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    parts.add(
                        Part(
                            kind = o.optString("kind"),
                            name = o.optString("name"),
                            b64 = o.optString("b64"),
                            size = o.optLong("size", 0),
                            url = o.optString("url"),
                        )
                    )
                }
            }
            if (reply.isBlank() && parts.isEmpty()) throw IOException("服务没有返回内容")
            ChatResult(reply, sid, parts)
        } finally {
            c.disconnect()
        }
    }

    /** 传一个文件上去，返回它在电脑上的路径。 */
    suspend fun upload(prefs: Prefs, name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val body = JSONObject().apply {
            put("name", name)
            put("data", b64)
        }.toString()

        val c = conn(prefs, "/upload", "POST", 180_000).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 显式给出长度（而不是 chunked）：Cloudflare 隧道对 chunked POST 更挑剔
            try {
                setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
            } catch (_: Exception) {}
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                if (code == 502 || code == 503 || code == 504) {
                    throw IOException("上传失败 $code（隧道/网关拦截，请求没到电脑。换个网络或改用局域网地址再试）")
                }
                val msg = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                val snippet = raw.take(120).replace("\n", " ")
                throw IOException("上传失败 $code" + (if (msg.isNotBlank()) "：$msg" else "：$snippet"))
            }
            val path = JSONObject(raw).optString("path")
            if (path.isBlank()) throw IOException("服务没给路径")
            path
        } finally {
            c.disconnect()
        }
    }

    suspend fun ping(prefs: Prefs): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = conn(prefs, "/ping", "GET", 6_000)
            try {
                c.responseCode in 200..299
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
/** 聊天背景：图片或视频，存在 App 私有目录。 */
enum class BgKind { NONE, IMAGE, VIDEO }

fun Prefs.bgKind(): BgKind = when (sp.getString("bgKind", "NONE")) {
    "IMAGE" -> BgKind.IMAGE
    "VIDEO" -> BgKind.VIDEO
    else -> BgKind.NONE
}

fun Prefs.bgImageFile(): File = File(dir, "bg_image.jpg")
fun Prefs.bgVideoFile(): File = File(dir, "bg_video.mp4")

fun Prefs.setBgImage(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        bgImageFile().outputStream().use { out -> input.copyTo(out) }
    }
    bgVideoFile().delete()
    sp.edit().putString("bgKind", "IMAGE").apply()
    true
} catch (e: Exception) {
    false
}

fun Prefs.setBgVideo(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        bgVideoFile().outputStream().use { out -> input.copyTo(out) }
    }
    bgImageFile().delete()
    sp.edit().putString("bgKind", "VIDEO").apply()
    true
} catch (e: Exception) {
    false
}

fun Prefs.clearBg() {
    bgImageFile().delete()
    bgVideoFile().delete()
    sp.edit().putString("bgKind", "NONE").apply()
}
/** 背景填充方式：铺满裁切 / 完整显示。 */
enum class BgFill { COVER, CONTAIN }

fun Prefs.bgFill(): BgFill =
    if (sp.getString("bgFill", "COVER") == "CONTAIN") BgFill.CONTAIN else BgFill.COVER

fun Prefs.setBgFill(v: BgFill) = sp.edit().putString("bgFill", v.name).apply()

/** 横屏素材要不要转 90° 再铺（有些横拍视频竖屏看特别怪）。 */
fun Prefs.bgRotate(): Boolean = sp.getBoolean("bgRotate", false)
fun Prefs.setBgRotate(v: Boolean) = sp.edit().putBoolean("bgRotate", v).apply()

/** 背景显现程度 0~1（越大背景越清楚，压暗层越淡）。 */
fun Prefs.bgReveal(): Float = sp.getFloat("bgReveal", 0.55f)
fun Prefs.setBgReveal(v: Float) = sp.edit().putFloat("bgReveal", v.coerceIn(0f, 1f)).apply()
/** 背景左右位置：-1 贴左、0 居中、+1 贴右（只在铺满、画面横向溢出时有效）。 */
fun Prefs.bgPan(): Float = sp.getFloat("bgPan", 0f)
fun Prefs.setBgPan(v: Float) = sp.edit().putFloat("bgPan", v.coerceIn(-1f, 1f)).apply()
/** 缩放微调：1.0 = 按铺满/原样算出来的标准倍率，<1 收小（开始留边），>1 再放大。 */
fun Prefs.bgZoom(): Float = sp.getFloat("bgZoom", 1f)
fun Prefs.setBgZoom(v: Float) = sp.edit().putFloat("bgZoom", v.coerceIn(0.5f, 1.6f)).apply()
/** 竖直拉伸铺满：宽度照旧，只把高度撑满屏幕（会有轻微纵向变形，ta 要这个效果）。 */
fun Prefs.bgStretch(): Boolean = sp.getBoolean("bgStretch", false)
fun Prefs.setBgStretch(v: Boolean) = sp.edit().putBoolean("bgStretch", v).apply()