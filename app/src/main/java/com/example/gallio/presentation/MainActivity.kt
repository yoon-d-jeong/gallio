package com.example.gallio.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────── 앱 시작 지점 ─────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 화면 3개: 메인 / 녹음 목록 / 녹음 하나 자세히
            var screen by remember { mutableStateOf("main") }
            var selected by remember { mutableStateOf<String?>(null) }
            when (screen) {
                "list" -> RecordingListScreen(
                    onBack = { screen = "main" },
                    onOpen = { selected = it; screen = "detail" }
                )
                "detail" -> RecordingDetailScreen(
                    name = selected ?: "",
                    onBack = { screen = "list" }
                )
                else -> SafeRecordScreen(onOpenList = { screen = "list" })
            }
        }
    }
}

// ───────── 화면 ─────────
// 실제 감지·녹음은 RecordingService 가 뒤에서 하고, 화면은 그 상태를 보여주기만 함
@Composable
fun SafeRecordScreen(onOpenList: () -> Unit) {
    val context = LocalContext.current
    val running by AudioState.running.collectAsState()
    val status by AudioState.status.collectAsState()

    fun startOuting(manual: Boolean) {
        val intent = Intent(context, RecordingService::class.java)
        if (manual) intent.action = RecordingService.ACTION_MANUAL
        ContextCompat.startForegroundService(context, intent)
    }
    fun stopOuting() = context.stopService(Intent(context, RecordingService::class.java))

    // 필요한 권한: 마이크 + 심박 + (안드로이드 13 이상이면) 알림
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(heartRatePermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun allGranted() = permissions.all { granted(it) }

    // 권한 창이 닫힌 뒤 무엇을 할지 (외출 모드 켜기 / 긴급 녹음)
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
        else {
            afterPermission = manual
            permissionLauncher.launch(permissions)
        }
    }

    val inStage2 = running && status.stage == 2

    val buttonColor = when {
        status.recording -> Color(0xFFC62828)   // 녹음 중: 빨강
        inStage2 -> Color(0xFFEF6C00)           // 2단계 (마이크 켜짐): 주황
        running -> Color(0xFF2E7D32)            // 1단계 (감시 중): 초록
        else -> Color(0xFF424242)               // 꺼짐: 회색
    }

    // 지금 단계 설명
    val stageText = when {
        !running -> "감지 꺼짐"
        status.recording && status.trigger == "수동" -> "긴급 녹음 중"
        status.recording -> "고함 감지 → 녹음 중"
        inStage2 -> "2단계 · 소리 확인 ${status.micSecondsLeft}초 (${status.trigger})"
        status.cooldownLeft > 0 -> "1단계 · 쉬는 중 ${status.cooldownLeft}초"
        else -> "1단계 · 움직임·심박 감시"
    }

    // 실험용 숫자 (지금 값 / 기준값)
    val detailText = when {
        !running -> " "
        inStage2 && status.aiLabel.isNotEmpty() ->
            "%.0f dB · %s · 고함 %d%%".format(status.db, status.aiLabel, (status.aiShout * 100).toInt())
        inStage2 -> "%.0f dB  (주변 %.0f)".format(status.db, status.ambientDb)
        else -> "흔들림 %.1f / %.1f".format(status.motion, MOTION_LIMIT)
    }
    val hrText = if (status.heartRate > 0) "심박 ${status.heartRate} (+${status.hrRise})" else "심박 --"

    // 막대: 1단계는 흔들림, 2단계는 소리 크기
    val level = when {
        !running -> 0f
        inStage2 -> ((status.db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
        else -> (status.motion / (MOTION_LIMIT * 2)).coerceIn(0.0, 1.0).toFloat()
    }
    val barColor = when {
        status.shouting -> Color(0xFFE53935)
        inStage2 -> Color(0xFF64B5F6)
        status.motion > MOTION_LIMIT -> Color(0xFFFFA726)
        else -> Color(0xFF81C784)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 외출 모드 버튼
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable {
                    if (running) stopOuting() else request(manual = false)
                },
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = when {
                    status.recording -> "● 녹음 중"
                    running -> "외출 모드\nON"
                    else -> "외출 모드\nOFF"
                },
                style = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            )
        }

        Spacer(Modifier.height(8.dp))

        BasicText(
            text = stageText,
            style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        )

        Spacer(Modifier.height(5.dp))

        Box(
            Modifier
                .width(120.dp)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF333333))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(level)
                    .background(barColor)
            )
        }

        Spacer(Modifier.height(4.dp))

        BasicText(
            text = detailText,
            style = TextStyle(color = Color(0xFFBDBDBD), fontSize = 11.sp)
        )

        // 2단계일 때 단어 인식 상태 (켜짐 / 들린 말 / 미지원)
        if (inStage2 && status.keywordInfo.isNotEmpty()) {
            BasicText(
                text = status.keywordInfo,
                style = TextStyle(color = Color(0xFFFFCC80), fontSize = 10.sp)
            )
        }

        BasicText(
            text = "$hrText  ·  녹음 ${status.savedCount}개",
            style = TextStyle(color = Color(0xFF9E9E9E), fontSize = 11.sp)
        )

        Spacer(Modifier.height(6.dp))

        // 긴급 녹음 버튼 (자동 감지가 놓쳤을 때 바로 녹음)
        Box(
            modifier = Modifier
                .width(84.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xFF8E2424))
                .clickable { request(manual = true) },
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = "긴급 녹음",
                style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            )
        }

        Spacer(Modifier.height(4.dp))

        // 녹음 목록 보기
        BasicText(
            text = "녹음 목록 ›",
            style = TextStyle(color = Color(0xFF90CAF9), fontSize = 12.sp),
            modifier = Modifier.clickable { onOpenList() }.padding(4.dp)
        )
    }
}

// ───────── 녹음 목록 화면 ─────────
@Composable
fun RecordingListScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val status by AudioState.status.collectAsState()
    // 녹음 개수가 바뀌면 목록 다시 읽기
    val recordings = remember(status.savedCount) { RecordingStore.list(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 36.dp, bottom = 36.dp)
    ) {
        item {
            BasicText(
                text = "‹ 녹음 ${recordings.size}개",
                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.clickable { onBack() }.padding(8.dp)
            )
        }
        if (recordings.isEmpty()) {
            item {
                BasicText(
                    text = "아직 녹음이 없어요",
                    style = TextStyle(color = Color(0xFF9E9E9E), fontSize = 12.sp)
                )
            }
        }
        items(recordings) { r ->
            Column(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .width(170.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF212121))
                    .clickable { onOpen(r.name) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                BasicText(
                    text = (if (r.keep) "★ " else "") + formatTime(r.start),
                    style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                )
                BasicText(
                    text = "${r.trigger} · ${r.durationSec}초",
                    style = TextStyle(color = Color(0xFFBDBDBD), fontSize = 11.sp)
                )
            }
        }
    }
}

// ───────── 녹음 하나 자세히 (재생 / 보관 / 삭제 + 증거 정보) ─────────
@Composable
fun RecordingDetailScreen(name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember(name) { mutableStateOf(RecordingStore.load(context, name)) }
    var playing by remember { mutableStateOf(false) }
    var verify by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }

    fun stopPlay() {
        player[0]?.release()
        player[0] = null
        playing = false
    }

    // 화면을 나가면 재생 멈춤
    DisposableEffect(name) { onDispose { stopPlay() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 36.dp, bottom = 40.dp)
    ) {
        item {
            BasicText(
                text = "‹ " + formatTime(info.start),
                style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.clickable { onBack() }.padding(6.dp)
            )
        }
        item {
            BasicText(
                text = "${info.trigger} · ${info.durationSec}초\n" +
                        "최대 %.0f dB · 심박 %s".format(info.maxDb, if (info.heartRate > 0) "${info.heartRate}" else "--"),
                style = TextStyle(color = Color(0xFFBDBDBD), fontSize = 11.sp, textAlign = TextAlign.Center)
            )
        }
        item { Spacer(Modifier.height(6.dp)) }
        item {
            DetailButton(if (playing) "■ 정지" else "▶ 재생", Color(0xFF1565C0)) {
                if (playing) stopPlay()
                else {
                    try {
                        val mp = MediaPlayer()
                        mp.setDataSource(RecordingStore.wav(context, name).absolutePath)
                        mp.setOnCompletionListener { stopPlay() }
                        mp.prepare()
                        mp.start()
                        player[0] = mp
                        playing = true
                    } catch (e: Exception) {
                        stopPlay()
                    }
                }
            }
        }
        item {
            DetailButton(if (info.keep) "★ 보관 해제" else "☆ 보관 (자동 삭제 안 함)", Color(0xFF6A5300)) {
                RecordingStore.setKeep(context, name, !info.keep)
                info = RecordingStore.load(context, name)
            }
        }
        item {
            DetailButton("원본 확인", Color(0xFF2E7D32)) {
                // 저장해둔 지문과 지금 다시 계산한 지문이 같으면 편집 안 된 원본
                verify = when {
                    info.sha256.isEmpty() -> "지문 정보 없음 (예전 녹음)"
                    RecordingStore.sha256(RecordingStore.wav(context, name)) == info.sha256 -> "✓ 원본 그대로예요"
                    else -> "✗ 파일이 바뀌었어요"
                }
            }
        }
        verify?.let {
            item {
                BasicText(
                    text = it,
                    style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                )
            }
        }
        item {
            DetailButton(if (askDelete) "한 번 더 누르면 삭제" else "삭제", Color(0xFF8E2424)) {
                if (askDelete) {
                    stopPlay()
                    RecordingStore.delete(context, name)
                    AudioState.status.value = AudioState.status.value.copy(savedCount = RecordingStore.count(context))
                    onBack()
                } else askDelete = true
            }
        }
        item {
            BasicText(
                text = "지문 " + info.sha256.take(16),
                style = TextStyle(color = Color(0xFF616161), fontSize = 9.sp)
            )
        }
    }
}

@Composable
fun DetailButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .width(160.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(color)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        BasicText(text = text, style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }
}

fun formatTime(millis: Long): String =
    SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA).format(Date(millis))