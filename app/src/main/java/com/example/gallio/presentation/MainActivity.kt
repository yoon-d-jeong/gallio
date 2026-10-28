package com.example.gallio.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
const val SAMPLE_RATE = 16000        // 1초에 소리를 16,000번 측정
const val SHOUT_DB = -20.0           // 이 값보다 크면 "큰 소리" (실험으로 조정)
const val SHOUT_CHUNKS = 10          // 큰 소리가 0.1초 x 10 = 1초 이상 이어지면 "고함"
const val PRE_SECONDS = 5            // 고함 감지 "이전" 몇 초까지 같이 저장할지
const val QUIET_STOP_SECONDS = 10    // 조용한 상태가 몇 초 이어지면 녹음을 멈출지
const val MAX_RECORD_SECONDS = 120   // 한 파일 최대 길이 (초)

// 화면에 보여줄 상태 묶음
data class AudioStatus(
    val db: Double = -90.0,
    val shouting: Boolean = false,
    val recording: Boolean = false,
    val savedCount: Int = 0,
    val lastFile: String? = null
)

// ───────── 앱 시작 지점 ─────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 실험용: 화면 계속 켜두기
        setContent { SafeRecordScreen() }
    }
}

// ───────── 화면 ─────────
@Composable
fun SafeRecordScreen() {
    val context = LocalContext.current

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var outing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(AudioStatus()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMic = granted
        if (granted) outing = true
    }

    // 외출 모드가 켜져 있는 동안: 소리 측정 + 고함이면 자동 녹음
    LaunchedEffect(outing, hasMic) {
        if (outing && hasMic) {
            listenLoop(context.applicationContext) { status = it }
        } else {
            status = status.copy(db = -90.0, shouting = false, recording = false)
        }
    }

    val buttonColor = when {
        status.recording -> Color(0xFFC62828)   // 녹음 중: 빨강
        outing -> Color(0xFF2E7D32)             // 외출 모드: 초록
        else -> Color(0xFF424242)               // 꺼짐: 회색
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 외출 모드 버튼
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable {
                    when {
                        outing -> outing = false
                        hasMic -> outing = true
                        else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = when {
                    status.recording -> "● 녹음 중"
                    outing -> "외출 모드\nON"
                    else -> "외출 모드\nOFF"
                },
                style = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            )
        }

        Spacer(Modifier.height(10.dp))

        BasicText(
            text = if (outing) "%.1f dB".format(status.db) else "감지 꺼짐",
            style = TextStyle(color = Color.White, fontSize = 14.sp)
        )

        Spacer(Modifier.height(6.dp))

        // 소리 크기 막대 (-60dB ~ 0dB 를 0~100%로)
        val level = ((status.db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
        Box(
            Modifier
                .width(120.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF333333))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(level)
                    .background(if (status.shouting) Color(0xFFE53935) else Color(0xFF64B5F6))
            )
        }

        Spacer(Modifier.height(6.dp))

        BasicText(
            text = if (status.shouting) "고함 감지!" else " ",
            style = TextStyle(color = Color(0xFFE53935), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )

        BasicText(
            text = "저장된 녹음: ${status.savedCount}개",
            style = TextStyle(color = Color(0xFF9E9E9E), fontSize = 11.sp)
        )
    }
}

// ───────── 마이크 듣기 + 고함이면 녹음 ─────────
@SuppressLint("MissingPermission")
suspend fun listenLoop(context: Context, onStatus: (AudioStatus) -> Unit) = withContext(Dispatchers.IO) {
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

    val dir = File(context.filesDir, "recordings").apply { mkdirs() }
    var savedCount = dir.listFiles()?.size ?: 0
    var lastFile: String? = null

    val preBuffer = ArrayDeque<ShortArray>()   // 최근 몇 초를 계속 들고 있는 "링 버퍼"
    var writer: WavWriter? = null
    var loudStreak = 0
    var quietChunks = 0
    var recordedChunks = 0

    recorder.startRecording()
    try {
        while (isActive) {
            val buffer = ShortArray(chunkSize)
            val n = recorder.read(buffer, 0, chunkSize)
            if (n <= 0) continue
            val chunk = buffer.copyOf(n)

            val db = calcDb(chunk)
            val loud = db > SHOUT_DB
            loudStreak = if (loud) loudStreak + 1 else 0
            val shouting = loudStreak >= SHOUT_CHUNKS

            val w = writer
            if (w == null) {
                // 아직 녹음 전: 최근 PRE_SECONDS 초만 기억
                preBuffer.addLast(chunk)
                while (preBuffer.size > PRE_SECONDS * 10) preBuffer.removeFirst()

                if (shouting) {
                    // 녹음 시작! 기억해둔 "이전 상황"부터 먼저 파일에 씀
                    val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
                    val newWriter = WavWriter(File(dir, name), SAMPLE_RATE)
                    preBuffer.forEach { newWriter.write(it) }
                    preBuffer.clear()
                    writer = newWriter
                    quietChunks = 0
                    recordedChunks = 0
                    vibrate(context)
                }
            } else {
                // 녹음 중: 계속 파일에 쓰고, 오래 조용하거나 너무 길면 멈춤
                w.write(chunk)
                recordedChunks++
                quietChunks = if (loud) 0 else quietChunks + 1
                if (quietChunks >= QUIET_STOP_SECONDS * 10 || recordedChunks >= MAX_RECORD_SECONDS * 10) {
                    w.close()
                    writer = null
                    savedCount++
                    lastFile = w.file.name
                }
            }

            val snapshot = AudioStatus(db, shouting, writer != null, savedCount, lastFile)
            withContext(Dispatchers.Main) { onStatus(snapshot) }
        }
    } finally {
        writer?.close()   // 외출 모드를 끄면 녹음 중이던 파일도 마무리해서 저장
        recorder.stop()
        recorder.release()
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