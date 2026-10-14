package com.example.entify_again.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["topicId"], unique = true),
        Index(value = ["category"]),
        Index(value = ["isRead"]),
        Index(value = ["isStarred"]),
        Index(value = ["isBlocked"]),
        Index(value = ["actorHexId"]),
        Index(value = ["createdAt"])
    ]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: String,
    val category: String,       // COMMENT, LIKE, FOLLOW, SHARE, SYSTEM
    val template: String,       // template_1, template_3, etc.
    val actor: String,          // nickname of the person who triggered the notification
    val actorImageUrl: String? = null,
    val projectThumbnailUrl: String? = null,
    val actorHexId: String? = null,
    val title: String,
    val body: String,
    val deepLink: String? = null,
    val stickerUrl: String? = null,
    val imageUrl: String? = null,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isBlocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notification_archive",
    indices = [Index(value = ["topicId"], unique = true), Index(value = ["createdAt"])]
)
data class NotificationArchiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: String,
    val category: String,
    val template: String,
    val actor: String,
    val actorImageUrl: String? = null,
    val projectThumbnailUrl: String? = null,
    val actorHexId: String? = null,
    val title: String,
    val body: String,
    val deepLink: String? = null,
    val stickerUrl: String? = null,
    val imageUrl: String? = null,
    val rawPayload: String? = null,
    val receivedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
) {
    fun asNotification(): NotificationEntity = NotificationEntity(
        id = id,
        topicId = topicId,
        category = category,
        template = template,
        actor = actor,
        actorImageUrl = actorImageUrl,
        projectThumbnailUrl = projectThumbnailUrl,
        actorHexId = actorHexId,
        title = title,
        body = body,
        deepLink = deepLink,
        stickerUrl = stickerUrl,
        imageUrl = imageUrl,
        createdAt = createdAt
    )
}

fun NotificationEntity.asArchive(rawPayload: String? = null): NotificationArchiveEntity =
    NotificationArchiveEntity(
        topicId = topicId,
        category = category,
        template = template,
        actor = actor,
        actorImageUrl = actorImageUrl,
        projectThumbnailUrl = projectThumbnailUrl,
        actorHexId = actorHexId,
        title = title,
        body = body,
        deepLink = deepLink,
        stickerUrl = stickerUrl,
        imageUrl = imageUrl,
        rawPayload = rawPayload,
        createdAt = createdAt
    )
