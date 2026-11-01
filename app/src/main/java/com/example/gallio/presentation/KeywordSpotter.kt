package com.example.gallio.presentation

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

// ───────── 감지할 단어 (직접 추가·삭제 가능) ─────────
// 띄어쓰기는 무시하고 "들린 말에 이 단어가 들어 있는지"로 판단
// 욕설: 기분 나쁜 말 / 위협: 해를 끼치겠다는 말 (더 위험하게 봄)
val SWEAR_WORDS = listOf(
    "시발", "씨발", "시바", "씨바", "씨팔", "병신", "개새끼", "좆", "지랄", "미친놈", "미친년",
    "닥쳐", "꺼져", "뒤질", "또라이",
    // 받아쓰기가 다르게 적는 경우 + 자주 쓰는 욕
    "시팔", "씨빨", "십알", "ㅅㅂ", "ㅆㅂ", "병씬", "븅신", "개새", "개색", "새끼야", "좆같", "존나",
    "엿먹", "니미", "느금", "염병", "썅", "쌍놈", "쌍년", "미친새끼", "미쳤냐", "돌았냐", "등신", "멍청한",
    "졸라", "**"   // ** = 받아쓰기가 욕을 별표로 가린 경우
)
val THREAT_WORDS = listOf(
    "죽여", "죽을래", "죽고싶", "가만안둬", "가만안둔다", "찾아간다", "찾아갈", "패버", "때려", "때릴",
    "맞고싶", "칼로", "칼들고", "칼빵", "신상털"   // "칼"만 있으면 칼국수도 잡혀서 뺌
)

// 막말·갑질: 욕은 아니지만 진상 손님이 자주 하는 말 (욕설과 같은 점수로 셈)
val JINSANG_WORDS = listOf(
    "사장나와", "사장나오라", "사장불러", "책임자나와", "몇살이야", "내가누군지", "알바주제", "주제에",
    "니가뭔데", "너따위", "니까짓", "똑바로안해", "똑바로해", "눈깔", "눈을그렇게", "어디서감히",
    "무릎꿇", "나무시", "일머리", "알바나하", "알바나하고", "무시하냐", "가르쳤냐", "머리가나쁘", "말귀를", "그따위", "싸가지", "개념없",
    "잘리고싶", "잘라버", "모가지", "반말하", "뭘봐", "웃기고있네", "장난하냐", "장난해"
)

// 불만·짜증: 아직 욕은 아니지만 분위기가 험해지는 말 → "주의"까지만 올림 (이것만으로는 녹음 안 함)
val WARN_WORDS = listOf(
    "눈없", "눈이없", "눈똑바로", "어떻게들고", "답답하", "짜증나", "왜이렇게느려", "빨리빨리",
    "제대로좀", "똑바로좀", "일을이렇게", "뭐하는거야", "생각이없", "아이씨", "정신없"
)

data class WordHit(val word: String, val threat: Boolean, val warn: Boolean = false)

// 알아들은 문장 (문장 AI 가 판단). 같은 문장은 같은 id (말하는 중간 결과 → 최종 결과)
data class Heard(val id: Int, val text: String, val isFinal: Boolean)

// 문장을 AI 에게 넘길지 정리: 문장이 끝났거나, 말하는 중이라도 6글자 이상 늘었으면 넘김
class SentenceTracker {
    private var id = 0
    private var lastLen = 0

    @Synchronized
    fun update(text: String, isFinal: Boolean): Heard? {
        val out = when {
            text.isBlank() -> null
            isFinal || text.length - lastLen >= 6 -> Heard(id, text, isFinal)
            else -> null
        }
        if (out != null) lastLen = text.length
        if (isFinal) { id++; lastLen = 0 }
        return out
    }
}

// 문장 안의 욕설·위협 단어를 "몇 번" 나왔는지 셈 (긴 단어부터 찾고 지워서 겹쳐 세지 않음)
fun countWords(text: String): Map<WordHit, Int> {
    var t = text.replace(" ", "")
    val result = mutableMapOf<WordHit, Int>()
    val all = (SWEAR_WORDS + JINSANG_WORDS).map { WordHit(it, false) } + THREAT_WORDS.map { WordHit(it, true) } +
            WARN_WORDS.map { WordHit(it, false, warn = true) }
    for (w in all.sortedByDescending { it.word.length }) {
        var count = 0
        var i = t.indexOf(w.word)
        while (i >= 0) {
            count++
            t = t.substring(0, i) + "#".repeat(w.word.length) + t.substring(i + w.word.length)
            i = t.indexOf(w.word)
        }
        if (count > 0) result[w] = count
    }
    return result
}

// 음성인식은 말하는 도중에 "중간 결과"를 여러 번 보냄 → 같은 욕이 여러 번 세어지지 않게
// 한 문장 안에서는 "지금까지 센 횟수보다 늘어난 만큼만" 새로 들린 것으로 인정
class HitCounter {
    private val seen = mutableMapOf<WordHit, Int>()

    @Synchronized
    fun update(text: String, isFinal: Boolean): List<WordHit> {
        val newHits = mutableListOf<WordHit>()
        for ((w, count) in countWords(text)) {
            val before = seen[w] ?: 0
            if (count > before) {
                repeat(count - before) { newHits.add(w) }
                seen[w] = count
            }
        }
        if (isFinal) seen.clear()   // 문장이 끝나면 다음 문장은 새로 셈
        return newHits
    }
}

// 단어 인식 공통 모양 (워치 자체 인식 / 폰에게 맡기기 둘 다 이 모양)
interface WordListener {
    val info: String
    fun start()
    fun feed(chunk: ShortArray)
    fun takeHits(): List<WordHit>   // 지난번 이후 새로 들린 욕설·위협 단어
    fun takeSentences(): List<Heard> // 지난번 이후 새로 알아들은 문장 (문장 AI 용)
    fun stop()
}

// ───────── 단어 인식 (기기 안 음성인식, 인터넷 사용 안 함) ─────────
// 근무 모드 마이크 소리를 그대로 받아서 글자로 바꾸고, 욕설·위협 단어를 셈
class KeywordSpotter(private val context: Context) : WordListener {

    @Volatile override var info: String = ""              // 화면 표시용 상태
        private set
    private val counter = HitCounter()
    private val hits = java.util.concurrent.ConcurrentLinkedQueue<WordHit>()
    private val tracker = SentenceTracker()
    private val sentences = java.util.concurrent.ConcurrentLinkedQueue<Heard>()

    @Volatile private var running = false
    @Volatile private var out: OutputStream? = null
    private var readSide: ParcelFileDescriptor? = null
    private var recognizer: SpeechRecognizer? = null
    private var errorCount = 0
    private val main = Handler(Looper.getMainLooper())

    // 소리를 음성인식에 넘겨주는 별도 일꾼 (음성인식이 느려도 녹음이 멈추지 않게)
    private val queue = LinkedBlockingQueue<ByteArray>(50)
    private var writerThread: Thread? = null

    companion object {
        // 기기 안 음성인식 + "내 소리 넘겨주기" 기능은 안드로이드 13 이상에서만 가능
        fun supported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    // ※ 메인 스레드에서 호출
    override fun start() {
        if (!supported(context)) {
            info = "단어 인식: 이 기기 미지원"
            return
        }
        running = true
        errorCount = 0
        info = "단어 인식: 켜짐"
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        writerThread = Thread {
            try {
                while (running) {
                    val bytes = queue.take()
                    try { out?.write(bytes) } catch (e: Exception) { /* 재시작 중이면 버림 */ }
                }
            } catch (e: InterruptedException) { /* 종료 */ }
        }.also { it.start() }
        begin()
    }

    // 마이크에서 받은 0.1초 소리를 넘겨줌 (꽉 차면 그냥 버림)
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

    override fun takeHits(): List<WordHit> {
        val list = mutableListOf<WordHit>()
        while (true) list.add(hits.poll() ?: break)
        return list
    }

    override fun takeSentences(): List<Heard> {
        val list = mutableListOf<Heard>()
        while (true) list.add(sentences.poll() ?: break)
        return list
    }

    // ※ 메인 스레드에서 호출
    override fun stop() {
        running = false
        writerThread?.interrupt()
        writerThread = null
        closePipe()
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        queue.clear()
    }

    private fun begin() {
        if (!running) return
        val r = recognizer ?: return
        val pipe = ParcelFileDescriptor.createPipe()
        readSide = pipe[0]
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 마이크를 따로 켜지 말고, 우리가 넘겨주는 소리를 들어라
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            // 한 문장 끝나도 멈추지 말고 계속 들어라
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            info = "단어 인식: 시작 실패"
            running = false
        }
    }

    private fun closePipe() {
        try { out?.close() } catch (e: Exception) {}
        try { readSide?.close() } catch (e: Exception) {}
        out = null
        readSide = null
    }

    // 끊기면 잠깐 쉬었다 다시 듣기
    private fun restart() {
        if (!running) return
        closePipe()
        try { recognizer?.cancel() } catch (e: Exception) {}
        main.postDelayed({ begin() }, 200)
    }

    private fun check(results: Bundle?, isFinal: Boolean) {
        // 첫 번째(가장 그럴듯한) 결과만 사용
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (text.isNullOrBlank()) {
            if (isFinal) {
                counter.update("", true)
                tracker.update("", true)
            }
            return
        }
        errorCount = 0
        info = "들림: " + text.takeLast(14)
        hits.addAll(counter.update(text, isFinal))
        tracker.update(text, isFinal)?.let { sentences.add(it) }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) = check(partialResults, false)
        override fun onSegmentResults(segmentResults: Bundle) = check(segmentResults, true)
        override fun onResults(results: Bundle?) {
            check(results, true)
            restart()
        }
        override fun onEndOfSegmentedSession() = restart()

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    info = "단어 인식: 한국어 모델 없음"
                    running = false
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    info = "단어 인식: 권한 없음"
                    running = false
                }
                else -> {
                    errorCount++
                    if (errorCount > 5) {
                        info = "단어 인식: 오류 $error"
                        running = false
                    } else restart()
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}