package com.example.gallio.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────── 색 (갤럭시 워치 기본 앱 느낌: 검은 배경 + 진회색 카드 + 파란 강조) ─────────
private val Bg = Color.Black
private val Card = Color(0xFF1C1C1E)
private val Track = Color(0xFF2C2C2E)
private val Blue = Color(0xFF3E91FF)
private val Amber = Color(0xFFFF9F0A)
private val Red = Color(0xFFFF453A)
private val TextMain = Color(0xFFF2F2F2)
private val TextSub = Color(0xFF9A9A9F)

@Composable
private fun T(
    text: String, size: TextUnit, color: Color = TextMain,
    weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Center
) = BasicText(
    text = text, modifier = modifier,
    style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align)
)

private const val WARN_LEVEL = 0.3f   // 30점부터 "주의"(주황)

// ───────── 앱 시작 지점 ─────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var screen by remember { mutableStateOf("main") }
            var selected by remember { mutableStateOf<String?>(null) }
            // 워치 뒤로가기(스와이프/버튼)로 이전 화면
            BackHandler(enabled = screen != "main") {
                screen = if (screen == "detail") "list" else "main"
            }
            when (screen) {
                "score" -> ScoreDetailScreen()
                "list" -> RecordingListScreen(onOpen = { selected = it; screen = "detail" })
                "detail" -> RecordingDetailScreen(name = selected ?: "", onBack = { screen = "list" })
                else -> SafeRecordScreen(onOpenList = { screen = "list" }, onOpenScore = { screen = "score" })
            }
        }
    }
}

// ───────── 메인 화면 ─────────
@Composable
fun SafeRecordScreen(onOpenList: () -> Unit, onOpenScore: () -> Unit) {
    val context = LocalContext.current
    val running by AudioState.running.collectAsState()
    val status by AudioState.status.collectAsState()

    fun startOuting(manual: Boolean) {
        val intent = Intent(context, RecordingService::class.java)
        if (manual) intent.action = RecordingService.ACTION_MANUAL
        ContextCompat.startForegroundService(context, intent)
    }
    fun stopOuting() = context.stopService(Intent(context, RecordingService::class.java))

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(heartRatePermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun allGranted() = permissions.all { granted(it) }

    var afterPermission by remember { mutableStateOf<Boolean?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val manual = afterPermission
        afterPermission = null
        if (manual != null && granted(Manifest.permission.RECORD_AUDIO)) startOuting(manual)
    }
    fun request(manual: Boolean) {
        if (allGranted()) startOuting(manual)
        else { afterPermission = manual; permissionLauncher.launch(permissions) }
    }

    // 녹음 시간 (mm:ss)
    var recStart by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.recording) {
        if (status.recording) {
            recStart = System.currentTimeMillis()
            while (true) { now = System.currentTimeMillis(); delay(500) }
        }
    }
    val recSec = ((now - recStart) / 1000).coerceAtLeast(0)

    val score = if (running) status.score.toFloat() else 0f
    val ratio = (score / RECORD_SCORE.toFloat()).coerceIn(0f, 1f)  // 녹음 기준까지 얼마나 찼나
    val ring by animateFloatAsState(if (status.recording) 1f else ratio, tween(400), label = "ring")
    val accent by animateColorAsState(
        when {
            !running -> Track
            status.recording -> Red
            score >= WARN_LEVEL -> Amber
            else -> Blue
        }, tween(400), label = "accent"
    )

    val title = when {
        !running -> "근무 모드"
        status.recording && status.manual -> "녹음 중"
        status.recording -> "녹음 중"
        score >= WARN_LEVEL -> "주의"
        else -> "보호 중"
    }
    val subtitle = when {
        !running -> "꺼져 있음"
        status.recording -> "%02d:%02d".format(recSec / 60, recSec % 60)
        score >= WARN_LEVEL -> "분위기가 험해지고 있어요"
        else -> "주변 소리를 듣고 있어요"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // 첫 화면: 링 + 상태 + 버튼 (워치 화면 한 장에 꽉 차게)
        item {
            Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                    val w = 7.dp.toPx()
                    val inset = w / 2
                    val sz = Size(size.width - w, size.height - w)
                    drawArc(Track, 135f, 270f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
                    if (ring > 0.01f)
                        drawArc(accent, 135f, 270f * ring, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(10.dp))
                    if (status.recording) RecDot()
                    // 가운데 글자를 눌러도 점수 화면으로
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpenScore() }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        T(title, 20.sp, weight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        T(subtitle, 12.sp, if (status.recording) Red else TextSub)
                        if (running) T("${(status.score * 100).toInt()}점", 11.sp, accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RoundIcon(Card, onClick = onOpenList) { ListIcon() }
                        RoundIcon(Card, onClick = onOpenScore) { ScoreIcon() }
                        RoundIcon(Color(0xFF3A1614), onClick = { request(manual = true) }) { MicIcon(Red) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Pill(
                        text = if (running) "근무 종료" else "근무 시작",
                        color = if (running) Card else Blue,
                        width = 116
                    ) { if (running) stopOuting() else request(manual = false) }
                }
            }
        }

        // 아래로 스크롤: 지금 상황 자세히
        if (running) {
            item { Spacer(Modifier.height(4.dp)); T("지금 상황", 13.sp, TextSub) ; Spacer(Modifier.height(6.dp)) }
            item {
                InfoCard {
                    Meter("말", status.wordScore.toFloat(), when {
                        status.swearCount + status.threatCount + status.warnCount == 0 -> "문제 없음"
                        else -> "욕·막말 ${status.swearCount} · 위협 ${status.threatCount}"
                    })
                    Meter("목소리 크기", status.shoutScore.toFloat(), "%.0f dB".format(status.db))
                    Meter("지속 시간", status.sustainScore.toFloat(),
                        if (status.sustainScore < 0.05) "조용함" else "계속되는 중")
                    Meter("심박", status.heartScore.toFloat(),
                        if (status.heartRate > 0) "${status.heartRate} bpm" else "측정 중")
                }
            }
            if (status.keywordInfo.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    T(status.keywordInfo.removePrefix("단어 인식: "), 11.sp, TextSub,
                        modifier = Modifier.width(170.dp))
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            T("저장된 녹음 ${status.savedCount}개", 11.sp, TextSub)
        }
    }
}

// ───────── 녹음 목록 ─────────
@Composable
fun RecordingListScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val status by AudioState.status.collectAsState()
    val recordings = remember(status.savedCount) { RecordingStore.list(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 30.dp, bottom = 40.dp)
    ) {
        item { T("녹음", 16.sp, weight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)) }
        if (recordings.isEmpty()) {
            item { Spacer(Modifier.height(30.dp)); T("녹음이 없어요", 13.sp, TextSub) }
        }
        items(recordings) { r ->
            val manual = r.trigger == "긴급 버튼"
            Row(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .width(176.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Card)
                    .clickable { onOpen(r.name) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (manual) Red else Amber))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T(formatTime(r.start), 13.sp, align = TextAlign.Start)
                    T((if (manual) "직접 녹음" else "자동 감지") + " · " + formatDur(r.durationSec),
                        11.sp, TextSub, align = TextAlign.Start)
                }
                if (r.keep) T("★", 12.sp, Amber)
            }
        }
    }
}

// ───────── 녹음 상세 ─────────
@Composable
fun RecordingDetailScreen(name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember(name) { mutableStateOf(RecordingStore.load(context, name)) }
    var playing by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }

    fun stopPlay() { player[0]?.release(); player[0] = null; playing = false }
    DisposableEffect(name) { onDispose { stopPlay() } }

    val manual = info.trigger == "긴급 버튼"

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 30.dp, bottom = 44.dp)
    ) {
        item {
            T(formatTime(info.start), 14.sp, weight = FontWeight.SemiBold)
            T((if (manual) "직접 녹음" else "자동 감지") + " · " + formatDur(info.durationSec), 11.sp, TextSub)
            Spacer(Modifier.height(10.dp))
        }
        // 큰 재생 버튼
        item {
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(Blue).clickable {
                    if (playing) stopPlay() else try {
                        val mp = MediaPlayer()
                        mp.setDataSource(RecordingStore.wav(context, name).absolutePath)
                        mp.setOnCompletionListener { stopPlay() }
                        mp.prepare(); mp.start()
                        player[0] = mp; playing = true
                    } catch (e: Exception) { stopPlay() }
                },
                contentAlignment = Alignment.Center
            ) { if (playing) StopIcon() else PlayIcon() }
            Spacer(Modifier.height(12.dp))
        }
        item {
            InfoCard {
                InfoLine("최대 소리", "%.0f dB".format(info.maxDb))
                InfoLine("심박", if (info.heartRate > 0) "${info.heartRate} bpm" else "-")
                InfoLine("백업", "휴대폰으로 전송")
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            if (!manual && info.trigger.isNotEmpty()) {
                T("감지 이유", 11.sp, TextSub)
                T(info.trigger, 11.sp, modifier = Modifier.width(176.dp).padding(bottom = 8.dp))
            }
            Pill(if (info.keep) "보관 해제" else "보관하기", Card) {
                RecordingStore.setKeep(context, name, !info.keep)
                info = RecordingStore.load(context, name)
            }
        }
        item {
            Pill("원본 확인", Card) {
                verify = when {
                    info.sha256.isEmpty() -> "확인 정보가 없는 녹음이에요"
                    RecordingStore.sha256(RecordingStore.wav(context, name)) == info.sha256 -> "녹음 후 바뀐 적 없어요"
                    else -> "파일이 바뀌었어요"
                }
            }
        }
        verify?.let { item { T(it, 11.sp, TextSub); Spacer(Modifier.height(2.dp)) } }
        item {
            Pill(if (askDelete) "한 번 더 눌러 삭제" else "삭제", if (askDelete) Red else Card,
                textColor = if (askDelete) Color.White else Red) {
                if (askDelete) {
                    stopPlay()
                    RecordingStore.delete(context, name)
                    AudioState.status.value = AudioState.status.value.copy(savedCount = RecordingStore.count(context))
                    onBack()
                } else askDelete = true
            }
        }
    }
}

// ───────── 점수 자세히 (가운데 글자나 막대 아이콘을 누르면) ─────────
@Composable
fun ScoreDetailScreen() {
    val running by AudioState.running.collectAsState()
    val st by AudioState.status.collectAsState()
    fun pts(w: Double, v: Double) = (w * v * 100).roundToInt()
    val total = if (running) (st.score * 100).roundToInt() else 0
    val limit = (RECORD_SCORE * 100).roundToInt()
    val color = when {
        st.recording || total >= limit -> Red
        total >= (WARN_LEVEL * 100).roundToInt() -> Amber
        else -> Blue
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 28.dp, bottom = 44.dp)
    ) {
        item {
            T("위험 점수", 12.sp, TextSub)
            Row(verticalAlignment = Alignment.Bottom) {
                T("$total", 44.sp, color, FontWeight.Bold)
                T(" / 100", 13.sp, TextSub, modifier = Modifier.padding(bottom = 8.dp))
            }
            T(
                when {
                    !running -> "근무 모드가 꺼져 있어요"
                    st.recording -> "녹음 중"
                    else -> "${limit}점이 넘으면 녹음해요"
                }, 11.sp, if (st.recording) Red else TextSub
            )
            Spacer(Modifier.height(10.dp))
        }
        item {
            InfoCard {
                PointRow("언어", pts(W_WORD, st.wordScore), pts(W_WORD, 1.0), st.wordScore,
                    "욕·막말 ${st.swearCount} · 위협 ${st.threatCount}" +
                            (if (st.warnCount > 0) " · 불만 ${st.warnCount}" else "") +
                            if (st.lastAiProb >= 0) " · 문장 ${(st.lastAiProb * 100).roundToInt()}%" else "")
                PointRow("고함", pts(W_SHOUT, st.shoutScore), pts(W_SHOUT, 1.0), st.shoutScore,
                    "%.0f dB".format(st.db) + if (st.aiLabel.isNotEmpty()) " · ${st.aiLabel}" else "")
                PointRow("지속", pts(W_SUSTAIN, st.sustainScore), pts(W_SUSTAIN, 1.0), st.sustainScore,
                    "거친 소리 ${(st.sustainScore * SUSTAIN_FULL * 100).roundToInt()}%")
                PointRow("심박", pts(W_HEART, st.heartScore), pts(W_HEART, 1.0), st.heartScore,
                    if (st.heartRate > 0) "${st.heartRate} bpm (+${st.hrRise})" else "측정 중")
            }
        }
        if (st.keywordInfo.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                InfoCard {
                    T("들린 말", 11.sp, TextSub, align = TextAlign.Start)
                    Spacer(Modifier.height(2.dp))
                    T(st.keywordInfo.removePrefix("폰: ").removePrefix("단어 인식: "), 13.sp, align = TextAlign.Start)
                }
            }
        }
    }
}

// "언어  33 / 65점" + 막대 + 설명 한 줄
@Composable
fun PointRow(name: String, point: Int, max: Int, value: Double, detail: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            T(name, 13.sp, weight = FontWeight.Medium, align = TextAlign.Start, modifier = Modifier.weight(1f))
            T("$point", 15.sp, weight = FontWeight.Bold)
            T(" / ${max}점", 11.sp, TextSub)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Track)) {
            Box(Modifier.fillMaxWidth(value.toFloat().coerceIn(0f, 1f)).height(4.dp)
                .clip(RoundedCornerShape(2.dp)).background(if (value >= 0.7) Amber else Blue))
        }
        Spacer(Modifier.height(3.dp))
        T(detail, 10.sp, TextSub, align = TextAlign.Start)
    }
}

// ───────── 공통 부품 ─────────
@Composable
fun Pill(text: String, color: Color, width: Int = 160, textColor: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.padding(vertical = 3.dp).width(width.dp).height(40.dp)
            .clip(RoundedCornerShape(20.dp)).background(color).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { T(text, 14.sp, textColor, FontWeight.Medium) }
}

@Composable
fun RoundIcon(color: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(color).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { icon() }
}

@Composable
fun InfoCard(content: @Composable () -> Unit) {
    Column(
        Modifier.width(184.dp).clip(RoundedCornerShape(22.dp)).background(Card)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) { content() }
}

@Composable
fun Meter(name: String, value: Float, detail: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            T(name, 12.sp, align = TextAlign.Start, modifier = Modifier.weight(1f))
            T(detail, 11.sp, TextSub, align = TextAlign.End)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Track)) {
            Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(4.dp)
                .clip(RoundedCornerShape(2.dp)).background(if (value >= 0.7f) Amber else Blue))
        }
    }
}

@Composable
fun InfoLine(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        T(name, 12.sp, TextSub, align = TextAlign.Start, modifier = Modifier.weight(1f))
        T(value, 12.sp, align = TextAlign.End)
    }
}

// 깜빡이는 빨간 점 (녹음 중)
@Composable
fun RecDot() {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(600); on = !on } }
    Box(Modifier.padding(bottom = 4.dp).size(8.dp).clip(CircleShape).background(if (on) Red else Color.Transparent))
}

// ───────── 아이콘 (그림 파일 없이 직접 그림) ─────────
@Composable
fun MicIcon(color: Color) = Canvas(Modifier.size(20.dp)) {
    val s = size.width
    drawRoundRect(color, Offset(s * 0.34f, s * 0.05f), Size(s * 0.32f, s * 0.52f),
        androidx.compose.ui.geometry.CornerRadius(s * 0.16f))
    drawArc(color, 0f, 180f, false, Offset(s * 0.2f, s * 0.22f), Size(s * 0.6f, s * 0.5f),
        style = Stroke(s * 0.08f, cap = StrokeCap.Round))
    drawLine(color, Offset(s / 2, s * 0.72f), Offset(s / 2, s * 0.92f), s * 0.08f, StrokeCap.Round)
}

@Composable
fun ListIcon() = Canvas(Modifier.size(18.dp)) {
    val s = size.width
    for (i in 0..2) {
        val y = s * (0.22f + i * 0.28f)
        drawCircle(TextMain, s * 0.06f, Offset(s * 0.12f, y))
        drawLine(TextMain, Offset(s * 0.3f, y), Offset(s * 0.92f, y), s * 0.09f, StrokeCap.Round)
    }
}

@Composable
fun ScoreIcon() = Canvas(Modifier.size(18.dp)) {
    val s = size.width
    val w = s * 0.18f
    listOf(0.45f, 0.75f, 0.3f).forEachIndexed { i, h ->
        val x = s * (0.14f + i * 0.36f)
        drawLine(TextMain, Offset(x, s * 0.92f), Offset(x, s * (0.92f - h)), w, StrokeCap.Round)
    }
}

@Composable
fun PlayIcon() = Canvas(Modifier.size(22.dp)) {
    val s = size.width
    drawPath(Path().apply {
        moveTo(s * 0.25f, s * 0.12f); lineTo(s * 0.9f, s * 0.5f); lineTo(s * 0.25f, s * 0.88f); close()
    }, Color.White)
}

@Composable
fun StopIcon() = Canvas(Modifier.size(18.dp)) {
    drawRoundRect(Color.White, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.15f))
}

// ───────── 글자 형식 ─────────
fun formatTime(millis: Long): String =
    SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(millis))

fun formatDur(sec: Int): String = if (sec < 60) "${sec}초" else "${sec / 60}분 ${sec % 60}초"