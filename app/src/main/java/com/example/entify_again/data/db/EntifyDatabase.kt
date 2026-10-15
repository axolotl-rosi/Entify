package com.example.entify_again.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.entify_again.data.entity.NotificationArchiveEntity
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.data.entity.encrypted
import com.example.entify_again.util.DatabaseFieldCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Database(entities = [NotificationEntity::class, NotificationArchiveEntity::class], version = 3, exportSchema = false)
abstract class EntifyDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN projectThumbnailUrl TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS notification_archive (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        topicId TEXT NOT NULL,
                        category TEXT NOT NULL,
                        template TEXT NOT NULL,
                        actor TEXT NOT NULL,
                        actorImageUrl TEXT,
                        projectThumbnailUrl TEXT,
                        actorHexId TEXT,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        deepLink TEXT,
                        stickerUrl TEXT,
                        imageUrl TEXT,
                        rawPayload TEXT,
                        receivedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notification_archive_topicId ON notification_archive(topicId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notification_archive_createdAt ON notification_archive(createdAt)")
                db.execSQL(
                    """INSERT OR IGNORE INTO notification_archive
                        (topicId, category, template, actor, actorImageUrl, projectThumbnailUrl,
                         actorHexId, title, body, deepLink, stickerUrl, imageUrl, rawPayload, receivedAt, createdAt)
                        SELECT topicId, category, template, actor, actorImageUrl, NULL,
                               actorHexId, title, body, deepLink, stickerUrl, imageUrl, NULL, createdAt, createdAt
                        FROM notifications""".trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: EntifyDatabase? = null
        private val encryptionMigrationStarted = AtomicBoolean(false)
        private val encryptionMigrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getInstance(context: Context): EntifyDatabase {
            DatabaseFieldCipher.init(context)
            val database = INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EntifyDatabase::class.java,
                    "entify_db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                    .also {
                        INSTANCE = it
                    }
            }
            migrateLegacyPlaintextRows(database)
            return database
        }

        private fun migrateLegacyPlaintextRows(database: EntifyDatabase) {
            if (!encryptionMigrationStarted.compareAndSet(false, true)) return
            encryptionMigrationScope.launch {
                try {
                    val dao = database.notificationDao()
                    dao.getAllRawForEncryption().forEach { row ->
                        if (!DatabaseFieldCipher.isEncrypted(row.title) || !DatabaseFieldCipher.isEncrypted(row.body)) {
                            val encrypted = row.encrypted()
                            dao.updateEncryptedFields(row.id, encrypted.title, encrypted.body)
                        }
                    }
                    dao.getAllArchiveRawForEncryption().forEach { row ->
                        if (!DatabaseFieldCipher.isEncrypted(row.title) ||
                            !DatabaseFieldCipher.isEncrypted(row.body) ||
                            (!row.rawPayload.isNullOrBlank() && !DatabaseFieldCipher.isEncrypted(row.rawPayload))
                        ) {
                            val encrypted = row.encrypted()
                            dao.updateArchiveEncryptedFields(
                                row.id,
                                encrypted.title,
                                encrypted.body,
                                encrypted.rawPayload
                            )
                        }
                    }
                } catch (_: Exception) {
                    encryptionMigrationStarted.set(false)
                }
            }
        }
    }
}
