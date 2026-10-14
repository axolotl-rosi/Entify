package com.example.entify_again.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.entify_again.MainActivity
import com.example.entify_again.R
import com.example.entify_again.util.EntryTemplateFormatter
import com.example.entify_again.util.NotificationSemantics
import com.example.entify_again.util.PreferenceManager
import com.example.entify_again.util.SecurityUtil
import com.example.entify_again.util.UserProfileFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.example.entify_again.data.db.EntifyDatabase
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.data.entity.asArchive
import com.example.entify_again.receiver.NotificationDismissReceiver
import java.text.SimpleDateFormat
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class EntryPollingForegroundService : Service() {

    companion object {
        private const val TAG = "EntryPollingService"
        private const val ACTION_START = "com.example.entify_again.action.POLL_START"

        private const val SERVICE_CHANNEL_ID = "entry_fg_polling_channel"
        private const val COMMENT_CHANNEL_ID = "entry_comment_channel"
        private const val LIKE_CHANNEL_ID = "entry_like_channel"
        private const val FOLLOW_CHANNEL_ID = "entry_follow_channel"
        private const val REMAKE_CHANNEL_ID = "entry_remake_channel"
        private const val SHARE_CHANNEL_ID = "entry_share_channel"
        private const val SYSTEM_CHANNEL_ID = "entry_system_channel"
        private const val SERVICE_NOTIFICATION_ID = 910001
        private const val POLL_INTERVAL_MS = 10_000L
        private const val MAX_SEEN_IDS = 500
        private const val STATE_PREFS_NAME = "fg_polling_state"
        private const val KEY_MAIN_APP_FOREGROUND = "main_app_foreground"

        fun start(context: Context) {
            val intent = Intent(context, EntryPollingForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground polling service: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EntryPollingForegroundService::class.java))
        }

        fun setMainAppForeground(context: Context, isForeground: Boolean) {
            context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MAIN_APP_FOREGROUND, isForeground)
                .apply()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
    private lateinit var preferenceManager: PreferenceManager
    private val statePrefs by lazy { getSharedPreferences("fg_polling_state", Context.MODE_PRIVATE) }
    private val mainSeenPrefs by lazy { getSharedPreferences("seen_notifs", Context.MODE_PRIVATE) }
    private val seenTopicIds = LinkedHashSet<String>()
    private var lastAppliedNotificationMemoryGeneration: Long = -1L
    private val supportedTemplates = setOf("template_1", "template_34", "template_space_1", "template_3", "template_36", "template_37", "template_28", "template_27")
    private val templateComment = setOf("template_1", "template_34", "template_space_1")
    private val templateProjectMeta = setOf("template_3", "template_5", "template_6", "template_28", "template_36", "template_37")
    private val templateFollow = setOf("template_27")

    private data class NotificationEnrichment(
        val title: String,
        val body: String,
        val largeIcon: Bitmap? = null,
        val commentPreview: String? = null,
        val actorImageUrl: String? = null,
        val projectThumbnailUrl: String? = null
    )

    private data class ProjectMeta(
        val name: String,
        val thumbUrl: String,
        val ownerProfileUrl: String
    )

    private data class CommentMeta(
        val content: String,
        val profileUrl: String
    )

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        if (!preferenceManager.rememberNotifications) {
            preferenceManager.clearNotificationMemory(bumpGeneration = false)
        }
        syncNotificationMemoryPolicy(force = true)
        loadSeenIds()
        ensureChannels()
        val intervalSec = (preferenceManager.pollingIntervalMs / 1000).coerceAtLeast(5)
        val serviceNotif = buildServiceNotification("${intervalSec}초마다 최신 알림 확인 중")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SERVICE_NOTIFICATION_ID,
                    serviceNotif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, serviceNotif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground: ${e.message}", e)
        }
        Log.d(TAG, "Foreground polling service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                ensurePollingLoop()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: app task dismissed from recents, keeping polling loop alive")
        setMainAppForeground(this, false)
        ensurePollingLoop()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Foreground polling service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var isNetworkOfflineState = false

    private fun updateServiceNotificationText(text: String) {
        val notif = buildServiceNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    SERVICE_NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(SERVICE_NOTIFICATION_ID, notif)
        }
    }

    private fun ensurePollingLoop() {
        if (pollJob?.isActive == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pollJob = serviceScope.launch {
            while (isActive) {
                val intervalSec = (preferenceManager.pollingIntervalMs / 1000).coerceAtLeast(5)

                if (!preferenceManager.isMonitoringEnabled) {
                    updateServiceNotificationText("알림 감시가 꺼져 있습니다")
                    delay(preferenceManager.pollingIntervalMs.coerceAtLeast(5000L))
                    continue
                }

                if (preferenceManager.isDndActiveNow()) {
                    updateServiceNotificationText("방해금지 시간 동작 중 (알림 감지 일시정지)")
                    delay(preferenceManager.pollingIntervalMs.coerceAtLeast(5000L))
                    continue
                }

                val wakeLock = runCatching {
                    powerManager?.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "Entify:PollingCycleWakeLock"
                    )?.apply {
                        setReferenceCounted(false)
                        acquire(4000L) // 최대 4초간 CPU 깨움
                    }
                }.getOrNull()

                try {
                    runCatching { pollLatestTopics() }
                        .onSuccess {
                            if (isNetworkOfflineState) {
                                isNetworkOfflineState = false
                                Log.i(TAG, "Network restored, updated service notification")
                            }
                            // 매 폴링 주기마다 포그라운드 알림을 재확인/유지하여 스와이프로 닫혀도 자동 복구
                            updateServiceNotificationText("${intervalSec}초마다 최신 알림 확인 중")
                        }
                        .onFailure { error ->
                            Log.e(TAG, "pollLatestTopics failed: ${error.message}", error)
                            if (error is java.io.IOException || error.cause is java.io.IOException) {
                                if (!isNetworkOfflineState) {
                                    isNetworkOfflineState = true
                                    updateServiceNotificationText("⚠️ 네트워크 끊김 (인터넷 연결 확인 필요)")
                                    Log.w(TAG, "Network disconnected, updated service notification")
                                }
                            } else {
                                updateServiceNotificationText("${intervalSec}초마다 최신 알림 확인 중")
                            }
                        }
                } finally {
                    runCatching {
                        if (wakeLock?.isHeld == true) wakeLock.release()
                    }
                }
                delay(preferenceManager.pollingIntervalMs.coerceAtLeast(5000L))
            }
        }
    }

    private fun pollLatestTopics() {
        if (!preferenceManager.isMonitoringEnabled) return
        if (preferenceManager.isDndActiveNow()) return
        if (preferenceManager.logoutPending) return
        if (MainActivity.isAppInForeground) return
        if (statePrefs.getBoolean(KEY_MAIN_APP_FOREGROUND, false) && MainActivity.isAppInForeground) return
        val xToken = preferenceManager.xToken.orEmpty()
        val csrfToken = preferenceManager.csrfToken.orEmpty()
        if (xToken.isBlank() || csrfToken.isBlank()) return

        val topics = fetchLatestTopics(xToken, csrfToken, preferenceManager.cookies.orEmpty())
        if (topics.isEmpty()) return

        val dao = EntifyDatabase.getInstance(this@EntryPollingForegroundService).notificationDao()
        val freshTopics = mutableListOf<JSONObject>()
        for (topic in topics) {
            val id = topic.optString("id").trim()
            if (id.isBlank()) continue
            // 엔트리 서버 상에서 이미 읽음 처리된 알림은 스킵
            val isReadEntry = topic.optBoolean("isRead", false)
            if (isReadEntry) {
                markTopicSeen(id)
                markMainSeenTopic(id)
                preferenceManager.markTopicSeen(id)
                continue
            }
            if (hasMainSeenTopic(id) || preferenceManager.isTopicSeen(id)) {
                markTopicSeen(id)
                continue
            }
            // 이미 로컬 DB에 수신되었거나 읽음 처리된 알림은 스킵
            val existsOrReadInDb = runCatching {
                kotlinx.coroutines.runBlocking { dao.existsByTopicId(id) || dao.isReadByTopicId(id) }
            }.getOrDefault(false)
            if (existsOrReadInDb) {
                markTopicSeen(id)
                markMainSeenTopic(id)
                preferenceManager.markTopicSeen(id)
                continue
            }
            if (!hasSeenTopic(id)) {
                freshTopics.add(topic)
            }
        }
        if (freshTopics.isEmpty()) return

        if (!canPostUserNotifications()) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; keep unread topics for later retry")
            return
        }

        // 최신 응답은 보통 최신순이므로 역순으로 표시해 시간 순서를 맞춘다.
        freshTopics.asReversed().take(8).forEach { topic ->
            val topicId = topic.optString("id").trim()
            if (topicId.isBlank()) return@forEach

            // 사용자가 이미 앱 내 알림 로그에서 확인(읽음)했거나 이미 저장된 알림은 다시 띄우지 않음
            val isAlreadyHandled = runCatching {
                kotlinx.coroutines.runBlocking { dao.existsByTopicId(topicId) || dao.isReadByTopicId(topicId) }
            }.getOrDefault(false)
            if (isAlreadyHandled) {
                markTopicSeen(topicId)
                markMainSeenTopic(topicId)
                preferenceManager.markTopicSeen(topicId)
                return@forEach
            }

            val posted = runCatching { postTopicNotification(topic) }.getOrElse {
                Log.e(TAG, "postTopicNotification failed: ${it.message}", it)
                false
            }
            if (posted) {
                markTopicSeen(topicId)
                markMainSeenTopic(topicId)
                preferenceManager.markTopicSeen(topicId)
            }
        }
    }

    private fun fetchLatestTopics(xToken: String, csrfToken: String, cookieHeader: String): List<JSONObject> {
        val cleanX = xToken.trim('"', '\'', ' ')
        val cleanCsrf = csrfToken.trim('"', '\'', ' ')
        if (cleanX.isBlank() || cleanCsrf.isBlank()) {
            Log.w(TAG, "fetchLatestTopics aborted: blank token(s)")
            return emptyList()
        }

        val cmCookies = runCatching {
            android.webkit.CookieManager.getInstance().getCookie("https://playentry.org")
        }.getOrNull()?.trim()

        val effectiveCookies = when {
            !cmCookies.isNullOrBlank() -> cmCookies
            cookieHeader.isNotBlank() -> cookieHeader
            else -> ""
        }

        val query = """
            query SELECT_TOPICS(${'$'}pageParam: PageParam) {
              topicList(pageParam: ${'$'}pageParam) {
                list {
                  id
                  category
                  template
                  params
                  created
                  isRead
                  link { category target }
                }
              }
            }
        """.trimIndent()
        val body = JSONObject()
            .put("operationName", "SELECT_TOPICS")
            .put("query", query)
            .put("variables", JSONObject().put("pageParam", JSONObject().put("display", 20)))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url("https://playentry.org/graphql/SELECT_TOPICS")
            .addHeader("accept", "*/*")
            .addHeader("content-type", "application/json")
            .addHeader("x-token", cleanX)
            .addHeader("csrf-token", cleanCsrf)
            .addHeader("x-client-type", "Client")
            .addHeader("Origin", "https://playentry.org")
            .addHeader("Referer", "https://playentry.org/alarm")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        if (effectiveCookies.isNotBlank()) {
            requestBuilder.addHeader("cookie", effectiveCookies)
        }

        val request = requestBuilder.post(body).build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "SELECT_TOPICS HTTP ${response.code}")
                return@use emptyList()
            }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return@use emptyList()

            val root = runCatching { JSONObject(text) }.getOrNull() ?: return@use emptyList()
            val errors = root.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                Log.w(TAG, "SELECT_TOPICS GraphQL errors: $text")
                return@use emptyList()
            }
            val list = root.optJSONObject("data")
                ?.optJSONObject("topicList")
                ?.optJSONArray("list")
                ?: return@use emptyList()

            buildList {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    add(item)
                }
            }
        }
    }

    private fun postTopicNotification(topic: JSONObject): Boolean {
        val topicId = topic.optString("id").trim()
        if (topicId.isBlank()) return false
        val template = topic.optString("template").trim().lowercase(Locale.ROOT)
        val rawParams = topic.opt("params")
        val rawActor = extractParam(topic, 0).ifBlank { "누군가" }
        val parsedParams = EntryTemplateFormatter.parseParams(rawParams, rawActor)
        val actor = parsedParams.user.ifBlank { rawActor }
        val formatted = EntryTemplateFormatter.format(
            template = template,
            rawParams = rawParams,
            fallbackActor = actor,
            fallbackBody = null
        )
        val semanticKind = NotificationSemantics.resolve(
            template = template,
            declaredCategory = topic.optString("category")
        )
        val category = semanticKind.category
        val semanticIconType = iconTypeFor(semanticKind)
        val channelId = resolveEventChannelId(semanticIconType, category)
        val contentTitle = parsedParams.projectName.ifBlank { extractParam(topic, 1) }
        val deepLink = buildDeepLink(topic.optJSONObject("link"), topicId)
        val targetId = extractTargetId(topic.optJSONObject("link"), deepLink)
        val created = sanitizeText(topic.optString("created"), 80)

        if (preferenceManager.isSemanticDuplicate(semanticKind.name, actor, created)) {
            Log.d(TAG, "Skip duplicate semantic notification kind=${semanticKind.name} actor=$actor created=$created")
            return true
        }

        val enrichment = enrichNotification(topic, template, actor, contentTitle, deepLink, targetId, formatted)
        val tag = buildUnifiedTag(template, semanticKind, actor, targetId.orEmpty().ifBlank { topicId }, created)
        val notifId = stableId(tag)
        val isInteractive = !isNonInteractiveNotification(template, deepLink)

        val pendingIntent = if (isInteractive) {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("openUrl", deepLink)
            }
            PendingIntent.getActivity(
                this,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val actorHexId = extractUserHexId(topic)
        val isFilteredByUser = preferenceManager.isNotificationFiltered(actorHexId, actor, category)
        val isDismissed = preferenceManager.isNotificationDismissed(topicId) || preferenceManager.isNotificationDismissed(tag)

        val canPostPush = !isFilteredByUser && !isDismissed && canDeliverNotification(channelId, tag)

        if (canPostPush) {
            val resolvedLargeIcon = when {
                enrichment.largeIcon != null -> enrichment.largeIcon
                formatted.isLikeWithoutId || template == "template_36" -> makeLikeBadgeBitmap(128)
                semanticIconType == EntryTemplateFormatter.LeftIconType.WARNING -> makeWarningBadgeBitmap(128)
                semanticIconType == EntryTemplateFormatter.LeftIconType.AI_SYSTEM -> makeAiBadgeBitmap(128)
                else -> makeInitialsAvatar(actor)
            }

            val deleteIntent = Intent(this, NotificationDismissReceiver::class.java).apply {
                action = NotificationDismissReceiver.ACTION_NOTIFICATION_DISMISSED
                putExtra(NotificationDismissReceiver.EXTRA_TOPIC_ID, topicId)
                putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_TAG, tag)
            }
            val deletePendingIntent = PendingIntent.getBroadcast(
                this,
                notifId,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(pickSmallIcon(semanticIconType))
                .setContentTitle(enrichment.title)
                .setContentText(enrichment.body)
                .setAutoCancel(isInteractive)
                .setDeleteIntent(deletePendingIntent)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            pendingIntent?.let { builder.setContentIntent(it) }
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(enrichment.body))
            builder.setLargeIcon(resolvedLargeIcon)

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(tag, notifId, builder.build())
            Log.d(TAG, "notify() succeeded channel=$channelId tag=$tag notifId=$notifId")
        } else {
            Log.d(TAG, "Push alert skipped (filtered=$isFilteredByUser, dismissed=$isDismissed)")
        }

        // Save to Room DB for in-app feed
        serviceScope.launch {
            try {
                val dao = EntifyDatabase.getInstance(this@EntryPollingForegroundService).notificationDao()
                val isBlocked = isFilteredByUser || preferenceManager.isUserBlocked(actorHexId, actor)
                val finalBody = enrichment.body
                val cachedImage = enrichment.actorImageUrl?.takeIf { it.isNotBlank() }
                    ?: dao.findActorImageUrlByActor(actor)
                val entity = NotificationEntity(
                        topicId = topicId,
                        category = category,
                        template = template,
                        actor = actor,
                        actorImageUrl = cachedImage,
                        projectThumbnailUrl = enrichment.projectThumbnailUrl,
                        actorHexId = extractUserHexId(topic),
                        title = enrichment.title,
                        body = finalBody,
                        deepLink = if (isInteractive) deepLink else null,
                        isRead = true,
                        isStarred = false,
                        isBlocked = isBlocked,
                        createdAt = parseIsoToMillis(created)
                    )
                val existingId = dao.findDuplicateNotificationId(category, actor, entity.createdAt)
                if (existingId == null) {
                    dao.insertIfNew(entity)
                    dao.archiveIfNew(entity.asArchive(rawPayload = topic.toString()))
                }
                if (!cachedImage.isNullOrBlank()) {
                    dao.updateActorImage(topicId, actor, cachedImage)
                    dao.updateArchiveActorImage(topicId, cachedImage)
                }
                enrichment.projectThumbnailUrl?.takeIf { it.isNotBlank() }?.let { thumbUrl ->
                    dao.updateProjectThumbnail(topicId, thumbUrl)
                    dao.updateArchiveProjectThumbnail(topicId, thumbUrl)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Room insert failed: ${e.message}")
            }
        }

        return true
    }

    private fun parseIsoToMillis(isoString: String): Long {
        if (isoString.isBlank()) return System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(isoString)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.parse(isoString)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    private fun pickSmallIcon(iconType: EntryTemplateFormatter.LeftIconType): Int {
        return when (iconType) {
            EntryTemplateFormatter.LeftIconType.LIKE -> R.drawable.ic_stat_like
            EntryTemplateFormatter.LeftIconType.FOLLOW -> android.R.drawable.stat_notify_chat
            EntryTemplateFormatter.LeftIconType.SHARE -> android.R.drawable.stat_notify_chat
            EntryTemplateFormatter.LeftIconType.WARNING -> android.R.drawable.stat_sys_warning
            EntryTemplateFormatter.LeftIconType.AI_SYSTEM -> android.R.drawable.stat_notify_more
            EntryTemplateFormatter.LeftIconType.COMMENT -> android.R.drawable.stat_notify_chat
        }
    }

    private fun iconTypeFor(kind: NotificationSemantics.Kind): EntryTemplateFormatter.LeftIconType = when (kind) {
        NotificationSemantics.Kind.COMMENT_PROJECT,
        NotificationSemantics.Kind.COMMENT_REPLY,
        NotificationSemantics.Kind.COMMENT_SPACE -> EntryTemplateFormatter.LeftIconType.COMMENT
        NotificationSemantics.Kind.LIKE_PROJECT,
        NotificationSemantics.Kind.LIKE_COMMENT,
        NotificationSemantics.Kind.PIN_PROJECT -> EntryTemplateFormatter.LeftIconType.LIKE
        NotificationSemantics.Kind.FOLLOW -> EntryTemplateFormatter.LeftIconType.FOLLOW
        NotificationSemantics.Kind.SHARE_PROJECT,
        NotificationSemantics.Kind.REMAKE_PROJECT -> EntryTemplateFormatter.LeftIconType.SHARE
        NotificationSemantics.Kind.WARNING -> EntryTemplateFormatter.LeftIconType.WARNING
        NotificationSemantics.Kind.SYSTEM -> EntryTemplateFormatter.LeftIconType.AI_SYSTEM
    }

    private fun resolveEventChannelId(iconType: EntryTemplateFormatter.LeftIconType, category: String = ""): String {
        if (category.equals("REMAKE", ignoreCase = true)) return REMAKE_CHANNEL_ID
        return when (iconType) {
            EntryTemplateFormatter.LeftIconType.COMMENT -> COMMENT_CHANNEL_ID
            EntryTemplateFormatter.LeftIconType.LIKE -> LIKE_CHANNEL_ID
            EntryTemplateFormatter.LeftIconType.FOLLOW -> FOLLOW_CHANNEL_ID
            EntryTemplateFormatter.LeftIconType.SHARE -> SHARE_CHANNEL_ID
            EntryTemplateFormatter.LeftIconType.WARNING,
            EntryTemplateFormatter.LeftIconType.AI_SYSTEM -> SYSTEM_CHANNEL_ID
        }
    }

    private fun buildUnifiedTag(
        template: String,
        kind: NotificationSemantics.Kind,
        actor: String,
        targetId: String,
        created: String
    ): String {
        val type = when (kind) {
            NotificationSemantics.Kind.COMMENT_PROJECT -> "COMMENT"
            NotificationSemantics.Kind.COMMENT_REPLY -> "REPLY"
            NotificationSemantics.Kind.COMMENT_SPACE -> "SPACE_COMMENT"
            NotificationSemantics.Kind.LIKE_PROJECT -> "LIKE_PROJ"
            NotificationSemantics.Kind.LIKE_COMMENT -> "LIKE_COMM"
            NotificationSemantics.Kind.PIN_PROJECT -> "PIN_PROJ"
            NotificationSemantics.Kind.REMAKE_PROJECT -> "REMAKE"
            NotificationSemantics.Kind.SHARE_PROJECT -> "SHARE"
            NotificationSemantics.Kind.FOLLOW -> "FOLLOW"
            NotificationSemantics.Kind.WARNING -> "WARNING"
            NotificationSemantics.Kind.SYSTEM -> "SYSTEM"
        }
        val tail = when (template) {
            "template_1" -> "댓글을 남겼습니다"
            "template_space_1" -> "스페이스에 댓글을 남겼습니다"
            "template_34" -> "답글을 남겼습니다"
            "template_3" -> "나의 작품을 좋아합니다"
            "template_36" -> "나의 댓글을 좋아합니다"
            "template_37" -> "나를 고정했습니다"
            "template_5", "template_6" -> "작품을 리메이크했습니다"
            "template_28" -> "작품을 공유했습니다"
            "template_27" -> "팔로잉합니다"
            else -> "알림"
        }
        return listOf(
            type,
            sanitizeText(actor, 80),
            tail,
            sanitizeText(targetId, 80),
            sanitizeText(created, 80)
        ).joinToString("|")
    }

    private fun makeInitialsAvatar(nameRaw: String, size: Int = 128): Bitmap {
        val name = sanitizeText(nameRaw, 40).ifBlank { "?" }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (name.hashCode() and 0xFFFFFF) % 360
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.35f, 0.95f))
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val initial = name.take(1).uppercase(Locale.ROOT)
        val fm = textPaint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(initial, size / 2f, baseline, textPaint)
        return bitmap
    }

    private fun makeLikeBadgeBitmap(size: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E91E63")
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_stat_like)
        if (drawable != null) {
            val pad = (size * 0.22f).toInt()
            drawable.setBounds(pad, pad, size - pad, size - pad)
            drawable.setTint(Color.WHITE)
            drawable.draw(canvas)
        } else {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = size * 0.5f
            }
            val fm = textPaint.fontMetrics
            val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText("❤️", size / 2f, baseline, textPaint)
        }
        return bitmap
    }

    private fun makeAiBadgeBitmap(size: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5C6BC0")
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.5f
        }
        val fm = textPaint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText("🤖", size / 2f, baseline, textPaint)
        return bitmap
    }

    private fun makeWarningBadgeBitmap(size: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E65100")
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.5f
        }
        val fm = textPaint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText("⚠️", size / 2f, baseline, textPaint)
        return bitmap
    }

    private fun enrichNotification(
        topic: JSONObject,
        template: String,
        actor: String,
        contentTitle: String,
        deepLink: String,
        targetId: String?,
        formatted: EntryTemplateFormatter.FormattedMessage
    ): NotificationEnrichment {
        val baseTitle = when {
            contentTitle.isNotBlank() -> contentTitle
            formatted.title.isNotBlank() -> formatted.title
            else -> "알림"
        }
        val baseBody = formatted.body
        val normalizedTarget = targetId.orEmpty()

        var title = baseTitle
        var body = baseBody
        var largeIcon: Bitmap? = null
        var commentPreview: String? = null
        var actorImageUrl: String? = null
        var projectThumbnailUrl: String? = null

        if (normalizedTarget.isNotBlank() && template in templateProjectMeta) {
            val meta = fetchProjectMeta(normalizedTarget)
            if (meta != null) {
                if (title == "알림" && meta.name.isNotBlank()) {
                    title = meta.name
                }
                val imageUrl = when {
                    (template == "template_5" || template == "template_6") && meta.ownerProfileUrl.isNotBlank() -> meta.ownerProfileUrl
                    template == "template_28" -> meta.thumbUrl.ifBlank { meta.ownerProfileUrl }
                    else -> meta.thumbUrl
                }
                if (meta.ownerProfileUrl.isNotBlank()) {
                    actorImageUrl = meta.ownerProfileUrl
                }
                if (meta.thumbUrl.isNotBlank()) {
                    projectThumbnailUrl = meta.thumbUrl
                }
                largeIcon = fetchBitmap(imageUrl, 128)
            }
        }

        if (normalizedTarget.isNotBlank() && template in templateFollow) {
            val profileUrl = fetchFollowProfileUrl(normalizedTarget)
            if (profileUrl.isNotBlank()) {
                actorImageUrl = profileUrl
                largeIcon = fetchBitmap(profileUrl, 128)
            }
        }

        if (template in templateComment) {
            val isReply = template == "template_34"
            title = if (isReply) "${actor}님의 답글" else "${actor}님의 댓글"
        }

        if (normalizedTarget.isNotBlank() && (template in templateComment || template == "template_36")) {
            val comment = fetchCommentMeta(
                targetId = normalizedTarget,
                actor = actor,
                createdIso = sanitizeText(topic.optString("created"), 80),
                refererUrl = deepLink
            )
            if (comment != null) {
                if (comment.content.isNotBlank()) {
                    commentPreview = comment.content
                    if (template in templateComment) {
                        body = sanitizeText(comment.content, 200)
                    }
                }
                if (comment.profileUrl.isNotBlank()) {
                    actorImageUrl = comment.profileUrl
                    if (largeIcon == null) {
                        largeIcon = fetchBitmap(comment.profileUrl, 128)
                    }
                }
            }
        }

        // 댓글에 답글을 남긴 경우 (template_34) 또는 다른 알림에서 응답 json에 해당 유저의 hexid가 있으면 프로필 추출 시도
        val userHexId = extractUserHexId(topic)
        if (actorImageUrl.isNullOrBlank() && !userHexId.isNullOrBlank()) {
            val profileUrl = fetchFollowProfileUrl(userHexId)
            if (profileUrl.isNotBlank()) {
                actorImageUrl = profileUrl
                if (largeIcon == null) {
                    largeIcon = fetchBitmap(profileUrl, 128)
                }
            }
        }

        // 프로필 이미지가 아직 없고 작성자가 유효하면 직접 프로필 이미지 조회
        if (actorImageUrl.isNullOrBlank() && actor.isNotBlank() && actor != "누군가" && actor != "엔트리 친구") {
            val profileUrl = fetchFollowProfileUrl(actor)
            if (profileUrl.isNotBlank()) {
                actorImageUrl = profileUrl
                if (largeIcon == null) {
                    largeIcon = fetchBitmap(profileUrl, 128)
                }
            }
        }

        return NotificationEnrichment(
            title = sanitizeText(title, 120).ifBlank { "알림" },
            body = sanitizeText(body, 200).ifBlank { "새 알림이 도착했어요" },
            largeIcon = largeIcon,
            commentPreview = sanitizeText(commentPreview, 180).takeIf { !it.isNullOrBlank() },
            actorImageUrl = actorImageUrl,
            projectThumbnailUrl = projectThumbnailUrl
        )
    }

    private fun extractTargetId(link: JSONObject?, deepLink: String): String? {
        val fromLink = sanitizeText(link?.optString("target"), 80)
        if (looksLikeObjectId(fromLink)) return fromLink

        val normalized = deepLink.lowercase(Locale.ROOT)
        val patterns = listOf(
            Regex("/project/([0-9a-f]{24})(?:[/?#]|$)", RegexOption.IGNORE_CASE),
            Regex("/community/[a-z0-9_-]+/([0-9a-f]{24})(?:[/?#]|$)", RegexOption.IGNORE_CASE),
            Regex("/group/community/([0-9a-f]{24})/[0-9a-f]{24}(?:[/?#]|$)", RegexOption.IGNORE_CASE),
            Regex("([0-9a-f]{24})(?![0-9a-f])", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(normalized) ?: continue
            val id = m.groupValues.getOrNull(1).orEmpty()
            if (looksLikeObjectId(id)) return id
        }
        return null
    }

    private fun looksLikeObjectId(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return Regex("^[0-9a-f]{24}$", RegexOption.IGNORE_CASE).matches(v)
    }

    private fun extractUserHexId(o: JSONObject): String? {
        val directKeys = listOf("userId", "actorId", "authorId", "userHexId", "hexId", "targetUserId")
        for (k in directKeys) {
            val v = o.optString(k).trim()
            if (looksLikeObjectId(v)) return v
        }
        val nestedObjs = listOf("user", "actor", "author", "topicinfo")
        for (n in nestedObjs) {
            val obj = o.optJSONObject(n) ?: continue
            for (k in listOf("id", "_id", "userId", "hexId")) {
                val v = obj.optString(k).trim()
                if (looksLikeObjectId(v)) return v
            }
        }
        val rawParams = o.opt("params")
        if (rawParams is JSONArray) {
            for (idx in 0 until rawParams.length()) {
                val item = rawParams.opt(idx)
                if (item is JSONObject) {
                    for (k in listOf("id", "_id", "userId", "actorId")) {
                        val v = item.optString(k).trim()
                        if (looksLikeObjectId(v)) return v
                    }
                } else if (item is String) {
                    val v = item.trim()
                    if (looksLikeObjectId(v)) return v
                }
            }
        } else if (rawParams is JSONObject) {
            for (k in listOf("id", "_id", "userId", "actorId")) {
                val v = rawParams.optString(k).trim()
                if (looksLikeObjectId(v)) return v
            }
        }
        val link = o.optJSONObject("link")
        if (link != null) {
            val cat = link.optString("category").trim().lowercase(Locale.ROOT)
            if (cat == "user" || cat == "profile" || cat == "following") {
                val t = link.optString("target").trim()
                if (looksLikeObjectId(t)) return t
            }
        }
        return null
    }

    private fun fetchProjectMeta(projectId: String): ProjectMeta? {
        val query = """
            query (${'$'}id: ID!) {
              project(id: ${'$'}id) {
                name
                thumb
                user {
                  profileImage {
                    filename
                    imageType
                  }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject().put("id", projectId)
        val root = executeGraphql(query, variables, referer = "https://playentry.org/project/$projectId") ?: return null
        val project = root.optJSONObject("data")?.optJSONObject("project") ?: return null
        val name = sanitizeText(project.optString("name"), 120)
        val thumbUrl = sanitizeImageUrl(makeAbsoluteEntryUrl(project.optString("thumb")))
        val profileObj = project.optJSONObject("user")?.optJSONObject("profileImage")
        val ownerProfileUrl = sanitizeImageUrl(
            buildUploadUrlFromSlug(
                profileObj?.optString("filename").orEmpty(),
                profileObj?.optString("imageType")
            )
        )
        if (name.isBlank() && thumbUrl.isBlank() && ownerProfileUrl.isBlank()) return null
        return ProjectMeta(name, thumbUrl, ownerProfileUrl)
    }

    private fun fetchFollowProfileUrl(userId: String): String {
        val query = """
            query (${'$'}id: String) {
              userstatus(id: ${'$'}id) {
                profileImage {
                  filename
                  imageType
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject().put("id", userId)
        val root = executeGraphql(query, variables, referer = "https://playentry.org/profile/$userId/project?sort=created&term=all&isOpen=all")
        val profile = root?.optJSONObject("data")
            ?.optJSONObject("userstatus")
            ?.optJSONObject("profileImage")
        if (profile != null) {
            val url = sanitizeImageUrl(
                buildUploadUrlFromSlug(
                    profile.optString("filename"),
                    profile.optString("imageType")
                )
            )
            if (url.isNotBlank()) return url
        }

        // Fallback: UserProfileFetcher with CSRF and HTML parsing
        return runCatching {
            runBlocking {
                UserProfileFetcher.fetchProfile(
                    idOrNickname = userId,
                    cookies = preferenceManager.cookies,
                    csrfToken = preferenceManager.csrfToken,
                    xToken = preferenceManager.xToken
                )
            }?.avatarUrl.orEmpty()
        }.getOrDefault("")
    }

    private fun fetchCommentMeta(
        targetId: String,
        actor: String,
        createdIso: String,
        refererUrl: String
    ): CommentMeta? {
        val query = """
            query SELECT_COMMENTS(${'$'}pageParam: PageParam, ${'$'}target: String, ${'$'}searchAfter: JSON) {
              commentList(pageParam: ${'$'}pageParam, target: ${'$'}target, searchAfter: ${'$'}searchAfter) {
                list {
                  content
                  created
                  user {
                    nickname
                    profileImage {
                      filename
                      imageType
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject()
            .put("target", targetId)
            .put("searchAfter", JSONObject.NULL)
            .put(
                "pageParam",
                JSONObject()
                    .put("display", 25)
                    .put("sort", "created")
                    .put("order", -1)
            )
        val referer = if (refererUrl.startsWith("https://playentry.org/")) refererUrl else "https://playentry.org/project/$targetId"
        val root = executeGraphql(query, variables, referer = referer) ?: return null
        val list = root.optJSONObject("data")
            ?.optJSONObject("commentList")
            ?.optJSONArray("list")
            ?: return null
        if (list.length() == 0) return null

        val actorSafe = sanitizeText(actor, 80)
        val targetMs = parseIsoMillis(createdIso)
        var bestObj: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        var fallbackByActor: JSONObject? = null

        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val nick = sanitizeText(item.optJSONObject("user")?.optString("nickname"), 80)
            if (actorSafe.isNotBlank() && nick.equals(actorSafe, ignoreCase = true) && fallbackByActor == null) {
                fallbackByActor = item
            }
            if (targetMs > 0L && actorSafe.isNotBlank() && nick.equals(actorSafe, ignoreCase = true)) {
                val diff = abs(parseIsoMillis(item.optString("created")) - targetMs)
                if (diff < bestScore) {
                    bestScore = diff
                    bestObj = item
                }
            }
        }

        val picked = when {
            bestObj != null && bestScore <= 5 * 60 * 1000L -> bestObj
            fallbackByActor != null -> fallbackByActor
            else -> list.optJSONObject(0)
        } ?: return null

        val content = sanitizeText(picked.optString("content"), 180)
        val profileObj = picked.optJSONObject("user")?.optJSONObject("profileImage")
        val profileUrl = sanitizeImageUrl(
            buildUploadUrlFromSlug(
                profileObj?.optString("filename").orEmpty(),
                profileObj?.optString("imageType")
            )
        )
        if (content.isBlank() && profileUrl.isBlank()) return null
        return CommentMeta(content = content, profileUrl = profileUrl)
    }

    private fun executeGraphql(
        query: String,
        variables: JSONObject,
        referer: String
    ): JSONObject? {
        val xToken = preferenceManager.xToken.orEmpty()
        val csrfToken = preferenceManager.csrfToken.orEmpty()

        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://playentry.org/graphql")
            .addHeader("accept", "*/*")
            .addHeader("content-type", "application/json")
            .addHeader("origin", "https://playentry.org")
            .addHeader("referer", referer)
            .apply {
                if (xToken.isNotBlank()) addHeader("x-token", xToken)
                if (csrfToken.isNotBlank()) addHeader("csrf-token", csrfToken)
                addHeader("x-client-type", "Client")
                val cookieHeader = preferenceManager.cookies.orEmpty()
                if (cookieHeader.isNotBlank()) addHeader("cookie", cookieHeader)
            }
            .post(body)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return@use null
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return@use null
            val errors = root.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                Log.w(TAG, "GraphQL error: ${errors.optJSONObject(0)?.optString("message")}")
                return@use null
            }
            root
        }
    }

    private fun fetchBitmap(rawUrl: String, size: Int): Bitmap? {
        val url = sanitizeImageUrl(rawUrl)
        if (url.isBlank()) return null
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .addHeader("Referer", "https://playentry.org/")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val mime = response.header("content-type").orEmpty()
            if (!isAllowedImageMime(mime)) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            val isSvg = mime.contains("svg", true) || url.contains(".svg", true) || looksLikeSvg(bytes)
            val decoded = if (isSvg) {
                renderSvgToBitmap(bytes, size, size)
            } else {
                if (!looksLikeRasterImage(bytes)) return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } ?: return@use null
            if (decoded.width == size && decoded.height == size) decoded
            else Bitmap.createScaledBitmap(decoded, size, size, true)
        }
    }

    private fun looksLikeSvg(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return runCatching {
            val head = String(bytes, 0, minOf(bytes.size, 240), Charsets.UTF_8)
            head.contains("<svg", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun renderSvgToBitmap(svgBytes: ByteArray, width: Int, height: Int): Bitmap? {
        return runCatching {
            val svg = com.caverock.androidsvg.SVG.getFromString(String(svgBytes, Charsets.UTF_8))
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            svg.setDocumentWidth(width.toFloat())
            svg.setDocumentHeight(height.toFloat())
            svg.renderToCanvas(canvas)
            bmp
        }.getOrNull()
    }

    private fun parseIsoMillis(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (pattern in patterns) {
            val millis = runCatching {
                val fmt = java.text.SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                fmt.parse(s)?.time ?: 0L
            }.getOrDefault(0L)
            if (millis > 0L) return millis
        }
        return runCatching { java.util.Date(s).time }.getOrDefault(0L)
    }

    private fun buildBodyText(template: String, actor: String): String {
        return EntryTemplateFormatter.format(template, null, actor).body
    }

    private fun buildDeepLink(link: JSONObject?, fallbackId: String): String {
        val target = sanitizeText(link?.optString("target"), 80).ifBlank { fallbackId }
        val category = sanitizeText(link?.optString("category"), 40).lowercase(Locale.ROOT)
        if (target.isBlank()) return "https://playentry.org/alarm"

        return when {
            category.isBlank() || category == "project" || category == "comment" -> "https://playentry.org/project/$target"
            category == "following" || category == "user" -> "https://playentry.org/profile/$target/project?sort=created&term=all&isOpen=all"
            category in setOf("qna", "tips", "entrystory", "notice", "entrylife", "free", "discuss") ->
                "https://playentry.org/community/$category/$target"
            else -> "https://playentry.org/alarm"
        }
    }

    private fun extractParam(topic: JSONObject, index: Int): String {
        val values = paramsToList(topic.opt("params"))
        return values.getOrNull(index).orEmpty()
    }

    private fun paramsToList(raw: Any?): List<String> {
        return try {
            when (raw) {
                null -> emptyList()
                is JSONArray -> buildList {
                    for (i in 0 until raw.length()) {
                        val v = raw.opt(i)
                        val text = sanitizeText(v?.toString(), 160)
                        if (text.isNotBlank()) add(text)
                    }
                }
                is JSONObject -> {
                    val keys = raw.keys()
                    buildList {
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val text = sanitizeText(raw.opt(k)?.toString(), 160)
                            if (text.isNotBlank()) add(text)
                        }
                    }
                }
                is String -> {
                    val s = raw.trim()
                    if (s.isBlank()) emptyList()
                    else if ((s.startsWith("[") && s.endsWith("]")) || (s.startsWith("{") && s.endsWith("}"))) {
                        val parsed: Any = if (s.startsWith("[")) JSONArray(s) else JSONObject(s)
                        paramsToList(parsed)
                    } else {
                        s.split(",").map { sanitizeText(it, 160) }.filter { it.isNotBlank() }
                    }
                }
                else -> listOfNotNull(sanitizeText(raw.toString(), 160).takeIf { it.isNotBlank() })
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sanitizeText(raw: String?, maxLen: Int): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("<[^>]*>"), " ")
            .replace('\u2028', ' ')
            .replace('\u2029', ' ')
            .trim()
        text = text.replace(Regex("\\s+"), " ").trim()
        return if (text.length > maxLen) text.take(maxLen).trim() else text
    }

    private fun sanitizeUrl(raw: String): String {
        var s = raw.trim()
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length - 1)
        }
        if (s.startsWith("url(", ignoreCase = true) && s.endsWith(")")) {
            s = s.substring(4, s.length - 1).trim()
            if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
                s = s.substring(1, s.length - 1)
            }
        }
        s = s.replace("&quot;", "").replace("\n", "").trim()
        if (s.startsWith("//")) s = "https:$s"
        if (s.startsWith("/")) s = "https://playentry.org$s"
        return s.replace(" ", "%20")
    }

    private fun isAllowedEntryHost(hostRaw: String?): Boolean {
        val host = hostRaw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return host == "playentry.org" || host.endsWith(".playentry.org") || host.endsWith(".pstatic.net")
    }

    private fun isAllowedImageMime(mimeRaw: String): Boolean {
        val mime = mimeRaw.lowercase(Locale.ROOT)
        return mime.startsWith("image/")
    }

    private fun sanitizeImageUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        val normalized = sanitizeUrl(rawUrl)
        if (normalized.isBlank()) return ""
        val lower = normalized.lowercase(Locale.ROOT)
        if (
            lower.startsWith("javascript:") ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("intent:") ||
            lower.startsWith("data:")
        ) return ""

        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "https") return ""
        if (!isAllowedEntryHost(uri.host)) return ""
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if (path.contains("..")) return ""
        val likelyImage = path.contains("/uploads/") ||
            path.contains("/thumb/") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif") ||
            path.endsWith(".bmp") ||
            path.endsWith(".svg") ||
            path.endsWith(".avif")
        return if (likelyImage) normalized else ""
    }

    private fun looksLikeRasterImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        val isPng = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47
        val isJpeg = b0 == 0xFF && b1 == 0xD8
        val isGif = b0 == 0x47 && b1 == 0x49 && b2 == 0x46
        val isWebp = bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        return isPng || isJpeg || isGif || isWebp
    }

    private fun makeAbsoluteEntryUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.startsWith("http", true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://playentry.org$trimmed"
            else -> "https://playentry.org/$trimmed"
        }
    }

    private fun buildUploadUrlFromSlug(filenameRaw: String?, imageTypeRaw: String?): String {
        val filename = filenameRaw?.trim().orEmpty()
        if (filename.isBlank()) return ""
        if (filename.startsWith("http", true)) return filename
        val imageType = imageTypeRaw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val hasExt = Regex("\\.[a-z0-9]{2,6}$", RegexOption.IGNORE_CASE).containsMatchIn(filename)

        val withoutPrefix = filename.removePrefix("/uploads/").removePrefix("uploads/")
        val slugPath = when {
            withoutPrefix.contains("/") -> withoutPrefix
            withoutPrefix.length >= 4 -> {
                val a = withoutPrefix.substring(0, 2)
                val b = withoutPrefix.substring(2, 4)
                "$a/$b/$withoutPrefix"
            }
            withoutPrefix.length >= 2 -> {
                val a = withoutPrefix.substring(0, 2)
                "$a/$withoutPrefix"
            }
            else -> withoutPrefix
        }

        val ext = if (hasExt || imageType.isBlank()) "" else ".$imageType"
        return "https://playentry.org/uploads/$slugPath$ext"
    }

    private fun ensureChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Entify 백그라운드 감시 (상태 표시)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "10초 주기 백그라운드 감시 서비스의 동작 상태를 조용히 표시합니다"
                setShowBadge(false)
            }
            nm.createNotificationChannel(serviceChannel)
            val channels = listOf(
                NotificationChannel(COMMENT_CHANNEL_ID, "댓글/답글 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "작품 및 스페이스 댓글/답글 알림"
                },
                NotificationChannel(LIKE_CHANNEL_ID, "좋아요/고정 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "작품 및 댓글 좋아요 알림"
                },
                NotificationChannel(FOLLOW_CHANNEL_ID, "팔로우 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "신규 팔로워 알림"
                },
                NotificationChannel(REMAKE_CHANNEL_ID, "작품 리메이크 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "내 작품 리메이크 알림 (소리/진동)"
                },
                NotificationChannel(SHARE_CHANNEL_ID, "작품 공유 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "팔로우한 사용자의 새 작품/스터디 공유 알림"
                },
                NotificationChannel(SYSTEM_CHANNEL_ID, "시스템/운영 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "엔트리 공지, 뱃지, 제재 등 시스템 알림 (감시 서비스와 분리되어 정상 수신)"
                }
            )
            channels.forEach { nm.createNotificationChannel(it) }
        }
    }

    private fun buildServiceNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Entify 알림 감시 중")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
            .apply {
                flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            }
    }

    private fun canPostUserNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun canDeliverNotification(channelId: String, tag: String): Boolean {
        if (!preferenceManager.isPushNotificationEnabled) {
            Log.d(TAG, "Push notifications disabled by user in settings; suppress push alert for tag=$tag")
            return false
        }
        if (!canPostUserNotifications()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .getNotificationChannel(channelId)
            ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            Log.w(TAG, "Channel blocked importance=NONE channel=$channelId tag=$tag")
            return false
        }
        return true
    }

    private fun isNonInteractiveNotification(template: String, deepLink: String?): Boolean {
        return template.contains("space", ignoreCase = true) ||
            (deepLink?.contains("space.playentry.org", ignoreCase = true) == true)
    }

    private fun stableId(key: String): Int {
        var hash = key.hashCode()
        hash = hash xor (hash ushr 16)
        hash *= 0x85ebca6b.toInt()
        hash = hash xor (hash ushr 13)
        hash *= 0xc2b2ae35.toInt()
        hash = hash xor (hash ushr 16)
        return hash and 0x7fffffff
    }

    private fun hasSeenTopic(topicId: String): Boolean {
        syncNotificationMemoryPolicy()
        if (!preferenceManager.rememberNotifications) return false
        if (topicId.isBlank()) return true
        synchronized(seenTopicIds) {
            return seenTopicIds.contains(topicId)
        }
    }

    private fun hasMainSeenTopic(topicId: String): Boolean {
        syncNotificationMemoryPolicy()
        if (!preferenceManager.rememberNotifications) return false
        if (topicId.isBlank()) return true
        return mainSeenPrefs.contains("topic:$topicId")
    }

    private fun markTopicSeen(topicId: String) {
        syncNotificationMemoryPolicy()
        if (!preferenceManager.rememberNotifications) return
        if (topicId.isBlank()) return
        synchronized(seenTopicIds) {
            seenTopicIds.add(topicId)
            while (seenTopicIds.size > MAX_SEEN_IDS) {
                val it = seenTopicIds.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            persistSeenIdsLocked()
        }
    }

    private fun markMainSeenTopic(topicId: String) {
        syncNotificationMemoryPolicy()
        if (!preferenceManager.rememberNotifications) return
        if (topicId.isBlank()) return
        mainSeenPrefs.edit().putLong("topic:$topicId", System.currentTimeMillis()).apply()
    }

    private fun loadSeenIds() {
        synchronized(seenTopicIds) {
            seenTopicIds.clear()
            if (!preferenceManager.rememberNotifications) return
            val raw = statePrefs.getString("seen_topic_ids_json", "[]").orEmpty()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i).trim()
                if (id.isNotBlank()) seenTopicIds.add(id)
            }
            while (seenTopicIds.size > MAX_SEEN_IDS) {
                val it = seenTopicIds.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
    }

    private fun persistSeenIdsLocked() {
        if (!preferenceManager.rememberNotifications) return
        val arr = JSONArray()
        seenTopicIds.forEach { arr.put(it) }
        statePrefs.edit().putString("seen_topic_ids_json", arr.toString()).apply()
    }

    private fun syncNotificationMemoryPolicy(force: Boolean = false) {
        val generation = preferenceManager.notificationMemoryGeneration
        val remember = preferenceManager.rememberNotifications
        val generationChanged = generation != lastAppliedNotificationMemoryGeneration
        if (!force && !generationChanged && remember) return
        lastAppliedNotificationMemoryGeneration = generation
        if (generationChanged || !remember || force) {
            synchronized(seenTopicIds) {
                seenTopicIds.clear()
            }
        }
        if (generationChanged || !remember || force) {
            statePrefs.edit().remove("seen_topic_ids_json").apply()
        }
    }
}
