package com.example.entify_again.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.data.entity.NotificationArchiveEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications WHERE isBlocked = 0 ORDER BY createdAt DESC")
    fun getAllNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE category = :category AND isBlocked = 0 ORDER BY createdAt DESC")
    fun getByCategory(category: String): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE isStarred = 1 AND isBlocked = 0 ORDER BY createdAt DESC")
    fun getStarred(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE isBlocked = 1 ORDER BY createdAt DESC")
    fun getBlocked(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications ORDER BY createdAt DESC")
    fun getAllForStats(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0 AND isBlocked = 0")
    fun getUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(notification: NotificationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun archiveIfNew(notification: NotificationArchiveEntity): Long

    @Query("SELECT * FROM notification_archive ORDER BY createdAt DESC")
    fun getArchive(): Flow<List<NotificationArchiveEntity>>

    @Query("SELECT COUNT(*) FROM notification_archive")
    suspend fun countArchive(): Int

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE notifications SET isRead = 1 WHERE isBlocked = 0")
    suspend fun markAllRead()

    @Query("UPDATE notifications SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE notifications SET isBlocked = :blocked WHERE id = :id")
    suspend fun setBlocked(id: Long, blocked: Boolean)

    @Query("UPDATE notifications SET isBlocked = :blocked WHERE actorHexId = :hexId AND actorHexId IS NOT NULL AND actorHexId != ''")
    suspend fun setBlockedByActorHexId(hexId: String, blocked: Boolean)

    @Query("UPDATE notifications SET isBlocked = :blocked WHERE actor = :actor")
    suspend fun setBlockedByActor(actor: String, blocked: Boolean)

    @Query("UPDATE notifications SET body = :body WHERE topicId = :topicId")
    suspend fun updateBodyByTopicId(topicId: String, body: String)

    @Query("UPDATE notifications SET actorImageUrl = :imageUrl WHERE topicId = :topicId")
    suspend fun updateActorImageByTopicId(topicId: String, imageUrl: String)

    @Query("UPDATE notifications SET actorImageUrl = :imageUrl WHERE actor = :actor AND (actorImageUrl IS NULL OR actorImageUrl = '')")
    suspend fun updateActorImageByActor(actor: String, imageUrl: String)

    @Query("UPDATE notifications SET actorImageUrl = :imageUrl WHERE (topicId = :topicId OR actor = :actor)")
    suspend fun updateActorImage(topicId: String, actor: String, imageUrl: String)

    @Query("UPDATE notifications SET projectThumbnailUrl = :imageUrl WHERE topicId = :topicId")
    suspend fun updateProjectThumbnail(topicId: String, imageUrl: String)

    @Query("UPDATE notification_archive SET actorImageUrl = :imageUrl WHERE topicId = :topicId AND (actorImageUrl IS NULL OR actorImageUrl = '')")
    suspend fun updateArchiveActorImage(topicId: String, imageUrl: String)

    @Query("UPDATE notification_archive SET projectThumbnailUrl = :imageUrl WHERE topicId = :topicId AND (projectThumbnailUrl IS NULL OR projectThumbnailUrl = '')")
    suspend fun updateArchiveProjectThumbnail(topicId: String, imageUrl: String)

    @Query("SELECT actorImageUrl FROM notifications WHERE actor = :actor AND actorImageUrl IS NOT NULL AND actorImageUrl != '' LIMIT 1")
    suspend fun findActorImageUrlByActor(actor: String): String?

    @Query("UPDATE notifications SET stickerUrl = :stickerUrl, imageUrl = :imageUrl, actorHexId = CASE WHEN :actorHexId IS NOT NULL AND :actorHexId != '' THEN :actorHexId ELSE actorHexId END WHERE topicId = :topicId")
    suspend fun updateMediaByTopicId(topicId: String, stickerUrl: String?, imageUrl: String?, actorHexId: String?)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notifications WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM notifications WHERE isBlocked = :isBlocked")
    suspend fun deleteByBlockedStatus(isBlocked: Boolean)

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM notifications WHERE isBlocked = 0")
    suspend fun count(): Int

    @Query("SELECT * FROM notifications WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NotificationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM notifications WHERE topicId = :topicId AND isRead = 1)")
    suspend fun isReadByTopicId(topicId: String): Boolean

    @Query("SELECT topicId FROM notifications WHERE isRead = 1")
    suspend fun getAllReadTopicIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM notifications WHERE topicId = :topicId)")
    suspend fun existsByTopicId(topicId: String): Boolean

    @Query("UPDATE notifications SET isRead = 1 WHERE topicId = :topicId")
    suspend fun markReadByTopicId(topicId: String)

    @Query("SELECT id FROM notifications WHERE category = :category AND actor = :actor AND ABS(createdAt - :createdAt) < 60000 LIMIT 1")
    suspend fun findDuplicateNotificationId(category: String, actor: String, createdAt: Long): Long?

    @Query("SELECT id FROM notification_archive WHERE category = :category AND actor = :actor AND ABS(createdAt - :createdAt) < 60000 LIMIT 1")
    suspend fun findDuplicateArchiveId(category: String, actor: String, createdAt: Long): Long?
}
