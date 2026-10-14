package com.example.entify_again.util

import org.json.JSONArray
import org.json.JSONObject

typealias FormattedTemplate = EntryTemplateFormatter.FormattedMessage

/**
 * 엔트리 알림 템플릿 포매터 (Gemini 스타일 친근한 어조)
 *
 * 공식 템플릿(template_1 ~ template_42, template_space_1 ~ template_space_19)을
 * 부드럽고 친근한 안내 메시지와 카테고리별 아이콘 타입으로 변환합니다.
 */
object EntryTemplateFormatter {

    enum class LeftIconType {
        COMMENT,    // 댓글 / 답글
        LIKE,       // 좋아요 / 댓글 좋아요
        FOLLOW,     // 팔로우
        SHARE,      // 작품 공유 / 리메이크
        WARNING,    // ⚠️ 블라인드, 정지, 제재 등 경고 표지판
        AI_SYSTEM   // 배지, 공지, 스태프 등 일반 시스템
    }

    data class FormattedMessage(
        val title: String,
        val body: String,
        val category: String,
        val iconType: LeftIconType,
        val isLikeWithoutId: Boolean = false
    )

    data class TemplateParams(
        val user: String = "",
        val projectName: String = "",
        val userComment: String = "",
        val count: String = ""
    )

    /**
     * params 객체/배열에서 user, projectName, userComment, count 추출
     */
    fun parseParams(rawParams: Any?, fallbackActor: String? = null): TemplateParams {
        var user = fallbackActor.orEmpty()
        var projectName = ""
        var userComment = ""
        var count = ""

        when (rawParams) {
            is JSONObject -> {
                user = rawParams.optString("user", user)
                if (user.isBlank()) user = rawParams.optString("0", user)
                projectName = rawParams.optString("projectName", rawParams.optString("1", ""))
                userComment = rawParams.optString("userComment", rawParams.optString("2", ""))
                count = rawParams.optString("count", "")
            }
            is JSONArray -> {
                if (rawParams.length() > 0 && user.isBlank()) user = rawParams.optString(0, "")
                if (rawParams.length() > 1) projectName = rawParams.optString(1, "")
                if (rawParams.length() > 2) userComment = rawParams.optString(2, "")
                if (rawParams.length() > 3) count = rawParams.optString(3, "")
            }
            is List<*> -> {
                if (rawParams.isNotEmpty() && user.isBlank()) user = rawParams[0]?.toString().orEmpty()
                if (rawParams.size > 1) projectName = rawParams[1]?.toString().orEmpty()
                if (rawParams.size > 2) userComment = rawParams[2]?.toString().orEmpty()
                if (rawParams.size > 3) count = rawParams[3]?.toString().orEmpty()
            }
            is String -> {
                val s = rawParams.trim()
                if (s.startsWith("{") && s.endsWith("}")) {
                    runCatching { JSONObject(s) }.getOrNull()?.let { return parseParams(it, user) }
                } else if (s.startsWith("[") && s.endsWith("]")) {
                    runCatching { JSONArray(s) }.getOrNull()?.let { return parseParams(it, user) }
                } else if (s.contains(",")) {
                    val tokens = s.split(",").map { it.trim() }
                    if (tokens.isNotEmpty() && user.isBlank()) user = tokens[0]
                    if (tokens.size > 1) projectName = tokens[1]
                    if (tokens.size > 2) userComment = tokens[2]
                } else if (user.isBlank()) {
                    user = s
                }
            }
        }

        return TemplateParams(
            user = SecurityUtil.sanitizeDisplayText(user, 30).ifBlank { "엔트리 친구" },
            projectName = SecurityUtil.sanitizeDisplayText(projectName, 40),
            userComment = SecurityUtil.sanitizeDisplayText(userComment, 60),
            count = SecurityUtil.sanitizeDisplayText(count, 10)
        )
    }

    /**
     * 템플릿 ID와 파라미터를 기반으로 Gemini 감성의 친근한 메시지로 포맷합니다.
     */
    fun format(
        template: String?,
        rawParams: Any?,
        fallbackActor: String? = null,
        fallbackBody: String? = null
    ): FormattedMessage {
        val t = template?.trim()?.lowercase().orEmpty()
        val p = parseParams(rawParams, fallbackActor)
        val user = p.user
        val project = p.projectName.ifBlank { "내 작품" }
        val comment = p.userComment.ifBlank { "내 댓글" }
        val count = p.count.ifBlank { "여러" }

        return when (t) {
            // === 댓글 / 답글 계열 ===
            "template_1" -> FormattedMessage(
                title = "새로운 댓글 💬",
                body = "${user}님이 '${project}'에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_2" -> FormattedMessage(
                title = "댓글이 가득 모였어요 💬",
                body = "${user}님 외 ${count}명이 내 작품에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_7" -> FormattedMessage(
                title = "스터디 새 댓글 📚",
                body = "${user}님이 나의 스터디에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_8" -> FormattedMessage(
                title = "스터디 새 소식 📚",
                body = "${user}님 외 ${count}명이 나의 스터디에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_13" -> FormattedMessage(
                title = "새 댓글 도착 💬",
                body = "${user}님이 '${project}'에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_14" -> FormattedMessage(
                title = "댓글 소식 💬",
                body = "${user}님 외 ${count}명이 '${project}'에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_34" -> FormattedMessage(
                title = "내 댓글에 답글 💬",
                body = "${user}님이 '${comment}' 댓글에 답글을 남겼어요!",
                category = "REPLY",
                iconType = LeftIconType.COMMENT
            )
            "template_35" -> FormattedMessage(
                title = "답글이 도착했어요 💬",
                body = "${user}님 외 ${count}명이 내 댓글에 답글을 남겼어요!",
                category = "REPLY",
                iconType = LeftIconType.COMMENT
            )

            // === 좋아요 계열 ===
            "template_3" -> FormattedMessage(
                title = "작품 좋아요 ❤️",
                body = "${user}님이 '${project}' 작품을 좋아해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_4" -> FormattedMessage(
                title = "인기 상승 중! ❤️",
                body = "${user}님 외 ${count}명이 내 작품을 좋아해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_9" -> FormattedMessage(
                title = "스터디 응원 🌟",
                body = "${user}님이 나의 스터디를 응원해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_10" -> FormattedMessage(
                title = "스터디 인기 폭발 🌟",
                body = "${user}님 외 ${count}명이 나의 스터디를 좋아해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_11" -> FormattedMessage(
                title = "좋아요 소식 ❤️",
                body = "${user}님이 '${project}'을(를) 좋아해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_12" -> FormattedMessage(
                title = "좋아요 모음 ❤️",
                body = "${user}님 외 ${count}명이 '${project}'을(를) 좋아해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_36" -> FormattedMessage(
                title = "댓글 좋아요 👍",
                body = "${user}님이 내 댓글을 좋아해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE,
                isLikeWithoutId = true // Hex ID/프로젝트가 없는 댓글 좋아요 전용 플래그
            )
            "template_37" -> FormattedMessage(
                title = "댓글 고정 📌",
                body = "${user}님이 내 댓글을 상단에 고정했어요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )

            // === 리메이크 / 공유 계열 ===
            "template_5" -> FormattedMessage(
                title = "작품 리메이크 🎨",
                body = if (p.projectName.isNotBlank()) "${user}님이 '${p.projectName}' 작품을 리메이크했어요!" else "${user}님이 내 작품을 멋지게 리메이크했어요!",
                category = "REMAKE",
                iconType = LeftIconType.SHARE
            )
            "template_6" -> FormattedMessage(
                title = "리메이크 릴레이 🎨",
                body = if (count.isNotBlank()) "${user}님 외 ${count}명이 내 작품을 리메이크했어요!" else "${user}님이 내 작품을 리메이크했어요!",
                category = "REMAKE",
                iconType = LeftIconType.SHARE
            )
            "template_28" -> FormattedMessage(
                title = "새 작품 공유 🚀",
                body = if (p.projectName.isNotBlank()) "${user}님이 '${p.projectName}' 작품을 공유했어요!" else "${user}님이 새로운 작품을 공개했어요!",
                category = "SHARE",
                iconType = LeftIconType.SHARE
            )
            "template_29" -> FormattedMessage(
                title = "새 스터디 공유 📖",
                body = "${user}님이 새로운 스터디를 공유했어요!",
                category = "SHARE",
                iconType = LeftIconType.SHARE
            )
            "template_33" -> FormattedMessage(
                title = "학급 새 작품 🏫",
                body = "${user}님이 학급에 새로운 작품을 공유했어요!",
                category = "SHARE",
                iconType = LeftIconType.SHARE
            )
            "template_41" -> FormattedMessage(
                title = "학급 스터디 공유 🏫",
                body = "${user}님이 학급에 새로운 스터디를 공유했어요!",
                category = "SHARE",
                iconType = LeftIconType.SHARE
            )

            // === 팔로우 계열 ===
            "template_27" -> FormattedMessage(
                title = "새로운 팔로워 🎉",
                body = "${user}님이 나를 팔로우하기 시작했어요!",
                category = "FOLLOW",
                iconType = LeftIconType.FOLLOW
            )

            // === 스태프 선정 / 명예 / 배지 ===
            "template_15" -> FormattedMessage(
                title = "스태프 선정 축하! 🏆",
                body = "와우! '${project}'이(가) 엔트리 '스태프 선정'에 뽑혔어요!",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_16" -> FormattedMessage(
                title = "인기작품 등극! 🔥",
                body = "대단해요! '${project}'이(가) 실시간 '인기작품'에 올랐어요!",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_42" -> FormattedMessage(
                title = "첫 작품 배지 획득! 🏅",
                body = "첫 작품 공개를 축하해요! 정식 엔트리 크리에이터가 되신 것을 환영해요.",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )

            // === 학급 과제 및 공지 ===
            "template_38" -> FormattedMessage(
                title = "학급 새 공지 📢",
                body = "${user}님이 학급에 새로운 공지사항을 등록했어요!",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_39" -> FormattedMessage(
                title = "학급 새 과제 📝",
                body = "${user}님이 학급에 새로운 과제를 등록했어요!",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_40" -> FormattedMessage(
                title = "과제 마감 D-1 ⏰",
                body = "학급 과제 마감이 하루 남았어요! 기간 내에 잊지 말고 완료해 주세요.",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )

            // === 제재, 가이드라인, 블라인드 (경고 표지판 UI) ===
            "template_18" -> FormattedMessage(
                title = "이용 정지 안내 ⚠️",
                body = "커뮤니티 가이드라인 위반으로 이용이 제한되었어요. 반성 페이지를 확인해 주세요.",
                category = "WARNING",
                iconType = LeftIconType.WARNING
            )
            "template_19" -> FormattedMessage(
                title = "활동 재개 안내 ✨",
                body = "반성 시간이 끝났어요! 앞으로 다른 친구들을 배려하며 활동해 주세요.",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_20" -> FormattedMessage(
                title = "프로필 이미지 주의 안내 ⚠️",
                body = "운영 정책에 의해 부적절한 프로필 이미지가 초기화되었어요.",
                category = "WARNING",
                iconType = LeftIconType.WARNING
            )
            "template_21" -> FormattedMessage(
                title = "닉네임 변경 주의 안내 ⚠️",
                body = "운영 정책에 의해 부적절한 닉네임이 변경되었어요.",
                category = "WARNING",
                iconType = LeftIconType.WARNING
            )
            "template_22", "template_23", "template_24", "template_25", "template_31" -> FormattedMessage(
                title = "게시글 블라인드 경고 ⚠️",
                body = if (project.isNotBlank()) "'${project}' 작품/글이 가이드라인 위반으로 블라인드 처리되었어요."
                       else "게시글이 커뮤니티 가이드라인 위반으로 블라인드 처리되었어요.",
                category = "WARNING",
                iconType = LeftIconType.WARNING
            )
            "template_26" -> FormattedMessage(
                title = "신고 처리 결과 🛡️",
                body = "접수하신 ${user}님에 대한 신고가 안전하게 검토 및 처리되었어요.",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_30" -> FormattedMessage(
                title = "소개글 수정 주의 ⚠️",
                body = "운영 정책에 의해 부적절한 프로필 소개글이 수정되었어요.",
                category = "WARNING",
                iconType = LeftIconType.WARNING
            )
            "template_32" -> FormattedMessage(
                title = "반성문 재작성 필요 ⚠️",
                body = "반성문이 반려되었어요. 반성 페이지로 이동해 정성껏 다시 작성해 주세요.",
                category = "WARNING",
                iconType = LeftIconType.WARNING
            )

            // === 스페이스 월드 계열 ===
            "template_space_1" -> FormattedMessage(
                title = "스페이스 새 댓글 🪐",
                body = "${user}님이 나의 월드에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_space_1_multiple" -> FormattedMessage(
                title = "스페이스 댓글 모음 🪐",
                body = "${user}님 외 ${count}명이 나의 월드에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_space_2" -> FormattedMessage(
                title = "월드 응원 도착 💖",
                body = "${user}님이 나의 월드를 힘차게 응원해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_space_2_multiple" -> FormattedMessage(
                title = "월드 응원 릴레이 💖",
                body = "${user}님 외 ${count}명이 나의 월드를 응원해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_space_3" -> FormattedMessage(
                title = "픽토로그 댓글 📸",
                body = "${user}님이 나의 픽토로그에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_space_3_multiple" -> FormattedMessage(
                title = "픽토로그 댓글 모음 📸",
                body = "${user}님 외 ${count}명이 나의 픽토로그에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_space_4" -> FormattedMessage(
                title = "픽토로그 응원 📸",
                body = "${user}님이 나의 픽토로그를 응원해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_space_4_multiple" -> FormattedMessage(
                title = "픽토로그 응원 릴레이 📸",
                body = "${user}님 외 ${count}명이 나의 픽토로그를 응원해요!",
                category = "LIKE",
                iconType = LeftIconType.LIKE
            )
            "template_space_5" -> FormattedMessage(
                title = "오브젝트 댓글 🧩",
                body = "${user}님이 내가 만든 '${project}' 오브젝트에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_space_5_multiple" -> FormattedMessage(
                title = "오브젝트 댓글 모음 🧩",
                body = "${user}님 외 ${count}명이 내가 만든 '${project}'에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_space_6" -> FormattedMessage(
                title = "아이템 댓글 🎁",
                body = "${user}님이 내가 만든 '${project}' 아이템에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_space_6_multiple" -> FormattedMessage(
                title = "아이템 댓글 모음 🎁",
                body = "${user}님 외 ${count}명이 내가 만든 '${project}' 아이템에 댓글을 남겼어요!",
                category = "COMMENT",
                iconType = LeftIconType.COMMENT
            )
            "template_space_7" -> FormattedMessage(
                title = "월드 블라인드 안내 🔒",
                body = "'${project}' 월드가 커뮤니티 가이드라인에 의해 블라인드 처리되었어요.",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_space_8" -> FormattedMessage(
                title = "월드 블라인드 해제 ✨",
                body = "'${project}' 월드의 블라인드가 안전하게 해제되었어요!",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_space_9", "template_space_10", "template_space_11",
            "template_space_12", "template_space_13", "template_space_14" -> FormattedMessage(
                title = "스페이스 가이드라인 안내 ℹ️",
                body = "커뮤니티 가이드라인에 의해 정보가 수정 또는 블라인드 처리되었어요.",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_space_15" -> FormattedMessage(
                title = "새 친구 요청 👋",
                body = "${user}님이 친구 요청을 보냈어요!",
                category = "FOLLOW",
                iconType = LeftIconType.FOLLOW
            )
            "template_space_15_multiple" -> FormattedMessage(
                title = "친구 요청 도착 👋",
                body = "${user}님 외 ${count}명이 친구 요청을 보냈어요!",
                category = "FOLLOW",
                iconType = LeftIconType.FOLLOW
            )
            "template_space_16" -> FormattedMessage(
                title = "스태프 선정 월드! 🌟",
                body = "축하해요! 나의 월드가 '스태프 선정 월드'로 선정되었어요!",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_space_17" -> FormattedMessage(
                title = "인기 월드 등극! 🚀",
                body = "와우! 나의 월드가 실시간 '인기 월드' 목록에 올랐어요!",
                category = "SYSTEM",
                iconType = LeftIconType.AI_SYSTEM
            )
            "template_space_19" -> FormattedMessage(
                title = "친구 수락 완료 🎉",
                body = "${user}님이 친구 요청을 수락했어요! 이제 함께 활동해봐요.",
                category = "FOLLOW",
                iconType = LeftIconType.FOLLOW
            )

            // === 기본 폴백 ===
            else -> {
                val cleanFallback = fallbackBody?.let { SecurityUtil.sanitizeDisplayText(it, 100) }
                FormattedMessage(
                    title = "엔트리 새 알림 ✨",
                    body = cleanFallback?.ifBlank { null } ?: "${user}님으로부터 새로운 알림이 도착했어요!",
                    category = "SYSTEM",
                    iconType = LeftIconType.AI_SYSTEM
                )
            }
        }
    }
}
