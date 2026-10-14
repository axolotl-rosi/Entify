package com.example.entify_again.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.entify_again.util.PreferenceManager

/**
 * 사용자가 상단 푸시 알림을 스와이프하여 지웠을 때 호출되는 리시버.
 * 이미 확인하고 직접 밀어서 지운 알림을 재알림하지 않도록 기록합니다.
 * (단, 사용자가 직접 지우지 않은 채 재시작되거나 앱이 꺼진 경우에는 재알림 가능)
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifDismissReceiver"
        const val ACTION_NOTIFICATION_DISMISSED = "com.example.entify_again.NOTIFICATION_DISMISSED"
        const val EXTRA_TOPIC_ID = "topic_id"
        const val EXTRA_NOTIFICATION_TAG = "notification_tag"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val topicId = intent.getStringExtra(EXTRA_TOPIC_ID)?.trim().orEmpty()
        val tag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG)?.trim().orEmpty()

        if (topicId.isBlank() && tag.isBlank()) return

        Log.d(TAG, "Notification dismissed by user swipe: topicId=$topicId, tag=$tag")
        val prefs = PreferenceManager(context)

        if (topicId.isNotBlank()) {
            prefs.markNotificationDismissed(topicId)
        }
        if (tag.isNotBlank()) {
            prefs.markNotificationDismissed(tag)
        }
    }
}
