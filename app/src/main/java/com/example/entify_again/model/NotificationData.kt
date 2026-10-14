package com.example.entify_again.model

/**
 * 엔트리 알림 데이터 모델
 */
data class NotificationData(
    val id: String,
    val nickname: String,
    val category: String,
    val template: String,
    val isRead: Boolean,
    val created: String,
    val params: Map<String, Any>? = null,
    val profileImageUrl: String? = null  // 프로필 이미지 URL
)

/**
 * 알림 타입 enum
 */
enum class NotificationType(val template: String, val description: String) {
    NEW_COMMENT("template_1", "작품에 댓글을 작성함"),
    NEW_LIKE("template_3", "작품에 좋아요를 누름"),
    NEW_FOLLOW("template_27", "나를 팔로잉함"),
    NEW_WORK_SHARED("template_28", "새로운 작품을 공유"),
    REPLY_TO_COMMENT("template_34", "내가 작성한 댓글에 답글을 작성함"),
    UNKNOWN("", "알 수 없는 알림");
    
    companion object {
        fun fromTemplate(template: String): NotificationType {
            return values().find { it.template == template } ?: UNKNOWN
        }
    }
}
