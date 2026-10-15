package com.example.entify_again.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.entify_again.util.PrivacyLog as Log
import androidx.core.app.NotificationCompat
import com.example.entify_again.MainActivity
import com.example.entify_again.R
import com.example.entify_again.service.EntryPollingForegroundService
import com.example.entify_again.util.PreferenceManager

/**
 * 기기 재부팅 시 동작하는 브로드캐스트 리시버.
 * 폰 전원이 꺼졌다가 켜졌을 때 알림 감시가 중단되어 오해하는 일이 없도록 복구 및 상태 알림을 제공합니다.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_CHANNEL_ID = "entry_system_channel"
        private const val NOTIF_ID_BOOT = 920001
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device rebooted (BOOT_COMPLETED received)")
            val preferenceManager = PreferenceManager(context)

            // 로그인 상태인 경우
            if (preferenceManager.isLoggedIn && !preferenceManager.logoutPending) {
                try {
                    // 1. 포그라운드 폴링 서비스 자동 재시작
                    EntryPollingForegroundService.start(context)
                    Log.i(TAG, "Foreground polling service restored after boot")

                    // 2. 부팅 복구 안내 알림 표시 (테스터 오해 방지)
                    showBootRestartNotification(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore service after boot: ${e.message}", e)
                }
            }
        }
    }

    private fun showBootRestartNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BOOT_CHANNEL_ID,
                "시스템 상태 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "기기 재부팅 및 연결 상태 관련 알림"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSmallIcon(R.drawable.ic_stat_like)
            .setContentTitle("Entify 실시간 감시 재개")
            .setContentText("기기가 재부팅되어 엔트리 알림 감시를 자동으로 다시 시작했습니다.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIF_ID_BOOT, notification)
    }
}
