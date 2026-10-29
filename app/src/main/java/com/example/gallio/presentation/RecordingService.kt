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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
// [1단계] 움직임
const val MOTION_SAMPLES = 50        // 최근 몇 개의 가속도 값으로 판단할지 (약 1초 분량)
const val MOTION_LIMIT = 3.0         // 흔들림이 이 값보다 크면 "격한 움직임"

// [1단계] 심박
const val HR_JUMP_BPM = 20           // HR_JUMP_SECONDS 초 안에 이만큼 오르면 "심박 급상승"
const val HR_JUMP_SECONDS = 10       //   (러닝처럼 서서히 오르는 건 무시하기 위해 "짧은 시간" 기준)

const val SKIP_STAGE1 = false        // true 로 바꾸면 1단계 없이 바로 소리 감시 (소리만 테스트할 때)
const val COOLDOWN_SECONDS = 30      // 2단계에서 아무 일 없었으면 이 시간 동안 1단계 쉬기 (운동 중 반복 방지)

// [2단계] 소리
const val MIC_WINDOW_SECONDS = 15    // 1단계 통과 후 마이크를 몇 초 동안 켤지
const val SAMPLE_RATE = 16000        // 1초에 소리를 16,000번 측정
const val SHOUT_DB = -20.0           // 최소 기준: 이 값보다 커야 고함 후보
const val SHOUT_RISE_DB = 15.0       // 그리고 주변 소음보다 이만큼 더 커야 고함 (헬스장·클럽 음악 대비)
const val SHOUT_CHUNKS = 10          // (AI 없을 때) 큰 소리가 0.1초 x 10 = 1초 이상 이어지면 "고함"

// [2단계] AI 소리 분류 (YAMNet)
const val USE_AI = true              // false 로 바꾸면 소리 크기만으로 판단 (예전 방식)
const val AI_SHOUT_SCORE = 0.3f      // "고함류" 점수가 이 값 이상이면 AI가 고함이라고 판단 (0~1)
const val AI_LOUD_CHUNKS = 3         // AI 판단 + 큰 소리가 0.3초 이상이면 고함 (멀리서 들리는 TV 고함 제외)

// [녹음]
const val PRE_SECONDS = 5            // 고함 감지 "이전" 몇 초까지 같이 저장할지
const val QUIET_STOP_SECONDS = 10    // 조용한 상태가 몇 초 이어지면 녹음을 멈출지
const val MAX_RECORD_SECONDS = 120   // 한 파일 최대 길이 (초)
const val MANUAL_MIN_SECONDS = 60    // 긴급 녹음 버튼을 누르면 최소 이 시간은 녹음
const val KEEP_DAYS = 7              // 이 기간이 지난 녹음은 자동 삭제 (사생활 보호)

// 화면에 보여줄 상태 묶음
data class AudioStatus(
    val stage: Int = 0,              // 0: 꺼짐, 1: 움직임·심박 감시, 2: 소리 확인
    val trigger: String = "",        // 2단계로 넘어간 이유: "움직임" / "심박" / "수동"
    val cooldownLeft: Int = 0,       // 쉬는 중이면 남은 초
    val motion: Double = 0.0,        // 현재 흔들림 정도
    val heartRate: Int = 0,          // 현재 심박
    val hrRise: Int = 0,             // 최근 HR_JUMP_SECONDS 초 동안 오른 심박
    val micSecondsLeft: Int = 0,     // 2단계에서 마이크가 꺼지기까지 남은 초
    val db: Double = -90.0,          // 현재 소리 크기
    val ambientDb: Double = -90.0,   // 주변 소음 크기
    val shouting: Boolean = false,
    val recording: Boolean = false,
    val savedCount: Int = 0,
    val aiLabel: String = "",        // AI가 들은 소리 중 1위 (예: "말소리 82%")
    val aiShout: Float = 0f,         // AI가 판단한 "고함류" 점수 (0~1)
    val keywordInfo: String = ""     // 단어 인식 상태 (예: "단어 인식: 켜짐", "들림: ...")
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
    private var motion: MotionDetector? = null
    private var heart: HeartRateDetector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()   // 알림창에 "외출 모드 켜짐" 표시 (이게 있어야 뒤에서 계속 돌 수 있음)

        if (intent?.action == ACTION_MANUAL) AudioState.manualRequest.value = true

        if (job == null) {
            RecordingStore.deleteOld(applicationContext)

            val m = MotionDetector(applicationContext)
            val h = HeartRateDetector(applicationContext)
            motion = m
            heart = h
            AudioState.running.value = true
            AudioState.status.value = AudioStatus(stage = 1, savedCount = RecordingStore.count(applicationContext))

            job = scope.launch {
                m.start()
                h.start()
                try {
                    while (isActive) {
                        // ── 1단계: 마이크 꺼둔 채 움직임·심박만 감시 ──
                        AudioState.status.update { it.copy(stage = 1, trigger = "") }
                        val reason = if (SKIP_STAGE1) "테스트" else waitForTrigger(m, h)
                        val manual = reason == "수동"
                        if (manual) AudioState.manualRequest.value = false

                        // ── 2단계: 마이크를 잠깐 켜고 소리 확인 (고함이면 녹음) ──
                        AudioState.status.update { it.copy(stage = 2, trigger = reason) }
                        val recorded = listenWindow(applicationContext, startRecordingNow = manual, stageReason = reason)

                        // 끝나면 기록 초기화. 아무 일 없었으면 잠깐 쉬기 (운동 중 반복 방지)
                        m.reset()
                        h.reset()
                        if (!recorded && !SKIP_STAGE1) cooldown()
                    }
                } finally {
                    m.stop()
                    h.stop()
                }
            }
        }
        return START_NOT_STICKY
    }

    // 움직임 "또는" 심박 급상승 "또는" 긴급 버튼 중 하나라도 생길 때까지 기다림
    private suspend fun waitForTrigger(m: MotionDetector, h: HeartRateDetector): String =
        combine(m.shaking, h.jump, AudioState.manualRequest) { shake, jump, manual ->
            when {
                manual -> "수동"
                shake -> "움직임"
                jump -> "심박"
                else -> null
            }
        }.filterNotNull().first()

    private suspend fun cooldown() {
        for (left in COOLDOWN_SECONDS downTo 1) {
            if (AudioState.manualRequest.value) break   // 긴급 버튼은 쉬는 중에도 바로 동작
            AudioState.status.update { it.copy(stage = 1, cooldownLeft = left) }
            delay(1000)
        }
        AudioState.status.update { it.copy(cooldownLeft = 0) }
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        motion?.stop()
        heart?.stop()
        motion = null
        heart = null
        scope.cancel()
        AudioState.running.value = false
        AudioState.manualRequest.value = false
        AudioState.status.update {
            AudioStatus(savedCount = it.savedCount)
        }
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "외출 모드", NotificationManager.IMPORTANCE_LOW)
        )

        // 알림을 누르면 앱 화면 열기
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("외출 모드 켜짐")
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

// ───────── [1단계] 가속도로 격한 움직임 감지 ─────────
class MotionDetector(context: Context) : SensorEventListener {
    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val window = ArrayDeque<FloatArray>()   // 최근 가속도 값들
    val shaking = MutableStateFlow(false)           // 지금 격하게 움직이는 중인지

    fun start() {
        val sm = sensorManager ?: return
        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)   // 초당 약 50번
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun reset() {
        synchronized(window) { window.clear() }
        shaking.value = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        var level = 0.0
        synchronized(window) {
            window.addLast(floatArrayOf(event.values[0], event.values[1], event.values[2]))
            while (window.size > MOTION_SAMPLES) window.removeFirst()
            if (window.size < MOTION_SAMPLES / 2) return   // 값이 아직 덜 모였으면 판단 보류
            level = shakeLevel(window)
        }
        AudioState.status.update { it.copy(motion = level) }
        shaking.value = level > MOTION_LIMIT
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// 흔들림 정도 = x, y, z 값이 평균에서 얼마나 들쭉날쭉한지 (표준편차)
fun shakeLevel(samples: Collection<FloatArray>): Double {
    val n = samples.size
    var mx = 0.0
    var my = 0.0
    var mz = 0.0
    for (s in samples) {
        mx += s[0]; my += s[1]; mz += s[2]
    }
    mx /= n; my /= n; mz /= n

    var sum = 0.0
    for (s in samples) {
        val dx = s[0] - mx
        val dy = s[1] - my
        val dz = s[2] - mz
        sum += dx * dx + dy * dy + dz * dz
    }
    return sqrt(sum / n)
}

// ───────── [1단계] 심박 급상승 감지 ─────────
// TODO 워치 오면: 삼성 헬스 센서 SDK 로 바꾸면 더 정확함 (지금은 안드로이드 기본 심박 센서)
class HeartRateDetector(context: Context) : SensorEventListener {
    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val history = ArrayDeque<Pair<Long, Float>>()   // (시각, 심박) 최근 기록
    val jump = MutableStateFlow(false)                      // 지금 심박이 급상승 중인지

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

    fun reset() {
        synchronized(history) { history.clear() }
        jump.value = false
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
        jump.value = rise >= HR_JUMP_BPM
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// ───────── [2단계] AI 소리 분류 (YAMNet) ─────────
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

// ───────── [2단계] 마이크를 잠깐 켜서 소리 확인 + 고함이면 녹음 ─────────
// 반환값: 이번에 녹음을 한 번이라도 했는지
@SuppressLint("MissingPermission")
suspend fun listenWindow(context: Context, startRecordingNow: Boolean, stageReason: String): Boolean = withContext(Dispatchers.IO) {
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
        return@withContext false
    }

    var savedCount = RecordingStore.count(context)

    val ai = SoundAi.get(context)               // AI 모델 (없으면 null → 소리 크기로만 판단)
    val aiBuffer = FloatArray(SoundAi.WINDOW)   // AI 에게 보여줄 최근 약 1초 소리
    var aiFilled = 0
    var aiLabel = ""
    var aiShout = 0f
    var chunkIndex = 0

    // 단어 인식 (욕설·이름). 기기가 지원 안 하면 알아서 꺼진 채로 있음
    val spotter = KeywordSpotter(context)
    withContext(Dispatchers.Main) { spotter.start() }

    val preBuffer = ArrayDeque<ShortArray>()   // 최근 몇 초 소리를 계속 들고 있는 "링 버퍼"
    val recentDb = ArrayDeque<Double>()        // 최근 10초 소리 크기 (주변 소음 계산용)
    var active: ActiveRecording? = null        // 지금 녹음 중인 파일
    var recordedAny = false
    var pendingManual = startRecordingNow
    var loudStreak = 0
    var quietChunks = 0
    var recordedChunks = 0
    var minChunks = 0                          // 긴급 녹음이면 최소 녹음 길이
    var idleChunks = 0                         // 녹음 안 하고 듣기만 한 시간 (0.1초 단위)

    recorder.startRecording()
    try {
        while (isActive) {
            val buffer = ShortArray(chunkSize)
            val n = recorder.read(buffer, 0, chunkSize)
            if (n <= 0) continue
            val chunk = buffer.copyOf(n)

            // 긴급 버튼이 2단계 도중에 눌렸는지
            if (AudioState.manualRequest.value) {
                AudioState.manualRequest.value = false
                pendingManual = true
            }

            // 주변 소음 = 최근 10초 소리 중 조용한 쪽(하위 20%) 크기
            val db = calcDb(chunk)
            recentDb.addLast(db)
            while (recentDb.size > 100) recentDb.removeFirst()
            val ambient = if (recentDb.size >= 10) recentDb.sorted()[recentDb.size / 5] else -90.0

            // 큰 소리 = 최소 기준보다 크고 + 주변보다 갑자기 확 커진 소리
            val loud = db > SHOUT_DB && (recentDb.size < 10 || db > ambient + SHOUT_RISE_DB)
            loudStreak = if (loud) loudStreak + 1 else 0

            // AI: 최근 약 1초 소리를 0.5초마다 들려주고 "무슨 소리인지" 물어봄
            if (ai != null) {
                val shift = chunk.size.coerceAtMost(SoundAi.WINDOW)
                System.arraycopy(aiBuffer, shift, aiBuffer, 0, SoundAi.WINDOW - shift)
                for (i in 0 until shift) aiBuffer[SoundAi.WINDOW - shift + i] = chunk[chunk.size - shift + i] / 32768f
                aiFilled = minOf(SoundAi.WINDOW, aiFilled + shift)
                chunkIndex++
                if (aiFilled == SoundAi.WINDOW && chunkIndex % 5 == 0) {
                    try {
                        val (label, score) = SoundAi.classify(ai, aiBuffer.copyOf())
                        aiLabel = label
                        aiShout = score
                    } catch (e: Exception) { /* 한 번 실패해도 계속 진행 */ }
                }
            }

            // 단어 인식에도 같은 소리를 넘겨줌 (마이크를 두 번 켜지 않기 위해)
            spotter.feed(chunk)
            val word = spotter.heardWord   // 욕설이나 이름이 들렸으면 그 단어

            // 고함 판단
            //  - AI 있음: AI가 "고함/비명"이라고 하고 + 실제로 큰 소리가 0.3초 이상
            //  - AI 없음: 큰 소리가 1초 이상 (예전 방식)
            val aiOrLoud = if (ai != null) aiShout >= AI_SHOUT_SCORE && loudStreak >= AI_LOUD_CHUNKS
            else loudStreak >= SHOUT_CHUNKS
            // 최종: 고함 "또는" 욕설·이름이 들리면 위험
            val shouting = aiOrLoud || word != null

            val a = active
            if (a == null) {
                idleChunks++
                // 아직 녹음 전: 최근 PRE_SECONDS 초만 기억
                preBuffer.addLast(chunk)
                while (preBuffer.size > PRE_SECONDS * 10) preBuffer.removeFirst()

                if (shouting || pendingManual) {
                    // 녹음 시작! 기억해둔 "이전 상황"부터 먼저 파일에 씀
                    val start = System.currentTimeMillis()
                    val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(start))
                    val writer = WavWriter(RecordingStore.wav(context, name), SAMPLE_RATE)
                    preBuffer.forEach { writer.write(it) }
                    preBuffer.clear()
                    active = ActiveRecording(
                        writer = writer,
                        name = name,
                        start = start,
                        trigger = when {
                            pendingManual -> "긴급 버튼"
                            word != null -> "$stageReason → 단어 '$word'"
                            ai != null -> "$stageReason → AI 고함"
                            else -> "$stageReason → 고함"
                        },
                        heartRate = AudioState.status.value.heartRate,
                        maxDb = db
                    )
                    recordedAny = true
                    quietChunks = 0
                    recordedChunks = 0
                    minChunks = if (pendingManual) MANUAL_MIN_SECONDS * 10 else 0
                    pendingManual = false
                    spotter.clearHeard()
                    vibrate(context)
                } else if (idleChunks >= MIC_WINDOW_SECONDS * 10) {
                    break   // 고함 없이 시간이 다 됨 → 마이크 끄고 1단계로
                }
            } else {
                if (pendingManual) {   // 녹음 중에 긴급 버튼 → 최소 길이 연장
                    minChunks = recordedChunks + MANUAL_MIN_SECONDS * 10
                    pendingManual = false
                }
                // 녹음 중: 계속 파일에 쓰고, 오래 조용하거나 너무 길면 멈춤
                a.writer.write(chunk)
                a.maxDb = maxOf(a.maxDb, db)
                recordedChunks++
                // 욕설·이름이 또 들리면 "아직 조용하지 않음"으로 보고 녹음 계속
                quietChunks = if (loud || word != null) 0 else quietChunks + 1
                if (word != null) spotter.clearHeard()
                val quietEnough = quietChunks >= QUIET_STOP_SECONDS * 10 && recordedChunks >= minChunks
                if (quietEnough || recordedChunks >= MAX_RECORD_SECONDS * 10) {
                    RecordingStore.finish(context, a)   // 파일 닫기 + 지문 계산 + 정보 저장
                    active = null
                    savedCount++
                    idleChunks = 0   // 녹음이 끝나면 한 번 더 MIC_WINDOW_SECONDS 초 동안 들어봄
                }
            }

            val left = if (active == null) MIC_WINDOW_SECONDS - idleChunks / 10 else 0
            AudioState.status.update {
                it.copy(
                    db = db, ambientDb = ambient, shouting = shouting, recording = active != null,
                    savedCount = savedCount, micSecondsLeft = left, aiLabel = aiLabel, aiShout = aiShout,
                    keywordInfo = spotter.info
                )
            }
        }
    } finally {
        // 외출 모드를 끄면 녹음 중이던 파일도 마무리해서 저장
        active?.let { RecordingStore.finish(context, it) }
        recorder.stop()
        recorder.release()
        withContext(NonCancellable + Dispatchers.Main) { spotter.stop() }
        AudioState.status.update {
            it.copy(
                db = -90.0, ambientDb = -90.0, shouting = false, recording = false, micSecondsLeft = 0,
                savedCount = RecordingStore.count(context), aiLabel = "", aiShout = 0f, keywordInfo = ""
            )
        }
    }
    recordedAny
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