package com.example.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 워치 앱(PhoneLink)과 같은 경로 이름
const val PATH_ALERT = "/gallio/alert"
const val PATH_AUDIO = "/gallio/audio"
const val PATH_KEYWORD = "/gallio/keyword"
const val PATH_TEXT = "/gallio/text"
const val PATH_REC = "/gallio/rec/"

// ───────── 워치에서 오는 것들을 받는 서비스 (앱이 꺼져 있어도 동작) ─────────
class WatchListenerService : WearableListenerService() {

    // ── 1. 녹음 시작 알림 ──
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_ALERT) return
        val trigger = String(event.data)
        notify(this, "⚠ 위험 감지 · 워치가 녹음을 시작했어요", "감지 이유: $trigger")
        if (Settings.autoSms(this)) sendGuardianSms(trigger)
    }

    private fun sendGuardianSms(trigger: String) {
        val number = Settings.guardian(this)
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return

        val loc = lastLocation(this)
        val where = if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude}" else "위치 확인 불가"
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
        val text = "[자동 알림] $time 위험 상황이 감지되어 녹음이 시작됐어요 (오작동일 수도 있어요). 위치: $where"
        try {
            val sms = getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            notify(this, "보호자에게 문자를 보냈어요", number)
        } catch (e: Exception) {
            notify(this, "보호자 문자 전송 실패", e.message ?: "")
        }
    }

    // ── 2. 녹음 파일 백업 받기 ──
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            val item = event.dataItem
            val path = item.uri.path ?: continue
            if (event.type != DataEvent.TYPE_CHANGED || !path.startsWith(PATH_REC)) continue
            try {
                val map = DataMapItem.fromDataItem(item).dataMap
                val name = map.getString("name") ?: continue
                val asset = map.getAsset("audio") ?: continue
                val dir = Backup.dir(this)
                val wav = File(dir, "$name.wav")

                val input = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset)).inputStream
                input.use { i -> wav.outputStream().use { o -> i.copyTo(o) } }

                // 워치에서 계산한 지문과 받은 파일의 지문이 같으면 "전송 중 바뀌지 않음"
                val sha = map.getString("sha256") ?: ""
                val ok = sha.isNotEmpty() && sha256(wav) == sha

                val meta = JSONObject()
                    .put("start", map.getLong("start"))
                    .put("durationSec", map.getInt("durationSec"))
                    .put("trigger", map.getString("trigger") ?: "")
                    .put("maxDb", map.getDouble("maxDb"))
                    .put("heartRate", map.getInt("heartRate"))
                    .put("sha256", sha)
                    .put("verified", ok)
                File(dir, "$name.json").writeText(meta.toString())

                // 다 받았으면 전송용 임시 데이터는 지움 (워치의 원본 파일은 그대로)
                Wearable.getDataClient(this).deleteDataItems(item.uri)
                notify(this, "녹음이 폰에 백업됐어요", (map.getString("trigger") ?: "") + if (ok) " · 원본 확인 ✓" else "")
            } catch (e: Exception) {
                // 다음 동기화 때 다시 시도됨
            }
        }
    }

    // ── 3. 워치 소리를 받아서 한국어로 알아듣기 → 욕설·이름이면 워치에 알려줌 ──
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != PATH_AUDIO) return
        val channels = Wearable.getChannelClient(this)
        val messages = Wearable.getMessageClient(this)
        val watch = channel.nodeId
        val main = Handler(Looper.getMainLooper())

        val pipe = ParcelFileDescriptor.createPipe()
        val toRecognizer = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        var recognizer: SpeechRecognizer? = null
        var lastSent: String? = null

        fun check(b: Bundle?) {
            val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ") ?: return
            if (text.isBlank()) return
            messages.sendMessage(watch, PATH_TEXT, text.takeLast(30).toByteArray())
            val word = KEYWORDS.firstOrNull { text.replace(" ", "").contains(it) }
            if (word != null && word != lastSent) {
                lastSent = word
                messages.sendMessage(watch, PATH_KEYWORD, word.toByteArray())
            }
        }

        // 음성인식은 메인 스레드에서 시작해야 함
        main.post {
            try {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onPartialResults(partialResults: Bundle?) = check(partialResults)
                        override fun onSegmentResults(segmentResults: Bundle) = check(segmentResults)
                        override fun onResults(results: Bundle?) = check(results)
                        override fun onEndOfSegmentedSession() {}
                        override fun onError(error: Int) {}
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    })
                }
            } catch (e: Exception) { }
        }

        // 워치가 보내는 소리를 음성인식에 그대로 흘려보냄 (워치가 마이크를 끄면 끝남)
        try {
            val fromWatch = Tasks.await(channels.getInputStream(channel))
            fromWatch.use { input ->
                val buf = ByteArray(3200)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    toRecognizer.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
        } finally {
            try { toRecognizer.close() } catch (e: Exception) {}
            main.post {
                try { recognizer?.destroy() } catch (e: Exception) {}
                try { pipe[0].close() } catch (e: Exception) {}
            }
        }
    }
}

// ───────── 설정 저장 (보호자 번호, 자동 문자) ─────────
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("gallio", Context.MODE_PRIVATE)
    fun guardian(c: Context) = prefs(c).getString("guardian", "") ?: ""
    fun setGuardian(c: Context, v: String) = prefs(c).edit().putString("guardian", v).apply()
    fun autoSms(c: Context) = prefs(c).getBoolean("autoSms", false)
    fun setAutoSms(c: Context, v: Boolean) = prefs(c).edit().putBoolean("autoSms", v).apply()
}

// ───────── 백업된 녹음 ─────────
data class BackupInfo(
    val name: String, val start: Long, val durationSec: Int, val trigger: String, val verified: Boolean
)

object Backup {
    fun dir(c: Context) = File(c.filesDir, "backup").apply { mkdirs() }
    fun list(c: Context): List<BackupInfo> =
        (dir(c).listFiles()?.filter { it.extension == "wav" } ?: emptyList()).map { wav ->
            val meta = File(wav.parentFile, wav.nameWithoutExtension + ".json")
            val j = if (meta.exists()) JSONObject(meta.readText()) else JSONObject()
            BackupInfo(
                name = wav.nameWithoutExtension,
                start = j.optLong("start", wav.lastModified()),
                durationSec = j.optInt("durationSec", 0),
                trigger = j.optString("trigger", "알 수 없음"),
                verified = j.optBoolean("verified", false)
            )
        }.sortedByDescending { it.start }
}

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r <= 0) break
            md.update(buf, 0, r)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun notify(context: Context, title: String, text: String) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("alert", "위험 감지", NotificationManager.IMPORTANCE_HIGH))
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n = NotificationCompat.Builder(context, "alert")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    manager.notify((System.currentTimeMillis() % 100000).toInt(), n)
}

// 가장 최근에 알려진 폰 위치 (GPS → 네트워크 순)
fun lastLocation(context: Context): Location? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    return try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }
}