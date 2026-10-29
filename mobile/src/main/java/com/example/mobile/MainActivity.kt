package com.example.mobile  // ← 자동으로 만들어진 원래 파일의 package 줄로 바꾸세요

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

// 워치 앱과 같은 단어 목록
val KEYWORDS = listOf(
    "시발", "씨발", "시바", "씨바", "병신", "개새끼", "좆", "죽여", "죽을래", "꺼져", "패버",
    "정지윤", "지윤"
)

// ───────── S26 에서 "기기 안 한국어 음성인식"이 되는지 확인하는 테스트 앱 ─────────
class MainActivity : ComponentActivity() {

    private val support = mutableStateOf("확인 전")      // 지원 여부
    private val heard = mutableStateOf("")               // 알아들은 말
    private val hit = mutableStateOf("")                 // 감지된 단어
    private val listening = mutableStateOf(false)
    private var recognizer: SpeechRecognizer? = null

    private fun koreanIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkSupport()
        setContent { Screen() }
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
            Txt("기기 안 한국어 음성인식 테스트", 20, Color.White, bold = true)
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