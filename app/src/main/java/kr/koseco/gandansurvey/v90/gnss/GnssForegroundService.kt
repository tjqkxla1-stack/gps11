package kr.koseco.gandansurvey.v90.gnss

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class GnssForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channelId = "gandan_survey_gnss_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "간단측량 RTK 실시간 위치 수신",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(ch)
            val notif = Notification.Builder(this, channelId)
                .setContentTitle("간단측량 V90 실행 중")
                .setContentText("블루투스 RTK 수신기 및 VRS 연결 유지 중")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()
            try {
                startForeground(9001, notif)
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
