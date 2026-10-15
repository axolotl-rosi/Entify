package com.example.entify_again.classifier

import android.content.Context
import com.example.entify_again.util.PrivacyLog as Log
import com.example.entify_again.model.NotificationData
import com.example.entify_again.model.NotificationType
import com.example.entify_again.webview.EntryWebViewManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 엔트리 알림 분류기
 * GraphQL 응답을 파싱하고 알림 유형을 분류합니다
 */
class NotificationClassifier(private val context: Context? = null) {
    
    companion object {
        private const val TAG = "NotificationClassifier"
    }
    
    private var webViewManager: EntryWebViewManager? = null
    
    init {
        if (context != null) {
            webViewManager = EntryWebViewManager(context)
        }
    }
    
    /**
     * GraphQL 응답을 파싱하여 알림 목록을 추출합니다
     */
    fun parseNotifications(jsonResponse: String): List<NotificationData> {
        return try {
            val jsonObject = JSONObject(jsonResponse)
            val data = jsonObject.optJSONObject("data") ?: return emptyList()
            val topicList = data.optJSONObject("topicList") ?: return emptyList()
            val list = topicList.optJSONArray("list") ?: return emptyList()
            
            val notifications = mutableListOf<NotificationData>()
            
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                
                val notification = NotificationData(
                    id = item.optString("id", ""),
                    nickname = "", // DOM에서 추출 필요
                    category = item.optString("category", ""),
                    template = item.optString("template", ""),
                    isRead = item.optBoolean("isRead", false),
                    created = item.optString("created", ""),
                    params = parseParams(item.optJSONObject("params"))
                )
                
                notifications.add(notification)
            }
            
            Log.d(TAG, "파싱된 알림 개수: ${notifications.size}")
            notifications
            
        } catch (e: Exception) {
            Log.e(TAG, "알림 파싱 실패", e)
            emptyList()
        }
    }
    
    /**
     * params JSON을 Map으로 변환
     */
    private fun parseParams(paramsJson: JSONObject?): Map<String, Any>? {
        if (paramsJson == null) return null
        
        return try {
            val map = mutableMapOf<String, Any>()
            val keys = paramsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = paramsJson.get(key)
            }
            map
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 알림을 분류합니다
     */
    fun classifyNotification(notification: NotificationData): NotificationType {
        val type = NotificationType.fromTemplate(notification.template)
        Log.d(TAG, "알림 분류: ${notification.template} -> ${type.description}")
        return type
    }
    
    /**
     * 읽지 않은 새 댓글 알림만 필터링
     */
    fun filterNewComments(notifications: List<NotificationData>): List<NotificationData> {
        return notifications.filter { notification ->
            !notification.isRead && 
            classifyNotification(notification) == NotificationType.NEW_COMMENT
        }
    }
    
    /**
     * 답글이 필요한 알림인지 확인
     */
    fun needsReply(notification: NotificationData): Boolean {
        val type = classifyNotification(notification)
        return type == NotificationType.NEW_COMMENT && !notification.isRead
    }
    
    /**
     * 알림에서 작품 ID 추출
     */
    fun extractWorkId(notification: NotificationData): String? {
        return try {
            notification.params?.get("workId") as? String
                ?: notification.params?.get("postId") as? String
        } catch (e: Exception) {
            Log.e(TAG, "작품 ID 추출 실패", e)
            null
        }
    }
    
    /**
     * 알림에서 댓글 ID 추출
     */
    fun extractCommentId(notification: NotificationData): String? {
        return try {
            notification.params?.get("commentId") as? String
                ?: notification.params?.get("replyId") as? String
        } catch (e: Exception) {
            Log.e(TAG, "댓글 ID 추출 실패", e)
            null
        }
    }
    
    /**
     * 알림에 프로필 이미지 URL 추가
     * findComment로 댓글을 찾아서 프로필 이미지를 가져옵니다
     */
    suspend fun enrichWithProfileImage(
        notification: NotificationData,
        targetId: String
    ): NotificationData = withContext(Dispatchers.IO) {
        if (webViewManager == null) {
            Log.w(TAG, "WebViewManager가 초기화되지 않았습니다")
            return@withContext notification
        }
        
        try {
            // findComment로 댓글 정보 조회
            val commentResult = webViewManager?.findComment(
                targetId = targetId,
                nickname = notification.nickname,
                timeString = notification.created
            )
            
            if (commentResult != null && commentResult.profileImageFilename.isNotEmpty()) {
                // normalizeUploadUrl로 프로필 이미지 URL 정규화
                val profileUrl = webViewManager?.normalizeUploadUrl(
                    filename = commentResult.profileImageFilename,
                    kind = "profile"
                )
                
                if (profileUrl != null) {
                    Log.d(TAG, "프로필 이미지 URL 추출 성공: $profileUrl")
                    return@withContext notification.copy(profileImageUrl = profileUrl)
                }
            }
            
            Log.w(TAG, "프로필 이미지를 찾을 수 없습니다: ${notification.nickname}")
            return@withContext notification
            
        } catch (e: Exception) {
            Log.e(TAG, "프로필 이미지 추출 실패", e)
            return@withContext notification
        }
    }
    
    /**
     * 여러 알림에 프로필 이미지 일괄 추가
     */
    suspend fun enrichNotificationsWithProfileImages(
        notifications: List<NotificationData>,
        targetId: String
    ): List<NotificationData> = withContext(Dispatchers.IO) {
        notifications.map { notification ->
            enrichWithProfileImage(notification, targetId)
        }
    }

    /**
     * template_27 알림에서 프로필 이미지 추출
     * 팔로우 알림의 경우 프로필 페이지로 이동하여 프로필 이미지를 추출합니다
     */
    suspend fun enrichTemplate27WithProfileImage(
        notification: NotificationData
    ): NotificationData = withContext(Dispatchers.IO) {
        if (webViewManager == null) {
            Log.w(TAG, "WebViewManager가 초기화되지 않았습니다")
            return@withContext notification
        }

        // template_27이 아니면 그대로 반환
        if (notification.template != "template_27") {
            return@withContext notification
        }

        try {
            // params에서 userId 추출
            val userId = notification.params?.get("userId") as? String
            if (userId.isNullOrEmpty()) {
                Log.w(TAG, "userId를 찾을 수 없습니다")
                return@withContext notification
            }

            // 프로필 페이지 URL 생성
            val profileUrl = "https://playentry.org/profile/$userId"

            // 프로필 페이지에서 이미지 추출
            val profileImageUrl = webViewManager?.extractProfileImageFromTemplate27(profileUrl)

            if (profileImageUrl != null) {
                Log.d(TAG, "template_27 프로필 이미지 추출 성공: $profileImageUrl")
                return@withContext notification.copy(profileImageUrl = profileImageUrl)
            } else {
                Log.w(TAG, "template_27 프로필 이미지를 찾을 수 없습니다")
                return@withContext notification
            }

        } catch (e: Exception) {
            Log.e(TAG, "template_27 프로필 이미지 추출 실패", e)
            return@withContext notification
        }
    }

    /**
     * template_28 알림에서 프로필 이미지 추출
     * 새 작품 공유 알림의 경우 작품 페이지로 이동하여 작성자의 프로필 이미지를 추출합니다
     */
    suspend fun enrichTemplate28WithProfileImage(
        notification: NotificationData
    ): NotificationData = withContext(Dispatchers.IO) {
        if (webViewManager == null) {
            Log.w(TAG, "WebViewManager가 초기화되지 않았습니다")
            return@withContext notification
        }

        // template_28이 아니면 그대로 반환
        if (notification.template != "template_28") {
            return@withContext notification
        }

        try {
            // params에서 workId 또는 projectId 추출
            val workId = notification.params?.get("workId") as? String
                ?: notification.params?.get("projectId") as? String

            if (workId.isNullOrEmpty()) {
                Log.w(TAG, "workId를 찾을 수 없습니다")
                return@withContext notification
            }

            // 작품 페이지 URL 생성
            val projectUrl = "https://playentry.org/project/$workId"

            // 작품 페이지에서 작성자의 프로필 이미지 추출
            val profileImageUrl = webViewManager?.extractProfileImageFromTemplate28(projectUrl)

            if (profileImageUrl != null) {
                Log.d(TAG, "template_28 프로필 이미지 추출 성공: $profileImageUrl")
                return@withContext notification.copy(profileImageUrl = profileImageUrl)
            } else {
                Log.w(TAG, "template_28 프로필 이미지를 찾을 수 없습니다")
                return@withContext notification
            }

        } catch (e: Exception) {
            Log.e(TAG, "template_28 프로필 이미지 추출 실패", e)
            return@withContext notification
        }
    }
}
