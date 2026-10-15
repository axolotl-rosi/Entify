package com.example.entify_again.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationSemanticsTest {
    @Test
    fun resolve_usesSpecificMessageWhenTemplateAndContentDisagree() {
        val result = NotificationSemantics.resolve(
            template = "template_1",
            message = "사용자가 내 댓글을 좋아합니다."
        )

        assertEquals(NotificationSemantics.Kind.LIKE_COMMENT, result)
    }

    @Test
    fun resolve_classifiesAggregateAndSpaceTemplates() {
        assertEquals("COMMENT", NotificationSemantics.resolve("template_2").category)
        assertEquals("REPLY", NotificationSemantics.resolve("template_35").category)
        assertEquals("LIKE", NotificationSemantics.resolve("template_10").category)
        assertEquals("COMMENT", NotificationSemantics.resolve("template_space_5_multiple").category)
        assertEquals("FOLLOW", NotificationSemantics.resolve("template_space_19").category)
    }

    @Test
    fun resolve_doesNotTreatSilentDeliveryHintAsSemanticCategory() {
        val result = NotificationSemantics.resolve(
            template = "unknown_template",
            declaredCategory = "silent"
        )

        assertEquals(NotificationSemantics.Kind.SYSTEM, result)
        assertFalse(NotificationSemantics.isNavigationCategory("silent"))
    }

    @Test
    fun resolve_usesContentEvenWhenDeliveryCategoryIsSilent() {
        val result = NotificationSemantics.resolve(
            template = "template_1",
            message = "사용자가 내 작품을 좋아합니다.",
            declaredCategory = "silent"
        )

        assertEquals(NotificationSemantics.Kind.LIKE_PROJECT, result)
    }

    @Test
    fun resolve_doesNotLetDeclaredCategoryOverrideSpecificContent() {
        val result = NotificationSemantics.resolve(
            template = "template_27",
            message = "사용자가 내 댓글에 답글을 남겼습니다.",
            declaredCategory = "FOLLOW"
        )

        assertEquals(NotificationSemantics.Kind.COMMENT_REPLY, result)
    }

    @Test
    fun resolve_prefersCommentLikeOverGenericComment() {
        assertEquals(
            NotificationSemantics.Kind.LIKE_COMMENT,
            NotificationSemantics.resolve(null, "someone liked your comment")
        )
    }
}
