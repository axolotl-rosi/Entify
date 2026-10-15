package com.example.entify_again.handler

import android.content.Context
import com.example.entify_again.util.PrivacyLog as Log
import com.example.entify_again.classifier.NotificationClassifier
import com.example.entify_again.model.NotificationData
import com.example.entify_again.model.NotificationType
import com.example.entify_again.util.PreferenceManager
import com.example.entify_again.webview.EntryWebViewManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 자동 답글 처리 핸들러
 * 알림을 감지하고 자동으로 답글을 작성합니다
 */
class AutoReplyHandler(private val context: Context) {
    
    companion object {
        private const val TAG = "AutoReplyHandler"
        private const val REPLY_DELAY_MS = 3000L // 답글 작성 전 대기 시간
    }
    
    private val webViewManager = EntryWebViewManager(context)
    private val classifier = NotificationClassifier()
    private val prefManager = PreferenceManager(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // 이미 처리한 알림 ID 저장 (중복 방지)
    private val processedNotifications = mutableSetOf<String>()
    
    /**
     * 알림 처리 시작
     */
    fun handleNotification() {
        if (!prefManager.autoReplyEnabled) {
            Log.d(TAG, "자동 답글이 비활성화되어 있습니다")
            return
        }
        
        if (!prefManager.isLoggedIn) {
            Log.d(TAG, "로그인이 필요합니다")
            return
        }
        
        scope.launch {
            try {
                Log.d(TAG, "알림 확인 시작...")
                
                // 1. 알림 목록 조회
                val notificationsJson = webViewManager.fetchNotifications()
                if (notificationsJson == null) {
                    Log.e(TAG, "알림 조회 실패")
                    return@launch
                }
                
                // 2. 알림 파싱
                val notifications = classifier.parseNotifications(notificationsJson)
                Log.d(TAG, "조회된 알림: ${notifications.size}개")
                
                // 3. 새 댓글만 필터링
                val newComments = classifier.filterNewComments(notifications)
                Log.d(TAG, "새 댓글 알림: ${newComments.size}개")
                
                // 4. 각 댓글에 답글 작성
                for (notification in newComments) {
                    if (processedNotifications.contains(notification.id)) {
                        Log.d(TAG, "이미 처리한 알림: ${notification.id}")
                        continue
                    }
                    
                    processComment(notification)
                    processedNotifications.add(notification.id)
                    
                    // 다음 댓글 처리 전 대기
                    delay(REPLY_DELAY_MS)
                }
                
                Log.d(TAG, "알림 처리 완료")
                
            } catch (e: Exception) {
                Log.e(TAG, "알림 처리 중 오류 발생", e)
            }
        }
    }
    
    /**
     * 개별 댓글 처리
     */
    private suspend fun processComment(notification: NotificationData) {
        try {
            Log.d(TAG, "댓글 처리 시작: ${notification.nickname}")
            
            // 작품 ID 추출
            val workId = classifier.extractWorkId(notification)
            if (workId == null) {
                Log.e(TAG, "작품 ID를 찾을 수 없습니다")
                return
            }
            
            // 댓글 ID 추출 (대댓글인 경우)
            val commentId = classifier.extractCommentId(notification)
            
            // 답글 메시지
            val replyMessage = prefManager.replyMessage
            
            // 답글 작성
            val success = webViewManager.postComment(
                workId = workId,
                commentText = replyMessage,
                parentCommentId = commentId
            )
            
            if (success) {
                Log.d(TAG, "답글 작성 성공: ${notification.nickname}님에게")
                onReplySuccess(notification)
            } else {
                Log.e(TAG, "답글 작성 실패")
                onReplyFailure(notification)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "댓글 처리 중 오류", e)
        }
    }
    
    /**
     * 답글 성공 콜백
     */
    private fun onReplySuccess(notification: NotificationData) {
        // 필요시 통계나 로그 기록
        Log.i(TAG, "✓ 답글 성공 - ID: ${notification.id}, 유저: ${notification.nickname}")
    }
    
    /**
     * 답글 실패 콜백
     */
    private fun onReplyFailure(notification: NotificationData) {
        // 실패한 알림 재시도 큐에 추가 가능
        Log.w(TAG, "✗ 답글 실패 - ID: ${notification.id}, 유저: ${notification.nickname}")
    }
    
    /**
     * 처리된 알림 기록 초기화
     */
    fun clearProcessedNotifications() {
        processedNotifications.clear()
        Log.d(TAG, "처리 기록 초기화")
    }
    
    /**
     * 특정 알림 강제 처리
     */
    fun forceProcessNotification(notificationId: String) {
        scope.launch {
            try {
                val notificationsJson = webViewManager.fetchNotifications()
                if (notificationsJson == null) return@launch
                
                val notifications = classifier.parseNotifications(notificationsJson)
                val target = notifications.find { it.id == notificationId }
                
                if (target != null && classifier.needsReply(target)) {
                    processComment(target)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "강제 처리 실패", e)
            }
        }
    }
    
    /**
     * 리소스 해제
     */
    fun destroy() {
        webViewManager.destroy()
    }
}
