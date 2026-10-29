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
val KEYWORDS = listOf(
    // 욕설·위협
    "시발", "씨발", "시바", "씨바", "병신", "개새끼", "좆", "죽여", "죽을래", "꺼져", "패버",
    // 착용자 이름 → "나를 향한 말"이라는 뜻
    "정지윤", "지윤"
)

// 단어 인식 공통 모양 (워치 자체 인식 / 폰에게 맡기기 둘 다 이 모양)
interface WordListener {
    val heardWord: String?
    val info: String
    fun start()
    fun feed(chunk: ShortArray)
    fun clearHeard()
    fun stop()
}

// ───────── 단어 인식 (기기 안 음성인식, 인터넷 사용 안 함) ─────────
// 2단계 마이크 소리를 그대로 받아서 글자로 바꾸고, KEYWORDS 가 들리면 알려줌
class KeywordSpotter(private val context: Context) : WordListener {

    @Volatile override var heardWord: String? = null      // 들린 단어 (없으면 null)
        private set
    @Volatile override var info: String = ""              // 화면 표시용 상태
        private set

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

    override fun clearHeard() {
        heardWord = null
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

    private fun check(results: Bundle?) {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val joined = texts.joinToString(" ")
        if (joined.isBlank()) return
        errorCount = 0
        val compact = joined.replace(" ", "")
        info = "들림: " + joined.takeLast(14)
        val word = KEYWORDS.firstOrNull { compact.contains(it) }
        if (word != null) heardWord = word
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) = check(partialResults)
        override fun onSegmentResults(segmentResults: Bundle) = check(segmentResults)
        override fun onResults(results: Bundle?) {
            check(results)
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