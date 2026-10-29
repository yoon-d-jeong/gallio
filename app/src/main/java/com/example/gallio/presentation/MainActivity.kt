package com.example.gallio.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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

// ───────── 앱 시작 지점 ─────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SafeRecordScreen() }
    }
}

// ───────── 화면 ─────────
// 실제 감지·녹음은 RecordingService 가 뒤에서 하고, 화면은 그 상태를 보여주기만 함
@Composable
fun SafeRecordScreen() {
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
    }
}