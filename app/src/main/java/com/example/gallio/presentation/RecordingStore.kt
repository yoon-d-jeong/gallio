package com.example.gallio.presentation

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// 녹음 하나에 대한 정보 (증거 정보)
data class RecordingInfo(
    val name: String,          // 파일 이름 (확장자 없이) 예: rec_20261029_195210
    val start: Long,           // 녹음 시작 시각 (밀리초)
    val durationSec: Int,      // 길이 (초)
    val trigger: String,       // 감지 이유 예: "움직임 → 고함", "긴급 버튼"
    val maxDb: Double,         // 녹음 중 가장 컸던 소리
    val heartRate: Int,        // 녹음 시작 때 심박 (0 이면 측정 안 됨)
    val sha256: String,        // 파일의 "디지털 지문". 파일이 1바이트라도 바뀌면 값이 완전히 달라짐
    val keep: Boolean          // 보관 표시 (true 면 자동 삭제 안 함)
)

// 지금 녹음 중인 파일
class ActiveRecording(
    val writer: WavWriter,
    val name: String,
    val start: Long,
    val trigger: String,
    val heartRate: Int,
    var maxDb: Double
)

// ───────── 녹음 파일 + 정보 파일(.json) 관리 ─────────
// 녹음 하나 = rec_xxx.wav (소리) + rec_xxx.json (증거 정보)
object RecordingStore {

    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }
    fun wav(context: Context, name: String) = File(dir(context), "$name.wav")
    private fun meta(context: Context, name: String) = File(dir(context), "$name.json")

    fun count(context: Context): Int =
        dir(context).listFiles()?.count { it.extension == "wav" } ?: 0

    // 최신 녹음이 위로 오게 목록 만들기
    fun list(context: Context): List<RecordingInfo> =
        (dir(context).listFiles()?.filter { it.extension == "wav" } ?: emptyList())
            .map { load(context, it.nameWithoutExtension) }
            .sortedByDescending { it.start }

    fun load(context: Context, name: String): RecordingInfo {
        val wavFile = wav(context, name)
        val metaFile = meta(context, name)
        if (metaFile.exists()) {
            try {
                val j = JSONObject(metaFile.readText())
                return RecordingInfo(
                    name = name,
                    start = j.getLong("start"),
                    durationSec = j.getInt("durationSec"),
                    trigger = j.optString("trigger", "알 수 없음"),
                    maxDb = j.optDouble("maxDb", -90.0),
                    heartRate = j.optInt("heartRate", 0),
                    sha256 = j.optString("sha256", ""),
                    keep = j.optBoolean("keep", false)
                )
            } catch (e: Exception) {
                // 정보 파일이 망가졌으면 아래 기본값 사용
            }
        }
        // 정보 파일이 없는 예전 녹음 (또는 지금 녹음 중인 파일)
        return RecordingInfo(
            name = name,
            start = wavFile.lastModified(),
            durationSec = durationOf(wavFile),
            trigger = "알 수 없음",
            maxDb = -90.0,
            heartRate = 0,
            sha256 = "",
            keep = false
        )
    }

    private fun save(context: Context, info: RecordingInfo) {
        val j = JSONObject()
        j.put("start", info.start)
        j.put("durationSec", info.durationSec)
        j.put("trigger", info.trigger)
        j.put("maxDb", info.maxDb)
        j.put("heartRate", info.heartRate)
        j.put("sha256", info.sha256)
        j.put("keep", info.keep)
        meta(context, info.name).writeText(j.toString())
    }

    // 녹음을 마무리: 파일 닫고 → 지문(해시) 계산 → 정보 파일 저장
    fun finish(context: Context, rec: ActiveRecording) {
        rec.writer.close()
        val file = wav(context, rec.name)
        val info = RecordingInfo(
            name = rec.name,
            start = rec.start,
            durationSec = durationOf(file),
            trigger = rec.trigger,
            maxDb = rec.maxDb,
            heartRate = rec.heartRate,
            sha256 = sha256(file),
            keep = false
        )
        save(context, info)
        PhoneLink.backup(context, info)   // 폰으로 자동 백업
    }

    fun setKeep(context: Context, name: String, keep: Boolean) {
        save(context, load(context, name).copy(keep = keep))
    }

    fun delete(context: Context, name: String) {
        wav(context, name).delete()
        meta(context, name).delete()
    }

    // KEEP_DAYS 일이 지났고 "보관" 표시가 없는 녹음 삭제
    fun deleteOld(context: Context) {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        list(context).forEach {
            if (!it.keep && it.start < limit) delete(context, it.name)
        }
    }

    // 파일의 디지털 지문(SHA-256). 녹음 직후 저장해두고, 나중에 다시 계산해서 같으면 "편집 안 된 원본"
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

    private fun durationOf(file: File): Int =
        ((file.length() - 44).coerceAtLeast(0) / (SAMPLE_RATE * 2)).toInt()
}