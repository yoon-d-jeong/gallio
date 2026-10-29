package com.example.gallio.presentation

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

// ───────── 워치 ↔ 폰 연결 (블루투스, 인터넷 안 씀) ─────────
// 폰 앱(WatchListenerService)에도 똑같은 경로 이름이 있어야 함
object PhoneLink {
    const val PATH_ALERT = "/gallio/alert"       // 워치 → 폰: 녹음 시작됨
    const val PATH_AUDIO = "/gallio/audio"       // 워치 → 폰: 2단계 소리 실시간 전송
    const val PATH_KEYWORD = "/gallio/keyword"   // 폰 → 워치: 욕설·이름 들림
    const val PATH_TEXT = "/gallio/text"         // 폰 → 워치: 알아들은 말 (화면 표시용)
    const val PATH_REC = "/gallio/rec/"          // 워치 → 폰: 녹음 파일 백업

    // 녹음 시작 알림 (보호자 문자는 폰이 판단)
    fun sendAlert(context: Context, trigger: String) = background {
        val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
        nodes.forEach {
            Wearable.getMessageClient(context).sendMessage(it.id, PATH_ALERT, trigger.toByteArray())
        }
    }

    // 녹음 파일 + 증거 정보를 폰으로 백업 (폰이 지금 연결 안 돼 있어도, 나중에 연결되면 자동 전송됨)
    fun backup(context: Context, info: RecordingInfo) = background {
        val file = RecordingStore.wav(context, info.name)
        if (!file.exists()) return@background
        val req = PutDataMapRequest.create(PATH_REC + info.name).apply {
            dataMap.putAsset("audio", Asset.createFromBytes(file.readBytes()))
            dataMap.putString("name", info.name)
            dataMap.putLong("start", info.start)
            dataMap.putInt("durationSec", info.durationSec)
            dataMap.putString("trigger", info.trigger)
            dataMap.putDouble("maxDb", info.maxDb)
            dataMap.putInt("heartRate", info.heartRate)
            dataMap.putString("sha256", info.sha256)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(req)
    }

    private fun background(work: () -> Unit) {
        Thread {
            try { work() } catch (e: Exception) { /* 폰 연결 안 됨 등 → 워치 동작엔 영향 없음 */ }
        }.start()
    }
}

// ───────── 폰에게 대신 들어달라고 하는 단어 인식 ─────────
// 워치에 한국어 기기 안 인식이 없을 때 사용. 2단계 소리를 폰으로 보내면 폰이 알아듣고 결과를 돌려줌
class PhoneKeywordSpotter(private val context: Context) : WordListener, MessageClient.OnMessageReceivedListener {

    @Volatile override var heardWord: String? = null
        private set
    @Volatile override var info: String = ""
        private set

    @Volatile private var running = false
    @Volatile private var out: OutputStream? = null
    private var channel: ChannelClient.Channel? = null
    private val queue = LinkedBlockingQueue<ByteArray>(50)
    private var thread: Thread? = null

    override fun start() {
        running = true
        info = "단어 인식: 폰 연결 중"
        Wearable.getMessageClient(context).addListener(this)
        thread = Thread {
            try {
                val node = Tasks.await(Wearable.getNodeClient(context).connectedNodes).firstOrNull { it.isNearby }
                if (node == null) {
                    info = "단어 인식: 폰 연결 안 됨"
                    return@Thread
                }
                val channels = Wearable.getChannelClient(context)
                val ch = Tasks.await(channels.openChannel(node.id, PhoneLink.PATH_AUDIO))
                channel = ch
                val os = Tasks.await(channels.getOutputStream(ch))
                out = os
                info = "단어 인식: 폰이 듣는 중"
                while (running) {
                    os.write(queue.take())
                    os.flush()
                }
            } catch (e: InterruptedException) {
                // 종료
            } catch (e: Exception) {
                if (running) info = "단어 인식: 폰 연결 실패"
            }
        }.also { it.start() }
    }

    override fun feed(chunk: ShortArray) {
        if (!running) return
        val bytes = ByteArray(chunk.size * 2)
        for (i in chunk.indices) {
            val s = chunk[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        queue.offer(bytes)
    }

    // 폰이 보낸 결과 받기
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PhoneLink.PATH_KEYWORD -> {
                val word = String(event.data)
                heardWord = word
                info = "폰이 들음: '$word'"
            }
            PhoneLink.PATH_TEXT -> {
                if (heardWord == null) info = "폰: " + String(event.data).takeLast(14)
            }
        }
    }

    override fun clearHeard() {
        heardWord = null
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try { out?.close() } catch (e: Exception) {}
        channel?.let { try { Wearable.getChannelClient(context).close(it) } catch (e: Exception) {} }
        Wearable.getMessageClient(context).removeListener(this)
        queue.clear()
    }
}