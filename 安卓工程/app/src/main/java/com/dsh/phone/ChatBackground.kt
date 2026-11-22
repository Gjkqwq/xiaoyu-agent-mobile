package com.dsh.phone

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BgDebug {
    @Volatile var line: String = "（还没算过）"
}

/**
 * 聊天背景层 —— 行为对齐 v1.4：**只有一种填充方式，永远铺满，不会出现黑边**。
 *
 * 做法：算出「等比放大到能盖住整屏」的尺寸，用这个尺寸摆放，父容器裁掉溢出。
 * 缩放系数只有一个（k = max(屏宽/素材宽, 屏高/素材高)），所以永远不会变形；
 * k 取大的那个，所以两个方向都一定铺满，永远不会留边。
 *
 * 可选：左右平移（pan = -1 贴左 / 0 居中 / +1 贴右）、顺时针转 90°。
 */
@Composable
fun ChatBackground(prefs: Prefs, version: Int, pan: Float) {
    val kind = remember(version) { prefs.bgKind() }
    val reveal = remember(version) { prefs.bgReveal() }
    val rotate90 = remember(version) { prefs.bgRotate() }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF070A12), Color(0xFF121D33), Color(0xFF070A12))
                )
            )
    ) {
        when (kind) {
            BgKind.IMAGE -> {
                val file = remember(version) { prefs.bgImageFile() }
                val bmp by produceState<android.graphics.Bitmap?>(null, version) {
                    value = if (file.exists()) {
                        withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                    } else null
                }
                bmp?.let { b ->
                    CoverFrame(b.width, b.height, pan, rotate90) {
                        Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            BgKind.VIDEO -> {
                val file = remember(version) { prefs.bgVideoFile() }
                if (file.exists()) {
                    val dims by produceState<IntArray?>(null, version) {
                        value = withContext(Dispatchers.IO) { probeVideo(file.absolutePath) }
                    }
                    dims?.let { d ->
                        CoverFrame(d[0], d[1], pan, rotate90) { TextureVideo(file) }
                    }
                }
            }

            BgKind.NONE -> Unit
        }

        // 压暗层：reveal 越大越淡
        val dim = (1f - reveal).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF060A12).copy(alpha = (dim * 0.95f).coerceIn(0f, 1f)),
                            Color(0xFF0A1120).copy(alpha = (dim * 0.78f).coerceIn(0f, 1f)),
                            Color(0xFF060A12).copy(alpha = (dim * 0.95f).coerceIn(0f, 1f)),
                        )
                    )
                )
        )
    }
}

/**
 * 永远铺满：k = max(屏宽/素材宽, 屏高/素材高)，只有一个缩放系数。
 * 转 90° 时宽高先互换再算 k，所以旋转后同样铺满、同样不变形。
 */
@Composable
private fun CoverFrame(
    srcW: Int,
    srcH: Int,
    pan: Float,
    rotate90: Boolean,
    content: @Composable () -> Unit,
) {
    val fw = if (rotate90) srcH else srcW
    val fh = if (rotate90) srcW else srcH

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val cw = with(density) { maxWidth.toPx() }
        val ch = with(density) { maxHeight.toPx() }
        if (cw <= 0f || ch <= 0f || fw <= 0 || fh <= 0) return@BoxWithConstraints

        val geo = remember(cw, ch, fw, fh, pan, rotate90) {
            val k = maxOf(cw / fw, ch / fh)
            val w = fw * k
            val h = fh * k
            val slackX = (w - cw).coerceAtLeast(0f)
            val x = -(slackX * (pan + 1f) / 2f)
            val y = (ch - h) / 2f
            floatArrayOf(w, h, x, y, k)
        }

        val d = density.density
        BgDebug.line = buildString {
            append("素材 ").append(fw).append("×").append(fh)
            append(" · 屏px ").append(cw.toInt()).append("×").append(ch.toInt())
            append(" · 画面 ").append((geo[0] / d).toInt()).append("×").append((geo[1] / d).toInt()).append("dp")
            append(" · k=").append(String.format(java.util.Locale.US, "%.3f", geo[4]))
            append(" · 偏移 ").append((geo[2] / d).toInt()).append(",").append((geo[3] / d).toInt())
            append(" · 横向溢出 ").append(((geo[0] - cw) / d).toInt()).append("dp")
            if (rotate90) append(" · 转90°")
        }

        // 关键：requiredSize 不受父容器最大约束影响。
        // 用 size() 的话，超过屏幕的尺寸会被压回屏幕大小，内容再铺满 -> 纵向被拉伸压扁。
        Box(
            Modifier
                .requiredSize(with(density) { geo[0].toDp() }, with(density) { geo[1].toDp() })
                .offset(with(density) { geo[2].toDp() }, with(density) { geo[3].toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            if (rotate90) {
                Box(
                    Modifier
                        .requiredSize(with(density) { geo[1].toDp() }, with(density) { geo[0].toDp() })
                        .rotate(90f)
                ) { content() }
            } else {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

/** 视频用 TextureView：SurfaceView 旋转时容易出问题，TextureView 随便转。 */
@Composable
private fun TextureVideo(file: java.io.File) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            val mp = MediaPlayer()
                            mp.setDataSource(file.absolutePath)
                            mp.isLooping = true
                            mp.setVolume(0f, 0f)
                            mp.setSurface(Surface(st))
                            mp.setOnPreparedListener { p -> player = p; p.start() }
                            mp.prepareAsync()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        runCatching { player?.release() }
                        player = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }
}

/** 读视频真实显示尺寸，处理 90/270 旋转标记。 */
private fun probeVideo(path: String): IntArray? = try {
    val mmr = MediaMetadataRetriever()
    mmr.setDataSource(path)
    val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    mmr.release()
    when {
        w <= 0 || h <= 0 -> null
        rot == 90 || rot == 270 -> intArrayOf(h, w)
        else -> intArrayOf(w, h)
    }
} catch (e: Exception) {
    null
}
