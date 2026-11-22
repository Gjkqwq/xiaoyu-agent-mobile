package com.dsh.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 消息层（独立文件，界面只管显示）
 *
 * 设计：
 *   - 最终回复走阻塞的 /chat，一问一答，不会重复
 *   - 播报(step/say)、提问(question) 走 /inbox 轮询，只取这几类，跳过 reply
 *   - 忙的时候发消息：先 /stop 打断，再发
 *   - 每条自己发的消息有状态：未读 → 排队中 → 已读
 */
class ChatEngine(private val scope: CoroutineScope, private val prefs: Prefs) {

    interface Listener {
        /** 她的回复（已按行拆好由外部处理，这里给整段） */
        fun onReply(reply: String, parts: List<Part>, sessionId: String)
        /** 播报 / 进度 */
        fun onSay(text: String)
        /** 她在提问 */
        fun onAsk(id: String, question: String, options: List<String>)
        /** 自己那条消息的状态变了 */
        fun onStatus(msgIndex: Int, status: String)
        fun onError(msg: String)
        /** 忙 / 空闲 */
        fun onBusy(busy: Boolean)
    }

    private var pollJob: Job? = null
    private var sinceSeq = 0L
    var busy = false
        private set

    /** 发一句。msgIndex 用于回写已读状态 */
    fun send(text: String, msgIndex: Int, l: Listener) {
        scope.launch {
            if (busy) {
                l.onStatus(msgIndex, "打断中…")
                try { BridgeX.stop(prefs) } catch (_: Exception) {}
                busy = false
                delay(500)
            }
            busy = true
            l.onBusy(true)
            startPolling(l)
            l.onStatus(msgIndex, "未读")

            var res: ChatResult? = null
            var tries = 0
            while (res == null) {
                try {
                    res = Bridge.chat(prefs, text)
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (msg.contains("409") && tries < 40) {
                        // 上一句还在跑：排队等它空出来
                        l.onStatus(msgIndex, "排队中…")
                        tries++
                        delay(2000)
                    } else {
                        l.onStatus(msgIndex, "发送失败")
                        l.onError(msg)
                        busy = false
                        l.onBusy(false)
                        return@launch
                    }
                }
            }
            l.onStatus(msgIndex, "已读")
            if (res.sessionId.isNotBlank()) prefs.sessionId = res.sessionId
            l.onReply(res.reply, res.parts, res.sessionId)
            busy = false
            l.onBusy(false)
        }
    }

    /** 打断 */
    fun stop(l: Listener) {
        scope.launch {
            try { BridgeX.stop(prefs) } catch (_: Exception) {}
            busy = false
            l.onBusy(false)
        }
    }

    /** 回答她的提问 */
    fun answer(id: String, answer: String, l: Listener) {
        scope.launch {
            try { BridgeX.answer(prefs, id, answer) } catch (e: Exception) { l.onError(e.message ?: "回答没发出去") }
        }
    }

    /** 轮询播报和提问 */
    private fun startPolling(l: Listener) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var idleRounds = 0
            while (true) {
                try {
                    val page = BridgeX.inbox(prefs, sinceSeq)
                    if (page.seq > sinceSeq) sinceSeq = page.seq
                    for (it in page.items) {
                        if (it.type == "question") {
                            l.onAsk(it.askId, it.question.ifBlank { it.reply }, it.options)
                        } else if (it.type == "step" || it.type == "say") {
                            l.onSay(it.reply)
                        }
                    }
                    // 常驻轮询：任务结束后也继续跑一会儿，她的收尾播报才不会被吞
                    if (!page.running && page.queued == 0) {
                        idleRounds++
                        if (idleRounds >= 60) break   // 约 2 分钟没事才停
                    } else {
                        idleRounds = 0
                    }
                } catch (_: Exception) {
                    idleRounds++
                }
                delay(900)
            }
        }
    }
}