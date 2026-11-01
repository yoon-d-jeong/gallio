package com.example.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

// ───────── 워치 연결 유지 ─────────
// 왜 필요한가:
//  1) 폰 앱이 화면에서 사라지면 삼성 폰이 앱을 금방 재움 → 워치 소리를 받다가 끊김
//  2) 안드로이드는 "화면에 안 보이는 앱"이 음성인식(마이크 권한)을 쓰는 걸 막음
// 해결: 폰 앱 화면이 떠 있을 때 "마이크 종류" 알림 서비스를 켜두면,
//       그 뒤로는 앱을 닫아도 계속 받아쓰기 가능
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            micReady = false
            status = "꺼짐"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val n = try {
            notification()
        } catch (e: Exception) {
            status = "❌ 알림 만들기 실패: ${short(e)}"
            stopSelf()
            return START_NOT_STICKY
        }

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        // 1순위: 마이크 종류 → 앱을 닫아도 받아쓰기 가능
        var micError = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!micGranted) micError = "폰 마이크 권한 없음 (① 권한 받기 누르기)"
            else try {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                running = true
                micReady = true
                status = "✅ 연결 유지 켜짐 · 앱을 닫아도 받아쓰기 돼요"
                return START_STICKY
            } catch (e: Exception) {
                micError = short(e)
            }
        }

        // 2순위: 마이크 없이 → 연결은 유지되지만 받아쓰기는 앱이 화면에 있을 때만
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, n)
            }
            running = true
            micReady = false
            status = "⚠ 연결만 유지 (마이크 안 됨: $micError)"
            return START_STICKY
        } catch (e: Exception) {
            running = false
            micReady = false
            status = "❌ 연결 유지 실패 · 마이크: $micError · 연결: ${short(e)}"
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun notification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "워치 연결 유지", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Gallio · 워치와 연결 유지 중")
            .setContentText("근무 중 워치가 들은 말을 이 폰이 받아써요")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        running = false
        micReady = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val ID = 7
        private const val ACTION_STOP = "stop"
        @Volatile var running = false
        @Volatile var micReady = false              // 앱을 닫아도 받아쓰기 되는 상태인지
        @Volatile var status = "아직 안 켜짐"        // 폰 화면에 보여줄 상태 (안 될 때 이유가 여기 나옴)

        private fun short(e: Exception) =
            e.javaClass.simpleName + ": " + (e.message ?: "").take(120)

        // 폰 앱 화면에서 부름 (화면이 떠 있을 때만 안드로이드가 허락함)
        fun start(c: Context): Boolean = try {
            c.startService(Intent(c, KeepAliveService::class.java))
            true
        } catch (e: Exception) {
            status = "❌ 서비스 시작 못 함: ${short(e)} (매니페스트 확인)"
            false
        }

        fun stop(c: Context) {
            try {
                c.startService(Intent(c, KeepAliveService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {}
        }
    }
}

// 폰 화면에 보여줄 연결 상태
object LinkState {
    @Volatile var relays = 0              // 지금 워치 소리를 받고 있는 연결 수
    @Volatile var lastText = ""           // 마지막으로 받아쓴 말
    @Volatile var lastTime = 0L           // 마지막으로 워치 소리를 받은 시각
}