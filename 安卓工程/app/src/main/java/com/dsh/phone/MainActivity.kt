package com.dsh.phone

import android.content.Context
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlin.random.Random

// ── Discord 深色配色（面板带透明度，壁纸能透出来） ──
private val BgDeep = Color(0xF21E1F22)      // 最深：标题栏、底栏
private val BgPanel = Color(0xEB2B2D31)     // 面板：她的气泡、设置
private val BgField = Color(0xFF383A40)     // 输入框
private val Blurple = Color(0xFF5865F2)     // Discord 主色
private val BlurpleSoft = Color(0xE64752C4)
private val TextNorm = Color(0xFFDBDEE1)
private val TextMuted = Color(0xFF949BA4)
private val TextDim = Color(0xFF87898C)
private val Green = Color(0xFF23A55A)
private val Red = Color(0xFFF23F43)
private val Yellow = Color(0xFFF0B232)
private val Line = Color(0x14FFFFFF)
private val DIALOG_BG = Color(0xFF2B2D31)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent { DshTheme { App(prefs) } }
    }
}

@Composable
private fun DshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blurple,
            background = Color.Transparent,
            surface = BgPanel,
            onPrimary = Color.White,
            onBackground = TextNorm,
            onSurface = TextNorm,
        ),
        content = content,
    )
}

private data class Sticker(val file: String, val summary: String)

private fun loadStickers(ctx: Context): List<Sticker> = try {
    val raw = ctx.assets.open("stickers/index.json").bufferedReader().use { it.readText() }
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Sticker("stickers/" + o.optString("file"), o.optString("summary"))
    }
} catch (e: Exception) {
    emptyList()
}

/** 把一大段回复拆成几条短消息 —— 手机上一句一句蹦出来，才像在打字。 */
private fun splitReply(text: String): List<String> {
    val raw = text.replace("\r", "").trim()
    if (raw.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    for (line in raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }) {
        var rest = line
        while (rest.length > 72) {
            val marks = listOf('。', '！', '？', '；', '…', '，')
            val cut = marks.map { rest.indexOf(it) }.filter { it in 18..72 }.maxOrNull() ?: -1
            if (cut <= 0) break
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<Msg>() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var bannerOk by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }
    var avatarVersion by remember { mutableStateOf(0) }
    var bgVersion by remember { mutableStateOf(0) }
    var pan by remember { mutableStateOf(prefs.bgPan()) }
    var stretch by remember { mutableStateOf(prefs.bgStretch()) }
    var panMode by remember { mutableStateOf(false) }
    val stickers = remember { loadStickers(ctx) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { messages.addAll(prefs.loadHistory()) }
    LaunchedEffect(messages.size, avatarVersion) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        prefs.saveHistory(messages)
    }

    fun note(msg: String, ok: Boolean = false) {
        banner = msg
        bannerOk = ok
    }

    // ── 新消息层：ChatEngine 负责收发，界面只显示 ──
    val engine = remember { ChatEngine(scope, prefs) }
    var busy by remember { mutableStateOf(false) }

    val listener = object : ChatEngine.Listener {
        override fun onReply(reply: String, parts: List<Part>, sessionId: String) {
            scope.launch {
                for ((i, line) in splitReply(reply).withIndex()) {
                    if (i > 0) delay(320L + Random.nextLong(0, 380))
                    messages.add(Msg(false, line))
                }
                for (part in parts) {
                    val local: String? = withContext(Dispatchers.IO) { savePart(prefs, part) }
                    delay(260L)
                    when (part.kind) {
                        "sticker" -> messages.add(Msg(false, part.name, kind = MKind.STICKER, payload = local ?: ""))
                        "image" -> messages.add(Msg(false, part.name, kind = MKind.IMAGE, payload = local ?: ""))
                        else -> {
                            val dl = if (part.url.isNotBlank()) prefs.server.trimEnd(0x27.toChar()) + part.url else ""
                            val label = part.name + if (part.size > 0) "  (" + humanSize(part.size) + ")" else ""
                            messages.add(Msg(false, label, kind = MKind.FILE, payload = dl))
                        }
                    }
                }
            }
        }
        override fun onSay(text: String) { messages.add(Msg(false, text)) }
        override fun onAsk(id: String, question: String, options: List<String>) {
            val opts = if (options.isEmpty()) "" else "（选一个：" + options.joinToString(" / ") + "）"
            messages.add(Msg(false, "❓ " + question + " " + opts))
        }
        override fun onStatus(msgIndex: Int, status: String) {
            if (msgIndex in messages.indices) messages[msgIndex] = messages[msgIndex].copy(status = status)
        }
        override fun onError(msg: String) { note(msg) }
        override fun onBusy(b: Boolean) { busy = b }
    }

    fun ask(prompt: String) {
        val idx = messages.size
        messages.add(Msg(true, prompt, status = "未读"))
        engine.send(prompt, idx, listener)
    }

    fun sendText() {
        val t = input.trim()
        if (t.isEmpty()) return
        input = ""
        // ask() 内部会把自己这条消息加进列表并标记「未读」
        ask(t)
    }

    fun sendAttachment(uri: Uri, asImage: Boolean) {
        if (sending || uploading) return
        scope.launch {
            uploading = true
            banner = null
            try {
                val (bytes, name) = withContext(Dispatchers.IO) { readUri(ctx, uri) }
                if (bytes == null) {
                    note("读不到这个文件")
                    return@launch
                }
                val local = withContext(Dispatchers.IO) {
                    val f = File(prefs.dir, "send_" + System.currentTimeMillis() + "_" + name)
                    f.outputStream().use { it.write(bytes) }
                    f.absolutePath
                }
                messages.add(
                    Msg(
                        fromMe = true,
                        text = if (asImage) "" else name,
                        kind = if (asImage) MKind.IMAGE else MKind.FILE,
                        payload = local,
                    )
                )
                val remote = Bridge.upload(prefs, name, bytes)
                val prompt = if (asImage) {
                    "[ta（${prefs.myName}）用手机发来一张图片，文件在电脑上：$remote 。想看图就用 read 打开它。]"
                } else {
                    "[ta（${prefs.myName}）用手机发来一个文件：$name ，存在电脑上：$remote 。需要就看，看完说一句。]"
                }
                val ix = messages.size
                messages.add(Msg(true, "[附件] " + name, status = "未读"))
                engine.send(prompt, ix, listener)
            } catch (e: Exception) {
                note(e.message ?: "传不上去")
            } finally {
                uploading = false
            }
        }
    }

    fun sendSticker(s: Sticker) {
        if (sending || uploading) return
        scope.launch {
            uploading = true
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.assets.open(s.file).use { it.readBytes() } }
                messages.add(Msg(true, s.summary, kind = MKind.STICKER, payload = s.file))
                val remote = Bridge.upload(prefs, "sticker.png", bytes)
                val ix2 = messages.size
                engine.send("[ta（${prefs.myName}）发来一张表情包「${s.summary}」，文件在电脑上：$remote 。]", ix2, listener)
            } catch (e: Exception) {
                note(e.message ?: "表情包发不出去")
            } finally {
                uploading = false
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendAttachment(uri, true)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendAttachment(uri, false)
    }
    val pickMyAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && prefs.saveAvatar(ctx, "me", uri)) avatarVersion++
    }
    val pickHerAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && prefs.saveAvatar(ctx, "her", uri)) avatarVersion++
    }
    val pickBgImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            if (prefs.setBgImage(ctx, uri)) {
                bgVersion++
                note("背景换好了", true)
            } else note("这张图用不了")
        }
    }
    val pickBgVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (prefs.setBgVideo(ctx, uri)) {
                bgVersion++
                note("视频背景设好了", true)
            } else note("这个视频用不了")
        }
    }

    Box(Modifier.fillMaxSize()) {
        ChatBackground(prefs, bgVersion, pan)

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Header(
                    herName = prefs.herName,
                    herAvatar = prefs.avatarFile("her").takeIf { it.exists() },
                    busy = sending || uploading,
                    onAvatar = {
                        pickHerAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onNewSession = {
                        prefs.resetSession()
                        messages.clear()
                        note("开新会话了", true)
                    },
                    onSettings = { showSettings = true },
                )
            },
            bottomBar = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC0B1220))
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Line)
                    )
                    banner?.let { b ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                b,
                                color = if (bannerOk) Green else Red,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { banner = null }) {
                                Text("知道了", color = Blurple, fontSize = 12.sp)
                            }
                        }
                    }
                    InputBar(
                        value = input,
                        onValueChange = { if (it.length <= 4000) input = it },
                        sending = sending || uploading,
                        onSend = { sendText() },
                        onSticker = { showStickers = true },
                        onImage = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onFile = { pickFile.launch(arrayOf("*/*")) },
                    )
                }
            },
        ) { pad ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
            ) {
                if (messages.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(prefs.avatarFile("her").takeIf { it.exists() }, 92)
                        Spacer(Modifier.height(16.dp))
                        Text(prefs.herName, color = TextNorm, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("说点什么吧，她记得你", color = TextMuted, fontSize = 13.sp)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    items(messages) { m -> MessageRow(m, prefs) }
                    if (sending || uploading) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(13.dp), color = Blurple, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(if (uploading) "正在传…" else "正在想…", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (panMode) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x40000000))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dx ->
                        pan = (pan + dx / 700f).coerceIn(-1f, 1f)
                        prefs.setBgPan(pan)
                    }
                }
        ) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xF21E1F22))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("左右拖动调背景", color = TextNorm, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { panMode = false }) {
                    Text("完成", color = Blurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showStickers) {
        ModalBottomSheet(
            onDismissRequest = { showStickers = false },
            sheetState = sheetState,
            containerColor = Color(0xFF2B2D31),
        ) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text("表情包", color = TextNorm, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(stickers) { s ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF313338))
                                .clickable {
                                    showStickers = false
                                    sendSticker(s)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            AssetImage(s.file, Modifier.fillMaxSize().padding(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            prefs = prefs,
            onClose = { showSettings = false },
            onSaved = { showSettings = false },
            onPickMyAvatar = {
                pickMyAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickHerAvatar = {
                pickHerAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickBgImage = {
                pickBgImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickBgVideo = { pickBgVideo.launch(arrayOf("video/*")) },
            onClearBg = {
                prefs.clearBg()
                bgVersion++
                note("背景清掉了", true)
            },
            onBgChanged = { bgVersion++ },
            pan = pan,
            onPanChange = {
                pan = it
                prefs.setBgPan(it)
            },
            onEnterPanMode = {
                showSettings = false
                panMode = true
            },


        )
    }
}

@Composable
private fun Header(
    herName: String,
    herAvatar: File?,
    busy: Boolean,
    onAvatar: () -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xE60A101C), Color(0x990A101C), Color(0x000A101C))
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(CircleShape).clickable { onAvatar() }) { Avatar(herAvatar, 38) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(herName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (busy) Yellow else Green)
                )
                Spacer(Modifier.width(5.dp))
                Text(if (busy) "正在想…" else "在线", color = TextMuted, fontSize = 11.sp)
            }
        }
        IconButton(onClick = onNewSession) { Icon(Icons.Filled.Add, "新会话", tint = TextMuted) }
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "设置", tint = TextMuted) }
    }
}

@Composable
private fun MessageRow(m: Msg, prefs: Prefs) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!m.fromMe) {
            Avatar(prefs.avatarFile("her").takeIf { it.exists() }, 32)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start) {
            when (m.kind) {
                MKind.STICKER ->
                    if (m.payload.startsWith("stickers/")) AssetImage(m.payload, Modifier.size(118.dp))
                    else LocalImage(m.payload, Modifier.size(118.dp))
                MKind.IMAGE -> LocalImage(m.payload, Modifier.widthIn(max = 210.dp))
                else -> TextBubble(m)
            }
            if (m.kind == MKind.FILE) {
                Spacer(Modifier.height(4.dp))
                FileBubble(m)
            }
            if (m.fromMe && m.status.isNotEmpty()) {
                Text(
                    m.status,
                    color = if (m.status == "已读") Color(0xFF23A55A) else TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                )
            }
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.at)),
                color = TextDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
            )
        }
        if (m.fromMe) {
            Spacer(Modifier.width(8.dp))
            Avatar(prefs.avatarFile("me").takeIf { it.exists() }, 32)
        }
    }
}

@Composable
private fun TextBubble(m: Msg) {
    val clip = LocalClipboardManager.current
    val shape = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = if (m.fromMe) 14.dp else 4.dp,
        bottomEnd = if (m.fromMe) 4.dp else 14.dp,
    )
    Text(
        text = m.text,
        color = if (m.fromMe) Color.White else TextNorm,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        modifier = Modifier
            .widthIn(max = 285.dp)
            .clip(shape)
            .background(if (m.fromMe) BlurpleSoft else BgPanel)
            .clickable { clip.setText(AnnotatedString(m.text)) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun FileBubble(m: Msg) {
    val ctx = LocalContext.current
    val url = m.payload
    Row(
        Modifier
            .widthIn(max = 290.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgPanel)
            .clickable(enabled = url.isNotBlank()) {
                if (url.isBlank()) return@clickable
                try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(ctx, "没有能打开链接的应用", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📎", fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(m.text.ifBlank { "文件" }, color = TextNorm, fontSize = 13.sp)
            Text(
                if (url.isBlank()) "（没有下载链接）" else "点击下载到手机",
                color = if (url.isBlank()) Red else Blurple,
                fontSize = 11.sp,
            )
        }
    }
}

private fun humanSize(n: Long): String = when {
    n >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
    n >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", n / 1024.0)
    else -> n.toString() + " B"
}

@Composable
private fun Avatar(file: File?, size: Int) {
    val dp = size.dp
    val ctx = LocalContext.current
    if (file != null && file.exists()) {
        val bmp by produceState<android.graphics.Bitmap?>(null, file.absolutePath) {
            value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
        }
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(dp).clip(CircleShape),
            )
        } ?: DefaultAvatar(dp, ctx)
    } else {
        DefaultAvatar(dp, ctx)
    }
}

@Composable
private fun DefaultAvatar(dp: androidx.compose.ui.unit.Dp, ctx: Context) {
    val bmp by produceState<android.graphics.Bitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open("stickers/s00.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(dp).clip(CircleShape),
        )
    } ?: Box(
        Modifier
            .size(dp)
            .clip(CircleShape)
            .background(BgPanel)
    )
}

@Composable
private fun AssetImage(path: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    bmp?.let {
        Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = modifier)
    }
}

@Composable
private fun LocalImage(path: String, modifier: Modifier = Modifier) {
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    bmp?.let {
        Image(
            it.asImageBitmap(),
            null,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(10.dp)),
        )
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onSticker: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        focusedContainerColor = BgField,
        unfocusedContainerColor = BgField,
        cursorColor = TextNorm,
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onSticker, enabled = !sending) {
                Icon(Icons.Outlined.Face, "表情包", tint = TextMuted)
            }
            IconButton(onClick = onImage, enabled = !sending) {
                Icon(Icons.Outlined.Image, "发图片", tint = TextMuted)
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…", color = TextDim, fontSize = 14.sp) },
                maxLines = 5,
                shape = RoundedCornerShape(10.dp),
                colors = fieldColors,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextNorm),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (value.isNotBlank() && !sending) Blurple else Color(0xFF404249))
                    .clickable(enabled = value.isNotBlank() && !sending) { onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Send, "发送", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier.padding(start = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onFile, enabled = !sending) {
                Icon(Icons.Outlined.AttachFile, "发文件", tint = TextMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("文件", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    prefs: Prefs,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    onPickMyAvatar: () -> Unit,
    onPickHerAvatar: () -> Unit,
    onPickBgImage: () -> Unit,
    onPickBgVideo: () -> Unit,
    onClearBg: () -> Unit,
    onBgChanged: () -> Unit,
    pan: Float,
    onPanChange: (Float) -> Unit,
    onEnterPanMode: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reveal by remember { mutableStateOf(prefs.bgReveal()) }
    var server by remember { mutableStateOf(prefs.server) }
    var token by remember { mutableStateOf(prefs.token) }
    var myName by remember { mutableStateOf(prefs.myName) }
    var herName by remember { mutableStateOf(prefs.herName) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = DIALOG_BG,
        shape = RoundedCornerShape(10.dp),
        title = { Text("设置", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .height(430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionLabel("个人")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).clickable { onPickHerAvatar() }) {
                        Avatar(prefs.avatarFile("her").takeIf { it.exists() }, 40)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(CircleShape).clickable { onPickMyAvatar() }) {
                        Avatar(prefs.avatarFile("me").takeIf { it.exists() }, 40)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        TextButton(onClick = onPickHerAvatar) {
                            Text("换她的头像", color = Blurple, fontSize = 12.sp)
                        }
                        TextButton(onClick = onPickMyAvatar) {
                            Text("换我的头像", color = Blurple, fontSize = 12.sp)
                        }
                    }
                }
                Field(myName, { myName = it }, "我的 ID / 昵称")
                Field(herName, { herName = it }, "她的名字")

                SectionLabel("背景")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip("选视频", false) { onPickBgVideo() }
                    Spacer(Modifier.width(6.dp))
                    Chip("选图片", false) { onPickBgImage() }
                    Spacer(Modifier.width(6.dp))
                    Chip("清除", false) { onClearBg() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("旋转", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Chip("转90°", prefs.bgRotate()) {
                        prefs.setBgRotate(!prefs.bgRotate())
                        onBgChanged()
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("位置", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Slider(
                        value = pan,
                        onValueChange = { onPanChange(it) },
                        modifier = Modifier.weight(1f),
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Blurple,
                            activeTrackColor = Blurple,
                            inactiveTrackColor = Color(0xFF404249),
                        ),
                    )
                    TextButton(onClick = onEnterPanMode) { Text("拖动", color = Blurple, fontSize = 12.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显现", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Slider(
                        value = reveal,
                        onValueChange = {
                            reveal = it
                            prefs.setBgReveal(it)
                            onBgChanged()
                        },
                        modifier = Modifier.weight(1f),
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Blurple,
                            activeTrackColor = Blurple,
                            inactiveTrackColor = Color(0xFF404249),
                        ),
                    )
                    Text("${(reveal * 100).toInt()}%", color = TextMuted, fontSize = 11.sp)
                }

                SectionLabel("连接")
                Field(server, { server = it }, "服务地址", mono = true)
                Field(token, { token = it }, "口令 token", mono = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            prefs.server = server
                            prefs.token = token
                            testing = true
                            result = null
                            scope.launch {
                                result = if (Bridge.ping(prefs)) "连上了 ✓" else "连不上 ✗"
                                testing = false
                            }
                        },
                        enabled = !testing,
                    ) { Text(if (testing) "测试中…" else "测试连接", color = Blurple, fontSize = 12.sp) }
                    Text(
                        result ?: "",
                        color = if (result?.contains("✓") == true) Green else Red,
                        fontSize = 12.sp,
                    )
                }
                Text("背景诊断：", color = TextDim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                Text(BgDebug.line, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
                Text("v3.7 · 上传修复", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.server = server
                prefs.token = token
                prefs.myName = myName
                prefs.herName = herName
                onSaved()
            }) { Text("保存", color = Blurple, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消", color = TextMuted) } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Blurple else Color(0xFF404249))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, mono: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        textStyle = if (mono) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Blurple,
            unfocusedBorderColor = Color(0xFF404249),
            focusedContainerColor = Color(0xFF313338),
            unfocusedContainerColor = Color(0xFF313338),
        ),
    )
}

private fun savePart(prefs: Prefs, part: Part): String? {
    if (part.b64.isBlank()) return null
    return try {
        val bytes = Base64.getDecoder().decode(part.b64)
        val safe = part.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "img" }
        val f = File(prefs.dir, "recv_" + System.currentTimeMillis() + "_" + safe + ".png")
        f.outputStream().use { it.write(bytes) }
        f.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun readUri(ctx: Context, uri: Uri): Pair<ByteArray?, String> {
    val name = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull() ?: ("file_" + System.currentTimeMillis())

    val bytes = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()
    return bytes to name
}
