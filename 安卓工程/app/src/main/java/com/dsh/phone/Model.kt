package com.dsh.phone

/** 一条消息的类型。 */
enum class MKind {
    TEXT,     // 纯文字
    STICKER,  // 表情包（payload = assets/stickers 里的文件名）
    IMAGE,    // 图片（payload = 手机本地缓存路径）
    FILE,     // 文件（payload = 手机本地缓存路径，text = 文件名）
}

data class Msg(
    val fromMe: Boolean,
    val text: String,
    val at: Long = System.currentTimeMillis(),
    val kind: MKind = MKind.TEXT,
    val payload: String = "",
)
