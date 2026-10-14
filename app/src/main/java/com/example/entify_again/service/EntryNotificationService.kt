package com.example.entify_again.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.entify_again.handler.AutoReplyHandler
import com.example.entify_again.util.PreferenceManager

/**
 * 엔트리 알림 감지 Accessibility Service
 * 엔트리 앱의 알림을 감지하고 자동 답글을 처리합니다
 */
class EntryNotificationService : AccessibilityService() {
    
    companion object {
        private const val TAG = "EntryNotificationService"
        private const val ENTRY_PACKAGE = "com.nts.ade"
        
        // 알림 키워드
        private val COMMENT_KEYWORDS = listOf(
            "댓글을 남겼습니다",
            "댓글을 작성",
            "comment"
        )
    }
    
    private lateinit var autoReplyHandler: AutoReplyHandler
    private lateinit var prefManager: PreferenceManager
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service 연결됨")
        
        autoReplyHandler = AutoReplyHandler(this)
        prefManager = PreferenceManager(this)
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val sourcePackage = event.packageName?.toString()?.trim().orEmpty()
        // 엔트리 앱의 이벤트만 처리
        if (sourcePackage != ENTRY_PACKAGE) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleNotificationEvent(event)
        }
    }
    
    /**
     * 알림 이벤트 처리
     */
    private fun handleNotificationEvent(event: AccessibilityEvent) {
        try {
            val notification = event.parcelableData as? android.app.Notification
            if (notification == null) {
                // Notification 객체가 없으면 텍스트로 판단
                val text = event.text.toString()
                Log.d(TAG, "알림 텍스트: $text")
                
                if (isCommentNotification(text)) {
                    Log.d(TAG, "댓글 알림 감지!")
                    handleCommentNotification()
                }
                return
            }
            
            // Notification extras 확인
            val extras = notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getString("android.text") ?: ""
            val bigText = extras.getString("android.bigText") ?: ""
            
            val fullText = "$title $text $bigText"
            Log.d(TAG, "알림 내용: $fullText")
            
            if (isCommentNotification(fullText)) {
                Log.d(TAG, "댓글 알림 감지!")
                handleCommentNotification()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "알림 처리 중 오류", e)
        }
    }
    
    /**
     * 댓글 알림인지 확인
     */
    private fun isCommentNotification(text: String): Boolean {
        return COMMENT_KEYWORDS.any { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
    }
    
    /**
     * 댓글 알림 처리
     */
    private fun handleCommentNotification() {
        // TODO: 자동 답글 기능 - 나중에 활성화 예정
        Log.d(TAG, "댓글 알림 감지됨 (자동 답글 기능은 비활성화됨)")
        
        // 나중에 활성화할 코드:
        /*
        if (!prefManager.autoReplyEnabled) {
            Log.d(TAG, "자동 답글이 비활성화되어 있습니다")
            return
        }
        
        if (!prefManager.isLoggedIn) {
            Log.d(TAG, "로그인이 필요합니다")
            return
        }
        
        Log.d(TAG, "자동 답글 처리 시작...")
        autoReplyHandler.handleNotification()
        */
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service 중단됨")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility Service 종료됨")
        autoReplyHandler.destroy()
    }
}
