package com.example.gallio.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

// ───────── 설정값 (실험하면서 바꿀 숫자들) ─────────
// [가중치] 네 가지를 각각 0~1 점수로 만든 뒤 이 비율로 합침 (합이 1)
const val W_WORD = 0.65              // 언어 65% (욕설·위협 단어 목록 + 문장 AI 중 높은 쪽)
const val W_SHOUT = 0.10             // 고함 10%
const val W_SUSTAIN = 0.05           // 지속성 5% (한 번 소리친 것 vs 계속 몰아붙이는 것)
const val W_HEART = 0.20             // 심박 20% (착용자가 실제로 긴장했는지)

const val RECORD_SCORE = 0.6         // 위험 점수가 이 이상이면 녹음 시작 (0~1)
const val RECORD_HOLD_CHUNKS = 10    // 그 상태가 1초 이상 이어져야 시작 (순간 튀는 값 무시)
const val STOP_SCORE = 0.25          // 녹음 중 점수가 이 아래로 QUIET_STOP_SECONDS 동안 있으면 멈춤
const val HOLD_SECONDS = 3           // 고함·심박의 높은 값을 몇 초 동안 기억할지

// [욕설·위협] 최근 WORD_WINDOW_SECONDS 초 동안 들린 횟수로 점수
const val WORD_WINDOW_SECONDS = 30
val SWEAR_SCORE = listOf(0.0, 0.72, 1.0)      // 욕·막말 0번, 1번(47점: 고함·심박이 조금 더해지면 녹음), 2번 이상(65점: 바로 녹음)
val THREAT_SCORE = listOf(0.0, 0.75, 1.0)      // 위협 0번, 1번(49점), 2번 이상(65점)
const val SWEAR_AND_THREAT_BONUS = 0.3         // 욕 + 위협이 같이 나오면 추가 점수
const val WARN_SCORE = 0.5                      // 불만·짜증 말 ("눈 없어?") → 언어 32점 = "주의"까지만

// [문장 AI] 욕이 없어도 "알바 주제에 어디서", "사장 나와" 같은 공격적인 말을 문장 전체로 판단
const val USE_TEXT_AI = true                    // false 면 단어 목록만 사용
val TEXT_COUNT_SCORE = listOf(0.0, 0.85, 1.0, 1.0)   // 공격적인 문장 0, 1, 2개 이상
const val TEXT_SCORE_MAX = 0.5                         // 문장 AI만으로는 "주의"(32점)까지. 녹음은 욕·막말·위협 단어나 고함·심박이 더해져야
const val TEXT_AI_THRESHOLD = 0.4                     // AI 확률이 이 이상이면 공격적인 문장 (평범한 주문 문장은 0.1 이하)

// [고함] 주변보다 얼마나 커졌나 + AI 고함 점수
const val SAMPLE_RATE = 16000        // 1초에 소리를 16,000번 측정
const val SOUND_MIN_DB = -40.0       // 이보다 작은 소리는 0점
const val SOUND_FULL_RISE_DB = 20.0  // 주변 소음보다 이만큼 커지면 크기 점수 1점
const val USE_AI = true              // false 면 AI 없이 크기 점수만 사용

// [지속성] 최근 SUSTAIN_WINDOW_SECONDS 초 중 "거친 소리"였던 비율. SUSTAIN_FULL 이상이면 1점
const val SUSTAIN_WINDOW_SECONDS = 30
const val SUSTAIN_FULL = 0.5         // 30초 중 50%(15초) 이상 거칠어야 만점 (잠깐 큰 소리는 거의 0점)

// [심박] 최근 HR_JUMP_SECONDS 초 동안 HR_FULL_RISE 만큼 오르면 1점
const val HR_JUMP_SECONDS = 10       // (일하면서 서서히 오르는 건 점수가 거의 안 나옴)
const val HR_FULL_RISE = 25.0

// [녹음]
const val PRE_SECONDS = 5            // 감지 "이전" 몇 초까지 같이 저장할지
const val QUIET_STOP_SECONDS = 10    // 점수가 낮은 상태가 몇 초 이어지면 녹음을 멈출지
const val MAX_RECORD_SECONDS = 120   // 한 파일 최대 길이 (초)
const val MANUAL_MIN_SECONDS = 60    // 긴급 녹음 버튼을 누르면 최소 이 시간은 녹음
const val KEEP_DAYS = 7              // 이 기간이 지난 녹음은 자동 삭제 (사생활 보호)

fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)

// 화면에 보여줄 상태 묶음
data class AudioStatus(
    val score: Double = 0.0,         // 최종 위험 점수 (0~1)
    val wordScore: Double = 0.0,     // 언어 점수 (단어 목록·문장 AI 중 높은 쪽)
    val textCount: Int = 0,          // 최근 30초 AI가 공격적이라고 본 문장 수
    val lastAiProb: Double = -1.0,   // 마지막으로 들은 문장의 AI 공격 확률 (-1 = 아직 없음)
    val shoutScore: Double = 0.0,    // 고함 점수
    val sustainScore: Double = 0.0,  // 지속성 점수
    val heartScore: Double = 0.0,    // 심박 점수
    val swearCount: Int = 0,         // 최근 30초 욕 횟수
    val threatCount: Int = 0,        // 최근 30초 위협 횟수
    val warnCount: Int = 0,          // 최근 30초 불만·짜증 말 횟수
    val heartRate: Int = 0,          // 현재 심박
    val hrRise: Int = 0,             // 최근 HR_JUMP_SECONDS 초 동안 오른 심박
    val db: Double = -90.0,          // 현재 소리 크기
    val ambientDb: Double = -90.0,   // 주변 소음 크기
    val aiLabel: String = "",        // AI가 들은 소리 중 1위 (예: "말소리 82%")
    val aiShout: Float = 0f,         // AI가 판단한 "고함류" 점수 (0~1)
    val keywordInfo: String = "",    // 단어 인식 상태
    val recording: Boolean = false,
    val manual: Boolean = false,     // 긴급 버튼으로 시작한 녹음인지
    val savedCount: Int = 0
)

// 서비스(뒤에서 일하는 쪽)와 화면이 같이 보는 "게시판"
object AudioState {
    val running = MutableStateFlow(false)          // 외출 모드(서비스)가 켜져 있는지
    val status = MutableStateFlow(AudioStatus())   // 현재 상태
    val manualRequest = MutableStateFlow(false)    // 긴급 녹음 버튼이 눌렸는지
}

// 심박 권한 이름 (안드로이드 16 이상은 이름이 바뀜)
fun heartRatePermission(): String =
    if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE"
    else Manifest.permission.BODY_SENSORS

fun hasHeartRatePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, heartRatePermission()) == PackageManager.PERMISSION_GRANTED

// ───────── 포그라운드 서비스: 화면이 꺼져도 계속 감지 ─────────
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var heart: HeartRateDetector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()   // 알림창에 "외출 모드 켜짐" 표시 (이게 있어야 뒤에서 계속 돌 수 있음)

        if (intent?.action == ACTION_MANUAL) AudioState.manualRequest.value = true

        if (job == null) {
            RecordingStore.deleteOld(applicationContext)

            val h = HeartRateDetector(applicationContext)
            heart = h
            AudioState.running.value = true
            AudioState.status.value = AudioStatus(savedCount = RecordingStore.count(applicationContext))

            job = scope.launch {
                h.start()
                try {
                    watchLoop(applicationContext)   // 근무 모드가 꺼질 때까지 계속
                } finally {
                    h.stop()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        heart?.stop()
        heart = null
        scope.cancel()
        AudioState.running.value = false
        AudioState.manualRequest.value = false
        AudioState.status.update {
            AudioStatus(savedCount = RecordingStore.count(applicationContext))
        }
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "근무 모드", NotificationManager.IMPORTANCE_LOW)
        )

        // 알림을 누르면 앱 화면 열기
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("근무 모드 켜짐")
            .setContentText("위험 상황을 감지하고 있어요")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()

        // 마이크 + (심박 권한이 있으면) 건강 센서 용도로 실행
        val micOnly = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val withHealth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasHeartRatePermission(this)) {
            micOnly or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else micOnly

        try {
            startForeground(NOTIFICATION_ID, notification, withHealth)
        } catch (e: Exception) {
            startForeground(NOTIFICATION_ID, notification, micOnly)   // 건강 용도가 거절되면 마이크만
        }
    }

    companion object {
        const val CHANNEL_ID = "outing_mode"
        const val NOTIFICATION_ID = 1
        const val ACTION_MANUAL = "com.example.gallio.MANUAL_RECORD"
    }
}

// ───────── 심박: 최근 몇 초 동안 얼마나 올랐는지 계산 ─────────
// TODO 워치 오면: 삼성 헬스 센서 SDK 로 바꾸면 더 정확함 (지금은 안드로이드 기본 심박 센서)
class HeartRateDetector(context: Context) : SensorEventListener {
    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val history = ArrayDeque<Pair<Long, Float>>()   // (시각, 심박) 최근 기록

    fun start() {
        val sm = sensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        try {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (e: SecurityException) {
            // 심박 권한이 없으면 심박 없이 움직임만으로 동작
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val bpm = event.values[0]
        if (bpm <= 0f) return   // 0 은 측정 실패
        val now = System.currentTimeMillis()

        var rise = 0f
        synchronized(history) {
            history.addLast(now to bpm)
            while (history.isNotEmpty() && now - history.first().first > HR_JUMP_SECONDS * 1000L) {
                history.removeFirst()
            }
            rise = bpm - history.minOf { it.second }   // 최근 몇 초 중 가장 낮았을 때보다 얼마나 올랐나
        }
        AudioState.status.update { it.copy(heartRate = bpm.toInt(), hrRise = rise.toInt()) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// ───────── AI 소리 분류 (YAMNet) ─────────
// 구글이 공개한 YAMNet 모델: 약 1초 소리를 듣고 521가지 종류(말소리, 고함, 음악, 박수...) 중 무엇인지 점수를 매김
object SoundAi {
    const val WINDOW = 15600   // YAMNet 은 16kHz 소리 15600개(약 0.975초)를 한 번에 봄

    // 고함·비명 계열 이름 (YAMNet 라벨)
    private val SHOUT_LABELS = setOf(
        "Shout", "Yell", "Bellow", "Screaming", "Battle cry", "Children shouting", "Whoop"
    )

    // 화면에 한글로 보여줄 이름 (없으면 영어 그대로)
    private val KOREAN = mapOf(
        "Speech" to "말소리", "Shout" to "고함", "Yell" to "고함", "Bellow" to "고함",
        "Screaming" to "비명", "Battle cry" to "고함", "Children shouting" to "아이 고함",
        "Music" to "음악", "Silence" to "조용함", "Crying, sobbing" to "울음",
        "Laughter" to "웃음", "Clapping" to "박수", "Singing" to "노래", "Vehicle" to "차량"
    )

    private var classifier: AudioClassifier? = null
    private var failed = false

    @Synchronized
    fun get(context: Context): AudioClassifier? {
        if (!USE_AI || failed) return null
        classifier?.let { return it }
        return try {
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .setMaxResults(10)
                .build()
            AudioClassifier.createFromOptions(context, options).also { classifier = it }
        } catch (e: Exception) {
            failed = true   // 모델 파일이 없거나 문제가 있으면 AI 없이 소리 크기로만 판단
            null
        }
    }

    // 결과: (1위 소리 이름 + 점수, 고함류 점수)
    fun classify(ai: AudioClassifier, samples: FloatArray): Pair<String, Float> {
        val data = AudioData.create(
            AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(SAMPLE_RATE.toFloat()).build(),
            samples.size
        )
        data.load(samples)
        val result = ai.classify(data)
        val categories = result.classificationResults().firstOrNull()
            ?.classifications()?.firstOrNull()?.categories() ?: return "" to 0f

        val top = categories.maxByOrNull { it.score() }
        val topText = top?.let { "${KOREAN[it.categoryName()] ?: it.categoryName()} ${(it.score() * 100).toInt()}%" } ?: ""
        val shoutScore = categories.filter { it.categoryName() in SHOUT_LABELS }.maxOfOrNull { it.score() } ?: 0f
        return topText to shoutScore
    }
}

// ───────── 위험 점수로 판단해서 녹음 ─────────
// 근무 모드 동안 0.1초마다: 욕설·고함·지속성·심박 점수 → 가중치로 합침 → 기준 넘으면 녹음
@SuppressLint("MissingPermission")
suspend fun watchLoop(context: Context) = withContext(Dispatchers.IO) {
    val chunkSize = SAMPLE_RATE / 10   // 0.1초 분량
    val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuf, chunkSize * 4)
    )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release()
        return@withContext
    }

    var savedCount = RecordingStore.count(context)

    val ai = SoundAi.get(context)               // AI 모델 (없으면 null → 크기 점수만)
    val aiBuffer = FloatArray(SoundAi.WINDOW)   // AI 에게 보여줄 최근 약 1초 소리
    var aiFilled = 0
    var aiLabel = ""
    var aiShout = 0f
    var chunkIndex = 0

    // 단어 인식 (욕설·이름): 워치가 한국어를 알아들으면 워치에서, 아니면 폰에게 맡김
    val spotter: WordListener =
        if (KeywordSpotter.supported(context)) KeywordSpotter(context) else PhoneKeywordSpotter(context)
    withContext(Dispatchers.Main) { spotter.start() }

    val preBuffer = ArrayDeque<ShortArray>()   // 최근 몇 초 소리를 계속 들고 있는 "링 버퍼"
    val recentDb = ArrayDeque<Double>()        // 최근 10초 소리 크기 (주변 소음 계산용)
    var active: ActiveRecording? = null        // 지금 녹음 중인 파일

    // 고함·심박은 "최근 몇 초 중 높은 값"을 기억 (천천히 줄어듦)
    val decay = 1.0 / (HOLD_SECONDS * 10)
    var heldShout = 0.0
    var heldHeart = 0.0
    val wordLog = ArrayDeque<Pair<Long, WordHit>>()   // 최근 30초 동안 들린 욕설·위협 (시각, 단어)
    val textOn = USE_TEXT_AI && TextAbuseModel.load(context)   // 문장 AI (파일 없으면 꺼짐)
    class Sent(var time: Long, var prob: Double, var text: String)
    val sentLog = LinkedHashMap<Int, Sent>()           // 최근 30초 동안 들은 문장 (문장 id → 확률)
    var lastAiProb = -1.0
    val roughLog = ArrayDeque<Boolean>()              // 최근 30초 동안 0.1초마다 "거친 소리"였는지
    var roughCount = 0
    var highChunks = 0                          // 점수가 기준 이상인 상태가 이어진 시간
    var lowChunks = 0                           // 녹음 중 점수가 낮은 상태가 이어진 시간
    var recordedChunks = 0
    var minChunks = 0                           // 긴급 녹음이면 최소 녹음 길이

    recorder.startRecording()
    try {
        while (isActive) {
            val buffer = ShortArray(chunkSize)
            val n = recorder.read(buffer, 0, chunkSize)
            if (n <= 0) continue
            val chunk = buffer.copyOf(n)
            val now = AudioState.status.value

            // ── 소리 크기 ──
            val db = calcDb(chunk)
            recentDb.addLast(db)
            while (recentDb.size > 100) recentDb.removeFirst()
            val ambient = if (recentDb.size >= 10) recentDb.sorted()[recentDb.size / 5] else -90.0
            val loudScore = if (db < SOUND_MIN_DB) 0.0 else clamp01((db - ambient) / SOUND_FULL_RISE_DB)

            // ── AI: 소리가 어느 정도 클 때만 0.5초마다 판단 (배터리 절약) ──
            if (ai != null) {
                val shift = chunk.size.coerceAtMost(SoundAi.WINDOW)
                System.arraycopy(aiBuffer, shift, aiBuffer, 0, SoundAi.WINDOW - shift)
                for (i in 0 until shift) aiBuffer[SoundAi.WINDOW - shift + i] = chunk[chunk.size - shift + i] / 32768f
                aiFilled = minOf(SoundAi.WINDOW, aiFilled + shift)
                chunkIndex++
                val worthChecking = db >= SOUND_MIN_DB && db > ambient + 6
                if (aiFilled == SoundAi.WINDOW && chunkIndex % 5 == 0) {
                    if (worthChecking) {
                        try {
                            val (label, score) = SoundAi.classify(ai, aiBuffer.copyOf())
                            aiLabel = label
                            aiShout = score
                        } catch (e: Exception) { /* 한 번 실패해도 계속 */ }
                    } else {
                        aiLabel = "조용함"
                        aiShout = 0f
                    }
                }
            }

            // ── ① 욕설·위협: 최근 30초 동안 몇 번 들렸나 ──
            spotter.feed(chunk)
            val nowMs = System.currentTimeMillis()
            spotter.takeHits().forEach { wordLog.addLast(nowMs to it) }
            while (wordLog.isNotEmpty() && nowMs - wordLog.first().first > WORD_WINDOW_SECONDS * 1000L) wordLog.removeFirst()
            val swearCount = wordLog.count { !it.second.threat && !it.second.warn }
            val warnCount = wordLog.count { it.second.warn }
            val warnPart = if (warnCount > 0) WARN_SCORE else 0.0
            val threatCount = wordLog.count { it.second.threat }
            val swearPart = SWEAR_SCORE[swearCount.coerceAtMost(SWEAR_SCORE.size - 1)]
            val threatPart = THREAT_SCORE[threatCount.coerceAtMost(THREAT_SCORE.size - 1)]
            val bonus = if (swearCount > 0 && threatCount > 0) SWEAR_AND_THREAT_BONUS else 0.0
            val listScore = clamp01(maxOf(swearPart, threatPart, warnPart) + bonus)

            // ── ①-2 문장 AI: 들은 문장마다 공격 확률 계산 ──
            spotter.takeSentences().forEach { h ->
                val p = if (textOn) TextAbuseModel.score(h.text) else 0.0
                lastAiProb = if (textOn) p else -1.0
                val e = sentLog[h.id]
                if (e == null) sentLog[h.id] = Sent(nowMs, p, h.text)
                else {
                    e.time = nowMs
                    if (p >= e.prob) { e.prob = p; e.text = h.text }   // 같은 문장은 가장 높게 나온 값 기억
                }
            }
            sentLog.entries.removeAll { nowMs - it.value.time > WORD_WINDOW_SECONDS * 1000L }
            val aggressive = sentLog.values.filter { it.prob >= TEXT_AI_THRESHOLD }
            val textCount = aggressive.size
            val worst = aggressive.maxByOrNull { it.prob }
            val textScore = if (worst == null) 0.0
            else TEXT_SCORE_MAX * (0.6 + 0.4 * worst.prob) * TEXT_COUNT_SCORE[textCount.coerceAtMost(TEXT_COUNT_SCORE.size - 1)]

            // 언어 점수 = 단어 목록과 문장 AI 중 높은 쪽
            val wordScore = maxOf(listScore, textScore)

            // ── ② 고함: 크기 50% + AI 고함 50% ──
            val shoutScore = if (ai != null) (loudScore + aiShout) / 2 else loudScore
            heldShout = maxOf(shoutScore, heldShout - decay)

            // ── ③ 지속성: 최근 30초 중 거친 소리였던 비율 ──
            val rough = loudScore >= 0.6 || aiShout >= 0.4   // "거친 소리" 기준 (높일수록 덜 민감)
            roughLog.addLast(rough)
            if (rough) roughCount++
            while (roughLog.size > SUSTAIN_WINDOW_SECONDS * 10) if (roughLog.removeFirst()) roughCount--
            val sustainScore = clamp01(roughCount.toDouble() / (SUSTAIN_WINDOW_SECONDS * 10) / SUSTAIN_FULL)

            // ── ④ 심박: 10초 안에 얼마나 급하게 올랐나 ──
            val heartScore = clamp01(now.hrRise / HR_FULL_RISE)
            heldHeart = maxOf(heartScore, heldHeart - decay)

            // ── 최종 위험 점수 ──
            val score = W_WORD * wordScore + W_SHOUT * heldShout + W_SUSTAIN * sustainScore + W_HEART * heldHeart

            // 긴급 버튼
            val manualNow = AudioState.manualRequest.value
            if (manualNow) AudioState.manualRequest.value = false

            val a = active
            if (a == null) {
                // 아직 녹음 전: 최근 PRE_SECONDS 초만 기억
                preBuffer.addLast(chunk)
                while (preBuffer.size > PRE_SECONDS * 10) preBuffer.removeFirst()
                highChunks = if (score >= RECORD_SCORE) highChunks + 1 else 0

                if (highChunks >= RECORD_HOLD_CHUNKS || manualNow) {
                    // 녹음 시작! 기억해둔 "이전 상황"부터 먼저 파일에 씀
                    val start = System.currentTimeMillis()
                    val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(start))
                    val writer = WavWriter(RecordingStore.wav(context, name), SAMPLE_RATE)
                    preBuffer.forEach { writer.write(it) }
                    preBuffer.clear()
                    val words = wordLog.groupingBy { it.second.word }.eachCount()
                        .entries.joinToString(" ") { "${it.key}×${it.value}" }
                    val reason = if (manualNow) "긴급 버튼" else
                        "점수 %d (언어 %d·고함 %d·지속 %d·심박 %d)".format(
                            (score * 100).toInt(), (wordScore * 100).toInt(), (heldShout * 100).toInt(),
                            (sustainScore * 100).toInt(), (heldHeart * 100).toInt()
                        ) + (if (words.isNotEmpty()) " $words" else "") +
                                (worst?.let { " · AI ${textCount}문장 '${it.text.take(20)}' ${(it.prob * 100).toInt()}%" } ?: "")
                    active = ActiveRecording(
                        writer = writer, name = name, start = start, trigger = reason,
                        heartRate = now.heartRate, maxDb = db
                    )
                    lowChunks = 0
                    recordedChunks = 0
                    minChunks = if (manualNow) MANUAL_MIN_SECONDS * 10 else 0
                    vibrate(context)
                    PhoneLink.sendAlert(context, reason)   // 폰에 "녹음 시작" 알림
                }
            } else {
                if (manualNow) minChunks = recordedChunks + MANUAL_MIN_SECONDS * 10   // 녹음 중 긴급 버튼 → 연장
                // 녹음 중: 계속 쓰고, 점수가 오래 낮거나 너무 길면 멈춤
                a.writer.write(chunk)
                a.maxDb = maxOf(a.maxDb, db)
                recordedChunks++
                lowChunks = if (score < STOP_SCORE) lowChunks + 1 else 0
                val calmEnough = lowChunks >= QUIET_STOP_SECONDS * 10 && recordedChunks >= minChunks
                if (calmEnough || recordedChunks >= MAX_RECORD_SECONDS * 10) {
                    RecordingStore.finish(context, a)   // 파일 닫기 + 지문 계산 + 정보 저장 + 폰 백업
                    active = null
                    savedCount++
                    highChunks = 0
                }
            }

            AudioState.status.update {
                it.copy(
                    score = score, wordScore = wordScore, shoutScore = heldShout, sustainScore = sustainScore,
                    heartScore = heldHeart, swearCount = swearCount, threatCount = threatCount, warnCount = warnCount,
                    textCount = textCount, lastAiProb = lastAiProb,
                    db = db, ambientDb = ambient, aiLabel = aiLabel, aiShout = aiShout,
                    keywordInfo = spotter.info, recording = active != null,
                    manual = active?.trigger == "긴급 버튼", savedCount = savedCount
                )
            }
        }
    } finally {
        // 근무 모드를 끄면 녹음 중이던 파일도 마무리해서 저장
        active?.let { RecordingStore.finish(context, it) }
        recorder.stop()
        recorder.release()
        withContext(NonCancellable + Dispatchers.Main) { spotter.stop() }
    }
}

// 0.1초 분량 소리의 크기(dB) 계산
fun calcDb(chunk: ShortArray): Double {
    var sum = 0.0
    for (s in chunk) {
        val v = s.toDouble()
        sum += v * v
    }
    val rms = sqrt(sum / chunk.size)
    return if (rms < 1.0) -90.0 else 20 * log10(rms / 32768.0)
}

// 녹음 시작 알림 진동
fun vibrate(context: Context) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
}

// ───────── WAV 파일 쓰기 ─────────
// 맨 앞 44바이트 = "이 파일은 16kHz, 모노, 16비트" 라는 정보(헤더), 그 뒤 = 소리 데이터
class WavWriter(val file: File, private val sampleRate: Int) {
    private val out = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    init {
        out.setLength(0)
        out.write(ByteArray(44))   // 헤더 자리 비워두기 (길이를 끝나고 알 수 있어서)
    }

    fun write(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        out.write(bytes)
        dataBytes += bytes.size
    }

    fun close() {
        if (closed) return
        closed = true
        out.seek(0)
        out.write(header())
        out.close()
    }

    private fun header(): ByteArray {
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt((36 + dataBytes).toInt())
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)                  // fmt 블록 크기
        buf.putShort(1.toShort())       // PCM
        buf.putShort(1.toShort())       // 모노
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)      // 1초당 바이트 수
        buf.putShort(2.toShort())       // 샘플 1개당 바이트 수
        buf.putShort(16.toShort())      // 16비트
        buf.put("data".toByteArray())
        buf.putInt(dataBytes.toInt())
        return buf.array()
    }
}