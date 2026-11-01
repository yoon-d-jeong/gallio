package com.example.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 워치 앱과 같은 단어 목록 (테스트 화면용)
val KEYWORDS = listOf(
    "시발", "씨발", "시바", "씨바", "씨팔", "병신", "개새끼", "좆", "지랄", "미친놈", "미친년",
    "닥쳐", "꺼져", "뒤질", "또라이",
    "죽여", "죽을래", "죽고싶", "가만안둬", "가만안둔다", "찾아간다", "찾아갈", "패버", "때려", "때릴",
    "맞고싶", "칼", "신상털"
)

// ───────── 폰 앱: 워치 녹음 백업·보호자 알림 설정 + 한국어 인식 테스트 ─────────
class MainActivity : ComponentActivity() {

    private val support = mutableStateOf("확인 전")      // 지원 여부
    private val heard = mutableStateOf("")               // 알아들은 말
    private val hit = mutableStateOf("")                 // 감지된 단어
    private val listening = mutableStateOf(false)
    private var recognizer: SpeechRecognizer? = null

    private val guardian = mutableStateOf("")            // 보호자 번호
    private val autoSms = mutableStateOf(false)          // 녹음 시작 시 보호자 자동 문자
    private val backups = mutableStateOf(listOf<BackupInfo>())
    private val playing = mutableStateOf<String?>(null)
    private val keepLink = mutableStateOf(true)          // 워치 연결 유지
    private val linkText = mutableStateOf("")            // 연결 상태 표시
    private val batteryOk = mutableStateOf(false)        // 배터리 "제한 없음" 여부
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val ago = (System.currentTimeMillis() - LinkState.lastTime) / 1000
            linkText.value = when {
                LinkState.relays > 0 && ago <= 3 -> "✅ 워치 소리 받는 중"
                LinkState.relays > 0 -> "🟡 연결됨 · 소리 기다리는 중"
                else -> "⚪ 대기 중 (워치에서 근무 시작을 누르면 연결돼요)"
            } + if (LinkState.lastText.isNotEmpty()) "\n들은 말: " + LinkState.lastText.takeLast(30) else ""
            ticker.postDelayed(this, 1000)
        }
    }
    private var player: MediaPlayer? = null

    private fun koreanIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkSupport()
        guardian.value = Settings.guardian(this)
        autoSms.value = Settings.autoSms(this)
        keepLink.value = Settings.keepLink(this)
        if (keepLink.value) KeepAliveService.start(this)   // 앱을 열 때 연결 유지 켜기
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        backups.value = Backup.list(this)   // 앱 화면 볼 때마다 새 백업 반영
        batteryOk.value = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    // 배터리 "제한 없음" 요청 창 (삼성이 앱을 재우지 않게)
    private fun askBattery() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // 필요한 권한 한 번에 요청 (알림, 마이크, 문자, 위치)
    private fun askPermissions() {
        requestPermissions(arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ), 2)
    }

    // 앱이 꺼져 있을 때도 위치를 쓰려면 "항상 허용"이 따로 필요 (위치 권한을 먼저 받은 뒤에만 요청 가능)
    private fun askBackgroundLocation() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3)
    }

    private fun play(name: String) {
        stopPlay()
        try {
            player = MediaPlayer().apply {
                setDataSource(File(Backup.dir(this@MainActivity), "$name.wav").absolutePath)
                setOnCompletionListener { stopPlay() }
                prepare()
                start()
            }
            playing.value = name
        } catch (e: Exception) { stopPlay() }
    }

    private fun stopPlay() {
        player?.release()
        player = null
        playing.value = null
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(30.dp))
            Txt("Gallio 폰", 24, Color.White, bold = true)

            // ── 워치 연결 ──
            Txt("워치 연결", 18, Color.White, bold = true)
            Txt(linkText.value, 15, Color(0xFF90CAF9))
            Btn(
                if (keepLink.value) "✅ 연결 유지: 켜짐 (근무 중엔 켜두세요)" else "⬜ 연결 유지: 꺼짐",
                if (keepLink.value) Color(0xFF1565C0) else Color(0xFF424242)
            ) {
                keepLink.value = !keepLink.value
                Settings.setKeepLink(this@MainActivity, keepLink.value)
                if (keepLink.value) KeepAliveService.start(this@MainActivity) else KeepAliveService.stop(this@MainActivity)
            }
            Btn(
                if (batteryOk.value) "✅ 배터리 제한 없음 (앱이 잠들지 않아요)" else "⚠ 배터리 제한 풀기 (눌러서 '허용')",
                if (batteryOk.value) Color(0xFF2E7D32) else Color(0xFFB26A00)
            ) { askBattery() }

            // ── 보호자 알림 설정 ──
            Txt("보호자 알림", 18, Color.White, bold = true)
            Btn("① 권한 받기 (알림·문자·위치·마이크)", Color(0xFF424242)) { askPermissions() }
            Btn("② 위치 '항상 허용' (앱 꺼져 있을 때도 위치 전송)", Color(0xFF424242)) { askBackgroundLocation() }
            Txt("보호자 번호", 14, Color(0xFF9E9E9E))
            BasicTextField(
                value = guardian.value,
                onValueChange = {
                    guardian.value = it.filter { c -> c.isDigit() }
                    Settings.setGuardian(this@MainActivity, guardian.value)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF616161), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            )
            Btn(
                if (autoSms.value) "✅ 녹음 시작되면 보호자에게 위치 문자 보내기: 켜짐"
                else "⬜ 녹음 시작되면 보호자에게 위치 문자 보내기: 꺼짐",
                if (autoSms.value) Color(0xFF2E7D32) else Color(0xFF424242)
            ) {
                autoSms.value = !autoSms.value
                Settings.setAutoSms(this@MainActivity, autoSms.value)
            }
            Txt("※ 오작동일 수도 있다는 문구가 함께 가요. 원할 때만 켜세요.", 12, Color(0xFF757575))

            // ── 백업된 녹음 ──
            Spacer(Modifier.height(10.dp))
            Txt("워치에서 백업된 녹음 ${backups.value.size}개", 18, Color.White, bold = true)
            if (backups.value.isEmpty()) Txt("아직 없어요", 14, Color(0xFF9E9E9E))
            backups.value.forEach { b ->
                val time = SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA).format(Date(b.start))
                Btn(
                    (if (playing.value == b.name) "■ " else "▶ ") +
                            "$time · ${b.trigger} · ${b.durationSec}초" + if (b.verified) " · 원본✓" else "",
                    Color(0xFF263238)
                ) {
                    if (playing.value == b.name) stopPlay() else play(b.name)
                }
            }

            // ── 한국어 인식 테스트 ──
            Spacer(Modifier.height(10.dp))
            Txt("기기 안 한국어 음성인식 테스트", 18, Color.White, bold = true)
            Txt(support.value, 15, Color(0xFFFFCC80))

            Btn(if (listening.value) "■ 듣기 멈춤" else "🎤 듣기 시작", Color(0xFF1565C0)) {
                if (listening.value) stopListening() else startListening()
            }
            Btn("한국어 모델 받기 (지원 안 될 때)", Color(0xFF424242)) { downloadModel() }

            Txt("알아들은 말", 14, Color(0xFF9E9E9E))
            Txt(heard.value.ifEmpty { "-" }, 18, Color.White)

            Txt("감지된 단어", 14, Color(0xFF9E9E9E))
            Txt(hit.value.ifEmpty { "-" }, 22, if (hit.value.isEmpty()) Color.White else Color(0xFFE53935), bold = true)

            Txt("※ 비행기 모드(인터넷 끔)로 해도 글자가 뜨면 진짜 '기기 안' 인식이에요", 12, Color(0xFF757575))
        }
    }

    // ── 1. 이 폰이 기기 안 한국어 인식을 지원하는지 ──
    private fun checkSupport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            support.value = "안드로이드 13 미만이라 확인 불가"
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            support.value = "❌ 기기 안 음성인식 자체가 없음"
            return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        r.checkRecognitionSupport(koreanIntent(), ContextCompat.getMainExecutor(this),
            object : RecognitionSupportCallback {
                override fun onSupportResult(result: RecognitionSupport) {
                    val installed = result.installedOnDeviceLanguages.any { it.startsWith("ko") }
                    val downloadable = result.supportedOnDeviceLanguages.any { it.startsWith("ko") }
                    val pending = result.pendingOnDeviceLanguages.any { it.startsWith("ko") }
                    support.value = when {
                        installed -> "✅ 한국어 기기 안 인식 사용 가능"
                        pending -> "⏳ 한국어 모델 받는 중"
                        downloadable -> "⬇ 한국어 지원됨, 모델 받기 필요"
                        else -> "❌ 한국어 기기 안 인식 미지원"
                    }
                    r.destroy()
                }
                override fun onError(error: Int) {
                    support.value = "확인 실패 (오류 $error) · 그래도 듣기 시작 눌러보세요"
                    r.destroy()
                }
            })
    }

    private fun downloadModel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        r.triggerModelDownload(koreanIntent())
        support.value = "⏳ 다운로드 요청함 · 잠시 후 앱을 다시 켜서 확인"
    }

    // ── 2. 실제로 듣고 단어 찾기 ──
    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            support.value = "❌ 기기 안 음성인식 자체가 없음"
            return
        }
        hit.value = ""
        listening.value = true
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
            startListening(koreanIntent())
        }
    }

    private fun stopListening() {
        listening.value = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun check(b: Bundle?) {
        val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (text.isBlank()) return
        heard.value = text
        val word = KEYWORDS.firstOrNull { text.replace(" ", "").contains(it) }
        if (word != null) hit.value = "'$word' 감지!"
    }

    // 한 문장 끝나면 자동으로 다시 듣기
    private fun again() {
        if (listening.value) recognizer?.startListening(koreanIntent())
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) = check(partialResults)
        override fun onResults(results: Bundle?) { check(results); again() }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    support.value = "❌ 한국어 모델 없음 (모델 받기 눌러보세요)"
                    stopListening()
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> again()
                else -> {
                    support.value = "오류 $error"
                    stopListening()
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

    override fun onDestroy() {
        stopListening()
        stopPlay()
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
fun Txt(text: String, size: Int, color: Color, bold: Boolean = false) {
    BasicText(
        text = text,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    )
}

@androidx.compose.runtime.Composable
fun Btn(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(color)
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Txt(text, 16, Color.White, bold = true)
    }
}