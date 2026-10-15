package com.example.entify_again.data.repository

import android.content.Context
import com.example.entify_again.data.db.EntifyDatabase
import com.example.entify_again.data.db.NotificationDao
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.data.entity.NotificationArchiveEntity
import com.example.entify_again.data.entity.asArchive
import com.example.entify_again.data.entity.encrypted
import com.example.entify_again.data.entity.decrypted
import com.example.entify_again.util.DatabaseFieldCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NotificationRepository(context: Context) {

    private val dao: NotificationDao = EntifyDatabase.getInstance(context).notificationDao()

    fun getAllNotifications(): Flow<List<NotificationEntity>> =
        dao.getAllNotifications().map { list -> list.map { it.decrypted() } }

    fun getByCategory(category: String): Flow<List<NotificationEntity>> =
        dao.getByCategory(category).map { list -> list.map { it.decrypted() } }

    fun getSystemNotifications(): Flow<List<NotificationEntity>> =
        dao.getSystemNotifications().map { list -> list.map { it.decrypted() } }

    fun getStarred(): Flow<List<NotificationEntity>> =
        dao.getStarred().map { list -> list.map { it.decrypted() } }
 
    fun getBlocked(): Flow<List<NotificationEntity>> =
        dao.getBlocked().map { list -> list.map { it.decrypted() } }

    fun getAllForStats(): Flow<List<NotificationEntity>> =
        dao.getArchive().map { list -> list.map { it.asNotification().decrypted() } }

    fun getArchive(): Flow<List<NotificationArchiveEntity>> =
        dao.getArchive().map { list -> list.map { it.decrypted() } }

    suspend fun countArchive(): Int = dao.countArchive()

    fun getUnreadCount(): Flow<Int> = dao.getUnreadCount()

    suspend fun insertIfNew(notification: NotificationEntity, rawPayload: String? = null): Long {
        val existingId = dao.findDuplicateNotificationId(notification.category, notification.actor, notification.createdAt)
        if (existingId != null) {
            if (!notification.actorImageUrl.isNullOrBlank()) {
                dao.updateActorImage(notification.topicId, notification.actor, notification.actorImageUrl)
                dao.updateArchiveActorImage(notification.topicId, notification.actorImageUrl)
            }
            if (!notification.projectThumbnailUrl.isNullOrBlank()) {
                dao.updateProjectThumbnail(notification.topicId, notification.projectThumbnailUrl)
                dao.updateArchiveProjectThumbnail(notification.topicId, notification.projectThumbnailUrl)
            }
            if (!rawPayload.isNullOrBlank()) {
                dao.updateArchiveRawPayload(
                    notification.topicId,
                    DatabaseFieldCipher.encrypt(rawPayload) ?: error("Payload encryption failed")
                )
            }
            return existingId
        }
        // Finish all cryptographic work before the first write. A cipher failure
        // must not leave only one of the live/archive rows persisted.
        val encryptedEntity = notification.encrypted()
        val encryptedArchive = notification.asArchive(rawPayload).encrypted()
        val id = dao.insertIfNew(encryptedEntity)
        dao.archiveIfNew(encryptedArchive)
        return id
    }

    suspend fun archiveIfNew(notification: NotificationArchiveEntity): Long {
        val existingId = dao.findDuplicateArchiveId(notification.category, notification.actor, notification.createdAt)
        if (existingId != null) {
            if (!notification.actorImageUrl.isNullOrBlank()) {
                dao.updateArchiveActorImage(notification.topicId, notification.actorImageUrl)
            }
            if (!notification.projectThumbnailUrl.isNullOrBlank()) {
                dao.updateArchiveProjectThumbnail(notification.topicId, notification.projectThumbnailUrl)
            }
            return existingId
        }
        return dao.archiveIfNew(notification.encrypted())
    }

    suspend fun markRead(id: Long) = dao.markRead(id)

    suspend fun markAllRead() = dao.markAllRead()

    suspend fun setStarred(id: Long, starred: Boolean) = dao.setStarred(id, starred)

    suspend fun setBlocked(id: Long, blocked: Boolean) = dao.setBlocked(id, blocked)

    suspend fun setBlockedByActorHexId(hexId: String, blocked: Boolean) = dao.setBlockedByActorHexId(hexId, blocked)

    suspend fun setBlockedByActor(actor: String, blocked: Boolean) = dao.setBlockedByActor(actor, blocked)

    suspend fun updateBodyByTopicId(topicId: String, body: String) =
        dao.updateBodyByTopicId(topicId, DatabaseFieldCipher.encrypt(body).orEmpty())

    suspend fun updateActorImageByTopicId(topicId: String, imageUrl: String) = dao.updateActorImageByTopicId(topicId, imageUrl)

    suspend fun updateActorImageByActor(actor: String, imageUrl: String) = dao.updateActorImageByActor(actor, imageUrl)

    suspend fun updateActorImage(topicId: String, actor: String, imageUrl: String) = dao.updateActorImage(topicId, actor, imageUrl)

    suspend fun updateProjectThumbnail(topicId: String, imageUrl: String) = dao.updateProjectThumbnail(topicId, imageUrl)

    suspend fun updateArchiveActorImage(topicId: String, imageUrl: String) = dao.updateArchiveActorImage(topicId, imageUrl)

    suspend fun updateArchiveProjectThumbnail(topicId: String, imageUrl: String) = dao.updateArchiveProjectThumbnail(topicId, imageUrl)

    suspend fun findActorImageUrlByActor(actor: String): String? = dao.findActorImageUrlByActor(actor)

    suspend fun updateMediaByTopicId(topicId: String, stickerUrl: String?, imageUrl: String?, actorHexId: String?) =
        dao.updateMediaByTopicId(topicId, stickerUrl, imageUrl, actorHexId)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)

    suspend fun deleteByBlockedStatus(isBlocked: Boolean) = dao.deleteByBlockedStatus(isBlocked)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun count(): Int = dao.count()

    suspend fun getById(id: Long): NotificationEntity? = dao.getById(id)?.decrypted()

    suspend fun isReadByTopicId(topicId: String): Boolean = dao.isReadByTopicId(topicId)

    suspend fun existsByTopicId(topicId: String): Boolean = dao.existsByTopicId(topicId)

    suspend fun markReadByTopicId(topicId: String) = dao.markReadByTopicId(topicId)

    suspend fun getAllReadTopicIds(): List<String> = dao.getAllReadTopicIds()
}
