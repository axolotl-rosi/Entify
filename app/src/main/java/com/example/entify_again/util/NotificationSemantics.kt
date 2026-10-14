package com.example.entify_again.util

import java.util.Locale

/** Resolves the user-visible meaning of an Entry notification. */
object NotificationSemantics {
    enum class Kind(val category: String) {
        COMMENT_PROJECT("COMMENT"), COMMENT_REPLY("REPLY"), COMMENT_SPACE("COMMENT"),
        LIKE_PROJECT("LIKE"), LIKE_COMMENT("LIKE"), PIN_PROJECT("LIKE"),
        SHARE_PROJECT("SHARE"), REMAKE_PROJECT("REMAKE"), FOLLOW("FOLLOW"),
        WARNING("WARNING"), SYSTEM("SYSTEM")
    }

    private val projectComments = setOf(
        "template_1", "template_2", "template_7", "template_8", "template_13", "template_14"
    )
    private val replies = setOf("template_34", "template_35")
    private val projectLikes = setOf(
        "template_3", "template_4", "template_9", "template_10", "template_11", "template_12"
    )
    private val commentLikes = setOf("template_36")
    private val pins = setOf("template_37")
    private val remakes = setOf("template_5", "template_6")
    private val shares = setOf("template_28", "template_29", "template_33", "template_41")
    private val follows = setOf("template_27", "template_space_15", "template_space_15_multiple", "template_space_19")
    private val spaceComments = setOf(
        "template_space_1", "template_space_1_multiple", "template_space_3", "template_space_3_multiple",
        "template_space_5", "template_space_5_multiple", "template_space_6", "template_space_6_multiple"
    )
    private val spaceLikes = setOf(
        "template_space_2", "template_space_2_multiple", "template_space_4", "template_space_4_multiple"
    )
    private val warnings = setOf(
        "template_18", "template_20", "template_21", "template_22", "template_23",
        "template_24", "template_25", "template_30", "template_31", "template_32"
    )

    fun resolve(template: String?, message: String? = null, declaredCategory: String? = null): Kind {
        // A real message is stronger evidence than a stale/mismatched template.
        kindFromMessage(message.orEmpty().trim().lowercase(Locale.ROOT))?.let { return it }

        return when (template.orEmpty().trim().lowercase(Locale.ROOT)) {
            in replies -> Kind.COMMENT_REPLY
            in commentLikes -> Kind.LIKE_COMMENT
            in pins -> Kind.PIN_PROJECT
            in remakes -> Kind.REMAKE_PROJECT
            in shares -> Kind.SHARE_PROJECT
            in follows -> Kind.FOLLOW
            in spaceComments -> Kind.COMMENT_SPACE
            in spaceLikes -> Kind.LIKE_PROJECT
            in projectComments -> Kind.COMMENT_PROJECT
            in projectLikes -> Kind.LIKE_PROJECT
            in warnings -> Kind.WARNING
            else -> kindFromDeclaredCategory(declaredCategory)
        }
    }

    private fun kindFromDeclaredCategory(category: String?): Kind =
        when (category.orEmpty().trim().uppercase(Locale.ROOT)) {
            "COMMENT" -> Kind.COMMENT_PROJECT
            "REPLY" -> Kind.COMMENT_REPLY
            "LIKE" -> Kind.LIKE_PROJECT
            "FOLLOW" -> Kind.FOLLOW
            "SHARE" -> Kind.SHARE_PROJECT
            "REMAKE" -> Kind.REMAKE_PROJECT
            "WARNING" -> Kind.WARNING
            // `silent` is delivery metadata, not a semantic notification kind.
            else -> Kind.SYSTEM
        }

    private fun kindFromMessage(message: String): Kind? {
        if (message.isBlank()) return null
        fun has(vararg values: String) = values.any(message::contains)
        // Test compound phrases before generic words such as "comment".
        return when {
            has("댓글에 답글", "댓글에 답변", "replied to", "reply to") -> Kind.COMMENT_REPLY
            has("댓글을 좋아", "댓글 좋아요", "liked your comment") -> Kind.LIKE_COMMENT
            has("리메이크", "remake") -> Kind.REMAKE_PROJECT
            has("팔로", "follow", "친구 요청", "친구 수락") -> Kind.FOLLOW
            has("작품을 공유", "새 작품", "shared", "공유했") -> Kind.SHARE_PROJECT
            has("고정", "pinned") -> Kind.PIN_PROJECT
            has("좋아", "like", "응원") -> Kind.LIKE_PROJECT
            has("댓글", "comment") -> Kind.COMMENT_PROJECT
            has("제재", "경고", "블라인드", "warning") -> Kind.WARNING
            else -> null
        }
    }

    fun isNavigationCategory(raw: String?): Boolean =
        when (raw.orEmpty().trim().lowercase(Locale.ROOT)) {
            "project", "comment", "space", "space_explore", "following", "follow", "user",
            "qna", "tips", "entrystory", "notice", "entrylife", "free", "discuss" -> true
            else -> false
        }
}
