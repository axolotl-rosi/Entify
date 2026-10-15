package com.example.entify_again

import android.annotation.SuppressLint
import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.Person as NotificationPerson
import org.json.JSONObject
import com.example.entify_again.util.PrivacyLog as AndroidLog
import android.widget.Toast
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import java.util.Collections
import okhttp3.OkHttpClient
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.data.entity.asArchive
import com.example.entify_again.data.repository.NotificationRepository
import com.example.entify_again.util.PreferenceManager
import com.example.entify_again.util.EntryTemplateFormatter
import com.example.entify_again.util.NotificationSemantics
import com.example.entify_again.util.UserProfileFetcher
import com.example.entify_again.util.SecurityUtil
import com.example.entify_again.util.SafeImageDecoder
import com.example.entify_again.util.SafeResponseReader
import com.example.entify_again.util.SecurityDiagnostics
import com.example.entify_again.webview.EntryOriginPolicy
import com.example.entify_again.service.EntryPollingForegroundService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.os.Message
import android.app.Dialog
import android.app.AlertDialog
import android.widget.EditText
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs
import kotlin.math.max
import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.drawable.IconCompat
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.text.TextPaint
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import com.example.entify_again.ui.theme.EntifyTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.IOException
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private fun buildLockScreenPlaceholder(channelId: String, smallIcon: Int): android.app.Notification =
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle("새 Entify 알림")
            .setContentText("잠금을 해제하여 알림 내용을 확인하세요.")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private open inner class SecureEntryWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString()
            return !EntryOriginPolicy.isAboutBlank(url) && !EntryOriginPolicy.isTrusted(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun hardenSessionWebView(target: WebView) {
        target.filterTouchesWhenObscured = true
        target.settings.apply {
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            saveFormData = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, false)
    }

    private fun installEntryMessageListener(
        target: WebView,
        javaScriptObjectName: String,
        source: String,
        isAllowedTopLevelUrl: (String?) -> Boolean,
        onMessage: (String) -> Unit
    ) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "A current Android System WebView is required for secure page messaging."
        }
        WebViewCompat.addWebMessageListener(
            target,
            javaScriptObjectName,
            setOf("https://playentry.org", "https://space.playentry.org")
        ) { view, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || logoutInProgress) return@addWebMessageListener
            if (!EntryOriginPolicy.isTrusted(sourceOrigin.toString())) return@addWebMessageListener
            if (!isAllowedTopLevelUrl(view.url)) return@addWebMessageListener
            val payload = message.data ?: return@addWebMessageListener
            if (payload.length > MAX_WEB_MESSAGE_CHARS) {
                AndroidLog.w("WEB_MESSAGE", "Rejected oversized message from $source")
                return@addWebMessageListener
            }
            runOnUiThread {
                if (!logoutInProgress && isAllowedTopLevelUrl(view.url)) {
                    onMessage(payload)
                }
            }
        }
    }

    private fun isTrustedEntryNavigation(uri: Uri?): Boolean {
        if (uri == null) return false
        val url = uri.toString()
        return EntryOriginPolicy.isAboutBlank(url) || EntryOriginPolicy.isTrusted(url)
    }

    private fun renderProcessCrashState(detail: RenderProcessGoneDetail?): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail?.didCrash() else null
    private val client = OkHttpClient()
    private val entryUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private lateinit var preferenceManager: PreferenceManager
    private val notificationRepository by lazy { NotificationRepository(this) }
    private var startupToastShown = false
    companion object {
        private const val MAX_WEB_MESSAGE_CHARS = 1_048_576
        @Volatile
        var isAppInForeground: Boolean = false
    }

    // ✅ 알림 채널 ID들 (타입별 분리)
    private val commentChannelId = "entry_comment_channel"
    private val likeChannelId = "entry_like_channel"
    private val followChannelId = "entry_follow_channel"
    private val mentionChannelId = "entry_mention_channel"
    private val shareChannelId = "entry_share_channel"
    private val remakeChannelId = "entry_remake_channel"
    private val systemChannelId = "entry_system_channel"
    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }
    private var mainWebView: WebView? = null
    private var previousUrl: String? = null  // ✅ 이전 URL 추적 (로그인 감지용)
    private var pendingOpenUrl: String? = null
    private var pendingFindUser: String? = null
    private var pendingFindTime: String? = null
    private var pendingNotifFetchAfterLogin: Boolean = false
    private val seen by lazy { getSharedPreferences("seen_notifs", MODE_PRIVATE) }
    private val seenTopicTtlMs: Long = 7L * 24L * 60L * 60L * 1000L
    private var seenRecoveryAttempted = false
    private var lastAppliedNotificationMemoryGeneration: Long = -1L
    private val seenSkippedTopicBuffer = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            while (size > 300) {
                val first = iterator()
                if (first.hasNext()) {
                    first.next()
                    first.remove()
                } else {
                    break
                }
            }
            return added
        }
    }
    private var lastNotifAt: Long = 0L
    private var lastNewNotifLogAt: Long = 0L
    private val minNotifIntervalMs: Long = 1000L
    private var lastDeepLink: String? = null
    private var lastLinkCategory: String? = null
    private var lastLinkTarget: String? = null
    private var lastUserNickname: String? = null
    private var lastTopicId: String? = null
    private var lastCreated: String? = null
    private var preferredProfileUrl: String? = null
    private var pendingAllowNotify: Boolean = false
    private var notifiedCurrent: Boolean = false
    private var findProfileUrl: String? = null
    private val avatarCache by lazy { LruCache<String, Bitmap>(6) }
    @Volatile private var lastTokenReadyAt: Long = 0L
    @Volatile private var pendingTokenReady: CompletableDeferred<Boolean>? = null
    // 최적화 토글 (테스트 목적: 기본 비활성화)
    private val USE_HTTP_DISK_CACHE = false
    private val ENABLE_USER_AVATAR_KEY_CACHE = false
    private val userAvatarBitmapByKey = LruCache<String, Bitmap>(16) // userKey -> bitmap (비활성화 기본)
    private val followDeepLinks = mutableMapOf<String, String>() // userId -> deepLink
    private val followNickById = mutableMapOf<String, String>() // userId -> nickname
    private val notifAvatarUrl = mutableMapOf<String, String>() // tag -> url (덮어쓰기 방지용)
    private val followProfileApplied = java.util.Collections.synchronizedSet(mutableSetOf<String>()) // 한 유저당 한 번만 적용
    private val followInProgress = mutableSetOf<String>() // 진행중인 팔로우 처리 (중복 방지용)
    private val likeLargeIconBitmap by lazy {
        runCatching {
            BitmapFactory.decodeResource(resources, R.drawable.like_icon_light)?.let { getCircularBitmap(it, 128) }
        }.getOrNull()
    }
    private val likeCommentTopicToKey = mutableMapOf<String, String>()
    private val likeCommentFetchInProgress = mutableSetOf<String>()
    private var hiddenWebView: WebView? = null // 전용 숨김 WebView
    private var alarmWebView: WebView? = null   // 알림 수집용 숨김 WebView
    private var tokenWebView: WebView? = null   // 토큰 수집용 숨김 WebView
    private var followGraphqlWebView: WebView? = null // FOLLOW GraphQL 전용 WebView
    private var followGraphqlLoadingUserId: String? = null
    private var followGraphqlReadyUserId: String? = null
    private var followGraphqlPageDeferred: CompletableDeferred<Boolean>? = null
    private var shareGraphqlWebView: WebView? = null
    private var shareGraphqlLoadingProjectId: String? = null
    private var shareGraphqlReadyProjectId: String? = null
    private var shareGraphqlPageDeferred: CompletableDeferred<Boolean>? = null
    private var followGraphqlLastUrl: String? = null
    private var shareGraphqlLastUrl: String? = null
    private var lastCookieRestoreAt: Long = 0L
    private val cookieRestoreLock = Any()
    private var cookieGeneration = 0
    private val webViewCookieGeneration = WeakHashMap<WebView, Int>()
    private var cookiesPrimedGlobally = false
    private var tokenBootstrapDeferred: CompletableDeferred<Boolean>? = null
    private var tokenBootstrapPending = false
    private var lastLoginRedirectAt = 0L
    private var lastTriggerToastAt = 0L
    @Volatile private var logoutInProgress = false
    private var webViewGeneration by mutableIntStateOf(0)

    private fun postDebugTriggerNotification() {
        val key = "TEST_TRIGGER"
        val title = "알림 트리거 테스트"
        val text = "알림 시스템이 정상 동작 중입니다."
        val whenMillis = System.currentTimeMillis()
        postSimpleNotification(
            key = key,
            title = title,
            text = text,
            whenMillis = whenMillis,
            openUrl = "https://playentry.org/"
        )
    }

    private fun testSelectTopicsOnce() {
        val xToken = preferenceManager.xToken.orEmpty()
        val csrfToken = preferenceManager.csrfToken.orEmpty()
        if (xToken.isBlank() || csrfToken.isBlank()) {
            showTriggerToastNow("SELECT_TOPICS: 토큰 없음")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val query = """
                query SELECT_TOPICS(${'$'}pageParam: PageParam){
                  topicList(pageParam: ${'$'}pageParam){
                    list{ id template created }
                  }
                }
                """.trimIndent()
                val variables = JSONObject().put("pageParam", JSONObject().put("display", 20))
                val bodyJson = JSONObject()
                    .put("query", query)
                    .put("variables", variables)
                    .toString()
                val body = bodyJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://playentry.org/graphql/SELECT_TOPICS")
                    .addHeader("accept", "*/*")
                    .addHeader("content-type", "application/json")
                    .addHeader("origin", "https://playentry.org")
                    .addHeader("referer", "https://playentry.org/alarm")
                    .addHeader("user-agent", "Mozilla/5.0 (Android) Entify/1.0")
                    .addHeader("x-token", xToken)
                    .addHeader("csrf-token", csrfToken)
                    .addHeader("x-client-type", "Client")
                    .post(body)
                    .build()
                val respText = client.newCall(request).execute().use {
                    SafeResponseReader.readText(it.body).orEmpty()
                }
                val listLen = runCatching {
                    val root = JSONObject(respText)
                    root.optJSONObject("data")?.optJSONObject("topicList")?.optJSONArray("list")?.length() ?: 0
                }.getOrElse { 0 }
                showTriggerToastNow("SELECT_TOPICS: ${listLen}건")
            } catch (e: Exception) {
                showTriggerToastNow("SELECT_TOPICS 오류: ${e.message}")
            }
        }
    }

    private fun triggerNotificationPollingNow() {
        AndroidLog.d("TRIGGER", "Manual notification trigger")
        showTriggerToastNow("알림 감지 트리거")
        showTriggerToastNow("로그인 확인 중...")
        if (!checkLoginStatus()) {
            showTriggerToastNow("로그인/토큰 없음")
            runTokenExtractorIfReady("manual_trigger")
            return
        }
        showTriggerToastNow("토큰 있음")
        startNotificationLogicIfLoggedIn(force = true)
        showTriggerToastNow("알림 로직 호출")
        runTokenExtractorIfReady("manual_trigger")
        showTriggerToastNow("토큰 추출 시도")
        mainWebView?.post {
            val currentUrl = mainWebView?.url.orEmpty()
            if (isMainContentUrl(currentUrl) && shouldUseMainNotifPoller()) {
                try {
                    mainWebView?.evaluateJavascript(jsNotifPoller, null)
                    showTriggerToastNow("메인 폴러 주입")
                } catch (_: Exception) {}
            }
        }
        startAlarmBackgroundPoller(forceReload = true)
        showTriggerToastNow("폴러 시작 호출")
    }

    private fun showTriggerToastNow(message: String) {
        AndroidLog.d("TRIGGER_TOAST", message)
    }
    private var alarmPollerStarted = false
    private var alarmPollerPending = false
    private var alarmTokenBootstrapJs: String? = null
    private var forceMainPoller = false // 기본은 alarm WebView 폴러 사용
    private var showPollerDebugToast = false
    private var isAppForeground = false

    private fun isAlarmPollerActive(): Boolean = alarmPollerPending || alarmPollerStarted
    private fun shouldUseMainNotifPoller(): Boolean = forceMainPoller || !isAlarmPollerActive()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var alarmStartRunnable: Runnable? = null
    private var alarmRecoveryRunnable: Runnable? = null
    private var alarmRestartBackoffMs: Long = 0L
    private var lastAlarmAuthRecoveryAt: Long = 0L
    private var consecutivePollAuthErrors: Int = 0
    private var lastPollDebugPayload: String = ""
    private val POLL_AUTH_ERROR_LOGOUT_THRESHOLD = 5
    private var lastLoginOkHandledAt: Long = 0L
    private var lastLoginTimeMs: Long = 0L
    private val LOGIN_GRACE_PERIOD_MS = 30_000L

    // ✅ 통합 프로필 추출 시스템 (모든 알림 타입 지원)
    private data class ProfileNotifInfo(
        val tag: String,
        val notifId: Int,
        val username: String,
        val userId: String?,  // 작품 공유는 projectId 사용, 팔로잉은 userId 사용
        val deepLink: String,
        val notifType: String,  // "SHARE", "FOLLOW", "REPLY", "LIKE_COMMENT"
        val notifTitle: String,
        val notifText: String,
        val whenMillis: Long = System.currentTimeMillis(),  // 알림 생성 시간 저장
        val template: String? = null,  // 원본 템플릿 (예: template_27, template_28)
        val topicId: String? = null
    )

    private data class ShareProfileGraphqlResult(
        val avatarUrl: String,
        val nickname: String?,
        val thumbUrl: String? = null
    )
    private val profileNotifInfoByKey = mutableMapOf<String, ProfileNotifInfo>()
    private val profilePendingUpdates = mutableMapOf<String, MutableList<ProfileNotifInfo>>() // 동일 key 알림 보조 업데이트
    private val profileQueue: ArrayDeque<String> = ArrayDeque()
    private val profileInProgress = mutableSetOf<String>()
    private var profileWebView: WebView? = null
    private var profileWebViewBusy = false
    private var currentProfileKey: String? = null
    private val profileTimeoutRunnables = mutableMapOf<String, Runnable>()
    private var pendingTokenRefresh: CompletableDeferred<Boolean>? = null
    private val pendingUserProfileRequests = mutableMapOf<String, CompletableDeferred<String?>>()
    private val pendingFollowGraphqlRequests = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<GraphqlCallResult<String>>>())
    private val pendingShareGraphqlRequests = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<GraphqlCallResult<ShareProfileGraphqlResult>>>())
    private val followGraphqlMutex = Mutex()
    private val followGraphqlWebViewMutex = Mutex()
    private val shareGraphqlMutex = Mutex()
    private val shareGraphqlWebViewMutex = Mutex()
    private val tokenRefreshMutex = Mutex()
    private val shareDomFallbackType = "SHARE_DOM"
    private var notificationLogicStarted = false
    private var startupFlowJob: Job? = null
    private var pendingGraphqlTokenRequest: CompletableDeferred<Pair<String, String>?>? = null

    private fun isShareProfileType(type: String): Boolean =
        type == "SHARE" || type == "REMAKE" || type == shareDomFallbackType

    private fun isProfileExtractionEnabled(type: String): Boolean =
        type == "FOLLOW" || type == "REPLY" || isShareProfileType(type)

    // 알림 과폭 방지 큐 (안드로이드가 mute로 강등하지 않도록 간격 보장)
    private val notificationDeliveryQueue = ArrayDeque<() -> Unit>()
    private var notificationDeliveryActive = false
    private var nextAllowedNotificationAt = 0L
    private val NOTIF_MIN_INTERVAL_MS = 150L
    private val NOTIF_BURST_LIMIT = 3
    private val NOTIF_BURST_WINDOW_MS = 1000L
    private val graphqlParityLock = Any()
    private val graphqlParityResults = mutableMapOf<String, MutableMap<String, GraphqlParitySnapshot>>()
    private val recentNotificationTimestamps = ArrayDeque<Long>()

    // ✅ 드라이런: 링크 이동/댓글 분석/알림 발송 전부 끄고 "수신만" 검사
    private var DRY_RUN_NOTIF_ONLY = false
    private var SHOW_DRYRUN_TOAST = false   // 토스트 팝업 토글 (기본 끔)
    private val dryRunReceived = mutableSetOf<String>() // topicId를 모아 중복 제거
    private val dryRunSeen = mutableSetOf<String>()  // 드라이런용 중복 방지

    // 🔧 템플릿 → 유형 매핑 (4종 정확히 분류)
    private val TEMPLATE_COMMENT_PROJECT = setOf("template_1")
    private val TEMPLATE_COMMENT_REPLY   = setOf("template_34")  // 내 댓글에 답글
    private val TEMPLATE_COMMENT_SPACE   = setOf("template_space_1")
    private val TEMPLATE_LIKE_PROJECT    = setOf("template_3")
    private val TEMPLATE_LIKE_COMMENT    = setOf("template_36")
    private val TEMPLATE_PIN_PROJECT     = setOf("template_37")
    private val TEMPLATE_SHARE_PROJECT   = setOf("template_28", "template_29")  // 작품 공유
    private val TEMPLATE_REMAKE_PROJECT  = setOf("template_5", "template_6")   // 작품 리메이크
    private val TEMPLATE_FOLLOW          = setOf("template_27")
    private val COMMUNITY_LINK_CATEGORIES = setOf("qna", "tips", "entrystory", "notice", "entrylife", "free", "discuss")
    private val HEX_OBJECT_ID            = Regex("^[0-9a-f]{24}$", RegexOption.IGNORE_CASE)
    private val PROFILE_TEMPLATE_WHITELIST = setOf("template_27", "template_28", "template_29", "template_5", "template_6")

    // 공통 알림 큐 구조체 (순차 처리)
    private sealed class NotifTask {
        data class Comment(val item: CommentNotifItem) : NotifTask()
        data class Follow(
            val user: String,
            val time: String,
            val deep: String,
            val userIdHint: String?
        ) : NotifTask()
    }
    
    private suspend fun handleTemplate1Graphql(item: CommentNotifItem): Boolean {
        commentPhase = "T1_NATIVE_GRAPHQL_START"
        commentPhaseTopic = item.topicId
        AndroidLog.d("COMMENT_FLOW", "[1] native GraphQL start topic=${item.topicId}")
        val projectId = item.linkTarget?.takeIf { it.isNotBlank() }
            ?: Regex("/project/([0-9a-f]{24})").find(item.deepLink)?.groupValues?.get(1)
            ?: run {
                AndroidLog.w("COMMENT_GRAPHQL", "template_1 without projectId (topic=${item.topicId})")
                AndroidLog.w("COMMENT_FLOW", "[1] native GraphQL fail: no projectId topic=${item.topicId}")
                return false
            }

        // 1) 네이티브 GraphQL (WebView 우회)
        // ✅ 403으로 토큰이 만료된 경우 fetchTemplate1ViaNativeGraphql 내부에서 토큰을 갱신하고
        // 재시도하는데, WebView 기반 토큰 갱신은 수 초가 걸릴 수 있어 여유 있게 잡는다.
        val nativePayload = withTimeoutOrNull(22000) {
            fetchTemplate1ViaNativeGraphql(
                targetId = projectId,
                item = item,
                refererUrl = buildProjectUrl(projectId),
                matchTypeHint = "comment"
            )
        }
        if (nativePayload != null) {
            AndroidLog.d(
                "COMMENT_GRAPHQL",
                "template_1 native GraphQL success topic=${item.topicId} user=${nativePayload.nick}"
            )
            AndroidLog.d("COMMENT_FLOW", "[1] native GraphQL success topic=${item.topicId}")
            withContext(Dispatchers.Main) {
                val jo = JSONObject()
                    .put("userId", nativePayload.userId)
                    .put("nickname", nativePayload.nick)
                    .put("content", nativePayload.content)
                    .put("profile", nativePayload.profile)
                    .put("link", nativePayload.link)
                    .put("time", nativePayload.time)
                    .put("matchType", nativePayload.matchType)
                handleCommentResultJson(item.topicId, jo)
            }
            return true
        } else {
            AndroidLog.w(
                "COMMENT_GRAPHQL",
                "template_1 native GraphQL miss topic=${item.topicId} - fallback to WebView"
            )
            AndroidLog.w("COMMENT_FLOW", "[1] native GraphQL miss topic=${item.topicId}")
        }

        val deferred = CompletableDeferred<Boolean>()
        template1GraphqlDeferred?.cancel()
        template1GraphqlDeferred = deferred
        template1GraphqlTopicId = item.topicId
        val createdIso = item.created.takeIf { it.isNotBlank() } ?: ""
        AndroidLog.d(
            "COMMENT_GRAPHQL",
            "template_1 GraphQL start topic=${item.topicId} user=${item.actorName} project=$projectId created=$createdIso"
        )
        commentPhase = "T1_WEBVIEW_GRAPHQL_START"
        commentPhaseTopic = item.topicId
        AndroidLog.d("COMMENT_FLOW", "[2] hidden WebView GraphQL start topic=${item.topicId}")

        val success = try {
            withContext(Dispatchers.Main) {
                hiddenLaunched = true
                executeFindCommentInHiddenWebView(
                    topicId = item.topicId,
                    targetId = projectId,
                    pageUrl = buildProjectUrl(projectId),
                    userHint = item.actorName,
                    createdIso = createdIso,
                    createdDom = createdIso,  // ✅ ISO만 사용
                    mode = "comment",
                    enableDomScan = false
                )
            }

            withTimeoutOrNull(6000) {
                deferred.await()
            } ?: false
        } finally {
            template1GraphqlDeferred = null
            template1GraphqlTopicId = null
            hiddenLaunched = false
        }
        AndroidLog.d(
            "COMMENT_GRAPHQL",
            "template_1 GraphQL ${if (success) "success" else "timeout/fail"} topic=${item.topicId}"
        )
        if (!success) {
            AndroidLog.w("COMMENT_FLOW", "[2] hidden WebView GraphQL fail topic=${item.topicId}")
        } else {
            AndroidLog.d("COMMENT_FLOW", "[2] hidden WebView GraphQL success topic=${item.topicId}")
        }
        return success
    }

    private suspend fun handleTemplateSpaceNativeGraphql(item: CommentNotifItem): Boolean {
        commentPhase = "SPACE_NATIVE_GRAPHQL_START"
        commentPhaseTopic = item.topicId
        AndroidLog.d("COMMENT_FLOW", "[1] space native GraphQL start topic=${item.topicId}")

        val targetId = resolveCommentTargetId(item)
        if (!HEX_OBJECT_ID.matches(targetId)) {
            AndroidLog.w("COMMENT_GRAPHQL", "template_space_1 without valid targetId (topic=${item.topicId})")
            return false
        }

        val pageUrl = resolveCommentPageUrl(item)
        val nativePayload = withTimeoutOrNull(22000) {
            fetchTemplate1ViaNativeGraphql(
                targetId = targetId,
                item = item,
                refererUrl = pageUrl,
                matchTypeHint = "comment"
            )
        }
        if (nativePayload == null) {
            AndroidLog.w("COMMENT_GRAPHQL", "template_space_1 native GraphQL miss topic=${item.topicId}")
            return false
        }

        AndroidLog.d("COMMENT_GRAPHQL", "template_space_1 native GraphQL success topic=${item.topicId}")
        withContext(Dispatchers.Main) {
            val jo = JSONObject()
                .put("userId", nativePayload.userId)
                .put("nickname", nativePayload.nick)
                .put("content", nativePayload.content)
                .put("profile", nativePayload.profile)
                .put("link", nativePayload.link)
                .put("time", nativePayload.time)
                .put("matchType", nativePayload.matchType)
            handleCommentResultJson(item.topicId, jo)
        }
        return true
    }

    private suspend fun fetchTemplate1ViaNativeGraphql(
        targetId: String,
        item: CommentNotifItem,
        refererUrl: String,
        matchTypeHint: String = "comment"
    ): CommentPayload? {
        var xToken = preferenceManager.xToken.orEmpty()
        var csrfToken = preferenceManager.csrfToken.orEmpty()
        if (xToken.isBlank() || csrfToken.isBlank()) {
            AndroidLog.w("COMMENT_GRAPHQL", "native GraphQL missing tokens (topic=${item.topicId})")
            return null
        }

        val query = """
        query SELECT_COMMENTS(${'$'}pageParam: PageParam, ${'$'}target: String, ${'$'}searchAfter: JSON) {
          commentList(pageParam: ${'$'}pageParam, target: ${'$'}target, searchAfter: ${'$'}searchAfter) {
            searchAfter
            list {
              id
              content
              created
              user { id nickname profileImage { filename imageType } }
              image { filename }
              sticker { filename }
            }
          }
        }
        """.trimIndent()

        val targetUserId = preferenceManager.commentUserId?.trim().orEmpty()
        val targetNick = item.actorName.trim()
        val useUid = looksLikeUid(targetUserId)
        val useNick = targetNick.isNotBlank()
        if (!useUid && !useNick) {
            AndroidLog.w("COMMENT_GRAPHQL", "native GraphQL missing comment UID/nick (topic=${item.topicId})")
            return null
        }
        val targetMs = item.created.takeIf { it.isNotBlank() }?.let { parseWhen(it) }
        val toleranceMs = 300_000L
        val maxFallbackWindowMs = 86_400_000L
        var searchAfter: JSONObject? = null
        var page = 0
        var best: Pair<Long, JSONObject>? = null
        var candidateCount = 0

        val cookieHeader = try {
            CookieManager.getInstance().getCookie("https://playentry.org").orEmpty()
        } catch (_: Exception) { "" }

        while (page < 10) {
            val pageParam = JSONObject()
                .put("display", 20)
                .put("sort", "created")
                .put("order", -1)
            val variables = JSONObject()
                .put("pageParam", pageParam)
                .put("target", targetId)
            searchAfter?.let { variables.put("searchAfter", it) }

            val bodyJson = JSONObject()
                .put("query", query)
                .put("variables", variables)
                .toString()

            fun buildRequest(xt: String, ct: String): Request {
                val body = bodyJson.toRequestBody("application/json".toMediaType())
                return Request.Builder()
                    .url("https://playentry.org/graphql/SELECT_COMMENTS")
                    .addHeader("accept", "*/*")
                    .addHeader("content-type", "application/json")
                    .addHeader("origin", "https://playentry.org")
                    .addHeader("referer", sanitizeEntryUrl(refererUrl, "https://playentry.org/project/$targetId"))
                    .addHeader("user-agent", "Mozilla/5.0 (Android) Entify/1.0")
                    .addHeader("x-token", xt)
                    .addHeader("csrf-token", ct)
                    .addHeader("x-client-type", "Client")
                    .apply {
                        if (cookieHeader.isNotBlank()) {
                            addHeader("cookie", cookieHeader)
                        }
                    }
                    .post(body)
                    .build()
            }

            var respCode = 0
            var respText = withContext(Dispatchers.IO) {
                client.newCall(buildRequest(xToken, csrfToken)).execute().use { resp ->
                    respCode = resp.code
                    SafeResponseReader.readText(resp.body).orEmpty()
                }
            }
            // ✅ 403(토큰 만료)이면 토큰을 갱신한 뒤 "이번 시도 안에서" 즉시 한 번 재시도한다.
            // 기존에는 갱신만 해두고 그냥 null을 반환해서, 다음 재시도(최대 40초 뒤)까지
            // 댓글 내용을 못 가져오는 원인이었다.
            if (respCode == 403) {
                AndroidLog.w("COMMENT_GRAPHQL", "native HTTP 403 topic=${item.topicId} - refreshing token & retrying once")
                val refreshed = runCatching {
                    ensureTokensForGraphql(forceRefresh = true, reason = "comment_graphql_403")
                }.getOrDefault(false)
                if (refreshed) {
                    xToken = preferenceManager.xToken.orEmpty()
                    csrfToken = preferenceManager.csrfToken.orEmpty()
                    if (xToken.isNotBlank() && csrfToken.isNotBlank()) {
                        respText = withContext(Dispatchers.IO) {
                            client.newCall(buildRequest(xToken, csrfToken)).execute().use { resp ->
                                respCode = resp.code
                                SafeResponseReader.readText(resp.body).orEmpty()
                            }
                        }
                    }
                }
                if (respCode != 200) {
                    AndroidLog.w("COMMENT_GRAPHQL", "native HTTP still ${respCode} after retry topic=${item.topicId}")
                }
            } else if (respCode != 200) {
                AndroidLog.w("COMMENT_GRAPHQL", "native HTTP ${respCode} topic=${item.topicId}")
            }
            if (respText.isBlank()) return null

            val list = runCatching {
                val root = JSONObject(respText)
                val errors = root.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    AndroidLog.w("COMMENT_GRAPHQL", "native errors topic=${item.topicId} err=${errors.optJSONObject(0)?.optString("message")}")
                }
                val commentList = root.optJSONObject("data")?.optJSONObject("commentList")
                searchAfter = commentList?.opt("searchAfter")?.let { it as? JSONObject }
                commentList?.optJSONArray("list")
            }.getOrNull() ?: return null

            if (list.length() == 0) break

            for (i in 0 until list.length()) {
                val node = list.optJSONObject(i) ?: continue
                val uid = node.optJSONObject("user")?.optString("id").orEmpty()
                val nick = node.optJSONObject("user")?.optString("nickname").orEmpty()
                val matchUid = useUid && uid.isNotBlank() && uid.equals(targetUserId, ignoreCase = true)
                val matchNick = useNick && nick.isNotBlank() && nick.equals(targetNick, ignoreCase = true)
                if (useUid || useNick) {
                    if (!matchUid && !matchNick) continue
                }
                candidateCount++
                val created = node.optString("created")
                val createdMs = created.takeIf { it.isNotBlank() }?.let { parseWhen(it) } ?: 0L
                val diff = if (targetMs != null) kotlin.math.abs(createdMs - targetMs) else 0L
                val bestDiff = best?.first ?: Long.MAX_VALUE
                if (diff <= bestDiff) best = diff to node
                if (targetMs != null && diff <= toleranceMs) {
                    // close enough, can stop early
                    best = diff to node
                    break
                }
            }

            if (best != null && (targetMs == null || best!!.first <= toleranceMs)) break
            if (searchAfter == null) break
            page++
        }

        val hit = best?.second ?: return null
        if (targetMs != null && best!!.first > toleranceMs && best!!.first > maxFallbackWindowMs) {
            AndroidLog.w("COMMENT_GRAPHQL", "native closest too far (diffMs=${best!!.first}) topic=${item.topicId} candidates=$candidateCount")
            return null
        }
        if (targetMs != null && best!!.first > toleranceMs && candidateCount == 1) {
            AndroidLog.w(
                "COMMENT_GRAPHQL",
                "native accepting single candidate despite time diffMs=${best!!.first} topic=${item.topicId}"
            )
        } else if (targetMs != null && best!!.first > toleranceMs) {
            AndroidLog.w(
                "COMMENT_GRAPHQL",
                "native time diff over tolerance (diffMs=${best!!.first}) topic=${item.topicId} candidates=$candidateCount"
            )
        }
        val profileObj = hit.optJSONObject("user")?.optJSONObject("profileImage")
        val profRaw = profileObj?.optString("filename").orEmpty()
        val profType = profileObj?.optString("imageType").orEmpty()
        val profUrl = when {
            profRaw.startsWith("http", ignoreCase = true) -> profRaw
            profRaw.isNotBlank() -> buildUploadUrlFromSlug(profRaw, profType)
            else -> ""
        }

        return CommentPayload(
            userId = hit.optJSONObject("user")?.optString("id").orEmpty(),
            nick = hit.optJSONObject("user")?.optString("nickname").orEmpty(),
            content = hit.optString("content").orEmpty(),
            profile = profUrl,
            link = sanitizeEntryUrl(refererUrl, buildProjectUrl(targetId)),
            time = hit.optString("created").orEmpty(),
            matchType = matchTypeHint
        )
    }

    private fun completeTemplate1Graphql(result: Boolean, topicHint: String? = null) {
        val targetTopic = template1GraphqlTopicId ?: return
        val candidate = topicHint ?: lastTopicId ?: ""
        AndroidLog.d("COMMENT_GRAPHQL", "Completing GraphQL for topic=$targetTopic result=$result payloadTopic=$candidate")
        if (candidate.isNotBlank() && !candidate.equals(targetTopic, ignoreCase = true)) {
            AndroidLog.w("COMMENT_GRAPHQL", "GraphQL topic mismatch expected=$targetTopic got=$candidate")
            return
        }
        template1GraphqlDeferred?.takeIf { it.isActive }?.complete(result)
    }


    private fun startDomCommentPipeline(item: CommentNotifItem, onDone: () -> Unit, enableDomScan: Boolean = false) {
        commentPhase = "GRAPHQL_ONLY_START"
        commentPhaseTopic = item.topicId
        AndroidLog.d("COMMENT_FLOW", "[3] GraphQL-only start topic=${item.topicId}")
        // ✅ 1단계: NO-NAV(JS) 시도
        executeFindCommentJS(item)

        // ✅ 2단계: 2초 후 NO-NAV 실패 시 숨김 WebView 폴백 실행
        lifecycleScope.launch {
            delay(2000) // NO-NAV 타임아웃 (5초 → 2초로 단축)

            // NO-NAV에서 COMMENT_RESULT가 안 왔으면 숨김 WebView 폴백 실행
            if (!notifiedCurrent && !hiddenLaunched) {
                AndroidLog.d("COMMENT_FALLBACK", "NO-NAV 실패, 숨김 WebView 폴백 실행")
                val targetId = resolveCommentTargetId(item)
                val pageUrl = resolveCommentPageUrl(item)
                val createdIso = normalizeIsoTime(item.created)
                val mode = resolveCommentSearchMode(item.template)
                hiddenLaunched = true  // 중복 실행 방지
                executeFindCommentInHiddenWebView(
                    topicId = item.topicId,
                    targetId = targetId,
                    pageUrl = pageUrl,
                    userHint = item.actorName,
                    createdIso = createdIso,
                    createdDom = createdIso,
                    mode = mode,
                    enableDomScan = false
                )

                // 추가 2초 대기 (총 4초)
                delay(2000)
            }

            // ✅ 완료 후 플래그 리셋
            hiddenLaunched = false
            onDone()
        }
    }

    private data class CommentNotifItem(
        val linkJson: String,
        val actorName: String,
        val topicId: String,
        val deepLink: String,
        val created: String,
        val template: String,
        val linkTarget: String?,
        val linkCategoryHint: String? = null,
        val titleHint: String? = null
    )

    private fun normalizeLinkCategory(raw: String?): String? {
        val category = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (!NotificationSemantics.isNavigationCategory(category)) return null
        return when (category) {
            "follow" -> "following"
            "space_explore" -> "space"
            else -> category
        }
    }

    private fun normalizeJsonToken(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return ""
        return when (value.lowercase(Locale.ROOT)) {
            "null", "undefined", "nan" -> ""
            else -> value
        }
    }

    private fun buildEntryUrlByCategory(categoryRaw: String?, targetRaw: String?): String? {
        val base = "https://playentry.org"
        val spaceBase = "https://space.playentry.org"
        val target = normalizeJsonToken(targetRaw)
        if (target.isBlank()) return null

        val category = normalizeLinkCategory(categoryRaw)
        if (!categoryRaw.isNullOrBlank() && category == null) return null
        return when {
            category == null -> "$base/project/$target"
            category == "project" || category == "comment" -> "$base/project/$target"
            category == "space" -> "$spaceBase/world/$target"
            category in COMMUNITY_LINK_CATEGORIES -> "$base/community/$category/$target"
            category == "following" || category == "user" -> "$base/profile/$target/project?sort=created&term=all&isOpen=all"
            else -> null
        }
    }

    private fun resolveCommentTargetId(item: CommentNotifItem): String {
        val fromLink = normalizeJsonToken(item.linkTarget)
        if (HEX_OBJECT_ID.matches(fromLink)) return fromLink
        val fromDeepLink = normalizeJsonToken(extractTargetIdFromUrl(item.deepLink))
        if (HEX_OBJECT_ID.matches(fromDeepLink)) return fromDeepLink
        return ""
    }

    private fun resolveCommentPageUrl(item: CommentNotifItem): String {
        val normalized = normalizeEntryUrl(item.deepLink)
        val hasInvalidNullPath = normalized?.contains("/null", ignoreCase = true) == true
        if (!normalized.isNullOrBlank() && !normalized.endsWith("/alarm") && !hasInvalidNullPath) return normalized
        val targetId = resolveCommentTargetId(item)
        return buildEntryUrlByCategory(item.linkCategoryHint, targetId)
            ?: when {
                normalizeLinkCategory(item.linkCategoryHint) == "space" -> "https://space.playentry.org/"
                targetId.isNotBlank() -> "https://playentry.org/project/$targetId"
                else -> "https://playentry.org/"
            }
    }

    private fun rememberCommentLinkInfo(item: CommentNotifItem) {
        lastLinkCategory = null
        lastLinkTarget = normalizeJsonToken(item.linkTarget).takeIf { it.isNotBlank() }
        if (item.linkJson.isNotBlank()) {
            try {
                val obj = JSONObject(item.linkJson)
                normalizeLinkCategory(obj.optString("category"))?.let { lastLinkCategory = it }
                normalizeJsonToken(obj.optString("target")).takeIf { it.isNotBlank() }?.let { lastLinkTarget = it }
            } catch (e: Exception) {
                AndroidLog.w("COMMENT_LINK", "Failed to parse linkJson: ${e.message}")
            }
        }
        if (lastLinkCategory.isNullOrBlank()) {
            lastLinkCategory = normalizeLinkCategory(item.linkCategoryHint)
        }
        if (lastLinkCategory.isNullOrBlank() && item.template.equals("template_1", true)) {
            lastLinkCategory = "project"
        }
    }

    private fun buildCommentDeepLinkFromLast(): String? {
        val target = lastLinkTarget?.takeIf { it.isNotBlank() } ?: return null
        return buildEntryUrlByCategory(lastLinkCategory, target) ?: "https://playentry.org/project/$target"
    }
    private val notifQueue = Collections.synchronizedList(mutableListOf<NotifTask>())
    private var isProcessingNotif = false
    private var domDetectGen = 0  // DOM 감지 무효화 토큰
    private var hiddenLaunched = false  // 숨김 WebView 중복 실행 방지
    private var template1GraphqlDeferred: CompletableDeferred<Boolean>? = null
    private var template1GraphqlTopicId: String? = null
    private var expectedCommentUser: String? = null // 댓글 알림에서 받은 작성자 닉네임
    private var commentPhase: String? = null
    private var commentPhaseTopic: String? = null
    private var commentUidDialogShowing = false
    private var pendingCommentForUid: CommentNotifItem? = null
    private var pendingCommentOnDone: (() -> Unit)? = null
    private val hiddenWebViewQueue = ArrayDeque<() -> Unit>()
    private var hiddenWebViewBusy = false
    private var hiddenWebViewTaskId: String? = null
    private var hiddenWebViewTimeout: Runnable? = null
    
    // ✅ ISO 시간만 사용 - DOM 표기 변환 불필요
    private fun normalizeForDom(time: String): String {
        // ISO 시간을 그대로 반환 (GraphQL에서 사용)
        return time
    }

    private fun normalizeIsoTime(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return raw
        if (s.contains("T") && s.endsWith("Z", ignoreCase = true)) return s
        val patterns = arrayOf("M/d/yyyy, h:mm:ss a", "M/d/yyyy, h:mm a")
        for (p in patterns) {
            try {
                val parser = java.text.SimpleDateFormat(p, java.util.Locale.US)
                parser.isLenient = true
                val date = parser.parse(s) ?: continue
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return fmt.format(date)
            } catch (_: Exception) { }
        }
        return raw
    }

    private fun looksLikeUid(uid: String?): Boolean {
        if (uid.isNullOrBlank()) return false
        return uid.matches(Regex("^[0-9a-f]{24}$", RegexOption.IGNORE_CASE))
    }

    private fun ensureCommentUidOrPrompt(item: CommentNotifItem, onDone: () -> Unit): Boolean {
        val stored = preferenceManager.commentUserId?.trim().orEmpty()
        if (looksLikeUid(stored)) return true

        pendingCommentForUid = item
        pendingCommentOnDone = onDone

        if (commentUidDialogShowing) return false
        commentUidDialogShowing = true

        runOnUiThread {
            val input = EditText(this).apply {
                hint = "예: 61409f26389b55ababc8d4f4"
            }

            val dialog = AlertDialog.Builder(this)
                .setTitle("댓글 UID 입력 필요")
                .setMessage("댓글 알림 처리를 위해 사용자 UID(24자리 16진수)가 필요합니다.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("저장", null)
                .create()

            dialog.setOnShowListener {
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.setOnClickListener {
                    val uid = input.text?.toString()?.trim().orEmpty()
                    if (!looksLikeUid(uid)) {
                        input.error = "UID 형식이 올바르지 않습니다"
                        return@setOnClickListener
                    }

                    preferenceManager.commentUserId = uid
                    commentUidDialogShowing = false
                    dialog.dismiss()

                    val pending = pendingCommentForUid
                    val pendingDone = pendingCommentOnDone
                    pendingCommentForUid = null
                    pendingCommentOnDone = null

                    if (pending != null && pendingDone != null) {
                        processComment(pending, pendingDone)
                    }
                }
            }

            dialog.show()
        }
        return false
    }

    private fun enqueueHiddenWebViewTask(topicId: String, task: () -> Unit) {
        if (hiddenWebViewBusy) {
            hiddenWebViewQueue.add { enqueueHiddenWebViewTask(topicId, task) }
            AndroidLog.d("HIDDEN_WEBVIEW", "Queued task topic=$topicId queueSize=${hiddenWebViewQueue.size}")
            return
        }
        hiddenWebViewBusy = true
        hiddenWebViewTaskId = topicId
        AndroidLog.d("HIDDEN_WEBVIEW", "Starting task topic=$topicId")
        hiddenWebViewTimeout?.let { mainHandler.removeCallbacks(it) }
        hiddenWebViewTimeout = Runnable {
            AndroidLog.w("HIDDEN_WEBVIEW", "Task timeout topic=$topicId")
            finishHiddenWebViewTask("timeout", topicId)
        }.also { mainHandler.postDelayed(it, 12000) }
        task()
    }

    private fun finishHiddenWebViewTask(reason: String, topicHint: String? = null) {
        val topic = topicHint ?: hiddenWebViewTaskId.orEmpty()
        if (hiddenWebViewBusy) {
            AndroidLog.d("HIDDEN_WEBVIEW", "Finish task topic=$topic reason=$reason")
        }
        hiddenWebViewTimeout?.let { mainHandler.removeCallbacks(it) }
        hiddenWebViewTimeout = null
        hiddenWebViewBusy = false
        hiddenWebViewTaskId = null
        hiddenWebViewQueue.pollFirst()?.let { it() }
    }
    
    // 토큰 확인 및 로그인 상태 체크
    private fun checkLoginStatus(): Boolean {
        val isLoggedIn = preferenceManager.isLoggedIn
        val xToken = preferenceManager.xToken
        val csrfToken = preferenceManager.csrfToken
        
        AndroidLog.d("LOGIN_CHECK", "🔍 로그인 상태 확인:")
        AndroidLog.d("LOGIN_CHECK", "  - isLoggedIn: $isLoggedIn")
        AndroidLog.d("LOGIN_CHECK", "  - session tokens present: ${!xToken.isNullOrBlank() && !csrfToken.isNullOrBlank()}")
        
        val result = isLoggedIn && !xToken.isNullOrBlank() && !csrfToken.isNullOrBlank()
        AndroidLog.d("LOGIN_CHECK", "  - 결과: $result")
        
        return result
    }

    private fun redirectToLoginIfNeeded(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLoginRedirectAt < 5000) return
        lastLoginRedirectAt = now
        AndroidLog.w("LOGIN_REDIRECT", "Redirecting to login (reason=$reason)")
        runOnUiThread {
            try {
                Toast.makeText(this, "로그인이 필요합니다. 로그인 페이지로 이동합니다.", Toast.LENGTH_SHORT).show()
                val currentUrl = mainWebView?.url.orEmpty()
                if (!currentUrl.contains("/signin")) {
                    mainWebView?.loadUrl("https://playentry.org/signin")
                }
            } catch (_: Exception) {}
        }
    }

    private fun showTriggerToast(message: String) {
        AndroidLog.d("TRIGGER_TOAST", message)
    }
    
    // 토큰 확인 후 알림 로직 시작
    private fun startNotificationLogicIfLoggedIn(force: Boolean = false) {
        AndroidLog.d("NOTIFICATION_LOGIC", "🔔 알림 로직 시작 시도... (force=$force)")

        if (!force && notificationLogicStarted) {
            AndroidLog.d("NOTIFICATION_LOGIC", "이미 실행 중 - 건너뜀")
            return
        }
        
        if (checkLoginStatus()) {
            AndroidLog.d("NOTIFICATION_LOGIC", "✅ 로그인 확인됨! 알림 로직 시작")
            notificationLogicStarted = true
            showTriggerToast("알림 트리거 시작됨")

            val startupHexId = preferenceManager.commentUserId
            if (!startupHexId.isNullOrBlank() && (preferenceManager.username.isNullOrBlank() || preferenceManager.profileImageUrl.isNullOrBlank())) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val prof = UserProfileFetcher.fetchProfile(startupHexId, preferenceManager.cookies)
                    if (prof != null) {
                        prof.nickname?.let { if (preferenceManager.username.isNullOrBlank()) preferenceManager.username = it }
                        prof.avatarUrl?.let { if (preferenceManager.profileImageUrl.isNullOrBlank()) preferenceManager.profileImageUrl = it }
                        AndroidLog.d("STARTUP_PROFILE", "Fetched profile on startup: nickname=${prof.nickname}, avatar=${prof.avatarUrl}")
                    }
                }
            }

            startAlarmBackgroundPoller()
            
            // WebView에서 토큰 확인 및 폴링 시작
            AndroidLog.d("NOTIFICATION_LOGIC", "🌐 WebView에서 토큰 확인 및 폴링 시작...")
            mainWebView?.post {
                val currentUrl = mainWebView?.url.orEmpty()
                if (isMainContentUrl(currentUrl)) {
                    mainWebView?.evaluateJavascript("""
                    (() => {
                        const xtoken = sessionStorage.getItem('entry_xToken');
                        const csrf = sessionStorage.getItem('entry_csrfToken');
                        console.log('[NOTIFICATION_LOGIC] WebView 토큰 확인:', {
                            xToken: xtoken ? xtoken.substring(0, 10) + '...' : 'null',
                            csrfToken: csrf ? csrf.substring(0, 10) + '...' : 'null'
                        });
                        if (xtoken && csrf) {
                            console.log('[NOTIFICATION_LOGIC] Token found, starting notification polling');
                            AndroidApp.postMessage('TOKEN_VERIFIED');
                            return true;
                        } else {
                            console.log('[NOTIFICATION_LOGIC] No token found, skipping polling');
                            return false;
                        }
                    })();
                """.trimIndent(), null)
                } else {
                    AndroidLog.d("NOTIFICATION_LOGIC", "메인 페이지가 아니라 토큰 확인 JS 실행 생략: $currentUrl")
                }

                if (isMainContentUrl(currentUrl)) {
                    AndroidLog.d("NOTIFICATION_LOGIC", "📡 메인 페이지 감지, 폴러 강제 재주입 시도: $currentUrl")
                    mainWebView?.postDelayed({
                        try {
                            mainWebView?.evaluateJavascript("if (window.__ENTRY_POLLER__){ __ENTRY_POLLER__.stop(); delete window.__ENTRY_POLLER__; }", null)
                            if (shouldUseMainNotifPoller()) {
                                mainWebView?.evaluateJavascript(jsNotifPoller, null)
                            } else {
                                AndroidLog.d("NOTIFICATION_LOGIC", "Alarm WebView poller active, skip main reinject")
                            }
                        } catch (e: Exception) {
                            AndroidLog.e("NOTIFICATION_LOGIC", "폴러 재주입 실패: ${e.message}")
                        }
                    }, 200)
                } else {
                    AndroidLog.d("NOTIFICATION_LOGIC", "메인 페이지가 아니라 폴러 재주입 생략: $currentUrl")
                }
            }
        } else {
            AndroidLog.d("NOTIFICATION_LOGIC", "❌ 로그인되지 않음. 알림 로직 건너뜀")
        }
    }

    // 저장된 토큰/쿠키를 WebView 쿠키 스토어에 주입하여 자동 로그인 유지
    private fun restoreCookiesIntoWebView(
        targetWebView: WebView? = mainWebView,
        force: Boolean = false,
        reason: String = "manual"
    ) {
        synchronized(cookieRestoreLock) {
            val cm = CookieManager.getInstance()
            val baseUrl = "https://playentry.org"
            val shouldSkip = !force && targetWebView != null && cookieGeneration != 0 &&
                webViewCookieGeneration[targetWebView] == cookieGeneration
            val skipGlobal = !force && targetWebView == null && cookieGeneration != 0 && cookiesPrimedGlobally
            if (shouldSkip || skipGlobal) {
                AndroidLog.d("COOKIE_RESTORE", "Skip restore for reason=$reason (generation=$cookieGeneration)")
                return
            }
            try {
                cm.setAcceptCookie(true)
                targetWebView?.let { runCatching { cm.setAcceptThirdPartyCookies(it, false) } }

                val savedCookiesRaw = preferenceManager.cookies
                val savedCookies = sanitizeCookieHeader(savedCookiesRaw) ?: savedCookiesRaw
                if (!savedCookies.isNullOrBlank()) {
                    if (savedCookies != savedCookiesRaw) {
                        preferenceManager.cookies = savedCookies
                    }
                    savedCookies.split(";")
                        .map { it.trim() }
                        .filter { it.contains("=") }
                        .forEach { raw ->
                            val key = raw.substringBefore("=").trim()
                            val value = raw.substringAfter("=").trim()
                            if (key.isNotBlank()) {
                                cm.setCookie(baseUrl, "$key=$value; path=/; domain=.playentry.org")
                            }
                        }
                }
                cm.flush()
                val targetLabel = when {
                    targetWebView === followGraphqlWebView -> "follow"
                    targetWebView === alarmWebView -> "alarm"
                    targetWebView === hiddenWebView -> "hidden"
                    targetWebView === mainWebView -> "main"
                    targetWebView == null -> "global"
                    else -> "custom"
                }
                AndroidLog.d(
                    "COOKIE_RESTORE",
                    "Cookies restored (len=${savedCookies?.length ?: 0}, reason=$reason, target=$targetLabel)"
                )
                if (targetWebView != null) {
                    webViewCookieGeneration[targetWebView] = cookieGeneration
                } else {
                    cookiesPrimedGlobally = true
                }
                lastCookieRestoreAt = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                AndroidLog.e("COOKIE_RESTORE", "쿠키 복원 실패: ${e.message}")
            }
        }
    }

    private fun markWebViewCookiesPrimed(vararg targets: WebView?) {
        synchronized(cookieRestoreLock) {
            targets.filterNotNull().forEach { webViewCookieGeneration[it] = cookieGeneration }
        }
    }

    private fun WebView.applyEmulatorLayerWorkaround() {
        try {
            val isEmulator = Build.FINGERPRINT.contains("generic", true) ||
                    Build.HARDWARE.contains("goldfish", true) ||
                    Build.HARDWARE.contains("ranchu", true) ||
                    Build.MODEL.contains("sdk_gphone", true)
            if (isEmulator) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
        } catch (_: Exception) {}
    }

    private fun sanitizeCookieHeader(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val sanitized = raw.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                val eq = token.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = token.substring(0, eq).trim()
                if (key.equals("path", true) || key.equals("domain", true) || key.equals("expires", true)) {
                    return@mapNotNull null
                }
                val value = token.substring(eq + 1).trim()
                "$key=$value"
            }
            .distinct()
        return sanitized.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun bumpCookieGenerationLocked(): Int {
        synchronized(cookieRestoreLock) {
            cookieGeneration = if (cookieGeneration == Int.MAX_VALUE) 1 else cookieGeneration + 1
            return cookieGeneration
        }
    }

    private suspend fun captureReadyState(webView: WebView?): String =
        suspendCancellableCoroutine { cont ->
            val target = webView ?: run {
                cont.resume("no_webview")
                return@suspendCancellableCoroutine
            }
            try {
                target.evaluateJavascript("document.readyState") { value ->
                    val normalized = value?.replace("\"", "")?.ifBlank { "unknown" } ?: "unknown"
                    if (cont.isActive) cont.resume(normalized)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("error:${e.message}")
            }
        }

    private fun extractCookieValue(header: String?, key: String): String? {
        if (header.isNullOrBlank()) return null
        val target = header.split(";").map { it.trim() }.firstOrNull { it.startsWith("$key=") } ?: return null
        return target.substring(key.length + 1)
    }

    private suspend fun logGraphqlContext(tag: String, stage: String, webView: WebView?) {
        val cm = CookieManager.getInstance()
        val cookieHeader = runCatching { cm.getCookie("https://playentry.org") }.getOrNull()
        val cookieSnapshotLen = cookieHeader?.length ?: 0
        val cookieCsrf = extractCookieValue(cookieHeader, "_csrf")
        val cookieHasToken = cookieHeader?.contains("playentry_token=") == true
        val cookieHasCsrf = !cookieCsrf.isNullOrEmpty()
        val restoreAge = if (lastCookieRestoreAt == 0L) -1L else SystemClock.elapsedRealtime() - lastCookieRestoreAt
        val readyState = captureReadyState(webView)
        val url = webView?.url.orEmpty()
        val referer = webView?.originalUrl.orEmpty()
        val ua = webView?.settings?.userAgentString.orEmpty()
        val thirdParty = runCatching { CookieManager.getInstance().acceptThirdPartyCookies(webView) }
            .getOrDefault(false)
        AndroidLog.d(
            tag,
            "[$stage] url=$url referer=$referer cookieManagerSnapshotLen=$cookieSnapshotLen cookieHasCsrf=$cookieHasCsrf cookieHasPlayentryToken=$cookieHasToken restoreAgeMs=$restoreAge readyState=$readyState thirdParty=$thirdParty"
        )
    }

    private fun logGraphqlIntercept(source: String, request: WebResourceRequest?) {
        if (request == null) return
        val url = request.url?.toString() ?: return
        if (!EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/graphql")) return
        val headers = request.requestHeaders ?: emptyMap()
        val interestingKeys = listOf(
            "Referer",
            "Origin",
            "sec-fetch-site",
            "sec-fetch-mode",
            "sec-fetch-dest",
            "sec-fetch-user",
            "accept",
            "accept-language",
            "user-agent",
            "content-type"
        )
        val highlight = interestingKeys.joinToString(", ") { key ->
            val match = headers.entries.firstOrNull { it.key.equals(key, true) }?.value
            "$key=${match ?: ""}"
        }
        AndroidLog.d(
            "GRAPHQL_INTERCEPT",
            "[$source] method=${request.method} url=$url highlight={$highlight}"
        )
    }

    private fun getStoredCookieHeader(): String {
        val stored = preferenceManager.cookies
        if (!stored.isNullOrBlank()) return stored

        var result = ""
        if (Looper.myLooper() == Looper.getMainLooper()) {
            result = try {
                CookieManager.getInstance().getCookie("https://playentry.org") ?: ""
            } catch (e: Exception) {
                AndroidLog.e("COOKIE_FETCH", "Failed to get cookies: ${e.message}")
                ""
            }
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    result = CookieManager.getInstance().getCookie("https://playentry.org") ?: ""
                } catch (e: Exception) {
                    AndroidLog.e("COOKIE_FETCH", "Failed to get cookies: ${e.message}")
                    result = ""
                } finally {
                    latch.countDown()
                }
            }
            latch.await(500, TimeUnit.MILLISECONDS)
        }
        return result
    }

    // ✅ 공통 알림 큐 처리 함수들
    private fun enqueueNotif(task: NotifTask) {
        notifQueue.add(task)
        if (!isProcessingNotif) processNextNotif()
    }
    
    private fun processNextNotif() {
        if (notifQueue.isEmpty()) {
            isProcessingNotif = false
            return
        }
        
        // DOM 감지 무효화 (이전에 예약된 detectDOM 전부 무효화)
        domDetectGen++
        mainWebView?.evaluateJavascript(
            "window.__ENTRY_BLOCK_DOM = 1; if (window.__ENTRY_POLLER__ && window.__ENTRY_POLLER__.stop) window.__ENTRY_POLLER__.stop();",
            null
        )

        isProcessingNotif = true
        val next = notifQueue.removeAt(0)  // ✅ API 24 호환: removeFirst() 대신 removeAt(0) 사용

        when (next) {
            is NotifTask.Comment -> {
                processComment(next.item) {
                    // 댓글 완료 시 다음으로
                    processNextNotif()
                }
            }
            is NotifTask.Follow -> {
                processFollow(next.user, next.time, next.deep, next.userIdHint) {
                    // 팔로우 완료 시 다음으로
                    processNextNotif()
                }
            }
        }
    }
    
    private fun processComment(item: CommentNotifItem, onDone: () -> Unit) {
        AndroidLog.d("QUEUE", "▶ COMMENT 처리 시작: ${item.actorName} (${item.topicId})")
        AndroidLog.d("COMMENT_DEBUG", "DeepLink: ${item.deepLink}")
        commentPhase = "COMMENT_START"
        commentPhaseTopic = item.topicId
        rememberCommentKind(item.topicId, if (item.template.equals("template_34", true)) "REPLY" else "COMMENT")

        if (item.template.equals("template_space_1", true)) {
            val who = item.actorName.ifBlank { "누군가" }
            val key = notifKey(
                "SPACE_COMMENT",
                who,
                "스페이스 댓글/답글",
                item.topicId.ifBlank { item.linkTarget },
                normalizeIsoTime(item.created)
            )
            val posted = rememberNotificationKey(key) && postSimpleNotification(
                key = key,
                title = item.titleHint?.takeIf { it.isNotBlank() } ?: "스페이스 알림",
                text = "${who}님이 스페이스에 댓글 또는 답글을 남겼어요",
                whenMillis = parseWhen(item.created),
                openUrl = item.deepLink.ifBlank { "https://space.playentry.org/" },
                topicId = item.topicId
            )
            if (!posted) {
                releaseRememberedNotificationKey(key)
                AndroidLog.w("COMMENT_NOTIF", "template_space_1 basic notify failed topic=${item.topicId}")
            } else {
                AndroidLog.d("COMMENT_NOTIF", "template_space_1 basic notify only topic=${item.topicId}")
            }
            onDone()
            return
        }

        
        // ⬇️ 추가: 폴백이 쓸 컨텍스트 저장
        lastDeepLink = item.deepLink
        rememberCommentLinkInfo(item)
        rememberContentTitle(item.topicId, item.titleHint)
        lastUserNickname = item.actorName  // ✅ 댓글 작성자(actor) 정보 사용
        lastTopicId = item.topicId
        lastCreated = normalizeIsoTime(item.created)
        expectedCommentUser = item.actorName // ✅ 댓글 작성자 닉네임 저장
        pendingAllowNotify = true
        notifiedCurrent = false
        hiddenLaunched = false  // ✅ 플래그 리셋

        // 1단계: 일단 내용과 몸통(기본 알림)을 즉시 띄운다 (사용자 의도: 기본 알림 먼저 표시 후 비동기 상세 업데이트)
        val who = item.actorName.ifBlank { expectedCommentUser ?: lastUserNickname ?: "새 댓글" }
        val isReply = item.template.equals("template_34", true)
        val basicKind = if (isReply) "REPLY" else "COMMENT"
        val basicTitle = if (isReply) "${who}님의 답글" else "${who}님의 댓글"
        val basicText = if (isReply) {
            "${who}님이 작품에 답글을 작성했어요"
        } else {
            val proj = item.titleHint?.takeIf { it.isNotBlank() && it != "알림" } ?: "작품"
            "${who}님이 '${proj}'에 댓글을 작성했어요"
        }
        val basicCreated = normalizeIsoTime(item.created)
        val basicKey = notifKey(
            basicKind,
            who,
            if (isReply) "답글을 남겼습니다" else "댓글을 남겼습니다",
            item.topicId,
            basicCreated
        )
        val basicNid = stableIdFrom(basicKey)
        rememberCommentTag(item.topicId, basicKind, basicKey)

        if (rememberNotificationKey(basicKey)) {
            postCommentNotification(
                title = basicTitle,
                text = basicText,
                openUrl = item.deepLink.ifBlank { "https://playentry.org/" },
                profileUrl = null,
                avatarName = who,
                tag = basicKey,
                nid = basicNid,
                whenMillis = parseWhen(item.created),
                commentPreview = null,
                topicIdHint = item.topicId
            )
            notifiedCurrent = true
        }

        if (item.template.equals("template_1", true)) {
            lifecycleScope.launch {
                val success = handleTemplate1Graphql(item)
                withContext(Dispatchers.Main) {
                    if (!success) {
                        AndroidLog.w("COMMENT_GRAPHQL", "template_1 GraphQL failed (topic=${item.topicId}) - fallback to DOM scan")
                        startDomCommentPipeline(item, onDone = {
                            if (!notifiedCurrent) {
                                AndroidLog.w("COMMENT_FALLBACK", "template_1 not found - posting basic notification")
                                AndroidLog.w("COMMENT_FLOW", "[4] basic notification fallback topic=${item.topicId}")
                                postBasicCommentFallback(item, reason = "template1_not_found")
                            }
                            onDone()
                        }, enableDomScan = false)  // ✅ GraphQL 전용
                    } else {
                        AndroidLog.d("COMMENT_GRAPHQL", "template_1 GraphQL succeeded (topic=${item.topicId})")
                        onDone()
                    }
                }
            }
        } else if (item.template.equals("template_space_1", true)) {
            lifecycleScope.launch {
                val success = handleTemplateSpaceNativeGraphql(item)
                withContext(Dispatchers.Main) {
                    if (!success) {
                        startDomCommentPipeline(item, onDone = {
                            if (!notifiedCurrent) {
                                AndroidLog.w("COMMENT_FALLBACK", "template_space_1 not found - posting basic notification")
                                postBasicCommentFallback(item, reason = "space_comment_not_found")
                            }
                            onDone()
                        }, enableDomScan = false)
                    } else {
                        onDone()
                    }
                }
            }
        } else {
            startDomCommentPipeline(item, onDone, enableDomScan = false)
        }
    }
    
    private fun processFollow(user: String, time: String, deep: String, userIdHint: String?, onDone: () -> Unit) {
        AndroidLog.d("QUEUE", "▶ FOLLOW 처리 시작: $user")
        handleFollowNotification(user, time, deep, userIdHint)
        lifecycleScope.launch {
            delay(3500)
            onDone()
        }
    }
    
    // ✅ assets에서 JS 파일 로드
    private fun loadAssetAsString(filename: String): String {
        return try {
            assets.open(filename).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AndroidLog.e("ASSET_LOAD", "Failed to load $filename: ${e.message}")
            ""
        }
    }
    
    // ✅ 숨김 WebView에서 댓글 찾기 (폴백용)
    private fun executeFindCommentInHiddenWebView(
        topicId: String,
        targetId: String,
        pageUrl: String,
        userHint: String,
        createdIso: String,
        createdDom: String,
        mode: String = "auto",
        enableDomScan: Boolean = false
    ) {
        enqueueHiddenWebViewTask(topicId) {
            executeFindCommentInHiddenWebViewInternal(
                topicId = topicId,
                targetId = targetId,
                pageUrl = pageUrl,
                userHint = userHint,
                createdIso = createdIso,
                createdDom = createdDom,
                mode = mode,
                enableDomScan = enableDomScan
            )
        }
    }

    private fun executeFindCommentInHiddenWebViewInternal(
        topicId: String,
        targetId: String,
        pageUrl: String,
        userHint: String,
        createdIso: String,
        createdDom: String,
        mode: String = "auto",
        enableDomScan: Boolean = false
    ) {
        if (enableDomScan) {
            AndroidLog.d("HIDDEN_WEBVIEW", "Starting comment search in hidden WebView for targetId=$targetId page=$pageUrl")
        } else {
            AndroidLog.d(
                "COMMENT_GRAPHQL",
                "Launching GraphQL WebView for targetId=$targetId page=$pageUrl user=$userHint created=$createdIso"
            )
        }
        
        ensureHiddenWebView(this)
        
        // 쿠키 동기화
        CookieManager.getInstance().getCookie("https://playentry.org")?.let {
            CookieManager.getInstance().setCookie("https://playentry.org", it)
            CookieManager.getInstance().flush()
        }
        
        // 숨김 WebView 설정
        hiddenWebView?.webViewClient = object : SecureEntryWebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                AndroidLog.e("HIDDEN_WEBVIEW", "Render process gone: didCrash=${renderProcessCrashState(detail)}")
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val isEntryPage = EntryOriginPolicy.isTrustedHost(url, "playentry.org") ||
                    EntryOriginPolicy.isTrustedHost(url, "space.playentry.org")
                val isSigninPage = EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/signin")
                if (isEntryPage && !isSigninPage) {
                    AndroidLog.d("HIDDEN_WEBVIEW", "Hidden page finished: $url")

                    // ✅ 토큰/쿠키 재주입
                    AndroidLog.d("HIDDEN_WEBVIEW", "[TOKEN] Injecting tokens into hidden WebView")
                    view?.evaluateJavascript(jsTokenInjector, null)
                    val xToken = preferenceManager.xToken
                    val csrfToken = preferenceManager.csrfToken
                    if (!xToken.isNullOrBlank() && !csrfToken.isNullOrBlank()) {
                        val bootstrap = buildAlarmTokenBootstrapJs(xToken, csrfToken)
                        view?.evaluateJavascript(bootstrap, null)
                    }

                    // ✅ 토큰 기다렸다가 → findComment() 실행
                    val xTokenPref = preferenceManager.xToken.orEmpty()
                    val csrfTokenPref = preferenceManager.csrfToken.orEmpty()
                    val storedUid = preferenceManager.commentUserId?.trim().orEmpty()
                    val runFindAfterToken = """
                        (function waitToken(){
                            try{
                                const optToken = ${JSONObject.quote(xTokenPref)};
                                const optCsrf = ${JSONObject.quote(csrfTokenPref)};
                                const has = !!(
                                  optToken ||
                                  localStorage.getItem('playentry_token') ||
                                  sessionStorage.getItem('entry_xToken') ||
                                  localStorage.getItem('entry_xToken')
                                );
                                const csrf = optCsrf ||
                                  (document.querySelector('meta[name="csrf-token"]')||{}).content ||
                                  sessionStorage.getItem('entry_csrfToken') ||
                                  localStorage.getItem('entry_csrfToken') ||
                                  '';
                                if(!has){ return setTimeout(waitToken, 150); }
                                try{ window.__ENTRY_TOKEN_READY__ = true; }catch(e){}

                                // 모듈 보장
                                ${loadAssetAsString("findComment.js")}

                                // 이제 실행! (닉네임/시각만 넘김)
                                const mode = ${JSONObject.quote(mode)};
                                const isReply = mode === "reply";
                                const isSpaceBoth = mode === "space_both";
                                window.__ENTRY_SUPPRESS_POST__ = true;
                                const fn = isSpaceBoth
                                  ? (window.runEntrySpaceCommentSearch || window.runEntryReplySearch || window.runEntryTimeMatch || window.findComment)
                                  : (isReply ? (window.runEntryReplySearch || window.findComment) : (window.runEntryTimeMatch || window.findComment));
                                if (!fn) throw new Error("no_function");
                                const args = isSpaceBoth ? {
                                  parentUserId: ${JSONObject.quote(storedUid)},
                                  isoTime: ${JSONObject.quote(createdIso)},
                                  toleranceSec: 1.0,
                                  token: optToken,
                                  csrf: csrf,
                                  targetId: ${JSONObject.quote(targetId)}
                                } : (isReply ? {
                                  parentUserId: ${JSONObject.quote(storedUid)},
                                  replyIsoTime: ${JSONObject.quote(createdIso)},
                                  toleranceSec: 1.0,
                                  token: optToken,
                                  csrf: csrf,
                                  targetId: ${JSONObject.quote(targetId)}
                                } : {
                                  isoTime: ${JSONObject.quote(createdIso)},
                                  toleranceSec: 0.1,
                                  token: optToken,
                                  csrf: csrf,
                                  targetId: ${JSONObject.quote(targetId)}
                                });
                                fn(args)
                                    .then(r => {
                                      if (r && typeof r === 'object') r.topicId = ${JSONObject.quote(topicId)};
                                      AndroidApp.postMessage("COMMENT_RESULT:"+JSON.stringify(r||{error:"not_found", topicId:${JSONObject.quote(topicId)}}));
                                    })
                                    .catch(e => AndroidApp.postMessage("COMMENT_RESULT:error_"+(e&&e.message||e)));
                            }catch(e){
                                if (String(e).includes('localStorage')) {
                                    setTimeout(waitToken, 200);
                                } else {
                                    AndroidApp.postMessage("COMMENT_RESULT:error_"+(e && e.message ? e.message : e));
                                }
                            }
                        })();
                    """.trimIndent()
                    view?.postDelayed({ try { view.evaluateJavascript(runFindAfterToken, null) } catch (_: Exception) {} }, 200)
                    
                    // ✅ 3) (선택) DOM 스캔은 보조로만 — r가 안 오면 그때 돌려
                    if (enableDomScan) {
                        val domScanFunction = """
                        window.startDOMScan = (function(){
                            try{ window.__ENTRY_SUPPRESS_POST__ = true; }catch(e){}
                            const targetUser = ${JSONObject.quote(userHint)};
                            const targetTime = ${JSONObject.quote(createdDom)};
                            const topicId = ${JSONObject.quote(topicId)};
                            let tries = 0;

                            function normalizeYYMMDD(s){
                                const m = String(s).match(/(\d{1,2})\/(\d{1,2})\/(\d{4})/);
                                if (m){ const mm=m[1].padStart(2,'0'), dd=m[2].padStart(2,'0'), yy=m[3].slice(-2); return `${"$"}{yy}.${"$"}{mm}.${"$"}{dd}`; }
                                return s;
                            }
                            const timeHint = normalizeYYMMDD(targetTime);

                            function wakeComments(){
                                try{
                                    window.scrollTo(0, document.body.scrollHeight);
                                    document.dispatchEvent(new Event('scroll'));
                                    document.querySelector('a[href="#comment"]')?.click();
                                    document.querySelector('.css-2hcz3y.erhmwsd0')?.click(); // 최신순
                                }catch(e){}
                            }

                            function scan(){
                                wakeComments();
                                const list = document.querySelector('ul.css-1m3ba66.e1fqckzt0');
                                if (!list){
                                    if (++tries <= 10) return setTimeout(scan, 200); // 최대 2초
                                    AndroidApp.postMessage("COMMENT_RESULT:"+JSON.stringify({error:"no_list"})); return;
                                }
                                
                                // 디버깅: DOM 스캔 시작
                                if (tries === 0) AndroidApp.postMessage("COMMENT_RESULT:__DOM_SCAN_START__");

                                const items = list.querySelectorAll('li.css-hjaihh.e19b9x4q0');
                                // ① 날짜 포함 매칭
                                for (const li of items){
                                    const isOwner  = !!li.querySelector('.ee2n3ac1')?.textContent?.includes('작성자');
                                    const isPinned = !!li.querySelector('.ee2n3ac0')?.textContent?.includes('고정');
                                    if (isOwner || isPinned) continue;

                                    const nick = li.querySelector('.ee2n3ac5 a[href^="/profile/"]')?.textContent?.trim()||"";
                                    const time = li.querySelector('.ee2n3ac5 em')?.textContent?.trim()||"";
                                    if (!nick || nick !== targetUser) continue;
                                    if (timeHint && !String(time).includes(timeHint)) continue;

                                    return found(li, time);
                                }

                                // ② 닉네임만으로 느슨 매칭
                                for (const li of items){
                                    const isOwner  = !!li.querySelector('.ee2n3ac1')?.textContent?.includes('작성자');
                                    const isPinned = !!li.querySelector('.ee2n3ac0')?.textContent?.includes('고정');
                                    if (isOwner || isPinned) continue;

                                    const nick = li.querySelector('.ee2n3ac5 a[href^="/profile/"]')?.textContent?.trim()||"";
                                    if (!nick || nick !== targetUser) continue;

                                    return found(li, "");
                                }

                                // ❗여기서 바로 not_found 보내지 말고, 다시 폴링 (더 짧게)
                                if (++tries <= 10) return setTimeout(scan, 200);
                                AndroidApp.postMessage("COMMENT_RESULT:"+JSON.stringify({error:"not_found"}));
                            }

                            function found(li, time){
                                // 디버깅: DOM 매칭 성공
                                AndroidApp.postMessage("COMMENT_RESULT:__DOM_MATCH__");
                                
                                const body = li.querySelector('.e1i41bku1')?.textContent?.trim()||"";
                                
                                // 닉네임/시간
                                const nick = li.querySelector('.ee2n3ac5 a[href^="/profile/"]')?.textContent?.trim()||"";
                                
                                // ✅ 프로필 이미지 DOM 추출
                                let domProfile = "";
                                
                                // 1) background-image가 있는 요소 찾기 (a, div, span 등)
                                let avatar = li.querySelector('a[style*="background-image"]') || 
                                             li.querySelector('div[style*="background-image"]') || 
                                             li.querySelector('span[style*="background-image"]') ||
                                             li.querySelector('[style*="background-image"]');
                                
                                if (avatar) {
                                    const style = avatar.getAttribute('style') || getComputedStyle(avatar).backgroundImage || '';
                                    // &quot; 처리 개선: HTML 엔티티를 먼저 디코딩
                                    const cleanStyle = style.replace(/&quot;/g, '"');
                                    const m = cleanStyle.match(/url\(["']?([^)"']+)["']?\)/);
                                    if (m && m[1]) {
                                        // /uploads/로 시작하는 경로만 추출 (EmptyImage.svg 제외)
                                        const path = m[1].trim();
                                        if (path.includes('/uploads/') && !path.includes('EmptyImage')) {
                                            domProfile = path.startsWith('http') ? path : ('https://playentry.org' + (path.startsWith('/') ? path : '/' + path));
                                        }
                                    }
                                }
                                
                                // 2) img 태그로도 시도
                                if (!domProfile) {
                                    const img = li.querySelector('img[src*="/uploads/"]') || li.querySelector('img[alt="profile"]');
                                    if (img) domProfile = img.src;
                                }
                                
                                const stickerDom = li.querySelector('em.e1877mpo0 img')?.src || "";
                                
                                // ✅ GraphQL 보강: findComment를 사용해 프로필/스티커/첨부 URL 복원
                                try {
                                    if (typeof findComment === 'function') {
                                        findComment(nick, time || "").then(function(r){
                                            const profile = (r && r.profile) ? r.profile : (domProfile || "");
                                            const sticker = (r && r.sticker) ? r.sticker : (stickerDom || "");
                                            const image   = (r && r.image)   ? r.image   : "";
                                            AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({
                                                topicId: topicId,
                                                nickname: nick,
                                                content:  body,
                                                profile:  profile,
                                                sticker:  sticker,
                                                image:    image,
                                                link:     location.href,
                                                time
                                            }));
                                        }).catch(function(){
                                            AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({
                                                topicId: topicId,
                                                nickname: nick,
                                                content:  body,
                                                profile:  domProfile,
                                                sticker:  stickerDom,
                                                image:    "",
                                                link:     location.href,
                                                time
                                            }));
                                        });
                                        return;
                                    }
                                } catch (e) { /* fallback below */ }
                                
                                // ✅ 폴백: DOM에서 수집한 값만으로 전송 (profilePath 제거)
                                AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({
                                    topicId: topicId,
                                    nickname: nick,
                                    content:  body,
                                    profile:  domProfile,
                                    sticker:  stickerDom,
                                    image:    "",
                                    link:     location.href,
                                    time
                                }));
                            }

                            return function(){ tries = 0; scan(); };
                        })();
                    """.trimIndent()
                        view?.evaluateJavascript(domScanFunction, null)
                        
                        // ✅ 바로 DOM 스캔 시작 (토큰 대기 X)
                        view?.evaluateJavascript("try{ if(window.startDOMScan){ window.startDOMScan(); } }catch(e){}", null)
                        
                        // ✅ (2) 토큰 대기 → 안전함수 로드만 담당

                        // ✅ 브릿지 핑 테스트
                        view?.evaluateJavascript("try{AndroidApp.postMessage('COMMENT_RESULT:__PING__')}catch(e){}", null)
                        
                        // ✅ 세이프티 콜: DOM 스캔이 3초 안에 못 찾으면 안전함수로 강제 보강
                        val execSafeOnly = """
                        (function(){
                            setTimeout(function(){
                                if (!window.__ENTRY_FOUND__) {
                                    const mode = ${JSONObject.quote(mode)};
                                    const isReply = mode === "reply";
                                    const isSpaceBoth = mode === "space_both";
                                    window.__ENTRY_SUPPRESS_POST__ = true;
                                    const fn = isSpaceBoth
                                        ? (window.runEntrySpaceCommentSearch || window.runEntryReplySearch || window.runEntryTimeMatch || window.findComment)
                                        : (isReply ? (window.runEntryReplySearch || window.findComment) : (window.runEntryTimeMatch || window.findComment));
                                    if (typeof fn === 'function') {
                                        const args = isSpaceBoth ? {
                                            parentUserId: ${JSONObject.quote(storedUid)},
                                            isoTime: ${JSONObject.quote(createdIso)},
                                            toleranceSec: 1.0,
                                            token: ${JSONObject.quote(xTokenPref)},
                                            csrf: ${JSONObject.quote(csrfTokenPref)},
                                            targetId: ${JSONObject.quote(targetId)}
                                        } : (isReply ? {
                                            parentUserId: ${JSONObject.quote(storedUid)},
                                            replyIsoTime: ${JSONObject.quote(createdIso)},
                                            toleranceSec: 1.0,
                                            token: ${JSONObject.quote(xTokenPref)},
                                            csrf: ${JSONObject.quote(csrfTokenPref)},
                                            targetId: ${JSONObject.quote(targetId)}
                                        } : {
                                            isoTime: ${JSONObject.quote(createdIso)},
                                            toleranceSec: 0.1,
                                            token: ${JSONObject.quote(xTokenPref)},
                                            csrf: ${JSONObject.quote(csrfTokenPref)},
                                            targetId: ${JSONObject.quote(targetId)}
                                        });
                                        fn(args)
                                            .then(function(r){
                                                if (r && typeof r === 'object') r.topicId = ${JSONObject.quote(topicId)};
                                                AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify(r||{error:"not_found", topicId:${JSONObject.quote(topicId)}}));
                                            })
                                            .catch(function(e){ AndroidApp.postMessage("COMMENT_RESULT:error_"+(e&&e.message||e)); });
                                    } else {
                                        AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({
                                            nickname: ${JSONObject.quote(userHint)},
                                            content:  "",
                                            profile:  "",
                                            sticker:  "",
                                            link:     location.href,
                                            time:     ""
                                        }));
                                    }
                                }
                            }, 1000);
                        })();
                    """.trimIndent()
                        view?.evaluateJavascript(execSafeOnly, null)
                    }
                }
            }
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && errorResponse?.statusCode == 403) {
                    AndroidLog.w("HIDDEN_WEBVIEW", "HTTP 403 received on $pageUrl - reloading without cookie reset")
                    view?.evaluateJavascript(jsTokenInjector, null)
                    view?.postDelayed({ try { view.reload() } catch (_: Exception) {} }, 250)
                }
            }
        }
        
        val resolvedUrl = normalizeEntryUrl(pageUrl)
            ?: if (targetId.isNotBlank()) "https://playentry.org/project/$targetId" else "https://playentry.org/"
        hiddenWebView?.post {
            hiddenWebView?.loadUrl(resolvedUrl)
        }
    }
    
    // ✅ 댓글 스캔 JS (안전한 문자열 escape 적용)
    private fun buildCommentScanJS(userHint: String, created: String) = """
        (function(){
            try {
                const targetUser = ${JSONObject.quote(userHint)};
                const targetTime = ${JSONObject.quote(created)};
                console.log('[COMMENT_SCAN] Start scan for', targetUser, targetTime);
                
                const commentList = document.querySelector('ul.css-1m3ba66.e1fqckzt0');
                if (!commentList) {
                    console.log('[COMMENT_SCAN] No comment list found');
                    AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({error:'no_list'}));
                    return;
                }
                
                const comments = commentList.querySelectorAll('li.css-hjaihh.e19b9x4q0');
                console.log('[COMMENT_SCAN] Found comments:', comments.length);
                
                for (const li of comments) {
                    const nick = li.querySelector('.ee2n3ac5 a[href^="/profile/"]')?.textContent?.trim() || '';
                    const body = li.querySelector('.e1i41bku1')?.textContent?.trim() || '';
                    const time = li.querySelector('.ee2n3ac5 em')?.textContent?.trim() || '';
                    
                    console.log('[COMMENT_SCAN] Comment:', {nick, body: body.substring(0, 30), time});
                    
                    if (!nick || nick !== targetUser) continue;
                    if (targetTime && !time.includes(targetTime.split(' ')[0])) continue;
                    
                    const style = li.querySelector('a.css-44n1to.enx4swp0')?.getAttribute('style') || '';
                    const profile = (style.match(/url\\(["']?([^"')]+)["']?\\)/)||[])[1] || '';
                    const sticker = li.querySelector('em.e1877mpo0 img')?.src || '';
                    
                    console.log('[COMMENT_SCAN] MATCH FOUND:', {nick, body, profile});
                    
                    AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({
                        nickname: nick,
                        content: body,
                        profile: profile,
                        sticker: sticker,
                        link: location.href,
                        time: time
                    }));
                    return;
                }
                
                console.log('[COMMENT_SCAN] No matching comment found');
                AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({error:'not_found'}));
                
            } catch(e) {
                console.error('[COMMENT_SCAN] Error:', e);
                AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({error:e.message}));
            }
        })();
    """.trimIndent()

    // 🔒 페이지 이동 없이, 현재 WebView 위에서 JS만 실행
    private fun executeFindCommentJS(item: CommentNotifItem) {
        AndroidLog.d("COMMENT_DEBUG", "NO-NAV 모드로 댓글 찾기 실행 (deepLink=${item.deepLink})")

        val targetId = normalizeJsonToken(resolveCommentTargetId(item))
        val targetPageUrl = resolveCommentPageUrl(item)
        val mode = resolveCommentSearchMode(item.template)
        val storedUid = preferenceManager.commentUserId?.trim().orEmpty()
        val xTokenPref = preferenceManager.xToken.orEmpty()
        val csrfTokenPref = preferenceManager.csrfToken.orEmpty()

        // 2) 현재 페이지가 target 페이지인지 확인 (루트/알람 페이지에서는 NO-NAV 금지)
        val currentUrl = mainWebView?.url.orEmpty()
        val normalizedCurrent = normalizeEntryUrl(currentUrl).orEmpty()
        val normalizedTarget = normalizeEntryUrl(targetPageUrl).orEmpty()
        val currentBase = normalizedCurrent.substringBefore('?').substringBefore('#')
        val targetBase = normalizedTarget.substringBefore('?').substringBefore('#')
        val isEntryContentPage = (
            EntryOriginPolicy.isTrustedHost(normalizedCurrent, "playentry.org") ||
                EntryOriginPolicy.isTrustedHost(normalizedCurrent, "space.playentry.org")
            ) && !EntryOriginPolicy.isTrustedPath(normalizedCurrent, "playentry.org", "/signin")
        val targetIdValid = HEX_OBJECT_ID.matches(targetId)
        val sameTargetPage = currentBase.isNotBlank() && targetBase.isNotBlank() &&
            currentBase.equals(targetBase, ignoreCase = true)
        val sameTargetIdInUrl = targetIdValid && currentBase.contains(targetId, ignoreCase = true)
        val canRunNoNav = isEntryContentPage && (sameTargetPage || sameTargetIdInUrl)

        if (!canRunNoNav) {
            AndroidLog.d(
                "COMMENT_DEBUG",
                "NO-NAV 조건 불충족(current=$normalizedCurrent target=$targetBase targetId=$targetId) → 숨김 WebView 폴백"
            )
            val createdIso = normalizeIsoTime(item.created)
            hiddenLaunched = true  // 중복 실행 방지
            executeFindCommentInHiddenWebView(
                topicId = item.topicId,
                targetId = targetId,
                pageUrl = targetPageUrl,
                userHint = item.actorName,
                createdIso = createdIso,
                createdDom = createdIso,
                mode = mode,
                enableDomScan = false
            )
            return
        }

        // 3) findComment.js 모듈을 에셋에서 읽어서 주입 (실패하면 즉시 폴백)
        val jsModule = loadAssetAsString("findComment.js")
        if (jsModule.isNotEmpty()) {
            mainWebView?.evaluateJavascript(jsModule, null)
            AndroidLog.d("COMMENT_DEBUG", "댓글 찾기 JS 모듈 주입 완료")

            // 4) 현재 엔트리 본문 페이지에서 findComment 실행
            val exec = """
                (function(){
                  try{
                    if (typeof findComment !== 'function') {
                      AndroidApp.postMessage("COMMENT_RESULT: error_no_function");
                      return;
                    }
                    window.__ENTRY_SUPPRESS_POST__ = true;
                    const mode = ${JSONObject.quote(mode)};
                    const isReply = mode === "reply";
                    const isSpaceBoth = mode === "space_both";
                    const fn = isSpaceBoth
                      ? (window.runEntrySpaceCommentSearch || window.runEntryReplySearch || window.runEntryTimeMatch || window.findComment)
                      : (isReply ? (window.runEntryReplySearch || window.findComment) : (window.runEntryTimeMatch || window.findComment));
                    if (typeof fn !== 'function') {
                      AndroidApp.postMessage("COMMENT_RESULT: error_no_function");
                      return;
                    }
                    const args = isSpaceBoth ? {
                      parentUserId: ${JSONObject.quote(storedUid)},
                      isoTime: ${JSONObject.quote(normalizeIsoTime(item.created))},
                      toleranceSec: 1.0,
                      token: ${JSONObject.quote(xTokenPref)},
                      csrf: ${JSONObject.quote(csrfTokenPref)},
                      targetId: ${JSONObject.quote(targetId)}
                    } : (isReply ? {
                      parentUserId: ${JSONObject.quote(storedUid)},
                      replyIsoTime: ${JSONObject.quote(normalizeIsoTime(item.created))},
                      toleranceSec: 1.0,
                      token: ${JSONObject.quote(xTokenPref)},
                      csrf: ${JSONObject.quote(csrfTokenPref)},
                      targetId: ${JSONObject.quote(targetId)}
                    } : {
                      isoTime: ${JSONObject.quote(normalizeIsoTime(item.created))},
                      toleranceSec: 0.1,
                      token: ${JSONObject.quote(xTokenPref)},
                      csrf: ${JSONObject.quote(csrfTokenPref)},
                      targetId: ${JSONObject.quote(targetId)}
                    });
                    fn(args)
                      .then(function(r){
                        if (!r) {
                          AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify({error:"not_found", topicId:${JSONObject.quote(item.topicId)}}));
                          return;
                        }
                        if (typeof r === 'object') r.topicId = ${JSONObject.quote(item.topicId)};
                        AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify(r));
                      })
                      .catch(function(e){
                        AndroidApp.postMessage("COMMENT_RESULT:error_" + (e && e.message ? e.message : e));
                      });
                  }catch(e){
                    AndroidApp.postMessage("COMMENT_RESULT:error_" + e.message);
                  }
                })();
            """.trimIndent()
            mainWebView?.evaluateJavascript(exec, null)
        } else {
            AndroidLog.e("COMMENT_DEBUG", "findComment.js 로드 실패 → 숨김 WebView 폴백으로 진행")
            val createdIso = normalizeIsoTime(item.created)
            hiddenLaunched = true  // 중복 실행 방지
            executeFindCommentInHiddenWebView(
                topicId = item.topicId,
                targetId = targetId,
                pageUrl = targetPageUrl,
                userHint = item.actorName,
                createdIso = createdIso,
                createdDom = createdIso,
                mode = mode,
                enableDomScan = false
            )
        }
    }
    
    // ✅ 폴백 DOM 스캐너 (딱 맞는 셀렉터/매칭 로직)
    private fun buildCommentFetchFallbackJS(item: CommentNotifItem, projectId: String): String {
        val user = item.actorName
        val created = item.created
        val deep = item.deepLink

        return """
          (function(){
            try{
              var nickname = "${user}";      // 코틀린에서 치환
              var timeStr  = "${created}";       // "25.10.12 ・ 16:27" 같은 형식
              var deepLink = "${deep}";

              function abs(u){ if(!u) return ""; if(/^https?:\/\//i.test(u)) return u; return (u[0]==='/'? location.origin+u : location.origin+'/'+u); }
              function matchTime(t){ 
                if(!timeStr) return true; 
                var hhmmNotif = (timeStr.match(/\\d{2}:\\d{2}/)||[])[0]||"";
                var hhmmDom   = (String(t).match(/\\d{2}:\\d{2}/)||[])[0]||"";
                var ymdNotif  = (timeStr.match(/\\d{2}\\.\\d{2}\\.\\d{2}/)||[])[0]||"";
                var ymdDom    = (String(t).match(/\\d{2}\\.\\d{2}\\.\\d{2}/)||[])[0]||"";
                // HH:MM 또는 YY.MM.DD 중 하나만 맞아도 OK (느슨 매칭)
                return (hhmmNotif && hhmmDom && hhmmDom.indexOf(hhmmNotif)>=0) || (ymdNotif && ymdDom && ymdDom.indexOf(ymdNotif)>=0) || (!hhmmNotif && !ymdNotif);
              }

              var items = document.querySelectorAll('ul.css-1m3ba66.e1fqckzt0 > li.css-hjaihh.e19b9x4q0');
              if(!items.length){
                AndroidApp.postMessage("COMMENT_RESULT: not_found");
                return;
              }

              for (var i=0;i<items.length;i++){
                var li = items[i];

                var nick = li.querySelector('.ee2n3ac5 a[href^="/profile/"]');
                var nickTxt = nick && nick.textContent ? nick.textContent.trim() : '';
                if (!nickTxt) continue;
                if (nickname && nickTxt.indexOf(nickname) < 0) continue;

                var timeEl = li.querySelector('.ee2n3ac5 em');
                var timeTxt = timeEl && timeEl.textContent ? timeEl.textContent.trim() : '';
                if (!matchTime(timeTxt)) continue;

                // 본문(스티커-only면 비어있을 수 있음)
                var bodyEl = li.querySelector('.e1i41bku1');
                var bodyTxt = bodyEl && bodyEl.textContent ? bodyEl.textContent.trim() : '';

                // 프로필(첫 번째 url(...) 만 뽑기)
                var avatarA = li.querySelector('a.css-44n1to.enx4swp0[style*="background-image"]');
                var style   = avatarA ? (avatarA.getAttribute('style')||'') : '';
                var m = style.match(/url\\((?:"|&quot;)?(\\/uploads[^)"']+)/i);
                var profile = m ? abs(m[1]) : '';

                // 스티커(있으면)
                var stickerImg = li.querySelector('em.e1877mpo0 img');
                var sticker = stickerImg ? abs(stickerImg.getAttribute('src')||'') : '';

                var out = {
                  nickname: nickTxt,
                  content:  bodyTxt,
                  profile:  profile,
                  sticker:  sticker,
                  link:     deepLink || location.href
                };
                AndroidApp.postMessage("COMMENT_RESULT:" + JSON.stringify(out));
                return;
              }

              AndroidApp.postMessage("COMMENT_RESULT: not_found");
            } catch(e){
              AndroidApp.postMessage("COMMENT_RESULT: error_" + (e && e.message ? e.message : e));
            }
          })();
        """.trimIndent()
    }
    
    // ✅ 댓글 추출 JS 생성기 (폴백용)
    private fun buildCommentFetchJS(item: CommentNotifItem): String {
        val user = item.actorName
        val topicId = item.topicId
        val deep = item.deepLink ?: "https://playentry.org/"
        val created = item.created

        return """
            (function(){
              try {
                console.log('[COMMENT_FETCH] for $user / $topicId');
                console.log('[COMMENT_FETCH] 현재 URL:', window.location.href);
                let tries = 0;
                function check(){
                  // ✅ 더 넓은 범위의 댓글 셀렉터 시도
                  const selectors = [
                    '.comment_item', 
                    '[data-comment-id]',
                    '.css-1wc2sdr li',  // Entry 댓글 리스트
                    '.e1i41bku1',        // Entry 댓글 아이템 클래스
                    'li[data-testid="comment"]',  // 테스트용 댓글
                    'div[class*="comment"]',           // comment가 포함된 클래스
                    'li[class*="comment"]'             // li 요소 중 comment 포함
                  ];
                  
                  let comments = [];
                  for (let sel of selectors) {
                    const found = document.querySelectorAll(sel);
                    if (found.length > 0) {
                      console.log('[COMMENT_FETCH] 셀렉터', sel, '에서', found.length, '개 요소 발견');
                      comments = found;
                      break;
                    }
                  }
                  
                  console.log('[COMMENT_FETCH] 총 댓글 요소 개수:', comments.length);
                  
                  if (!comments.length && tries++ < 15){
                    console.log('[COMMENT_FETCH] 재시도', tries, '/15');
                    return setTimeout(check, 300);
                  }
                  
                  let found = null;
                  comments.forEach(el=>{
                    const text = el.textContent || '';
                    console.log('[COMMENT_FETCH] 댓글 텍스트 확인:', text.substring(0, 50));
                    if(text.includes("$topicId")) {
                      found = el;
                      console.log('[COMMENT_FETCH] topicId 매칭 성공!');
                    }
                  });
                  
                  if(found){
                    const text = found.innerText.trim().slice(0, 100);
                    const uname = found.querySelector('.user_name, strong, .nickname')?.innerText || "$user";
                    console.log('[COMMENT_FETCH] 댓글 추출 성공:', uname, text.substring(0, 30));
                    AndroidApp.postMessage(JSON.stringify({
                      type:'COMMENT_RESULT',
                      user: uname,
                      text: text,
                      id:'$topicId',
                      link:'$deep',
                      time:'$created'
                    }));
                  } else {
                    console.log('[COMMENT_FETCH] topicId 매칭 실패, not_found 반환');
                    AndroidApp.postMessage(JSON.stringify({type:'COMMENT_RESULT', user:'$user', id:'$topicId', error:'not_found'}));
                  }
                }
                check();
              } catch(e){
                console.log('[COMMENT_FETCH] 에러:', e.message);
                AndroidApp.postMessage(JSON.stringify({type:'COMMENT_RESULT', user:'$user', id:'$topicId', error:e.message}));
              }
            })();
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AndroidLog.e("FATAL_CRASH", "Uncaught exception in ${thread.name}: ${throwable.message}", throwable)
            prevHandler?.uncaughtException(thread, throwable)
        }
        isAppForeground = true
        EntryPollingForegroundService.setMainAppForeground(this, true)
        enableEdgeToEdge()

        // PreferenceManager 초기화
        preferenceManager = PreferenceManager(this)
        val securityStatus = SecurityDiagnostics.checkStatus(this)
        if (securityStatus.isPotentiallyCompromised) {
            Toast.makeText(
                this,
                "기기 보안 상태 확인을 권장합니다. Entify 기능은 차단하지 않습니다.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (!preferenceManager.rememberNotifications) {
            preferenceManager.clearNotificationMemory(bumpGeneration = false)
        }
        syncNotificationMemoryPolicy(force = true)
        restoreCookiesIntoWebView(null, force = true, reason = "startup")

        // Session-bearing WebViews must never be exposed through remote debugging.
        try { WebView.setWebContentsDebuggingEnabled(false) } catch (_: Exception) {}

        ensureNotificationSetup()

        // 알림 클릭으로 진입한 경우 열 URL 보관
        pendingOpenUrl = intent?.getStringExtra("openUrl")

        // 앱 시작 시 토스트는 로그인 확인 후 표시 (startupToastShown은 false로 시작)

        // 앱 시작 시 토큰 상태에 따라 메인 페이지/로그인 페이지를 준비한 뒤
        // 알림 로직을 실행하도록 스타트업 플로우 스케줄
        scheduleStartupFlow("app_launch")
        syncForegroundPollingService("on_create")

        setContent {
            val currentThemeModeString by PreferenceManager.themeModeFlow.collectAsState()
            val currentThemeMode = remember(currentThemeModeString) {
                try { com.example.entify_again.ui.theme.ThemeMode.valueOf(currentThemeModeString) }
                catch (_: Exception) { com.example.entify_again.ui.theme.ThemeMode.SYSTEM }
            }
            EntifyTheme(themeMode = currentThemeMode) {
                var status by remember { mutableStateOf("🔍 로그인 또는 자동 로그인 감지 중...") }
                var notifications by remember { mutableStateOf("") }
                var overlayVisible by remember { mutableStateOf(false) }
                var loginNotified by remember { mutableStateOf(false) }
                var commentNotifsAttempted by remember { mutableStateOf(false) }
                var commentProfile by remember { mutableStateOf("") }
                var commentNickname by remember { mutableStateOf("") }
                var commentContent by remember { mutableStateOf("") }
                var commentSticker by remember { mutableStateOf("") }
                var commentImage by remember { mutableStateOf("") }
                var isLoggedIn by remember { mutableStateOf(preferenceManager.isLoggedIn) }

                // WebView is always alive. When logged in, it hides behind the nav UI.
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Box(
                        modifier = Modifier
                            .padding(inner)
                            .fillMaxSize()
                    ) {
                        // ✅ 원본 WebView — 항상 존재, 로그인 후에는 숨김
                        key(webViewGeneration) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(0f)
                                .then(
                                    if (isLoggedIn || overlayVisible)
                                        Modifier.size(1.dp).offset(9999.dp, 9999.dp)
                                    else Modifier
                                ),
                            factory = { context ->
                                val webView = WebView(context).apply {
                                    AndroidLog.d("MAIN_WEBVIEW", "Main WebView instance created (loggedIn=${preferenceManager.isLoggedIn})")
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.setGeolocationEnabled(false)
                                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                    // ✅ 하얀 화면 문제 해결: 명시적 배경색 설정
                                    setBackgroundColor(android.graphics.Color.WHITE)

                                    // Emulator-specific workaround for black screen (force software layer)
                                    applyEmulatorLayerWorkaround()

                                    // ✅ 쿠키 허용 (여기 수정됨!!)
                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, false)
                                    cookieManager.flush()
                                    webChromeClient = object : WebChromeClient() {
                                        // ✅ 3️⃣ onProgressChanged → onPageFinished로 JS 실행 위치 복귀
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            // ❌ 여기서 JS 실행하지 말기!
                                        }
                                        
                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message?
                                        ): Boolean {
                                            val ctx = view?.context ?: return false
                                            val newWebView = WebView(ctx).apply {
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                                settings.allowFileAccess = false
                                                settings.allowContentAccess = false
                                                settings.setGeolocationEnabled(false)
                                                applyEmulatorLayerWorkaround()
                                            }

                                            val cm = CookieManager.getInstance()
                                            cm.setAcceptCookie(true)
                                            cm.setAcceptThirdPartyCookies(newWebView, false)
                                            cm.flush()

                                            val dialog = Dialog(ctx)
                                            dialog.setContentView(newWebView)
                                            dialog.window?.setLayout(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                            dialog.show()

                                            newWebView.webViewClient = object : WebViewClient() {
                                                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                                    try { dialog.dismiss() } catch (_: Exception) {}
                                                    return true
                                                }

                                                override fun onPageFinished(v: WebView?, url: String?) {
                                                    super.onPageFinished(v, url)
                                                    v?.evaluateJavascript(jsTokenInjector, null)
                                                }

                                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                    return !isTrustedEntryNavigation(request?.url)
                                                }
                                            }

                                            (resultMsg?.obj as? WebView.WebViewTransport)?.webView = newWebView
                                            resultMsg?.sendToTarget()
                                            return true
                                        }
                                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                            // Console output can contain session-bearing page data.
                                            return true
                                        }
                                    }

                                    hardenSessionWebView(this)
                                    webViewClient = object : SecureEntryWebViewClient() {
                                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                            AndroidLog.e("MAIN_WEBVIEW", "Render process gone: didCrash=${renderProcessCrashState(detail)}")
                                            return true
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val uri = request?.url ?: return true
                                            if (isTrustedEntryNavigation(uri)) return false
                                            if (uri.scheme.equals("https", ignoreCase = true)) {
                                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                            }
                                            return true
                                        }

                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): WebResourceResponse? {
                                            logGraphqlIntercept("main", request)
                                            return super.shouldInterceptRequest(view, request)
                                        }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                AndroidLog.d("MAIN_WEBVIEW", "onPageFinished url=$url (previous=$previousUrl)")

                                            // ✅ 로그인 페이지 → 메인 페이지 전환 감지 (수동 로그인 성공)
                                            val wasSigninPage = previousUrl?.contains("/signin") == true
                                            val isNowMainPage = EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/")

                                            if (wasSigninPage && isNowMainPage) {
                                                AndroidLog.d("LOGIN_TRANSITION", "🎉 로그인 페이지 → 메인 페이지 전환 감지! 즉시 토큰 추출 시작")

                                                // 즉시 토큰 추출 (지연 없이)
                                                view?.evaluateJavascript("""
                                                    (() => {
                                                        const xtoken = sessionStorage.getItem('entry_xToken') || localStorage.getItem('playentry_token');
                                                        const csrf = sessionStorage.getItem('entry_csrfToken') || document.querySelector('meta[name="csrf-token"]')?.content;
                                                        let nick = '';
                                                        let uid = '';
                                                        try {
                                                            const el = document.getElementById('__NEXT_DATA__');
                                                            if (el && el.innerText) {
                                                                const d = JSON.parse(el.innerText);
                                                                const u = d?.props?.initialState?.common?.user
                                                                    || d?.props?.pageProps?.initialState?.common?.user;
                                                                nick = u?.nickname || '';
                                                                uid = u?.id || u?._id || '';
                                                            }
                                                        } catch(_){}
                                                        console.log('[LOGIN_TRANSITION] 토큰 추출 시도:', {
                                                            xToken: xtoken ? xtoken.substring(0, 10) + '...' : 'null',
                                                            csrfToken: csrf ? csrf.substring(0, 10) + '...' : 'null',
                                                            nickname: nick,
                                                            userId: uid
                                                        });
                                                        if (xtoken && csrf) {
                                                            AndroidApp.postMessage('SAVE_TOKENS:' + JSON.stringify({
                                                                xToken: xtoken,
                                                                csrfToken: csrf,
                                                                nickname: nick,
                                                                userId: uid
                                                            }));
                                                            AndroidApp.postMessage('LOGIN_OK');
                                                        } else {
                                                            console.error('[LOGIN_TRANSITION] 토큰이 없음!');
                                                        }
                                                    })();
                                                """.trimIndent(), null)

                                                // ✅ 메인 폴러가 허용된 경우에만 주입 (기본은 alarm poller 사용)
                                                view?.postDelayed({
                                                    if (shouldUseMainNotifPoller()) {
                                                        AndroidLog.d("LOGIN_TRANSITION", "📡 메인 WebView에 푸시 알림 폴러 주입")
                                                        view?.evaluateJavascript(jsNotifPoller, null)
                                                    } else {
                                                        AndroidLog.d("LOGIN_TRANSITION", "Skip main poller injection - alarm poller preferred")
                                                    }
                                                }, 250)

                                                Toast.makeText(this@MainActivity, "로그인 완료 - 알림 감지 시작", Toast.LENGTH_SHORT).show()
                                            }

                                            // 현재 URL을 이전 URL로 저장
                                            previousUrl = url

                                            // ✅ 프로필/커뮤니티-유저/로그인 페이지에서는 메인 WebView에서 추가 JS를 최소화 (검은화면 방지)
                                            val isProfilePageBlock = url?.contains("/profile/") == true
                                            val isCommunityUserPageBlock = url?.contains("/community/user/") == true
                                            val isSignin = url?.contains("/signin") == true
                                            if (isProfilePageBlock || isCommunityUserPageBlock) {
                                                AndroidLog.d("FOLLOW_PAGE", "Skip heavy JS on Main WebView: $url")
                                                return
                                            }

                                            // ✅ 로그인 페이지에서는 로그인 감지 폴러 주입 (__NEXT_DATA__ 토큰 추출 방식)
                                            if (isSignin) {
                                                AndroidLog.d("LOGIN_POLLER", "로그인 페이지 감지 - __NEXT_DATA__ 토큰 추출 폴러 주입")
                                                view?.postDelayed({
                                                    view?.evaluateJavascript("""
                                                        (function() {
                                                            if (window.__LOGIN_POLLER__) {
                                                                console.log('[LOGIN_POLLER] Already running');
                                                                return;
                                                            }

                                                            console.log('[LOGIN_POLLER] Starting login detection poller (__NEXT_DATA__ extraction)');
                                                            window.__LOGIN_POLLER__ = true;

                                                            let checkCount = 0;
                                                            const maxChecks = 120; // 120초 동안 체크

                                                            const checkInterval = setInterval(() => {
                                                                checkCount++;
                                                                const currentUrl = window.location.href;

                                                                // 로그 출력 (3초마다)
                                                                if (checkCount % 3 === 0) {
                                                                    console.log('[LOGIN_POLLER] Check #' + checkCount + ' - URL: ' + currentUrl);
                                                                }

                                                                // __NEXT_DATA__에서 토큰 추출 시도
                                                                try {
                                                                    const el = document.getElementById("__NEXT_DATA__");

                                                                    // 디버깅: __NEXT_DATA__ 요소 상태 (5초마다)
                                                                    if (checkCount % 5 === 0) {
                                                                        console.log('[LOGIN_POLLER] __NEXT_DATA__ element: ' + JSON.stringify({
                                                                            exists: !!el,
                                                                            hasText: el ? !!el.innerText : false,
                                                                            textLength: el && el.innerText ? el.innerText.length : 0
                                                                        }));
                                                                    }

                                                                    if (!el || !el.innerText) {
                                                                        // __NEXT_DATA__ 없음 - 아직 로그인 안 됨
                                                                        if (checkCount % 10 === 0) {
                                                                            console.log('[LOGIN_POLLER] No __NEXT_DATA__ yet');
                                                                        }
                                                                        return;
                                                                    }

                                                                    const data = JSON.parse(el.innerText || '{}');
                                                                    const xtoken = data?.props?.initialState?.common?.user?.xToken
                                                                        || data?.props?.pageProps?.initialState?.common?.user?.xToken;
                                                                    const csrf = data?.props?.initialProps?.csrfToken
                                                                        || document.querySelector('meta[name="csrf-token"]')?.content;

                                                                    // ETR_CHK 쿠키에서 토큰 찾기 (엔트리의 인증 쿠키)
                                                                    let cookieToken = null;
                                                                    try {
                                                                        const cookies = document.cookie.split(';').map(c => c.trim());
                                                                        for (const cookie of cookies) {
                                                                            if (cookie.startsWith('ETR_CHK=')) {
                                                                                cookieToken = cookie.split('=')[1];
                                                                                console.log('[LOGIN_POLLER] 🎯 ETR_CHK cookie found!');
                                                                                break;
                                                                            }
                                                                        }
                                                                    } catch (e) {}

                                                                    // 디버깅: 토큰 상태 (5초마다)
                                                                    if (checkCount % 5 === 0) {
                                                                        console.log('[LOGIN_POLLER] Token status: ' + JSON.stringify({
                                                                            hasXToken: !!xtoken,
                                                                            hasCsrf: !!csrf,
                                                                            hasCookieToken: !!cookieToken,
                                                                            hasUser: !!(data?.props?.initialState?.common?.user || data?.props?.pageProps?.initialState?.common?.user),
                                                                            propsKeys: data?.props ? Object.keys(data.props) : [],
                                                                            cookieCount: document.cookie.split(';').length
                                                                        }));

                                                                        // 쿠키 목록 출력
                                                                        const cookies = document.cookie.split(';').map(c => c.trim().split('=')[0]);
                                                                        console.log('[LOGIN_POLLER] Cookie names: ' + JSON.stringify(cookies));
                                                                    }

                                                                    // 토큰 발견! (__NEXT_DATA__ JWT 우선, 폴백으로 ETR_CHK)
                                                                    if ((xtoken && csrf) || (cookieToken && csrf)) {
                                                                        console.log('[LOGIN_POLLER] 🎉 로그인 토큰 발견! 로그인 완료!');
                                                                        console.log('[LOGIN_POLLER] Token source: ' + (xtoken ? '__NEXT_DATA__ JWT' : 'ETR_CHK cookie'));
                                                                        clearInterval(checkInterval);
                                                                        delete window.__LOGIN_POLLER__;

                                                                        // sessionStorage에 저장 (JWT 우선)
                                                                        const finalXToken = xtoken || cookieToken;
                                                                        sessionStorage.setItem('entry_xToken', finalXToken);
                                                                        sessionStorage.setItem('entry_csrfToken', csrf);
                                                                        console.log('[LOGIN_POLLER] Token stored in sessionStorage');

                                                                        // 닉네임 및 사용자 ID 추출
                                                                        const u = data?.props?.initialState?.common?.user
                                                                            || data?.props?.pageProps?.initialState?.common?.user;
                                                                        const nick = u?.nickname || '';
                                                                        const uid = u?.id || u?._id || '';

                                                                        // Android에 알림
                                                                        AndroidApp.postMessage('SAVE_TOKENS:' + JSON.stringify({
                                                                            xToken: finalXToken,
                                                                            csrfToken: csrf,
                                                                            nickname: nick,
                                                                            userId: uid
                                                                        }));
                                                                        AndroidApp.postMessage('LOGIN_OK');

                                                                        // 메인 페이지로 이동 (아직 signin 페이지면)
                                                                        if (currentUrl.includes('/signin')) {
                                                                            console.log('[LOGIN_POLLER] Redirecting to main page');
                                                                            window.location.href = 'https://playentry.org/';
                                                                        }

                                                                        return;
                                                                    }
                                                                } catch (e) {
                                                                    // 파싱 에러
                                                                    if (checkCount % 5 === 0) {
                                                                        console.log('[LOGIN_POLLER] Parse error:', e.message);
                                                                    }
                                                                }

                                                                // 타임아웃
                                                                if (checkCount >= maxChecks) {
                                                                    console.log('[LOGIN_POLLER] Timeout - stopping poller');
                                                                    clearInterval(checkInterval);
                                                                    delete window.__LOGIN_POLLER__;
                                                                }
                                                            }, 500); // 0.5초마다 체크
                                                        })();
                                                    """.trimIndent(), null)
                                                }, 150)
                                                return
                                            }

                                            // ✅ WebView가 먼저 렌더링되도록 모든 JavaScript 실행을 지연시킴
                                            view?.postDelayed({
                                                // ✅ 3️⃣ onPageFinished에서 JS 실행 (정상적인 DOM 로딩 이후)
                                                // 로그인 페이지가 아니면 폴러를 주입
                                                if (url?.contains("/signin") != true) {
                                                    if (shouldUseMainNotifPoller()) {
                                                        view?.evaluateJavascript(jsNotifPoller, null)
                                                    } else {
                                                        AndroidLog.d("NOTIFICATION_LOGIC", "Skip main poller injection on page finish - alarm poller active")
                                                    }
                                                }

                                            // ✅ 메인 WebView에서는 팔로우 프로필 추출을 수행하지 않음 (hiddenWebView 전용)

                                                view?.evaluateJavascript(hideUIJS, null)
                                                // 이전 폴러 중지 후 새 버전 주입 - 댓글 JS 주입 금지
                                                if (!DRY_RUN_NOTIF_ONLY) {
                                                    view?.evaluateJavascript("if (window.__ENTRY_POLLER__){ __ENTRY_POLLER__.stop(); delete window.__ENTRY_POLLER__; }", null)
                                                    // 댓글 JS 주입 금지 (우파로시 섞임 방지)
                                                    // view?.evaluateJavascript(jsCommentDetailByTarget, null)
                                                    // view?.evaluateJavascript(jsOpenAndScrape, null)
                                                    // view?.evaluateJavascript(jsFindComment, null)
                                                }
                                            }, 250) // ✅ 지연 최소화: 렌더링 안정성과 시작 속도 균형

                                            // ✅ 메인(홈) 판별: 정확히 루트(쿼리 포함 가능)
                                            val isMain = EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/")
                                            val isNotificationPage = url?.contains("/notification") == true || url?.contains("/notifications") == true
                                            val isProfilePage = url?.contains("/profile/") == true
                                            val isCommunityUser = url?.contains("/community/user/") == true

                                            if (isMain) {
                                                // ✅ 1단계: 토큰 추출 먼저 실행 (지연)
                                                view?.postDelayed({
                                                    if (!DRY_RUN_NOTIF_ONLY) {
                                                        view?.evaluateJavascript(jsTokenExtractor, null)
                                                    }
                                                }, 250)

                                                // ✅ 2단계: 토큰 체크 후 폴링 시작 (1500ms 후)
                                                view?.postDelayed({
                                                    // 토큰이 있는지 확인하고 폴링 시작
                                                    view?.evaluateJavascript("""
                                                        (() => {
                                                            const xtoken = sessionStorage.getItem('entry_xToken');
                                                            const csrf = sessionStorage.getItem('entry_csrfToken');
                                                            if (xtoken && csrf) {
                                                                console.log('[POLLING] Token found, starting notification polling');
                                                                AndroidApp.postMessage('TOKEN_VERIFIED');
                                                                return true;
                                                            } else {
                                                                console.log('[POLLING] No token found, skipping polling');
                                                                return false;
                                                            }
                                                        })();
                                                    """.trimIndent(), null)
                                                    
                                                    // 폴링 시작
                                                    view?.evaluateJavascript("if (window.__ENTRY_POLLER__){ __ENTRY_POLLER__.stop(); delete window.__ENTRY_POLLER__; }", null)
                                                    if (shouldUseMainNotifPoller()) {
                                                        view?.evaluateJavascript(jsNotifPoller, null)
                                                    } else {
                                                        AndroidLog.d("NOTIFICATION_LOGIC", "Skip delayed poller injection - alarm poller active")
                                                    }
                                                }, 500)
                                                
                                                // ✅ 3단계: 댓글 조회 (4초 후 - 토큰 추출 후 충분한 시간)
                                                view?.postDelayed({
                                                    if (!commentNotifsAttempted) {
                                                        commentNotifsAttempted = true
                                                        try { view.evaluateJavascript(jsFetchCommentNotifications, null) } catch (_: Exception) {}
                                                    }
                                                }, 4000)

                                                // ✅ 1️⃣ detectDOM() 호출 가드 추가 (/profile/ 페이지일 땐 DOM 탐지 실행하지 않기)
                                                if (!isProfilePage && !isCommunityUser) {
                                                AndroidLog.d("DOM_NOTIF", "Page detected: $url")
                                                    
                                                // ✅ 댓글 처리 중이면 DOM 스캐너 잠깐 끄기
                                                if (isProcessingNotif) {
                                                    AndroidLog.d("DOM_NOTIF", "skip detectDOM while processing notif")
                                                } else {
                                                    // DOM 감지 예약에 토큰 체크 추가 (더 긴 지연으로 UI 렌더링 우선)
                                                    val myGen = ++domDetectGen
                                                view?.postDelayed({
                                                        if (myGen != domDetectGen) return@postDelayed // 무효화
                                                    try {
                                                            view.evaluateJavascript("if (window.__ENTRY_POLLER__ && window.__ENTRY_POLLER__.detectDOM && !window.__ENTRY_BLOCK_DOM) { window.__ENTRY_POLLER__.detectDOM(); }", null)
                                                        AndroidLog.d("DOM_NOTIF", "DOM notification detection triggered (1st attempt)")
                                                    } catch (_: Exception) {}
                                                        }, 2000)

                                                    val myGen2 = ++domDetectGen
                                                view?.postDelayed({
                                                        if (myGen2 != domDetectGen) return@postDelayed // 무효화
                                                    try {
                                                            view.evaluateJavascript("if (window.__ENTRY_POLLER__ && window.__ENTRY_POLLER__.detectDOM && !window.__ENTRY_BLOCK_DOM) { window.__ENTRY_POLLER__.detectDOM(); }", null)
                                                        AndroidLog.d("DOM_NOTIF", "DOM notification detection triggered (2nd attempt)")
                                                    } catch (_: Exception) {}
                                                        }, 3500)

                                                    val myGen3 = ++domDetectGen
                                                view?.postDelayed({
                                                        if (myGen3 != domDetectGen) return@postDelayed // 무효화
                                                    try {
                                                            view.evaluateJavascript("if (window.__ENTRY_POLLER__ && window.__ENTRY_POLLER__.detectDOM && !window.__ENTRY_BLOCK_DOM) { window.__ENTRY_POLLER__.detectDOM(); }", null)
                                                        AndroidLog.d("DOM_NOTIF", "DOM notification detection triggered (3rd attempt)")
                                                    } catch (_: Exception) {}
                                                        }, 5000)
                                                }
                                                } else {
                                                    AndroidLog.d("DOM_NOTIF", "Skipping DOM detection for profile/community user page: $url")
                                                }
                                            }

                                            // 작품/커뮤니티 페이지에서 댓글 매핑/추출 스크립트 주입
                                            val isProjectOrCommunity =
                                                EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/project") ||
                                                        EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/community")
                                            if (isProjectOrCommunity) {
                                                if (!DRY_RUN_NOTIF_ONLY) {
                                                    view?.evaluateJavascript(jsCommentMapping, null)
                                                    view?.evaluateJavascript("window.__ENTRY_COMMENTS__ && window.__ENTRY_COMMENTS__()", null)
                                                }
                                                // 실시간 폴링 감시 시작 (1회 주입 가드 포함) - DRY-RUN에서도 폴링은 실행
                                                view?.evaluateJavascript("if (window.__ENTRY_POLLER__){ __ENTRY_POLLER__.stop(); delete window.__ENTRY_POLLER__; }", null)
                                                if (shouldUseMainNotifPoller()) {
                                                    view?.evaluateJavascript(jsNotifPoller, null)
                                                } else {
                                                    AndroidLog.d("NOTIFICATION_LOGIC", "Skip project/community poller injection - alarm poller active")
                                                }
                                                // findComment 정의 보장 (혼선 방지를 위해 주석 처리)
                                                // if (!DRY_RUN_NOTIF_ONLY) {
                                                //     view?.evaluateJavascript(jsFindCommentOverride, null)
                                                //     view?.evaluateJavascript("try{ console.log('[FIND] typeof', typeof window.findComment); }catch(e){}", null)
                                                // }
                                                // Fallback registrar 제거 (안전함수만 사용)
                                                // if (!DRY_RUN_NOTIF_ONLY) {
                                                //     view?.evaluateJavascript(
                                                //     (
                                                //             """
                                                //         (function(){
                                                //           if (typeof window.findComment !== 'function') {
                                                //             window.findComment = async function(nickname, time){
                                                //               try {
                                                //                 const list = document.querySelectorAll('div');
                                                //                 for (const el of list){
                                                //                   const nickEl = el.querySelector('.ee2n3ac5 a');
                                                //                   const nick = nickEl && nickEl.textContent ? nickEl.textContent.trim() : '';
                                                //                   if (!nick || nick !== String(nickname||'').trim()) continue;
                                                //                   const contentEl = el.querySelector('.e1i41bku1');
                                                //                   const payload = { nickname: nick, content: contentEl ? contentEl.textContent.trim() : '', profile: '', sticker: '', image: '' };
                                                //                   try { AndroidApp.postMessage('COMMENT_DETAIL_JSON_FIND:'+JSON.stringify(payload)); } catch(_){ }
                                                //                   return payload;
                                                //                 }
                                                //               } catch(_){ }
                                                //               try { AndroidApp.postMessage('COMMENT_DETAIL_JSON_FIND:'+JSON.stringify({ error:'not_found' })); } catch(_){ }
                                                //               return null;
                                                //             };
                                                //             try { console.log('[FIND] fallback registered'); } catch(_){}
                                                //           }
                                                //         })();
                                                //         """
                                                //             ).trim(), null)
                                                // }
                                                // ✅ 3️⃣ 대기 중이던 findComment 실행 제거 (안전함수만 사용)
                                                // if (!DRY_RUN_NOTIF_ONLY) {
                                                //     pendingFindUser?.let { fu ->
                                                //         val ft = pendingFindTime ?: ""
                                                //         val call = "(function retryFind(){ if (typeof window.findComment==='function'){ window.findComment('" + fu.escapeForJs() + "', '" + ft.escapeForJs() + "', {maxPages:10, toleranceMin:0.084}); } else { console.warn('[FIND] waiting...'); setTimeout(retryFind, 1500); } })()"
                                                //         val v = view
                                                //         // ✅ 페이지 렌더링 안정화 대기 후 실행 (2초)
                                                //         v?.postDelayed({
                                                //             try { AndroidLog.d("FindComment", "invoke -> $call"); v.evaluateJavascript(call, null) } catch (_: Exception) {}
                                                //         }, 2000) // ✅ 3️⃣ 딜레이 단축 (5000ms → 2000ms)
                                                //         pendingFindUser = null; pendingFindTime = null
                                                //     }
                                                // }
                                                
                                                // ✅ 3️⃣ 강제 보강 제거 (안전함수만 사용)
                                                // if (!DRY_RUN_NOTIF_ONLY && !isProcessingNotif) {
                                                //     val forcedUser = lastUserNickname?.escapeForJs() ?: ""
                                                //     if (forcedUser.isNotBlank()) {
                                                //         val forcedCall = "(function retryFind(){ if (typeof window.findComment==='function'){ window.findComment('" + forcedUser + "', '', { maxPages:10 }); } else { console.warn('[FIND] waiting...'); setTimeout(retryFind, 1500); } })()"
                                                //         view?.postDelayed({
                                                //             try { AndroidLog.d("FindComment", "forced invoke -> $forcedCall"); view.evaluateJavascript(forcedCall, null) } catch (_: Exception) {}
                                                //         }, 2000) // ✅ 3️⃣ 딜레이 단축 (5000ms → 2000ms)
                                                //     }
                                                // }
                                                
                                                // ✅ 3️⃣ 추가 폴백: URL에서 target 추출 후 GraphQL 1회 강제 호출 (2.2초 후 - 딜레이 단축)
                                                if (!DRY_RUN_NOTIF_ONLY) {
                                                    val forcedFetch = "(function(){ try{ var u=String(location.href); var g=u.match(/\\/group\\/community\\/([0-9a-f]{24})\\/([0-9a-f]{24})/i); var m=g||u.match(/(?:project|community\\/[^/]+)\\/([0-9a-f]{24})/i); if(m && window.__ENTRY_FETCH_DETAIL__){ window.__ENTRY_FETCH_DETAIL__(m[1]); } }catch(e){} })()"
                                                    view?.postDelayed({
                                                        try { AndroidLog.d("FindComment", "forced fetch -> $forcedFetch"); view.evaluateJavascript(forcedFetch, null) } catch (_: Exception) {}
                                                    }, 2200) // ✅ 3️⃣ 딜레이 단축 (5200ms → 2200ms)
                                                }
                                                // 알림 인텐트로 넘어온 discussId가 있다면 REST 1회 수집 트리거
                                                if (!DRY_RUN_NOTIF_ONLY) {
                                                    intent?.getStringExtra("discussId")?.let { did ->
                                                        mainWebView?.evaluateJavascript(restCommentFetcher + ";window.__ENTRY_FETCH_ONCE__ && window.__ENTRY_FETCH_ONCE('" + did + "');", null)
                                                        intent?.removeExtra("discussId")
                                                    }
                                                }
                                            }
                                        }

                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                            super.onReceivedError(view, request, error)
                                            try {
                                                AndroidLog.e("WebViewError", "onReceivedError: url=" + (request?.url?.toString() ?: "") + ", desc=" + (error?.description ?: "") + ", code=" + (error?.errorCode ?: 0))
                                            } catch (_: Exception) {}
                                        }

                                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                            super.onReceivedHttpError(view, request, errorResponse)
                                            try {
                                                AndroidLog.e("WebViewError", "onReceivedHttpError: url=" + (request?.url?.toString() ?: "") + ", status=" + (errorResponse?.statusCode ?: 0))
                                            } catch (_: Exception) {}
                                            
                                            if (request?.isForMainFrame == true && errorResponse?.statusCode == 403) {
                                                AndroidLog.w("WebViewError", "403 detected - reloading main WebView without cookie reset")
                                                view?.evaluateJavascript(jsTokenInjector, null)
                                                view?.postDelayed({
                                                    try { view.reload() } catch (_: Exception) {}
                                                }, 250)
                                            }
                                        }

                                        // 로그인 후 메인으로 복귀 시 처리 로직은 onPageFinished 내부 상단에서 수행됩니다.
                                    }

                                    installEntryMessageListener(
                                        this,
                                        "AndroidApp",
                                        "MAIN",
                                        EntryOriginPolicy::isTrustedMainHost
                                    ) { msg ->
                                        runOnUiThread {
                                            onJsMessage(msg, "MAIN")
                                            status = msg
                                            
                                            // ✅ TOKEN_VERIFIED 메시지 처리 (토큰 확인됨)
                                            if (msg == "TOKEN_VERIFIED") {
                                                if (!loginNotified) {
                                                    loginNotified = true
                                                    if (!startupToastShown) {
                                                        Toast.makeText(this@MainActivity, "로그인 확인됨 - 알림 감지 시작", Toast.LENGTH_SHORT).show()
                                                        startupToastShown = true
                                                    }
                                                }
                                            }
                                            
                                            if (!loginNotified && msg == "LOGIN_OK") {
                                                AndroidLog.d("LOGIN_SUCCESS", "🎉 LOGIN_OK 메시지 수신!")
                                                loginNotified = true
                                                
                                                // 로그인 성공 시 토큰 저장
                                                AndroidLog.d("LOGIN_SUCCESS", "🔍 WebView에서 토큰 추출 시작...")
                                                mainWebView?.evaluateJavascript("""
                                                    (() => {
                                                        const xtoken = sessionStorage.getItem('entry_xToken');
                                                        const csrf = sessionStorage.getItem('entry_csrfToken');
                                                        let nick = '';
                                                        let uid = '';
                                                        try {
                                                            const el = document.getElementById('__NEXT_DATA__');
                                                            if (el && el.innerText) {
                                                                const d = JSON.parse(el.innerText);
                                                                const u = d?.props?.initialState?.common?.user
                                                                    || d?.props?.pageProps?.initialState?.common?.user;
                                                                nick = u?.nickname || '';
                                                                uid = u?.id || u?._id || '';
                                                            }
                                                        } catch(_){}
                                                        console.log('[LOGIN_SUCCESS] 토큰 추출:', {
                                                            xToken: xtoken ? xtoken.substring(0, 10) + '...' : 'null',
                                                            csrfToken: csrf ? csrf.substring(0, 10) + '...' : 'null',
                                                            nickname: nick,
                                                            userId: uid
                                                        });
                                                        if (xtoken && csrf) {
                                                            AndroidApp.postMessage('SAVE_TOKENS:' + JSON.stringify({
                                                                xToken: xtoken,
                                                                csrfToken: csrf,
                                                                nickname: nick,
                                                                userId: uid
                                                            }));
                                                        } else {
                                                            console.log('[LOGIN_SUCCESS] 토큰이 없음!');
                                                        }
                                                    })();
                                                """.trimIndent(), null)
                                                
                                                startupToastShown = true
                                                // 메인으로 즉시 이동 → onPageFinished에서 3초 후 jsFetchCommentNotifications 호출
                                                try {
                                                    val wv = mainWebView
                                                    if (EntryOriginPolicy.isTrustedPath(wv?.url, "playentry.org", "/signin")) {
                                                        wv?.loadUrl("https://playentry.org/")
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                            // 로그인 오류 계열이면, 로그인 페이지에서 로그인 완료 후 메인 이동 시 폴러 주입을 예약
                                            if (msg.startsWith("LOGIN_ERR:")) {
                                                pendingNotifFetchAfterLogin = true
                                            }
                                            // 로그인 실패 팝업은 표시하지 않음 (요구사항)

                                            // 알림 타입 구분 처리 (좋아요/댓글 완전 분리)
                                            if (msg.startsWith("NOTIFICATION_TYPE:")) {
                                                val notificationType = msg.removePrefix("NOTIFICATION_TYPE:")
                                                AndroidLog.d("NotificationType", "Detected type: $notificationType")

                                                // 좋아요 계열은 findComment 호출하지 않음 (단순 알림만)
                                                if (notificationType.startsWith("LIKE_")) {
                                                    AndroidLog.d("NotificationType", "Like notification - showing simple notification")
                                                    // 좋아요 알림은 단순 알림만 표시
                                                }

                                                // 댓글 계열만 findComment 호출하여 상세 분석
                                                if (notificationType.startsWith("COMMENT_")) {
                                                    AndroidLog.d("NotificationType", "Comment notification - proceeding with findComment")
                                                }
                                            }

                                            // 좋아요 알림 처리 (단순 알림만)
                                            if (msg.startsWith("LIKE:") && !DRY_RUN_NOTIF_ONLY) {
                                                val payload = msg.removePrefix("LIKE:")
                                                val jo = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }
                                                val user = jo.optString("user", "(작성자)")
                                                val time = jo.optString("time", "")
                                                val created = jo.optString("created", "").ifBlank { time }
                                                val notifType = jo.optString("type", "LIKE_UNKNOWN")
                                                val contentTitleHint = sanitizeNotificationText(
                                                    jo.optString("contentTitle", "").ifBlank { jo.optString("title", "") },
                                                    120
                                                )
                                                val link = jo.optJSONObject("link")
                                                val categoryHint = link?.optString("category").orEmpty()
                                                    .ifBlank { jo.optString("category", "") }
                                                val targetHint = link?.optString("target").orEmpty()
                                                    .ifBlank { jo.optString("id", "") }
                                                val topicId = jo.optString("topicId", "")
                                                    .ifBlank { jo.optString("id", "") }
                                                    .ifBlank { targetHint }
                                                val deepLink = deepLinkFromLink(link, categoryHint, targetHint)

                                                // 좋아요 알림은 단순 알림만 표시 (findComment 호출 안 함)
                                                val likeTypeText = when (notifType) {
                                                    "LIKE_PROJECT" -> "작품을 좋아합니다"
                                                    "LIKE_COMMENT" -> "댓글을 좋아합니다"
                                                    "LIKE_POST" -> "게시글을 좋아합니다"
                                                    else -> "좋아요"
                                                }

                                                val notificationBody = "${user}님이 $likeTypeText"
                                                AndroidLog.d("LIKE", "recv user=$user, type=$notifType, body=$notificationBody")

                                                // 좋아요 알림 표시
                                                when (notifType) {
                                                    "LIKE_PROJECT" -> postLikeProject(
                                                        user = user,
                                                        topicId = topicId,
                                                        created = created,
                                                        openUrl = deepLink,
                                                        projectIdHint = targetHint,
                                                        contentTitleHint = contentTitleHint
                                                    )
                                                    "LIKE_COMMENT" -> postLikeComment(
                                                        user = user,
                                                        topicId = topicId,
                                                        created = created,
                                                        openUrl = deepLink,
                                                        contentTitleHint = contentTitleHint,
                                                        projectIdHint = targetHint
                                                    )
                                                    else -> {
                                                        if (deepLink.isNotBlank()) {
                                                            showLikeNotification(notificationBody, deepLink, contentTitleHint)
                                                        }
                                                    }
                                                }
                                            }

                                            // 팔로잉 알림 처리 (큐에 추가)
                                            if (msg.startsWith("FOLLOW:") && !DRY_RUN_NOTIF_ONLY) {
                                                val payload = msg.removePrefix("FOLLOW:")
                                                val jo = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }
                                                val user = jo.optString("user", "(작성자)")
                                                val time = jo.optString("time", "")
                                                val link = jo.optJSONObject("link")
                                                val categoryHint = link?.optString("category").orEmpty()
                                                    .ifBlank { jo.optString("category", "") }
                                                val targetHint = link?.optString("target").orEmpty()
                                                    .ifBlank { jo.optString("id", "") }
                                                val deepLink = deepLinkFromLink(link, categoryHint, targetHint)
                                                val linkTarget = link?.optString("target").orEmpty()
                                                val linkUrl = link?.optString("url").orEmpty()
                                                val absUrl = when {
                                                    linkUrl.startsWith("http") -> linkUrl
                                                    linkUrl.startsWith("/") -> "https://playentry.org$linkUrl"
                                                    linkUrl.isNotBlank() -> "https://playentry.org/$linkUrl"
                                                    else -> ""
                                                }
                                                val domUserId = when {
                                                    linkTarget.isNotBlank() && HEX_OBJECT_ID.matches(linkTarget) -> linkTarget
                                                    else -> extractUserIdFromUrl(absUrl)
                                                }

                                                AndroidLog.d("FOLLOW", "recv user=$user, body=${user}님이 팔로잉합니다")

                                                // 큐에 추가 (순차 처리)
                                                enqueueNotif(NotifTask.Follow(user, time, deepLink, domUserId))
                                            }

                                            // 댓글 알림 팝업 처리
                                            if (msg.startsWith("COMMENT:")) {
                                                val payload = msg.removePrefix("COMMENT:")
                                                val jo = try { JSONObject(payload) } catch (_: Exception) { JSONObject() }

                                                val user = jo.optString("user", "(작성자)")
                                                val time = jo.optString("time", "")
                                                val notifId = jo.optString("id", "")
                                                val template = jo.optString("template", "").trim().lowercase(Locale.ROOT)
                                                val contentTitleHint = sanitizeNotificationText(
                                                    jo.optString("contentTitle", "").ifBlank { jo.optString("title", "") },
                                                    120
                                                )
                                                val isSupportedTemplate =
                                                    TEMPLATE_COMMENT_PROJECT.contains(template) || TEMPLATE_COMMENT_REPLY.contains(template)
                                                if (!isSupportedTemplate) {
                                                    AndroidLog.d(
                                                        "COMMENT",
                                                        "Skip legacy COMMENT payload without supported template (id=$notifId, template='$template')"
                                                    )
                                                    return@runOnUiThread
                                                }
                                                val link = jo.optJSONObject("link")
                                                val categoryHint = link?.optString("category").orEmpty()
                                                    .ifBlank { jo.optString("category", "") }
                                                val targetHint = link?.optString("target").orEmpty()
                                                    .ifBlank { jo.optString("id", notifId) }
                                                val deepLink = deepLinkFromLink(link, categoryHint, targetHint)
                                                
                                                // deepLink가 /alarm 이면 스킵 (유효 타겟 없음)
                                                if (deepLink.endsWith("/alarm")) {
                                                    AndroidLog.d("COMMENT", "Skip DOM fallback: no target (deepLink=/alarm)")
                                                } else {
                                                    // 큐에 추가 (순차 처리)
                                                    AndroidLog.d("COMMENT", "Comment detected from DOM - user: $user, time: $time")
                                                    val linkTarget = link?.optString("target")
                                                    val createdIso = jo.optString("timeIso", "").ifBlank { time }
                                                    enqueueNotif(
                                                        NotifTask.Comment(
                                                            CommentNotifItem(
                                                                payload,
                                                                user,
                                                                notifId,
                                                                deepLink,
                                                                createdIso,
                                                                template,
                                                                linkTarget,
                                                                categoryHint,
                                                                contentTitleHint
                                                            )
                                                        )
                                                    )
                                                }
                                            }
                                            if (msg.startsWith("COMMENT_DETAIL:")) {
                                                try {
                                                    val payload = msg.removePrefix("COMMENT_DETAIL:").trim()
                                                    var handled = false
                                                    // 1) JSON 객체 형태 처리
                                                    if (payload.startsWith("{")) {
                                                        try {
                                                            val o = JSONObject(payload)
                                                            commentProfile = o.optString("profile", "")
                                                            commentNickname = o.optString("nickname", "")
                                                            commentContent = o.optString("content", o.optString("내용", ""))
                                                            commentSticker = o.optString("sticker", "")
                                                            commentImage = o.optString("image", "")
                                                            AndroidLog.d("FindCommentFix", "commentContent=" + commentContent)
                                                            AndroidLog.d("CommentDetail", "COMMENT_DETAIL(JSON) profile=" + commentProfile + ", sticker=" + commentSticker + ", image=" + commentImage + ", nickname=" + commentNickname)
                                                            AndroidLog.d("EntifyProfile", "raw(COMMENT_DETAIL.JSON)=" + o.optString("profile"))
                                                            AndroidLog.d("EntifyProfile", "sanitized(COMMENT_DETAIL.JSON)=" + sanitizeUrl(commentProfile))
                                                            // 중간 스냅샷에서는 알림을 발송하지 않음. 최종(JSON_FIND)에서만 발송
                                                            // 단, 프로필이 확보되었고 최종 알림이 아직 아니면 안전 폴백으로 1회 발송
                                                            val nickOk = !lastUserNickname.isNullOrBlank() && commentNickname.isNotBlank() && commentNickname.equals(lastUserNickname!!, ignoreCase = true)
                                                            val isDefaultProf = commentProfile.contains("/profileImage.svg", true) || commentProfile.contains("EmptyImage", true) || commentProfile.contains("/img/EmptyImage.svg", true)
                                                            if (pendingAllowNotify && !notifiedCurrent && nickOk && commentProfile.isNotBlank() && !isDefaultProf) {
                                                                val deep = lastDeepLink ?: "https://playentry.org/"
                                                                val topicId = lastTopicId
                                                                val notifIdInt = stableNotifId(topicId, (lastUserNickname ?: commentNickname) + commentContent)
                                                                val who = (lastUserNickname ?: commentNickname).ifBlank { "새 댓글" }
                                                                val createdForKey = lastCreated?.ifBlank { "t:$topicId" } ?: "t:$topicId"
                                                                val key = notifKey("COMMENT", who, "댓글을 남겼습니다", topicId, createdForKey)
                                                                if (rememberNotificationKey(key)) {
                                                                    val isReply = findCommentKind(topicId)?.equals("REPLY", true) == true
                                                                    val notifTitle = if (isReply) "${who}님의 답글" else "${who}님의 댓글"
                                                                    postSimpleNotification(
                                                                        key = key,  // 고유 키 사용
                                                                        title = notifTitle,
                                                                        text = commentContent,
                                                                        whenMillis = System.currentTimeMillis(),
                                                                        openUrl = deep,
                                                                        topicId = topicId
                                                                    )
                                                                    if (!DRY_RUN_NOTIF_ONLY && !topicId.isNullOrBlank()) {
                                                                        markTopicSeen(topicId)
                                                                    }
                                                                }
                                                                notifiedCurrent = true
                                                            }
                                                            handled = true
                                                        } catch (_: Exception) { /* fallthrough */ }
                                                    }
                                                    // 2) 파이프 구분자 형태 처리 (profile|nickname|content|sticker|image)
                                                    if (!handled) {
                                                        val arr = payload.split("|", limit = 5)
                                                        commentProfile = arr.getOrNull(0) ?: ""
                                                        // PIPE 스냅샷에서도 findComment 전용 URL은 설정하지 않음
                                                        commentNickname = arr.getOrNull(1) ?: ""
                                                        commentContent = arr.getOrNull(2) ?: ""
                                                        val stickerUrl = arr.getOrNull(3) ?: ""
                                                        val imageUrl = arr.getOrNull(4) ?: ""
                                                        commentSticker = stickerUrl
                                                        commentImage = imageUrl
                                                        AndroidLog.d("CommentDetail", "COMMENT_DETAIL(PIPE) profile=" + commentProfile + ", sticker=" + commentSticker + ", image=" + commentImage + ", nickname=" + commentNickname)
                                                        AndroidLog.d("EntifyProfile", "raw(COMMENT_DETAIL.PIPE)=" + (arr.getOrNull(0) ?: ""))
                                                        AndroidLog.d("EntifyProfile", "sanitized(COMMENT_DETAIL.PIPE)=" + sanitizeUrl(commentProfile))
                                                        // 파이프 폴백에서도 알림은 발송하지 않음. 최종(JSON_FIND)에서만 발송
                                                        // 단, 타겟 닉네임과 일치하고 기본 프로필이 아니며 최종 알림이 아직 아니면 안전 폴백으로 1회 발송
                                                        val nickOk2 = !lastUserNickname.isNullOrBlank() && commentNickname.isNotBlank() && commentNickname.equals(lastUserNickname!!, ignoreCase = true)
                                                        val isDefaultProf2 = commentProfile.contains("/profileImage.svg", true) || commentProfile.contains("EmptyImage", true) || commentProfile.contains("/img/EmptyImage.svg", true)
                                                        if (pendingAllowNotify && !notifiedCurrent && nickOk2 && commentProfile.isNotBlank() && !isDefaultProf2) {
                                                            val deep = lastDeepLink ?: "https://playentry.org/"
                                                            val topicId = lastTopicId
                                                            val notifIdInt = stableNotifId(topicId, (lastUserNickname ?: commentNickname) + commentContent)
                                                            val who = (lastUserNickname ?: commentNickname).ifBlank { "새 댓글" }
                                                            val createdForKey = lastCreated?.ifBlank { "t:$topicId" } ?: "t:$topicId"
                                                            val key = notifKey("COMMENT", who, "댓글을 남겼습니다", topicId, createdForKey)
                                                            if (rememberNotificationKey(key)) {
                                                                val isReply = findCommentKind(topicId)?.equals("REPLY", true) == true
                                                                val notifTitle = if (isReply) "${who}님의 답글" else "${who}님의 댓글"
                                                                postSimpleNotification(
                                                                    key = key,  // 고유 키 사용
                                                                    title = notifTitle,
                                                                    text = commentContent,
                                                                    whenMillis = System.currentTimeMillis(),
                                                                    openUrl = deep,
                                                                    topicId = topicId
                                                                )
                                                                // topicId도 seen에 기록
                                                                if (!DRY_RUN_NOTIF_ONLY && topicId != null && topicId.isNotBlank()) {
                                                                    markTopicSeen(topicId)
                                                                }
                                                            }
                                                            notifiedCurrent = true
                                                        }
                                                    }
                                                } catch (_: Exception) {}
                                            }

                                            // ✅ 댓글 최종 결과 수신 (DOM/안전함수 공통)
                                            if (msg.startsWith("COMMENT_RESULT:")) {
                                                val payload = msg.removePrefix("COMMENT_RESULT:").trim()

                                                // 디버깅 마커는 무시
                                                if (payload == "__DOM_SCAN_START__" || payload == "__DOM_MATCH__") {
                                                    // 디버깅 마커는 무시하고 다음 처리로
                                                } else if (!payload.startsWith("{")) {
                                                    // JSON이 아닌 문자열은 스킵
                                                } else {
                                                    try {
                                                        val o = JSONObject(payload)
                                                        val profRaw = o.optString("profile", "")
                                                        val nickname = o.optString("nickname", "")
                                                        val userId = o.optString("userId", "")
                                                        val content  = o.optString("content", "")
                                                        val sticker  = o.optString("sticker", "")
                                                        val link     = o.optString("link", lastDeepLink ?: "https://playentry.org/")
                                                        val time     = o.optString("time", lastCreated ?: "")

                                                        // ✅ matchType 확인: template_1(comment)은 userId 체크 안 함
                                                        val matchType = o.optString("matchType", "comment")
                                                        val targetUid = preferenceManager.commentUserId?.trim().orEmpty()
                                                        val skipUidCheck = matchType == "comment"  // template_1은 시간만 체크

                                                        if (!skipUidCheck && looksLikeUid(targetUid) && userId.isNotBlank() && !userId.equals(targetUid, ignoreCase = true)) {
                                                            AndroidLog.d("COMMENT_RESULT", "skip: userId mismatch target=$targetUid got=$userId (matchType=$matchType)")
                                                        } else {
                                                            // 프로필 URL 정리(절대경로화) + 기본이미지 필터
                                                            val profAbs = sanitizeImageUrl(profRaw)
                                                            val isDefault = profAbs.contains("/profileImage.svg", true) ||
                                                                            profAbs.contains("EmptyImage", true) ||
                                                                            profAbs.isBlank()

                                                            // 팔로잉 알림처럼 아바타 적용: 우선 profAbs, 없으면 캐시/선호값 사용
                                                            val finalProf = when {
                                                                !isDefault -> profAbs
                                                                !preferredProfileUrl.isNullOrBlank() && nickname.equals(lastUserNickname ?: "", true) -> preferredProfileUrl!!
                                                                else -> profAbs // 그래도 없으면 그대로(나중에 자동 교체되게)
                                                            }

                                                            // 알림 한 번만
                                                            if (pendingAllowNotify && !notifiedCurrent) {
                                                                val topicId = lastTopicId
                                                                val who = (lastUserNickname ?: nickname).ifBlank { "새 댓글" }
                                                                val key = notifKey("COMMENT", who, "댓글을 남겼습니다", topicId, time.ifBlank { "t:$topicId" })

                                                                if (rememberNotificationKey(key)) {
                                                                    val notifId = stableIdFrom(key)
                                                                    val whenMillis = parseWhen(time)
                                                                    val isReply = findCommentKind(topicId)?.equals("REPLY", true) == true || matchType == "reply"
                                                                    val notifTitle = if (isReply) "${who}님의 답글" else "${who}님의 댓글"
                                                                    // ✅ 프로필 이미지를 큰 아이콘으로 붙여서 표시 (팔로잉 알림과 동일한 UX)
                                                                        val posted = postCommentNotification(
                                                                            title = notifTitle,
                                                                            text = content,
                                                                            openUrl = link,
                                                                            profileUrl = finalProf,
                                                                            avatarName = who,
                                                                            tag = key,
                                                                            nid = notifId,
                                                                            whenMillis = whenMillis,
                                                                            topicIdHint = topicId
                                                                        )
                                                                        if (!posted) {
                                                                            releaseRememberedNotificationKey(key)
                                                                        }

                                                                    if (!DRY_RUN_NOTIF_ONLY && !topicId.isNullOrBlank()) {
                                                                        markTopicSeen(topicId)
                                                                    }
                                                                }
                                                                notifiedCurrent = true
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        AndroidLog.e("COMMENT_RESULT", "parse-error: ${e.message}")
                                                    }
                                                }
                                            }

                                            // ✅ COMMENT_TEST_COUNT 수신 시 디버그 로그
                                            if (msg.startsWith("COMMENT_TEST_COUNT:")) {
                                                val count = msg.removePrefix("COMMENT_TEST_COUNT:")
                                                AndroidLog.d("COMMENT_DEBUG", "현재 페이지 댓글 요소 개수: $count")
                                                if (count == "0") {
                                                    AndroidLog.w("COMMENT_DEBUG", "⚠️ 현재 페이지에 댓글이 없습니다. DeepLink 확인 필요")
                                                }
                                            }

                                            if (msg.contains("\"type\":\"FOLLOW_RESULT\"")) {
                                                handleFollowProfileResult(msg)
                                            }
                                            if (msg.contains("\"type\":\"SHARE_RESULT\"")) {
                                                handleShareGraphqlResult(msg)
                                            }
                                            
                                            // ✅ 중복 핸들러 제거됨 - 공통 핸들러(onJsMessage)에서 처리
                                            // 중복 핸들러 전체 블록 주석 처리됨 - 공통 핸들러에서 처리
                                            /*
                                            if (msg.startsWith("COMMENT_RESULT:")) {
                                                try {
                                                    val payload = msg.removePrefix("COMMENT_RESULT:")
                                                    when {
                                                        payload == "not_found" -> {
                                                            AndroidLog.d("COMMENT_RESULT", "댓글을 찾을 수 없습니다")
                                                            return@runOnUiThread
                                                        }
                                                        payload.startsWith("error_") -> {
                                                            AndroidLog.e("COMMENT_RESULT", "댓글 찾기 에러: ${payload.removePrefix("error_")}")
                                                            return@runOnUiThread
                                                        }
                                                        !payload.trim().startsWith("{") -> {
                                                            AndroidLog.w("COMMENT_RESULT", "JSON 아님: $payload")
                                                            return@runOnUiThread
                                                        }
                                                        else -> {
                                                            val o = JSONObject(payload) // ← 이제서야 안전
                                                            val user = o.optString("nickname")
                                                            val text = o.optString("content")
                                                            val profile = o.optString("profile")
                                                            val link = o.optString("link", "https://playentry.org/")
                                                            
                                                            AndroidLog.d("COMMENT_RESULT", "✅ 댓글: $user / 내용: $text / 프로필: $profile")

                                                            val tag = "COMMENT|$user"
                                                            val nid = stableIdFrom(tag)

                                                            val intent = android.content.Intent(this@MainActivity, MainActivity::class.java)
                                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                            if (link.isNotBlank()) {
                                                                intent.putExtra("openUrl", link)
                                                            }

                                                            val pi = android.app.PendingIntent.getActivity(
                                                                this@MainActivity,
                                                                nid,
                                                                intent,
                                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                                            )

                                                            val builder = NotificationCompat.Builder(this@MainActivity, commentChannelId)
                                                                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                                                                .setPublicVersion(buildLockScreenPlaceholder(commentChannelId, android.R.drawable.ic_dialog_info))
                                                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                                                .setContentTitle("$user 님의 댓글")
                                                                .setContentText(text.take(80))
                                                                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                                                                .setContentIntent(pi)
                                                                .setOnlyAlertOnce(true)
                                                                .setSilent(true)
                                                                .setAutoCancel(true)

                                                            // ✅ 권한 체크 후 알림 발송
                                                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                                            ) {
                                                                try {
                                                                    NotificationManagerCompat.from(this@MainActivity).notify(tag, nid, builder.build())
                                                                    AndroidLog.d("COMMENT_RESULT", "✅ 댓글 알림 표시 완료 ($user)")
                                                                    notifiedCurrent = true // ✅ NO-NAV 성공 플래그 설정
                                                                } catch (e: SecurityException) {
                                                                    AndroidLog.e("COMMENT_RESULT", "알림 권한 없음: ${e.message}")
                                                                }
                                                            } else {
                                                                AndroidLog.w("COMMENT_RESULT", "알림 권한이 없어서 댓글 알림을 표시할 수 없습니다")
                                                            }
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    AndroidLog.e("COMMENT_RESULT", "댓글 알림 처리 실패", e)
                                                }
                                            }
                                            */
                                            
                                            // ✅ 중복 핸들러 제거됨 - 공통 핸들러에서 처리
                                            /*
                                            // ✅ 기존 COMMENT_RESULT 수신 시 알림 띄우기 (폴백용)
                                            if (msg.contains("\"type\":\"COMMENT_RESULT\"")) {
                                                try {
                                                    val jo = JSONObject(msg)
                                                    val user = jo.optString("user")
                                                    val text = jo.optString("text")
                                                    val link = jo.optString("link")
                                                    val id = jo.optString("id")
                                                    val error = jo.optString("error")

                                                    if (!error.isNullOrBlank()) {
                                                        AndroidLog.d("COMMENT_RESULT", "댓글 추출 실패: $error")
                                                        return@runOnUiThread
                                                    }

                                                    val tag = "COMMENT|$id"
                                                    val nid = stableIdFrom(tag)

                                                    val intent = android.content.Intent(this@MainActivity, MainActivity::class.java)
                                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                    if (link.isNotBlank()) {
                                                        intent.putExtra("openUrl", link)
                                                    }

                                                    val pi = android.app.PendingIntent.getActivity(
                                                        this@MainActivity,
                                                        nid,
                                                        intent,
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                                    )

                                                    val builder = NotificationCompat.Builder(this@MainActivity, commentChannelId)
                                                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                                                        .setPublicVersion(buildLockScreenPlaceholder(commentChannelId, android.R.drawable.ic_dialog_info))
                                                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                                                        .setContentTitle("$user 님이 댓글을 남겼어요")
                                                        .setContentText(text.take(80))
                                                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                                                        .setContentIntent(pi)
                                                        .setOnlyAlertOnce(true)
                                                        .setSilent(true)
                                                        .setAutoCancel(true)

                                                    // ✅ 권한 체크 후 알림 발송
                                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                                    ) {
                                                        try {
                                                            NotificationManagerCompat.from(this@MainActivity).notify(tag, nid, builder.build())
                                                            AndroidLog.d("COMMENT_RESULT", "✅ 댓글 알림 표시 완료 ($user)")
                                                            notifiedCurrent = true // ✅ 폴백 성공 플래그 설정
                                                        } catch (e: SecurityException) {
                                                            AndroidLog.e("COMMENT_RESULT", "알림 권한 없음: ${e.message}")
                                                        }
                                                    } else {
                                                        AndroidLog.w("COMMENT_RESULT", "알림 권한이 없어서 댓글 알림을 표시할 수 없습니다")
                                                    }
                                                } catch (e: Exception) {
                                                    AndroidLog.e("COMMENT_RESULT", "댓글 알림 처리 실패", e)
                                                }
                                            }
                                            */
                                            
                                            // NEW_NOTIF은 onJsMessage의 공통 핸들러에서만 처리한다.
                                            // 여기서 다시 처리하면 동일 payload가 중복 알림으로 발행된다.
                                            if (msg.startsWith("COMMENT_DETAIL_JSON_FIND:")) {
                                                // findComment 최종 결과 수신 시점에서만 알림 발송
                                                try {
                                                    val o = JSONObject(msg.removePrefix("COMMENT_DETAIL_JSON_FIND:"))
                                                    if (o.has("error")) {
                                                        onCommentProcessed()
                                                        return@runOnUiThread
                                                    }
                                                    commentProfile = o.optString("profile", "")
                                                    if (commentProfile.isNotBlank()) findProfileUrl = commentProfile
                                                    commentNickname = o.optString("nickname", "")
                                                    commentContent = o.optString("content", o.optString("내용", ""))
                                                    commentSticker = o.optString("sticker", "")
                                                    commentImage = o.optString("image", "")
                                                    AndroidLog.d("CommentDetail", "JSON_FIND profile=" + commentProfile + ", sticker=" + commentSticker + ", image=" + commentImage + ", nickname=" + commentNickname)
                                                    val deep = lastDeepLink ?: "https://playentry.org/"
                                                    val topicId = lastTopicId
                                                    val notifIdInt = stableNotifId(topicId, (lastUserNickname ?: commentNickname) + commentContent)
                                                    val who = (lastUserNickname ?: commentNickname).ifBlank { "새 댓글" }
                                                    if (pendingAllowNotify) {
                                                        val createdForKey = lastCreated?.ifBlank { "t:$topicId" } ?: "t:$topicId"
                                                        val key = notifKey("COMMENT", who, "댓글을 남겼습니다", topicId, createdForKey)
                                                        if (rememberNotificationKey(key)) {
                                                            val isReply = findCommentKind(topicId)?.equals("REPLY", true) == true
                                                            val notifTitle = if (isReply) "${who}님의 답글" else "${who}님의 댓글"
                                                            postSimpleNotification(
                                                                key = key,
                                                                title = notifTitle,
                                                                text = commentContent,
                                                                whenMillis = System.currentTimeMillis(),
                                                                openUrl = deep,
                                                                topicId = topicId
                                                            )
                                                            // topicId도 seen에 기록
                                                            if (!DRY_RUN_NOTIF_ONLY && topicId != null && topicId.isNotBlank()) {
                                                                markTopicSeen(topicId)
                                                            }
                                                        }

                                                        // 댓글 처리 완료, 다음 댓글 처리
                                                        onCommentProcessed()
                                                    } else {
                                                        // 알림 조건 불만족 시에도 다음 댓글 처리
                                                        onCommentProcessed()
                                                    }
                                                } catch (_: Exception) {
                                                    // 오류 발생 시에도 다음 댓글 처리
                                                    onCommentProcessed()
                                                }
                                            }
                                            if (msg.startsWith("COMMENT_DETAIL_JSON:")) {
                                                // DOM 스크랩/기타 경로: 기본 필터를 통과하는 경우에만 보조적으로 갱신
                                                try {
                                                    val o = JSONObject(msg.removePrefix("COMMENT_DETAIL_JSON:"))
                                                    if (o.has("error")) {
                                                        onCommentProcessed()
                                                        return@runOnUiThread
                                                    }
                                                    val prof = o.optString("profile", "")
                                                    val nick = o.optString("nickname", "")
                                                    val isDefault = prof.contains("/profileImage.svg", true) || prof.contains("DefaultProfile", true) || prof.contains("/img/EmptyImage.svg", true)
                                                    val nickMismatch = !lastUserNickname.isNullOrBlank() && nick.isNotBlank() && !nick.equals(lastUserNickname!!, ignoreCase = true)
                                                    if (isDefault || nickMismatch) return@runOnUiThread
                                                    commentProfile = prof
                                                    commentNickname = nick
                                                    commentContent = o.optString("content", o.optString("내용", ""))
                                                    commentSticker = o.optString("sticker", "")
                                                    commentImage = o.optString("image", "")
                                                    AndroidLog.d("FindCommentFix", "commentContent=" + commentContent)
                                                    AndroidLog.d("CommentDetail", "JSON profile=" + commentProfile + ", sticker=" + commentSticker + ", image=" + commentImage + ", nickname=" + commentNickname)
                                                    AndroidLog.d("EntifyProfile", "raw(JSON)=" + prof)
                                                    AndroidLog.d("EntifyProfile", "sanitized(JSON)=" + sanitizeUrl(commentProfile))
                                                    val created = o.optString("created", "")
                                                    AndroidLog.d("FindComment", "닉네임=" + commentNickname + ", 작성시각=" + created + ", 내용=" + commentContent + ", 프로필=" + commentProfile + ", 스티커=" + commentSticker + ", 첨부이미지=" + commentImage)
                                                    val deep = lastDeepLink ?: "https://playentry.org/"
                                                    val topicId = lastTopicId
                                                    val notifIdInt = stableNotifId(topicId, (lastUserNickname ?: commentNickname) + commentContent)
                                                    val who = (lastUserNickname ?: commentNickname).ifBlank { "새 댓글" }
                                                    if (pendingAllowNotify) {
                                                        val createdForKey = lastCreated?.ifBlank { "t:$topicId" } ?: "t:$topicId"
                                                        val key = notifKey("COMMENT", who, "댓글을 남겼습니다", topicId, createdForKey)
                                                        if (rememberNotificationKey(key)) {
                                                            val isReply = findCommentKind(topicId)?.equals("REPLY", true) == true
                                                            val notifTitle = if (isReply) "${who}님의 답글" else "${who}님의 댓글"
                                                            postSimpleNotification(
                                                                key = key,
                                                                title = notifTitle,
                                                                text = commentContent,
                                                                whenMillis = System.currentTimeMillis(),
                                                                openUrl = deep,
                                                                topicId = topicId,
                                                                actorHexId = o.optString("userId", "")
                                                            )
                                                            // topicId도 seen에 기록
                                                            if (!DRY_RUN_NOTIF_ONLY && topicId != null && topicId.isNotBlank()) {
                                                                markTopicSeen(topicId)
                                                            }
                                                        }
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                            if (msg.startsWith("COMMENT_DETAIL_ARRAY:")) {
                                                // 콘솔 스크립트와 동일한 배열 포맷 수신 → 팝업으로 표시
                                                try {
                                                    val arr = JSONArray(msg.removePrefix("COMMENT_DETAIL_ARRAY:"))
                                                    if (arr.length() > 0) {
                                                        val it = arr.getJSONObject(0)
                                                        val prof = it.optString("프로필", "")
                                                        val nick = it.optString("닉네임", "")
                                                        val isDefault = prof.contains("/profileImage.svg", true) || prof.contains("DefaultProfile", true) || prof.contains("/img/EmptyImage.svg", true)
                                                        val nickMismatch = !lastUserNickname.isNullOrBlank() && nick.isNotBlank() && !nick.equals(lastUserNickname!!, ignoreCase = true)
                                                        if (isDefault || nickMismatch) return@runOnUiThread
                                                        commentProfile = it.optString("프로필", "")
                                                        commentNickname = it.optString("닉네임", "")
                                                        commentContent = it.optString("내용", "")
                                                        val stickerUrl = it.optString("스티커", "")
                                                        val imageUrl = it.optString("첨부이미지", "")
                                                        commentSticker = stickerUrl
                                                        commentImage = imageUrl
                                                        AndroidLog.d("CommentDetail", "ARRAY profile=" + commentProfile + ", sticker=" + commentSticker + ", image=" + commentImage + ", nickname=" + commentNickname)
                                                        AndroidLog.d("EntifyProfile", "raw(ARRAY)=" + it.optString("프로필"))
                                                        AndroidLog.d("EntifyProfile", "sanitized(ARRAY)=" + sanitizeUrl(commentProfile))
                                                        // 최종 findComment 결과 로그 (6필드)
                                                        val createdKo = it.optString("작성시각", "")
                                                        AndroidLog.d(
                                                            "FindComment",
                                                            "닉네임=" + commentNickname + ", 작성시각=" + createdKo + ", 내용=" + commentContent + ", 프로필=" + commentProfile + ", 스티커=" + commentSticker + ", 첨부이미지=" + commentImage
                                                        )
                                                        val deep = lastDeepLink ?: "https://playentry.org/"
                                                        val topicId = lastTopicId
                                                        val notifIdInt = stableNotifId(topicId, (lastUserNickname ?: commentNickname) + commentContent)
                                                        val who = (lastUserNickname ?: commentNickname).ifBlank { "새 댓글" }
                                                        if (pendingAllowNotify) {
                                                            val createdForKey = lastCreated?.ifBlank { "t:$topicId" } ?: "t:$topicId"
                                                            val key = notifKey("COMMENT", who, "댓글을 남겼습니다", topicId, createdForKey)
                                                            if (rememberNotificationKey(key)) {
                                                                val isReply = findCommentKind(topicId)?.equals("REPLY", true) == true
                                                                val notifTitle = if (isReply) "${who}님의 답글" else "${who}님의 댓글"
                                                                postSimpleNotification(
                                                                    key = key,  // 고유 키 사용
                                                                    title = notifTitle,
                                                                    text = commentContent,
                                                                    whenMillis = System.currentTimeMillis(),
                                                                    openUrl = deep,
                                                                    topicId = topicId,
                                                                    actorHexId = it.optString("userId", "")
                                                                )
                                                                // topicId도 seen에 기록
                                                                if (!DRY_RUN_NOTIF_ONLY && topicId != null && topicId.isNotBlank()) {
                                                                    markTopicSeen(topicId)
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                            // PROFILE_RESULT 메시지 처리 (백그라운드 fetch 결과)
                                            if (msg.contains("\"type\":\"PROFILE_RESULT\"")) {
                                                try {
                                                    val jo = JSONObject(msg)
                                                    val type = jo.optString("type")
                                                    if (type == "PROFILE_RESULT") {
                                                        val userId = jo.optString("userId")
                                                        val profileUrl = jo.optString("url")
                                                        val error = jo.optString("error")
                                                        
                                                        AndroidLog.d("PROFILE_RESULT", "Received: userId=$userId, url=$profileUrl, error=$error")
                                                        
                                                        if (!error.isNullOrBlank()) {
                                                            AndroidLog.w("PROFILE_RESULT", "Profile fetch error for $userId: $error")
                                                            return@runOnUiThread
                                                        }
                                                        
                                                        if (profileUrl.isNullOrBlank()) {
                                                            AndroidLog.d("PROFILE_RESULT", "No profile URL found for $userId")
                                                            return@runOnUiThread
                                                        }
                                                        
                                                        // userId로 username 찾기 (딥링크에서 추출)
                                                        val username = followDeepLinks.keys.find { user ->
                                                            extractUserIdFromUrl(followDeepLinks[user]) == userId
                                                        } ?: return@runOnUiThread
                                                        
                                                        AndroidLog.d("PROFILE_RESULT", "Processing profile for username: $username")
                                                        handleProfileResult(msg, username)
                                                    }
                                                } catch (e: Exception) {
                                                    AndroidLog.e("PROFILE_RESULT", "Error processing profile result", e)
                                                }
                                            }
                                        }
                                    }

                                    // ✅ 토큰 보유 여부에 따라 초기 진입 페이지 결정
                                    val initialUrl = if (hasStoredTokens()) {
                                        "https://playentry.org/"
                                    } else {
                                        "https://playentry.org/signin"
                                    }
                                    AndroidLog.d("MAIN_WEBVIEW", "Loading initial URL: $initialUrl (logged=${preferenceManager.isLoggedIn})")
                                    loadUrl(initialUrl)
                                }

                                mainWebView = webView
                                // 알림에서 넘어온 URL 처리
                                pendingOpenUrl?.let { urlToOpen ->
                                    handleNotificationUrl(urlToOpen)
                                    pendingOpenUrl = null
                                }
                                webView
                            }
                        )
                        }

                        // ✅ 로그인 후 덮개 (터치 차단)
                        if (overlayVisible) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    .zIndex(2f)
                            )
                        }

                        // ✅ 로그인 완료 시 네비게이션 UI를 WebView 위에 덮음
                        if (isLoggedIn) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(3f)
                            ) {
                                com.example.entify_again.ui.navigation.EntifyMainScreen(
                                    context = this@MainActivity,
                                    onLogout = {
                                        performSecureLogout("user_request") {
                                            isLoggedIn = false
                                        }
                                    },
                                    onNavigateToLogin = {
                                        // 사용자가 수동으로 로그인 화면으로 이동 요청
                                        isLoggedIn = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 로그인 상태 감지 (SharedPrefs 폴링)
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(1000)
                        val loggedIn = preferenceManager.isLoggedIn
                        if (loggedIn && !isLoggedIn) {
                            isLoggedIn = true
                        } else if (!loggedIn && isLoggedIn && !logoutInProgress) {
                            isLoggedIn = false
                        }
                    }
                }
            }
        }
    }

    // ✅ 헤더/푸터 숨기기 (DOM 조작 최소화 - 읽기 전용)
    private val hideUIJS = """
        (() => {
          try {
            // ✅ DOM 조작 없이 CSS만 조작 (화면 초기화 방지)
            const style = document.createElement('style');
            style.textContent = `
              header[role="banner"] { display: none !important; }
              footer { display: none !important; }
            `;
            document.head.appendChild(style);
          } catch(e){
            console.log('[HIDE_UI] Safe error:', e.message);
          }
        })();
    """.trimIndent()

    // ✅ 토큰 추출 JS (읽기 전용 - DOM 조작 없음)
    private val jsTokenExtractor = """
        (() => {
          "use strict";
          function normalizeUser(node){
            if (!node || typeof node !== "object") return node;
            try {
              const init = node?.props?.initialState?.common?.user;
              const page = node?.props?.pageProps?.initialState?.common?.user;
              if (init && !page) {
                node.props.pageProps = node.props.pageProps || {};
                const ppInit = node.props.pageProps.initialState = node.props.pageProps.initialState || {};
                const ppCommon = ppInit.common = ppInit.common || {};
                ppCommon.user = init;
              } else if (!init && page) {
                node.props.initialState = node.props.initialState || {};
                const baseCommon = node.props.initialState.common = node.props.initialState.common || {};
                baseCommon.user = page;
              }
            } catch (e) {
              console.log('[TOKEN_PATCH] error:', e?.message);
            }
            return node;
          }
          try {
            const el = document.getElementById("__NEXT_DATA__");
            if (!el || !el.innerText) {
              AndroidApp.postMessage("LOGIN_ERR: NO_NEXT_DATA");
              return;
            }
            let parsed = {};
            try {
              parsed = normalizeUser(JSON.parse(el.innerText.trim() || "{}"));
              el.innerText = JSON.stringify(parsed);
            } catch (err) {
              AndroidApp.postMessage("LOGIN_ERR: PARSE_FAIL");
              return;
            }
            if ("__NEXT_DATA__" in window && window.__NEXT_DATA__) {
              normalizeUser(window.__NEXT_DATA__);
            }
            const userNode =
              parsed?.props?.initialState?.common?.user ||
              parsed?.props?.pageProps?.initialState?.common?.user;
            const xtoken =
              userNode?.xToken ||
              sessionStorage.getItem("entry_xToken") ||
              localStorage.getItem("entry_xToken") ||
              localStorage.getItem("playentry_token");
            const csrf =
              parsed?.props?.initialProps?.csrfToken ||
              sessionStorage.getItem("entry_csrfToken") ||
              localStorage.getItem("entry_csrfToken") ||
              document.querySelector('meta[name="csrf-token"]')?.content;
            if (!xtoken || !csrf) {
              AndroidApp.postMessage("LOGIN_ERR: NO_TOKEN");
              return;
            }
            try {
              const cleanX = (xtoken || '').replace(/^["']+|["']+$/g, '');
              const cleanC = (csrf || '').replace(/^["']+|["']+$/g, '');
              sessionStorage.setItem("entry_xToken", cleanX);
              localStorage.setItem("entry_xToken", cleanX);
              sessionStorage.setItem("entry_csrfToken", cleanC);
              localStorage.setItem("entry_csrfToken", cleanC);
              if (cleanX) document.cookie = `playentry_token=${'$'}{cleanX}; path=/; domain=.playentry.org`;
            } catch (storeErr) {
              console.log("[TOKEN] storage error:", storeErr?.message);
            }
            try {
              const nick = userNode?.nickname || '';
              const uid = userNode?.id || userNode?._id || '';
              AndroidApp.postMessage('SAVE_TOKENS:' + JSON.stringify({ xToken: xtoken, csrfToken: csrf, nickname: nick, userId: uid }));
            } catch (postErr) {
              console.log("[TOKEN] SAVE_TOKENS post error:", postErr?.message);
            }
            AndroidApp.postMessage('LOGIN_OK');
          } catch (e) {
            console.log('[TOKEN] General error:', e?.message);
            AndroidApp.postMessage("LOGIN_ERR: EXTRACT_FAIL");
          }
        })();
    """.trimIndent()

    // ✅ 토큰 상태 5초 폴링 (로그아웃 감지용)
    private val jsTokenPoller = """
        (() => {
          const VERSION = '2026-02-08-a';
          if (window.__ENTRY_TOKEN_POLLER__?.stop) {
            try { window.__ENTRY_TOKEN_POLLER__.stop(); } catch (_) {}
          }
          if (window.__ENTRY_TOKEN_POLLER__?.version === VERSION) return;

          let timer = null;
          let seenTokens = false;
          const intervalMs = 5000;

          function normalizeUser(node){
            if (!node || typeof node !== 'object') return node;
            try {
              const init = node?.props?.initialState?.common?.user;
              const page = node?.props?.pageProps?.initialState?.common?.user;
              if (init && !page) {
                node.props.pageProps = node.props.pageProps || {};
                const ppInit = node.props.pageProps.initialState = node.props.pageProps.initialState || {};
                const ppCommon = ppInit.common = ppInit.common || {};
                ppCommon.user = init;
              } else if (!init && page) {
                node.props.initialState = node.props.initialState || {};
                const baseCommon = node.props.initialState.common = node.props.initialState.common || {};
                baseCommon.user = page;
              }
            } catch (_) {}
            return node;
          }

          function extractOnce(){
            try {
              const el = document.getElementById('__NEXT_DATA__');
              if (!el || !el.innerText) {
                if (seenTokens) {
                  try { AndroidApp.postMessage('LOGIN_ERR: NO_TOKEN'); } catch (_) {}
                }
                return;
              }
              let parsed = {};
              try {
                parsed = normalizeUser(JSON.parse(el.innerText.trim() || '{}'));
                el.innerText = JSON.stringify(parsed);
              } catch (_) {
                if (seenTokens) {
                  try { AndroidApp.postMessage('LOGIN_ERR: PARSE_FAIL'); } catch (_) {}
                }
                return;
              }
              if ('__NEXT_DATA__' in window && window.__NEXT_DATA__) {
                normalizeUser(window.__NEXT_DATA__);
              }

              const userNode =
                parsed?.props?.initialState?.common?.user ||
                parsed?.props?.pageProps?.initialState?.common?.user;
              const xtoken = userNode?.xToken || '';
              const csrf =
                parsed?.props?.initialProps?.csrfToken ||
                parsed?.props?.pageProps?.initialProps?.csrfToken ||
                document.querySelector('meta[name="csrf-token"]')?.content || '';

              if (xtoken && csrf) {
                seenTokens = true;
                try {
                  sessionStorage.setItem('entry_xToken', xtoken);
                  localStorage.setItem('entry_xToken', xtoken);
                  sessionStorage.setItem('entry_csrfToken', csrf);
                  localStorage.setItem('entry_csrfToken', csrf);
                } catch (_) {}
                try {
                  const nick = userNode?.nickname || '';
                  const uid = userNode?.id || userNode?._id || '';
                  AndroidApp.postMessage('SAVE_TOKENS:' + JSON.stringify({ xToken: xtoken, csrfToken: csrf, nickname: nick, userId: uid }));
                } catch (_) {}
                return;
              }

              if (seenTokens) {
                try { AndroidApp.postMessage('LOGIN_ERR: NO_TOKEN'); } catch (_) {}
              }
            } catch (_) {
              if (seenTokens) {
                try { AndroidApp.postMessage('LOGIN_ERR: EXTRACT_FAIL'); } catch (_) {}
              }
            }
          }

          function start(){
            if (timer) return;
            extractOnce();
            timer = setInterval(extractOnce, intervalMs);
          }
          function stop(){ if (timer) { clearInterval(timer); timer = null; } }

          window.__ENTRY_TOKEN_POLLER__ = { version: VERSION, start, stop };
          start();
        })();
    """.trimIndent()

    private val jsTokenBootstrapper = """
        (() => {
          "use strict";
          function patchUser(data){
            if (!data || typeof data !== "object") return data;
            try {
              const initUser = data?.props?.initialState?.common?.user;
              const pageUser = data?.props?.pageProps?.initialState?.common?.user;
              if (initUser && !pageUser) {
                const pp = data.props.pageProps = data.props.pageProps || {};
                const ppInit = pp.initialState = pp.initialState || {};
                const ppCommon = ppInit.common = ppInit.common || {};
                ppCommon.user = initUser;
              } else if (!initUser && pageUser) {
                const base = data.props.initialState = data.props.initialState || {};
                const baseCommon = base.common = base.common || {};
                baseCommon.user = pageUser;
              }
            } catch (err) {
              console.log("[TOKEN_BOOTSTRAP] patch error:", err?.message);
            }
            return data;
          }
          function respond(ok, reason){
            const msg = ok ? "TOKEN_BOOTSTRAP_OK" : ("TOKEN_BOOTSTRAP_FAIL:" + (reason || "unknown"));
            try { AndroidApp.postMessage(msg); } catch (err) {
              console.log("[TOKEN_BOOTSTRAP] bridge error:", err?.message);
            }
          }
          try {
            const el = document.getElementById("__NEXT_DATA__");
            if (!el || !el.innerText) {
              respond(false, "NO_NEXT_DATA");
              return;
            }
            let parsed = {};
            try {
              parsed = patchUser(JSON.parse(el.innerText.trim() || "{}"));
              el.innerText = JSON.stringify(parsed);
            } catch (err) {
              respond(false, "PARSE_FAIL");
              return;
            }
            if ("__NEXT_DATA__" in self && self.__NEXT_DATA__) {
              patchUser(self.__NEXT_DATA__);
            }
            const userNode =
              parsed?.props?.initialState?.common?.user ||
              parsed?.props?.pageProps?.initialState?.common?.user;
            const xtoken =
              userNode?.xToken ||
              localStorage.getItem("entry_xToken") ||
              sessionStorage.getItem("entry_xToken") ||
              localStorage.getItem("playentry_token");
            const csrf =
              parsed?.props?.initialProps?.csrfToken ||
              parsed?.props?.pageProps?.initialProps?.csrfToken ||
              document.querySelector('meta[name="csrf-token"]')?.content ||
              sessionStorage.getItem("entry_csrfToken") ||
              localStorage.getItem("entry_csrfToken");
            if (!xtoken || !csrf) {
              respond(false, "NO_TOKEN");
              return;
            }
            try {
              const cleanX = (xtoken || '').replace(/^["']+|["']+$/g, '');
              const cleanC = (csrf || '').replace(/^["']+|["']+$/g, '');
              sessionStorage.setItem("entry_xToken", cleanX);
              localStorage.setItem("entry_xToken", cleanX);
              sessionStorage.setItem("entry_csrfToken", cleanC);
              localStorage.setItem("entry_csrfToken", cleanC);
              if (cleanX) document.cookie = `playentry_token=${'$'}{cleanX}; path=/; domain=.playentry.org`;
            } catch (storeErr) {
              console.log("[TOKEN_BOOTSTRAP] storage error:", storeErr?.message);
            }
            try {
              const nick = userNode?.nickname || '';
              const uid = userNode?.id || userNode?._id || '';
              AndroidApp.postMessage('SAVE_TOKENS:' + JSON.stringify({ xToken: xtoken, csrfToken: csrf, nickname: nick, userId: uid }));
            } catch (postErr) {
              console.log("[TOKEN_BOOTSTRAP] SAVE_TOKENS error:", postErr?.message);
            }
            respond(true, "OK");
          } catch (err) {
            respond(false, err && err.message ? err.message : "ERROR");
          }
        })();
    """.trimIndent()

    private val jsTokenSnapshot = """
        (() => {
          "use strict";
          function patchUser(data){
            if (!data || typeof data !== "object") return data;
            try {
              const initUser = data?.props?.initialState?.common?.user;
              const pageUser = data?.props?.pageProps?.initialState?.common?.user;
              if (initUser && !pageUser) {
                const pp = data.props.pageProps = data.props.pageProps || {};
                const ppInit = pp.initialState = pp.initialState || {};
                const ppCommon = ppInit.common = ppInit.common || {};
                ppCommon.user = initUser;
              } else if (!initUser && pageUser) {
                const base = data.props.initialState = data.props.initialState || {};
                const baseCommon = base.common = base.common || {};
                baseCommon.user = pageUser;
              }
            } catch (err) {
              console.log("[TOKEN_SNAPSHOT] patch error:", err?.message);
            }
            return data;
          }
          try {
            const el = document.getElementById("__NEXT_DATA__");
            if (!el || !el.innerText) {
              console.log("[TOKEN_SNAPSHOT] __NEXT_DATA__ missing");
              return;
            }
            let parsed = {};
            try {
              parsed = patchUser(JSON.parse(el.innerText.trim() || "{}"));
              el.innerText = JSON.stringify(parsed);
            } catch (err) {
              console.log("[TOKEN_SNAPSHOT] parse error:", err?.message);
              return;
            }
            if ("__NEXT_DATA__" in window && window.__NEXT_DATA__) {
              patchUser(window.__NEXT_DATA__);
            }
            const userNode =
              parsed?.props?.initialState?.common?.user ||
              parsed?.props?.pageProps?.initialState?.common?.user;
            const xtoken = userNode?.xToken || "";
            const csrf = parsed?.props?.initialProps?.csrfToken || "";
            if (!xtoken || !csrf) {
              console.log("[TOKEN_SNAPSHOT] tokens missing");
              return;
            }
            try {
              const cleanX = (xtoken || '').replace(/^["']+|["']+$/g, '');
              const cleanC = (csrf || '').replace(/^["']+|["']+$/g, '');
              sessionStorage.setItem("entry_xToken", cleanX);
              sessionStorage.setItem("entry_csrfToken", cleanC);
              localStorage.setItem("entry_xToken", cleanX);
              localStorage.setItem("entry_csrfToken", cleanC);
              if (cleanX) document.cookie = `playentry_token=${'$'}{cleanX}; path=/; domain=.playentry.org`;
              console.log("[TOKEN_SNAPSHOT] tokens stored");
            } catch (err) {
              console.log("[TOKEN_SNAPSHOT] storage error:", err?.message);
            }
          } catch (err) {
            console.log("[TOKEN_SNAPSHOT] general error:", err?.message);
          }
        })();
    """.trimIndent()

    private val jsGraphqlTokenExtractor = """
        (() => {
          "use strict";
          function patchUser(node){
            if (!node || typeof node !== "object") return node;
            try {
              const initUser = node?.props?.initialState?.common?.user;
              const pageUser = node?.props?.pageProps?.initialState?.common?.user;
              if (initUser && !pageUser) {
                node.props.pageProps = node.props.pageProps || {};
                const ppInit = node.props.pageProps.initialState = node.props.pageProps.initialState || {};
                const ppCommon = ppInit.common = ppInit.common || {};
                ppCommon.user = initUser;
              } else if (!initUser && pageUser) {
                node.props.initialState = node.props.initialState || {};
                const baseCommon = node.props.initialState.common = node.props.initialState.common || {};
                baseCommon.user = pageUser;
              }
            } catch (err) {
              console.log("[GRAPHQL_TOKEN] patch error:", err?.message);
            }
            return node;
          }
          function respond(ok, payload){
            const tag = ok ? "GRAPHQL_TOKENS:" : "GRAPHQL_TOKENS_ERROR:";
            try { AndroidApp.postMessage(tag + payload); } catch (err) {
              console.log("[GRAPHQL_TOKEN] bridge error:", err?.message);
            }
          }
          try {
            const el = document.getElementById("__NEXT_DATA__");
            if (!el || !el.innerText) {
              respond(false, "NO_NEXT_DATA");
              return;
            }
            let parsed = {};
            try {
              parsed = patchUser(JSON.parse(el.innerText.trim() || "{}"));
              el.innerText = JSON.stringify(parsed);
            } catch (err) {
              respond(false, "PARSE_FAIL");
              return;
            }
            if ("__NEXT_DATA__" in window && window.__NEXT_DATA__) {
              patchUser(window.__NEXT_DATA__);
            }
            const userNode =
              parsed?.props?.initialState?.common?.user ||
              parsed?.props?.pageProps?.initialState?.common?.user;
            const xtoken =
              userNode?.xToken ||
              sessionStorage.getItem("entry_xToken") ||
              localStorage.getItem("entry_xToken") ||
              localStorage.getItem("playentry_token");
            const csrf =
              parsed?.props?.initialProps?.csrfToken ||
              parsed?.props?.pageProps?.initialProps?.csrfToken ||
              sessionStorage.getItem("entry_csrfToken") ||
              localStorage.getItem("entry_csrfToken");
            if (!xtoken || !csrf) {
              respond(false, "NO_TOKEN");
              return;
            }
            try {
              const cleanX = (xtoken || '').replace(/^["']+|["']+$/g, '');
              const cleanC = (csrf || '').replace(/^["']+|["']+$/g, '');
              sessionStorage.setItem("entry_xToken", cleanX);
              localStorage.setItem("entry_xToken", cleanX);
              sessionStorage.setItem("entry_csrfToken", cleanC);
              localStorage.setItem("entry_csrfToken", cleanC);
              if (cleanX) document.cookie = `playentry_token=${'$'}{cleanX}; path=/; domain=.playentry.org`;
            } catch (err) {
              console.log("[GRAPHQL_TOKEN] storage error:", err?.message);
            }
            respond(true, JSON.stringify({ xToken: xtoken, csrfToken: csrf }));
          } catch (err) {
            respond(false, err && err.message ? err.message : "ERROR");
          }
        })();
    """.trimIndent()

    private val jsUserTokenSync = """
        (() => {
          "use strict";
          function patchUser(node){
            if (!node || typeof node !== "object") return node;
            try {
              const initUser = node?.props?.initialState?.common?.user;
              const pageUser = node?.props?.pageProps?.initialState?.common?.user;
              if (initUser && !pageUser) {
                node.props.pageProps = node.props.pageProps || {};
                const ppInit = node.props.pageProps.initialState = node.props.pageProps.initialState || {};
                const ppCommon = ppInit.common = ppInit.common || {};
                ppCommon.user = initUser;
              } else if (!initUser && pageUser) {
                node.props.initialState = node.props.initialState || {};
                const baseCommon = node.props.initialState.common = node.props.initialState.common || {};
                baseCommon.user = pageUser;
              }
            } catch (err) {
              console.log("[USER_TOKEN_SYNC] patch error:", err?.message);
            }
            return node;
          }
          try {
            const el = document.getElementById("__NEXT_DATA__");
            if (!el || !el.innerText) {
              console.log("[USER_TOKEN_SYNC] __NEXT_DATA__ missing");
              return;
            }
            let parsed = {};
            try {
              parsed = patchUser(JSON.parse(el.innerText.trim() || "{}"));
              el.innerText = JSON.stringify(parsed);
            } catch (err) {
              console.log("[USER_TOKEN_SYNC] parse error:", err?.message);
              return;
            }
            if ("__NEXT_DATA__" in window && window.__NEXT_DATA__) {
              patchUser(window.__NEXT_DATA__);
            }
            const userNode =
              parsed?.props?.initialState?.common?.user ||
              parsed?.props?.pageProps?.initialState?.common?.user;
            const xtoken =
              userNode?.xToken ||
              sessionStorage.getItem("entry_xToken") ||
              localStorage.getItem("entry_xToken") ||
              localStorage.getItem("playentry_token");
            const csrf =
              parsed?.props?.initialProps?.csrfToken ||
              parsed?.props?.pageProps?.initialProps?.csrfToken ||
              sessionStorage.getItem("entry_csrfToken") ||
              localStorage.getItem("entry_csrfToken");
            if (!xtoken || !csrf) {
              console.log("[USER_TOKEN_SYNC] tokens missing");
              return;
            }
            const cleanX = (xtoken || '').replace(/^["']+|["']+$/g, '');
            const cleanC = (csrf || '').replace(/^["']+|["']+$/g, '');
            sessionStorage.setItem("entry_xToken", cleanX);
            localStorage.setItem("entry_xToken", cleanX);
            sessionStorage.setItem("entry_csrfToken", cleanC);
            localStorage.setItem("entry_csrfToken", cleanC);
            if (cleanX) document.cookie = `playentry_token=${'$'}{cleanX}; path=/; domain=.playentry.org`;
            console.log("[USER_TOKEN_SYNC] tokens stored");
          } catch (err) {
            console.log("[USER_TOKEN_SYNC] general error:", err?.message);
          }
        })();
    """.trimIndent()

    private val jsTokenRepairScript = """
        (() => {
          "use strict";
          function deepPatch(data){
            if (!data || typeof data !== "object") return data;
            try {
              const initUser = data?.props?.initialState?.common?.user;
              const pageUser = data?.props?.pageProps?.initialState?.common?.user;
              if (initUser && !pageUser) {
                const pp = data.props.pageProps = data.props.pageProps || {};
                const ppInit = pp.initialState = pp.initialState || {};
                const ppCommon = ppInit.common = ppInit.common || {};
                ppCommon.user = initUser;
              } else if (!initUser && pageUser) {
                const base = data.props.initialState = data.props.initialState || {};
                const baseCommon = base.common = base.common || {};
                baseCommon.user = pageUser;
              }
            } catch (e) {
              console.log("[TOKEN_REPAIR] patch error:", e?.message);
            }
            return data;
          }
          try {
            const el = document.getElementById("__NEXT_DATA__");
            if (!el || !el.innerText) {
              AndroidApp.postMessage("TOKEN_REFRESH_FAIL:NO_NEXT_DATA");
              return;
            }
            let parsed = {};
            try {
              parsed = deepPatch(JSON.parse(el.innerText || "{}"));
              el.innerText = JSON.stringify(parsed);
            } catch (e) {
              AndroidApp.postMessage("TOKEN_REFRESH_FAIL:PARSE");
              return;
            }
            if (window.__NEXT_DATA__) deepPatch(window.__NEXT_DATA__);
            const userNode = parsed?.props?.initialState?.common?.user
              || parsed?.props?.pageProps?.initialState?.common?.user;
            const xtoken = userNode?.xToken
              || sessionStorage.getItem("entry_xToken")
              || localStorage.getItem("entry_xToken")
              || localStorage.getItem("playentry_token");
            const csrf = parsed?.props?.initialProps?.csrfToken
              || (document.querySelector('meta[name="csrf-token"]') || {}).content
              || sessionStorage.getItem("entry_csrfToken")
              || localStorage.getItem("entry_csrfToken");
            if (!xtoken || !csrf) {
              AndroidApp.postMessage("TOKEN_REFRESH_FAIL:NO_TOKEN");
              return;
            }
            try {
              const cleanX = (xtoken || '').replace(/^["']+|["']+$/g, '');
              const cleanC = (csrf || '').replace(/^["']+|["']+$/g, '');
              sessionStorage.setItem("entry_xToken", cleanX);
              localStorage.setItem("entry_xToken", cleanX);
              sessionStorage.setItem("entry_csrfToken", cleanC);
              localStorage.setItem("entry_csrfToken", cleanC);
              if (cleanX) document.cookie = `playentry_token=${'$'}{cleanX}; path=/; domain=.playentry.org`;
            } catch (e) {
              console.log("[TOKEN_REPAIR] storage error:", e?.message);
            }
            AndroidApp.postMessage('SAVE_TOKENS:' + JSON.stringify({ xToken: xtoken, csrfToken: csrf }));
            AndroidApp.postMessage("TOKEN_REFRESH_OK");
          } catch (e) {
            AndroidApp.postMessage("TOKEN_REFRESH_FAIL:" + (e && e.message ? e.message : "error"));
          }
        })();
    """.trimIndent()

    private suspend fun refreshTokensFromWebView(reason: String = ""): Boolean = withContext(Dispatchers.Main) {
        val webView = mainWebView ?: run {
            AndroidLog.w("TOKEN_REFRESH", "No main WebView for token refresh ($reason)")
            return@withContext false
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingTokenRefresh?.cancel()
        pendingTokenRefresh = deferred
        try {
            webView.evaluateJavascript(jsTokenRepairScript, null)
        } catch (e: Exception) {
            AndroidLog.e("TOKEN_REFRESH", "evaluateJavascript failed: ${e.message}")
            pendingTokenRefresh = null
            return@withContext false
        }
        val result = withTimeoutOrNull(4000) {
            deferred.await()
        } ?: false
        if (!result) {
            AndroidLog.w("TOKEN_REFRESH", "Token refresh timed out ($reason)")
        } else {
            AndroidLog.d("TOKEN_REFRESH", "Token refresh success ($reason)")
        }
        result
    }

    private suspend fun waitForMainPageReady(timeoutMs: Long = 5000L): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val ready = withContext(Dispatchers.Main) {
                    val url = mainWebView?.url.orEmpty()
                    EntryOriginPolicy.isTrustedMainHost(url) &&
                        !EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/signin")
                }
                if (ready) return@withTimeoutOrNull true
                delay(200)
            }
            false
        } ?: false

    private suspend fun waitForMainWebViewInstance(timeoutMs: Long = 15000L): WebView? =
        withTimeoutOrNull(timeoutMs) {
            while (mainWebView == null) {
                delay(100)
            }
            mainWebView
        }

    private suspend fun waitForStartupReady(reason: String): Boolean {
        AndroidLog.d("STARTUP_FLOW", "Waiting for startup readiness (reason=$reason)")
        val hasTokens = hasStoredTokens()
        if (hasTokens) {
            ensureStoredTokensMarkedReady()
        }
        val webView = waitForMainWebViewInstance() ?: run {
            AndroidLog.w("STARTUP_FLOW", "Main WebView unavailable (reason=$reason)")
            return false
        }
        withContext(Dispatchers.Main) {
            val targetUrl = if (hasTokens) "https://playentry.org/" else "https://playentry.org/signin"
            val current = webView.url.orEmpty()
            if (!current.startsWith(targetUrl)) {
                AndroidLog.d("STARTUP_FLOW", "Loading startup URL: $targetUrl")
                runCatching { webView.stopLoading() }
                webView.loadUrl(targetUrl)
            }
        }
        return if (hasTokens) {
            val mainReady = waitForMainPageReady(15000)
            if (!mainReady) {
                AndroidLog.w("STARTUP_FLOW", "Main page not ready for auto-login (reason=$reason)")
                false
            } else {
                runTokenExtractorIfReady("startup_autologin")
                waitForSessionTokens()
            }
        } else {
            // 자동로그인(쿠키)로 메인페이지에 있을 수 있음 → 토큰 즉시 추출 시도
            val alreadyOnMain = waitForMainPageReady(5000)
            if (alreadyOnMain) {
                AndroidLog.d("STARTUP_FLOW", "Auto-login main page detected - trying token extract")
                runTokenExtractorIfReady("startup_auto_login")
                val tokensReady = waitForSessionTokens(10000)
                if (tokensReady) {
                    return true
                }
                AndroidLog.w("STARTUP_FLOW", "Auto-login token extract failed - redirecting to login")
                redirectToLoginIfNeeded("auto_login_no_tokens")
                return false
            }

            AndroidLog.d("STARTUP_FLOW", "No stored tokens - waiting for manual login")
            val tokensReady = waitForSessionTokens(300000)
            if (!tokensReady) {
                AndroidLog.w("STARTUP_FLOW", "Manual login not detected within timeout (reason=$reason)")
                false
            } else {
                waitForMainPageReady(15000)
            }
        }
    }

    private fun scheduleStartupFlow(reason: String) {
        if (startupFlowJob?.isActive == true) {
            AndroidLog.d("STARTUP_FLOW", "Startup flow already running (reason=$reason)")
            return
        }
        startupFlowJob = lifecycleScope.launch {
            val ready = waitForStartupReady(reason)
            if (ready) {
                AndroidLog.d("STARTUP_FLOW", "Startup readiness confirmed - starting notification logic")
                startNotificationLogicIfLoggedIn(force = true)
            } else {
                AndroidLog.w("STARTUP_FLOW", "Startup readiness failed (reason=$reason)")
            }
        }
    }

    // ✅ 댓글 탐색기 (닉네임/시간 기반, GraphQL 페이지네이션) - 제공 스펙으로 교체 + 업로드 경로 스마트 복원
    private val jsFindComment = """
      (function(){
        function parseEntryTime(str){
          if(!str) return null;
          const m = String(str).match(/[I]?(\d{2})\.(\d{2})\.(\d{2})\s*[・\-\.]?\s*(\d{2}):(\d{2})/);
          if(!m) return null;
          const yy = parseInt(m[1],10), MM = parseInt(m[2],10), dd = parseInt(m[3],10), hh = parseInt(m[4],10), mm2 = parseInt(m[5],10);
          return new Date(2000 + yy, MM-1, dd, hh, mm2, 0);
        }

        function within(a,b,ms){
          try { return Math.abs(new Date(a) - new Date(b)) <= ms; } catch { return false; }
        }

        function getTokens(){
          let x = '', c = '';
          try {
            x = localStorage.getItem('playentry_token') || sessionStorage.getItem('entry_xToken') || '';
          } catch (_) {
            try { x = sessionStorage.getItem('entry_xToken') || ''; } catch (_) {}
          }
          try {
            c = (document.querySelector('meta[name=\"csrf-token\"]')||{}).content || sessionStorage.getItem('entry_csrfToken') || '';
          } catch (_) {}
          const h = { 'content-type':'application/json', 'x-client-type':'Client' };
          if (x) h['x-token']=x; if (c) h['csrf-token']=c; return h;
        }

        function isAbsolute(url){ return /^https?:\\/\\//i.test(String(url||'')); }

        function fixUploadPath(raw, fallbackExt){
          if(!raw) return '';
          var s = String(raw).trim();
          if (isAbsolute(s)) return s;
          if (s.indexOf('/uploads/')>=0 && /\\.[a-zA-Z0-9]+$/.test(s)) return location.origin + s;
          if (s.startsWith('/uploads/')) return location.origin + s;
          var m = s.match(/([a-z0-9]{8,})/i);
          if (m){
            var id = m[1];
            var p1 = id.slice(0,2); var p2 = id.slice(2,4);
            var ext = /\\.[a-zA-Z0-9]+$/.test(id) ? '' : ('.' + (fallbackExt || 'jpeg'));
            return location.origin + '/uploads/' + p1 + '/' + p2 + '/' + id + ext;
          }
          return location.origin + '/uploads/' + s;
        }

        async function fixUploadPathSmart(raw){
          if(!raw) return '';
          var s = String(raw).trim();
          if (isAbsolute(s)) {
            // 절대경로인데 확장자가 없으면 ID로 추정해 확장자 탐색
            if (!/\.[a-zA-Z0-9]+$/.test(s)) {
              var mAbs = s.match(/([a-z0-9]{8,})/i);
              if (mAbs) {
                var idA = mAbs[1];
                var p1A = idA.slice(0,2); var p2A = idA.slice(2,4);
                var tryExtsA = ['jpeg','jpg','png','svg','webp'];
                for (var i=0;i<tryExtsA.length;i++){
                  var extA = tryExtsA[i];
                  var urlA = location.origin + '/uploads/' + p1A + '/' + p2A + '/' + idA + '.' + extA;
                  try { var resA = await fetch(urlA, { method:'HEAD', credentials:'include' }); if (resA && resA.ok) return urlA; } catch(e){}
                }
                return location.origin + '/uploads/' + p1A + '/' + p2A + '/' + idA + '.jpeg';
              }
            }
            // 확장자가 이미 있으면 그대로 사용
            return s;
          }
          if (s.startsWith('/uploads/') && /\.[a-zA-Z0-9]+$/.test(s)) return location.origin + s;
          var m = s.match(/([a-z0-9]{8,})/i);
          if (!m) return fixUploadPath(raw, 'jpeg');
          var id = m[1]; var p1 = id.slice(0,2); var p2 = id.slice(2,4);
          var tryExts = ['jpeg','jpg','png','svg','webp'];
          for (var i=0;i<tryExts.length;i++){
            var ext = tryExts[i];
            var url = location.origin + '/uploads/' + p1 + '/' + p2 + '/' + id + '.' + ext;
            try { var res = await fetch(url, { method:'HEAD', credentials:'include' }); if (res && res.ok) return url; } catch(e){}
          }
          return location.origin + '/uploads/' + p1 + '/' + p2 + '/' + id + '.jpeg';
        }

        function getPageInfoFromURL(){
          var u = String(location.href || '');
          var g = u.match(/\/group\/community\/([0-9a-f]{24})\/([0-9a-f]{24})/i);
          if (g) return { id:g[1], groupId:g[2], subject:'discuss', type:'group_community' };
          var p = u.match(/\/project\/([0-9a-f]{24})/i);
          if (p) return { id:p[1], subject:'project', type:'project' };
          var c = u.match(/\/community\/(qna|tips|entrystory|notice|entrylife|free|discuss)\/([0-9a-f]{24})/i);
          if (c) return { id:c[2], subject:'discuss', type:c[1].toLowerCase() };
          return { id:null, subject:'unknown', type:'unknown' };
        }

        function getTargetIdFromURL(){
          var info = getPageInfoFromURL();
          if (info && info.id) return info.id;
          var m = String(location.href || '').match(/([0-9a-f]{24})(?![0-9a-f])/i);
          return m ? m[1] : null;
        }

        async function fetchPage(target, searchAfter, display){
          if (display===undefined) display=20;
          const q = `query SELECT_COMMENTS(${'$'}pageParam: PageParam, ${'$'}target: String, ${'$'}searchAfter: JSON){ commentList(pageParam:${'$'}pageParam, target:${'$'}target, searchAfter:${'$'}searchAfter){ list{ id content created user{ nickname profileImage{ filename } } image{ filename } sticker{ filename } } searchAfter total } }`;
          const body = { query:q, variables:{ pageParam:{ display, sort:'created', order:-1 }, target, searchAfter: (searchAfter===undefined?null:searchAfter) } };
          try{
            const res = await fetch('https://playentry.org/graphql/SELECT_COMMENTS', { method:'POST', headers:getTokens(), credentials:'include', body: JSON.stringify(body) });
            const j = await res.json().catch(function(){ return {}; });
            const box = j && j.data && j.data.commentList ? j.data.commentList : null;
            return { list: (box && Array.isArray(box.list)) ? box.list : [], next: box ? (box.searchAfter||null) : null };
          }catch(e){ return { list:[], next:null }; }
        }

        function extractFromDOM(nickname, rawTime){
          try{
            const divs = document.querySelectorAll('div');
            for (const div of divs){
              const txt = div.textContent || '';
              if (!txt.includes(nickname)) continue;
              const timeEl = Array.from(div.querySelectorAll('em')).find(function(e){ return (e.textContent||'').match(/\\d{2}:\\d{2}/); });
              const timeText = timeEl ? timeEl.textContent.trim() : '';
              if (rawTime){
                const hhmm = (String(rawTime).match(/(\\d{2}:\\d{2})/)||[])[1];
                if (hhmm && !timeText.includes(hhmm)) continue;
              }
              let profile = '';
              const anchor = div.querySelector('a[style*="background-image"]');
              const style = anchor && anchor.getAttribute ? (anchor.getAttribute('style')||'') : '';
              if (style){
                const urls = Array.from(style.matchAll(/url\\(([\"']?)(.*?)\\1\\)/g)).map(function(m){ return m[2]; });
                const pick = urls.find(function(u){ return u.indexOf('/uploads/')>=0 && u.indexOf('EmptyImage')<0; }) || '';
                if (pick) profile = fixUploadPath(pick, 'jpeg');
              }
              const sEl = div.querySelector('img[alt="sticker"]');
              const stickerSrc = sEl && sEl.getAttribute ? (sEl.getAttribute('src')||'') : '';
              const sticker = stickerSrc ? fixUploadPath(stickerSrc, 'svg') : '';
              const iEl = div.querySelector('img[alt="image"]');
              const imgSrc = iEl && iEl.getAttribute ? (iEl.getAttribute('src')||'') : '';
              const image = imgSrc ? fixUploadPath(imgSrc, 'jpeg') : '';
              let content = '';
              const innerDivs = div.querySelectorAll('div');
              for (const d of innerDivs){
                const t = (d.textContent||'').trim();
                if (!t) continue;
                if (/(좋아요|답글|신고|가리기|고정|더보기)/.test(t)) continue;
                content = t; break;
              }
              return { 닉네임:nickname, 표시시각: timeText, 내용: content, 프로필: profile||'', 스티커: sticker||'', 첨부이미지: image||'' };
            }
          }catch(e){}
          return null;
        }

        async function findComment(userNickname, rawTime, opts){
          if (rawTime===undefined) rawTime=''; if(!opts) opts={};
          try { console.log('[FIND] start', { user:userNickname, time:rawTime, href: location.href }); } catch(_){}
          const maxPages = (opts.maxPages==null) ? 3 : opts.maxPages;
          const tolMin = (typeof opts.toleranceMin === 'number') ? opts.toleranceMin : 1;
          const tolMs = Math.max(0, tolMin) * 60000;
          const targetId = getTargetIdFromURL(); if(!targetId){ try{ console.warn('\u26A0\uFE0F 대상 글/작품 ID를 URL에서 찾지 못함'); }catch(_){}; return; }
          const wantedName = String(userNickname||'').trim().toLowerCase();
          const wantedTime = parseEntryTime(rawTime);
          let currentPage=0, searchAfter=null, found=null;
          while(true){
            currentPage++;
            const page = await fetchPage(targetId, searchAfter, 20);
            const list = page.list || [];
            try { console.log('[FIND] page', currentPage, 'items', list.length); } catch(_){}
            if (!list.length) break;
            for (let i=0;i<list.length;i++){
              const c = list[i];
              const n = String((c.user&&c.user.nickname)||'').trim().toLowerCase();
              const nameOk = (n === wantedName);
              const timeOk = !wantedTime || within(c.created, wantedTime, tolMs);
              if (nameOk && timeOk) { found = c; break; }
            }
            if (found) break;
            searchAfter = page.next;
            if (maxPages!==Infinity && currentPage>=maxPages) break;
            if (!searchAfter) break;
          }
          let domInfo = extractFromDOM(userNickname||'', rawTime||'');
          if (!found && !domInfo){
            try{ console.warn("\u274C '" + (userNickname||'') + "' (" + (rawTime||'시간 미지정') + ") 찾지 못함 (페이지 " + currentPage + ")"); }catch(_){ }
            try { AndroidApp.postMessage('COMMENT_DETAIL_JSON_FIND:'+JSON.stringify({ error:'not_found', user:userNickname||'', time:rawTime||'', page: currentPage })); } catch(_){}
            return;
          }
          // 스마트 업로드 경로 복원
          var profileRaw = (found && found.user && found.user.profileImage && found.user.profileImage.filename) ? found.user.profileImage.filename : '';
          var stickerRaw = (found && found.sticker && found.sticker.filename) ? found.sticker.filename : '';
          var imageRaw   = (found && found.image && found.image.filename) ? found.image.filename : '';
          var profile = profileRaw ? (await fixUploadPathSmart(profileRaw)) : (domInfo ? (domInfo.프로필||'') : '');
          var sticker = stickerRaw ? (await fixUploadPathSmart(stickerRaw)) : (domInfo ? (domInfo.스티커||'') : '');
          var image   = imageRaw   ? (await fixUploadPathSmart(imageRaw))   : (domInfo ? (domInfo.첨부이미지||'') : '');

          const out = {
            nickname: (found && found.user && found.user.nickname) ? found.user.nickname : (domInfo ? (domInfo.닉네임||'') : ''),
            created: (found && found.created) ? found.created : '',
            content: (domInfo && domInfo.내용) ? domInfo.내용 : (found && found.content ? found.content : ''),
            profile: profile || '',
            sticker: sticker || '',
            image: image || ''
          };

          try {
            AndroidApp.postMessage('COMMENT_DETAIL_JSON_FIND:'+JSON.stringify(out));
            AndroidApp.postMessage('COMMENT_DETAIL_ARRAY:'+JSON.stringify([{
              "닉네임": out.nickname,
              "작성시각": out.created,
              "내용": out.content,
              "프로필": out.profile||'' ,
              "스티커": out.sticker||'' ,
              "첨부이미지": out.image||''
            }]));
          } catch(_){ }

          try { console.log('\u2705 댓글 찾음 (페이지:', currentPage, ')'); console.table([{
            닉네임: out.nickname, 작성시각: out.created, 내용: out.content, 프로필: out.profile || '(없음)', 스티커: out.sticker || '(없음)', 첨부이미지: out.image || '(없음)'
          }]); } catch(_){ }
          return out;
        }

        window.findComment = findComment;
      })();
    """.trimIndent()

    // 사용자 제공 로직으로 교체한 findComment (오버라이드)
    private val jsFindCommentOverride = """
      (() => {
        const sleep = ms => new Promise(r => setTimeout(r, ms));
        const toAbs = p => /^https?:\/\//i.test(p) ? p : (location.origin + (p.startsWith('/') ? p : '/' + p));
        function parseEntryTime(s){ if(!s) return null; const m=String(s).match(/[I]?(\d{2})\.(\d{2})\.(\d{2})\s*[・\-.]?\s*(\d{2}):(\d{2})/); if(!m) return null; const [_,yy,MM,dd,hh,mm]=m; return new Date(2000+ +yy, +MM-1, +dd, +hh, +mm, 0); }
        function sameMinute(a,b){ try { return Math.abs(new Date(a) - new Date(b)) <= 60000; } catch { return false; } }
        function getHeaders(){ let x=''; try { x=localStorage.getItem('playentry_token')||sessionStorage.getItem('entry_xToken')||''; } catch(_){} const c=(document.querySelector('meta[name=\"csrf-token\"]')||{}).content||''; const h={ 'content-type':'application/json','x-client-type':'Client' }; if(x) h['x-token']=x; if(c) h['csrf-token']=c; return h; }
        function getPageInfoFromURL(){
          const u = String(location.href || '');
          const g = u.match(/\/group\/community\/([0-9a-f]{24})\/([0-9a-f]{24})/i);
          if (g) return { id:g[1], groupId:g[2], subject:'discuss', type:'group_community' };
          const p = u.match(/\/project\/([0-9a-f]{24})/i);
          if (p) return { id:p[1], subject:'project', type:'project' };
          const c = u.match(/\/community\/(qna|tips|entrystory|notice|entrylife|free|discuss)\/([0-9a-f]{24})/i);
          if (c) return { id:c[2], subject:'discuss', type:c[1].toLowerCase() };
          return { id:null, subject:'unknown', type:'unknown' };
        }
        function getTargetIdFromURL(){ const info=getPageInfoFromURL(); if(info && info.id) return info.id; const m=String(location.href||'').match(/([0-9a-f]{24})(?![0-9a-f])/i); return m?m[1]:null; }
        function hasExt(name){ return /\.[a-z0-9]{3,4}$/i.test(name); }
        function ensureUploadsPrefix(pathLike){ if(!pathLike) return ''; if(pathLike.includes('/uploads/')) return pathLike.slice(pathLike.indexOf('/uploads/')); if(pathLike.startsWith('/')) return '/uploads'+pathLike; return pathLike.includes('/')?('/uploads/'+pathLike.replace(/^\/+/,'')):pathLike; }
        function splitFoldersFromSlug(slug){ const a=slug.slice(0,2), b=slug.slice(2,4); return `${'$'}{a}/${'$'}{b}/${'$'}{slug}`; }
        function resolveExt(baseWithoutExt, extList){ return new Promise(resolve=>{ let i=0; const tryNext=()=>{ if(i>=extList.length) return resolve(''); const url=toAbs(`/uploads/${'$'}{baseWithoutExt}.${'$'}{extList[i++]}`); const img=new Image(); img.onload=()=>resolve(url); img.onerror=tryNext; img.decoding='async'; img.referrerPolicy='no-referrer'; img.src=url; }; tryNext(); }); }
        async function normalizeUploadUrl(raw, kind){ if(!raw) return ''; if(/^https?:\/\//i.test(raw)){ if(hasExt(raw)) return raw; const body=raw.replace(/^https?:\/\/[^/]+\/uploads\//i,''); const extCandidates= kind==='sticker'?['svg','png','webp','jpg','jpeg','gif'] : kind==='image'?['png','jpg','jpeg','webp','gif'] : ['jpeg','svg','jpg','png','webp']; const guessed=await resolveExt(body.replace(/^\/?uploads\//i,'').replace(/^\//,''), extCandidates); return guessed||raw; } let path=ensureUploadsPrefix(String(raw).trim()); if(path.startsWith('/uploads/')&&hasExt(path)) return toAbs(path); if(path.startsWith('/uploads/') && !hasExt(path) && path.split('/').length>=4){ const body=path.replace(/^\/?uploads\//i,'').replace(/^\//,''); const extCandidates= kind==='sticker'?['svg','png','webp','jpg','jpeg','gif'] : kind==='image'?['png','jpg','jpeg','webp','gif'] : ['jpeg','svg','jpg','png','webp']; const guessed=await resolveExt(body, extCandidates); return guessed||toAbs(path); } if(!path.includes('/') || !path.startsWith('/uploads/')){ const onlySlug=path.replace(/^\/?uploads\//i,'').replace(/^\//,''); const body=splitFoldersFromSlug(onlySlug); const extCandidates= kind==='sticker'?['svg','png','webp','jpg','jpeg','gif'] : kind==='image'?['png','jpg','jpeg','webp','gif'] : ['jpeg','svg','jpg','png','webp']; const guessed=await resolveExt(body, extCandidates); return guessed||toAbs('/uploads/'+body); } return toAbs(path); }
        async function fetchPage(target, searchAfter=null, display=20){ const q=`query SELECT_COMMENTS(${"$"}{'$'}pageParam: PageParam, ${"$"}{'$'}target: String, ${"$"}{'$'}searchAfter: JSON){ commentList(pageParam:${"$"}{'$'}pageParam, target:${"$"}{'$'}target, searchAfter:${"$"}{'$'}searchAfter){ list{ id content created user{ nickname profileImage{ filename } } image{ filename } sticker{ filename } } searchAfter total } }`; const body={ query:q, variables:{ pageParam:{ display, sort:'created', order:-1 }, target, searchAfter } }; try{ const res=await fetch('https://playentry.org/graphql/SELECT_COMMENTS',{ method:'POST', headers:getHeaders(), credentials:'include', body:JSON.stringify(body)}); const j=await res.json().catch(()=>({})); const box=j?.data?.commentList; return { list:Array.isArray(box?.list)?box.list:[], next:box?.searchAfter??null }; }catch{ return { list:[], next:null }; } }
        async function findComment(nickname, rawTime='', opts={}){ const maxPages=(opts.maxPages??10); const waitAfterLoadMs=(opts.waitAfterLoadMs??300); if(waitAfterLoadMs>0) await sleep(waitAfterLoadMs); const targetId=getTargetIdFromURL(); if(!targetId){ console.warn('⚠️ 대상 글/작품 ID를 URL에서 찾지 못했어요.'); return null; } const wantedName=String(nickname||'').trim().toLowerCase(); const wantedTime=parseEntryTime(rawTime); let searchAfter=null, current=0, found=null; while(true){ current++; const { list, next } = await fetchPage(targetId, searchAfter, 30); if(!list.length) break; const hit = list.find(c=>{ const n=(c.user?.nickname||'').trim().toLowerCase(); const nameOk=(n===wantedName); const timeOk=!wantedTime || sameMinute(c.created, wantedTime); return nameOk && timeOk; }); if(hit){ found=hit; break; } searchAfter = next ?? ( ()=>{ const last=list[list.length-1]; return last ? { created:last.created, id:last.id } : null; } )(); if(!searchAfter) break; if(maxPages!==Infinity && current>=maxPages) break; } if(!found){ console.warn(`❌ '${'$'}{nickname}' (${ '$'}{rawTime || '시간 미지정'})을(를) 찾지 못했어요.`); return null; } const profile = await normalizeUploadUrl(found.user?.profileImage?.filename||'', 'profile'); const sticker = await normalizeUploadUrl(found.sticker?.filename||'', 'sticker'); const image = await normalizeUploadUrl(found.image?.filename||'', 'image'); const out={ nickname: found.user?.nickname||'', created: found.created||'', content: found.content||'', profile, sticker, image, has:{ profile: !!profile, sticker: !!sticker, image: !!image } }; try{ AndroidApp.postMessage('COMMENT_DETAIL_JSON_FIND:'+JSON.stringify(out)); AndroidApp.postMessage('COMMENT_DETAIL_ARRAY:'+JSON.stringify([{ "닉네임": out.nickname, "작성시각": out.created, "내용": out.content, "프로필": out.profile, "스티커": out.sticker, "첨부이미지": out.image }])); }catch{} return out; }
        window.findComment = findComment;
      })();
    """.trimIndent()

    // ✅ 쿠키 재주입 JS (팝업 WebView에서 호출)
    private val jsTokenInjector = """
        (() => {
          try {
            const x = sessionStorage.getItem('entry_xToken');
            if (x) {
              const cleanX = x.replace(/^["']+|["']+$/g, '');
              if (cleanX) document.cookie = `playentry_token=${'$'}{cleanX}; path=/; domain=.playentry.org`;
            }
          } catch {}
        })();
    """.trimIndent()

    // ✅ 메인 진입 시 댓글 알림 조회 JS
    private val jsFetchCommentNotifications = """
        (async () => {
          try {
            const xToken = sessionStorage.getItem("entry_xToken");
            const csrfToken = sessionStorage.getItem("entry_csrfToken");
            if (!xToken || !csrfToken) return AndroidApp.postMessage("COMMENT_NONE");

            function normalizeParams(raw) {
              try {
                if (raw == null) return [];
                if (Array.isArray(raw)) return raw;
                if (typeof raw === 'object') {
                  const known = [];
                  const preferredKeys = ['userName', 'user_name', 'username', 'title', 'text', 'name'];
                  preferredKeys.forEach((k) => {
                    if (k in raw && raw[k] != null) known.push(String(raw[k]));
                  });
                  const values = Object.values(raw).map(v => v == null ? '' : String(v));
                  return [...known, ...values].filter((v, idx, arr) => v && arr.indexOf(v) === idx);
                }
                if (typeof raw === 'string') {
                  const s = raw.trim();
                  if (!s) return [];
                  if ((s.startsWith('[') && s.endsWith(']')) || (s.startsWith('{') && s.endsWith('}'))) {
                    const parsed = JSON.parse(s);
                    if (Array.isArray(parsed)) return parsed;
                    if (parsed && typeof parsed === 'object') return Object.values(parsed);
                  }
                  return s.split(',').map(v => v.trim());
                }
                return [String(raw)];
              } catch { return [String(raw)]; }
            }

            const query = `
              query SELECT_TOPICS(${'$'}pageParam: PageParam) {
                topicList(pageParam: ${'$'}pageParam) {
                  list { id template params created link { category target hash groupId } }
                }
              }`;

            const res = await fetch('https://playentry.org/graphql/SELECT_TOPICS', {
              method: 'POST',
              headers: { 'content-type': 'application/json', 'x-token': xToken, 'csrf-token': csrfToken, 'x-client-type': 'Client' },
              body: JSON.stringify({ query, variables: { pageParam: { display: 20 } } }),
              credentials: 'include'
            });
            const data = await res.json();
            const list = data?.data?.topicList?.list || [];
            if (!list.length) return AndroidApp.postMessage('COMMENT_NONE');

            for (const notif of list) {
              // 템플릿으로 댓글 알림만 선별 (공유/좋아요/팔로우는 제외)
              const tpl = String(notif?.template || '').toLowerCase();
              const isComment = tpl === 'template_1' /* 작품 댓글 */ || tpl === 'template_34' /* 내 댓글에 답글 */ || tpl === 'template_space_1' /* 스페이스 댓글/답글 */;
              if (!isComment) continue;

              const user = (() => { const p = normalizeParams(notif.params); return p?.[0] || notif.params?.userName || '(작성자)'; })();
              const contentTitle = (() => { const p = normalizeParams(notif.params); return p?.[1] || ''; })();
              const createdTs = Date.parse(notif.created);
              const timeIso = isNaN(createdTs) ? '' : new Date(createdTs).toISOString();
              const time = new Date(createdTs).toLocaleString();
              const payload = { id: notif.id, template: tpl, user, contentTitle, time, timeIso, link: notif.link || {} };
              // 댓글 알림만 Android로 전달
              AndroidApp.postMessage('COMMENT:' + JSON.stringify(payload));
              // GraphQL 상세를 즉시 요청해 본문/첨부 확보 (DOM 스크랩은 폴백)
              if (notif.link?.target) {
                try { window.__ENTRY_FETCH_DETAIL__ && window.__ENTRY_FETCH_DETAIL__(notif.link.target); } catch(e){}
              }
            }
          } catch(e) {
            AndroidApp.postMessage('COMMENT_NONE');
          }
        })();
    """.trimIndent()

    // ✅ 댓글 매핑/추출 스크립트 (읽기 전용 - DOM 조작 없음)
    private val jsCommentMapping = """
      (function() {
        'use strict';

        function extractComments() {
          try {
            // ✅ 읽기 전용 추출 (DOM 조작 없음)
          const commentBlocks = document.querySelectorAll(
            'div.css-uc6b30.e1877mpo2, div.css-xjp3o7.e1877mpo2'
          );
          const comments = [];
          commentBlocks.forEach(block => {
              try {
            const data = {};
            // 프로필: background-image 에서 추출, EmptyImage.svg 제외
            const profEl = block.querySelector('[style*="background-image"]');
            if (profEl) {
              const style = profEl.getAttribute('style') || '';
              const m = style.match(/background-image\s*:\s*url\((?:&quot;|"|')?(.*?)(?:&quot;|"|')?\)/i);
              if (m && m[1] && !/\/img\/EmptyImage\.svg$/i.test(m[1])) {
                data.profile = new URL(m[1], location.origin).href;
              }
            }
            const nickname = block.querySelector('.ee2n3ac5 a');
            if (nickname) data.nickname = nickname.textContent.trim();
            const content = block.querySelector('.e1i41bku1');
            if (content) data.content = content.textContent.trim();
            const sticker = block.querySelector('img[alt="sticker"]');
            if (sticker) { const src = sticker.getAttribute('src'); if (src) data.sticker = new URL(src, location.origin).href; }
            const attach = block.querySelector('a[role="button"] img');
            if (attach) { const src = attach.getAttribute('src'); if (src) data.image = new URL(src, location.origin).href; }
            if (Object.keys(data).length > 0) comments.push(data);
              } catch (e) {
                console.log('[COMMENT_MAP] Block processing error:', e.message);
              }
          });
          return comments;
          } catch (e) {
            console.log('[COMMENT_MAP] Extract error:', e.message);
            return [];
        }
        }

        // ✅ DOM 조작 없이 읽기 전용으로만 설정
        window.__ENTRY_COMMENTS__ = extractComments;
      })();
    """.trimIndent()

    // ✅ 열고 스크랩(딥링크 이동 후 DOM에서 댓글 1개 추출)
    private val jsOpenAndScrape = """
      (()=>{
        if(window.__ENTRY_OPEN_AND_SCRAPE__) return;
        function deepLinkFromLink(link){
          const base='https://playentry.org';
          if(!link) return base+'/alarm';
          const c=String(link.category||'').toLowerCase();
          const t=String(link.target||'');
          if(!t) return base+'/alarm';
          if(c==='project' || c==='comment' || !c) return `${'$'}{base}/project/${'$'}{t}`;
          if(['qna','tips','entrystory','notice','entrylife','free','discuss'].includes(c)) return `${'$'}{base}/community/${'$'}{c}/${'$'}{t}`;
          if(c==='following' || c==='user') return `${'$'}{base}/profile/${'$'}{t}/project?sort=created&term=all&isOpen=all`;
          return `${'$'}{base}/community/${'$'}{c}/${'$'}{t}`;
        }
        function waitFor(sel,timeout=15000){return new Promise((res,rej)=>{const hit=document.querySelector(sel);if(hit) return res(hit);const m=new MutationObserver(()=>{const el=document.querySelector(sel);if(el){m.disconnect();res(el);}});m.observe(document.documentElement,{childList:true,subtree:true});setTimeout(()=>{m.disconnect();res(null);},timeout);});}
        function mapRepliesWithFiber(ul){try{const k=Object.keys(ul).find(k=>k.startsWith('__reactFiber$'));const f=k&&ul[k];const p=f?.child?.pendingProps;const arr=Array.isArray(p)?p:(p?.children||[]);const ids=arr.map(v=>v?.props?.id).filter(Boolean);const lis=ul.querySelectorAll(':scope > li');lis.forEach((li,i)=>ids[i]&&li.setAttribute('data-reply-id',ids[i]));}catch{}}
        function extractOne(block){
          const out={};
          const prof=block.querySelector('a.enx4swp0, a.css-18bdrlk');
          if(prof){const st=prof.getAttribute('style')||'';const m=st.match(/url\(&quot;([^&]+uploads[^&]+)&quot;\)/);if(m&&m[1]) out.profile=new URL(m[1],location.origin).href;}
          const nick=block.querySelector('.ee2n3ac5 a'); if(nick) out.nickname=nick.textContent.trim();
          const content=block.querySelector('.e1i41bku1'); if(content) out.content=content.textContent.trim();
          // 시간 요소 추출 (가능한 경우 ISO로 정규화)
          const timeEl=block.querySelector('time, .created, [datetime]');
          if(timeEl){
            const raw=timeEl.getAttribute('datetime')||timeEl.getAttribute('title')||timeEl.textContent||'';
            const t=Date.parse(raw);
            if(!isNaN(t)) out.timeIso=new Date(t).toISOString();
          }
          const sticker=block.querySelector('.e1877mpo0 img[alt="sticker"]'); if(sticker) out.sticker=new URL(sticker.getAttribute('src')||'',location.origin).href;
          const attach=block.querySelector('.e1877mpo1 img[alt="image"]'); if(attach) out.image=new URL(attach.getAttribute('src')||'',location.origin).href;
          return out;
        }
        async function scrapeOne(hints){
          const list=await waitFor('.css-1m3ba66.e1fqckzt0');
          if(!list){ AndroidApp.postMessage('COMMENT_DETAIL_JSON:'+JSON.stringify({ error:'timeout' })); return; }
          list.querySelectorAll(':scope > li.css-zdw2xm.e19b9x4q0').forEach((li,i)=>{if(!li.hasAttribute('data-post-id')) li.setAttribute('data-post-id','auto_'+i);});
          list.querySelectorAll(':scope ul').forEach(mapRepliesWithFiber);
          let targetLi=null;
          if(hints.commentId){targetLi=list.querySelector(`[data-post-id="${'$'}{hints.commentId}"] , [data-reply-id="${'$'}{hints.commentId}"]`);}
          const blocks=list.querySelectorAll('div.css-uc6b30.e1877mpo2, div.css-xjp3o7.e1877mpo2');
          if(!targetLi&&(hints.user||hints.timeIso)){
            const want=(hints.user||'').trim();
            const wantTs=Date.parse(hints.timeIso||'');
            const hasTime=!isNaN(wantTs);
            let best=null; let bestDiff=Number.POSITIVE_INFINITY;
            for(const b of blocks){
              const a=b.querySelector('.ee2n3ac5 a');
              if(want && (!a || a.textContent.trim()!==want)) continue;
              const d=extractOne(b);
              if(hasTime && d.timeIso){
                const diff=Math.abs(Date.parse(d.timeIso)-wantTs);
                if(diff<=5000){ best=b; bestDiff=diff; break; } // 5초 이내면 즉시 채택
                if(diff<bestDiff){ best=b; bestDiff=diff; }
              } else if(!best){ best=b; }
            }
            if(best) targetLi=best.closest('li');
          }
          if(!targetLi){targetLi=list.querySelector(':scope > li.css-zdw2xm.e19b9x4q0');}
          if(!targetLi) throw new Error('no target li');
          const block=targetLi.querySelector('div.css-uc6b30.e1877mpo2, div.css-xjp3o7.e1877mpo2')||targetLi;
          const data=extractOne(block);
          AndroidApp.postMessage('COMMENT_DETAIL_JSON:'+JSON.stringify(data));
        }
        window.__ENTRY_OPEN_AND_SCRAPE__=async function(linkJson,hintJson){try{const link=JSON.parse(linkJson||'{}');const hints=JSON.parse(hintJson||'{}');const url=deepLinkFromLink(link);if(location.href!==url){location.assign(url);await new Promise(r=>setTimeout(r,300));}await scrapeOne(hints);}catch(e){AndroidApp.postMessage('COMMENT_DETAIL_JSON:'+JSON.stringify({error:String(e)}));}};
      })();
    """.trimIndent()

    // ✅ 대표 댓글 1건을 추출해 Android로 전달 (프로필/닉네임/본문/스티커/이미지)
    private val jsSendOneCommentDetail = """
      (function(){
        try {
          const get = window.__ENTRY_COMMENTS__;
          if (!get) return;
          const list = get();
          if (!Array.isArray(list) || !list.length) return;
          const c = list[0];
          const profile = c.profile || '';
          const nickname = c.nickname || '';
          const content = c.content || '';
          const sticker = c.sticker || '';
          const image = c.image || '';
          AndroidApp.postMessage('COMMENT_DETAIL:' + [profile, nickname, content, sticker, image].join('|'));
        } catch(e){}
      })();
    """.trimIndent()

    // ✅ REST 기반 가벼운 수집기 (단발성 1회 호출용 + Android로 대표 한 건 전달)
    private val restCommentFetcher = """
      (() => {
        const BASE = "https://playentry.org/api/comment";
        const H = { accept: "application/json", "x-client-type": "Client" };
        const sleep = (ms) => new Promise(r => setTimeout(r, ms));
        const nowTs = () => Date.now();
        async function fetchJson(url, { retries = 1, delay = 200 } = {}) {
          for (let i = 0; i <= retries; i++) {
            try {
              const res = await fetch(url, { headers: H, credentials: "include" });
              if (!res.ok) throw new Error(`${'$'}{res.status} ${'$'}{res.statusText}`);
              return await res.json();
            } catch (e) { if (i === retries) throw e; await sleep(delay * (i + 1)); }
          }
        }
        async function fetchRootPage(discussId, page) {
          const url = `${'$'}{BASE}/discuss/list/${'$'}{discussId}/${'$'}{page}?noCache=${'$'}{nowTs()}&target=${'$'}{discussId}&targetSubject=discuss&targetType=individual`;
          const json = await fetchJson(url);
          return (json?.list ?? []).map(c => ({
            nickname: c?.user?.nickname || '(익명)',
            content: c?.content || '',
            profile: c?.user?.profileImage?.filename ? (`https://playentry.org/uploads/${'$'}{c.user.profileImage.filename}`) : '',
            sticker: c?.sticker?.filename ? (`https://playentry.org/uploads/${'$'}{c.sticker.filename}`) : '',
            image: c?.image?.filename ? (`https://playentry.org/uploads/${'$'}{c.image.filename}`) : ''
          }));
        }
        async function once(discussId){
          try {
            const list = await fetchRootPage(discussId, 1);
            if (Array.isArray(list) && list.length) {
              const c = list[0];
              AndroidApp.postMessage('COMMENT_DETAIL:' + [c.profile, c.nickname, c.content, c.sticker, c.image].join('|'));
            }
          } catch(e) {}
        }
        window.__ENTRY_FETCH_ONCE__ = once;
      })();
    """.trimIndent()

    // ✅ SELECT_TOPICS 폴링(diff) 기반 실시간 감시기 (중지/재개/재주입 가능)
    private val jsNotifPoller: String get() {
        val baseInterval = preferenceManager.pollingIntervalMs.coerceAtLeast(5000L)
        return """
  (() => {
    const VERSION = '2026-02-25-b';
    if (window.__ENTRY_POLLER__?.stop) { try { window.__ENTRY_POLLER__.stop(); } catch (_) {} }
    if (window.__ENTRY_POLLER__?.version === VERSION) return;

    const MAX_PAGES = 1;
    const PAGE_SIZE = 100;
    const BASE_INTERVAL = $baseInterval;
    let timer = null;
    let paused = false;
    let intervalMs = BASE_INTERVAL;
    let consecutiveAuthErrors = 0;
    const AUTH_ERROR_LIMIT = 3;

    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

        function readCookie(name){
          try {
            const entry = document.cookie.split(';').map(s => s.trim()).find(row => row.startsWith(name + '='));
            return entry ? decodeURIComponent(entry.split('=')[1]) : '';
          } catch (_) { return ''; }
        }

        function resolveToken(storageKey, cookieName){
          let value = '';
          try { value = sessionStorage.getItem(storageKey) || localStorage.getItem(storageKey) || ''; } catch (_) {}
          if (!value) {
            value = readCookie(cookieName);
            if (value) {
              try {
                sessionStorage.setItem(storageKey, value);
                localStorage.setItem(storageKey, value);
              } catch (_) {}
            }
          }
          return value;
        }

        function buildHeaders(){
          let xtoken = resolveToken('entry_xToken', 'playentry_token');
          let csrf = resolveToken('entry_csrfToken', '_csrf');

          // 토큰이 없으면 __NEXT_DATA__에서 재추출 시도
          if (!xtoken || !csrf) {
            try {
              const el = document.getElementById('__NEXT_DATA__');
              if (el && el.innerText) {
                const data = JSON.parse(el.innerText || '{}');
                if (!xtoken) {
                  xtoken = data?.props?.initialState?.common?.user?.xToken
                    || data?.props?.pageProps?.initialState?.common?.user?.xToken || '';
                  if (xtoken) {
                    sessionStorage.setItem('entry_xToken', xtoken);
                    localStorage.setItem('entry_xToken', xtoken);
                  }
                }
                if (!csrf) {
                  csrf = data?.props?.initialProps?.csrfToken || '';
                  if (!csrf) csrf = document.querySelector('meta[name="csrf-token"]')?.content || '';
                  if (csrf) {
                    sessionStorage.setItem('entry_csrfToken', csrf);
                    localStorage.setItem('entry_csrfToken', csrf);
                  }
                }
              }
            } catch (e) {
              console.error('[POLLER] Token re-extraction failed:', e.message);
            }
          }

          if (!xtoken || !csrf) {
            console.log('[POLLER] No tokens available - xtoken:', !!xtoken, 'csrf:', !!csrf);
            return { ready:false };
          }

          const base = {
            'content-type': 'application/json',
            'accept': '*/*',
            'x-token': xtoken,
            'csrf-token': csrf,
            'x-client-type': 'Client',
            'accept-language': navigator.language || 'ko,en;q=0.9',
            'cache-control': 'no-cache',
            'pragma': 'no-cache',
            'dnt': '1',
            'origin': location.origin,
            'referer': location.href,
            'user-agent': navigator.userAgent || ''
          };
          return { ready: true, headers: base };
        }

    const baseQuery = `query SELECT_TOPICS(${'$'}pageParam: PageParam){
      topicList(pageParam: ${'$'}pageParam){
        list{
          id
          params
          category
          template
          created
          isRead
          link { category target hash groupId }
        }
      }
    }`;

	        async function fetchPage(page){
	          if (location.pathname === '/signin') {
	            return { status: 0, list: [], searchAfter: null, error: 'signin_page' };
	          }
	          const ctx = buildHeaders();
	          if (!ctx.ready) return { status: 0, list: [], searchAfter: null, error: 'no_token' };
          try {
            const body = JSON.stringify({
          operationName: "SELECT_TOPICS",
              query: baseQuery,
          variables: { pageParam: { display: PAGE_SIZE } }
        });
        const res = await fetch('https://playentry.org/graphql/SELECT_TOPICS', {
          method: 'POST',
          headers: ctx.headers,
          credentials: 'include',
          body
        });
        const raw = await res.text();
        if (!res.ok) {
          console.error('[POLLER] GraphQL HTTP error', res.status, raw);
          return { status: res.status || -1, list: [], searchAfter: null, error: raw || ('http_' + (res.status || '?')) };
        }
        let json = {};
        try { json = raw ? JSON.parse(raw) : {}; }
        catch (err) {
          console.error('[POLLER] GraphQL JSON parse error', err?.message, raw?.slice?.(0,200));
          return { status: -2, list: [], searchAfter: null, error: 'json_parse' };
        }
        if (Array.isArray(json?.errors) && json.errors.length) {
          console.error('[POLLER] GraphQL response errors', JSON.stringify(json.errors));

          const hasAuthError = json.errors.some(err => {
            const code = err?.statusCode || err?.status || err?.extensions?.statusCode;
            return code === 401 || code === 403;
          });
          if (hasAuthError) {
            return { status: 401, list: [], searchAfter: null, error: 'graphql_auth' };
          }
        }
        const bucket = json?.data?.topicList;
        const list = Array.isArray(bucket?.list) ? bucket.list : [];
        return { status: 200, list, searchAfter: bucket?.searchAfter || null };
      } catch (e) {
        return { status: -1, list: [], searchAfter: null, error: e?.message || 'error' };
      }
    }

        async function collectNotifications(){
          await sleep(200); // 토큰 세션 안정화 대기
          const aggregated = [];
      for (let page=1; page<=MAX_PAGES; page++){
        const res = await fetchPage(page);
        if (res.status !== 200) {
          return { status: res.status, list: aggregated, error: res.error };
        }
        if (res.list.length) aggregated.push(...res.list);
        if (res.list.length < PAGE_SIZE) break;
        await sleep(160);
      }
      return { status: 200, list: aggregated };
    }

    // ✅ 이미 보낸 알림 ID 추적 (중복 전송 방지, 단 일정 시간 후에는 재전송 허용)
    // id -> 마지막으로 보낸 시각(ms). 안드로이드 쪽에서 아직 댓글 내용을 못 얻은 경우
    // 재시도할 수 있도록 영구 차단이 아니라 시간 제한(TTL) 방식으로 동작한다.
    const sentNotificationIds = new Map();
    const RESEND_WINDOW_MS = 40000; // 이 시간이 지나면 같은 id도 다시 보낼 수 있음

    async function tick(){
      if (paused) return;
      const result = await collectNotifications();
      if (result.status === 200) {
        consecutiveAuthErrors = 0;
        window.__ALARM_RAW = result.list;
        intervalMs = BASE_INTERVAL;

        // ✅ 새로운 알림 + 아직 해결되지 않아 재전송 대상인 알림만 필터링
        const now = Date.now();
        const newNotifications = result.list.filter(n => {
          const id = n?.id;
          if (!id) return false;
          const lastSent = sentNotificationIds.get(id);
          if (lastSent !== undefined && (now - lastSent) < RESEND_WINDOW_MS) return false;
          sentNotificationIds.set(id, now);
          return true;
        });

        // ✅ 크기 제한 (메모리 관리) - 오래된 항목부터 제거
        if (sentNotificationIds.size > 500) {
          const entries = Array.from(sentNotificationIds.entries());
          entries.sort((a, b) => a[1] - b[1]);
          sentNotificationIds.clear();
          entries.slice(-300).forEach(([id, ts]) => sentNotificationIds.set(id, ts));
        }

        if (newNotifications.length > 0) {
          try {
            console.log('[POLLER] Sending', newNotifications.length, 'NEW notifications to Android (filtered from', result.list.length, 'total)');
            AndroidApp.postMessage('NEW_NOTIF:' + JSON.stringify(newNotifications));
          } catch (_) {}
        } else {
          // 중복만 있으면 조용히 스킵
          if (result.list.length > 0) {
            console.log('[POLLER] All', result.list.length, 'notifications already sent, skipping');
            try { AndroidApp.postMessage('POLL_STATUS:no_new|' + result.list.length); } catch (_) {}
          } else {
            try { AndroidApp.postMessage('POLL_STATUS:empty'); } catch (_) {}
          }
        }
      } else if (result.status === 403 || result.status === 401) {
        const had403 = result.status === 403;
        consecutiveAuthErrors++;
        intervalMs = Math.min(intervalMs * 2, 5000);
        if (had403) {
          try { AndroidApp.postMessage('POLL_ERROR:403'); } catch (_) {}
          try { AndroidApp.postMessage('POLL_STATUS:error_403'); } catch (_) {}
        } else {
          try { AndroidApp.postMessage('POLL_ERROR:401'); } catch (_) {}
          try { AndroidApp.postMessage('POLL_STATUS:error_401'); } catch (_) {}
        }

        if (consecutiveAuthErrors >= AUTH_ERROR_LIMIT) {
          consecutiveAuthErrors = 0;
          try {
            AndroidApp.postMessage('NEED_RELOGIN');
            console.log('[POLLER] Sending NEED_RELOGIN after repeated auth failures');
          } catch (_) {
            console.error('[POLLER] Failed to post NEED_RELOGIN');
          }
        }
      } else if (result.status === 0) {
        // Token values may be temporarily unavailable right after page load/bootstrap.
        // Do not escalate to NEED_RELOGIN for this case.
        consecutiveAuthErrors = 0;
        intervalMs = Math.min(Math.max(intervalMs, 2000) * 2, 10000);
        try { AndroidApp.postMessage('POLL_STATUS:no_token'); } catch (_) {}
      } else {
        consecutiveAuthErrors = 0;
        // GraphQL 실패 시 DOM 감지 fallback
        console.log('[POLLER] GraphQL failed, trying DOM detection fallback');
        try {
          const domNotifs = detectDOMNotifications();
          console.log('[POLLER] DOM detected', domNotifs.length, 'notifications');
          // ✅ DOM 감지 성공 시 안드로이드로 전송
          if (domNotifs && domNotifs.length > 0) {
            try {
              AndroidApp.postMessage('NEW_NOTIF:' + JSON.stringify(domNotifs));
              console.log('[POLLER] Sent', domNotifs.length, 'DOM notifications to Android');
            } catch (err) {
              console.error('[POLLER] Failed to send DOM notifications:', err.message);
            }
          } else {
            try { AndroidApp.postMessage('POLL_STATUS:dom_empty'); } catch (_) {}
          }
        } catch (e) {
          console.error('[POLLER] DOM detection failed:', e.message);
          try { AndroidApp.postMessage('POLL_STATUS:dom_error'); } catch (_) {}
        }
        intervalMs = BASE_INTERVAL;
        // ✅ DOM 감지 실행했으므로 POLL_ERROR 전송하지 않음 (중복 방지)
      }
      // ✅ 디버그: 응답 요약 전송 (실기기)
      try {
        const preview = (result && Array.isArray(result.list) && result.list.length > 0)
          ? JSON.stringify(result.list[0]).slice(0, 160)
          : (result && result.error ? String(result.error).slice(0, 80) : 'empty');
        AndroidApp.postMessage('POLL_DEBUG:' + (result.status || '?') + '|' + (result.list ? result.list.length : 0) + '|' + preview);
      } catch (_) {}
      timer = setTimeout(tick, intervalMs);
    }

    function detectDOMNotifications() {
      const ENABLE_DOM_POSTS = false;
      try {
        console.log('[DOM_DETECT] Starting DOM notification detection...');
        console.log('[DOM_DETECT] Current URL:', window.location.href);

        // ✅ 알림 페이지(/alarm)에서만 실행
        if (!window.location.pathname.includes('/alarm')) {
          console.log('[DOM_DETECT] Not on alarm page, skipping');
          return [];
        }

        try {
          const notifList = document.querySelectorAll('.notification-list-item');
          notifList.forEach(el => {
            const txt = el.textContent || '';
            if (txt.includes('팔로잉') && !el.dataset.followSent) {
              el.dataset.followSent = '1';
              const nickname = (el.querySelector('.nickname')?.textContent || '').trim();
              const linkEl = el.querySelector('a[href*="/profile/"]');
              const link = linkEl ? linkEl.getAttribute('href') : '';
              console.log('[FOLLOW_DETECT] Found follow notification for:', nickname);
              if (ENABLE_DOM_POSTS) {
                AndroidApp.postMessage('FOLLOW:' + JSON.stringify({
                  user: nickname || '(알수없음)',
                  time: new Date().toISOString(),
                  link: { url: link }
                }));
              }
            }
          });
        } catch (e) {
          console.log('[FOLLOW_DETECT] Error:', e.message);
        }

        // ✅ 더 정확한 선택자: time 태그를 포함한 li만 선택 (알림만)
        let liList = document.querySelectorAll('.css-1206xd6.eae70lg1 ul.css-1wc2sdr.eae70lg0 > li:has(time)');
        if (liList.length === 0) {
          // 대체 선택자: time 태그를 포함한 li (알림만)
          liList = document.querySelectorAll('li:has(time)');
          console.log('[DOM_DETECT] Using alternative selector with time filter, found:', liList.length);
        }
        if (liList.length === 0) {
          // 마지막 폴백: 알림 페이지의 메인 리스트 컨테이너 내부 li만
          liList = document.querySelectorAll('main ul[class*="css"] > li');
          console.log('[DOM_DETECT] Using main list selector, found:', liList.length);
          // time 태그 필터링
          liList = Array.from(liList).filter(li => li.querySelector('time'));
          console.log('[DOM_DETECT] After time filter:', liList.length);
        }
        console.log('[DOM_DETECT] Final count:', liList.length, 'notification items');
        const result = Array.from(liList).map((li, index) => {
          console.log('[DOM_DETECT] Processing item', index + 1, 'of', liList.length);
          const msg = (
            li.querySelector('time')?.parentElement?.previousElementSibling?.innerText ||
            li.querySelector('[class*="meid"][class*="1l656pp"]')?.innerText ||
            li.querySelector('[class*="meid"]')?.innerText ||
            li.innerText
          ).trim();
          const message = msg.replace(/\s+/g, ' ');
          console.log('[DOM_DETECT] Message:', message);
          const created = (
            li.querySelector('time')?.innerText ||
            li.querySelector('[datetime]')?.getAttribute('datetime') ||
            li.querySelector('[class*="meid"][class*="u980j1"]')?.innerText ||
            ''
          ).trim();
          let nickname = li.querySelector('em:nth-of-type(2)')?.textContent.trim() || '(알수없음)';
          if (nickname === '(알수없음)') {
            nickname = li.querySelector('em')?.textContent.trim() || '(알수없음)';
          }
          if (nickname === '(알수없음)') {
            nickname = li.querySelector('strong')?.textContent.trim() || '(알수없음)';
          }
          const category = li.querySelector('em:nth-of-type(1)')?.textContent.trim() || '';
          const thumbStyle = li.querySelector('.css-vwf6be.exgmeid4')?.getAttribute('style') || '';
          const profileMatch = thumbStyle.match(/url\(&quot;([^"]+)&quot;\)/);
          const profile = profileMatch ? 'https://playentry.org' + profileMatch[1] : '';
          let type = 'UNKNOWN';
          const endText = message.slice(-15);
          console.log('[DOM_DETECT] End text:', endText);
          if (endText.includes('작품을 좋아합니다.')) type = 'LIKE_PROJECT';
          else if (endText.includes('댓글을 좋아합니다.')) type = 'LIKE_COMMENT';
          else if (endText.includes('을 좋아합니다.')) type = 'LIKE_POST';
          else if (endText.includes('작품에 댓글을 남겼습니다.')) type = 'COMMENT_PROJECT';
          else if (endText.includes('에 댓글을 남겼습니다.')) type = 'COMMENT_POST';
          else if (endText.includes('팔로잉 합니다.')) type = 'FOLLOW';
          console.log('[DOM_DETECT] Detected type:', type, 'for user:', nickname);
          return { type, category, nickname, message, created, profile };
        });
        if (ENABLE_DOM_POSTS) {
          result.forEach(notif => {
            if (notif.type.startsWith('LIKE_')) {
              AndroidApp.postMessage('LIKE:' + JSON.stringify(notif));
            } else if (notif.type.startsWith('COMMENT_')) {
              AndroidApp.postMessage('COMMENT:' + JSON.stringify(notif));
            } else if (notif.type === 'FOLLOW') {
              AndroidApp.postMessage('FOLLOW:' + JSON.stringify(notif));
            }
          });
        }
        console.log('[DOM_DETECT] Processing', result.length, 'notifications...');
        let processedCount = 0;
        result.forEach((notif, index) => {
          console.log('[DOM_DETECT] Item', index + 1, ':', notif.type, '-', notif.nickname, '-', notif.message);
          if (notif.type !== 'UNKNOWN') {
            try {
              console.log('[DOM_DETECT] Sending notification type:', notif.type);
              AndroidApp.postMessage('NOTIFICATION_TYPE:' + notif.type);
              if (ENABLE_DOM_POSTS) {
                if (notif.type.startsWith('COMMENT_')) {
                  AndroidApp.postMessage('COMMENT:' + JSON.stringify({
                    user: notif.nickname,
                    time: notif.created,
                    timeIso: notif.created,
                    link: {},
                    id: notif.created + '_' + notif.nickname
                  }));
                  processedCount++;
                } else if (notif.type.startsWith('LIKE_')) {
                  AndroidApp.postMessage('LIKE:' + JSON.stringify({
                    user: notif.nickname,
                    time: notif.created,
                    timeIso: notif.created,
                    link: {},
                    id: notif.created + '_' + notif.nickname,
                    type: notif.type
                  }));
                  processedCount++;
                } else if (notif.type === 'FOLLOW') {
                  AndroidApp.postMessage('FOLLOW:' + JSON.stringify({
                    user: notif.nickname,
                    time: notif.created,
                    timeIso: notif.created,
                    link: {},
                    id: notif.created + '_' + notif.nickname,
                    type: notif.type
                  }));
                  processedCount++;
                }
              }
            } catch (e) {
              console.error('[DOM_DETECT] Error processing notification:', e);
            }
          } else {
            console.log('[DOM_DETECT] Skipping UNKNOWN type notification');
          }
        });
        console.log('[DOM_DETECT] Total processed notifications:', processedCount);
        return result;
      } catch (e) {
        return [];
      }
    }

    window.__ENTRY_POLLER__ = {
      version: VERSION,
      start(){ if (paused){ paused=false; tick(); } },
      pause(){ paused=true; if (timer){ clearTimeout(timer); timer=null; } },
      stop(){ paused=true; if (timer){ clearTimeout(timer); timer=null; } },
      detectDOM: detectDOMNotifications
    };

    document.addEventListener('visibilitychange', () => {
      if (document.hidden) window.__ENTRY_POLLER__.pause(); else window.__ENTRY_POLLER__.start();
    });

    // 알람 페이지 로딩 후 초기 DOM 감지 실행
    if (window.location.href.includes('/alarm')) {
      setTimeout(() => {
        console.log('[POLLER] Initial DOM detection on alarm page');
        try {
          const domNotifs = detectDOMNotifications();
          console.log('[POLLER] Initial DOM detected', domNotifs.length, 'notifications');
        } catch (e) {
          console.error('[POLLER] Initial DOM detection failed:', e.message);
        }
      }, 2000);
    }

    tick();
  })();
""".trimIndent()
    }

    // ✅ 어디서든 target만으로 댓글 1건 상세를 가져오는 GraphQL 수집기
    private val jsCommentDetailByTarget = """
      (() => {
        window.__ENTRY_FETCH_DETAIL__ = async function(target, groupId){
          try {
            const x = sessionStorage.getItem('entry_xToken');
            const c = sessionStorage.getItem('entry_csrfToken');
            const query = `query SELECT_COMMENTS(${'$'}pageParam: PageParam, ${'$'}target: String, ${'$'}groupId: ID){ commentList(pageParam: ${'$'}pageParam, target: ${'$'}target, groupId: ${'$'}groupId){ list{ id user{ nickname profileImage{ filename } } content image{ filename } sticker{ filename } } } }`;
            const body = JSON.stringify({ query, variables: { pageParam: { display: 1, sort: 'created', order: -1 }, target, groupId } });
            const res = await fetch('https://playentry.org/graphql/SELECT_COMMENTS', { method:'POST', headers:{ 'content-type':'application/json','x-client-type':'Client', ...(x?{'x-token':x}:{}) , ...(c?{'csrf-token':c}:{}) }, credentials:'include', body });
            const data = await res.json().catch(()=>({}));
            const cmt = data?.data?.commentList?.list?.[0];
            if(!cmt) return;
            const prof = cmt?.user?.profileImage?.filename ? `https://playentry.org/uploads/${'$'}{cmt.user.profileImage.filename}` : '';
            const st = cmt?.sticker?.filename ? `https://playentry.org/uploads/${'$'}{cmt.sticker.filename}` : '';
            const img = cmt?.image?.filename ? `https://playentry.org/uploads/${'$'}{cmt.image.filename}` : '';
            AndroidApp.postMessage('COMMENT_DETAIL:' + [prof, cmt?.user?.nickname||'', cmt?.content||'', st, img].join('|'));
          } catch(e){}
          }
        })();
    """.trimIndent()

    private fun ensureNotificationSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            
            // 알림 그룹 생성
            val group = NotificationChannelGroup("entify_group", "엔트리 알림")
            nm.createNotificationChannelGroup(group)
            
            // 댓글 알림 채널
            val commentChannel = NotificationChannel(
                commentChannelId,
                "엔트리 댓글 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "엔트리에서 댓글이 달렸을 때 알림을 받습니다"
                enableLights(true)
                lightColor = android.graphics.Color.BLUE
                enableVibration(true)
                setShowBadge(true)
                setGroup(group.id)
            }
            
            // 좋아요 알림 채널
            val likeChannel = NotificationChannel(
                likeChannelId,
                "엔트리 좋아요 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "엔트리에서 좋아요를 받았을 때 알림을 받습니다"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(false)
                setShowBadge(true)
                setGroup(group.id)
            }
            
            // 팔로우 알림 채널
            val followChannel = NotificationChannel(
                followChannelId,
                "엔트리 팔로우 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "엔트리에서 팔로우를 받았을 때 알림을 받습니다"
                enableLights(true)
                lightColor = android.graphics.Color.GREEN
                enableVibration(false)
                setShowBadge(true)
                setGroup(group.id)
            }
            
            // 멘션 알림 채널
            val mentionChannel = NotificationChannel(
                mentionChannelId,
                "엔트리 멘션 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "엔트리에서 멘션을 받았을 때 알림을 받습니다"
                enableLights(true)
                lightColor = android.graphics.Color.YELLOW
                enableVibration(true)
                setShowBadge(true)
                setGroup(group.id)
            }
            
            // 작품 공유 알림 채널
            val shareChannel = NotificationChannel(
                shareChannelId,
                "엔트리 작품 공유 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "팔로우한 사용자가 작품을 공유했을 때 알림을 받습니다"
                enableLights(true)
                lightColor = android.graphics.Color.CYAN
                enableVibration(false)
                setShowBadge(true)
                setGroup(group.id)
            }
            
            // 작품 리메이크 알림 채널
            val remakeChannel = NotificationChannel(
                remakeChannelId,
                "엔트리 작품 리메이크 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "내 작품이 리메이크되었을 때 알림을 받습니다"
                enableLights(true)
                lightColor = android.graphics.Color.MAGENTA
                enableVibration(true)
                setShowBadge(true)
                setGroup(group.id)
            }

            // 시스템 알림 채널
            val systemChannel = NotificationChannel(
                systemChannelId,
                "엔트리 시스템 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "엔트리 시스템 관련 알림을 받습니다"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                setGroup(group.id)
            }
            
            // 모든 채널 생성
            nm.createNotificationChannel(commentChannel)
            nm.createNotificationChannel(likeChannel)
            nm.createNotificationChannel(followChannel)
            nm.createNotificationChannel(mentionChannel)
            nm.createNotificationChannel(shareChannel)
            nm.createNotificationChannel(remakeChannel)
            nm.createNotificationChannel(systemChannel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun buildProjectUrl(projectId: String?): String {
        return if (!projectId.isNullOrBlank()) "https://playentry.org/project/" + projectId else "https://playentry.org/"
    }

    // ✅ JS 문자열로 안전하게 변환
    private fun String.escapeForJs(): String = this
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")

    private fun buildAlarmTokenBootstrapJs(xToken: String, csrfToken: String): String {
        val cleanX = xToken.trim('"', '\'', ' ')
        val cleanCsrf = csrfToken.trim('"', '\'', ' ')
        val safeX = cleanX.escapeForJs()
        val safeCsrf = cleanCsrf.escapeForJs()
        return """
            (function(){
              try{
                sessionStorage.setItem('entry_xToken', '$safeX');
                localStorage.setItem('entry_xToken', '$safeX');
                sessionStorage.setItem('entry_csrfToken', '$safeCsrf');
                localStorage.setItem('entry_csrfToken', '$safeCsrf');
                if ('$safeX') {
                  document.cookie = 'playentry_token=$safeX; path=/; domain=.playentry.org';
                }
              }catch(e){}
            })();
        """.trimIndent()
    }

    @SuppressLint("MissingPermission")
    private fun showLikeNotification(body: String, deepLinkUrl: String, titleHint: String? = null) {
        val safeDeepLink = sanitizeEntryUrl(deepLinkUrl, "https://playentry.org/alarm")
        val safeBody = sanitizeNotificationText(body, 200).ifBlank { "새 알림이 도착했습니다." }
        val safeTitle = sanitizeNotificationText(titleHint, 120).ifBlank { "알림" }
        val key = "LEGACY_LIKE|$safeDeepLink|$safeBody"
        val fixedId = stableIdFrom(key)

        postSimpleNotification(
            key = key,
            title = safeTitle,
            text = safeBody,
            whenMillis = System.currentTimeMillis(),
            openUrl = safeDeepLink,
            smallIconRes = R.drawable.ic_stat_like,
            largeIcon = likeLargeIconBitmap,
            fixedId = fixedId
        )
    }

    // showFollowNotification 함수는 handleFollowNotification으로 통합됨

    @SuppressLint("MissingPermission")
    private fun showCommentNotification(body: String, deepLinkUrl: String) {
        val safeDeepLink = sanitizeEntryUrl(deepLinkUrl, "https://playentry.org/alarm")
        val safeBody = sanitizeNotificationText(body, 200).ifBlank { "새 알림이 도착했습니다." }
        val key = "LEGACY_COMMENT|$safeDeepLink|$safeBody"
        val fixedId = stableIdFrom(key)

        val openIntent = intent?.let { it.clone() as? android.content.Intent } ?: android.content.Intent(this, MainActivity::class.java)
        openIntent.putExtra("openUrl", safeDeepLink)
        // discussId를 URL에서 추출하여 전달 (project는 댓글 API 대상이 다를 수 있으므로 community일 때 우선)
        val did = try {
            val u = android.net.Uri.parse(safeDeepLink)
            u.lastPathSegment // target id (community/{category}/{target} 또는 project/{id})
        } catch (_: Exception) { null }
        if (!did.isNullOrBlank()) openIntent.putExtra("discussId", did)
        openIntent.flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pi = android.app.PendingIntent.getActivity(
            this,
            fixedId,
            openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, commentChannelId)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildLockScreenPlaceholder(commentChannelId, android.R.drawable.ic_dialog_info))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("알림")
            .setContentText(safeBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)

        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                nm.notify(key, fixedId, builder.build())
            } catch (_: SecurityException) {
                // 권한 거부 시 알림 미표시
            }
        }
    }

    private fun stableNotifId(topicId: String?, fallback: String): Int {
        val key = if (!topicId.isNullOrBlank()) "c:$topicId" else "f:$fallback"
        val hash = key.hashCode()
        // 댓글 알림은 1000-1999 범위 사용
        return (abs(hash) % 1000) + 1000
    }
    
    // 다른 알림 타입별 ID 생성 함수들
    private fun getLikeNotifId(topicId: String?, fallback: String): Int {
        val key = if (!topicId.isNullOrBlank()) "l:$topicId" else "lf:$fallback"
        val hash = key.hashCode()
        // 좋아요 알림은 2000-2999 범위 사용
        return (abs(hash) % 1000) + 2000
    }
    
    private fun getFollowNotifId(topicId: String?, fallback: String): Int {
        val key = if (!topicId.isNullOrBlank()) "f:$topicId" else "ff:$fallback"
        val hash = key.hashCode()
        // 팔로우 알림은 3000-3999 범위 사용
        return (abs(hash) % 1000) + 3000
    }
    
    private fun getMentionNotifId(topicId: String?, fallback: String): Int {
        val key = if (!topicId.isNullOrBlank()) "m:$topicId" else "mf:$fallback"
        val hash = key.hashCode()
        // 멘션 알림은 4000-4999 범위 사용
        return (abs(hash) % 1000) + 4000
    }
    
    private fun getSystemNotifId(topicId: String?, fallback: String): Int {
        val key = if (!topicId.isNullOrBlank()) "s:$topicId" else "sf:$fallback"
        val hash = key.hashCode()
        // 시스템 알림은 5000-5999 범위 사용
        return (abs(hash) % 1000) + 5000
    }

    private fun makeInitialsAvatar(name: String, size: Int = 128): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        val hue = (name.hashCode() and 0xFFFFFF) % 360
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.HSVToColor(floatArrayOf(hue.toFloat(), 0.35f, 0.95f)) }
        c.drawCircle(size/2f, size/2f, size/2f, bg)
        val initial = name.trim().take(1).uppercase()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val fm = tp.fontMetrics
        val baseline = size/2f - (fm.ascent + fm.descent) / 2f
        c.drawText(initial, size/2f, baseline, tp)
        return bmp
    }

    private fun releaseRememberedNotificationKey(key: String?) {
        val safeKey = key?.trim().orEmpty()
        if (safeKey.isBlank()) return
        synchronized(postedKeys) {
            postedKeys.remove(safeKey)
        }
    }

    private fun deriveFeedCategoryFromKey(key: String): String {
        return when {
            key.startsWith("COMMENT") || key.startsWith("REPLY") || key.startsWith("SPACE_COMMENT") -> "COMMENT"
            key.startsWith("LIKE") || key.startsWith("PIN") || key.startsWith("LEGACY_LIKE") -> "LIKE"
            key.startsWith("FOLLOW") -> "FOLLOW"
            key.startsWith("REMAKE") -> "REMAKE"
            key.startsWith("SHARE") -> "SHARE"
            else -> "SYSTEM"
        }
    }

    private fun deriveFeedActorFromKey(key: String): String {
        val parts = key.split("|")
        return parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "알림"
    }

    private fun isSpaceNotificationUrl(url: String?): Boolean {
        val normalized = sanitizeEntryUrlOrNull(url) ?: return false
        return EntryOriginPolicy.isTrustedHost(normalized, "space.playentry.org")
    }

    private fun isNonInteractiveNotification(key: String?, url: String?): Boolean {
        val safeKey = key?.trim().orEmpty()
        return safeKey.startsWith("SPACE_", ignoreCase = true) || isSpaceNotificationUrl(url)
    }

    private fun persistNotificationToFeed(
        key: String,
        topicId: String?,
        title: String,
        body: String,
        deepLink: String?,
        createdAt: Long,
        actorImageUrl: String? = null,
        actorHexId: String? = null,
        stickerUrl: String? = null,
        imageUrl: String? = null,
        projectThumbnailUrl: String? = null,
        rawPayload: String? = null,
        template: String? = null
    ) {
        val normalizedKey = key.trim()
        if (normalizedKey.isBlank()) return
        val entityTopicId = topicId?.trim()?.takeIf { it.isNotBlank() } ?: "key:$normalizedKey"
        val category = deriveFeedCategoryFromKey(normalizedKey)
        val actor = deriveFeedActorFromKey(normalizedKey)
        val effectiveTemplate = template?.takeIf { it.isNotBlank() }
            ?: if (category == "REMAKE") "template_5"
            else if (category == "SHARE") "template_28"
            else normalizedKey.substringBefore('|', "SYSTEM")
        val isBlocked = preferenceManager.isUserBlocked(actorHexId, actor)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val resolvedActorImage = actorImageUrl?.takeIf { it.isNotBlank() }
                    ?: notifAvatarUrl[normalizedKey]?.takeIf { it.isNotBlank() }
                    ?: notificationRepository.findActorImageUrlByActor(actor)
                val entity = NotificationEntity(
                        topicId = entityTopicId,
                        category = category,
                        template = effectiveTemplate,
                        actor = actor,
                        actorImageUrl = resolvedActorImage,
                        projectThumbnailUrl = projectThumbnailUrl,
                        actorHexId = actorHexId,
                        title = sanitizeNotificationText(title, 120).ifBlank { "알림" },
                        body = sanitizeNotificationText(body, 200).ifBlank { "새 알림이 도착했습니다." },
                        deepLink = if (isNonInteractiveNotification(normalizedKey, deepLink)) null else sanitizeEntryUrlOrNull(deepLink),
                        stickerUrl = stickerUrl,
                        imageUrl = imageUrl,
                        isBlocked = isBlocked,
                        createdAt = createdAt
                    )
                val safePayload = com.example.entify_again.util.PayloadSanitizer.sanitize(rawPayload)
                notificationRepository.insertIfNew(entity, safePayload)
                if (!resolvedActorImage.isNullOrBlank()) {
                    notificationRepository.updateActorImage(entityTopicId, actor, resolvedActorImage)
                    notificationRepository.updateArchiveActorImage(entityTopicId, resolvedActorImage)
                }
                if (!projectThumbnailUrl.isNullOrBlank()) {
                    notificationRepository.updateProjectThumbnail(entityTopicId, projectThumbnailUrl)
                    notificationRepository.updateArchiveProjectThumbnail(entityTopicId, projectThumbnailUrl)
                }
            }.onFailure {
                AndroidLog.w("NOTIF_DB", "Failed to persist notification key=$normalizedKey: ${it.message}")
            }
        }
    }

    private fun notificationImportanceName(importance: Int): String {
        return when (importance) {
            NotificationManager.IMPORTANCE_NONE -> "NONE"
            NotificationManager.IMPORTANCE_MIN -> "MIN"
            NotificationManager.IMPORTANCE_LOW -> "LOW"
            NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
            NotificationManager.IMPORTANCE_HIGH -> "HIGH"
            NotificationManager.IMPORTANCE_MAX -> "MAX"
            else -> importance.toString()
        }
    }

    private fun canDeliverNotification(channelId: String, logTag: String, key: String? = null): Boolean {
        if (!preferenceManager.isPushNotificationEnabled) {
            AndroidLog.d(logTag, "Push notifications disabled by user in settings; suppress push alert (channel=$channelId, key=$key)")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AndroidLog.w(logTag, "Skip notify - POST_NOTIFICATIONS denied (channel=$channelId, key=$key)")
            return false
        }

        val nmCompat = NotificationManagerCompat.from(this)
        if (!nmCompat.areNotificationsEnabled()) {
            AndroidLog.w(logTag, "Skip notify - app notifications disabled (channel=$channelId, key=$key)")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)
            if (channel == null) {
                AndroidLog.w(logTag, "Skip notify - channel missing (channel=$channelId, key=$key)")
                return false
            }
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                AndroidLog.w(
                    logTag,
                    "Skip notify - channel blocked importance=${notificationImportanceName(channel.importance)} " +
                        "(channel=$channelId, key=$key)"
                )
                return false
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun postOrUpdateCommentNotification(
        notifId: Int,
        user: String,
        content: String,
        deepLinkUrl: String,
        avatar: Bitmap?
    ) {
        val safeDeepLink = sanitizeEntryUrl(deepLinkUrl, "https://playentry.org/alarm")
        val openIntent = intent?.let { it.clone() as? Intent } ?: Intent(this, MainActivity::class.java)
        openIntent.putExtra("openUrl", safeDeepLink)
        openIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pi = android.app.PendingIntent.getActivity(
            this,
            notifId,
            openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconBmp = avatar ?: makeInitialsAvatar(user)
        val person = NotificationPerson.Builder()
            .setName(user.ifBlank { "새 댓글" })
            .setIcon(IconCompat.createWithBitmap(iconBmp))
            .build()

        val style = NotificationCompat.MessagingStyle(person)
            .addMessage(content.ifBlank { "(내용 없음)" }, System.currentTimeMillis(), person)

        val builder = NotificationCompat.Builder(this, commentChannelId)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildLockScreenPlaceholder(commentChannelId, android.R.drawable.ic_dialog_info))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .setLargeIcon(iconBmp)
            .setContentTitle("알림")
            .setContentText("$user: $content")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)

        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try { nm.notify(notifId, builder.build()) } catch (_: SecurityException) {}
        }

        // Publish/refresh a dynamic shortcut for conversation so the header avatar is used by the system
        val shortcutId = "entry-conv-" + (user.ifBlank { "anon" })
        try {
            val shortcutIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("openUrl", safeDeepLink)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val shortcut = ShortcutInfoCompat.Builder(this, shortcutId)
                .setShortLabel(user.ifBlank { "새 댓글" })
                .setIcon(IconCompat.createWithBitmap(iconBmp))
                .setLongLived()
                .setIntent(shortcutIntent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        } catch (_: Exception) { /* ignore shortcut errors */ }
    }

    @SuppressLint("MissingPermission")
    private fun showRichCommentNotification(
        body: String,
        deepLinkUrl: String,
        bigPicture: Bitmap?,
        largeIcon: Bitmap?
    ) {
        val safeDeepLink = sanitizeEntryUrl(deepLinkUrl, "https://playentry.org/alarm")
        val safeBody = sanitizeNotificationText(body, 200).ifBlank { "새 알림이 도착했습니다." }
        val key = "LEGACY_RICH_COMMENT|$safeDeepLink|$safeBody"
        val fixedId = stableIdFrom(key)
        val openIntent = intent?.let { it.clone() as? android.content.Intent } ?: android.content.Intent(this, MainActivity::class.java)
        openIntent.putExtra("openUrl", safeDeepLink)
        openIntent.flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pi = android.app.PendingIntent.getActivity(
            this,
            fixedId,
            openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, commentChannelId)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildLockScreenPlaceholder(commentChannelId, android.R.drawable.ic_dialog_info))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("알림")
            .setContentText(safeBody)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)

        if (bigPicture != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPicture)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
        }
        largeIcon?.let { builder.setLargeIcon(it) }

        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                nm.notify(key, fixedId, builder.build())
            } catch (_: SecurityException) {}
        }
    }

    // ✅ 알림 불러오기
    @Suppress("unused")
    private fun getEntryNotifications(xToken: String, csrfToken: String, callback: (String) -> Unit) {
        val query = """
        query SELECT_TOPICS(${'$'}pageParam: PageParam) {
          topicList(pageParam: ${'$'}pageParam) {
            list {
              template
              created
              link { category target }
            }
          }
        }
        """.trimIndent()

        val bodyJson = """{
          "query": "$query",
          "variables": { "pageParam": { "display": 5 } }
        }""".trimIndent()

        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://playentry.org/graphql/SELECT_TOPICS")
            .addHeader("accept", "*/*")
            .addHeader("content-type", "application/json")
            .addHeader("x-token", xToken)
            .addHeader("csrf-token", csrfToken)
            .addHeader("x-client-type", "Client")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("❌ 요청 실패: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyText = SafeResponseReader.readText(response.body)
                    ?: return callback("⚠️ 응답이 비어 있거나 안전 한도를 초과했습니다")
                try {
                    val list = JSONObject(bodyText)
                        .getJSONObject("data")
                        .getJSONObject("topicList")
                        .getJSONArray("list")

                    val sb = StringBuilder("🔔 최근 알림 5개\n\n")
                    for (i in 0 until list.length()) {
                        val item = list.getJSONObject(i)
                        sb.append("• ${item.optString("template")}\n🕒 ${item.optString("created")}\n\n")
                    }
                    callback(sb.toString())
                } catch (e: Exception) {
                    callback("⚠️ 파싱 오류: ${e.message}\n\n$bodyText")
                }
            }
        })
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        val urlToOpen = intent?.getStringExtra("openUrl")
        if (!urlToOpen.isNullOrBlank()) {
            pendingOpenUrl = urlToOpen
            // WebView가 준비되면 자동으로 처리되도록 pendingOpenUrl에 저장
            // WebView가 이미 준비된 경우 즉시 처리
            mainWebView?.let { 
                handleNotificationUrl(urlToOpen)
                pendingOpenUrl = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppForeground = true
        isAppInForeground = true
        EntryPollingForegroundService.setMainAppForeground(this, true)
        syncForegroundPollingService("on_resume")
        stopAlarmBackgroundPoller("on_resume_reinit")
        if (!preferenceManager.logoutPending && hasStoredTokens()) {
            startAlarmBackgroundPoller(forceReload = true)
        }

        if (preferenceManager.logoutPending) {
            mainWebView?.post { mainWebView?.loadUrl("https://playentry.org/signin") }
        }
        
        // 로그인 상태 재확인: 필요 시에만 스타트업 플로우 재개
        if (!notificationLogicStarted) {
            scheduleStartupFlow("resume")
        }
        
        mainWebView?.post { mainWebView?.evaluateJavascript("window.__ENTRY_POLLER__ && __ENTRY_POLLER__.start()", null) }
        checkBatteryOptimizationNotice()
    }

    private fun checkBatteryOptimizationNotice() {
        if (preferenceManager.hasPromptedBatteryOptimization) return
        if (!com.example.entify_again.util.BatteryOptimizationHelper.isBatteryOptimizationIgnored(this)) {
            preferenceManager.hasPromptedBatteryOptimization = true
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("배터리 최적화 설정 안내")
                .setMessage("휴대폰 화면이 꺼진 상태에서도 10초 주기로 엔트리 알림을 정상 감지하려면 배터리 사용량 제한을 '제한 없음'으로 설정하는 것이 권장됩니다. 지금 설정하시겠습니까?\n\n(강제 사항이 아니며 나중에 설정 메뉴에서도 변경하실 수 있습니다.)")
                .setPositiveButton("설정하기") { _, _ ->
                    com.example.entify_again.util.BatteryOptimizationHelper.requestIgnoreBatteryOptimization(this)
                }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    override fun onPause() {
        super.onPause()
        isAppForeground = false
        isAppInForeground = false
        EntryPollingForegroundService.setMainAppForeground(this, false)
        mainWebView?.post { mainWebView?.evaluateJavascript("window.__ENTRY_POLLER__ && __ENTRY_POLLER__.pause()", null) }
        stopAlarmBackgroundPoller("on_pause")
        // 백그라운드에서는 Foreground Service가 10초 폴링을 담당한다.
        syncForegroundPollingService("on_pause")
    }

    override fun onStop() {
        super.onStop()
        isAppForeground = false
        isAppInForeground = false
        EntryPollingForegroundService.setMainAppForeground(this, false)
    }

    override fun onDestroy() {
        isAppForeground = false
        isAppInForeground = false
        EntryPollingForegroundService.setMainAppForeground(this, false)
        stopAlarmBackgroundPoller("on_destroy")
        listOfNotNull(
            mainWebView,
            hiddenWebView,
            alarmWebView,
            tokenWebView,
            followGraphqlWebView,
            shareGraphqlWebView,
            profileWebView
        ).distinct().forEach(::destroySessionWebView)
        mainWebView = null
        hiddenWebView = null
        alarmWebView = null
        tokenWebView = null
        followGraphqlWebView = null
        shareGraphqlWebView = null
        profileWebView = null
        super.onDestroy()
    }

    @Composable
    private fun RemoteImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
        val cleanUrl = remember(url) { sanitizeUrl(url) }
        val isSvg = remember(cleanUrl) { cleanUrl.lowercase().endsWith(".svg") }
        var imageBitmap by remember(cleanUrl) { mutableStateOf<ImageBitmap?>(null) }

        if (isSvg) {
            AndroidView(
                modifier = modifier,
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setBackgroundColor(0x00000000)
                        AndroidLog.d("RemoteImage", "<svg> load via WebView: " + cleanUrl)
                        val html = """
                            <html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>
                            <body style='margin:0; background:transparent;'>
                              <img src='${cleanUrl}' style='width:100%;height:100%;object-fit:contain;' />
                            </body></html>
                        """.trimIndent()
                        loadDataWithBaseURL("https://playentry.org", html, "text/html", "utf-8", null)
                    }
                }
            )
        } else {
            LaunchedEffect(cleanUrl) {
                AndroidLog.d("RemoteImage", "begin load: " + cleanUrl)
                imageBitmap = loadBitmap(cleanUrl)
                AndroidLog.d("RemoteImage", "end load: " + (imageBitmap != null))
            }
            imageBitmap?.let {
                Image(bitmap = it, contentDescription = null, modifier = modifier, contentScale = contentScale)
            }
            if (imageBitmap == null && !isSvg) {
                // 네트워크 지연/디코드 실패 대비 간단한 WebView 폴백
                AndroidView(
                    modifier = modifier,
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setBackgroundColor(0x00000000)
                            AndroidLog.d("RemoteImage", "fallback <img> via WebView: " + cleanUrl)
                            val html = """
                                <html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>
                                <body style='margin:0; background:transparent;'>
                                  <img src='${cleanUrl}' style='width:100%;height:100%;object-fit:contain;' />
                                </body></html>
                            """.trimIndent()
                            loadDataWithBaseURL("https://playentry.org", html, "text/html", "utf-8", null)
                        }
                    }
                )
            }
        }
    }

    private suspend fun loadBitmap(url: String, sizePx: Int = 128): ImageBitmap? {
        val clean = sanitizeImageUrl(url)
        if (clean.isBlank()) return null
        val normalized = sanitizeImageUrl(resolveUploadUrlIfNeeded(clean))
        if (normalized.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(normalized)
                    .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .addHeader("Referer", "https://playentry.org/")
                    .addHeader("User-Agent", "Mozilla/5.0 (Android) Entify/1.0")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val mime = (resp.header("Content-Type") ?: "").lowercase()
                    if (mime.isNotBlank() && !isAllowedImageMime(mime)) {
                        AndroidLog.w("RemoteImage", "Blocked non-image response mime=$mime url=$normalized")
                        return@use null
                    }
                    val isSvg = mime.contains("svg") || normalized.lowercase().endsWith(".svg")

                    if (isSvg) {
                        val svgBytes = resp.body?.let { SafeImageDecoder.readBounded(it) } ?: return@use null
                        if (!looksLikeSvg(svgBytes)) return@use null
                        val b64 = android.util.Base64.encodeToString(svgBytes, android.util.Base64.NO_WRAP)
                        return@use withContext(Dispatchers.Main) {
                            val w = WebView(this@MainActivity).apply {
                                settings.javaScriptEnabled = false
                                settings.loadsImagesAutomatically = true
                                settings.blockNetworkImage = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                setBackgroundColor(0x00000000)
                            }
                            val html = """
                                <html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>
                                <body style='margin:0;background:transparent;display:flex;align-items:center;justify-content:center'>
                                  <img id='img' src='data:image/svg+xml;base64,${b64}' style='width:100%;height:100%;object-fit:cover;border-radius:50%'/>
                                </body></html>
                            """.trimIndent()
                            try {
                                val result = CompletableDeferred<Bitmap?>()
                                w.measure(
                                    View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
                                )
                                w.layout(0, 0, sizePx, sizePx)
                                w.webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        try {
                                            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                                            val canvas = AndroidCanvas(bmp)
                                            view?.draw(canvas)
                                            result.complete(bmp)
                                        } catch (e: Exception) { result.complete(null) }
                                    }
                                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                        result.complete(null)
                                        return true
                                    }
                                }
                                w.loadDataWithBaseURL("https://playentry.org", html, "text/html", "utf-8", null)
                                val bmp = withTimeoutOrNull(5000) { result.await() }
                                bmp?.also { avatarCache.put(normalized, it) }?.asImageBitmap()
                            } finally {
                                try { w.destroy() } catch (_: Exception) {}
                            }
                        }
                    } else {
                        val bytes = resp.body?.let { SafeImageDecoder.readBounded(it) }
                        if (bytes == null || !looksLikeRasterImage(bytes)) return@use null
                        val decoded = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (decoded != null) {
                            avatarCache.put(normalized, decoded)
                            return@use decoded.asImageBitmap()
                        }
                        // 폴백: 일반 이미지 디코드 실패 시 WebView 렌더
                        return@use withContext(Dispatchers.Main) {
                            val w = WebView(this@MainActivity).apply {
                                settings.javaScriptEnabled = false
                                settings.loadsImagesAutomatically = true
                                settings.blockNetworkImage = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                setBackgroundColor(0x00000000)
                            }
                            val html = """
                                <html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'></head>
                                <body style='margin:0;background:transparent;display:flex;align-items:center;justify-content:center'>
                                  <img id='img' src='${normalized}' style='width:100%;height:100%;object-fit:cover;border-radius:50%'/>
                                </body></html>
                            """.trimIndent()
                            try {
                                val result = CompletableDeferred<Bitmap?>()
                                w.measure(
                                    View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
                                )
                                w.layout(0, 0, sizePx, sizePx)
                                w.webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        try {
                                            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                                            val canvas = AndroidCanvas(bmp)
                                            view?.draw(canvas)
                                            result.complete(bmp)
                                        } catch (e: Exception) { result.complete(null) }
                                    }
                                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                        result.complete(null)
                                        return true
                                    }
                                }
                                w.loadDataWithBaseURL("https://playentry.org", html, "text/html", "utf-8", null)
                                val bmp = withTimeoutOrNull(5000) { result.await() }
                                bmp?.also { avatarCache.put(normalized, it) }?.asImageBitmap()
                            } finally {
                                try { w.destroy() } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }.onFailure { AndroidLog.d("RemoteImage", "load failed: ${'$'}{it.message}") }.getOrNull()
        }
    }

    private suspend fun loadAvatarBitmap(url: String, sizePx: Int = 128): Bitmap? {
        val clean = sanitizeImageUrl(url)
        if (clean.isBlank()) return null
        val normalized = sanitizeImageUrl(resolveUploadUrlIfNeeded(clean))
        if (normalized.isBlank()) return null
        avatarCache.get(normalized)?.let { return it }
        val bitmap = loadBitmap(normalized, sizePx)?.asAndroidBitmap() ?: return null
        // ✅ 무조건 원형으로 변환하여 반환
        val circular = getCircularBitmap(bitmap)
        avatarCache.put(normalized, circular)
        return circular
    }

    // ✅ dataURL에서 Bitmap 로드 (Base64 디코딩)
    private suspend fun loadBitmapFromDataUrl(dataUrl: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (!isSafeImageDataUrl(dataUrl)) return@withContext null
                // data:image/jpeg;base64,{base64data} 파싱
                val base64Data = dataUrl.substringAfter("base64,", "")
                if (base64Data.isBlank()) {
                    AndroidLog.w("SHARE_PROFILE", "Invalid dataURL format")
                    return@withContext null
                }

                // Base64 디코딩
                val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

                if (bitmap == null) {
                    AndroidLog.w("SHARE_PROFILE", "Failed to decode bitmap from dataURL")
                    return@withContext null
                }

                // ✅ 원형 처리 (이미 JS에서 256x256으로 최적화되었지만 원형 처리는 필요)
                getCircularBitmap(bitmap)
            } catch (e: Exception) {
                AndroidLog.e("SHARE_PROFILE", "Error loading bitmap from dataURL", e)
                null
            }
        }
    }

    // ✅ URL에서 Bitmap 로드 (동기 방식, SVG 대응)
    private fun loadBitmapFromUrl(url: String, targetSize: Int = 128): Bitmap? {
        return try {
            val clean = sanitizeImageUrl(url)
            if (clean.isBlank()) return null
            val bitmap = when {
                clean.startsWith("data:", ignoreCase = true) -> decodeBitmapFromDataUrl(clean, targetSize)
                else -> requestBitmapWithSvgSupport(clean, targetSize)
            } ?: return null
            getCircularBitmap(bitmap, targetSize)
        } catch (e: Exception) {
            AndroidLog.e("PROFILE", "Failed to load bitmap from URL: ${e.message}")
            null
        }
    }

    private fun decodeBitmapFromDataUrl(dataUrl: String, _targetSize: Int): Bitmap? {
        return try {
            if (!isSafeImageDataUrl(dataUrl)) return null
            val base64Data = dataUrl.substringAfter("base64,", "")
            if (base64Data.isBlank()) return null
            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (_: Exception) { null }
    }

    private fun requestBitmapWithSvgSupport(url: String, targetSize: Int): Bitmap? {
        val safeUrl = sanitizeImageUrl(url)
        if (safeUrl.isBlank()) return null
        val request = Request.Builder()
            .url(safeUrl)
            .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .addHeader("Referer", "https://playentry.org/")
            .addHeader("User-Agent", "Mozilla/5.0 (Android) Entify/1.0")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyBytes = resp.body?.let { SafeImageDecoder.readBounded(it) } ?: return null
                val contentType = resp.header("content-type").orEmpty()
                if (contentType.isNotBlank() && !isAllowedImageMime(contentType)) return null
                val isSvg = safeUrl.contains(".svg", true) ||
                        contentType.contains("svg", true) ||
                        looksLikeSvg(bodyBytes)
                val decoded = if (isSvg) {
                    if (!SafeImageDecoder.isSafeSvg(bodyBytes)) return null
                    renderSvgToBitmap(bodyBytes, targetSize, targetSize)
                        ?: renderSvgWithWebView(String(bodyBytes, Charsets.UTF_8), targetSize)
                } else {
                    if (!looksLikeRasterImage(bodyBytes)) return null
                    SafeImageDecoder.decodeRaster(bodyBytes, targetSize)
                }
                decoded
            }
        } catch (e: Exception) {
            AndroidLog.e("PROFILE", "requestBitmapWithSvgSupport failed: ${e.message}")
            null
        }
    }

    private fun looksLikeSvg(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return try {
            val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8)
            head.contains("<svg", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun renderSvgWithWebView(svgXml: String, size: Int, timeoutMs: Long = 4000L): Bitmap? {
        var result: Bitmap? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val webView = WebView(this).apply {
                    settings.javaScriptEnabled = false
                    settings.loadsImagesAutomatically = true
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                }
                val html = """
                    <html>
                      <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                        <style>
                          html, body { margin:0; padding:0; background:transparent; }
                          body { display:flex; align-items:center; justify-content:center; }
                          svg { width:100%; height:100%; }
                        </style>
                      </head>
                      <body>$svgXml</body>
                    </html>
                """.trimIndent()
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, size, size)
                webView.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        result = null
                        latch.countDown()
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        try {
                            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                            val canvas = AndroidCanvas(bmp)
                            view?.draw(canvas)
                            result = bmp
                        } catch (_: Exception) {
                            result = null
                        } finally {
                            try { view?.destroy() } catch (_: Exception) {}
                            latch.countDown()
                        }
                    }
                }
                webView.loadDataWithBaseURL(
                    "https://playentry.org",
                    html,
                    "text/html",
                    "utf-8",
                    null
                )
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return result
        }
        return null
    }

    // ✅ 비트맵을 원형으로 크롭하는 함수 (targetSize로 리사이즈)
    private fun getCircularBitmap(bitmap: Bitmap, targetSize: Int = 128): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)

        // 1단계: 정사각형으로 크롭
        val cropped = if (bitmap.width != size || bitmap.height != size) {
            val left = (bitmap.width - size) / 2
            val top = (bitmap.height - size) / 2
            Bitmap.createBitmap(bitmap, left, top, size, size)
        } else {
            bitmap
        }

        // 2단계: targetSize로 리사이즈 (레이아웃 안정성을 위해)
        val resized = if (size != targetSize) {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        } else {
            cropped
        }

        // 3단계: 원형 마스크 적용
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            color = AndroidColor.WHITE
        }

        val rect = Rect(0, 0, targetSize, targetSize)
        val centerX = targetSize / 2f
        val centerY = targetSize / 2f
        val radius = targetSize / 2f

        // 원형 마스크 그리기
        canvas.drawCircle(centerX, centerY, radius, paint)

        // SRC_IN 모드로 이미지 합성 (마스크 내부만 표시)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(resized, null, rect, paint)

        return output
    }

    private fun defaultProfileImage(): ImageBitmap? {
        return try {
            val bmp = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            bmp?.asImageBitmap()
        } catch (_: Exception) { null }
    }

    // ✅ 성능 최적화: GraphQL 컨텍스트에 불필요한 리소스 차단
    // 이미지, CSS, 폰트, 비디오, 오디오, 분석 스크립트 등을 차단하여 페이지 로드 속도 향상
    private fun shouldBlockResourceForGraphql(url: String): Boolean {
        // GraphQL 요청은 항상 통과
        if (url.contains("/graphql")) return false
        // 메인 HTML은 통과
        if (url.contains("playentry.org/profile") || url.contains("playentry.org/project")) return false
        // _next 앱 번들 중 필수만 통과 (JS만)
        if (url.contains("/_next/") && !url.endsWith(".js")) return true

        // 차단할 리소스 패턴
        val blockPatterns = listOf(
            // 이미지
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp",
            // 스타일
            ".css",
            // 폰트
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            // 미디어
            ".mp4", ".webm", ".mp3", ".ogg", ".wav",
            // 분석/광고
            "google-analytics", "googletagmanager", "facebook", "analytics",
            "doubleclick", "adservice", "ads.", "/ad/", "/ads/",
            // 엔트리 불필요 리소스
            "/uploads/", "/thumb/", "/image/", "/video/", "/sound/",
            "cloudfront.net", "sentry.io", "hotjar"
        )

        return blockPatterns.any { url.contains(it) }
    }

    private fun sanitizeUrl(raw: String): String {
        var s = raw.trim()
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length - 1)
        }
        // strip css url(...) wrapper if present
        if (s.startsWith("url(", ignoreCase = true) && s.endsWith(")")) {
            s = s.substring(4, s.length - 1).trim()
            if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
                s = s.substring(1, s.length - 1)
            }
        }
        s = s.replace("&quot;", "").replace("\n", "").trim()
        if (s.startsWith("//")) s = "https:" + s
        if (s.startsWith("/")) s = "https://playentry.org" + s
        // minimal encoding for spaces/parentheses
        s = s.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
        return s
    }

    private fun isAllowedEntryHost(hostRaw: String?): Boolean {
        val host = hostRaw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return host == "playentry.org" || host.endsWith(".playentry.org")
    }

    private fun isAllowedImageHost(hostRaw: String?): Boolean {
        val host = hostRaw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return isAllowedEntryHost(host) || host == "pstatic.net" || host.endsWith(".pstatic.net")
    }

    private fun sanitizeEntryUrlOrNull(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val normalized = sanitizeUrl(rawUrl)
        if (normalized.isBlank()) return null
        val lower = normalized.lowercase(Locale.ROOT)
        if (
            lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("intent:")
        ) return null
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "https") return null
        if (!isAllowedEntryHost(uri.host)) return null
        val path = uri.encodedPath.orEmpty()
        if (path.contains("..")) return null
        return normalized
    }

    private fun sanitizeEntryUrl(rawUrl: String?, fallback: String = "https://playentry.org/alarm"): String {
        return sanitizeEntryUrlOrNull(rawUrl) ?: fallback
    }

    private fun isAllowedImageMime(mimeRaw: String): Boolean {
        val mime = mimeRaw.lowercase(Locale.ROOT)
        return mime.startsWith("image/") || mime.contains("image/svg+xml")
    }

    private fun isLikelyImagePath(pathRaw: String): Boolean {
        val path = pathRaw.lowercase(Locale.ROOT)
        if (path.isBlank() || path.contains("..")) return false
        if (path.contains("/uploads/") || path.contains("/thumb/") || path.contains("/image/")) return true
        return path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif") ||
            path.endsWith(".bmp") ||
            path.endsWith(".svg") ||
            path.endsWith(".avif")
    }

    private fun isSafeImageDataUrl(dataUrl: String): Boolean {
        val lower = dataUrl.lowercase(Locale.ROOT)
        if (!lower.startsWith("data:image/")) return false
        if (!lower.contains(";base64,")) return false
        if (dataUrl.length > 1_500_000) return false
        if (!Regex("^data:image/(png|jpe?g|webp|gif|bmp|svg\\+xml);base64,[a-z0-9+/=\\r\\n]+$",
                RegexOption.IGNORE_CASE).matches(dataUrl)) {
            return false
        }
        return true
    }

    private fun isSafeImageUrl(rawUrl: String): Boolean {
        return sanitizeImageUrl(rawUrl).isNotBlank()
    }

    private fun sanitizeImageUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        val normalized = sanitizeUrl(rawUrl)
        if (normalized.isBlank()) return ""
        val lower = normalized.lowercase(Locale.ROOT)
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("intent:")) {
            return ""
        }
        if (lower.startsWith("data:")) {
            return if (isSafeImageDataUrl(normalized)) normalized else ""
        }
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "https") return ""
        if (!isAllowedImageHost(uri.host)) return ""
        val path = uri.path.orEmpty()
        if (!isLikelyImagePath(path)) return ""
        return normalized
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

    private suspend fun resolveUploadUrlIfNeeded(url: String): String {
        try {
            val safe = sanitizeImageUrl(url)
            if (safe.isBlank()) return ""
            val u = Uri.parse(safe)
            val hostOk = EntryOriginPolicy.isTrusted(u.toString())
            val path = u.path ?: ""
            if (!hostOk || !path.contains("/uploads/")) return safe
            val last = path.substringAfterLast('/')
            if (last.contains('.')) return safe // already has extension
            val idMatch = Regex("([a-z0-9]{8,})", RegexOption.IGNORE_CASE).find(last) ?: return safe
            val id = idMatch.value
            if (id.length < 4) return safe
            val p1 = id.substring(0, 2)
            val p2 = id.substring(2, 4)
            val base = "https://playentry.org/uploads/$p1/$p2/$id"
            val tryExts = listOf("jpeg", "jpg", "png", "svg", "webp")
            for (ext in tryExts) {
                val candidate = "$base.$ext"
                val req = Request.Builder()
                    .url(candidate)
                    .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .addHeader("Referer", "https://playentry.org/")
                    .addHeader("User-Agent", "Mozilla/5.0 (Android) Entify/1.0")
                    .method("HEAD", null)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful && isAllowedImageMime(resp.header("content-type").orEmpty())) return candidate
                }
            }
            return "$base.jpeg"
        } catch (_: Exception) {
            return ""
        }
    }

    // 큐에 댓글 알림 추가 (새로운 통합 큐 시스템 사용)
    private fun enqueueComment(link: JSONObject?, user: String, topicId: String, deepLink: String, created: String) {
        if (DRY_RUN_NOTIF_ONLY) {
            if (dryRunSeen.add(topicId)) {
                dryRunReceived.add(topicId)
                AndroidLog.d("DRYRUN", "enqueue collected (dry-run): topic=$topicId user=$user created=$created")
            } else {
                AndroidLog.d("DRYRUN", "enqueue skip dup (dry): $topicId")
            }
            return
        }
        val linkJson = link?.toString() ?: "{}"
        val linkTarget = link?.optString("target")
        val linkCategory = link?.optString("category")
        enqueueNotif(NotifTask.Comment(CommentNotifItem(linkJson, user, topicId, deepLink, created, "", linkTarget, linkCategory)))
        AndroidLog.d("CommentQueue", "Enqueued comment: $user, queue size: ${notifQueue.size}")
    }

    // ✅ 기존 processCommentQueue는 새로운 통합 큐 시스템으로 대체됨
    // processNextNotif()에서 NotifTask.Comment를 처리할 때 processComment() 호출

    private fun onCommentProcessed() {
        AndroidLog.d("QUEUE", "Comment processed")
        
        // DOM 감지 재개
                mainWebView?.evaluateJavascript(
            "window.__ENTRY_BLOCK_DOM = 0; if (window.__ENTRY_POLLER__ && window.__ENTRY_POLLER__.start) window.__ENTRY_POLLER__.start();",
                    null
                )
        
        // ✅ 새로운 큐 시스템으로 다음 작업 처리
        processNextNotif()
    }

    // 고유 알림 키 생성
    private fun notifKey(
        type: String,          // FOLLOW / LIKE_PROJECT / LIKE_COMMENT / COMMENT / COMMUNITY_REPLY / COMMUNITY_LIKE
        nickname: String?,
        messageTail: String,   // "님이 나의 댓글을 좋아합니다." 같이 카테고리별 끝문장
        targetId: String?,     // projectId, postId 등
        createdText: String?   // "3 분 전" 또는 ISO 시간
    ): String {
        return listOf(
            type.trim(),
            nickname.orEmpty().trim(),
            messageTail.trim(),
            targetId.orEmpty().trim(),
            createdText.orEmpty().trim()
        ).joinToString("|")
    }

    // 해시 -> 양수로 정규화해서 notificationId / requestCode로 재사용
    // ✅ 개선: 더 나은 해시 분산을 위해 MurmurHash 스타일 적용
    private fun stableIdFrom(key: String): Int {
        var hash = key.hashCode()
        // 해시 분산 개선 (MurmurHash finalizer)
        hash = hash xor (hash ushr 16)
        hash = (hash * 0x85ebca6b.toInt())
        hash = hash xor (hash ushr 13)
        hash = (hash * 0xc2b2ae35.toInt())
        hash = hash xor (hash ushr 16)
        return hash and 0x7fffffff
    }

    // 실제로 알림을 띄운 후에만 seen 기록
    private fun markSeenAfterNotify(key: String) {
        if (DRY_RUN_NOTIF_ONLY) return
        val now = System.currentTimeMillis()
        seen.edit().putLong(key, now).apply()
    }

    private fun seenTopicKey(topicId: String): String = "topic:$topicId"

    private fun shouldRememberNotifications(): Boolean = preferenceManager.rememberNotifications

    private fun syncNotificationMemoryPolicy(force: Boolean = false) {
        val generation = preferenceManager.notificationMemoryGeneration
        val generationChanged = generation != lastAppliedNotificationMemoryGeneration
        if (!force && !generationChanged) return
        lastAppliedNotificationMemoryGeneration = generation
        if (generationChanged || force) {
            synchronized(postedKeys) { postedKeys.clear() }
            synchronized(processedTopicIds) { processedTopicIds.clear() }
            synchronized(commentTagByTopicAndKind) { commentTagByTopicAndKind.clear() }
            synchronized(commentTagByTopic) { commentTagByTopic.clear() }
            synchronized(commentKindByTopic) { commentKindByTopic.clear() }
            synchronized(contentTitleByTopic) { contentTitleByTopic.clear() }
            synchronized(activeCommentNotifications) { activeCommentNotifications.clear() }
        }
    }

    private fun rememberNotificationKey(key: String): Boolean {
        if (key.isBlank()) return false
        syncNotificationMemoryPolicy()
        return postedKeys.add(key)
    }

    private fun isTopicSeen(topicId: String): Boolean {
        syncNotificationMemoryPolicy()
        val id = topicId.trim()
        if (id.isBlank()) return false

        if (preferenceManager.isTopicSeen(id)) return true

        // DB에 이미 저장되어 있거나(이미 알림 처리됨) 이미 읽은 알림은 다시 띄우지 않음
        val existsOrReadInDb = runCatching {
            kotlinx.coroutines.runBlocking {
                notificationRepository.existsByTopicId(id) || notificationRepository.isReadByTopicId(id)
            }
        }.getOrDefault(false)
        if (existsOrReadInDb) {
            preferenceManager.markTopicSeen(id)
            return true
        }

        if (!shouldRememberNotifications()) return false
        val key = seenTopicKey(id)
        if (!seen.contains(key)) return false
        val savedAt = seen.getLong(key, 0L)
        if (savedAt <= 0L) {
            seen.edit().remove(key).apply()
            return false
        }
        val ageMs = System.currentTimeMillis() - savedAt
        if (ageMs < 0L || ageMs > seenTopicTtlMs) {
            seen.edit().remove(key).apply()
            return false
        }
        return true
    }

    private fun markTopicSeen(topicId: String?) {
        if (DRY_RUN_NOTIF_ONLY) return
        syncNotificationMemoryPolicy()
        val id = topicId?.trim().orEmpty()
        if (id.isBlank()) return
        preferenceManager.markTopicSeen(id)
        if (shouldRememberNotifications()) {
            seen.edit().putLong(seenTopicKey(id), System.currentTimeMillis()).apply()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { notificationRepository.markReadByTopicId(id) }
        }
    }

    private fun clearSeenTopics(topicIds: Collection<String>) {
        syncNotificationMemoryPolicy()
        if (!shouldRememberNotifications()) return
        if (topicIds.isEmpty()) return
        val editor = seen.edit()
        var removed = 0
        topicIds.forEach { rawId ->
            val id = rawId.trim()
            if (id.isBlank()) return@forEach
            editor.remove(seenTopicKey(id))
            removed++
        }
        if (removed > 0) {
            editor.apply()
            AndroidLog.w("NEW_NOTIF_SKIP", "Cleared stale seen topics for retry: count=$removed")
        }
    }

    // 세션 중 중복 방지용 키 세트
    private val postedKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private data class CommentNotificationState(
        val tag: String,
        val notifId: Int,
        val title: String,
        val text: String,
        val openUrl: String,
        val avatarName: String,
        val whenMillis: Long,
        var lastProfileUrl: String? = null,
        var commentPreview: String? = null
    )
    private val activeCommentNotifications = mutableMapOf<String, CommentNotificationState>()
    private val commentLoadedBitmaps = mutableMapOf<String, Bitmap>()
    private val commentTagByTopicAndKind = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 200
        }
    }
    private val commentTagByTopic = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 200
        }
    }
    private val commentKindByTopic = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 200
        }
    }
    private val contentTitleByTopic = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 300
        }
    }
    private fun rememberCommentTag(topicId: String?, kind: String, tag: String) {
        val id = topicId?.takeIf { it.isNotBlank() } ?: return
        val k = "${id}|${kind.trim().uppercase(Locale.ROOT)}"
        commentTagByTopicAndKind[k] = tag
        commentTagByTopic[id] = tag
    }
    private fun findCommentTag(topicId: String?, kind: String): String? {
        val id = topicId?.takeIf { it.isNotBlank() } ?: return null
        val k = "${id}|${kind.trim().uppercase(Locale.ROOT)}"
        return commentTagByTopicAndKind[k]
    }
    private fun findAnyCommentTag(topicId: String?): String? {
        val id = topicId?.takeIf { it.isNotBlank() } ?: return null
        return commentTagByTopic[id]
    }
    private fun rememberCommentKind(topicId: String?, kind: String) {
        val key = topicId?.takeIf { it.isNotBlank() } ?: return
        commentKindByTopic[key] = kind
    }
    private fun findCommentKind(topicId: String?): String? {
        val key = topicId?.takeIf { it.isNotBlank() } ?: return null
        return commentKindByTopic[key]
    }
    private fun rememberContentTitle(topicId: String?, title: String?) {
        val key = topicId?.takeIf { it.isNotBlank() } ?: return
        val safe = sanitizeNotificationText(title, 120)
        if (safe.isBlank()) return
        contentTitleByTopic[key] = safe
    }
    private fun findContentTitle(topicId: String?): String? {
        val key = topicId?.takeIf { it.isNotBlank() } ?: return null
        return contentTitleByTopic[key]
    }
    // 최근 처리한 topicId 집합(중복 알림 방지, 메모리 제한)
    private val processedTopicIds = java.util.LinkedHashSet<String>(512)
    private fun markTopicProcessed(dedupKey: String): Boolean {
        if (dedupKey.isBlank()) return true
        syncNotificationMemoryPolicy()
        synchronized(processedTopicIds) {
            if (processedTopicIds.contains(dedupKey)) return false
            processedTopicIds.add(dedupKey)
            if (processedTopicIds.size > 500) {
                val it = processedTopicIds.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            return true
        }
    }

    private fun buildDedupKey(topicId: String, template: String, created: String): String {
        val id = topicId.trim()
        if (id.isBlank()) return ""
        val tpl = template.trim().lowercase(Locale.ROOT)
        val time = created.trim()
        return listOf(id, tpl, time).joinToString("|")
    }

    // 댓글 내용까지 완전히 수집 완료된 topicId 추적 (remember-notifications 설정과 무관하게 항상 동작).
    // JS 폴러가 미해결 알림을 재전송해도, 이미 내용을 얻은 건은 다시 처리하지 않기 위함.
    private val resolvedCommentTopicIds = Collections.synchronizedSet(mutableSetOf<String>())
    private fun isCommentResolved(topicId: String?): Boolean {
        val id = topicId?.trim().orEmpty()
        if (id.isBlank()) return false
        return resolvedCommentTopicIds.contains(id)
    }
    private fun markCommentResolved(topicId: String?) {
        val id = topicId?.trim().orEmpty()
        if (id.isBlank()) return
        resolvedCommentTopicIds.add(id)
        if (resolvedCommentTopicIds.size > 800) {
            synchronized(resolvedCommentTopicIds) {
                val it = resolvedCommentTopicIds.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
        }
    }
    // 팔로우 알림 이벤트별 매핑 (프로필 갱신용)
    private val followNotifIdByTag = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val followCurrentTagByUserId = java.util.concurrent.ConcurrentHashMap<String, String>()
    // 알림 클릭 이후 일시적으로 팔로우 알림 억제 (중복 생성 방지)
    private val followSuppressUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // 3개 알림 빌더 함수
    private fun postLikeProject(
        user: String,
        topicId: String,
        created: String,
        openUrl: String? = null,
        projectIdHint: String? = null,
        thumbUrlHint: String? = null,
        template: String? = null,
        contentTitleHint: String? = null
    ) {
        val key = notifKey("LIKE_PROJ", user, "나의 작품을 좋아합니다", topicId, created)
        if (!rememberNotificationKey(key)) return

        val notifId = stableIdFrom(key)
        val deepLink = sanitizeEntryUrl(openUrl, "https://playentry.org/alarm")
        val whenMillis = parseWhen(created)
        val safeTitle = sanitizeNotificationText(contentTitleHint, 120).ifBlank { "알림" }
        val hintThumbUrl = sanitizeImageUrl(makeAbsoluteEntryUrl(thumbUrlHint.orEmpty()))

        postSimpleNotification(
            key = key,
            title = safeTitle,
            text = "내 작품에 좋아요가 도착했어요",
            whenMillis = whenMillis,
            openUrl = deepLink,
            smallIconRes = R.drawable.ic_stat_like,
            largeIcon = likeLargeIconBitmap,
            fixedId = notifId,
            topicId = topicId,
            projectThumbnailUrl = hintThumbUrl.takeIf { it.isNotBlank() }
        )

        if (hintThumbUrl.isNotBlank()) {
            lifecycleScope.launch {
                val hintBitmap = withContext(Dispatchers.IO) { loadBitmapFromUrl(hintThumbUrl, 128) }
                if (hintBitmap != null) {
                    postSimpleNotification(
                        key = key,
                        title = safeTitle,
                        text = "내 작품에 좋아요가 도착했어요",
                        whenMillis = whenMillis,
                        openUrl = deepLink,
                        smallIconRes = R.drawable.ic_stat_like,
                        largeIcon = hintBitmap,
                        fixedId = notifId,
                        topicId = topicId,
                        projectThumbnailUrl = hintThumbUrl
                    )
                    AndroidLog.d("LIKE_PROJECT", "Updated like notification with thumbUrl hint")
                }
            }
        }

        // 작품 좋아요 템플릿(template_3, 4, 11, 12 등) 프로젝트 thumb를 largeIcon으로 덮어쓴다.
        val isLikeTemplate = template.isNullOrBlank() ||
            template.equals("template_3", ignoreCase = true) ||
            template.equals("template_4", ignoreCase = true) ||
            template.equals("template_11", ignoreCase = true) ||
            template.equals("template_12", ignoreCase = true)
        if (!isLikeTemplate) return

        val projectId = resolveLikeProjectId(projectIdHint, deepLink, topicId) ?: run {
            AndroidLog.w("LIKE_PROJECT", "No valid projectId for thumb fetch (topicId=$topicId, hint=$projectIdHint)")
            return
        }

        lifecycleScope.launch {
            val thumbUrl = fetchLikeProjectThumbFromGraphql(projectId) ?: run {
                AndroidLog.w("LIKE_PROJECT", "thumb fetch failed for projectId=$projectId")
                return@launch
            }
            val thumbBitmap = withContext(Dispatchers.IO) { loadBitmapFromUrl(thumbUrl, 128) } ?: run {
                AndroidLog.w("LIKE_PROJECT", "thumb bitmap decode failed url=$thumbUrl")
                return@launch
            }
            postSimpleNotification(
                key = key,
                title = safeTitle,
                text = "내 작품에 좋아요가 도착했어요",
                whenMillis = whenMillis,
                openUrl = deepLink,
                smallIconRes = R.drawable.ic_stat_like,
                largeIcon = thumbBitmap,
                fixedId = notifId,
                topicId = topicId,
                projectThumbnailUrl = thumbUrl
            )
            AndroidLog.d("LIKE_PROJECT", "Updated like notification with project thumb (projectId=$projectId)")
        }
    }

    private fun postLikeComment(
        user: String,
        topicId: String,
        created: String,
        openUrl: String? = null,
        contentTitleHint: String? = null,
        projectIdHint: String? = null,
        thumbUrlHint: String? = null
    ) {
        val key = notifKey("LIKE_COMM", user, "나의 댓글을 좋아합니다", topicId, created)
        if (!rememberNotificationKey(key)) return

        val notifId = stableIdFrom(key)
        val deepLink = sanitizeEntryUrl(openUrl, "https://playentry.org/alarm")
        val whenMillis = parseWhen(created)
        val safeTitle = sanitizeNotificationText(contentTitleHint, 120).ifBlank { "알림" }
        val hintThumbUrl = sanitizeImageUrl(makeAbsoluteEntryUrl(thumbUrlHint.orEmpty()))

        postSimpleNotification(
            key = key,
            title = safeTitle,
            text = "내 댓글에 좋아요가 도착했어요",
            whenMillis = whenMillis,
            openUrl = deepLink,
            smallIconRes = R.drawable.ic_stat_like,
            largeIcon = likeLargeIconBitmap,
            fixedId = notifId,
            topicId = topicId,
            projectThumbnailUrl = hintThumbUrl.takeIf { it.isNotBlank() },
            template = "template_36"
        )

        val projectId = resolveLikeProjectId(projectIdHint, deepLink, topicId)
        if (!projectId.isNullOrBlank()) {
            lifecycleScope.launch {
                val thumbUrl = fetchLikeProjectThumbFromGraphql(projectId) ?: return@launch
                val thumbBitmap = withContext(Dispatchers.IO) { loadBitmapFromUrl(thumbUrl, 128) } ?: return@launch
                postSimpleNotification(
                    key = key,
                    title = safeTitle,
                    text = "내 댓글에 좋아요가 도착했어요",
                    whenMillis = whenMillis,
                    openUrl = deepLink,
                    smallIconRes = R.drawable.ic_stat_like,
                    largeIcon = thumbBitmap,
                    fixedId = notifId,
                    topicId = topicId,
                    projectThumbnailUrl = thumbUrl,
                    template = "template_36"
                )
                AndroidLog.d("LIKE_COMMENT", "Updated comment like notification with project thumb (projectId=$projectId)")
            }
        }
    }

    private fun postPinProject(
        user: String,
        topicId: String,
        created: String,
        openUrl: String? = null,
        projectIdHint: String? = null,
        thumbUrlHint: String? = null,
        template: String? = null,
        contentTitleHint: String? = null
    ) {
        val displayUser = user.ifBlank { "누군가" }
        val key = notifKey("PIN_PROJ", displayUser, "나를 고정했습니다", topicId, created)
        if (!rememberNotificationKey(key)) return

        val notifId = stableIdFrom(key)
        val deepLink = sanitizeEntryUrl(openUrl, "https://playentry.org/alarm")
        val whenMillis = parseWhen(created)
        val requestedTitle = sanitizeNotificationText(contentTitleHint, 120)
        val safeTitle = requestedTitle.ifBlank { "알림" }
        val body = "${displayUser}님이 나를 고정했어요"
        val hintThumbUrl = sanitizeImageUrl(makeAbsoluteEntryUrl(thumbUrlHint.orEmpty()))

        postSimpleNotification(
            key = key,
            title = safeTitle,
            text = body,
            whenMillis = whenMillis,
            openUrl = deepLink,
            smallIconRes = R.drawable.ic_stat_pin,
            fixedId = notifId,
            topicId = topicId,
            projectThumbnailUrl = hintThumbUrl.takeIf { it.isNotBlank() }
        )

        if (hintThumbUrl.isNotBlank()) {
            lifecycleScope.launch {
                val hintBitmap = withContext(Dispatchers.IO) { loadBitmapFromUrl(hintThumbUrl, 128) }
                if (hintBitmap != null) {
                    postSimpleNotification(
                        key = key,
                        title = safeTitle,
                        text = body,
                        whenMillis = whenMillis,
                        openUrl = deepLink,
                        smallIconRes = R.drawable.ic_stat_pin,
                        largeIcon = hintBitmap,
                        fixedId = notifId,
                        topicId = topicId,
                        projectThumbnailUrl = hintThumbUrl
                    )
                    AndroidLog.d("PIN_PROJECT", "Updated pin notification with thumbUrl hint")
                }
            }
        }

        if (!template.equals("template_37", ignoreCase = true)) return

        val projectId = resolveLikeProjectId(projectIdHint, deepLink, topicId) ?: run {
            AndroidLog.w("PIN_PROJECT", "No valid projectId for thumb fetch (topicId=$topicId, hint=$projectIdHint)")
            return
        }

        lifecycleScope.launch {
            val meta = fetchProjectMetaFromGraphql(projectId, "PIN_PROJECT") ?: run {
                AndroidLog.w("PIN_PROJECT", "project meta fetch failed for projectId=$projectId")
                return@launch
            }
            val resolvedTitle = if (requestedTitle.isNotBlank()) {
                safeTitle
            } else {
                meta.name.ifBlank { safeTitle }
            }

            val thumbBitmap = if (meta.thumbUrl.isNotBlank()) {
                withContext(Dispatchers.IO) { loadBitmapFromUrl(meta.thumbUrl, 128) }
            } else {
                null
            }

            postSimpleNotification(
                key = key,
                title = resolvedTitle,
                text = body,
                whenMillis = whenMillis,
                openUrl = deepLink,
                smallIconRes = R.drawable.ic_stat_pin,
                largeIcon = thumbBitmap,
                fixedId = notifId,
                topicId = topicId,
                projectThumbnailUrl = meta.thumbUrl.takeIf { it.isNotBlank() }
            )
            AndroidLog.d(
                "PIN_PROJECT",
                "Updated pin notification (projectId=$projectId, title='${resolvedTitle.take(40)}', hasThumb=${thumbBitmap != null})"
            )
        }
    }

    private fun resolveLikeProjectId(projectIdHint: String?, openUrl: String?, topicId: String): String? {
        val fromUrl = extractProjectIdFromUrl(openUrl)
        if (!fromUrl.isNullOrBlank()) {
            return fromUrl
        }
        val normalizedHint = projectIdHint?.trim().orEmpty()
        if (HEX_OBJECT_ID.matches(normalizedHint)) {
            return normalizedHint
        }
        val normalizedTopic = topicId.trim()
        return normalizedTopic.takeIf { HEX_OBJECT_ID.matches(it) }
    }

    private data class ProjectGraphqlMeta(
        val name: String,
        val thumbUrl: String
    )

    private suspend fun fetchLikeProjectThumbFromGraphql(projectId: String): String? = withContext(Dispatchers.IO) {
        val fromFetcher = UserProfileFetcher.fetchProject(projectId)?.thumbUrl
        if (!fromFetcher.isNullOrBlank()) return@withContext fromFetcher
        fetchProjectMetaFromGraphql(projectId, "LIKE_PROJECT")?.thumbUrl
    }

    private suspend fun fetchProjectMetaFromGraphql(
        projectId: String,
        logTag: String
    ): ProjectGraphqlMeta? = withContext(Dispatchers.IO) {
        val proj = UserProfileFetcher.fetchProject(projectId)
        if (proj != null && (!proj.name.isNullOrBlank() || !proj.thumbUrl.isNullOrBlank())) {
            return@withContext ProjectGraphqlMeta(
                name = proj.name.orEmpty(),
                thumbUrl = proj.thumbUrl.orEmpty()
            )
        }
        val xToken = preferenceManager.xToken.orEmpty()
        val csrfToken = preferenceManager.csrfToken.orEmpty()
        if (xToken.isBlank() || csrfToken.isBlank()) {
            AndroidLog.w(logTag, "Missing tokens for project meta fetch (projectId=$projectId)")
            return@withContext null
        }

        val query = """
            query (${'$'}id: ID!) {
              project(id: ${'$'}id) {
                name
                thumb
              }
            }
        """.trimIndent()
        val payload = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("id", projectId))
            .toString()
        val cookieHeader = runCatching {
            CookieManager.getInstance().getCookie("https://playentry.org").orEmpty()
        }.getOrElse { "" }

        val request = Request.Builder()
            .url("https://playentry.org/graphql")
            .addHeader("accept", "*/*")
            .addHeader("content-type", "application/json")
            .addHeader("origin", "https://playentry.org")
            .addHeader("referer", "https://playentry.org/project/$projectId")
            .addHeader("user-agent", "Mozilla/5.0 (Android) Entify/1.0")
            .addHeader("x-token", xToken)
            .addHeader("csrf-token", csrfToken)
            .addHeader("x-client-type", "Client")
            .apply {
                if (cookieHeader.isNotBlank()) {
                    addHeader("cookie", cookieHeader)
                }
            }
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AndroidLog.w(logTag, "Project meta GraphQL HTTP ${response.code} (projectId=$projectId)")
                    return@use null
                }
                val responseBody = SafeResponseReader.readText(response.body).orEmpty()
                if (responseBody.isBlank()) return@use null

                val root = JSONObject(responseBody)
                val errors = root.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    AndroidLog.w(
                        logTag,
                        "Project meta GraphQL errors for projectId=$projectId msg=${errors.optJSONObject(0)?.optString("message")}"
                    )
                    return@use null
                }
                val project = root.optJSONObject("data")
                    ?.optJSONObject("project")
                    ?: return@use null

                val projectName = sanitizeNotificationText(
                    project.optString("name"),
                    120
                )
                val thumbRaw = project
                    ?.optString("thumb")
                    .orEmpty()
                val absoluteThumb = makeAbsoluteEntryUrl(thumbRaw)
                val thumbUrl = sanitizeImageUrl(absoluteThumb)
                if (projectName.isBlank() && thumbUrl.isBlank()) return@use null
                ProjectGraphqlMeta(
                    name = projectName,
                    thumbUrl = thumbUrl
                )
            }
        }.getOrElse {
            AndroidLog.e(logTag, "Project meta GraphQL request failed for projectId=$projectId", it)
            null
        }
    }

    // ✅ 통합 프로필 시스템을 사용하는 팔로우 알림 처리 (간소화 버전)
    private fun handleFollowNotification(
        user: String,
        time: String,
        deepLink: String?,
        userIdHint: String? = null,
        template: String? = null,
        topicId: String? = null
    ) {
        AndroidLog.d("FOLLOW_HANDLER", "🔔 Started for user=$user, deepLink=$deepLink hint=$userIdHint")

        val sanitizedHint = userIdHint?.trim()?.takeIf { HEX_OBJECT_ID.matches(it) }
        val normalizedDeep = deepLink?.trim().orEmpty()
        val absoluteDeep = when {
            normalizedDeep.startsWith("http") -> normalizedDeep
            normalizedDeep.isNotBlank() -> {
                val prefix = if (normalizedDeep.startsWith("/")) "" else "/"
                "https://playentry.org$prefix$normalizedDeep"
            }
            else -> ""
        }

        // ✅ 절대경로 보정 + userId/user 기반 폴백 링크 생성
        val targetCandidate = sanitizedHint ?: userIdHint?.trim()?.takeIf { it.isNotBlank() } ?: user.takeIf { it.isNotBlank() }
        val fullLinkRaw = when {
            absoluteDeep.isNotBlank() -> absoluteDeep
            !targetCandidate.isNullOrBlank() -> "https://playentry.org/profile/$targetCandidate/project?sort=created&term=all&isOpen=all"
            else -> "https://playentry.org/"
        }
        val fullLink = sanitizeEntryUrl(fullLinkRaw, "https://playentry.org/")
        val userId = extractUserIdFromUrl(fullLink) ?: sanitizedHint ?: targetCandidate

        // 중복 방지 키 생성
        val key = notifKey("FOLLOW", user, "팔로잉합니다", userId ?: user, time)
        if (preferenceManager.isSemanticDuplicate("FOLLOW", user, time)) {
            AndroidLog.d("FOLLOW_HANDLER", "⚠️ Duplicate semantic follow notification for $user at $time")
            return
        }
        if (!rememberNotificationKey(key)) {
            AndroidLog.d("FOLLOW_HANDLER", "⚠️ Duplicate notification key, already posted: $key")
            return
        }

        AndroidLog.d("FOLLOW_HANDLER", "✅ New notification, proceeding with key=$key")
        val notifId = stableIdFrom(key)
        followNotifIdByTag[key] = notifId

        // ✅ 즉시 알림 표시 (기본 프로필)
        notifAvatarUrl.putIfAbsent(key, "")

        val whenMillis = parseWhen(time)
        val defaultIcon = makeInitialsAvatar(user.ifBlank { "?" })
        postSimpleNotification(
            key = key,
            title = "팔로잉",
            text = "${user}님이 나를 팔로잉 했어요",
            whenMillis = whenMillis,
            openUrl = fullLink,
            largeIcon = defaultIcon,
            fixedId = notifId,
            topicId = topicId,
            actorHexId = userId,
            actorImageUrl = notifAvatarUrl[key]?.takeIf { it.isNotBlank() }
        )

        // 백그라운드로 프로필 이미지 로드 및 업데이트
        val notifInfo = ProfileNotifInfo(
            tag = key,
            notifId = notifId,
            username = user,
            userId = userId,
            deepLink = fullLink,
            notifType = "FOLLOW",
            notifTitle = "팔로잉",
            notifText = "${user}님이 나를 팔로잉 했어요",
            whenMillis = whenMillis,  // 같은 시간 사용
            template = template?.lowercase(),
            topicId = topicId
        )

        if (!userId.isNullOrBlank()) {
            followDeepLinks[userId] = fullLink
            followNickById[userId] = user
            followCurrentTagByUserId[userId] = key
        }

        enqueueProfileFetch(user, userId, notifInfo)
    }
    
    // 기존 postFollow 함수는 호환성을 위해 유지 (내부적으로 handleFollowNotification 호출)
    private fun postFollow(user: String, created: String, openUrl: String? = null) {
        handleFollowNotification(user, created, openUrl)
    }

    private fun handleShareProjectNotification(
        user: String,
        topicId: String,
        created: String,
        link: JSONObject?,
        deepLink: String?,
        template: String? = null,
        contentTitleHint: String? = null,
        thumbUrlHint: String? = null
    ) {
        val who = if (user.isNullOrBlank()) "누군가" else user
        val projectId = link?.optString("target").orEmpty().ifBlank {
            extractProjectIdFromUrl(deepLink).orEmpty()
        }
        val openUrlRaw = when {
            !deepLink.isNullOrBlank() -> deepLink
            projectId.isNotBlank() -> "https://playentry.org/project/$projectId"
            else -> "https://playentry.org/"
        }
        val openUrl = sanitizeEntryUrl(openUrlRaw, "https://playentry.org/")
        AndroidLog.d("SHARE_PROFILE", "🔔 Started for user=$who projectId=$projectId openUrl=$openUrl")
        val key = notifKey("SHARE", who, "작품을 공유했습니다", projectId.ifBlank { topicId }, created)
        if (!rememberNotificationKey(key)) {
            AndroidLog.d("SHARE_PROFILE", "⚠️ Duplicate notification key, already posted: $key")
            return
        }
        AndroidLog.d("SHARE_PROFILE", "✅ New notification, proceeding with key=$key")
        val notifId = stableIdFrom(key)
        val safeTitle = sanitizeNotificationText(contentTitleHint, 120).ifBlank { "알림" }

        // ✅ 즉시 알림 표시 (기본 프로필)
        notifAvatarUrl.putIfAbsent(key, "")

        val whenMillis = parseWhen(created)
        val defaultIcon = makeInitialsAvatar(who.ifBlank { "?" })
        val safeThumb = makeAbsoluteEntryUrl(thumbUrlHint.orEmpty()).takeIf { it.isNotBlank() }
        postSimpleNotification(
            key = key,
            title = safeTitle,
            text = "${who}님이 작품을 공유 했어요",
            whenMillis = whenMillis,
            openUrl = openUrl,
            largeIcon = defaultIcon,
            fixedId = notifId,
            topicId = topicId,
            actorImageUrl = notifAvatarUrl[key]?.takeIf { it.isNotBlank() },
            projectThumbnailUrl = safeThumb,
            template = template?.lowercase() ?: "template_28"
        )

        // 백그라운드로 프로필 이미지 로드 및 업데이트
        val notifInfo = ProfileNotifInfo(
            tag = key,
            notifId = notifId,
            username = who,
            userId = projectId.ifBlank { null },  // projectId를 userId 필드에 저장
            deepLink = openUrl,
            notifType = "SHARE",
            notifTitle = safeTitle,
            notifText = "${who}님이 작품을 공유 했어요",
            whenMillis = whenMillis,
            template = template?.lowercase() ?: "template_28",
            topicId = topicId
        )

        enqueueProfileFetch(who, projectId.ifBlank { null }, notifInfo)
    }

    private fun handleRemakeProjectNotification(
        user: String,
        topicId: String,
        created: String,
        link: JSONObject?,
        deepLink: String?,
        template: String? = null,
        rawParams: Any? = null,
        contentTitleHint: String? = null
    ) {
        val who = if (user.isBlank()) "누군가" else user
        val projectId = link?.optString("target").orEmpty().ifBlank {
            extractProjectIdFromUrl(deepLink).orEmpty()
        }
        val openUrlRaw = when {
            !deepLink.isNullOrBlank() -> deepLink
            projectId.isNotBlank() -> "https://playentry.org/project/$projectId"
            else -> "https://playentry.org/"
        }
        val openUrl = sanitizeEntryUrl(openUrlRaw, "https://playentry.org/")
        val effectiveTemplate = template?.lowercase()?.takeIf { it.isNotBlank() } ?: "template_5"
        val key = notifKey("REMAKE", who, effectiveTemplate, projectId.ifBlank { topicId }, created)
        if (!rememberNotificationKey(key)) {
            AndroidLog.d("REMAKE_PROFILE", "⚠️ Duplicate notification key, already posted: $key")
            return
        }
        AndroidLog.d("REMAKE_PROFILE", "✅ New remake notification, key=$key")
        val notifId = stableIdFrom(key)

        val formatted = EntryTemplateFormatter.format(
            template = effectiveTemplate,
            rawParams = rawParams,
            fallbackActor = who
        )
        val safeTitle = formatted.title.ifBlank { sanitizeNotificationText(contentTitleHint, 120).ifBlank { "작품 리메이크 🎨" } }
        val safeText = formatted.body.ifBlank { "${who}님이 내 작품을 리메이크했어요!" }

        notifAvatarUrl.putIfAbsent(key, "")

        val whenMillis = parseWhen(created)
        val defaultIcon = makeInitialsAvatar(who.ifBlank { "?" })
        postSimpleNotification(
            key = key,
            title = safeTitle,
            text = safeText,
            whenMillis = whenMillis,
            openUrl = openUrl,
            largeIcon = defaultIcon,
            fixedId = notifId,
            topicId = topicId,
            actorImageUrl = notifAvatarUrl[key]?.takeIf { it.isNotBlank() },
            template = effectiveTemplate
        )

        // 백그라운드로 프로필 이미지 로드 및 업데이트
        val notifInfo = ProfileNotifInfo(
            tag = key,
            notifId = notifId,
            username = who,
            userId = projectId.ifBlank { null },
            deepLink = openUrl,
            notifType = "REMAKE",
            notifTitle = safeTitle,
            notifText = safeText,
            whenMillis = whenMillis,
            template = effectiveTemplate,
            topicId = topicId
        )

        enqueueProfileFetch(who, projectId.ifBlank { null }, notifInfo)
    }

    private fun buildProjectProfileExtractJS(projectId: String) = """
        (function(){
          try {
            function pickFromStyle(styleText){
              if (!styleText) return '';
              var normalized = String(styleText).replace(/&quot;/g, '"');
              var re = /url\(["']?([^"')]+)["']?\)/gi;
              var match;
              while ((match = re.exec(normalized)) !== null) {
                var candidate = match[1];
                if (candidate && candidate.indexOf('/uploads/') >= 0 && candidate.indexOf('EmptyImage') < 0) {
                  return candidate;
                }
              }
              return '';
            }
            function extractFromElement(el){
              if (!el) return '';
              var direct = pickFromStyle(el.getAttribute('style'));
              if (direct) return direct;
              if (window.getComputedStyle) {
                var bg = getComputedStyle(el).backgroundImage;
                var fromComputed = pickFromStyle(bg);
                if (fromComputed) return fromComputed;
              }
              var child = el.querySelector && el.querySelector('[style*="/uploads/"]');
              if (child && child !== el) {
                var nested = extractFromElement(child);
                if (nested) return nested;
              }
              return '';
            }
            function isWithin(node, selector){
              if (!node || typeof node.closest !== 'function') return false;
              try { return !!node.closest(selector); } catch(_) { return false; }
            }
            function scoreNode(node){
              if (!node) return -1e6;
              var score = 0;
              var href = (node.getAttribute && node.getAttribute('href')) || '';
              var style = (node.getAttribute && node.getAttribute('style')) || '';
              if (href.indexOf('/profile/') >= 0) score += 25;
              if (style.indexOf('background-image') >= 0) score += 10;
              if (node.querySelector && node.querySelector('span.blind')) score += 8;
              if (isWithin(node, 'main')) score += 12;
              else score -= 25;
              if (isWithin(node, '[class*="project"], [class*="Project"], [class*="profile"], [class*="Profile"], [class*="user"], article')) score += 8;
              if (isWithin(node, 'header, nav, [class*="gnb"], [data-name*="gnb"], [data-name*="header"]')) score -= 45;
              if (style.indexOf('EmptyImage') >= 0) score -= 20;
              return score;
            }
            function findClassFreeProfile(){
              try {
                var allAnchors = document.querySelectorAll('main a[style*="background-image"]');
                for (var i=0; i<allAnchors.length; i++) {
                  var anchor = allAnchors[i];
                  var hasSpan = anchor.querySelector('span') !== null;
                  if (hasSpan) continue;
                  var style = anchor.getAttribute('style') || '';
                  var profile = pickFromStyle(style);
                  if (profile) return profile;
                }
              } catch(_) {}
              return '';
            }
            function pickFromShareContainer(){
              try {
                var container = document.querySelector('.css-1do82rn.e1rinhrp0') ||
                                document.querySelector('[class*="css-1do82rn"][class*="e1rinhrp0"]');
                if (!container) return '';
                var nodes = [container];
                try {
                  var styled = container.querySelectorAll('[style*="background-image"], [style*="/uploads/"]');
                  for (var si=0; si<styled.length; si++) nodes.push(styled[si]);
                } catch(_) {}
                for (var ni=0; ni<nodes.length; ni++) {
                  var rel = extractFromElement(nodes[ni]);
                  if (rel) return rel;
                }
              } catch(_) {}
              return '';
            }
            function collectShareCards(){
              var bucket = [];
              try {
                var modern = document.querySelectorAll('[class*="css-1do82rn"][class*="e1rinhrp0"]');
                for (var m=0; m<modern.length; m++) {
                  var wrapper = modern[m];
                  bucket.push(wrapper);
                  var styled = wrapper.querySelectorAll('[style*="background-image"], [style*="/uploads/"]');
                  for (var s=0; s<styled.length; s++) bucket.push(styled[s]);
                }
                if (!modern.length) {
                  var legacy = document.querySelectorAll('[class*="css-1dcglws"][class*="e18ttzef6"]');
                  for (var i=0;i<legacy.length;i++) {
                    var card = legacy[i];
                    bucket.push(card);
                    var thumb = card.querySelector('[class*="css-12ehenu"][class*="e18ttzef4"][style*="background-image"]');
                    if (thumb) bucket.push(thumb);
                  }
                }
              } catch(_) {}
              return bucket;
            }
            function pickWithinShareLayout(){
              try {
                var containers = document.querySelectorAll('[class*="css-1do82rn"][class*="e1rinhrp0"]');
                for (var i=0; i<containers.length; i++) {
                  var card = containers[i];
                  var nodes = [card];
                  try {
                    var descendants = card.querySelectorAll('[style*="background-image"], [style*="/uploads/"]');
                    for (var d=0; d<descendants.length; d++) nodes.push(descendants[d]);
                  } catch(_) {}
                  for (var n=0; n<nodes.length; n++) {
                    var rel = extractFromElement(nodes[n]);
                    if (rel) return rel;
                  }
                }
              } catch(_) {}
              return '';
            }
            function gatherCandidates(){
              var selectors = [
                '.css-1do82rn.e1rinhrp0 [style*="background-image"]',
                '.css-1do82rn.e1rinhrp0 [style*="/uploads/"]',
                'main [class*="css-lowial"][style*="background-image"]',
                'main div[class*="css-lowial"] [style*="/uploads/"]',
                'main a.user_thmb',
                'main a[class*="user_thmb"]',
                'main a[href*="/profile/"][style*="background-image"]',
                'main a[href*="/profile/"]',
                'main [style*="/uploads/"]',
                '[class*="css-lowial"][style*="background-image"]',
                'div[class*="css-lowial"] [style*="/uploads/"]'
              ];
              var nodes = collectShareCards();
              selectors.forEach(function(sel){
                try {
                  var els = document.querySelectorAll(sel);
                  for (var i=0; i<els.length; i++) {
                    nodes.push(els[i]);
                  }
                } catch(_) {}
              });
              var blindSpans = document.querySelectorAll('span.blind');
              for (var i=0; i<blindSpans.length; i++) {
                var span = blindSpans[i];
                var anchor = null;
                if (typeof span.closest === 'function') {
                  anchor = span.closest('a[style*="background-image"], a[href*="/profile/"]');
                } else {
                  var parent = span.parentElement;
                  while (parent) {
                    if (parent.matches && parent.matches('a[style*="background-image"], a[href*="/profile/"]')) {
                      anchor = parent;
                      break;
                    }
                    parent = parent.parentElement;
                  }
                }
                if (anchor) nodes.push(anchor);
              }
              var uniqueNodes = [];
              for (var j=0; j<nodes.length; j++) {
                var node = nodes[j];
                if (!node) continue;
                if (uniqueNodes.indexOf(node) >= 0) continue;
                uniqueNodes.push(node);
              }
              uniqueNodes.sort(function(a,b){ return scoreNode(b) - scoreNode(a); });
              return uniqueNodes;
            }
            function toAbsolute(url){
              if (!url) return '';
              if (/^https?:\/\//i.test(url)) return url;
              if (url.indexOf('//') === 0) return location.protocol + url;
              if (url.charAt(0) === '/') return location.origin + url;
              var base = location.origin.replace(/\/$/, '');
              var trimmed = url.replace(/^\//, '');
              return base + '/' + trimmed;
            }
            var tries = 0;
            var maxTries = 12;
            var sent = false;
            function sendPayload(err, rel){
              if (sent) return;
              sent = true;
              var abs = rel ? toAbsolute(rel) : '';
              ShareBridge.postMessage(JSON.stringify({ type:'SHARE_PROFILE', projectId:'$projectId', error: err || '', url: abs }));
            }
            function findOnce(){
              var rel = findClassFreeProfile();
              if (rel) return rel;
              rel = pickFromShareContainer();
              if (rel) return rel;
              rel = pickWithinShareLayout();
              if (rel) return rel;
              var candidates = gatherCandidates();
              for (var i=0; i<candidates.length; i++) {
                rel = extractFromElement(candidates[i]);
                if (rel) break;
              }
              return rel;
            }
            function pump(){
              var rel = findOnce();
              if (rel) {
                sendPayload('', rel);
                return;
              }
              tries++;
              if (tries < maxTries) {
                setTimeout(pump, 250);
              } else {
                sendPayload('no_image', '');
              }
            }
            pump();
          } catch(e) {
            ShareBridge.postMessage(JSON.stringify({ type:'SHARE_PROFILE', projectId:'$projectId', error:String(e && e.message || e), url:'' }));
          }
        })();
    """.trimIndent()

    private fun handleFollowProfileResult(message: String) {
        try {
            val jo = JSONObject(message)
            val type = jo.optString("type")
            if (type != "FOLLOW_RESULT") return
            val userId = jo.optString("userId")
            if (userId.isBlank()) {
                AndroidLog.w("FOLLOW_PROFILE", "Missing userId in JS payload: $message")
                return
            }
            val error = jo.optString("error")
            val profileUrl = jo.optString("url")
            val filename = jo.optString("filename")
            val imageType = jo.optString("imageType")
            val picture = jo.optString("picture")
            val status = if (jo.has("status")) jo.optInt("status", 0) else 0
            val bodySnippet = when {
                jo.has("bodySnippet") -> jo.optString("bodySnippet")
                else -> jo.optString("body")
            }
            val storageToken = jo.optString("storageToken")
            val storageCsrf = jo.optString("storageCsrf")
            val cookieCsrf = jo.optString("cookieCsrf")
            val endpoint = jo.optString("endpoint")
            val operation = jo.optString("operation")
            val stage = jo.optString("stage")
            val ok = jo.optBoolean("ok", status in 200..299 && error.isBlank())
            val err = jo.optString("err")
            val diagnostics = jo.optJSONObject("diagnostics")
            val headerInfo = jo.optJSONObject("headers")

            val resolvedUrl = when {
                profileUrl.isNotBlank() -> sanitizeImageUrl(profileUrl)
                filename.isNotBlank() -> sanitizeImageUrl(buildUploadUrlFromSlug(filename, imageType))
                picture.isNotBlank() -> sanitizeImageUrl(makeAbsoluteEntryUrl(picture))
                else -> ""
            }
            val payload = resolvedUrl.takeIf { it.isNotBlank() }
            val authError = !ok || error == "missing_token" || (status in 400..403)
            if (authError) {
                markTokensStale()
            }

            if (status >= 400) {
                val cookieManager = CookieManager.getInstance()
                val cookieLen = runCatching { cookieManager.getCookie("https://playentry.org")?.length ?: 0 }
                    .getOrElse { -1 }
                val restoreAge = if (lastCookieRestoreAt == 0L) -1L else SystemClock.elapsedRealtime() - lastCookieRestoreAt
                AndroidLog.w(
                    "FOLLOW_GRAPHQL",
                    "HTTP $status for userId=$userId lastUrl=$followGraphqlLastUrl cookieLen=$cookieLen restoreAgeMs=$restoreAge snippet=${bodySnippet.take(120)} headers=${headerInfo?.toString()}"
                )
                invalidateFollowGraphqlContext("http_$status")
                if (status == 403) {
                    val trigger = "follow_403_${userId}_${SystemClock.elapsedRealtime()}"
                    runGraphqlParityProbe(trigger, buildFollowGraphqlPayloadJson(userId))
                }
            }

            AndroidLog.d(
                "FOLLOW_PROFILE",
                "Result stage=$stage ok=$ok err=$err userId=$userId status=$status url=$resolvedUrl endpoint=$endpoint op=$operation diag=${diagnostics?.toString()}"
            )

            val deferred = pendingFollowGraphqlRequests.remove(userId)
            if (deferred == null) {
                AndroidLog.w("FOLLOW_PROFILE", "No pending JS request for userId=$userId")
                quiesceGraphqlWebView(followGraphqlWebView)
                return
            }
            if (!deferred.isCompleted) {
                deferred.complete(GraphqlCallResult(payload, authError))
            }
            quiesceGraphqlWebView(followGraphqlWebView)
            if (!ok) {
                invalidateFollowGraphqlContext("stage_$stage")
            }
        } catch (e: Exception) {
            AndroidLog.e("FOLLOW_PROFILE", "Error parsing follow profile result", e)
            invalidateFollowGraphqlContext("parse_error")
            quiesceGraphqlWebView(followGraphqlWebView)
        }
    }

    private fun handleShareGraphqlResult(message: String) {
        try {
            val jo = JSONObject(message)
            val type = jo.optString("type")
            if (type != "SHARE_RESULT") return
            val projectId = jo.optString("projectId")
            if (projectId.isBlank()) {
                AndroidLog.w("SHARE_PROFILE", "Missing projectId in JS payload: $message")
                return
            }
            val error = jo.optString("error")
            val profileUrl = jo.optString("url")
            val filename = jo.optString("filename")
            val imageType = jo.optString("imageType")
            val picture = jo.optString("picture")
            val nickname = jo.optString("nickname").takeIf { it.isNotBlank() }
                ?: jo.optString("username").takeIf { it.isNotBlank() }
            val status = if (jo.has("status")) jo.optInt("status", 0) else 0
            val bodySnippet = when {
                jo.has("bodySnippet") -> jo.optString("bodySnippet")
                else -> jo.optString("body")
            }
            val storageToken = jo.optString("storageToken")
            val storageCsrf = jo.optString("storageCsrf")
            val cookieCsrf = jo.optString("cookieCsrf")
            val endpoint = jo.optString("endpoint")
            val operation = jo.optString("operation")
            val stage = jo.optString("stage")
            val ok = jo.optBoolean("ok", status in 200..299 && error.isBlank())
            val err = jo.optString("err")
            val diagnostics = jo.optJSONObject("diagnostics")
            val headerInfo = jo.optJSONObject("headers")

            val resolvedUrl = when {
                profileUrl.isNotBlank() -> sanitizeImageUrl(profileUrl)
                filename.isNotBlank() -> sanitizeImageUrl(buildUploadUrlFromSlug(filename, imageType))
                picture.isNotBlank() -> sanitizeImageUrl(makeAbsoluteEntryUrl(picture))
                else -> ""
            }
            val rawThumbUrl = jo.optString("thumbUrl")
            val resolvedThumbUrl = rawThumbUrl.takeIf { it.isNotBlank() }?.let { sanitizeImageUrl(it) }
            val payload = if (resolvedUrl.isNotBlank() || !resolvedThumbUrl.isNullOrBlank()) {
                ShareProfileGraphqlResult(resolvedUrl, nickname, resolvedThumbUrl)
            } else null
            val authError = !ok || error == "missing_token" || (status in 400..403)
            if (authError) {
                markTokensStale()
            }
            if (status >= 400) {
                val cm = CookieManager.getInstance()
                val cookieLen = runCatching { cm.getCookie("https://playentry.org")?.length ?: 0 }.getOrElse { -1 }
                val restoreAge = if (lastCookieRestoreAt == 0L) -1L else SystemClock.elapsedRealtime() - lastCookieRestoreAt
                AndroidLog.w(
                    "SHARE_GRAPHQL",
                    "HTTP $status for projectId=$projectId lastUrl=$shareGraphqlLastUrl cookieLen=$cookieLen restoreAgeMs=$restoreAge snippet=${bodySnippet.take(120)} headers=${headerInfo?.toString()}"
                )
                invalidateShareGraphqlContext("http_$status")
                if (status == 403) {
                    val trigger = "share_403_${projectId}_${SystemClock.elapsedRealtime()}"
                    runGraphqlParityProbe(trigger, buildShareGraphqlPayloadJson(projectId))
                }
            }

            AndroidLog.d(
                "SHARE_GRAPHQL",
                "Result stage=$stage ok=$ok err=$err projectId=$projectId status=$status url=$resolvedUrl endpoint=$endpoint op=$operation diag=${diagnostics?.toString()}"
            )

            val deferred = pendingShareGraphqlRequests.remove(projectId)
            if (deferred == null) {
                AndroidLog.w("SHARE_GRAPHQL", "No pending JS request for projectId=$projectId")
                quiesceGraphqlWebView(shareGraphqlWebView)
                return
            }
            if (!deferred.isCompleted) {
                deferred.complete(GraphqlCallResult(payload, authError))
            }
            quiesceGraphqlWebView(shareGraphqlWebView)
            if (!ok) {
                invalidateShareGraphqlContext("stage_$stage")
            }
        } catch (e: Exception) {
            AndroidLog.e("SHARE_GRAPHQL", "Error parsing share profile result", e)
            invalidateShareGraphqlContext("parse_error")
            quiesceGraphqlWebView(shareGraphqlWebView)
        }
    }

    private fun handleGraphqlParityResult(message: String) {
        try {
            val jo = JSONObject(message)
            if (jo.optString("type") != "GRAPHQL_PARITY_RESULT") return
            val trigger = jo.optString("trigger").ifBlank { "unknown" }
            val source = jo.optString("source").ifBlank { "unknown" }.lowercase()
            val status = if (jo.has("status")) jo.optInt("status") else null
            val ok = jo.optBoolean("ok", false)
            val snippet = jo.optString("bodySnippet")
            val error = jo.optString("error").ifBlank { null }
            val headers = jo.optJSONObject("headers")
            val duration = if (jo.has("durationMs")) jo.optLong("durationMs") else -1L
            AndroidLog.d(
                "GRAPHQL_PARITY",
                "Result trigger=$trigger source=$source status=${status ?: -1} ok=$ok error=${error ?: "-"} durationMs=$duration headers=${headers?.toString()} snippet=${snippet.take(80)}"
            )
            val snapshot = GraphqlParitySnapshot(
                source = source,
                status = status,
                ok = ok,
                snippet = snippet,
                error = error,
                headers = headers?.toString(),
                durationMs = duration
            )
            var summary: String? = null
            synchronized(graphqlParityLock) {
                val bucket = graphqlParityResults.getOrPut(trigger) { mutableMapOf() }
                bucket[source] = snapshot
                val mainSnap = bucket["main"]
                val followSnap = bucket["follow"]
                if (mainSnap != null && followSnap != null) {
                    summary =
                        "trigger=$trigger main=${mainSnap.status ?: -1}/${mainSnap.error ?: "-"} follow=${followSnap.status ?: -1}/${followSnap.error ?: "-"}"
                    graphqlParityResults.remove(trigger)
                }
            }
            summary?.let { AndroidLog.d("GRAPHQL_PARITY", "Summary $it") }
        } catch (e: Exception) {
            AndroidLog.e("GRAPHQL_PARITY", "Failed to parse parity result", e)
        }
    }

    // ✅ 통합 프로필 추출 시스템 - 모든 알림 타입에서 사용
    private fun enqueueProfileFetch(
        username: String,
        userId: String?,
        notifInfo: ProfileNotifInfo
    ) {
        if (!isProfileExtractionEnabled(notifInfo.notifType)) {
            AndroidLog.d(
                "PROFILE",
                "⏸️ Profile fetch locked for type=${notifInfo.notifType}, tag=${notifInfo.tag}"
            )
            return
        }

        val templateKey = notifInfo.template?.lowercase()
        if (templateKey !in PROFILE_TEMPLATE_WHITELIST && notifInfo.notifType != "FOLLOW" && notifInfo.notifType != "REPLY") {
            AndroidLog.d(
                "PROFILE",
                "⏭️ Skip profile fetch for template=$templateKey tag=${notifInfo.tag}"
            )
            return
        }

        // ✅ SHARE 타입은 항상 username을 key로 사용 (projectId는 프로필 검색에 사용 불가)
        val key = when {
            isShareProfileType(notifInfo.notifType) -> username
            else -> userId?.takeIf { it.isNotBlank() && (HEX_OBJECT_ID.matches(it) || it.length > 10) } ?: username
        }

        if (key.isBlank()) {
            AndroidLog.w("PROFILE", "❌ Empty key, cannot enqueue")
            return
        }

        if (notifInfo.notifType == "FOLLOW" || notifInfo.notifType == "REPLY") {
            val candidate = notifInfo.userId?.takeIf { it.isNotBlank() } ?: username
            if (candidate.isNotBlank()) {
                if (!profileInProgress.add(key)) {
                    AndroidLog.d("PROFILE", "⚠️ ${notifInfo.notifType} already in progress for $key - queueing pending")
                    val list = profilePendingUpdates.getOrPut(key) { mutableListOf() }
                    list.add(notifInfo)
                    return
                }
                AndroidLog.d("PROFILE", "🚀 Fetching follow profile for $candidate (key=$key)")
                profileNotifInfoByKey[key] = notifInfo
                lifecycleScope.launch {
                    val prof = UserProfileFetcher.fetchProfile(
                        idOrNickname = candidate,
                        cookies = preferenceManager.cookies,
                        csrfToken = preferenceManager.csrfToken,
                        xToken = preferenceManager.xToken
                    )
                    val avatar = prof?.avatarUrl
                    if (!avatar.isNullOrBlank()) {
                        AndroidLog.d("PROFILE", "✅ Follow profile success via OkHttp for $candidate: $avatar")
                        profileNotifInfoByKey[key] = notifInfo
                        updateNotificationWithProfile(key, avatar, actorHexId = notifInfo.userId)
                        return@launch
                    }
                    if (HEX_OBJECT_ID.matches(candidate)) {
                        val fallbackAvatar = fetchFollowProfileFromGraphql(candidate)
                        if (!fallbackAvatar.isNullOrBlank()) {
                            AndroidLog.d("PROFILE", "✅ Follow GraphQL profile success for $candidate: $fallbackAvatar")
                            profileNotifInfoByKey[key] = notifInfo
                            updateNotificationWithProfile(key, fallbackAvatar, actorHexId = candidate)
                            return@launch
                        }
                    }
                    AndroidLog.w("PROFILE", "❌ Follow profile fetch failed for $candidate - leaving default icon")
                    finishProfile(key)
                }
                return
            } else {
                AndroidLog.w("PROFILE", "❌ FOLLOW notification has no valid candidate - username=$username userId=${notifInfo.userId}")
            }
        }

        if (notifInfo.notifType == "SHARE" || notifInfo.notifType == "REMAKE") {
            val projectId = notifInfo.userId?.takeIf { it.isNotBlank() }
                ?: extractProjectIdFromUrl(notifInfo.deepLink)
            if (!projectId.isNullOrBlank()) {
                if (!profileInProgress.add(key)) {
                    AndroidLog.d("PROFILE", "⚠️ SHARE/REMAKE already in progress for $key - queueing pending")
                    val list = profilePendingUpdates.getOrPut(key) { mutableListOf() }
                    list.add(notifInfo)
                    return
                }
                AndroidLog.d("PROFILE", "🚀 Fetching project meta for share/remake projectId=$projectId (key=$key)")
                profileNotifInfoByKey[key] = notifInfo
                lifecycleScope.launch {
                    val projectResult = UserProfileFetcher.fetchProject(
                        projectId = projectId,
                        cookies = preferenceManager.cookies,
                        csrfToken = preferenceManager.csrfToken,
                        xToken = preferenceManager.xToken
                    )
                    val thumb = projectResult?.thumbUrl
                    val authorAvatar = projectResult?.authorAvatarUrl
                    val authorHex = projectResult?.authorId

                    if (notifInfo.notifType == "SHARE") {
                        val targetImage = thumb?.takeIf { it.isNotBlank() } ?: authorAvatar
                        if (!targetImage.isNullOrBlank()) {
                            AndroidLog.d("PROFILE", "⚡️ Share project thumb success: thumb=$thumb, author=$authorAvatar")
                            profileNotifInfoByKey[key] = notifInfo
                            updateNotificationWithProfile(
                                key = key,
                                profileUrl = authorAvatar,
                                thumbUrl = thumb,
                                actorHexId = authorHex
                            )
                            return@launch
                        }
                    } else {
                        val targetImage = authorAvatar?.takeIf { it.isNotBlank() } ?: thumb
                        if (!targetImage.isNullOrBlank()) {
                            AndroidLog.d("PROFILE", "⚡️ Remake author profile success: author=$authorAvatar, thumb=$thumb")
                            profileNotifInfoByKey[key] = notifInfo
                            updateNotificationWithProfile(
                                key = key,
                                profileUrl = authorAvatar,
                                thumbUrl = thumb,
                                actorHexId = authorHex
                            )
                            return@launch
                        }
                    }

                    // Fallback to WebView GraphQL if OkHttp did not get images
                    val quickResult = fetchShareProfileFromGraphql(projectId)
                    val confirmed = quickResult?.takeIf { it.avatarUrl.isNotBlank() || !it.thumbUrl.isNullOrBlank() }
                    if (confirmed == null) {
                        AndroidLog.d("PROFILE", "⚠️ Share/remake fetch failed for projectId=$projectId - leaving default icon")
                        finishProfile(key)
                    } else {
                        AndroidLog.d(
                            "PROFILE",
                            "⚡️ WebView share/remake profile success for projectId=$projectId (avatar=${confirmed.avatarUrl}, thumb=${confirmed.thumbUrl})"
                        )
                        profileNotifInfoByKey[key] = notifInfo
                        updateNotificationWithProfile(
                            key = key,
                            profileUrl = confirmed.avatarUrl.takeIf { it.isNotBlank() },
                            thumbUrl = confirmed.thumbUrl?.takeIf { it.isNotBlank() } ?: thumb
                        )
                    }
                }
                return
            }
        }
    }

    private fun queueProfileFetch(key: String, notifInfo: ProfileNotifInfo) {
        if (!profileInProgress.add(key)) {
            AndroidLog.d("PROFILE", "⚠️ Already in progress for $key - enqueueing pending update")
            val list = profilePendingUpdates.getOrPut(key) { mutableListOf() }
            list.add(notifInfo)
            return
        }

        profileNotifInfoByKey[key] = notifInfo
        val followPriority = notifInfo.notifType == "FOLLOW"
        if (followPriority) {
            profileQueue.addFirst(key)
        } else {
            profileQueue.addLast(key)
        }
        AndroidLog.d(
            "PROFILE",
            "✅ Queued profile fetch for ${notifInfo.username} (key=$key, type=${notifInfo.notifType}, queue=${profileQueue.size}, priority=${if (followPriority) "FOLLOW" else "NORMAL"})"
        )

        // ✅ Rate limit 방지: 요청 간 딜레이 추가 (속도 개선)
        mainHandler.postDelayed({
            processNextProfile()
        }, 30)  // 30ms 딜레이 (초고속 프로필 로딩)
    }

    private data class GraphqlCallResult<T>(val payload: T?, val authError: Boolean)

    private data class GraphqlParitySnapshot(
        val source: String,
        val status: Int?,
        val ok: Boolean,
        val snippet: String?,
        val error: String?,
        val headers: String?,
        val durationMs: Long
    )

    private suspend fun fetchShareProfileFromGraphql(projectId: String): ShareProfileGraphqlResult? = shareGraphqlMutex.withLock {
        if (!waitForSessionTokens()) {
            AndroidLog.w("PROFILE", "Tokens not ready for share GraphQL (projectId=$projectId) - requesting refresh")
            if (!ensureTokensForGraphql(forceRefresh = true, reason = "share_graphql_wait")) {
                AndroidLog.e("PROFILE", "Failed to refresh tokens for share GraphQL (projectId=$projectId)")
                return@withLock null
            }
        }
        if (!waitForMainPageReady()) {
            AndroidLog.w("PROFILE", "Main page not ready for share GraphQL (projectId=$projectId)")
            return@withLock null
        }
        repeat(2) { attempt ->
            val result = requestShareProfileViaWebView(projectId) ?: run {
                AndroidLog.w("SHARE_GRAPHQL", "Unable to dispatch JS fetch for projectId=$projectId (attempt=${attempt + 1})")
                return@withLock null
            }
            if (!result.authError) {
                return@withLock result.payload
            }
            AndroidLog.w(
                "SHARE_GRAPHQL",
                "Auth error during JS fetch for projectId=$projectId (attempt=${attempt + 1})"
            )
            if (!ensureTokensForGraphql(forceRefresh = true, reason = "share_graphql_retry")) {
                AndroidLog.e("SHARE_GRAPHQL", "Token refresh failed while retrying projectId=$projectId")
                return@withLock null
            }
        }
        null
    }

    private fun makeAbsoluteEntryUrl(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://playentry.org$trimmed"
            else -> "https://playentry.org/$trimmed"
        }
    }

    private fun markTokensReady() {
        lastTokenReadyAt = SystemClock.elapsedRealtime()
        pendingTokenReady?.let {
            if (!it.isCompleted) it.complete(true)
        }
        pendingTokenReady = null
    }

    private fun markTokensStale() {
        lastTokenReadyAt = 0L
        pendingTokenReady?.cancel()
        pendingTokenReady = null
        notificationLogicStarted = false
        if (::preferenceManager.isInitialized) {
            syncForegroundPollingService("tokens_stale")
        }
    }

    private fun syncForegroundPollingService(reason: String) {
        val hasTokens = hasStoredTokens()
        val shouldRun = !preferenceManager.logoutPending && hasTokens
        AndroidLog.d(
            "FG_POLL",
            "sync($reason) fg=$isAppForeground logoutPending=${preferenceManager.logoutPending} hasTokens=$hasTokens -> shouldRun=$shouldRun"
        )
        if (shouldRun) {
            // 앱이 백그라운드일 때만 WebView 기반 폴러를 멈춘다.
            if (!isAppForeground) {
                stopAlarmBackgroundPoller("fg_service_active:$reason")
            }
            EntryPollingForegroundService.start(this)
            AndroidLog.d("FG_POLL", "Foreground polling service start requested ($reason)")
        } else {
            EntryPollingForegroundService.stop(this)
            AndroidLog.d("FG_POLL", "Foreground polling service stop requested ($reason)")
        }
    }

    /**
     * Invalidates every in-process session surface before presenting a fresh
     * login WebView. Notification history and non-session user settings are
     * deliberately retained.
     */
    private fun performSecureLogout(reason: String, onComplete: (() -> Unit)? = null) {
        if (logoutInProgress) return
        logoutInProgress = true
        AndroidLog.w("LOGOUT", "Secure logout started ($reason)")

        preferenceManager.logoutPending = true
        preferenceManager.isLoggedIn = false
        preferenceManager.clearSessionSecrets()
        preferenceManager.username = null
        preferenceManager.profileImageUrl = null
        preferenceManager.commentUserId = null
        EntryPollingForegroundService.stop(this)

        startupFlowJob?.cancel()
        startupFlowJob = null
        stopAlarmBackgroundPoller("secure_logout:$reason")
        alarmStartRunnable?.let(mainHandler::removeCallbacks)
        alarmRecoveryRunnable?.let(mainHandler::removeCallbacks)
        hiddenWebViewTimeout?.let(mainHandler::removeCallbacks)
        alarmStartRunnable = null
        alarmRecoveryRunnable = null
        hiddenWebViewTimeout = null
        pendingTokenReady?.cancel()
        pendingTokenReady = null
        pendingGraphqlTokenRequest?.cancel()
        pendingGraphqlTokenRequest = null
        tokenBootstrapDeferred?.cancel()
        tokenBootstrapDeferred = null
        followGraphqlPageDeferred?.cancel()
        followGraphqlPageDeferred = null
        shareGraphqlPageDeferred?.cancel()
        shareGraphqlPageDeferred = null

        val sessionWebViews = listOfNotNull(
            mainWebView,
            hiddenWebView,
            alarmWebView,
            tokenWebView,
            followGraphqlWebView,
            shareGraphqlWebView,
            profileWebView
        ).distinct()
        mainWebView = null
        hiddenWebView = null
        alarmWebView = null
        tokenWebView = null
        followGraphqlWebView = null
        shareGraphqlWebView = null
        profileWebView = null
        sessionWebViews.forEach(::destroySessionWebView)

        webViewCookieGeneration.clear()
        cookiesPrimedGlobally = false
        avatarCache.evictAll()
        notifAvatarUrl.clear()
        followDeepLinks.clear()
        followNickById.clear()
        followProfileApplied.clear()
        followInProgress.clear()
        WebStorage.getInstance().deleteAllData()

        val cookieManager = CookieManager.getInstance()
        val finishLogout = Runnable {
            if (!logoutInProgress) return@Runnable
            logoutInProgress = false
            webViewGeneration++
            AndroidLog.d("LOGOUT", "Secure logout completed ($reason)")
            onComplete?.invoke()
        }
        cookieManager.removeAllCookies {
            cookieManager.flush()
            mainHandler.post {
                finishLogout.run()
            }
        }
    }

    private fun destroySessionWebView(webView: WebView) {
        runCatching { webView.stopLoading() }
        runCatching {
            webView.evaluateJavascript(
                "if(window.__ENTRY_POLLER__){window.__ENTRY_POLLER__.stop();delete window.__ENTRY_POLLER__;}",
                null
            )
        }
        runCatching { webView.removeJavascriptInterface("AndroidApp") }
        runCatching { webView.removeJavascriptInterface("ProfileBridge") }
        runCatching { webView.clearHistory() }
        runCatching { webView.clearCache(true) }
        runCatching { webView.clearFormData() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
    }

    private fun handleLogoutDetected(reason: String) {
        if (preferenceManager.logoutPending) return
        performSecureLogout("detected:$reason")

        postSimpleNotification(
            key = "SYSTEM_LOGOUT",
            title = "로그아웃 감지",
            text = "세션이 만료되었습니다. 설정에서 다시 로그인하세요.",
            whenMillis = System.currentTimeMillis(),
            openUrl = "https://playentry.org/signin"
        )
    }

    private fun hasValidTokens(): Boolean {
        return lastTokenReadyAt > 0 &&
            !preferenceManager.xToken.isNullOrBlank() &&
            !preferenceManager.csrfToken.isNullOrBlank()
    }

    private fun hasStoredTokens(): Boolean =
        !preferenceManager.xToken.isNullOrBlank() &&
            !preferenceManager.csrfToken.isNullOrBlank()

    private fun ensureStoredTokensMarkedReady() {
        if (lastTokenReadyAt == 0L && hasStoredTokens()) {
            AndroidLog.d("STARTUP_FLOW", "Marking stored tokens as ready")
            markTokensReady()
        }
    }

    private fun isMainContentUrl(url: String?): Boolean =
        EntryOriginPolicy.isTrustedMainHost(url) &&
            !EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/signin")

    private fun runTokenExtractorIfReady(reason: String = "manual") {
        val webView = mainWebView ?: run {
            AndroidLog.d("TOKEN_JS", "Skip $reason token extractor - mainWebView is null")
            return
        }
        val currentUrl = webView.url
        if (!isMainContentUrl(currentUrl)) {
            AndroidLog.d("TOKEN_JS", "Skip $reason token extractor - not on main page (url=$currentUrl)")
            return
        }
        try {
            webView.evaluateJavascript(jsTokenExtractor, null)
            webView.evaluateJavascript(jsTokenPoller, null)
        } catch (e: Exception) {
            AndroidLog.e("TOKEN_JS", "Failed to run token extractor ($reason): ${e.message}")
        }
    }

    private suspend fun waitForSessionTokens(timeoutMs: Long = 4000L): Boolean {
        if (hasValidTokens()) return true
        AndroidLog.d("TOKEN_WAIT", "Waiting for session tokens...")
        val deferred = CompletableDeferred<Boolean>()
        pendingTokenReady?.cancel()
        pendingTokenReady = deferred
        val ok = withTimeoutOrNull(timeoutMs) {
            deferred.await()
            true
        } ?: false
        if (!ok) {
            AndroidLog.w("TOKEN_WAIT", "Timed out while waiting for session tokens")
            return false
        }
        return hasValidTokens()
    }

    private fun buildUploadUrlFromSlug(filename: String, imageType: String?): String {
        if (filename.isBlank()) return ""
        val trimmed = filename.trim()
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed

        val withoutPrefix = trimmed.removePrefix("/").removePrefix("uploads/").removePrefix("/")
        val hasDir = withoutPrefix.contains("/")
        val hasExt = withoutPrefix.contains(".")

        val slugPath = when {
            hasDir -> withoutPrefix
            withoutPrefix.length >= 4 -> {
                val first = withoutPrefix.substring(0, 2)
                val second = withoutPrefix.substring(2, 4)
                "$first/$second/$withoutPrefix"
            }
            withoutPrefix.length >= 2 -> {
                val first = withoutPrefix.substring(0, 2)
                "$first/$withoutPrefix"
            }
            else -> withoutPrefix
        }

        val ext = when {
            hasExt -> ""
            imageType.isNullOrBlank() -> ""
            else -> ".${imageType.lowercase()}"
        }

        return "https://playentry.org/uploads/$slugPath$ext"
    }

    // TODO: Replace the query below with the exact DevTools-captured FIND_USERSTATUS_BY_USERNAME payload if it changes.
    private val followGraphqlQueryRaw = """
        query FIND_USERSTATUS_BY_USERNAME(${'$'}id: String) {
            userstatus(id: ${'$'}id) {
                id
                nickname
                username
                description
                shortUrl
                created
                profileImage {
                    id
                    name
                    label {
                        ko
                        en
                        ja
                        vn
                    }
                    filename
                    imageType
                    dimension {
                        width
                        height
                    }
                    trimmed {
                        filename
                        width
                        height
                    }
                }
                coverImage {
                    id
                    name
                    label {
                        ko
                        en
                        ja
                        vn
                    }
                    filename
                    imageType
                    dimension {
                        width
                        height
                    }
                    trimmed {
                        filename
                        width
                        height
                    }
                }
                role
                mark {
                    id
                    name
                    label {
                        ko
                        en
                        ja
                        vn
                    }
                    filename
                    imageType
                    dimension {
                        width
                        height
                    }
                    trimmed {
                        filename
                        width
                        height
                    }
                }
                studentTerm
                status {
                    project
                    projectAll
                    study
                    studyAll
                    community {
                        qna
                        tips
                        free
                    }
                    following
                    follower
                    bookmark {
                        project
                        study
                        discuss
                    }
                    userStatus
                }
                representativeContestPrizes {
                    id
                    contest {
                        name
                        url
                        enddate
                    }
                    badgeText
                    prizeName
                    prizeImageData {
                        path
                    }
                    target
                    targetSubject
                    targetType
                    created
                    category
                }
            }
        }
    """.trimIndent()

    // TODO: Replace the query below with the exact DevTools-captured SELECT_PROJECT_LITE payload if it changes.
    private val shareGraphqlQueryRaw = """
        query SELECT_PROJECT_LITE(${'$'}id: ID!, ${'$'}groupId: ID) {
            project(id: ${'$'}id, groupId: ${'$'}groupId) {
                id
                name
                user {
                    id
                    nickname
                    profileImage {
                        id
                        name
                        label {
                            ko
                            en
                            ja
                            vn
                        }
                        filename
                        imageType
                        dimension {
                            width
                            height
                        }
                        trimmed {
                            filename
                            width
                            height
                        }
                    }
                    status {
                        following
                        follower
                    }
                    description
                    role
                    mark {
                        id
                        name
                        label {
                            ko
                            en
                            ja
                            vn
                        }
                        filename
                        imageType
                        dimension {
                            width
                            height
                        }
                        trimmed {
                            filename
                            width
                            height
                        }
                    }
                }
                thumb
                isopen
                showComment
                blamed
                isPracticalCourse
                category
                categoryCode
                created
                updated
                special
                isForLecture
                isForStudy
                isForSubmit
                hashId
                complexity
                staffPicked
                ranked
                visit
                likeCnt
                comment
                favorite
                shortenUrl
                parent {
                    id
                    name
                    user {
                        id
                        nickname
                    }
                }
                description
                description2
                description3
                hasRealTimeVariable
                blockCategoryUsage
                childCnt
                commentGroup {
                    group
                    count
                }
                likeCntGroup {
                    group
                    count
                }
                visitGroup {
                    group
                    count
                }
                recentGroup {
                    group
                    count
                }
                published
                isFirstPublish
                tags
            }
        }
    """.trimIndent()

    private fun buildFollowGraphqlPayloadJson(userId: String): String {
        val payload = JSONObject()
        payload.put("operationName", "FIND_USERSTATUS_BY_USERNAME")
        val variables = JSONObject()
        variables.put("id", userId)
        payload.put("variables", variables)
        payload.put("query", followGraphqlQueryRaw)
        return payload.toString()
    }

    private fun buildShareGraphqlPayloadJson(projectId: String): String {
        val payload = JSONObject()
        payload.put("operationName", "SELECT_PROJECT_LITE")
        val variables = JSONObject()
        variables.put("id", projectId)
        variables.put("groupId", JSONObject.NULL)
        payload.put("variables", variables)
        payload.put("query", shareGraphqlQueryRaw)
        return payload.toString()
    }

    private fun buildFollowProfileGraphqlJs(userId: String, xToken: String, csrfToken: String): String {
        val safeUserId = JSONObject.quote(userId)
        val safeToken = JSONObject.quote(xToken)
        val safeCsrf = JSONObject.quote(csrfToken)
        val safeQuery = JSONObject.quote(followGraphqlQueryRaw)
        return """
            (() => {
              const targetId = $safeUserId;
              let token = $safeToken;
              let csrf = $safeCsrf;
              const GRAPHQL_ENDPOINT = 'https://playentry.org/graphql';
              const OPERATION_NAME = 'FIND_USERSTATUS_BY_USERNAME';
              const hasAndroidApp = typeof AndroidApp !== 'undefined' && !!AndroidApp.postMessage;
              const FETCH_CREDENTIALS = 'include';
              const timeoutMs = 6000;
              let timeoutHandle = null;
              let sent = false;
              const controller = typeof AbortController !== 'undefined'
                ? new AbortController()
                : null;

              function debug(stage, extra) {
                if (!hasAndroidApp) return;
                try {
                  AndroidApp.postMessage(JSON.stringify({
                    type: 'FOLLOW_DEBUG',
                    stage,
                    userId: targetId,
                    info: extra || {}
                  }));
                } catch (err) {
                  console.warn('[FOLLOW_PROFILE] debug send failed', err?.message || err);
                }
              }

              function diagSnapshot() {
                return {
                  location: location.href,
                  origin: location.origin,
                  referrer: document.referrer || '',
                  ua: navigator.userAgent,
                  cookieLen: (document.cookie || '').length,
                  credentialsMode: FETCH_CREDENTIALS,
                  canSetRefererHeader: false,
                  canSetOriginHeader: false
                };
              }

              debug('init', {
                hasAndroidApp,
                documentReady: document.readyState,
                location: location.href
              });

              function patchNextData(data){
                if (!data || typeof data !== 'object') return data;
                try {
                  const initUser = data?.props?.initialState?.common?.user;
                  const pageUser = data?.props?.pageProps?.initialState?.common?.user;
                  if (initUser && !pageUser) {
                    const pp = data.props.pageProps = data.props.pageProps || {};
                    const ppInit = pp.initialState = pp.initialState || {};
                    const ppCommon = ppInit.common = ppInit.common || {};
                    ppCommon.user = initUser;
                  } else if (!initUser && pageUser) {
                    const base = data.props.initialState = data.props.initialState || {};
                    const baseCommon = base.common = base.common || {};
                    baseCommon.user = pageUser;
                  }
                } catch (err) {
                  console.log('[FOLLOW_PROFILE] patch error', err?.message);
                }
                return data;
              }

              function extractTokensFromDom(){
                try {
                  const el = document.getElementById('__NEXT_DATA__');
                  if (!el || !el.innerText) return null;
                  const parsed = patchNextData(JSON.parse(el.innerText.trim() || '{}'));
                  if (window.__NEXT_DATA__) patchNextData(window.__NEXT_DATA__);
                  const userNode = parsed?.props?.initialState?.common?.user ||
                                   parsed?.props?.pageProps?.initialState?.common?.user;
                  const xtoken =
                    userNode?.xToken ||
                    sessionStorage.getItem('entry_xToken') ||
                    localStorage.getItem('entry_xToken') ||
                    localStorage.getItem('playentry_token');
                  const csrfToken =
                    parsed?.props?.initialProps?.csrfToken ||
                    sessionStorage.getItem('entry_csrfToken') ||
                    localStorage.getItem('entry_csrfToken');
                  if (xtoken && csrfToken) {
                    return { xtoken, csrfToken };
                  }
                } catch (err) {
                  console.log('[FOLLOW_PROFILE] extractTokens error', err?.message);
                }
                return null;
              }

              const GRAPHQL_QUERY = $safeQuery;
              const payload = {
                operationName: OPERATION_NAME,
                variables: { id: targetId },
                query: GRAPHQL_QUERY
              };
              const payloadJson = JSON.stringify(payload);

              function resolveCsrfFromDom() {
                try {
                  const meta = document.querySelector('meta[name="csrf-token"]');
                  if (meta && meta.content) return meta.content.trim();
                } catch (_err) {}
                try {
                  const nextData = window.__NEXT_DATA__;
                  const nextCsrf =
                    nextData?.props?.initialProps?.csrfToken ||
                    nextData?.props?.pageProps?.initialProps?.csrfToken;
                  if (nextCsrf) return nextCsrf;
                } catch (_err) {}
                try {
                  const script = document.getElementById('__NEXT_DATA__');
                  if (script && script.textContent) {
                    const parsed = JSON.parse(script.textContent);
                    const scriptCsrf =
                      parsed?.props?.initialProps?.csrfToken ||
                      parsed?.props?.pageProps?.initialProps?.csrfToken;
                    if (scriptCsrf) return scriptCsrf;
                  }
                } catch (_err) {}
                return null;
              }

              function gatherDiagnostics() {
                const storageToken =
                  sessionStorage.getItem('entry_xToken') ||
                  localStorage.getItem('entry_xToken') ||
                  localStorage.getItem('playentry_token') || '';
                const storageCsrf =
                  sessionStorage.getItem('entry_csrfToken') ||
                  localStorage.getItem('entry_csrfToken') || '';
                const cookieSnapshot = (document.cookie || '').slice(0, 600);
                const cookieCsrf = (cookieSnapshot.split(';').map(s => s.trim()).find(s => s.startsWith('_csrf=')) || '')
                  .replace(/^_csrf=/, '');
                return {
                  storageToken,
                  storageCsrf,
                  cookieSnapshot,
                  cookieCsrf
                };
              }

              function respond(stage, extra) {
                if (sent) return;
                sent = true;
                if (timeoutHandle) {
                  clearTimeout(timeoutHandle);
                  timeoutHandle = null;
                }
                const diag = Object.assign({}, gatherDiagnostics(), diagSnapshot());
                const out = Object.assign({
                  type: 'FOLLOW_RESULT',
                  userId: targetId,
                  endpoint: GRAPHQL_ENDPOINT,
                  operation: OPERATION_NAME,
                  stage,
                  diagnostics: diag
                }, diag, extra || {});
                try {
                  AndroidApp.postMessage(JSON.stringify(out));
                } catch (err) {
                  console.error('[FOLLOW_PROFILE] Bridge error', err);
                }
              }

              function buildUploadUrl(filename, imageType) {
                if (!filename) return '';
                let slug = String(filename).trim().replace(/^\/+/, '').replace(/^uploads\//, '');
                if (!slug.includes('/')) {
                  if (slug.length >= 4) {
                    slug = slug.slice(0, 2) + '/' + slug.slice(2, 4) + '/' + slug;
                  } else if (slug.length >= 2) {
                    slug = slug.slice(0, 2) + '/' + slug;
                  }
                }
                const hasExt = /\.[a-z0-9]+$/i.test(slug);
                const ext = hasExt ? '' : (imageType ? '.' + String(imageType).toLowerCase() : '');
                return 'https://playentry.org/uploads/' + slug + ext;
              }

              function normalizeUrl(raw) {
                if (!raw) return '';
                if (/^https?:\/\//i.test(raw)) return raw;
                if (raw.startsWith('//')) return 'https:' + raw;
                if (raw.startsWith('/')) return 'https://playentry.org' + raw;
                return 'https://playentry.org/' + raw;
              }

              function resolveCsrfFromDom() {
                try {
                  const meta = document.querySelector('meta[name="csrf-token"]');
                  if (meta && meta.content) return meta.content.trim();
                } catch (_err) {}
                try {
                  const nextData = window.__NEXT_DATA__;
                  const nextCsrf =
                    nextData?.props?.initialProps?.csrfToken ||
                    nextData?.props?.pageProps?.initialProps?.csrfToken;
                  if (nextCsrf) return nextCsrf;
                } catch (_err) {}
                try {
                  const script = document.getElementById('__NEXT_DATA__');
                  if (script && script.textContent) {
                    const parsed = JSON.parse(script.textContent);
                    const scriptCsrf =
                      parsed?.props?.initialProps?.csrfToken ||
                      parsed?.props?.pageProps?.initialProps?.csrfToken;
                    if (scriptCsrf) return scriptCsrf;
                  }
                } catch (_err) {}
                return null;
              }

              try {
                const domCsrf = resolveCsrfFromDom();
                if (domCsrf) {
                  csrf = domCsrf;
                }
                if (!token || !csrf) {
                  debug('missing_token_initial', {});
                  respond('missing_token', { ok: false, err: 'missing_token' });
                  return;
                }
                const refreshed = extractTokensFromDom();
                if (refreshed) {
                  token = refreshed.xtoken;
                  csrf = refreshed.csrfToken;
                }
                if (!token || !csrf) {
                  debug('missing_token_after_dom', {});
                  respond('missing_token', { ok: false, err: 'missing_token' });
                  return;
                }
                debug('token_ready', { tokenLen: token.length, csrfLen: csrf.length });
                try {
                  sessionStorage.setItem('entry_xToken', token);
                  sessionStorage.setItem('entry_csrfToken', csrf);
                  localStorage.setItem('entry_xToken', token);
                  localStorage.setItem('entry_csrfToken', csrf);
                } catch (cookieErr) {
                  console.warn('[FOLLOW_PROFILE] Failed to sync cookies', cookieErr);
                }
              const activeCsrf = csrf || '';
              const csrfHeaderPrefix = activeCsrf.substring(0, 16);
              const cookieCsrfPrefix = (document.cookie || '').split(';').map(s => s.trim()).find(s => s.startsWith('_csrf='))?.substring(6, 22) || '';
              const storageCsrfPrefix = (sessionStorage.getItem('entry_csrfToken') || localStorage.getItem('entry_csrfToken') || '').substring(0, 16);
              const hasPlayentryTokenCookie = (document.cookie || '').split(';').some(s => s.trim().startsWith('playentry_token='));
              debug('before_fetch', Object.assign({
                endpoint: GRAPHQL_ENDPOINT,
                operation: OPERATION_NAME,
                locationHref: location.href,
                referrer: document.referrer || '',
                userAgent: navigator.userAgent,
                cookieLen: (document.cookie || '').length,
                payloadLength: payloadJson.length,
                variableKeys: Object.keys(payload.variables || {}),
                queryPreview: payload.query.slice(0, 80),
                csrfHeaderPrefix,
                cookieCsrfPrefix,
                storageCsrfPrefix,
                csrfMatch: csrfHeaderPrefix && csrfHeaderPrefix === cookieCsrfPrefix,
                hasPlayentryTokenCookie
              }, diagSnapshot()));
                timeoutHandle = setTimeout(() => {
                  if (controller) controller.abort();
                  respond('timeout', { ok: false, err: 'timeout' });
                }, timeoutMs);
                fetch(GRAPHQL_ENDPOINT, {
                  method: 'POST',
                  headers: {
                    'content-type': 'application/json',
                    'x-client-type': 'Client',
                    'x-token': token,
                    'csrf-token': activeCsrf
                  },
                  credentials: FETCH_CREDENTIALS,
                  signal: controller ? controller.signal : undefined,
                  body: JSON.stringify(payload)
                }).then(async (res) => {
                  debug('fetch_response', { status: res.status });
                  const text = await res.text();
                  if (!res.ok) {
                    const headerInfo = {
                      server: res.headers.get('server') || '',
                      date: res.headers.get('date') || '',
                      contentType: res.headers.get('content-type') || '',
                      via: res.headers.get('via') || '',
                      xCache: res.headers.get('x-cache') || ''
                    };
                    respond('http_error', {
                      ok: false,
                      status: res.status,
                      bodySnippet: text ? text.slice(0, 200) : '',
                      headers: headerInfo
                    });
                    return;
                  }
                  if (!text) {
                    respond('empty_body', { ok: false, status: res.status, err: 'empty_body' });
                    return;
                  }
                  let json = null;
                  try {
                    json = JSON.parse(text);
                  } catch (err) {
                    debug('parse_error', { message: err && err.message ? err.message : 'parse_error' });
                    respond('parse_error', { ok: false, status: res.status, err: err?.message || 'parse_error' });
                    return;
                  }
                  if (json && Array.isArray(json.errors) && json.errors.length) {
                    debug('graphql_error', { errors: json.errors.length });
                    respond('graphql_error', { ok: false, status: res.status, err: 'graphql_error' });
                    return;
                  }
                  const user = json?.data?.userstatus || {};
                  const profile = user?.profileImage || {};
                  const filename = profile?.filename || '';
                  const imageType = profile?.imageType || '';
                  const imageUrl = profile?.imageUrl || '';
                  const picture = user?.picture || '';
                  const finalUrl = imageUrl || buildUploadUrl(filename, imageType) || normalizeUrl(picture);
                  debug('success', { hasUrl: !!finalUrl });
                  respond('success', {
                    ok: true,
                    status: res.status,
                    url: finalUrl || '',
                    filename,
                    imageType,
                    picture
                  });
                }).catch((err) => {
                  debug('fetch_exception', { message: err && err.message ? err.message : 'fetch_error' });
                  respond('fetch_exception', { ok: false, err: err && err.message ? err.message : 'fetch_error' });
                });
              } catch (err) {
                debug('outer_exception', { message: err && err.message ? err.message : 'js_error' });
                respond('js_error', { ok: false, err: err && err.message ? err.message : 'js_error' });
              }
            })();
        """.trimIndent()
    }

    private fun buildShareProfileGraphqlJs(projectId: String, xToken: String, csrfToken: String): String {
        val safeProjectId = JSONObject.quote(projectId)
        val safeToken = JSONObject.quote(xToken)
        val safeCsrf = JSONObject.quote(csrfToken)
        val safeShareQuery = JSONObject.quote(shareGraphqlQueryRaw)
        return """
            (() => {
              const projectId = $safeProjectId;
              let token = $safeToken;
              let csrf = $safeCsrf;
              const GRAPHQL_ENDPOINT = 'https://playentry.org/graphql';
              const OPERATION_NAME = 'SELECT_PROJECT_LITE';
              const hasAndroidApp = typeof AndroidApp !== 'undefined' && !!AndroidApp.postMessage;
              const FETCH_CREDENTIALS = 'include';
              const timeoutMs = 6000;
              let timeoutHandle = null;
              let sent = false;
              const controller = typeof AbortController !== 'undefined'
                ? new AbortController()
                : null;

              function debug(stage, extra) {
                if (!hasAndroidApp) return;
                try {
                  AndroidApp.postMessage(JSON.stringify({
                    type: 'SHARE_DEBUG',
                    stage,
                    projectId,
                    info: extra || {}
                  }));
                } catch (err) {
                  console.warn('[SHARE_PROFILE] debug send failed', err?.message || err);
                }
              }

              function diagSnapshot() {
                return {
                  location: location.href,
                  origin: location.origin,
                  referrer: document.referrer || '',
                  ua: navigator.userAgent,
                  cookieLen: (document.cookie || '').length,
                  credentialsMode: FETCH_CREDENTIALS,
                  canSetRefererHeader: false,
                  canSetOriginHeader: false
                };
              }

              function patchNextData(data){
                if (!data || typeof data !== 'object') return data;
                try {
                  const initUser = data?.props?.initialState?.common?.user;
                  const pageUser = data?.props?.pageProps?.initialState?.common?.user;
                  if (initUser && !pageUser) {
                    const pp = data.props.pageProps = data.props.pageProps || {};
                    const ppInit = pp.initialState = pp.initialState || {};
                    const ppCommon = ppInit.common = ppInit.common || {};
                    ppCommon.user = initUser;
                  } else if (!initUser && pageUser) {
                    const base = data.props.initialState = data.props.initialState || {};
                    const baseCommon = base.common = base.common || {};
                    baseCommon.user = pageUser;
                  }
                } catch (err) {
                  console.log('[SHARE_PROFILE] patch error', err?.message);
                }
                return data;
              }

              function extractTokensFromDom(){
                try {
                  const el = document.getElementById('__NEXT_DATA__');
                  if (!el || !el.innerText) return null;
                  const parsed = patchNextData(JSON.parse(el.innerText.trim() || '{}'));
                  if (window.__NEXT_DATA__) patchNextData(window.__NEXT_DATA__);
                  const userNode = parsed?.props?.initialState?.common?.user ||
                                   parsed?.props?.pageProps?.initialState?.common?.user;
                  const xtoken =
                    userNode?.xToken ||
                    sessionStorage.getItem('entry_xToken') ||
                    localStorage.getItem('entry_xToken') ||
                    localStorage.getItem('playentry_token');
                  const csrfToken =
                    parsed?.props?.initialProps?.csrfToken ||
                    parsed?.props?.pageProps?.initialProps?.csrfToken ||
                    sessionStorage.getItem('entry_csrfToken') ||
                    localStorage.getItem('entry_csrfToken');
                  if (xtoken && csrfToken) {
                    return { xtoken, csrfToken };
                  }
                } catch (err) {
                  console.log('[SHARE_PROFILE] extractTokens error', err?.message);
                }
                return null;
              }

              const GRAPHQL_QUERY = $safeShareQuery;
              const payload = {
                operationName: OPERATION_NAME,
                variables: { id: projectId, groupId: null },
                query: GRAPHQL_QUERY
              };
              const payloadJson = JSON.stringify(payload);

              function resolveCsrfFromDom() {
                try {
                  const meta = document.querySelector('meta[name="csrf-token"]');
                  if (meta && meta.content) return meta.content.trim();
                } catch (_err) {}
                try {
                  const nextData = window.__NEXT_DATA__;
                  const nextCsrf =
                    nextData?.props?.initialProps?.csrfToken ||
                    nextData?.props?.pageProps?.initialProps?.csrfToken;
                  if (nextCsrf) return nextCsrf;
                } catch (_err) {}
                try {
                  const script = document.getElementById('__NEXT_DATA__');
                  if (script && script.textContent) {
                    const parsed = JSON.parse(script.textContent);
                    const scriptCsrf =
                      parsed?.props?.initialProps?.csrfToken ||
                      parsed?.props?.pageProps?.initialProps?.csrfToken;
                    if (scriptCsrf) return scriptCsrf;
                  }
                } catch (_err) {}
                return null;
              }

              function gatherDiagnostics() {
                const storageToken =
                  sessionStorage.getItem('entry_xToken') ||
                  localStorage.getItem('entry_xToken') ||
                  localStorage.getItem('playentry_token') || '';
                const storageCsrf =
                  sessionStorage.getItem('entry_csrfToken') ||
                  localStorage.getItem('entry_csrfToken') || '';
                const cookieSnapshot = (document.cookie || '').slice(0, 600);
                const cookieCsrf = (cookieSnapshot.split(';').map(s => s.trim()).find(s => s.startsWith('_csrf=')) || '')
                  .replace(/^_csrf=/, '');
                return {
                  storageToken,
                  storageCsrf,
                  cookieSnapshot,
                  cookieCsrf
                };
              }

              function respond(stage, extra) {
                if (sent) return;
                sent = true;
                if (timeoutHandle) {
                  clearTimeout(timeoutHandle);
                  timeoutHandle = null;
                }
                const diag = Object.assign({}, gatherDiagnostics(), diagSnapshot());
                const out = Object.assign({
                  type: 'SHARE_RESULT',
                  projectId,
                  endpoint: GRAPHQL_ENDPOINT,
                  operation: OPERATION_NAME,
                  stage,
                  diagnostics: diag
                }, diag, extra || {});
                try {
                  AndroidApp.postMessage(JSON.stringify(out));
                } catch (err) {
                  console.error('[SHARE_PROFILE] Bridge error', err);
                }
              }

              function buildUploadUrl(filename, imageType) {
                if (!filename) return '';
                let slug = String(filename).trim().replace(/^\/+/, '').replace(/^uploads\//, '');
                if (!slug.includes('/')) {
                  if (slug.length >= 4) {
                    slug = slug.slice(0, 2) + '/' + slug.slice(2, 4) + '/' + slug;
                  } else if (slug.length >= 2) {
                    slug = slug.slice(0, 2) + '/' + slug;
                  }
                }
                const hasExt = /\.[a-z0-9]+$/i.test(slug);
                const ext = hasExt ? '' : (imageType ? '.' + String(imageType).toLowerCase() : '');
                return 'https://playentry.org/uploads/' + slug + ext;
              }

              function normalizeUrl(raw) {
                if (!raw) return '';
                if (/^https?:\/\//i.test(raw)) return raw;
                if (raw.startsWith('//')) return 'https:' + raw;
                if (raw.startsWith('/')) return 'https://playentry.org' + raw;
                return 'https://playentry.org/' + raw;
              }

              try {
                const domCsrf = resolveCsrfFromDom();
                if (domCsrf) {
                  csrf = domCsrf;
                }
                if (!token || !csrf) {
                  debug('missing_token_initial', {});
                  respond('missing_token', { ok: false, err: 'missing_token' });
                  return;
                }
                const refreshed = extractTokensFromDom();
                if (refreshed) {
                  token = refreshed.xtoken;
                  csrf = refreshed.csrfToken;
                }
                if (!token || !csrf) {
                  debug('missing_token_after_dom', {});
                  respond('missing_token', { ok: false, err: 'missing_token' });
                  return;
                }
                debug('token_ready', { tokenLen: token.length, csrfLen: csrf.length });
                try {
                  sessionStorage.setItem('entry_xToken', token);
                  sessionStorage.setItem('entry_csrfToken', csrf);
                  localStorage.setItem('entry_xToken', token);
                  localStorage.setItem('entry_csrfToken', csrf);
                } catch (storageErr) {
                  console.warn('[SHARE_PROFILE] Failed to sync storage', storageErr);
                }

                const activeCsrf = csrf || '';
                const csrfHeaderPrefix = activeCsrf.substring(0, 16);
                const cookieCsrfPrefix = (document.cookie || '').split(';').map(s => s.trim()).find(s => s.startsWith('_csrf='))?.substring(6, 22) || '';
                const storageCsrfPrefix = (sessionStorage.getItem('entry_csrfToken') || localStorage.getItem('entry_csrfToken') || '').substring(0, 16);
                const hasPlayentryTokenCookie = (document.cookie || '').split(';').some(s => s.trim().startsWith('playentry_token='));
                debug('before_fetch', Object.assign({
                  endpoint: GRAPHQL_ENDPOINT,
                  operation: OPERATION_NAME,
                  locationHref: location.href,
                  referrer: document.referrer || '',
                  userAgent: navigator.userAgent,
                  cookieLen: (document.cookie || '').length,
                  payloadLength: payloadJson.length,
                  variableKeys: Object.keys(payload.variables || {}),
                  queryPreview: payload.query.slice(0, 80),
                  csrfHeaderPrefix,
                  cookieCsrfPrefix,
                  storageCsrfPrefix,
                  csrfMatch: csrfHeaderPrefix && csrfHeaderPrefix === cookieCsrfPrefix,
                  hasPlayentryTokenCookie
                }, diagSnapshot()));

                timeoutHandle = setTimeout(() => {
                  if (controller) controller.abort();
                  respond('timeout', { ok: false, err: 'timeout' });
                }, timeoutMs);

                fetch(GRAPHQL_ENDPOINT, {
                  method: 'POST',
                  headers: {
                    'content-type': 'application/json',
                    'x-client-type': 'Client',
                    'x-token': token,
                    'csrf-token': activeCsrf
                  },
                  credentials: FETCH_CREDENTIALS,
                  signal: controller ? controller.signal : undefined,
                  body: payloadJson
                }).then(async (res) => {
                  debug('fetch_response', { status: res.status });
                  const text = await res.text();
                  if (!res.ok) {
                    const headerInfo = {
                      server: res.headers.get('server') || '',
                      date: res.headers.get('date') || '',
                      contentType: res.headers.get('content-type') || '',
                      via: res.headers.get('via') || '',
                      xCache: res.headers.get('x-cache') || ''
                    };
                    respond('http_error', {
                      ok: false,
                      status: res.status,
                      bodySnippet: text ? text.slice(0, 200) : '',
                      headers: headerInfo
                    });
                    return;
                  }
                  if (!text) {
                    respond('empty_body', { ok: false, status: res.status, err: 'empty_body' });
                    return;
                  }
                  let json = null;
                  try {
                    json = JSON.parse(text);
                  } catch (err) {
                    debug('parse_error', { message: err && err.message ? err.message : 'parse_error' });
                    respond('parse_error', { ok: false, status: res.status, err: err?.message || 'parse_error' });
                    return;
                  }
                  if (json && Array.isArray(json.errors) && json.errors.length) {
                    debug('graphql_error', { errors: json.errors.length });
                    respond('graphql_error', { ok: false, status: res.status, err: 'graphql_error' });
                    return;
                  }
                  const project = json?.data?.project || {};
                  const user = project?.user || {};
                  const profile = user?.profileImage || {};
                  const filename = profile?.filename || '';
                  const imageType = profile?.imageType || '';
                  const imageUrl = profile?.imageUrl || '';
                  const picture = user?.picture || '';
                  const nickname = user?.nickname || '';
                  const username = user?.username || '';
                  const thumb = project?.thumb || '';
                  const finalThumbUrl = normalizeUrl(thumb) || (thumb ? buildUploadUrl(thumb, 'png') : '');
                  respond('success', {
                    ok: true,
                    status: res.status,
                    url: finalUrl || '',
                    thumbUrl: finalThumbUrl || '',
                    filename,
                    imageType,
                    picture,
                    nickname,
                    username
                  });
                }).catch((err) => {
                  debug('fetch_exception', { message: err && err.message ? err.message : 'fetch_error' });
                  respond('fetch_exception', { ok: false, err: err && err.message ? err.message : 'fetch_error' });
                });
              } catch (err) {
                debug('outer_exception', { message: err && err.message ? err.message : 'js_error' });
                respond('js_error', { ok: false, err: err && err.message ? err.message : 'js_error' });
              }
            })();
        """.trimIndent()
    }

    private fun buildGraphqlParityTestJs(
        source: String,
        trigger: String,
        payloadJson: String,
        xToken: String,
        csrfToken: String
    ): String {
        val safeSource = JSONObject.quote(source)
        val safeTrigger = JSONObject.quote(trigger)
        val safePayload = JSONObject.quote(payloadJson)
        val safeToken = JSONObject.quote(xToken)
        val safeCsrf = JSONObject.quote(csrfToken)
        return """
            (() => {
              const source = $safeSource;
              const trigger = $safeTrigger;
              const payloadJson = $safePayload;
              const token = $safeToken;
              const csrf = $safeCsrf;
              const endpoint = 'https://playentry.org/graphql';
              const hasAndroidApp = typeof AndroidApp !== 'undefined' && !!AndroidApp.postMessage;
              const headers = {
                'content-type': 'application/json',
                'x-client-type': 'Client',
                'x-token': token,
                'csrf-token': csrf
              };
              const startedAt = Date.now();
              function send(extra) {
                if (!hasAndroidApp) return;
                try {
                  AndroidApp.postMessage(JSON.stringify(Object.assign({
                    type: 'GRAPHQL_PARITY_RESULT',
                    source,
                    trigger
                  }, extra || {})));
                } catch (_err) {}
              }
              fetch(endpoint, {
                method: 'POST',
                headers,
                credentials: 'include',
                body: payloadJson
              }).then(async (res) => {
                const text = await res.text();
                send({
                  ok: res.ok,
                  status: res.status,
                  durationMs: Date.now() - startedAt,
                  bodySnippet: text ? text.slice(0, 200) : '',
                  headers: {
                    server: res.headers.get('server') || '',
                    date: res.headers.get('date') || '',
                    contentType: res.headers.get('content-type') || '',
                    via: res.headers.get('via') || '',
                    xCache: res.headers.get('x-cache') || ''
                  }
                });
              }).catch((err) => {
                send({
                  ok: false,
                  durationMs: Date.now() - startedAt,
                  error: err && err.message ? err.message : String(err)
                });
              });
            })();
        """.trimIndent()
    }

    private fun runGraphqlParityProbe(trigger: String, payloadJson: String) {
        val xToken = preferenceManager.xToken
        val csrfToken = preferenceManager.csrfToken
        if (xToken.isNullOrBlank() || csrfToken.isNullOrBlank()) {
            AndroidLog.w("GRAPHQL_PARITY", "Skip parity probe ($trigger) - missing tokens")
            return
        }
        AndroidLog.d("GRAPHQL_PARITY", "Triggering parity probe: trigger=$trigger payloadLen=${payloadJson.length}")
        mainHandler.post {
            val mainView = mainWebView
            if (mainView != null) {
                val js = buildGraphqlParityTestJs("main", trigger, payloadJson, xToken, csrfToken)
                runGraphqlParityProbeOnWebView(mainView, "main", trigger, js)
            } else {
                AndroidLog.w("GRAPHQL_PARITY", "Main WebView null - cannot run parity probe ($trigger)")
            }
            ensureFollowGraphqlWebView(this@MainActivity)
            val followView = followGraphqlWebView
            if (followView != null) {
                val js = buildGraphqlParityTestJs("follow", trigger, payloadJson, xToken, csrfToken)
                runGraphqlParityProbeOnWebView(followView, "follow", trigger, js)
            } else {
                AndroidLog.w("GRAPHQL_PARITY", "Follow WebView null - cannot run parity probe ($trigger)")
            }
        }
    }

    private fun runGraphqlParityProbeOnWebView(webView: WebView, source: String, trigger: String, js: String) {
        runCatching { webView.evaluateJavascript(js, null) }.onFailure {
            AndroidLog.e(
                "GRAPHQL_PARITY",
                "Failed to inject parity JS (source=$source, trigger=$trigger): ${it.message}"
            )
        }
    }

    private suspend fun awaitShareGraphqlIdle(projectId: String) {
        val existing = shareGraphqlPageDeferred
        if (existing != null) {
            AndroidLog.d("SHARE_GRAPHQL", "Waiting for existing project load before $projectId")
            try {
                withTimeout(15000) { existing.await() }
            } catch (_: TimeoutCancellationException) {
            }
        }
    }

    private suspend fun prepareShareGraphqlContext(projectId: String): WebView? = withContext(Dispatchers.Main) {
        ensureShareGraphqlWebView(this@MainActivity)
        val targetWebView = shareGraphqlWebView ?: run {
            AndroidLog.e("SHARE_GRAPHQL", "shareGraphqlWebView is null")
            return@withContext null
        }
        if (shareGraphqlReadyProjectId?.equals(projectId, true) == true && shareGraphqlPageDeferred == null) {
            targetWebView.visibility = View.VISIBLE
            return@withContext targetWebView
        }
        awaitShareGraphqlIdle(projectId)
        val deferred = CompletableDeferred<Boolean>()
        shareGraphqlPageDeferred = deferred
        shareGraphqlLoadingProjectId = projectId
        val targetUrl = buildShareProjectPageUrl(projectId)
        AndroidLog.d("SHARE_GRAPHQL", "Loading project page for projectId=$projectId url=$targetUrl")
        return@withContext try {
            targetWebView.visibility = View.VISIBLE
            // ✅ 핵심 수정: 이전 페이지 완전히 정리 후 새 페이지 로드
            targetWebView.stopLoading()
            targetWebView.loadUrl("about:blank")
            delay(50)  // 짧은 딜레이로 이전 상태 정리
            targetWebView.loadUrl(targetUrl)
            val ok = withTimeoutOrNull(15000) { deferred.await() } == true
            if (!ok) {
                AndroidLog.w("SHARE_GRAPHQL", "Timed out while waiting for project page (projectId=$projectId)")
                completeShareGraphqlPage(false)
                null
            } else {
                targetWebView
            }
        } catch (e: Exception) {
            AndroidLog.e("SHARE_GRAPHQL", "Failed to load project page for projectId=$projectId", e)
            completeShareGraphqlPage(false)
            null
        }
    }

    private suspend fun requestShareProfileViaWebView(
        projectId: String
    ): GraphqlCallResult<ShareProfileGraphqlResult>? = shareGraphqlWebViewMutex.withLock {
        val xToken = preferenceManager.xToken
        val csrfToken = preferenceManager.csrfToken
        if (xToken.isNullOrBlank() || csrfToken.isNullOrBlank()) {
            AndroidLog.w("SHARE_GRAPHQL", "Stored tokens are missing for projectId=$projectId")
            return@withLock null
        }
        AndroidLog.d(
            "SHARE_GRAPHQL",
            "Using encrypted session for projectId=$projectId"
        )
        val targetWebView = prepareShareGraphqlContext(projectId) ?: run {
            AndroidLog.w("SHARE_GRAPHQL", "Project WebView unavailable for projectId=$projectId")
            return null
        }
        val deferred = CompletableDeferred<GraphqlCallResult<ShareProfileGraphqlResult>>()
        val started = withContext(Dispatchers.Main) {
            targetWebView.visibility = View.VISIBLE
            runCatching {
                targetWebView.evaluateJavascript(jsUserTokenSync, null)
            }.onFailure {
                AndroidLog.w("SHARE_GRAPHQL", "Token sync script failed: ${it.message}")
            }
            logGraphqlContext("SHARE_GRAPHQL", "before_fetch", targetWebView)
            val js = buildShareProfileGraphqlJs(projectId, xToken, csrfToken)
            return@withContext try {
                shareGraphqlLastUrl = targetWebView.url
                pendingShareGraphqlRequests[projectId]?.cancel()
                pendingShareGraphqlRequests[projectId] = deferred
                AndroidLog.d("SHARE_GRAPHQL", "Injecting JS fetch for projectId=$projectId (using shareGraphqlWebView)")
                targetWebView.evaluateJavascript(js, null)
                true
            } catch (e: Exception) {
                AndroidLog.e("SHARE_GRAPHQL", "evaluateJavascript failed: ${e.message}")
                pendingShareGraphqlRequests.remove(projectId)
                false
            }
        }
        if (!started) {
            deferred.cancel()
            return@withLock null
        }
        val result = try {
            withTimeout(4000) { deferred.await() }  // ✅ 타임아웃 단축 (6초 → 4초)
        } catch (t: TimeoutCancellationException) {
            AndroidLog.w("SHARE_GRAPHQL", "Timed out while waiting for JS result (projectId=$projectId)")
            invalidateShareGraphqlContext("timeout")
            null
        } finally {
            val pending = pendingShareGraphqlRequests[projectId]
            if (pending == null || pending == deferred) {
                pendingShareGraphqlRequests.remove(projectId)
            }
        }
        if (result == null) {
            if (!deferred.isCompleted) deferred.cancel()
            return@withLock null
        }
        result
    }

    private suspend fun awaitFollowGraphqlIdle(targetId: String) {
        val existing = followGraphqlPageDeferred
        if (existing != null) {
            val loadingId = followGraphqlLoadingUserId
            AndroidLog.d(
                "FOLLOW_GRAPHQL",
                "Waiting for existing profile load (current=$loadingId) before starting $targetId"
            )
            try {
                withTimeout(15000) { existing.await() }
            } catch (_: TimeoutCancellationException) {
            }
        }
    }

    private suspend fun prepareFollowGraphqlContext(userId: String): WebView? = withContext(Dispatchers.Main) {
        ensureFollowGraphqlWebView(this@MainActivity)
        val targetWebView = followGraphqlWebView ?: run {
            AndroidLog.e("FOLLOW_GRAPHQL", "followGraphqlWebView is null")
            return@withContext null
        }
        val normalizedId = userId.lowercase()
        if (isProfileUrlForUser(targetWebView.url, normalizedId) &&
            followGraphqlReadyUserId?.equals(normalizedId, true) == true
        ) {
            targetWebView.visibility = View.VISIBLE
            return@withContext targetWebView
        }

        awaitFollowGraphqlIdle(normalizedId)
        val deferred = CompletableDeferred<Boolean>()
        followGraphqlPageDeferred = deferred
        followGraphqlLoadingUserId = normalizedId
        followGraphqlReadyUserId = null
        val targetUrl = buildFollowProfilePageUrl(normalizedId)
        AndroidLog.d("FOLLOW_GRAPHQL", "Loading profile page for userId=$normalizedId url=$targetUrl")
        return@withContext try {
            targetWebView.visibility = View.VISIBLE
            // ✅ 핵심 수정: 이전 페이지 완전히 정리 후 새 페이지 로드
            targetWebView.stopLoading()
            targetWebView.loadUrl("about:blank")
            delay(50)  // 짧은 딜레이로 이전 상태 정리
            targetWebView.loadUrl(targetUrl)
            val ok = withTimeoutOrNull(15000) { deferred.await() } == true
            if (!ok) {
                AndroidLog.w("FOLLOW_GRAPHQL", "Timed out while waiting for profile page (userId=$normalizedId)")
                completeFollowGraphqlPage(false)
                null
            } else {
                targetWebView
            }
        } catch (e: Exception) {
            AndroidLog.e("FOLLOW_GRAPHQL", "Failed to load profile page for userId=$normalizedId", e)
            completeFollowGraphqlPage(false)
            null
        }
    }

    private suspend fun requestFollowProfileViaWebView(
        userId: String
    ): GraphqlCallResult<String>? = followGraphqlWebViewMutex.withLock {
        val xToken = preferenceManager.xToken
        val csrfToken = preferenceManager.csrfToken
        if (xToken.isNullOrBlank() || csrfToken.isNullOrBlank()) {
            AndroidLog.w("FOLLOW_GRAPHQL", "Stored tokens are missing for userId=$userId")
            return@withLock null
        }
        AndroidLog.d(
            "FOLLOW_GRAPHQL",
            "Using encrypted session for userId=$userId"
        )
        val targetWebView = prepareFollowGraphqlContext(userId) ?: run {
            AndroidLog.w("FOLLOW_GRAPHQL", "Profile WebView unavailable for userId=$userId")
            return@withLock null
        }
        val deferred = CompletableDeferred<GraphqlCallResult<String>>()
        val started = withContext(Dispatchers.Main) {
            targetWebView.visibility = View.VISIBLE
            runCatching {
                targetWebView.evaluateJavascript(jsUserTokenSync, null)
            }.onFailure {
                AndroidLog.w("FOLLOW_GRAPHQL", "Token sync script failed: ${it.message}")
            }
            logGraphqlContext("FOLLOW_GRAPHQL", "before_fetch", targetWebView)
            val js = buildFollowProfileGraphqlJs(userId, xToken, csrfToken)
            return@withContext try {
                followGraphqlLastUrl = targetWebView.url
                AndroidLog.d("FOLLOW_GRAPHQL", "Injecting JS fetch for userId=$userId (using followGraphqlWebView)")
                pendingFollowGraphqlRequests[userId]?.cancel()
                pendingFollowGraphqlRequests[userId] = deferred
                targetWebView.evaluateJavascript(js, null)
                true
            } catch (e: Exception) {
                AndroidLog.e("FOLLOW_GRAPHQL", "evaluateJavascript failed: ${e.message}")
                pendingFollowGraphqlRequests.remove(userId)
                false
            }
        }
        if (!started) {
            deferred.cancel()
            return@withLock null
        }
        val result = try {
            withTimeout(4000) { deferred.await() }  // ✅ 타임아웃 단축 (6초 → 4초)
        } catch (t: TimeoutCancellationException) {
            AndroidLog.w("FOLLOW_GRAPHQL", "Timed out while waiting for JS result (userId=$userId)")
            invalidateFollowGraphqlContext("timeout")
            null
        } finally {
            val pending = pendingFollowGraphqlRequests[userId]
            if (pending == null || pending == deferred) {
                pendingFollowGraphqlRequests.remove(userId)
            }
        }
        if (result == null) {
            if (!deferred.isCompleted) deferred.cancel()
            return@withLock null
        }
        result
    }

    private suspend fun fetchFollowProfileFromGraphql(userId: String): String? = followGraphqlMutex.withLock {
        if (!waitForSessionTokens()) {
            AndroidLog.w("PROFILE", "Tokens not ready for follow GraphQL (userId=$userId) - requesting refresh")
            if (!ensureTokensForGraphql(forceRefresh = true, reason = "follow_graphql_wait")) {
                AndroidLog.e("PROFILE", "Failed to refresh tokens for follow GraphQL (userId=$userId)")
                return@withLock null
            }
        }
        if (!waitForMainPageReady()) {
            AndroidLog.w("PROFILE", "Main page not ready for follow GraphQL (userId=$userId)")
            return@withLock null
        }
        repeat(2) { attempt ->
            val result = requestFollowProfileViaWebView(userId) ?: run {
                AndroidLog.w("FOLLOW_GRAPHQL", "Unable to dispatch JS fetch for userId=$userId (attempt=${attempt + 1})")
                return@withLock null
            }
            if (!result.authError) {
                return@withLock result.payload
            }
            AndroidLog.w(
                "FOLLOW_GRAPHQL",
                "Auth error during JS fetch for userId=$userId (attempt=${attempt + 1})"
            )
            if (!ensureTokensForGraphql(forceRefresh = true, reason = "follow_graphql_retry")) {
                AndroidLog.e("FOLLOW_GRAPHQL", "Token refresh failed while retrying userId=$userId")
                return@withLock null
            }
        }
        null
    }

    private suspend fun ensureTokensForGraphql(
        forceRefresh: Boolean = false,
        reason: String
    ): Boolean = tokenRefreshMutex.withLock {
        if (!forceRefresh && hasValidTokens()) return@withLock true

        val beforeXToken = preferenceManager.xToken.orEmpty()
        val beforeCsrf = preferenceManager.csrfToken.orEmpty()

        var refreshed = false
        val ready = waitForMainPageReady()
        if (ready) {
            refreshed = refreshTokensFromWebView(reason)
        } else {
            AndroidLog.w("PROFILE", "Main page not ready for token refresh ($reason)")
        }

        // ✅ refreshTokensFromWebView는 이미 mainWebView에 로드돼 있는 __NEXT_DATA__/localStorage에서
        // 토큰을 "다시 읽어오는" 것뿐이라, 서버가 이미 만료시킨 CSRF 토큰이면 매번 같은(죽은) 값을
        // "성공"으로 보고해버린다. 403 복구처럼 강제 갱신이 필요한 상황에서 토큰이 그대로면
        // 실질적으로 갱신되지 않은 것이므로, 페이지를 실제로 다시 불러와 새 CSRF를 발급받는
        // hidden bootstrap으로 반드시 넘어가게 한다.
        val tokenActuallyChanged = refreshed && (
            preferenceManager.xToken.orEmpty() != beforeXToken ||
            preferenceManager.csrfToken.orEmpty() != beforeCsrf
        )
        if (refreshed && forceRefresh && !tokenActuallyChanged) {
            AndroidLog.w("PROFILE", "Token refresh reported success but token unchanged ($reason) - forcing hidden bootstrap")
            refreshed = false
        }

        if (!refreshed) {
            AndroidLog.w("PROFILE", "Token refresh failed via main WebView ($reason) - using hidden bootstrap")
            val bootstrapped = bootstrapTokensViaHiddenWebView(reason)
            if (!bootstrapped) {
                AndroidLog.e("PROFILE", "Hidden token bootstrap failed ($reason)")
                return@withLock false
            }
        }
        waitForSessionTokens()
    }

    private fun processNextProfile() {
        if (profileWebViewBusy) return
        if (profileQueue.isEmpty()) return

        val key = profileQueue.removeFirst()
        val info = profileNotifInfoByKey[key]
        if (info == null) {
            profileInProgress.remove(key)
            processNextProfile()
            return
        }

        // ✅ template_28 (SHARE): GraphQL로 페이지 이동 없이 직접 조회
        if (info.notifType == "SHARE") {
            val projectId = info.userId
            if (projectId.isNullOrBlank()) {
                AndroidLog.w("PROFILE", "❌ SHARE type but no projectId for key=$key")
                handleProfileFailure(key, "no_project_id")
                return
            }

            AndroidLog.d("PROFILE", "🚀 Fetching SHARE profile via GraphQL for projectId=$projectId (key=$key)")

            // 타임아웃 설정 (GraphQL은 빠르므로 10초)
            val timeoutRunnable = Runnable {
                AndroidLog.w("PROFILE", "⏰ GraphQL Timeout for key=$key projectId=$projectId")
                handleProfileFailure(key, "graphql_timeout")
            }
            profileTimeoutRunnables[key] = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, 10000)

            // GraphQL 쿼리 실행 (코루틴)
            lifecycleScope.launch {
                try {
                    val result = fetchShareProfileFromGraphql(projectId)

                    // 타임아웃 취소
                    profileTimeoutRunnables.remove(key)?.let { mainHandler.removeCallbacks(it) }

                    if (result != null && result.avatarUrl.isNotBlank()) {
                        AndroidLog.d("PROFILE", "✅ SHARE GraphQL success: url=${result.avatarUrl} nickname=${result.nickname}")
                        updateNotificationWithProfile(key, result.avatarUrl)
                    } else {
                        AndroidLog.w("PROFILE", "❌ SHARE GraphQL returned null or empty")
                        handleProfileFailure(key, "graphql_empty")
                    }
                } catch (e: Exception) {
                    AndroidLog.e("PROFILE", "❌ SHARE GraphQL exception: ${e.message}")
                    profileTimeoutRunnables.remove(key)?.let { mainHandler.removeCallbacks(it) }
                    handleProfileFailure(key, "graphql_exception")
                }

                // 다음 프로필 처리
                profileInProgress.remove(key)
                profileNotifInfoByKey.remove(key)
                processNextProfile()
            }
            return
        }

        // ✅ template_27 (FOLLOW) 및 기타: WebView로 페이지 로딩
        ensureProfileWebView()
        profileWebViewBusy = true
        currentProfileKey = key

        val targetUrl = resolveProfilePageUrl(info)

        // 타임아웃 설정 (20초 - React 렌더링 + SVG/PNG 대비)
        val timeoutRunnable = Runnable {
            AndroidLog.w("PROFILE", "⏰ Timeout for key=$key user=${info.username}")
            handleProfileFailure(key, "timeout")
        }
        profileTimeoutRunnables[key] = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 20000)

        // ✅ 이전 페이지 클리어 (WebView 재사용 시 중요!)
        profileWebView?.apply {
            stopLoading()
            clearHistory()
            AndroidLog.d("PROFILE", "Cleared previous page, loading $targetUrl for ${info.username} (type=${info.notifType})")
            loadUrl(targetUrl)
        }
    }

    private fun releaseProfileWebView(key: String?) {
        if (key == null) return
        AndroidLog.d("PROFILE", "🧹 Releasing WebView for key=$key")

        // ✅ 타임아웃 제거
        profileTimeoutRunnables.remove(key)?.let { mainHandler.removeCallbacks(it) }

        // ✅ 진행 상태 클리어 (중요!)
        profileInProgress.remove(key)
        profileNotifInfoByKey.remove(key)
        profilePendingUpdates.remove(key)

        // ✅ 현재 작업이면 WebView 상태 리셋
        if (currentProfileKey == key) {
            try { profileWebView?.stopLoading() } catch (_: Exception) {}
            profileWebViewBusy = false
            currentProfileKey = null
            AndroidLog.d("PROFILE", "✅ Released, processing next profile...")
            processNextProfile()
        }
    }

    private fun handleProfileFailure(key: String?, reason: String) {
        if (key.isNullOrBlank()) return
        AndroidLog.w("PROFILE", "Profile extraction failed: key=$key reason=$reason")
        releaseProfileWebView(key)
        updateNotificationWithProfile(key, null)
    }

    private fun resolveProfilePageUrl(info: ProfileNotifInfo): String {
        val default = normalizeEntryUrl(info.deepLink) ?: "https://playentry.org/"
        return when (info.notifType) {
            "FOLLOW" -> {
                val id = info.userId?.takeIf { it.isNotBlank() }
                id?.let { "https://playentry.org/profile/$it/project?sort=created&term=all&isOpen=all" } ?: default
            }
            "SHARE", shareDomFallbackType -> {
                val projectId = info.userId?.takeIf { it.isNotBlank() }
                projectId?.let { "https://playentry.org/project/$it" } ?: default
            }
            else -> default
        }
    }

    private fun normalizeEntryUrl(raw: String?): String? {
        return sanitizeEntryUrlOrNull(raw)
    }

    private fun ensureProfileWebView() {
        if (profileWebView != null) return

        AndroidLog.d("PROFILE", "Creating unified profile WebView")
        profileWebView = WebView(this).apply {
            // ✅ WebView를 화면에 추가 (렌더링 파이프라인 유지)
            visibility = View.INVISIBLE
            layoutParams = ViewGroup.LayoutParams(1, 1)
            applyEmulatorLayerWorkaround()

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            hardenSessionWebView(this)
            webViewClient = object : SecureEntryWebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    AndroidLog.e("PROFILE", "Render process gone: didCrash=${renderProcessCrashState(detail)}")
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // ✅ 현재 로딩 중인 작업 확인
                    val key = currentProfileKey ?: run {
                        AndroidLog.w("PROFILE", "⚠️ No currentProfileKey on page finish (stale callback?), url=$url")
                        return
                    }
                    val info = profileNotifInfoByKey[key] ?: run {
                        AndroidLog.w("PROFILE", "⚠️ No profile info for key=$key (cleaned up?), url=$url")
                        return
                    }

                    // ✅ URL이 현재 작업과 일치하는지 확인 (race condition 방지)
                    val expectedUrl = resolveProfilePageUrl(info)
                    if (url != expectedUrl) {
                        AndroidLog.w("PROFILE", "⚠️ Stale page callback - expected=$expectedUrl, got=$url, currentKey=$key")
                        AndroidLog.w("PROFILE", "⚠️ This is likely from a previous failed/cancelled request, ignoring...")
                        return
                    }

                    AndroidLog.d("PROFILE", "📄 Page finished: $url")
                    AndroidLog.d("PROFILE", "📋 Expected: type=${info.notifType}, userId=${info.userId}, user=${info.username}")

                    // ✅ URL 검증: 올바른 페이지로 이동했는지 확인
                    val isValidUrl = when (info.notifType) {
                        "FOLLOW" -> {
                            // ✅ template_27: "https://playentry.org/profile/" 필수
                            val expectedUserId = info.userId
                            val valid = EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/profile") &&
                                (expectedUserId.isNullOrBlank() || url.contains("/profile/$expectedUserId"))

                            if (!valid) {
                                AndroidLog.w("PROFILE", "❌ template_27 wrong URL: expected https://playentry.org/profile/$expectedUserId, got $url")
                            } else {
                                AndroidLog.d("PROFILE", "✅ template_27 correct URL: $url")
                            }
                            valid
                        }
                        "SHARE", shareDomFallbackType -> {
                            // ✅ template_28: "https://playentry.org/project/" 필수
                            val expectedProjectId = info.userId  // SHARE에서는 userId 필드에 projectId 저장
                            val valid = EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/project") &&
                                (expectedProjectId.isNullOrBlank() || url.contains("/project/$expectedProjectId"))

                            if (!valid) {
                                AndroidLog.w("PROFILE", "❌ template_28 wrong URL: expected https://playentry.org/project/$expectedProjectId, got $url")
                            } else {
                                AndroidLog.d("PROFILE", "✅ template_28 correct URL: $url")
                            }
                            valid
                        }
                        else -> EntryOriginPolicy.isTrustedMainHost(url)
                    }

                    if (!isValidUrl) {
                        AndroidLog.w("PROFILE", "❌ Wrong page loaded, skipping extraction")
                        return
                    }

                    AndroidLog.d("PROFILE", "🚀 Starting profile extraction for ${info.username}")

                    // ✅ assets에서 적절한 프로필 추출 스크립트 로드 및 실행
                    val scriptFile = when (info.notifType) {
                        "FOLLOW" -> "findProfile27.js"  // template_27: 프로필 페이지
                        "SHARE", shareDomFallbackType -> "findProfile28.js"   // template_28: 프로젝트 페이지
                        else -> null
                    }

                    if (scriptFile == null) {
                        AndroidLog.w("PROFILE", "❌ Unknown notifType: ${info.notifType}")
                        handleProfileFailure(key, "unknown_type")
                        return
                    }

                    // assets에서 스크립트 로드
                    val profileScript = loadAssetAsString(scriptFile)
                    if (profileScript.isBlank()) {
                        AndroidLog.e("PROFILE", "❌ Failed to load $scriptFile from assets")
                        handleProfileFailure(key, "script_load_failed")
                        return
                    }

                    // 스크립트 실행 준비 (AUTO_RUN 플래그 설정 + Bridge key 설정)
                    val executeScript = """
                        window.FIND_PROFILE_${if (info.notifType == "FOLLOW") "27" else "28"}_AUTO_RUN = true;
                        window.CURRENT_PROFILE_KEY = ${JSONObject.quote(key)};

                        // 스크립트 로드
                        $profileScript

                        // 결과를 PROFILE_IMAGE 형식으로 변환하는 래퍼
                        window.findProfile${if (info.notifType == "FOLLOW") "27" else "28"}().then((result) => {
                            try {
                                const payload = {
                                    type: 'PROFILE_IMAGE',
                                    key: window.CURRENT_PROFILE_KEY,
                                    url: result.success ? (result.avatar || '') : '',
                                    error: result.success ? '' : (result.error || 'unknown')
                                };
                                console.log('[MainActivity] Sending result:', payload);
                                ProfileBridge.postMessage(JSON.stringify(payload));
                            } catch (err) {
                                console.error('[MainActivity] Bridge error:', err.message);
                            }
                        });
                    """.trimIndent()

                    view?.evaluateJavascript(executeScript, null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)

                    val key = currentProfileKey
                    val url = request?.url?.toString()

                    if (request?.isForMainFrame == true) {
                        AndroidLog.e("PROFILE", "❌ Main frame error for key=$key: ${error?.description} (${error?.errorCode}), url=$url")
                        if (key != null) {
                            handleProfileFailure(key, "webview_error: ${error?.description}")
                        }
                    } else {
                        AndroidLog.w("PROFILE", "⚠️ Resource error (non-main): ${error?.description}, url=$url")
                        // 리소스 에러는 무시 (메인 페이지만 중요)
                    }
                }
            }

            installEntryMessageListener(
                this,
                "ProfileBridge",
                "PROFILE",
                EntryOriginPolicy::isTrustedMainHost
            ) { message ->
                handleProfileMessage(message)
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(profileWebView, false)
        }
        // ✅ WebView를 화면에 추가 (필수!)
        (window.decorView as ViewGroup).addView(profileWebView)
        AndroidLog.d("PROFILE", "Profile WebView attached to window")
    }

    // ✅ buildProfileImageExtractJs 함수 제거됨
    // 이제 assets/findProfile27.js 및 assets/findProfile28.js 사용

    private fun handleProfileMessage(message: String) {
        try {
            if (message.contains("\"type\":\"PROFILE_IMAGE\"")) {
                val jo = JSONObject(message)
                val key = jo.optString("key")
                val profileUrl = jo.optString("url", "")
                val error = jo.optString("error")
                AndroidLog.d("PROFILE", "PROFILE_IMAGE result: key=$key url=$profileUrl error=$error")
                if (key.isNotBlank()) {
                    if (profileUrl.isNotBlank() && error.isBlank()) {
                        updateNotificationWithProfile(key, sanitizeImageUrl(profileUrl))
                    } else {
                        handleProfileFailure(key, error.ifBlank { "empty_url" })
                    }
                }
                return
            }

            // 레거시 UNIFIED_PROFILE 처리 (호환성)
            if (message.contains("\"type\":\"UNIFIED_PROFILE\"")) {
                val jo = JSONObject(message)
                val key = jo.optString("key")
                val profileUrl = jo.optString("url")
                val error = jo.optString("error")

                AndroidLog.d("PROFILE", "Legacy: key=$key, url=$profileUrl, error=$error")

                if (error.isNotBlank()) {
                    handleProfileFailure(key, error)
                } else {
                    updateNotificationWithProfile(key, profileUrl)
                }
            }
        } catch (e: Exception) {
            AndroidLog.e("PROFILE", "Failed to handle message: ${e.message}")
        }
    }

    private fun updateNotificationWithProfile(
        key: String,
        profileUrl: String?,
        thumbUrl: String? = null,
        actorHexId: String? = null
    ) {
        val info = profileNotifInfoByKey[key] ?: run {
            finishProfile(key)
            return
        }

        val sanitizedProfile = profileUrl?.takeIf { it.isNotBlank() }?.let { sanitizeImageUrl(it) }?.takeIf {
            it.isNotBlank() && !it.contains("EmptyImage") && !it.contains("/profileImage.svg")
        }
        val sanitizedThumb = thumbUrl?.takeIf { it.isNotBlank() }?.let { sanitizeImageUrl(it) }?.takeIf {
            it.isNotBlank() && !it.contains("EmptyImage")
        }

        val isShare = info.notifType == "SHARE"
        // SHARE 알림은 공개한 작품의 썸네일을 우선적으로 largeIcon으로 사용!
        // REMAKE, FOLLOW 등은 작성자/팔로워의 프로필 아바타를 우선적으로 largeIcon으로 사용!
        val primaryUrl = when {
            isShare -> sanitizedThumb ?: sanitizedProfile
            else -> sanitizedProfile ?: sanitizedThumb
        }

        if (primaryUrl.isNullOrBlank()) {
            deliverProfileResult(
                info = info,
                key = key,
                bitmap = null,
                profileUrl = sanitizedProfile,
                thumbUrl = sanitizedThumb,
                actorHexId = actorHexId
            )
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching { loadBitmapFromUrl(primaryUrl) }.onFailure {
                AndroidLog.e("PROFILE", "Failed to load bitmap on background: ${it.message}")
            }.getOrNull()
            withContext(Dispatchers.Main) {
                deliverProfileResult(
                    info = info,
                    key = key,
                    bitmap = bmp,
                    profileUrl = sanitizedProfile,
                    thumbUrl = sanitizedThumb,
                    actorHexId = actorHexId
                )
            }
        }
    }

    private fun deliverProfileResult(
        info: ProfileNotifInfo,
        key: String,
        bitmap: Bitmap?,
        profileUrl: String?,
        thumbUrl: String? = null,
        actorHexId: String? = null
    ) {
        val isShare = info.notifType == "SHARE"
        val finalIcon = bitmap ?: makeInitialsAvatar(info.username.ifBlank { "?" })

        AndroidLog.d(
            "PROFILE",
            "Updating notification: ${info.notifTitle} - ${info.notifText} (hasProfile=${bitmap != null}, isShare=$isShare, thumbUrl=$thumbUrl)"
        )

        val effectiveActorImage = profileUrl?.takeIf { it.isNotBlank() }
            ?: (if (!isShare) thumbUrl?.takeIf { it.isNotBlank() } else null)
        val effectiveProjectThumb = thumbUrl?.takeIf { it.isNotBlank() }
            ?: (if (isShare) profileUrl?.takeIf { it.isNotBlank() } else null)

        if (activeCommentNotifications.containsKey(info.tag)) {
            if (!effectiveActorImage.isNullOrBlank()) {
                updateCommentNotificationProfile(info.tag, effectiveActorImage)
            }
        } else {
            postSimpleNotification(
                key = info.tag,
                title = info.notifTitle,
                text = info.notifText,
                whenMillis = info.whenMillis,
                openUrl = info.deepLink,
                largeIcon = finalIcon,
                fixedId = info.notifId,
                topicId = info.topicId,
                actorHexId = actorHexId ?: info.userId?.takeIf { HEX_OBJECT_ID.matches(it) },
                actorImageUrl = effectiveActorImage,
                projectThumbnailUrl = effectiveProjectThumb,
                template = info.template
            )
        }

        if (!effectiveActorImage.isNullOrBlank()) {
            notifAvatarUrl[info.tag] = effectiveActorImage
            val targetTopicId = info.topicId?.takeIf { it.isNotBlank() } ?: "key:${info.tag}"
            lifecycleScope.launch(Dispatchers.IO) {
                notificationRepository.updateActorImage(targetTopicId, info.username, effectiveActorImage)
                notificationRepository.updateActorImage(info.tag, info.username, effectiveActorImage)
                notificationRepository.updateArchiveActorImage(targetTopicId, effectiveActorImage)
                if (info.username.isNotBlank() && info.username != "(알수없음)") {
                    notificationRepository.updateActorImageByActor(info.username, effectiveActorImage)
                }
            }
        }
        if (!effectiveProjectThumb.isNullOrBlank()) {
            val targetTopicId = info.topicId?.takeIf { it.isNotBlank() } ?: "key:${info.tag}"
            lifecycleScope.launch(Dispatchers.IO) {
                notificationRepository.updateProjectThumbnail(targetTopicId, effectiveProjectThumb)
                notificationRepository.updateProjectThumbnail(info.tag, effectiveProjectThumb)
                notificationRepository.updateArchiveProjectThumbnail(targetTopicId, effectiveProjectThumb)
            }
        }
        applyPendingProfileUpdates(key, effectiveActorImage, finalIcon, effectiveProjectThumb)
        finishProfile(key)
    }

    private fun applyPendingProfileUpdates(
        key: String,
        profileUrl: String?,
        bitmap: Bitmap?,
        projectThumbUrl: String? = null
    ) {
        val pendingList = profilePendingUpdates.remove(key) ?: return
        if (pendingList.isEmpty()) return

        pendingList.forEach { info ->
            val finalIcon = bitmap ?: makeInitialsAvatar(info.username.ifBlank { "?" })
            postSimpleNotification(
                key = info.tag,
                title = info.notifTitle,
                text = info.notifText,
                whenMillis = info.whenMillis,
                openUrl = info.deepLink,
                largeIcon = finalIcon,
                fixedId = info.notifId,
                topicId = info.topicId,
                actorImageUrl = profileUrl,
                projectThumbnailUrl = projectThumbUrl,
                template = info.template
            )
            if (!profileUrl.isNullOrBlank()) {
                notifAvatarUrl[info.tag] = profileUrl
                val targetTopicId = info.topicId?.takeIf { it.isNotBlank() } ?: "key:${info.tag}"
                lifecycleScope.launch(Dispatchers.IO) {
                    notificationRepository.updateActorImage(targetTopicId, info.username, profileUrl)
                    notificationRepository.updateActorImage(info.tag, info.username, profileUrl)
                    notificationRepository.updateArchiveActorImage(targetTopicId, profileUrl)
                    if (info.username.isNotBlank() && info.username != "(알수없음)") {
                        notificationRepository.updateActorImageByActor(info.username, profileUrl)
                    }
                }
            }
            if (!projectThumbUrl.isNullOrBlank()) {
                val targetTopicId = info.topicId?.takeIf { it.isNotBlank() } ?: "key:${info.tag}"
                lifecycleScope.launch(Dispatchers.IO) {
                    notificationRepository.updateProjectThumbnail(targetTopicId, projectThumbUrl)
                    notificationRepository.updateProjectThumbnail(info.tag, projectThumbUrl)
                    notificationRepository.updateArchiveProjectThumbnail(targetTopicId, projectThumbUrl)
                }
            }
        }
    }

    private fun finishProfile(key: String?) {
        if (key.isNullOrBlank()) return
        profileTimeoutRunnables.remove(key)?.let { mainHandler.removeCallbacks(it) }
        profileInProgress.remove(key)
        profileNotifInfoByKey.remove(key)
        profilePendingUpdates.remove(key)
        if (currentProfileKey == key) {
            profileWebViewBusy = false
            currentProfileKey = null
            // ✅ WebView 로딩 취소 (동기화 문제 방지)
            profileWebView?.stopLoading()
            processNextProfile()
        }
    }

    // ✅ 팔로잉 알림 업데이트 (프로필 이미지 적용) - 덮어쓰기 방지 버전
    private fun updateFollowNotificationWithProfile(userId: String, profileUrl: String) {
        val currentTag = followCurrentTagByUserId[userId] ?: return
        val id = followNotifIdByTag[currentTag] ?: stableIdFrom(currentTag)

        // ① 유효성: /uploads/ 포함 & 기본이미지 아님
        val valid = profileUrl.contains("/uploads/") &&
                    !profileUrl.contains("EmptyImage") &&
                    !profileUrl.contains("/profileImage.svg")
        if (!valid) {
            AndroidLog.d("FOLLOW_PROFILE", "Skip overwrite: invalid avatar $profileUrl")
            return
        }

        // ② 이미 좋은 아이콘 있으면 스킵(안정화)
        val prev = notifAvatarUrl[currentTag]
        if (prev != null && prev.contains("/uploads/")) {
            AndroidLog.d("FOLLOW_PROFILE", "Skip overwrite: already has good avatar: $prev")
            return
        }

        lifecycleScope.launch {
            val bmp = loadAvatarBitmap(profileUrl, 128) ?: run {
                AndroidLog.d("FOLLOW_PROFILE", "Skip overwrite: decode failed")
                return@launch
            }
            notifAvatarUrl[currentTag] = profileUrl

            val displayName = followNickById[userId] ?: userId.take(8)
            val deep = followDeepLinks[userId] ?: "https://playentry.org/"
            postFollowNotificationWithTag(currentTag, id, displayName, deep, bmp) // ← 같은 tag/id로 교체
            AndroidLog.d("FOLLOW_PROFILE", "Updated follow notification with profile: $profileUrl")
        }
    }
    
    // ✅ 팔로우 알림 전용 발송 (고정 tag/ID로 교체)
    private fun postFollowNotificationWithTag(tag: String, id: Int, displayName: String, deepLink: String, largeIcon: Bitmap?) {
        val intent = android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val safeDeepLink = sanitizeEntryUrlOrNull(deepLink)
        if (!safeDeepLink.isNullOrBlank()) {
            intent.putExtra("openUrl", safeDeepLink)
        }

        val pi = android.app.PendingIntent.getActivity(
            this,
            id,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, followChannelId)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildLockScreenPlaceholder(followChannelId, android.R.drawable.ic_menu_myplaces))
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)  // 사람 아이콘
            .setContentTitle("팔로잉")  // ✅ 통일된 제목
            .setContentText("${displayName}님이 나를 팔로잉 했어요")  // ✅ 통일된 내용 형식
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)

        // largeIcon 설정 (오른쪽 원형 프로필) - 없으면 이니셜 아바타 사용
        val iconBitmap = largeIcon ?: makeInitialsAvatar(displayName.ifBlank { "?" })
        builder.setLargeIcon(iconBitmap)

        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                nm.notify(tag, id, builder.build())
                AndroidLog.d("FOLLOW_NOTIF", "Posted follow notification: tag=$tag, id=$id, displayName=$displayName")
            } catch (e: Exception) {
                AndroidLog.e("FOLLOW_NOTIF", "Failed to post follow notification", e)
            }
        }
    }

    // ✅ 전용 숨김 WebView 준비 (attach 보장 + 렌더링 파이프라인 유지)
    private fun ensureHiddenWebView(context: Context) {
        if (hiddenWebView != null) return

        hiddenWebView = WebView(context).apply {
            // ✅ ① hiddenWebView 생성 시점 수정 (attach 보장 + 렌더링 파이프라인 유지)
            visibility = View.INVISIBLE // GONE ❌ → INVISIBLE ✅ (렌더링 파이프라인 유지)
            layoutParams = ViewGroup.LayoutParams(1, 1) // 최소 렌더 크기
            applyEmulatorLayerWorkaround()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true // 프로필 추출을 위해 필요
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = false // 이미지 렌더링 차단 → GPU 사용 0
                setSupportZoom(false)
            }

            hardenSessionWebView(this)
            // ✅ ② onPageFinished 이후에만 JS 주입하도록 수정
            webViewClient = object : SecureEntryWebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    AndroidLog.e("FOLLOW_EXTRACT", "Render process gone: didCrash=${renderProcessCrashState(detail)}")
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AndroidLog.d("FOLLOW_EXTRACT", "Page finished: $url")
                    
                    // ✅ ④ about:blank 감지 및 차단 로직 추가
                    if (url == "about:blank" || url?.contains("/signin") == true) {
                        AndroidLog.w("FOLLOW_EXTRACT", "Blocked on signin or blank: $url")
                        return
                    }
                    
                    // 프로필 페이지 로딩 완료 시 프로필 추출 JS 실행 (DOM 안정화 후)
                    if (url?.contains("/profile/") == true || url?.contains("/user/") == true) {
                        AndroidLog.d("FOLLOW_EXTRACT", "Profile page loaded, executing extraction JS")
                        view?.evaluateJavascript(buildProfileExtractJS("current_user"), null)
                        AndroidLog.d("FOLLOW_EXTRACT", "JS injected after page finished for current_user")
                    }
                }
            }

            installEntryMessageListener(this, "AndroidApp", "HIDDEN", { url ->
                EntryOriginPolicy.isTrustedHost(url, "playentry.org") ||
                    EntryOriginPolicy.isTrustedHost(url, "space.playentry.org")
            }) { msg -> onJsMessage(msg, "HIDDEN") }
        }

        // 쿠키와 세션을 메인 WebView와 공유
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWebView, false)
        // ✅ ① View 트리에 추가 (필수: 안 그러면 WebView 초기화 안 됨)
        (findViewById<ViewGroup>(android.R.id.content)).addView(hiddenWebView)
    }

    private fun ensureFollowGraphqlWebView(context: Context) {
        if (followGraphqlWebView != null) return

        followGraphqlWebView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            alpha = 0.02f
            setBackgroundColor(AndroidColor.TRANSPARENT)
            visibility = View.VISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            applyEmulatorLayerWorkaround()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = false
            }
            hardenSessionWebView(this)
            webViewClient = object : SecureEntryWebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    AndroidLog.e("FOLLOW_GRAPHQL", "Render process gone: didCrash=${renderProcessCrashState(detail)}")
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    logGraphqlIntercept("follow", request)
                    // ✅ 성능 최적화: 불필요한 리소스 차단 (GraphQL 컨텍스트에 불필요한 것들)
                    val url = request?.url?.toString()?.lowercase() ?: ""
                    if (shouldBlockResourceForGraphql(url)) {
                        return WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val readyId = extractUserIdFromUrl(url)?.lowercase()
                    val loadingId = followGraphqlLoadingUserId?.lowercase()
                    AndroidLog.d(
                        "FOLLOW_GRAPHQL",
                        "WebView finished loading (url=$url, readyId=$readyId, loadingId=$loadingId)"
                    )

                    // ✅ 핵심 수정: 현재 로드 중인 페이지가 아니면 완전히 무시 (stale redirect 방지)
                    if (loadingId != null && readyId != null && readyId != loadingId) {
                        AndroidLog.d("FOLLOW_GRAPHQL", "Ignoring stale page finish (expected=$loadingId, got=$readyId)")
                        return  // 이전 페이지의 redirect는 무시
                    }

                    if (!readyId.isNullOrBlank() && loadingId != null && readyId == loadingId) {
                        followGraphqlReadyUserId = readyId
                        completeFollowGraphqlPage(true)
                    } else if (loadingId != null && !url.isNullOrBlank() && url.contains(loadingId, true)) {
                        followGraphqlReadyUserId = loadingId
                        completeFollowGraphqlPage(true)
                    } else if (!readyId.isNullOrBlank() && loadingId == null) {
                        // loadingId가 없을 때만 readyUserId 설정 (로드 완료 후)
                        followGraphqlReadyUserId = readyId
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        AndroidLog.e("FOLLOW_GRAPHQL", "WebView error while loading profile: ${error?.description}")
                        completeFollowGraphqlPage(false)
                    }
                }
            }

            installEntryMessageListener(this, "AndroidApp", "FOLLOW", { url ->
                EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/profile")
            }) { message -> onJsMessage(message, "FOLLOW") }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            followGraphqlWebView?.let { setAcceptThirdPartyCookies(it, false) }
        }
        (findViewById<ViewGroup>(android.R.id.content)).addView(followGraphqlWebView)
    }

    private fun completeFollowGraphqlPage(success: Boolean) {
        followGraphqlPageDeferred?.let {
            if (!it.isCompleted) it.complete(success)
        }
        followGraphqlPageDeferred = null
        followGraphqlLoadingUserId = null
        if (!success) {
            followGraphqlReadyUserId = null
        }
    }

    private fun ensureShareGraphqlWebView(context: Context) {
        if (shareGraphqlWebView != null) return
        shareGraphqlWebView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            alpha = 0.02f
            setBackgroundColor(AndroidColor.TRANSPARENT)
            visibility = View.VISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            applyEmulatorLayerWorkaround()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = false
            }
            hardenSessionWebView(this)
            webViewClient = object : SecureEntryWebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    AndroidLog.e("SHARE_GRAPHQL", "Render process gone: didCrash=${renderProcessCrashState(detail)}")
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    logGraphqlIntercept("share", request)
                    // ✅ 성능 최적화: 불필요한 리소스 차단
                    val url = request?.url?.toString()?.lowercase() ?: ""
                    if (shouldBlockResourceForGraphql(url)) {
                        return WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val readyId = extractProjectIdFromUrl(url)?.lowercase()
                    val loadingId = shareGraphqlLoadingProjectId?.lowercase()
                    AndroidLog.d(
                        "SHARE_GRAPHQL",
                        "WebView finished loading (url=$url, readyId=$readyId, loadingId=$loadingId)"
                    )

                    // ✅ 핵심 수정: 현재 로드 중인 페이지가 아니면 완전히 무시 (stale redirect 방지)
                    if (loadingId != null && readyId != null && readyId != loadingId) {
                        AndroidLog.d("SHARE_GRAPHQL", "Ignoring stale page finish (expected=$loadingId, got=$readyId)")
                        return
                    }

                    if (!readyId.isNullOrBlank() && loadingId != null && readyId == loadingId) {
                        shareGraphqlReadyProjectId = readyId
                        completeShareGraphqlPage(true)
                    } else if (loadingId != null && !url.isNullOrBlank() && url.contains(loadingId, true)) {
                        shareGraphqlReadyProjectId = loadingId
                        completeShareGraphqlPage(true)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        AndroidLog.e("SHARE_GRAPHQL", "Project page error: ${error?.description}")
                        completeShareGraphqlPage(false)
                    }
                }
            }

            installEntryMessageListener(this, "AndroidApp", "SHARE", { url ->
                EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/project")
            }) { message -> onJsMessage(message, "SHARE") }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            shareGraphqlWebView?.let { setAcceptThirdPartyCookies(it, false) }
        }
        (findViewById<ViewGroup>(android.R.id.content)).addView(shareGraphqlWebView)
    }

    private fun completeShareGraphqlPage(success: Boolean) {
        shareGraphqlPageDeferred?.let {
            if (!it.isCompleted) it.complete(success)
        }
        shareGraphqlPageDeferred = null
        if (success) {
            shareGraphqlReadyProjectId = shareGraphqlLoadingProjectId
        } else {
            shareGraphqlReadyProjectId = null
        }
        shareGraphqlLoadingProjectId = null
    }


    private fun ensureTokenWebView(context: Context) {
        if (tokenWebView != null) return

        tokenWebView = WebView(context).apply {
            visibility = View.INVISIBLE
            layoutParams = ViewGroup.LayoutParams(1, 1)
            applyEmulatorLayerWorkaround()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadsImagesAutomatically = false

            hardenSessionWebView(this)
            webViewClient = object : SecureEntryWebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    AndroidLog.e("TOKEN_BOOTSTRAP", "Render process gone: didCrash=${renderProcessCrashState(detail)}")
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!tokenBootstrapPending) return
                    if (!EntryOriginPolicy.isTrustedMainHost(url)) {
                        AndroidLog.w("TOKEN_BOOTSTRAP", "Unexpected URL for token bootstrap: $url")
                        return
                    }
                    AndroidLog.d("TOKEN_BOOTSTRAP", "Page ready for bootstrap: $url")
                    view?.evaluateJavascript(jsTokenBootstrapper, null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        AndroidLog.e("TOKEN_BOOTSTRAP", "WebView error: ${error?.description}")
                        completeTokenBootstrap(false)
                    }
                }
            }

            installEntryMessageListener(
                this,
                "AndroidApp",
                "TOKEN",
                EntryOriginPolicy::isTrustedMainHost
            ) { message -> onJsMessage(message, "TOKEN") }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(tokenWebView, false)
        }
        (window.decorView as ViewGroup).addView(tokenWebView)
        AndroidLog.d("TOKEN_BOOTSTRAP", "Token WebView attached")
    }

    private fun completeTokenBootstrap(success: Boolean) {
        tokenBootstrapPending = false
        val deferred = tokenBootstrapDeferred
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(success)
        }
        tokenBootstrapDeferred = null
    }

    private suspend fun acquireGraphqlTokens(): Pair<String, String>? = withContext(Dispatchers.Main) {
        val webView = mainWebView ?: return@withContext null
        val currentUrl = webView.url.orEmpty()
        if (!isMainContentUrl(currentUrl)) {
            AndroidLog.w("GRAPHQL_TOKEN", "Main WebView not ready for token snapshot (url=$currentUrl)")
            return@withContext null
        }
        val deferred = CompletableDeferred<Pair<String, String>?>()
        pendingGraphqlTokenRequest?.cancel()
        pendingGraphqlTokenRequest = deferred
        return@withContext try {
            webView.evaluateJavascript(jsTokenSnapshot, null)
            webView.evaluateJavascript(jsGraphqlTokenExtractor, null)
            deferred
        } catch (e: Exception) {
            pendingGraphqlTokenRequest = null
            null
        }
    }?.let { withTimeoutOrNull(3000) { it.await() } }

    private suspend fun bootstrapTokensViaHiddenWebView(reason: String): Boolean {
        AndroidLog.d("TOKEN_BOOTSTRAP", "Starting hidden bootstrap ($reason)")
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            ensureTokenWebView(this@MainActivity)
            val webView = tokenWebView ?: run {
                AndroidLog.e("TOKEN_BOOTSTRAP", "tokenWebView is null")
                deferred.complete(false)
                return@withContext
            }
            tokenBootstrapPending = true
            tokenBootstrapDeferred?.cancel()
            tokenBootstrapDeferred = deferred
            val targetUrl = "https://playentry.org/?tokenBootstrap=" + SystemClock.elapsedRealtime()
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
            } catch (_: Exception) {}
            webView.postDelayed({
                try {
                    AndroidLog.d("TOKEN_BOOTSTRAP", "Loading $targetUrl")
                    webView.loadUrl(targetUrl)
                } catch (e: Exception) {
                    AndroidLog.e("TOKEN_BOOTSTRAP", "loadUrl failed: ${e.message}")
                    completeTokenBootstrap(false)
                }
            }, 120)
        }
        val result = withTimeoutOrNull(6000) { deferred.await() } ?: false
        if (!result) {
            AndroidLog.w("TOKEN_BOOTSTRAP", "Bootstrap timed out ($reason)")
            completeTokenBootstrap(false)
        }
        return result
    }

    private fun ensureAlarmPollerWebView() {
        if (alarmWebView != null) return
        alarmWebView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            alpha = 0.02f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            applyEmulatorLayerWorkaround()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = false
                setSupportZoom(false)
            }
            installEntryMessageListener(this, "AndroidApp", "ALARM", { url ->
                EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/alarm")
            }) { msg -> onJsMessage(msg, "ALARM") }
            hardenSessionWebView(this)
            webViewClient = object : SecureEntryWebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    AndroidLog.e("ALARM_POLLER", "Render process gone: didCrash=${renderProcessCrashState(detail)}")
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (EntryOriginPolicy.isTrustedPath(url, "playentry.org", "/alarm")) {
                        AndroidLog.d("ALARM_POLLER", "Alarm page finished: $url")
                        // ✅ fetch hook 주입: 페이지의 GraphQL 응답 가로채기
                        view?.evaluateJavascript("""
                            (function() {
                                if (window.__ENTRY_ALARM_FETCH_HOOKED__) return;
                                window.__ENTRY_ALARM_FETCH_HOOKED__ = true;
                                const originalFetch = window.fetch;
                                window.fetch = function(...args) {
                                    const url = args[0];
                                    return originalFetch.apply(this, args).then(async (response) => {
                                        if (url && url.includes('/graphql/SELECT_TOPICS') && !response.ok) {
                                            try {
                                                AndroidApp.postMessage('POLL_ERROR:' + response.status);
                                            } catch(_) {}
                                            return response;
                                        }
                                        // SELECT_TOPICS 응답만 가로채기
                                        if (url && url.includes('/graphql/SELECT_TOPICS')) {
                                            const cloned = response.clone();
                                            const text = await cloned.text();
                                            try {
                                                const json = JSON.parse(text);
                                                const list = json?.data?.topicList?.list;
                                                if (list && list.length > 0) {
                                                    console.log('[ALARM_HOOK] Intercepted', list.length, 'notifications');
                                                    AndroidApp.postMessage('NEW_NOTIF:' + JSON.stringify(list));
                                                }
                                            } catch (e) {
                                                console.error('[ALARM_HOOK] Parse error:', e.message);
                                                // "form tampered with" 같은 CSRF 에러 감지
                                                if (text && (text.includes('form tampered') || text.includes('csrf') || text.includes('forbidden'))) {
                                                    console.log('[ALARM_HOOK] CSRF/Auth error detected, requesting token refresh');
                                                    AndroidApp.postMessage('POLL_ERROR:403');
                                                }
                                            }
                                        }
                                        return response;
                                    }).catch((err) => {
                                        if (url && url.includes('/graphql/SELECT_TOPICS')) {
                                            try { AndroidApp.postMessage('POLL_ERROR:fetch'); } catch(_) {}
                                        }
                                        throw err;
                                    });
                                };
                                console.log('[ALARM_HOOK] fetch hook installed');
                            })();
                        """.trimIndent(), null)

                        // ✅ 토큰 정보(bootstrap)를 먼저 주입하여 토큰이 token poller에서 감지되게 함
                        alarmTokenBootstrapJs?.let { bootstrap ->
                            try {
                                AndroidLog.d("ALARM_POLLER", "Injecting bootstrap tokens into alarm WebView")
                                view?.evaluateJavascript(bootstrap, null)
                            } catch (e: Exception) {
                                AndroidLog.w("ALARM_POLLER", "Alarm bootstrap injection failed: ${e.message}")
                            }
                        }

                        // ✅ 약간의 지연 후에 알림 토큰/폴러 스크립트를 재주입
                        view?.postDelayed({
                            try {
                                view.evaluateJavascript(jsNotifPoller, null)
                                showTriggerToast("알림 폴러 JS 주입됨")
                            } catch (_: Exception) {
                                AndroidLog.w("ALARM_POLLER", "Notif poller reinjection failed")
                            }
                        }, 250)

                        alarmPollerStarted = true
                        alarmPollerPending = false
                        resetAlarmRestartBackoff()
                        AndroidLog.d("ALARM_POLLER", "Alarm page loaded - fetch hook installed")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    alarmPollerStarted = false
                    alarmPollerPending = false
                    val requestUrl = request?.url?.toString().orEmpty()
                    val code = error?.errorCode ?: 0
                    AndroidLog.e(
                        "ALARM_POLLER",
                        "WebView error: code=$code mainFrame=${request?.isForMainFrame == true} url=$requestUrl desc=${error?.description}"
                    )
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString().orEmpty()
                        if (code == WebViewClient.ERROR_UNKNOWN && desc.contains("ERR_ABORTED", ignoreCase = true)) {
                            AndroidLog.d("ALARM_POLLER", "Ignore aborted main-frame error during reload")
                            return
                        }
                        // ERROR_UNKNOWN(-1) / ERROR_CONNECT(-6) can happen transiently; restart with backoff.
                        scheduleAlarmPollerRestart("web_error:$code", minDelayMs = 1500L)
                    }
                }
            }

        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(alarmWebView, false)
        (findViewById<ViewGroup>(android.R.id.content)).addView(alarmWebView)
    }

    private fun logAlarmDiagnostics(reason: String) {
        val cm = CookieManager.getInstance()
        val cookieLen = runCatching { cm.getCookie("https://playentry.org")?.length ?: 0 }.getOrElse { -1 }
        val restoreAge = if (lastCookieRestoreAt == 0L) -1L else SystemClock.elapsedRealtime() - lastCookieRestoreAt
        val url = alarmWebView?.url.orEmpty()
        AndroidLog.d(
            "ALARM_POLLER",
            "[$reason] cookieLen=$cookieLen restoreAgeMs=$restoreAge pending=$alarmPollerPending started=$alarmPollerStarted url=$url"
        )
    }

    private fun resetAlarmRestartBackoff() {
        alarmRestartBackoffMs = 0L
    }

    private fun scheduleAlarmPollerRestart(reason: String, minDelayMs: Long = 1500L) {
        if (!isAppForeground) {
            AndroidLog.d("ALARM_POLLER", "Skip restart scheduling while app is background ($reason)")
            return
        }
        val hasTokens = !preferenceManager.xToken.isNullOrBlank() && !preferenceManager.csrfToken.isNullOrBlank()
        if (!hasTokens) {
            AndroidLog.w("ALARM_POLLER", "Skip restart scheduling - missing tokens ($reason)")
            return
        }
        val nextBackoff = if (alarmRestartBackoffMs <= 0L) minDelayMs else (alarmRestartBackoffMs * 2).coerceAtMost(15_000L)
        alarmRestartBackoffMs = nextBackoff
        alarmRecoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            alarmRecoveryRunnable = null
            if (!isAppForeground) return@Runnable
            val ready = !preferenceManager.xToken.isNullOrBlank() && !preferenceManager.csrfToken.isNullOrBlank()
            if (!ready) {
                AndroidLog.w("ALARM_POLLER", "Skip scheduled restart - tokens missing ($reason)")
                return@Runnable
            }
            AndroidLog.d("ALARM_POLLER", "Restarting alarm poller (reason=$reason delay=${alarmRestartBackoffMs}ms)")
            startAlarmBackgroundPoller(forceReload = true)
        }
        alarmRecoveryRunnable = runnable
        mainHandler.postDelayed(runnable, alarmRestartBackoffMs)
    }

    private fun startAlarmBackgroundPoller(forceReload: Boolean = false) {
        if (!isAppForeground) {
            AndroidLog.d("ALARM_POLLER", "Skip start while app is background (forceReload=$forceReload)")
            return
        }
        alarmRecoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        alarmRecoveryRunnable = null
        if (forceReload) {
            if (alarmPollerPending) {
                AndroidLog.d("ALARM_POLLER", "Skip forceReload - pending start in progress")
                return
            }
        } else if (isAlarmPollerActive()) {
            AndroidLog.d("ALARM_POLLER", "Already active (pending=$alarmPollerPending, started=$alarmPollerStarted) - skip start")
            return
        }
        val xToken = preferenceManager.xToken
        val csrfToken = preferenceManager.csrfToken
        if (xToken.isNullOrBlank() || csrfToken.isNullOrBlank()) {
            AndroidLog.w("ALARM_POLLER", "Cannot start background poller - missing tokens")
            redirectToLoginIfNeeded("missing_tokens")
            return
        }
        showTriggerToast("알림 폴러 시작")
        alarmPollerPending = true
        runOnUiThread {
            try {
                if (!forceMainPoller) {
                    try {
                        mainWebView?.evaluateJavascript(
                            "if (window.__ENTRY_POLLER__){ __ENTRY_POLLER__.stop(); delete window.__ENTRY_POLLER__; }",
                            null
                        )
                    } catch (_: Exception) {}
                }
                ensureAlarmPollerWebView()
                logAlarmDiagnostics("start")
                AndroidLog.d("ALARM_POLLER", "Starting alarm poller (forceReload=$forceReload, started=$alarmPollerStarted)")
                alarmTokenBootstrapJs = buildAlarmTokenBootstrapJs(xToken, csrfToken)
                if (forceReload) alarmPollerStarted = false
                val webView = alarmWebView
                if (webView == null) {
                    alarmPollerPending = false
                    AndroidLog.e("ALARM_POLLER", "alarmWebView is null - cannot load alarm page")
                    return@runOnUiThread
                }
                try {
                    val targetUrl = "https://playentry.org/alarm?u=" + System.nanoTime()
                    if (forceReload || !alarmPollerStarted) {
                        try { webView.stopLoading() } catch (_: Exception) {}
                        alarmStartRunnable?.let { mainHandler.removeCallbacks(it) }
                        val startRunnable = Runnable {
                            alarmStartRunnable = null
                            AndroidLog.d("ALARM_POLLER", "Loading alarm page: $targetUrl")
                            try { webView.loadUrl(targetUrl) } catch (err: Exception) {
                                alarmPollerPending = false
                                AndroidLog.e("ALARM_POLLER", "loadUrl failed: ${err.message}")
                            }
                        }
                        alarmStartRunnable = startRunnable
                        mainHandler.postDelayed(startRunnable, 50)
                    } else {
                        try {
                            webView.evaluateJavascript("if (window.__ENTRY_POLLER__){ __ENTRY_POLLER__.stop(); delete window.__ENTRY_POLLER__; }", null)
                            webView.evaluateJavascript(jsNotifPoller, null)
                        } catch (e: Exception) {
                            alarmPollerPending = false
                            AndroidLog.e("ALARM_POLLER", "Reinject failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    alarmPollerPending = false
                    AndroidLog.e("ALARM_POLLER", "Unexpected error scheduling alarm load", e)
                }
            } catch (e: Exception) {
                alarmPollerPending = false
                AndroidLog.e("ALARM_POLLER", "Failed to start alarm poller", e)
            }
        }
    }

    private fun stopAlarmBackgroundPoller(reason: String) {
        alarmPollerPending = false
        alarmPollerStarted = false
        alarmStartRunnable?.let { mainHandler.removeCallbacks(it) }
        alarmStartRunnable = null
        alarmRecoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        alarmRecoveryRunnable = null
        resetAlarmRestartBackoff()
        runOnUiThread {
            val webView = alarmWebView ?: return@runOnUiThread
            try {
                webView.stopLoading()
            } catch (_: Exception) {}
            try {
                webView.evaluateJavascript(
                    "if (window.__ENTRY_POLLER__){ __ENTRY_POLLER__.stop(); delete window.__ENTRY_POLLER__; }",
                    null
                )
            } catch (_: Exception) {}
        }
        AndroidLog.d("ALARM_POLLER", "Stopped alarm poller ($reason)")
    }


    // URL에서 userId 추출 (프로필 URL 또는 일반 URL에서)
    private fun extractUserIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(url)
            val path = uri.path ?: ""
            
            // 프로필 URL 패턴: /profile/{userId}/project
            val profileMatch = Regex("/profile/([^/]+)/project").find(path)
            if (profileMatch != null) return profileMatch.groupValues[1]

            // 단일 프로필 페이지 패턴: /profile/{userId}
            val profileSimple = Regex("/profile/([0-9a-f]{24})", RegexOption.IGNORE_CASE).find(path)
            if (profileSimple != null) return profileSimple.groupValues[1]
            
            // 일반 URL에서 userId 추출 시도
            val generalMatch = Regex("/([a-f0-9]{24})", RegexOption.IGNORE_CASE).find(path)
            generalMatch?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private fun isProfileUrlForUser(url: String?, userId: String): Boolean {
        if (url.isNullOrBlank() || userId.isBlank()) return false
        val fromUrl = extractUserIdFromUrl(url)?.lowercase() ?: return false
        return fromUrl == userId.lowercase()
    }

    private fun buildFollowProfilePageUrl(userId: String): String {
        val normalized = userId.lowercase()
        val ts = SystemClock.elapsedRealtime()
        return "https://playentry.org/profile/$normalized?from=follow_graphql&ts=$ts"
    }

    private fun quiesceGraphqlWebView(webView: WebView?) {
        webView ?: return
        runCatching { webView.stopLoading() }
        webView.visibility = View.INVISIBLE
    }

    private fun invalidateFollowGraphqlContext(reason: String) {
        followGraphqlReadyUserId = null
        followGraphqlLoadingUserId = null
        followGraphqlPageDeferred?.cancel()
        followGraphqlPageDeferred = null
        AndroidLog.d("FOLLOW_GRAPHQL", "Context invalidated ($reason)")
    }

    private fun invalidateShareGraphqlContext(reason: String) {
        shareGraphqlReadyProjectId = null
        shareGraphqlLoadingProjectId = null
        shareGraphqlPageDeferred?.cancel()
        shareGraphqlPageDeferred = null
        AndroidLog.d("SHARE_GRAPHQL", "Context invalidated ($reason)")
    }
    private fun extractProjectIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val match = Regex("/project/([0-9a-f]{24})", RegexOption.IGNORE_CASE).find(url)
            match?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private fun isProjectUrlForId(url: String?, projectId: String): Boolean {
        if (url.isNullOrBlank() || projectId.isBlank()) return false
        val extracted = extractProjectIdFromUrl(url)?.lowercase() ?: return false
        return extracted == projectId.lowercase()
    }

    private fun buildShareProjectPageUrl(projectId: String): String {
        val ts = SystemClock.elapsedRealtime()
        return "https://playentry.org/project/$projectId?from=share_graphql&ts=$ts"
    }

    // 시간 문자열 파싱 (API 레벨 호환)
    private fun parseWhen(timeStr: String?): Long {
        return try {
            if (timeStr.isNullOrBlank()) System.currentTimeMillis()
            else {
                // API 레벨 26+ 에서만 java.time.Instant 사용
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val parsed = java.time.Instant.parse(timeStr).toEpochMilli()
                        parsed
                    } catch (_: Exception) {
                        // Instant 파싱 실패 시 SimpleDateFormat으로 폴백
                        parseWithSimpleDateFormat(timeStr)
                    }
                } else {
                    // API 레벨 26 미만에서는 SimpleDateFormat만 사용
                    parseWithSimpleDateFormat(timeStr)
                }
            }
        } catch (_: Exception) { System.currentTimeMillis() }
    }
    
    private fun parseWithSimpleDateFormat(timeStr: String): Long {
        return try {
            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).parse(timeStr)
            parsed?.time ?: System.currentTimeMillis()
        } catch (_: Exception) { 
            // 다른 형식들도 시도
            try {
                val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault()).parse(timeStr)
                parsed?.time ?: System.currentTimeMillis()
            } catch (_: Exception) { System.currentTimeMillis() }
        }
    }
    
    private val PROFILE_CACHE_ENABLED = false

    // 프로필 이미지 캐시 관리
    private fun getCachedProfileImage(user: String): String? {
        if (!PROFILE_CACHE_ENABLED) return null
        return getSharedPreferences("profile_cache", MODE_PRIVATE).getString("user_$user", null)
    }
    
    private fun saveProfileImageToCache(user: String, url: String) {
        if (!PROFILE_CACHE_ENABLED) return
        getSharedPreferences("profile_cache", MODE_PRIVATE).edit()
            .putString("user_$user", url)
            .apply()
    }
    
    // ✅ 숨겨진 WebView에서 DOM 기반 프로필 추출 JS (정규식 에러 수정 버전)
    private fun buildProfileExtractJS(username: String) = """
(function(){
  try {
    // URL 내 /profile/{24hex}에서 userId 추출 (알림 매칭용)
    var m = String(location.pathname).match(/\/profile\/([0-9a-f]{24})/i);
    var userId = m ? m[1] : "";

    var tries = 0, maxTries = 10; // 300ms 간격으로 최대 3초 재시도

    function grabUrl(){
      console.log('[PROFILE_EXTRACT] Starting profile image extraction...');

      function isBannerNode(el){
        if (!el || !el.closest) return false;
        if (el.matches && el.matches('[data-testid="avatarButton"]')) return true;
        if (el.closest('[data-testid="avatarButton"]')) return true;
        return !!el.closest('header,[role="banner"],[data-testid*="global"],[data-testid*="layout-header"]');
      }

      function tryPickFromNode(el){
        if (!el || isBannerNode(el)) return '';
        var styleAttr = (el.getAttribute('style') || '').replace(/&quot;/g, '"');
        var urls = [];
        var re = /url\(["']?([^"')]+)["']?\)/g;
        var mm;
        while ((mm = re.exec(styleAttr)) !== null) urls.push(mm[1]);
        if (!urls.length && window.getComputedStyle) {
          try {
            var bg = getComputedStyle(el).backgroundImage || '';
            bg = bg.replace(/&quot;/g, '"');
            re = /url\(["']?([^"')]+)["']?\)/g;
            while ((mm = re.exec(bg)) !== null) urls.push(mm[1]);
          } catch(_) {}
        }
        for (var j=0; j<urls.length; j++) {
          var u = urls[j].trim();
          if (u.indexOf('/uploads/') >= 0 && u.indexOf('EmptyImage') < 0) {
            return u;
          }
        }
        return '';
      }

      function tryPickFromTree(el){
        if (!el || isBannerNode(el)) return '';
        var picked = tryPickFromNode(el);
        if (picked) return picked;
        if (!el.querySelectorAll) return '';
        try {
          var nested = el.querySelectorAll('[style*="background-image"], [style*="/uploads/"]');
          for (var ni=0; ni<nested.length; ni++) {
            if (isBannerNode(nested[ni])) continue;
            picked = tryPickFromNode(nested[ni]);
            if (picked) return picked;
          }
        } catch(_) {}
        return '';
      }

      function normalizeText(value){
        return (value || '').replace(/\s+/g, '');
      }

      function searchAroundAnchor(anchor, label){
        var current = anchor;
        if (!current || isBannerNode(current)) return '';
        for (var depth=0; depth<6 && current; depth++) {
          if (isBannerNode(current)) break;
          var fromSelf = tryPickFromTree(current);
          if (fromSelf) {
            console.log('[PROFILE_EXTRACT] ✅ Selected from ' + label + ' anchor (depth ' + depth + '):', fromSelf);
            return fromSelf;
          }
          var parent = current.parentElement;
          if (!parent || isBannerNode(parent)) break;
          var fromParent = tryPickFromTree(parent);
          if (fromParent) {
            console.log('[PROFILE_EXTRACT] ✅ Selected from ' + label + ' ancestor (depth ' + depth + '):', fromParent);
            return fromParent;
          }
          var siblings = parent.children || [];
          for (var si=0; si<siblings.length; si++) {
            var sibling = siblings[si];
            if (sibling === current || isBannerNode(sibling)) continue;
            var fromSibling = tryPickFromTree(sibling);
            if (fromSibling) {
              console.log('[PROFILE_EXTRACT] ✅ Selected from ' + label + ' sibling (depth ' + depth + '):', fromSibling);
              return fromSibling;
            }
          }
          current = parent;
        }
        return '';
      }

      function pickViaKeywords(){
        var selectors = 'div,section,article,span,strong,p,button,a';
        var nodes = document.querySelectorAll(selectors);
        for (var i=0; i<nodes.length; i++) {
          var node = nodes[i];
          if (!node || !node.textContent || isBannerNode(node)) continue;
          var text = normalizeText(node.textContent);
          if (!text) continue;
          if (text.indexOf('팔로잉') >= 0 && text.indexOf('팔로워') >= 0) {
            var res = searchAroundAnchor(node, 'follow-keyword');
            if (res) return res;
          }
          if (text.indexOf('대표배지') >= 0) {
            var badgeRes = searchAroundAnchor(node, 'badge-keyword');
            if (badgeRes) return badgeRes;
          }
        }
        return '';
      }

      function pickViaFollowAction(){
        var selectors = 'div,section,article,span,strong,p,button,a';
        var nodes = document.querySelectorAll(selectors);

        var actionAnchors = document.querySelectorAll('.follow, .following, .cancel, [class*="follow"]');
        for (var ai=0; ai<actionAnchors.length; ai++) {
          var anchor = actionAnchors[ai];
          if (!anchor || isBannerNode(anchor)) continue;
          var viaAnchor = searchAroundAnchor(anchor, 'follow-action');
          if (viaAnchor) return viaAnchor;
        }

        for (var i=0; i<nodes.length; i++) {
          var node = nodes[i];
          if (!node || !node.textContent || isBannerNode(node)) continue;
          var text = normalizeText(node.textContent);
          if (!text) continue;
          if (text.indexOf('팔로우취소') >= 0 || (text.indexOf('팔로우') >= 0 && text.indexOf('팔로잉') >= 0 && text.indexOf('팔로워') < 0)) {
            var act = searchAroundAnchor(node, 'follow-action');
            if (act) return act;
          }
        }
        return '';
      }

      function pickViaFollowCard(){
        var lowials = document.querySelectorAll('[class*="lowial"]');
        for (var i=0; i<lowials.length; i++) {
          var node = lowials[i];
          if (!node || isBannerNode(node)) continue;
          var picked = tryPickFromTree(node);
          if (picked) {
            console.log('[PROFILE_EXTRACT] ✅ Selected from lowial node #' + i + ':', picked);
            return picked;
          }
        }

        var cards = document.querySelectorAll('[class*="css-x4ht6q"],[class*="css-14rorqq"],[class*="e1e59sjh"]');
        for (var ci=0; ci<cards.length; ci++) {
          var card = cards[ci];
          if (!card || isBannerNode(card)) continue;
          var scoped = tryPickFromTree(card);
          if (scoped) {
            console.log('[PROFILE_EXTRACT] ✅ Selected from follow card #' + ci + ':', scoped);
            return scoped;
          }
        }
        return '';
      }

      var picked = '';

      // 0) 클래스 비의존 방법: strong 태그 기준으로 가장 가까운 background-image 찾기
      if (!picked) {
        try {
          console.log('[PROFILE_EXTRACT] Trying class-free method (strong tag based)');
          var strongElements = document.querySelectorAll('strong');
          for (var si=0; si<strongElements.length && !picked; si++) {
            var strong = strongElements[si];
            if (isBannerNode(strong)) continue;
            var current = strong;
            for (var depth=0; depth<5 && current && !picked; depth++) {
              current = current.parentElement;
              if (!current) break;
              if (isBannerNode(current)) break;
              picked = tryPickFromNode(current);
              if (picked) {
                console.log('[PROFILE_EXTRACT] ✅ Selected from strong tag ancestor:', picked);
                break;
              }
            }
          }
        } catch(_) {
          console.log('[PROFILE_EXTRACT] Class-free method failed');
        }
      }

      // 1) 팔로우 버튼 텍스트 기반 탐색
      if (!picked) {
        console.log('[PROFILE_EXTRACT] Trying follow-action anchors (팔로우/팔로우 취소)');
        picked = pickViaFollowAction();
      }

      // 2) 팔로워 키워드 기반 탐색 (팔로잉/팔로워/대표 배지 Anchors)
      if (!picked) {
        console.log('[PROFILE_EXTRACT] Trying keyword-based anchors (팔로잉/팔로워, 대표 배지)');
        picked = pickViaKeywords();
      }
      
      // 3) 팔로우 카드 컨테이너 직접 탐색
      if (!picked) {
        console.log('[PROFILE_EXTRACT] Trying follow card containers (css-lowial 등)');
        picked = pickViaFollowCard();
      }
      
      // 4) 못 찾았으면 다른 style 요소들 탐색
      if (!picked) {
        console.log('[PROFILE_EXTRACT] Keyword anchors failed, searching all elements with /uploads/');
        var cands = Array.from(document.querySelectorAll('[style*="/uploads/"]'));

        for (var i=0; i<cands.length && !picked; i++){
          if (isBannerNode(cands[i])) continue;
          picked = tryPickFromNode(cands[i]);
          if (picked) {
            console.log('[PROFILE_EXTRACT] ✅ Selected from other element:', picked);
            break;
          }
        }
      }

      // 못 찾았으면 재시도
      if (!picked) {
        if (++tries < maxTries) return setTimeout(grabUrl, 300);
        // 끝까지 못 찾으면 에러로 통지
        try { AndroidApp.postMessage(JSON.stringify({type:'PROFILE_RESULT', userId:userId, url:'', error:'no_url'})); } catch(_){}
        return;
      }

      // 절대경로화
      var abs = /^https?:\/\//i.test(picked) ? picked
               : (location.origin + (picked.startsWith('/') ? picked : '/' + picked));

      // 결과 전송
      try { AndroidApp.postMessage(JSON.stringify({type:'PROFILE_RESULT', userId:userId, url:abs, error:''})); } catch(_){}
    }

    grabUrl();
          } catch(e){
    try { AndroidApp.postMessage(JSON.stringify({type:'PROFILE_RESULT', userId:'', url:'', error:String(e && e.message || e)})); } catch(_){}
          }
        })();
    """.trimIndent()

    // ✅ 프로필 결과 처리 및 알림 갱신 (PROFILE_RESULT 타입 지원) - 개선된 버전
    private fun handleProfileResult(result: String?, username: String) {
        try {
            val raw = result ?: return
            AndroidLog.d("FOLLOW_PROFILE", "Processing profile result: $raw")

            // PROFILE_RESULT 타입 처리
            if (raw.contains("\"type\":\"PROFILE_RESULT\"")) {
                val jo = runCatching { JSONObject(raw) }.getOrNull() ?: return
                val type = jo.optString("type")
                if (type == "PROFILE_RESULT") {
                    val userId = jo.optString("userId")
                    val profileUrl = jo.optString("url")
                    val error = jo.optString("error")
                    
                    // ✅ 성공/실패 여부와 관계없이 알림 표시
                    if (!error.isNullOrBlank()) {
                        AndroidLog.w("FOLLOW_PROFILE", "Profile fetch error for $userId: $error")
                        if (userId.isNotBlank()) updateFollowNotificationWithProfile(userId, "")
                    } else if (profileUrl.isNullOrBlank()) {
                        AndroidLog.d("FOLLOW_PROFILE", "No profile URL found for $userId")
                        if (userId.isNotBlank()) updateFollowNotificationWithProfile(userId, "")
                    } else {
                        AndroidLog.d("FOLLOW_PROFILE", "Profile URL found for $userId: $profileUrl")
                        if (userId.isNotBlank()) updateFollowNotificationWithProfile(userId, profileUrl)
                    }
                    
                    // ✅ 성공/실패 여부와 관계없이 "처리 완료" 마킹 (재시도 방지)
                    val followKey = "$userId|${followDeepLinks[userId]}"
                    followProfileApplied.add(followKey)
                    followInProgress.remove(followKey)
                    AndroidLog.d("FOLLOW_PROFILE", "Marked as processed: $followKey")
                    return
                }
            }

            // 기존 방식 (URL 단독/JSONString 모두 허용)
            val jsonText = if (raw.startsWith("http")) {
                """{"profileImage":"$raw","href":""}"""
            } else {
                if (raw.startsWith("\"") && raw.endsWith("\""))
                    raw.substring(1, raw.length - 1).replace("\\\"", "\"")
                else raw
            }

            val jo = runCatching { JSONObject(jsonText) }.getOrNull() ?: return
            val href = jo.optString("href")
            if (href.isNotBlank() && !href.contains("/profile/")) {
                // 프로필 페이지가 아니면 무시 (홈/에러에서 긁는 문제 차단)
                AndroidLog.w("FOLLOW_PROFILE", "Ignored (not profile page): $href")
                return
            }

            val img = jo.optString("profileImage")
            if (img.isNullOrBlank()) return
            if (img.contains("/img/defaultOgImage") || img.contains("EmptyImage")) {
                AndroidLog.d("FOLLOW_PROFILE", "Ignored default image for $username")
                return
            }

            // ✅ 기존 방식도 성공 시 "처리 완료" 마킹
            val userIdFromMap = followNickById.entries.firstOrNull { it.value.equals(username, true) }?.key
            val followKey = if (userIdFromMap != null) "$userIdFromMap|${followDeepLinks[userIdFromMap]}" else "$username|"
            followProfileApplied.add(followKey)
            followInProgress.remove(followKey)
            AndroidLog.d("FOLLOW_PROFILE", "Marked as processed (legacy): $followKey")

            // 캐시 저장 + 같은 이벤트 알림만 갱신 (매핑 없으면 갱신 생략)
            saveProfileImageToCache(username, img)
            val uid = userIdFromMap ?: extractUserIdFromUrl(hiddenWebView?.url) ?: username
            val mapped = followCurrentTagByUserId.containsKey(uid)
            if (!mapped) {
                AndroidLog.d("FOLLOW_PROFILE", "Skip profile update - no currentTag for userId=$uid")
                return
            }
            lifecycleScope.launch {
                val bitmap = loadAvatarBitmap(img, 128)
                if (bitmap != null) {
                    updateFollowNotificationWithProfile(uid, img)
                    AndroidLog.d("FOLLOW_PROFILE", "Updated follow notification with profile for $username")
                }
            }
        } catch (e: Exception) {
            AndroidLog.e("FOLLOW_PROFILE", "Error parsing profile result", e)
        }
    }
    
    // 기존 WebView 방식은 백그라운드 fetch로 대체됨
    
    // postSimpleNotificationWithIcon은 postSimpleNotification으로 통합됨

    // 간단한 알림 발송 (largeIcon 및 고정 ID 지원)
    @SuppressLint("MissingPermission")
    private fun postSimpleNotification(
        key: String, 
        title: String, 
        text: String, 
        whenMillis: Long, 
        openUrl: String? = null,
        smallIconRes: Int? = null,
        largeIcon: Bitmap? = null,     // <= 추가
        fixedId: Int? = null,          // <= 추가 (같은 알림 갱신용)
        topicId: String? = null,       // <= topicId는 중복 방지용으로만 사용 (seen 체크)
        actorHexId: String? = null,
        actorImageUrl: String? = null,
        projectThumbnailUrl: String? = null,
        rawPayload: String? = null,
        template: String? = null
    ): Boolean {
        val safeTitle = sanitizeNotificationText(title, 120).ifBlank { "알림" }
        val safeText = sanitizeNotificationText(text, 200).ifBlank { "새 알림이 도착했습니다." }
        val safeOpenUrl = sanitizeEntryUrlOrNull(openUrl)
        val isInteractive = !isNonInteractiveNotification(key, safeOpenUrl)
        // ✅ key 기반으로 ID 생성 (템플릿 타입별로 그룹화)
        val id = fixedId ?: stableIdFrom(key)
        val pi = if (isInteractive) {
            val intent = android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!safeOpenUrl.isNullOrBlank()) {
                intent.putExtra("openUrl", safeOpenUrl)
            }
            android.app.PendingIntent.getActivity(
                this,
                id,
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE else android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        // 알림 타입에 따라 적절한 채널 선택
        val channelId = when {
            key.startsWith("COMMENT") || key.startsWith("REPLY") -> commentChannelId
            key.startsWith("LIKE") || key.startsWith("PIN") -> likeChannelId
            key.startsWith("FOLLOW") -> followChannelId
            key.startsWith("MENTION") -> mentionChannelId
            key.startsWith("REMAKE") -> remakeChannelId
            key.startsWith("SHARE") -> shareChannelId
            else -> systemChannelId
        }

        val smallIcon = smallIconRes ?: R.mipmap.ic_launcher
        if (!canDeliverNotification(channelId, "NOTIF", key)) {
            releaseRememberedNotificationKey(key)
            return false
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildLockScreenPlaceholder(channelId, smallIcon))
            .setSmallIcon(smallIcon)
            .setContentTitle(safeTitle)
            .setContentText(safeText)
            .setWhen(whenMillis)
            .setShowWhen(true)
            .setAutoCancel(isInteractive)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)  // ✅ 업데이트 시 소리/진동 방지
        pi?.let { builder.setContentIntent(it) }

        // ✅ largeIcon 먼저 설정 (레이아웃 고정)
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        // ✅ BigTextStyle은 largeIcon 설정 후에 추가
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(safeText))

        val nm = NotificationManagerCompat.from(this)
        val notification = builder.build()
        if (fixedId != null) {
            // ✅ 기존 알림 갱신은 대기 큐 없이 즉시 업데이트 (소리/진동 없이 조용히 반영)
            if (!canDeliverNotification(channelId, "NOTIF_UPDATE", key)) return true
            try {
                nm.notify(key, id, notification)
                markSeenAfterNotify(key)
                markTopicSeen(topicId)
                persistNotificationToFeed(
                    key = key,
                    topicId = topicId,
                    title = safeTitle,
                    body = safeText,
                    deepLink = safeOpenUrl,
                    createdAt = whenMillis,
                    actorImageUrl = actorImageUrl,
                    actorHexId = actorHexId,
                    projectThumbnailUrl = projectThumbnailUrl,
                    rawPayload = rawPayload,
                    template = template
                )
                AndroidLog.d("NOTIF", "update notification succeeded tag=$key id=$id channel=$channelId")
            } catch (e: Exception) {
                AndroidLog.e("NOTIF", "Failed to update notification: tag=$key id=$id", e)
            }
        } else {
            scheduleNotificationDelivery {
                if (!canDeliverNotification(channelId, "NOTIF", key)) {
                    releaseRememberedNotificationKey(key)
                    return@scheduleNotificationDelivery
                }
                try {
                    // ✅ tag와 id를 함께 사용하여 같은 알림을 업데이트
                    nm.notify(key, id, notification)
                    // ✅ 실제로 띄운 다음에만 seen
                    markSeenAfterNotify(key)
                    markTopicSeen(topicId)
                    persistNotificationToFeed(
                        key = key,
                        topicId = topicId,
                        title = safeTitle,
                        body = safeText,
                        deepLink = safeOpenUrl,
                        createdAt = whenMillis,
                        actorImageUrl = actorImageUrl,
                        actorHexId = actorHexId,
                        projectThumbnailUrl = projectThumbnailUrl,
                        rawPayload = rawPayload,
                        template = template
                    )
                    AndroidLog.d("NOTIF", "notify() succeeded tag=$key id=$id channel=$channelId")
                } catch (e: Exception) {
                    releaseRememberedNotificationKey(key)
                    AndroidLog.e("NOTIF", "Failed to post notification: tag=$key id=$id", e)
                }
            }
        }
        return true
    }

    private fun scheduleNotificationDelivery(task: () -> Unit) {
        synchronized(notificationDeliveryQueue) {
            notificationDeliveryQueue.add(task)
            if (!notificationDeliveryActive) {
                notificationDeliveryActive = true
                dispatchNextNotification()
            }
        }
    }

    private fun dispatchNextNotification() {
        val nextTask: (() -> Unit)? = synchronized(notificationDeliveryQueue) {
            if (notificationDeliveryQueue.isEmpty()) {
                notificationDeliveryActive = false
                null
            } else {
                notificationDeliveryQueue.removeFirst()
            }
        }
        if (nextTask == null) return

        val now = SystemClock.elapsedRealtime()
        val spacingDelay = max(0L, nextAllowedNotificationAt - now)
        val burstDelay = computeBurstDelay(now)
        val delay = max(spacingDelay, burstDelay)
        mainHandler.postDelayed({
            try {
                nextTask()
            } finally {
                val executedAt = SystemClock.elapsedRealtime()
                recordNotificationTimestamp(executedAt)
                nextAllowedNotificationAt = executedAt + NOTIF_MIN_INTERVAL_MS
                dispatchNextNotification()
            }
        }, delay)
    }

    private fun computeBurstDelay(now: Long): Long {
        synchronized(recentNotificationTimestamps) {
            while (recentNotificationTimestamps.isNotEmpty() &&
                now - recentNotificationTimestamps.first() >= NOTIF_BURST_WINDOW_MS) {
                recentNotificationTimestamps.removeFirst()
            }
            if (recentNotificationTimestamps.size < NOTIF_BURST_LIMIT) {
                return 0L
            }
            val oldest = recentNotificationTimestamps.firstOrNull() ?: return 0L
            return max(0L, (oldest + NOTIF_BURST_WINDOW_MS) - now)
        }
    }

    private fun recordNotificationTimestamp(timestamp: Long) {
        synchronized(recentNotificationTimestamps) {
            recentNotificationTimestamps.addLast(timestamp)
            while (recentNotificationTimestamps.isNotEmpty() &&
                timestamp - recentNotificationTimestamps.first() >= NOTIF_BURST_WINDOW_MS) {
                recentNotificationTimestamps.removeFirst()
            }
            while (recentNotificationTimestamps.size > NOTIF_BURST_LIMIT) {
                recentNotificationTimestamps.removeFirst()
            }
        }
    }

    private fun sanitizeNotificationText(raw: String?, maxLen: Int = 120): String {
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

    private fun paramsToValueList(paramsRaw: Any?): List<String> {
        return try {
            when (paramsRaw) {
                null -> emptyList()
                is JSONArray -> {
                    buildList {
                        for (i in 0 until paramsRaw.length()) {
                            val value = paramsRaw.opt(i)
                            val normalized = when (value) {
                                null, JSONObject.NULL -> ""
                                is String -> value
                                else -> value.toString()
                            }
                            val safe = sanitizeNotificationText(normalized, 180)
                            if (safe.isNotBlank()) add(safe)
                        }
                    }
                }
                is JSONObject -> {
                    val preferred = listOf("userName", "user_name", "username", "title", "name", "text")
                    val ordered = mutableListOf<String>()
                    preferred.forEach { key ->
                        val v = sanitizeNotificationText(paramsRaw.optString(key), 180)
                        if (v.isNotBlank()) ordered.add(v)
                    }
                    val keyIterator = paramsRaw.keys()
                    while (keyIterator.hasNext()) {
                        val key = keyIterator.next()
                        val value = paramsRaw.opt(key)
                        val normalized = when (value) {
                            null, JSONObject.NULL -> ""
                            is String -> value
                            else -> value.toString()
                        }
                        val safe = sanitizeNotificationText(normalized, 180)
                        if (safe.isNotBlank() && !ordered.contains(safe)) ordered.add(safe)
                    }
                    ordered
                }
                is String -> {
                    val s = paramsRaw.trim()
                    if (s.isBlank()) {
                        emptyList()
                    } else if ((s.startsWith("[") && s.endsWith("]")) || (s.startsWith("{") && s.endsWith("}"))) {
                        val parsed: Any = if (s.startsWith("[")) JSONArray(s) else JSONObject(s)
                        paramsToValueList(parsed)
                    } else {
                        s.split(",").map { sanitizeNotificationText(it, 180) }.filter { it.isNotBlank() }
                    }
                }
                else -> {
                    val one = sanitizeNotificationText(paramsRaw.toString(), 180)
                    if (one.isBlank()) emptyList() else listOf(one)
                }
            }
        } catch (e: Exception) {
            AndroidLog.w("PARAMS_PARSE", "paramsToValueList failed: ${e.message}")
            emptyList()
        }
    }

    private fun extractParamFromNotification(o: JSONObject, index: Int): String {
        val values = paramsToValueList(o.opt("params"))
        return values.getOrNull(index).orEmpty()
    }

    private fun extractContentTitleFromParams(o: JSONObject): String {
        val fromParams = sanitizeNotificationText(extractParamFromNotification(o, 1), 120)
        if (fromParams.isNotBlank()) return fromParams
        return sanitizeNotificationText(
            o.optString("contentTitle").ifBlank { o.optString("title") },
            120
        )
    }

    private fun extractNotifTargetId(o: JSONObject, link: JSONObject?, topicId: String): String {
        fun pickIdFromText(raw: String?): String {
            val value = normalizeJsonToken(raw)
            if (value.isBlank()) return ""
            if (HEX_OBJECT_ID.matches(value)) return value
            val match = Regex("([0-9a-fA-F]{24})").find(value)
            return match?.groupValues?.getOrNull(1).orEmpty()
        }
        val fromLink = pickIdFromText(link?.optString("target"))
        if (fromLink.isNotBlank()) return fromLink
        val fromHash = pickIdFromText(link?.optString("hash"))
        if (fromHash.isNotBlank()) return fromHash
        val fromGroup = pickIdFromText(link?.optString("groupId"))
        if (fromGroup.isNotBlank()) return fromGroup
        val fromTopicInfo = pickIdFromText(o.optJSONObject("topicinfo")?.optString("targetId"))
        if (fromTopicInfo.isNotBlank()) return fromTopicInfo
        val fromTopLevel = pickIdFromText(o.optString("target"))
        if (fromTopLevel.isNotBlank()) return fromTopLevel
        return topicId.trim()
    }

    private fun extractUserHexIdFromNotification(o: JSONObject): String? {
        val directKeys = listOf("userId", "actorId", "authorId", "userHexId", "hexId", "targetUserId")
        for (k in directKeys) {
            val v = o.optString(k).trim()
            if (HEX_OBJECT_ID.matches(v)) return v
        }
        val nestedObjs = listOf("user", "actor", "author", "topicinfo")
        for (n in nestedObjs) {
            val obj = o.optJSONObject(n) ?: continue
            for (k in listOf("id", "_id", "userId", "hexId")) {
                val v = obj.optString(k).trim()
                if (HEX_OBJECT_ID.matches(v)) return v
            }
        }
        val rawParams = o.opt("params")
        if (rawParams is JSONArray) {
            for (idx in 0 until rawParams.length()) {
                val item = rawParams.opt(idx)
                if (item is JSONObject) {
                    for (k in listOf("id", "_id", "userId", "actorId")) {
                        val v = item.optString(k).trim()
                        if (HEX_OBJECT_ID.matches(v)) return v
                    }
                } else if (item is String) {
                    val v = item.trim()
                    if (HEX_OBJECT_ID.matches(v)) return v
                }
            }
        } else if (rawParams is JSONObject) {
            for (k in listOf("id", "_id", "userId", "actorId")) {
                val v = rawParams.optString(k).trim()
                if (HEX_OBJECT_ID.matches(v)) return v
            }
        }
        val link = o.optJSONObject("link")
        if (link != null) {
            val cat = link.optString("category").trim().lowercase(Locale.ROOT)
            if (cat == "user" || cat == "profile" || cat == "following") {
                val t = link.optString("target").trim()
                if (HEX_OBJECT_ID.matches(t)) return t
            }
        }
        return null
    }

    private fun resolveCommentSearchMode(template: String): String = when {
        template.equals("template_34", true) -> "reply"
        template.equals("template_space_1", true) -> "space_both"
        else -> "comment"
    }

    // 알림 타입
    private enum class NotifType { COMMENT_PROJECT, COMMENT_REPLY, COMMENT_SPACE, LIKE_PROJECT, LIKE_COMMENT, PIN_PROJECT, SHARE_PROJECT, REMAKE_PROJECT, FOLLOW, UNKNOWN }

    // ✅ params에서 username 추출 (문자열 형태 JSON 대응)
    private fun extractUsernameFromParams(o: JSONObject): String {
        val user = sanitizeNotificationText(extractParamFromNotification(o, 0), 80)
        return if (user.isNotBlank()) user else "(작성자)"
    }

    // 4종 판정 함수
    private fun classify(o: JSONObject): NotifType {
        val tpl = o.optString("template", "").trim().lowercase(Locale.ROOT)
        val msg = o.optString("message","")
        return when (NotificationSemantics.resolve(tpl, msg, o.optString("category"))) {
            NotificationSemantics.Kind.COMMENT_PROJECT -> NotifType.COMMENT_PROJECT
            NotificationSemantics.Kind.COMMENT_REPLY -> NotifType.COMMENT_REPLY
            NotificationSemantics.Kind.COMMENT_SPACE -> NotifType.COMMENT_SPACE
            NotificationSemantics.Kind.LIKE_PROJECT -> NotifType.LIKE_PROJECT
            NotificationSemantics.Kind.LIKE_COMMENT -> NotifType.LIKE_COMMENT
            NotificationSemantics.Kind.PIN_PROJECT -> NotifType.PIN_PROJECT
            NotificationSemantics.Kind.SHARE_PROJECT -> NotifType.SHARE_PROJECT
            NotificationSemantics.Kind.REMAKE_PROJECT -> NotifType.REMAKE_PROJECT
            NotificationSemantics.Kind.FOLLOW -> NotifType.FOLLOW
            NotificationSemantics.Kind.WARNING,
            NotificationSemantics.Kind.SYSTEM -> NotifType.UNKNOWN
        }
    }

    private fun deepLinkFromLink(
        link: JSONObject?,
        fallbackCategory: String? = null,
        fallbackTarget: String? = null
    ): String {
        val base = "https://playentry.org"
        val explicitUrl = link?.optString("url").orEmpty()
        if (explicitUrl.isNotBlank()) {
            normalizeEntryUrl(explicitUrl)?.let { return it }
        }
        val category = normalizeLinkCategory(link?.optString("category"))
            ?: normalizeLinkCategory(fallbackCategory)
        val target = normalizeJsonToken(link?.optString("target"))
            .ifBlank { normalizeJsonToken(fallbackTarget) }
        return buildEntryUrlByCategory(category, target) ?: "$base/alarm"
    }

    // ✅ 알림 클릭 시 해당 페이지로 이동하는 함수
    fun openEntryPage(
        category: String?,    // ex) "project", "qna", "tips", "user", "notice", "space"
        target: String?,      // ex) "689432d00059d0826040b65e"
        userId: String?,      // ex) "61409f26389b55ababc8d4f4"
        message: String? = "" // 알림 문장 (ex: "JRSI님이 나의 월드를 응원합니다.")
    ) {
        val base = "https://playentry.org"
        val spaceBase = "https://space.playentry.org"
        var url = "$base/alarm"

        when {
            // 🧍‍♂️ 마이페이지(팔로잉)
            category.equals("user", true) ||
            category.equals("follow", true) ||
            category.equals("following", true) -> {
                if (!userId.isNullOrBlank()) {
                    url = "$base/profile/$userId/project?sort=created&term=all&isOpen=all"
                }
            }

            // 🎨 작품 관련 (좋아요, 댓글, 공유 등)
            category.equals("project", true) -> {
                if (!target.isNullOrBlank()) {
                    url = "$base/project/$target"
                    // 댓글 관련 알림이면 댓글 섹션으로 스크롤
                    if (message?.contains("댓글", true) == true) {
                        url += "#comments"
                    }
                }
                // "새로운 작품을 공유했습니다" 문장일 때도 작품 링크로
                if (message?.contains("새로운 작품을 공유", true) == true) {
                    url = "$base/project/$target"
                }
            }

            // ❓ 묻고답하기
            category.equals("qna", true) -> {
                if (!target.isNullOrBlank()) {
                    url = "$base/community/qna/$target"
                }
            }

            // 💡 노하우&팁
            category.equals("tips", true) -> {
                if (!target.isNullOrBlank()) {
                    url = "$base/community/tips/$target"
                }
            }

            // 📢 공지사항
            category.equals("notice", true) -> {
                if (!target.isNullOrBlank()) {
                    url = "$base/community/notice/$target"
                }
            }

            // 🌏 탐험하기(응원 = 좋아요, 댓글, 대댓글)
            category.equals("space", true) ||
            category.equals("space_explore", true) ||
            message?.contains("월드를 응원", true) == true ||
            message?.contains("탐험", true) == true -> {
                if (!target.isNullOrBlank()) {
                    url = "$spaceBase/world/$target"
                }
            }

            // 🏛 커뮤니티 기타
            category.equals("entrystory", true) ||
            category.equals("entrylife", true) ||
            category.equals("free", true) ||
            category.equals("discuss", true) -> {
                if (!target.isNullOrBlank()) {
                    url = "$base/community/$category/$target"
                }
            }
        }

        AndroidLog.d("DEEPLINK", "Opening: $url")
        mainWebView?.loadUrl(url)
        SecurityUtil.launchSafeEntryUrl(this, url)
        
        // 사용자에게 알림 클릭 처리 중임을 알림
        android.widget.Toast.makeText(this, "알림에서 해당 페이지로 이동합니다", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ✅ 댓글 알림 발송 (프로필 이미지 포함)
    @SuppressLint("MissingPermission")
    private fun postCommentNotification(
        title: String,
        text: String,
        openUrl: String,
        profileUrl: String?,
        avatarName: String,
        tag: String,
        nid: Int,
        whenMillis: Long,
        commentPreview: String? = null,
        topicIdHint: String? = null,
        stickerUrl: String? = null,
        imageUrl: String? = null,
        actorHexId: String? = null
    ): Boolean {
        val safeUrl = sanitizeEntryUrl(openUrl, "https://playentry.org/")
        val safeTitle = sanitizeNotificationText(title, 120).ifBlank { "알림" }
        val safeText = sanitizeNotificationText(text, 200).ifBlank { "새 알림이 도착했습니다." }
        // 피드에는 댓글 내용을 포함한 본문 저장
        val feedBody = if (!commentPreview.isNullOrBlank()) {
            "$safeText\n\"${sanitizeNotificationText(commentPreview, 150)}\""
        } else safeText
        if (!canDeliverNotification(commentChannelId, "COMMENT_NOTIF", tag)) {
            releaseRememberedNotificationKey(tag)
            return false
        }
        val state = CommentNotificationState(
            tag = tag,
            notifId = nid,
            title = safeTitle,
            text = safeText,
            openUrl = safeUrl,
            avatarName = avatarName,
            whenMillis = whenMillis
        )
        state.commentPreview = sanitizeNotificationText(commentPreview, 200).takeIf { it.isNotEmpty() }
        activeCommentNotifications[tag] = state
        val kind = if (findCommentKind(topicIdHint)?.equals("REPLY", true) == true) "REPLY" else "COMMENT"
        rememberCommentTag(topicIdHint, kind, tag)

        // ✅ 항상 기본 아바타로 즉시 발송 후, 프로필이 있으면 업데이트
        AndroidLog.d("COMMENT_NOTIF", "Posting immediate notification (default avatar)")
        val builder = buildCommentNotification(state, null)
        try {
            NotificationManagerCompat.from(this).notify(tag, nid, builder.build())
            if (!commentPreview.isNullOrBlank()) {
                markTopicSeen(topicIdHint)
                markCommentResolved(topicIdHint)
            }
            persistNotificationToFeed(
                key = tag,
                topicId = topicIdHint,
                title = safeTitle,
                body = feedBody,
                deepLink = safeUrl,
                createdAt = whenMillis,
                actorImageUrl = sanitizeImageUrl(profileUrl).takeIf { !it.isNullOrBlank() },
                actorHexId = actorHexId,
                stickerUrl = stickerUrl,
                imageUrl = imageUrl
            )
            AndroidLog.d("COMMENT_NOTIF", "notify() succeeded tag=$tag nid=$nid channel=$commentChannelId")
        } catch (e: Exception) {
            activeCommentNotifications.remove(tag)
            releaseRememberedNotificationKey(tag)
            AndroidLog.e("COMMENT_NOTIF", "notify() failed tag=$tag nid=$nid", e)
            return false
        }

        val sanitized = sanitizeImageUrl(profileUrl)
        if (sanitized.isNotBlank()) {
            AndroidLog.d("COMMENT_NOTIF", "Profile available, updating after load: $sanitized")
            updateCommentNotificationProfile(tag, sanitized)
        }
        return true
    }

    private fun buildCommentNotification(
        state: CommentNotificationState,
        customIcon: Bitmap?
    ): NotificationCompat.Builder {
        val icon = customIcon ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        val safeUrl = sanitizeEntryUrl(state.openUrl, "https://playentry.org/")
        val isInteractive = !isNonInteractiveNotification(state.tag, safeUrl)
        val pi = if (isInteractive) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("openUrl", safeUrl)
            }
            PendingIntent.getActivity(
                this,
                state.notifId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val preview = state.commentPreview?.let { sanitizeNotificationText(it, 200) }?.takeIf { it.isNotBlank() }
        val style = if (preview != null) {
            val safePreview = android.text.Html.escapeHtml(preview)
            val safeText = android.text.Html.escapeHtml(state.text)
            val htmlContent = "$safeText<br><br>💬 <b><font color=\"#1E88E5\">$safePreview</font></b>"
            val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.text.Html.fromHtml(htmlContent, android.text.Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                android.text.Html.fromHtml(htmlContent)
            }
            NotificationCompat.BigTextStyle()
                .bigText(spanned)
                .setSummaryText("댓글 내용")
        } else {
            NotificationCompat.BigTextStyle().bigText(state.text)
        }
        val builder = NotificationCompat.Builder(this, commentChannelId)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildLockScreenPlaceholder(commentChannelId, android.R.drawable.stat_notify_chat))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setStyle(style)
            .setAutoCancel(isInteractive)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setLargeIcon(icon)
            .setWhen(state.whenMillis)
            .setShowWhen(true)
        pi?.let { builder.setContentIntent(it) }
        return builder
    }

    private fun updateCommentNotificationProfile(tag: String, rawProfileUrl: String?) {
        val sanitized = sanitizeImageUrl(rawProfileUrl)
        if (sanitized.isBlank()) return
        val state = activeCommentNotifications[tag] ?: return
        if (state.lastProfileUrl == sanitized) {
            AndroidLog.d("COMMENT_NOTIF", "Skip update - already applied: $sanitized")
            return
        }
        loadProfileForComment(state, sanitized)
    }

    @SuppressLint("MissingPermission")
    private fun refreshCommentNotification(tag: String) {
        val state = activeCommentNotifications[tag] ?: return
        val icon = commentLoadedBitmaps[tag] ?: state.lastProfileUrl?.let { avatarCache.get(it) }
        val builder = buildCommentNotification(state, icon)
        if (!canDeliverNotification(commentChannelId, "COMMENT_NOTIF", state.tag)) return
        try {
            NotificationManagerCompat.from(this).notify(state.tag, state.notifId, builder.build())
        } catch (e: Exception) {
            AndroidLog.e("COMMENT_NOTIF", "refresh notify failed tag=${state.tag}", e)
        }
    }

    private fun requestLikeCommentContent(topicId: String, username: String, createdIso: String, deepLink: String?, notificationKey: String) {
        if (!likeCommentFetchInProgress.add(topicId)) {
            AndroidLog.d("LIKE_COMMENT", "Fetch already in progress for topic=$topicId")
            return
        }
        val targetId = extractTargetIdFromUrl(deepLink)
        if (targetId.isNullOrBlank()) {
            AndroidLog.w("LIKE_COMMENT", "Cannot determine targetId for topic=$topicId url=$deepLink")
            likeCommentFetchInProgress.remove(topicId)
            likeCommentTopicToKey.remove(topicId)
            return
        }
        val module = loadAssetAsString("findComment.js")
        if (module.isBlank()) {
            AndroidLog.w("LIKE_COMMENT", "findComment.js missing - cannot fetch comment content")
            likeCommentFetchInProgress.remove(topicId)
            likeCommentTopicToKey.remove(topicId)
            return
        }
        AndroidLog.d("LIKE_COMMENT", "Fetching comment content topic=$topicId targetId=$targetId user=$username")
        val quotedTopic = JSONObject.quote(topicId)
        val quotedUser = JSONObject.quote(username)
        val quotedTime = JSONObject.quote(createdIso)
        val quotedTarget = JSONObject.quote(targetId)
        val script = """
            (function(){
                try{
                    if (typeof findComment !== 'function') {
                        AndroidApp.postMessage("LIKE_COMMENT_RESULT:"+JSON.stringify({topic:$quotedTopic,error:"no_function"}));
                        return;
                    }
                    findComment($quotedUser, $quotedTime, { targetId:$quotedTarget })
                        .then(function(r){
                            AndroidApp.postMessage("LIKE_COMMENT_RESULT:"+JSON.stringify({topic:$quotedTopic,payload:r||{error:"not_found"}}));
                        })
                        .catch(function(e){
                            AndroidApp.postMessage("LIKE_COMMENT_RESULT:"+JSON.stringify({topic:$quotedTopic,error:(e && e.message) || String(e)}));
                        });
                }catch(err){
                    AndroidApp.postMessage("LIKE_COMMENT_RESULT:"+JSON.stringify({topic:$quotedTopic,error:(err && err.message) || String(err)}));
                }
            })();
        """.trimIndent()
        mainWebView?.post {
            try {
                mainWebView?.evaluateJavascript(module, null)
                mainWebView?.evaluateJavascript(script, null)
            } catch (e: Exception) {
                AndroidLog.e("LIKE_COMMENT", "Failed to evaluate JS for topic=$topicId: ${e.message}")
                likeCommentFetchInProgress.remove(topicId)
                likeCommentTopicToKey[topicId]?.takeIf { it == notificationKey }?.let { likeCommentTopicToKey.remove(topicId) }
            }
        } ?: run {
            AndroidLog.w("LIKE_COMMENT", "Main WebView unavailable - cannot fetch comment content")
            likeCommentFetchInProgress.remove(topicId)
            likeCommentTopicToKey[topicId]?.takeIf { it == notificationKey }?.let { likeCommentTopicToKey.remove(topicId) }
        }
    }

    private fun extractTargetIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val normalized = normalizeEntryUrl(url) ?: return null
        val group = Regex("/group/community/([0-9a-f]{24})/([0-9a-f]{24})(?:[/?#]|$)", RegexOption.IGNORE_CASE)
            .find(normalized)
        if (group != null) return group.groupValues[1]

        val project = Regex("/project/([0-9a-f]{24})(?:[/?#]|$)", RegexOption.IGNORE_CASE).find(normalized)
        if (project != null) return project.groupValues[1]

        val community = Regex(
            "/community/(qna|tips|entrystory|notice|entrylife|free|discuss)/([0-9a-f]{24})(?:[/?#]|$)",
            RegexOption.IGNORE_CASE
        ).find(normalized)
        if (community != null) return community.groupValues[2]

        val fallback = Regex("([0-9a-f]{24})(?![0-9a-f])", RegexOption.IGNORE_CASE).find(normalized)
        return fallback?.groupValues?.getOrNull(1)
    }

    @SuppressLint("MissingPermission")
    private fun loadProfileForComment(stateSnapshot: CommentNotificationState, profileUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cached = avatarCache.get(profileUrl)
            val bmp = cached ?: run {
                AndroidLog.d("COMMENT_NOTIF", "Downloading bitmap from: $profileUrl")
                val downloaded = runCatching { downloadBitmap(profileUrl, 128) }.onFailure {
                    AndroidLog.e("COMMENT_NOTIF", "Failed to download bitmap: ${it.message}")
                }.getOrNull()
                if (downloaded != null) {
                    avatarCache.put(profileUrl, downloaded)
                } else {
                    AndroidLog.w("COMMENT_NOTIF", "Bitmap download returned null")
                }
                downloaded
            }

            if (bmp == null) return@launch

            notificationRepository.updateActorImage(stateSnapshot.tag, stateSnapshot.avatarName, profileUrl)

            withContext(Dispatchers.Main) {
                val currentState = activeCommentNotifications[stateSnapshot.tag] ?: return@withContext
                currentState.lastProfileUrl = profileUrl
                commentLoadedBitmaps[currentState.tag] = bmp
                val builder = buildCommentNotification(currentState, bmp)
                if (!canDeliverNotification(commentChannelId, "COMMENT_NOTIF", currentState.tag)) return@withContext
                try {
                    NotificationManagerCompat.from(this@MainActivity).notify(currentState.tag, currentState.notifId, builder.build())
                    AndroidLog.d("COMMENT_NOTIF", "Notification updated with profile image")
                } catch (e: Exception) {
                    AndroidLog.e("COMMENT_NOTIF", "profile update notify failed tag=${currentState.tag}", e)
                }
            }
        }
    }

    // 비트맵 다운로드(최적화): SVG 변환 + PNG/WebP 병렬 시도 + 타임아웃
    private fun downloadBitmap(url: String, targetSize: Int = 256): Bitmap? {
        return try {
            val safeUrl = sanitizeImageUrl(url)
            if (safeUrl.isBlank()) return null
            // 빠른 캐시: 사용자 키 기반 메모리 캐시(비활성 기본)
            if (ENABLE_USER_AVATAR_KEY_CACHE) {
                userAvatarBitmapByKey.get(safeUrl)?.let { return it }
            }

            val isSvgUrl = safeUrl.contains(".svg", ignoreCase = true)
            val initial = requestBitmapOnce(safeUrl, targetSize)
            if (initial != null) {
                if (ENABLE_USER_AVATAR_KEY_CACHE) userAvatarBitmapByKey.put(safeUrl, initial)
                return getCircularBitmap(initial, targetSize)
            }

            if (!isSvgUrl) return null

            // SVG면: 1) 원본 SVG 렌더 2) 래스터 후보 병렬 시도 → 먼저 성공한 쪽 사용
            val svgIndex = safeUrl.lowercase().indexOf(".svg")
            val rasterCandidates = if (svgIndex >= 0) {
                val prefix = safeUrl.substring(0, svgIndex)
                val suffix = safeUrl.substring(svgIndex + 4)
                listOf("png", "webp", "jpg", "jpeg")
                    .map { "$prefix.$it$suffix" }
                    .mapNotNull { sanitizeImageUrl(it).takeIf { s -> s.isNotBlank() } }
            } else emptyList()

            // 1초 타임아웃 내 먼저 성공하는 비트맵 선택
            var chosen: Bitmap? = null
            val start = System.currentTimeMillis()

            // 원본 SVG 요청 (동기)
            chosen = requestBitmapOnce(safeUrl, targetSize) ?: chosen
            if (chosen == null) {
                // 병렬 대안 시도
                val jobs = rasterCandidates.map { alt ->
                    kotlin.concurrent.thread {
                        requestBitmapOnce(alt, targetSize)?.let { bmp ->
                            synchronized(this) { if (chosen == null) chosen = bmp }
                        }
                    }
                }
                // 1초 대기 또는 모두 종료
                val remain = 1000L - (System.currentTimeMillis() - start)
                if (remain > 0) Thread.sleep(remain)
                jobs.forEach { it.join(0) }
            }

            chosen?.also { bmp ->
                if (ENABLE_USER_AVATAR_KEY_CACHE) userAvatarBitmapByKey.put(safeUrl, bmp)
                return getCircularBitmap(bmp, targetSize)
            }
            null
        } catch (e: Exception) { null }
    }

    private fun requestBitmapOnce(targetUrl: String, targetSize: Int): Bitmap? {
        return try {
            val safeUrl = sanitizeImageUrl(targetUrl)
            if (safeUrl.isBlank()) return null
            val builder = Request.Builder()
                .url(safeUrl)
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .addHeader("Referer", "https://playentry.org/")
                .addHeader("User-Agent", "Mozilla/5.0 (Android) Entify/1.0")
            if (USE_HTTP_DISK_CACHE) {
                builder.header("Cache-Control", "public, max-age=3600")
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val bytes = SafeImageDecoder.readBounded(body) ?: return null
                val mime = resp.header("content-type") ?: ""
                if (mime.isNotBlank() && !isAllowedImageMime(mime)) return null
                val isSvg = safeUrl.contains(".svg", true) || mime.contains("svg", true) || looksLikeSvg(bytes)
                val decoded = if (isSvg) {
                    if (!SafeImageDecoder.isSafeSvg(bytes)) return null
                    renderSvgToBitmap(bytes, targetSize, targetSize)
                        ?: renderSvgWithWebView(String(bytes, Charsets.UTF_8), targetSize)
                } else {
                    if (!looksLikeRasterImage(bytes)) return null
                    SafeImageDecoder.decodeRaster(bytes, targetSize)
                }
                return decoded?.let {
                    if (it.width == targetSize && it.height == targetSize) it
                    else Bitmap.createScaledBitmap(it, targetSize, targetSize, true)
                }
            }
        } catch (_: Exception) { null }
    }

    private fun renderSvgToBitmap(svgBytes: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val svgClass = Class.forName("com.caverock.androidsvg.SVG")
            val getFromString = svgClass.getMethod("getFromString", String::class.javaObjectType)
            val svgObj = getFromString.invoke(null, String(svgBytes))

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bmp)

            svgClass.getMethod("setDocumentWidth", Float::class.javaPrimitiveType).invoke(svgObj, width.toFloat())
            svgClass.getMethod("setDocumentHeight", Float::class.javaPrimitiveType).invoke(svgObj, height.toFloat())
            svgClass.getMethod("renderToCanvas", AndroidCanvas::class.java).invoke(svgObj, canvas)

            bmp
        } catch (_: Throwable) {
            null
        }
    }

    // ✅ 댓글 페이로드 데이터 클래스
    data class CommentPayload(
        val userId: String,
        val nick: String,
        val content: String,
        val profile: String,
        val profileFilename: String = "",
        val profileImageType: String = "",
        val link: String,
        val time: String,
        val matchType: String = "comment",  // template_1: "comment", template_34: "reply"
        val parentUserId: String = "",
        val parentNickname: String = "",
        val sticker: String = "",
        val image: String = ""
    )
    
    private val lastByTopic = mutableMapOf<String, CommentPayload>()

    private fun handleCommentResultJson(topicHint: String?, obj: JSONObject) {
        val err = obj.optString("error", "").trim()
        if (err.isNotBlank()) {
            AndroidLog.w("COMMENT_RESULT", "error payload received: $err")
            val phase = commentPhase ?: "UNKNOWN"
            val topic = commentPhaseTopic ?: lastTopicId ?: ""
            when {
                phase.startsWith("T1_WEBVIEW_GRAPHQL") ->
                    AndroidLog.w("COMMENT_FLOW", "[2] hidden WebView GraphQL error topic=$topic err=$err")
                phase.startsWith("DOM_SCAN") ->
                    AndroidLog.w("COMMENT_FLOW", "[3] DOM scan error topic=$topic err=$err")
                else ->
                    AndroidLog.w("COMMENT_FLOW", "[?] comment error phase=$phase topic=$topic err=$err")
            }
            completeTemplate1Graphql(false)
            return
        }
        val topic = topicHint?.takeIf { it.isNotBlank() }
            ?: commentPhaseTopic
            ?: lastTopicId
            ?: "t:${obj.optString("link", "")}"
        val profileRaw = obj.optString("profile", "")
        val profileFilename = obj.optString("profileFilename", "")
            .ifBlank { obj.optString("profileImageFilename", "") }
        val profileImageType = obj.optString("profileImageType", "")
            .ifBlank { obj.optString("imageType", "") }
        val resolvedProfile = when {
            profileFilename.isNotBlank() -> buildUploadUrlFromSlug(profileFilename, profileImageType)
            profileRaw.isNotBlank() -> sanitizeUrl(profileRaw)
            else -> ""
        }
        val cur = CommentPayload(
            userId = obj.optString("userId", ""),
            nick = obj.optString("nickname", ""),
            content = obj.optString("content", ""),
            profile = resolvedProfile,
            profileFilename = profileFilename,
            profileImageType = profileImageType,
            link = obj.optString("link", lastDeepLink ?: "https://playentry.org/"),
            time = obj.optString("time", lastCreated ?: ""),
            matchType = obj.optString("matchType", "comment"),  // ✅ JavaScript에서 전달
            parentUserId = obj.optString("parentUserId", ""),
            parentNickname = obj.optString("parentNickname", ""),
            sticker = obj.optString("sticker", ""),
            image = obj.optString("image", "")
        )
        // ✅ payload의 matchType 기준으로 kind를 확정 (topic mismatch 대비)
        val kind = if (cur.matchType.equals("reply", true)) "REPLY" else "COMMENT"
        rememberCommentKind(topic, kind)
        applyCommentPayload(topic, cur)
        completeTemplate1Graphql(true, topic)
    }

    private fun handleLikeCommentResult(payload: String) {
        try {
            val obj = JSONObject(payload)
            val topic = obj.optString("topic")
            if (topic.isBlank()) return
            likeCommentFetchInProgress.remove(topic)
            val key = likeCommentTopicToKey.remove(topic) ?: run {
                AndroidLog.w("LIKE_COMMENT", "No notification key for topic=$topic")
                return
            }
            val errorMsg = obj.optString("error")
            val result = obj.optJSONObject("payload")
            if (result == null) {
                if (errorMsg.isNotBlank()) {
                    AndroidLog.w("LIKE_COMMENT", "Content fetch error topic=$topic err=$errorMsg")
                }
                return
            }
            val content = result.optString("content").orEmpty().trim()
            if (content.isBlank()) {
                AndroidLog.w("LIKE_COMMENT", "Empty comment content for topic=$topic")
                return
            }
            AndroidLog.d("LIKE_COMMENT", "Content fetched for topic=$topic preview=${content.take(40)}")
            activeCommentNotifications[key]?.let { state ->
                state.commentPreview = content
                refreshCommentNotification(key)
            }
        } catch (e: Exception) {
            AndroidLog.e("LIKE_COMMENT", "Failed to parse LIKE_COMMENT_RESULT", e)
        }
    }

    private fun isBetter(new: CommentPayload, old: CommentPayload?): Boolean {
        if (old == null) return true
        
        val addedContent = old.content.isBlank() && new.content.isNotBlank()
        val addedProfile = old.profile.isBlank() && new.profile.isNotBlank()
        val addedParent = old.parentUserId.isBlank() && new.parentUserId.isNotBlank()
        val changedParent = new.parentUserId.isNotBlank() && new.parentUserId != old.parentUserId
        
        val changedContent = new.content.isNotBlank() && new.content != old.content
        val changedProfile = new.profile.isNotBlank() && new.profile != old.profile

        val addedSticker = old.sticker.isBlank() && new.sticker.isNotBlank()
        val addedImage = old.image.isBlank() && new.image.isNotBlank()
        val changedSticker = new.sticker.isNotBlank() && new.sticker != old.sticker
        val changedImage = new.image.isNotBlank() && new.image != old.image
        
        // 새 정보가 추가되었거나 값이 실제로 바뀐 경우만 개선으로 간주
        return addedContent || addedProfile || addedParent || changedParent || changedContent || changedProfile ||
            addedSticker || addedImage || changedSticker || changedImage
    }

    private fun applyCommentPayload(topic: String, payload: CommentPayload) {
        val prev = lastByTopic[topic]
        if (!isBetter(payload, prev)) {
            AndroidLog.d("COMMENT_RESULT", "skip weak update for topic=$topic")
            return
        }
        lastByTopic[topic] = payload
        dispatchCommentNotification(topic, payload)
    }

    private fun dispatchCommentNotification(topic: String, payload: CommentPayload) {
        val nickname = payload.nick
        val content = payload.content
        val fallbackLink = buildCommentDeepLinkFromLast() ?: lastDeepLink
        val link = payload.link.ifBlank { fallbackLink ?: "https://playentry.org/" }
        val time = payload.time

        // template_34(reply)은 parentUserId 기준으로만 검증하고, template_1(comment)은 UID 검증을 생략한다.
        val targetUid = preferenceManager.commentUserId?.trim().orEmpty()
        val topicKind = findCommentKind(topic)?.trim().orEmpty()
        val isReplyMatchType = payload.matchType.trim().equals("reply", true)
        val isReplyByTopic = topicKind.equals("REPLY", true)
        val shouldCheckReplyUid = isReplyMatchType || isReplyByTopic

        if (shouldCheckReplyUid && looksLikeUid(targetUid)) {
            val parentUid = payload.parentUserId.trim()
            if (parentUid.isNotBlank() && !parentUid.equals(targetUid, ignoreCase = true)) {
                AndroidLog.d(
                    "COMMENT_RESULT",
                    "skip: parentUserId mismatch target=$targetUid got=$parentUid (matchType=${payload.matchType}, topicKind=$topicKind)"
                )
                return
            }
            if (parentUid.isBlank()) {
                AndroidLog.w(
                    "COMMENT_RESULT",
                    "reply payload without parentUserId (matchType=${payload.matchType}, topicKind=$topicKind) - skip UID verification"
                )
            }
        }

        val targetNick = expectedCommentUser ?: lastUserNickname ?: ""
        val effectiveNick = if (nickname.isBlank() && targetNick.isNotBlank()) targetNick else nickname

        val profAbs = sanitizeImageUrl(payload.profile)
        val isDefault = profAbs.contains("/profileImage.svg", true) ||
            profAbs.contains("EmptyImage", true) ||
            profAbs.isBlank()

        val finalProf = when {
            !isDefault -> profAbs
            !preferredProfileUrl.isNullOrBlank() && nickname.equals(lastUserNickname ?: "", true) -> preferredProfileUrl!!
            else -> profAbs
        }

        AndroidLog.d("COMMENT_RESULT", "Processing comment: nickname=$nickname, content=$content, profile=$profAbs")
        AndroidLog.d("COMMENT_RESULT", "Conditions: pendingAllowNotify=$pendingAllowNotify, notifiedCurrent=$notifiedCurrent")

        val who = effectiveNick.ifBlank { expectedCommentUser ?: lastUserNickname ?: "새 댓글" }
        val topicKey = topic.takeIf { it.isNotBlank() } ?: lastTopicId?.takeIf { it.isNotBlank() }
        val commentKind = findCommentKind(topicKey) ?: "COMMENT"
        val isReplyByPayload = payload.matchType.equals("reply", true)
        val isReply = isReplyByPayload || commentKind.equals("REPLY", true)
        val contentTitle = findContentTitle(topicKey)
        val title = contentTitle?.takeIf { it.isNotBlank() } ?: "알림"
        val text = if (isReply) {
            "${who}님이 작품에 답글을 작성했어요"
        } else {
            val projectName = "작품"
            "${who}님이 ${projectName}에 댓글을 작성했어요"
        }
        val createdForKey = time.ifBlank { "t:$topicKey" }
        val calculatedKey = notifKey(
            if (isReply) "REPLY" else "COMMENT",
            who,
            if (isReply) "답글을 남겼습니다" else "댓글을 남겼습니다",
            topicKey,
            createdForKey
        )
        val keyKind = if (isReply) "REPLY" else "COMMENT"
        val oppositeKind = if (keyKind == "REPLY") "COMMENT" else "REPLY"
        val rememberedTag = findCommentTag(topicKey, keyKind)
            ?: findAnyCommentTag(topicKey)
            ?: findCommentTag(topicKey, oppositeKind)
        val key = rememberedTag ?: calculatedKey
        if (rememberedTag != null) {
            // kind가 뒤늦게 바뀌어도 같은 알림을 갱신하도록 매핑을 동기화한다.
            rememberCommentTag(topicKey, keyKind, key)
        }
        val nid = stableIdFrom(key)
        val whenMillis = parseWhen(time)
        val commentPreview = content.trim().takeIf { it.isNotEmpty() }
        val hasCustomProfile = finalProf.isNotBlank()

        AndroidLog.d(
            "COMMENT_NOTIF",
            "pre-post topic=$topicKey key=$key remembered=${rememberedTag != null} first=${rememberedTag == null} " +
                "nick=$who hasProfile=$hasCustomProfile previewLen=${commentPreview?.length ?: 0} time='$time'"
        )

        if (rememberedTag == null) {
            val firstPost = rememberNotificationKey(key)
            AndroidLog.d("COMMENT_NOTIF", "postedKeys.add -> $firstPost for key=$key")
            if (firstPost) {
                AndroidLog.d("COMMENT_NOTIF", "posting notification tag=$key nid=$nid link=$link")
                val posted = postCommentNotification(
                    title = title,
                    text = text,
                    openUrl = link,
                    profileUrl = finalProf.takeIf { hasCustomProfile },
                    avatarName = who,
                    tag = key,
                    nid = nid,
                    whenMillis = whenMillis,
                    commentPreview = commentPreview,
                    topicIdHint = topicKey,
                    stickerUrl = payload.sticker.takeIf { it.isNotBlank() },
                    imageUrl = payload.image.takeIf { it.isNotBlank() },
                    actorHexId = payload.userId.takeIf { it.isNotBlank() }
                )
                if (posted) {
                    rememberCommentTag(topicKey, keyKind, key)
                    notifiedCurrent = true
                } else {
                    releaseRememberedNotificationKey(key)
                    activeCommentNotifications.remove(key)
                    AndroidLog.w("COMMENT_NOTIF", "post skipped or failed, key released for retry: $key")
                }
            } else {
                AndroidLog.d("COMMENT_NOTIF", "skip notify: duplicate key=$key")
            }
        }

        val feedTopicId = topicKey?.takeIf { it.isNotBlank() } ?: "key:$key"

        var state = activeCommentNotifications[key]
        if (state == null && rememberedTag != null) {
            state = CommentNotificationState(
                tag = key,
                notifId = nid,
                title = title,
                text = text,
                openUrl = link,
                avatarName = who,
                whenMillis = whenMillis
            )
            activeCommentNotifications[key] = state
        }
        if (commentPreview != null && state != null) {
            val previewChanged = state.commentPreview != commentPreview
            if (previewChanged) {
                state.commentPreview = commentPreview
                refreshCommentNotification(key)
                markTopicSeen(topicKey)
                markCommentResolved(topicKey)
                val updatedBody = "${state.text}\n\"${sanitizeNotificationText(commentPreview, 150)}\""
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { notificationRepository.updateBodyByTopicId(feedTopicId, updatedBody) }
                }
            }
        }

        if (hasCustomProfile) {
            updateCommentNotificationProfile(key, finalProf)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { notificationRepository.updateActorImageByTopicId(feedTopicId, finalProf) }
            }
        }

        if (payload.sticker.isNotBlank() || payload.image.isNotBlank() || payload.userId.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    notificationRepository.updateMediaByTopicId(
                        topicId = feedTopicId,
                        stickerUrl = payload.sticker.takeIf { it.isNotBlank() },
                        imageUrl = payload.image.takeIf { it.isNotBlank() },
                        actorHexId = payload.userId.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
    }

    private fun postBasicCommentFallback(item: CommentNotifItem, reason: String) {
        if (notifiedCurrent) return
        val who = item.actorName.ifBlank { expectedCommentUser ?: lastUserNickname ?: "새 댓글" }
        val payload = CommentPayload(
            userId = preferenceManager.commentUserId?.trim().orEmpty(),
            nick = who,
            content = "",
            profile = "",
            link = item.deepLink.ifBlank { lastDeepLink ?: "https://playentry.org/" },
            time = item.created.ifBlank { lastCreated ?: "" }
        )
        AndroidLog.d("COMMENT_FALLBACK", "Posting basic comment notification (reason=$reason, topic=${item.topicId})")
        AndroidLog.w("COMMENT_FLOW", "[4] basic notification posted topic=${item.topicId} reason=$reason")
        applyCommentPayload(item.topicId.ifBlank { lastTopicId ?: "" }, payload)
        val dedupKey = buildDedupKey(item.topicId, item.template, item.created)
        if (dedupKey.isNotBlank()) {
            synchronized(processedTopicIds) { processedTopicIds.remove(dedupKey) }
            AndroidLog.d("COMMENT_FALLBACK", "Removed dedup key for retry: $dedupKey")
        }
    }

    // ✅ 프로필 추출 JS (폴링 버전)
    private val PROFILE_JS_POLLING = """
        (function(){
          const until = Date.now()+8000; // 최대 8초 대기
          function tryPick(){
            console.log('[PROFILE_POLLING] Attempting to extract profile');
            
            // 네가 준 구조: .css-lowial.e1hjgst10
            function pickNodeFromContainer(container){
              if (!container) return null;
              const nodes = [container];
              try {
                const styled = container.querySelectorAll('[style*="background-image"], [style*="/uploads/"]');
                styled.forEach(el => nodes.push(el));
              } catch(_) {}
              for (const node of nodes){
                const styleAttr = (node.getAttribute && node.getAttribute('style')) || '';
                if (styleAttr.includes('background-image') || styleAttr.includes('/uploads/')) return node;
                try {
                  const bg = getComputedStyle(node).backgroundImage || '';
                  if (bg && bg !== 'none') return node;
                } catch(_) {}
              }
              return nodes[0] || null;
            }
            function pickProfileNode(){
              const followNode = (function(){
                const matchers = ['.css-x4ht6q.e1e59sjh11','[class*="css-x4ht6q"][class*="e1e59sjh11"]'];
                for (const sel of matchers){
                  const container = document.querySelector(sel);
                  const node = pickNodeFromContainer(container);
                  if (node) return node;
                }
                return null;
              })();
              if (followNode) return followNode;
              const shareNode = (function(){
                const matchers = ['.css-1do82rn.e1rinhrp0','[class*="css-1do82rn"][class*="e1rinhrp0"]'];
                for (const sel of matchers){
                  const container = document.querySelector(sel);
                  const node = pickNodeFromContainer(container);
                  if (node) return node;
                }
                return null;
              })();
              if (shareNode) return shareNode;
              return document.querySelector('.css-lowial.e1hjgst10');
            }
            let el = pickProfileNode();
            
            if (el){
              let style = el.getAttribute('style') || getComputedStyle(el).backgroundImage || '';
              console.log('[PROFILE_POLLING] Original style:', style);
              
              // &quot; HTML 엔티티 디코딩
              style = style.replace(/&quot;/g, '"');
              console.log('[PROFILE_POLLING] After decode:', style);
              
              // 모든 url() 추출
              let urls = [];
              let regex = /url\\(["']?([^)"']+)["']?\\)/g;
              let match;
              while ((match = regex.exec(style)) !== null) {
                urls.push(match[1]);
              }
              
              console.log('[PROFILE_POLLING] Found URLs:', JSON.stringify(urls));
              
              // /uploads/ 포함하고 EmptyImage/Default 아닌 첫 번째 선택
              for (let u of urls) {
                if (u.includes('/uploads/') && !u.includes('EmptyImage') && !u.includes('Default')) {
                  if (!/^https?:/i.test(u)) u = 'https://playentry.org' + (u.startsWith('/') ? u : '/' + u);
                  console.log('[PROFILE_POLLING] Success:', u);
                  AndroidApp.postMessage('PROFILE_RESULT:'+u);
                  return;
                }
              }
            }
            
            if (Date.now() < until) return setTimeout(tryPick, 150);
            console.log('[PROFILE_POLLING] Failed to find profile');
            AndroidApp.postMessage('PROFILE_RESULT:'); // 실패
          }
          tryPick();
        })();
    """.trimIndent()

    // ✅ 프로필 페이지에서 아바타 추출
    private var pendingProfileCallback: ((String?) -> Unit)? = null
    private val pendingProfileUpdate = mutableMapOf<Pair<String, Int>, (String?) -> Unit>()
    
    private fun fetchAvatarFromProfile(profilePath: String, onReady: (String?) -> Unit) {
        ensureHiddenWebView(this)
        val profileCandidate = when {
            profilePath.startsWith("/") -> "https://playentry.org$profilePath"
            profilePath.startsWith("https://", ignoreCase = true) -> profilePath
            else -> "https://playentry.org/$profilePath"
        }
        val url = sanitizeImageUrl(profileCandidate)
        if (url.isBlank()) {
            onReady(null)
            return
        }
        
        pendingProfileCallback = onReady
        
        hiddenWebView?.webViewClient = object : SecureEntryWebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                AndroidLog.e("HIDDEN_WEBVIEW", "Render process gone in profile fetch: didCrash=${renderProcessCrashState(detail)}")
                pendingProfileCallback?.invoke(null)
                pendingProfileCallback = null
                return true
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                super.onPageFinished(view, u)
                if (u?.contains("/profile/") == true) {
                    val js = """
                        (function(){
                          console.log('[PROFILE_EXTRACT] Starting profile extraction');
                          
                          let el = document.querySelector('.css-lowial.e1hjgst10');
                          if (!el) el = document.querySelector('[style*="background-image"]');
                          
                          if (!el) {
                            console.log('[PROFILE_EXTRACT] No element found');
                            AndroidApp.postMessage('PROFILE_RESULT:');
                            return;
                          }
                          
                          let style = el.getAttribute('style') || getComputedStyle(el).backgroundImage || '';
                          console.log('[PROFILE_EXTRACT] Original style:', style);
                          
                          // &quot; HTML 엔티티 디코딩
                          style = style.replace(/&quot;/g, '"');
                          console.log('[PROFILE_EXTRACT] After decode:', style);
                          
                          // 모든 url() 추출
                          let urls = [];
                          let regex = /url\\(["']?([^)"']+)["']?\\)/g;
                          let match;
                          while ((match = regex.exec(style)) !== null) {
                            urls.push(match[1]);
                          }
                          
                          console.log('[PROFILE_EXTRACT] Found URLs:', JSON.stringify(urls));
                          
                          // /uploads/ 포함하고 EmptyImage/Default 아닌 첫 번째 선택
                          let v = '';
                          for (let url of urls) {
                            if (url.includes('/uploads/') && !url.includes('EmptyImage') && !url.includes('Default')) {
                              v = url;
                              break;
                            }
                          }
                          
                          console.log('[PROFILE_EXTRACT] Selected URL:', v);
                          
                          if (v && !/^https?:/i.test(v)) {
                            v = 'https://playentry.org' + (v.startsWith('/') ? v : '/' + v);
                          }
                          
                          console.log('[PROFILE_EXTRACT] Final URL:', v);
                          AndroidApp.postMessage('PROFILE_RESULT:'+ (v||''));
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }
        }
        
        hiddenWebView?.post { hiddenWebView?.loadUrl(url) }
    }

    // SAVE_TOKENS에 nickname이 실려오지 않았고 아직 저장된 닉네임도 없을 때,
    // __NEXT_DATA__ / JWT 디코딩 / GraphQL 조회 순으로 닉네임을 찾아 USER_NICKNAME으로 보고한다.
    // 토큰 분기(CSRF 갱신 등)와 무관하게 언제든 호출 가능하도록 별도 함수로 분리했다.
    private var lastNicknameFallbackFetchAt = 0L
    private fun requestNicknameFallbackFetch() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNicknameFallbackFetchAt < 5000L) return
        lastNicknameFallbackFetchAt = now
        mainWebView?.post {
            mainWebView?.evaluateJavascript("""
                (async function(){
                    try {
                        // __NEXT_DATA__에서 user _id 추출
                        var userId = '';
                        var el = document.getElementById('__NEXT_DATA__');
                        if (el && el.innerText) {
                            var d = JSON.parse(el.innerText);
                            var u = (d.props && d.props.initialState && d.props.initialState.common && d.props.initialState.common.user)
                                 || (d.props && d.props.pageProps && d.props.pageProps.initialState && d.props.pageProps.initialState.common && d.props.pageProps.initialState.common.user);
                            if (u) {
                                userId = u._id || u.id || '';
                                // __NEXT_DATA__에 nickname이 있으면 바로 사용
                                var nick = u.nickname || '';
                                if (nick) {
                                    AndroidApp.postMessage('USER_NICKNAME:' + nick);
                                    return;
                                }
                            }
                        }
                        if (!userId && window.__NEXT_DATA__) {
                            var u2 = (window.__NEXT_DATA__.props && window.__NEXT_DATA__.props.initialState && window.__NEXT_DATA__.props.initialState.common && window.__NEXT_DATA__.props.initialState.common.user)
                                  || (window.__NEXT_DATA__.props && window.__NEXT_DATA__.props.pageProps && window.__NEXT_DATA__.props.pageProps.initialState && window.__NEXT_DATA__.props.pageProps.initialState.common && window.__NEXT_DATA__.props.pageProps.initialState.common.user);
                            if (u2) {
                                userId = u2._id || u2.id || '';
                                if (userId) {
                                    AndroidApp.postMessage('USER_ID:' + userId);
                                }
                                var nick2 = u2.nickname || '';
                                if (nick2) {
                                    AndroidApp.postMessage('USER_NICKNAME:' + nick2);
                                    return;
                                }
                            }
                        }
                        // xToken JWT 디코딩으로 userId 추출 시도
                        if (!userId) {
                            var xt0 = sessionStorage.getItem('entry_xToken') || localStorage.getItem('entry_xToken');
                            if (xt0) {
                                try {
                                    var parts = xt0.split('.');
                                    if (parts.length >= 2) {
                                        var payload = JSON.parse(atob(parts[1]));
                                        userId = payload._id || payload.id || payload.sub || '';
                                    }
                                } catch(e2) { console.log('[NICKNAME_FETCH] JWT decode error:', e2 && e2.message); }
                            }
                        }
                        if (!userId) return;
                        // GraphQL로 닉네임 조회
                        var xt = sessionStorage.getItem('entry_xToken') || localStorage.getItem('entry_xToken');
                        var cs = sessionStorage.getItem('entry_csrfToken') || localStorage.getItem('entry_csrfToken');
                        if (!xt || !cs) return;
                        var res = await fetch('https://playentry.org/graphql', {
                            method: 'POST',
                            headers: {
                                'content-type': 'application/json',
                                'x-token': xt,
                                'csrf-token': cs
                            },
                            credentials: 'include',
                            body: JSON.stringify({
                                query: 'query (${'$'}id: ID!) { user(id: ${'$'}id) { nickname } }',
                                variables: { id: userId }
                            })
                        });
                        var json = await res.json();
                        var nick = json && json.data && json.data.user && json.data.user.nickname;
                        if (nick) {
                            AndroidApp.postMessage('USER_NICKNAME:' + nick);
                        }
                    } catch(e) {
                        console.log('[NICKNAME_FETCH] error:', e && e.message);
                    }
                })();
            """.trimIndent(), null)
        }
    }

    // ✅ 공통 JS 메시지 핸들러 (메인/숨김 WebView 공통)
    private fun onJsMessage(message: String, origin: String) {
        AndroidLog.d("JS_BRIDGE", "[$origin] event received")
        
        if (message == "TOKEN_REFRESH_OK") {
            pendingTokenRefresh?.complete(true)
            pendingTokenRefresh = null
            return
        }
        if (message.startsWith("TOKEN_REFRESH_FAIL")) {
            pendingTokenRefresh?.complete(false)
            pendingTokenRefresh = null
            AndroidLog.w("TOKEN_REFRESH", "Failed: $message")
            return
        }
        if (message == "NEED_RELOGIN") {
            if (!origin.equals("ALARM", ignoreCase = true)) {
                AndroidLog.d("AUTH", "Ignore NEED_RELOGIN from $origin")
                return
            }
            handleLogoutDetected("need_relogin")
            return
        } else if (message.startsWith("LOGIN_ERR:")) {
            val reason = message.removePrefix("LOGIN_ERR:").trim()
            val shouldInvalidate = reason.equals("NO_TOKEN", true) || reason.equals("EXTRACT_FAIL", true)
            if (shouldInvalidate) {
                handleLogoutDetected("login_err_$reason")
            } else {
                AndroidLog.d("TOKEN_JS", "Ignore transient LOGIN_ERR ($reason)")
            }
            return
        }

        if (message == "TOKEN_BOOTSTRAP_OK") {
            AndroidLog.d("TOKEN_BOOTSTRAP", "Hidden bootstrap completed successfully")
            completeTokenBootstrap(true)
            return
        }
        if (message.startsWith("TOKEN_BOOTSTRAP_FAIL")) {
            AndroidLog.w("TOKEN_BOOTSTRAP", "Hidden bootstrap failed: $message")
            completeTokenBootstrap(false)
            return
        }
        
        // 토큰 저장 메시지 처리
        if (message.startsWith("SAVE_TOKENS:")) {
            try {
                val tokenJson = message.removePrefix("SAVE_TOKENS:").trim()

                val tokenObj = JSONObject(tokenJson)
                val xToken = tokenObj.optString("xToken", "")
                val csrfToken = tokenObj.optString("csrfToken", "")
                val normalizedOrigin = origin.trim().uppercase(Locale.ROOT)
                val fromAlarm = normalizedOrigin == "ALARM"
                val fromMain = normalizedOrigin == "MAIN"
                val fromToken = normalizedOrigin == "TOKEN"

                // ✅ 닉네임은 아래 토큰 분기(CSRF 갱신/동일 토큰 early-return)와 무관하게
                // 이 메시지에 실려온 값이 있으면 항상 즉시 반영한다.
                // xToken은 세션 내내 고정, csrfToken만 페이지마다 갱신되기 때문에
                // 로그인 이후 대부분의 SAVE_TOKENS 호출은 "CSRF 갱신"/"동일 토큰" 분기에서
                // return 되어버려, 닉네임을 그 아래쪽(신규 토큰 분기)에서만 처리하면
                // 실제로는 거의 실행되지 않는다.
                val incomingNickname = tokenObj.optString("nickname", "")
                val incomingUserId = tokenObj.optString("userId", "").trim()
                val incomingProfile = tokenObj.optString("profileImage", "").trim()
                if (looksLikeUid(incomingUserId) && preferenceManager.commentUserId != incomingUserId) {
                    preferenceManager.commentUserId = incomingUserId
                    AndroidLog.d("TOKEN_SAVE", "  - commentUserId 저장됨(early): $incomingUserId")
                }
                if (incomingNickname.isNotBlank() && preferenceManager.username.isNullOrBlank()) {
                    preferenceManager.username = incomingNickname
                    AndroidLog.d("TOKEN_SAVE", "  - nickname 저장됨(early): $incomingNickname")
                } else if (incomingNickname.isBlank() && preferenceManager.username.isNullOrBlank()
                    && xToken.isNotBlank() && csrfToken.isNotBlank()) {
                    requestNicknameFallbackFetch()
                }
                if (incomingProfile.isNotBlank() && preferenceManager.profileImageUrl.isNullOrBlank()) {
                    preferenceManager.profileImageUrl = incomingProfile
                    AndroidLog.d("TOKEN_SAVE", "  - profileImageUrl 저장됨(early): $incomingProfile")
                }

                // Alarm WebView token snapshots are noisy and can oscillate rapidly.
                // Keep MAIN/TOKEN tokens as canonical while already logged in.
                if (fromAlarm && hasStoredTokens()) {
                    val sameAsStored = preferenceManager.xToken == xToken && preferenceManager.csrfToken == csrfToken
                    if (!sameAsStored) {
                        AndroidLog.d("TOKEN_SAVE", "Ignore ALARM token update while stored tokens already exist")
                    } else {
                        AndroidLog.d("TOKEN_SAVE", "Ignore duplicate ALARM token snapshot")
                    }
                    markTokensReady()
                    return
                }

                // MAIN/TOKEN: xToken 동일 + csrfToken만 다른 경우 → CSRF 갱신
                // 서버가 페이지마다 새 CSRF를 발급하므로 정상적인 갱신이다.
                if ((fromMain || fromToken) && hasStoredTokens()) {
                    val sameX = preferenceManager.xToken == xToken
                    val sameCsrf = preferenceManager.csrfToken == csrfToken
                    if (sameX && !sameCsrf && csrfToken.isNotBlank()) {
                        AndroidLog.d(
                            "TOKEN_SAVE",
                            "CSRF session value refreshed"
                        )
                        preferenceManager.csrfToken = csrfToken
                        markTokensReady()
                        // 알람 WebView에도 새 CSRF 주입
                        alarmWebView?.post {
                            val bootstrapJs = buildAlarmTokenBootstrapJs(xToken, csrfToken)
                            alarmWebView?.evaluateJavascript(bootstrapJs, null)
                            AndroidLog.d("TOKEN_SAVE", "🔄 CSRF 갱신 → alarm WebView 토큰 재주입")
                        }
                        return
                    }
                }

                // 중복 저장 방지 - 이미 같은 토큰이 저장되어 있으면 스킵
                val sameAsStored = preferenceManager.xToken == xToken && preferenceManager.csrfToken == csrfToken
                if (sameAsStored) {
                    AndroidLog.d("TOKEN_SAVE", "⏭️ 동일한 토큰이 이미 저장되어 있음 - 상태만 갱신")
                    markTokensReady()
                    syncForegroundPollingService("tokens_same")
                    pendingTokenReady?.let {
                        if (!it.isCompleted) it.complete(true)
                    }
                    return
                }

                AndroidLog.d("TOKEN_SAVE", "🔑 새로운 토큰 저장!")
                AndroidLog.d("TOKEN_SAVE", "  - required session values received")

                if (xToken.isNotBlank() && csrfToken.isNotBlank()) {
                    // PreferenceManager에 토큰 저장
                    preferenceManager.isLoggedIn = true
                    preferenceManager.logoutPending = false
                    preferenceManager.xToken = xToken
                    preferenceManager.csrfToken = csrfToken
                    
                    try {
                        val cm = CookieManager.getInstance()
                        val cookieStr = cm.getCookie("https://playentry.org")
                        if (!cookieStr.isNullOrBlank()) {
                            preferenceManager.cookies = cookieStr
                            AndroidLog.d("TOKEN_SAVE", "  - cookies 저장됨 (len=${cookieStr.length})")
                            AndroidLog.d("TOKEN_SAVE", "  - session cookies captured")
                        } else {
                            AndroidLog.w("TOKEN_SAVE", "  - 저장할 쿠키 없음")
                        }
                    } catch (e: Exception) {
                        AndroidLog.e("TOKEN_SAVE", "쿠키 저장 실패: ${e.message}")
                    }
                    bumpCookieGenerationLocked()
                    restoreCookiesIntoWebView(null, force = true, reason = "save_tokens")
                    markWebViewCookiesPrimed(
                        mainWebView,
                        followGraphqlWebView,
                        alarmWebView,
                        hiddenWebView,
                        tokenWebView
                    )
                    
                    // 닉네임은 위쪽(early 섹션)에서 이미 처리됨 - 여기서는 중복 처리하지 않는다.

                    AndroidLog.d("TOKEN_SAVE", "✅ 토큰 저장 완료!")
                    AndroidLog.d("TOKEN_SAVE", "  - isLoggedIn: ${preferenceManager.isLoggedIn}")
                    AndroidLog.d("TOKEN_SAVE", "  - encrypted session storage updated")
                    markTokensReady()
                    syncForegroundPollingService("tokens_saved")

                    // 알람 WebView에 새 토큰 주입 (CSRF 불일치 방지)
                    alarmWebView?.post {
                        val bootstrapJs = buildAlarmTokenBootstrapJs(xToken, csrfToken)
                        alarmWebView?.evaluateJavascript(bootstrapJs, null)
                        AndroidLog.d("TOKEN_SAVE", "🔄 alarm WebView에 새 토큰 주입 완료")
                    }
                    
                    if (!notificationLogicStarted) {
                        startNotificationLogicIfLoggedIn(force = true)
                        AndroidLog.d("TOKEN_SAVE", "🚀 토큰 저장 완료 - 스타트업 플로우 재실행")
                        scheduleStartupFlow("tokens_saved")
                    }
                } else {
                    AndroidLog.w("TOKEN_SAVE", "Required session values were empty")
                }
            } catch (e: Exception) {
                AndroidLog.e("TOKEN_SAVE", "토큰 저장 실패: ${e.message}")
                e.printStackTrace()
            }
            return
        }

        if (message.startsWith("USER_NICKNAME:")) {
            val nick = message.removePrefix("USER_NICKNAME:").trim()
            if (nick.isNotBlank() && preferenceManager.username.isNullOrBlank()) {
                preferenceManager.username = nick
                AndroidLog.d("TOKEN_SAVE", "닉네임 별도 추출 성공: $nick")
            }
            return
        }

        if (message.startsWith("USER_ID:")) {
            val uid = message.removePrefix("USER_ID:").trim()
            if (looksLikeUid(uid) && preferenceManager.commentUserId != uid) {
                preferenceManager.commentUserId = uid
                AndroidLog.d("TOKEN_SAVE", "사용자 UID 별도 추출 성공: $uid")
            }
            return
        }

        if (message == "TOKEN_VERIFIED") {
            AndroidLog.d("ALARM_POLLER", "TOKEN_VERIFIED received from $origin - ensuring alarm poller active")
            if (origin.equals("ALARM", ignoreCase = true) && showPollerDebugToast) {
                showTriggerToast("토큰 확인됨(TOKEN_VERIFIED)")
            }
            startAlarmBackgroundPoller()
            return
        }

        if (message.startsWith("GRAPHQL_TOKENS:")) {
            val payload = message.removePrefix("GRAPHQL_TOKENS:")
            runCatching {
                val jo = JSONObject(payload)
                val xToken = jo.optString("xToken")
                val csrfToken = jo.optString("csrfToken")
                if (xToken.isNotBlank() && csrfToken.isNotBlank()) {
                    preferenceManager.xToken = xToken
                    preferenceManager.csrfToken = csrfToken
                    preferenceManager.isLoggedIn = true
                    preferenceManager.logoutPending = false
                    markTokensReady()
                    syncForegroundPollingService("graphql_tokens")
                    pendingGraphqlTokenRequest?.complete(xToken to csrfToken)
                    pendingGraphqlTokenRequest = null
                    return
                }
            }
            pendingGraphqlTokenRequest?.complete(null)
            pendingGraphqlTokenRequest = null
            return
        }

        if (message.startsWith("GRAPHQL_TOKENS_ERROR:")) {
            pendingGraphqlTokenRequest?.complete(null)
            pendingGraphqlTokenRequest = null
            return
        }

        if (message.contains("\"type\":\"GRAPHQL_PARITY_RESULT\"")) {
            handleGraphqlParityResult(message)
            return
        }

        // ✅ FOLLOW_RESULT 처리 (followGraphqlWebView에서 오는 메시지)
        if (message.contains("\"type\":\"FOLLOW_RESULT\"")) {
            handleFollowProfileResult(message)
            return
        }

        // ✅ SHARE_RESULT 처리 (shareGraphqlWebView에서 오는 메시지)
        if (message.contains("\"type\":\"SHARE_RESULT\"")) {
            handleShareGraphqlResult(message)
            return
        }

        // ✅ LOGIN_OK: 토큰 추출 성공 시 메인 폴러 주입
        if (message == "LOGIN_OK") {
            if (!origin.equals("MAIN", ignoreCase = true)) {
                AndroidLog.d("LOGIN_OK", "Ignore LOGIN_OK from $origin")
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (preferenceManager.isLoggedIn && (now - lastLoginOkHandledAt) < 5000L) {
                AndroidLog.d("LOGIN_OK", "최근 LOGIN_OK 처리됨 - 중복 이벤트 무시")
                return
            }
            lastLoginOkHandledAt = now
            if (notificationLogicStarted && preferenceManager.isLoggedIn) {
                AndroidLog.d("LOGIN_OK", "이미 로그인 처리됨 - 이벤트 무시")
                syncForegroundPollingService("login_ok_already")
                return
            }
            preferenceManager.logoutPending = false
            consecutivePollAuthErrors = 0
            lastLoginTimeMs = SystemClock.elapsedRealtime()
            AndroidLog.d("LOGIN_OK", "Auth error counter reset, grace period started (${LOGIN_GRACE_PERIOD_MS}ms)")
            syncForegroundPollingService("login_ok")
            AndroidLog.d("LOGIN_OK", "🎉 LOGIN_OK 메시지 수신 - 토큰 추출 성공!")
            mainWebView?.post {
                val currentUrl = mainWebView?.url.orEmpty()
                AndroidLog.d("LOGIN_OK", "현재 URL: $currentUrl")

                if (isMainContentUrl(currentUrl)) {
                    mainWebView?.postDelayed({
                        try {
                            // 기존 폴러 정리
                            mainWebView?.evaluateJavascript("if (window.__ENTRY_POLLER__){ __ENTRY_POLLER__.stop(); delete window.__ENTRY_POLLER__; }", null)

                            // ✅ 기본은 alarm poller를 사용하고, 메인 폴러가 허용된 경우에만 주입
                            if (shouldUseMainNotifPoller()) {
                                AndroidLog.d("LOGIN_OK", "📡 메인 WebView에 푸시 알림 폴러 주입")
                                mainWebView?.evaluateJavascript(jsNotifPoller, null)
                            } else {
                                AndroidLog.d("LOGIN_OK", "Skip main poller injection - alarm poller preferred")
                            }
                            mainWebView?.evaluateJavascript(jsTokenPoller, null)
                        } catch (e: Exception) {
                            AndroidLog.e("LOGIN_OK", "Failed to inject poller: ${e.message}")
                        }
                    }, 250)  // 토큰 추출 후 짧게 대기
                } else {
                    AndroidLog.w("LOGIN_OK", "로그인 페이지에 있거나 엔트리 사이트가 아님 - 폴러 주입 건너뜀")
                }
            }
            scheduleStartupFlow("login_ok")
            return
        }

        if (message == "NEED_RELOGIN") {
            if (!origin.equals("ALARM", ignoreCase = true)) {
                AndroidLog.d("AUTH", "Ignore NEED_RELOGIN from $origin")
                return
            }
            AndroidLog.w("AUTH", "토큰이 만료되었습니다. 재로그인 필요")
            handleLogoutDetected("need_relogin")
            return
        }

        if (message.startsWith("POLL_ERROR:")) {
            if (!origin.equals("ALARM", ignoreCase = true)) {
                AndroidLog.d("POLL_ERROR", "Ignore non-alarm poll error from $origin: $message")
                return
            }
            val status = message.removePrefix("POLL_ERROR:").trim().ifBlank { "unknown" }
            AndroidLog.w("POLL_ERROR", "GraphQL 폴링 오류 발생: status=$status")
            if (status != "403" && status != "401") {
                // fetch/network errors are often transient; avoid aggressive force-reload loops.
                if (status.equals("fetch", ignoreCase = true)) {
                    scheduleAlarmPollerRestart("poll_error_fetch", minDelayMs = 2500L)
                }
                return
            }

            val debugLower = lastPollDebugPayload.trim().lowercase(Locale.ROOT)
            val looksLikeCsrfMismatch =
                status == "403" && (
                    debugLower.contains("form tampered") ||
                    debugLower.contains("csrf") ||
                    debugLower.contains("forbidden")
                )

            if (looksLikeCsrfMismatch) {
                AndroidLog.w(
                    "POLL_ERROR",
                    "Treat 403 CSRF mismatch as recoverable without logout escalation: $lastPollDebugPayload"
                )
                consecutivePollAuthErrors = 0
            }

            // ✅ 연속 인증 에러 카운터 (로그인 직후 grace period 동안은 무시)
            val sinceLogin = SystemClock.elapsedRealtime() - lastLoginTimeMs
            if (!looksLikeCsrfMismatch && sinceLogin < LOGIN_GRACE_PERIOD_MS) {
                AndroidLog.d("POLL_ERROR", "Auth error ignored - within login grace period (${sinceLogin}ms / ${LOGIN_GRACE_PERIOD_MS}ms)")
            } else if (!looksLikeCsrfMismatch) {
                consecutivePollAuthErrors++
                AndroidLog.w("POLL_ERROR", "Auth error count: $consecutivePollAuthErrors / $POLL_AUTH_ERROR_LOGOUT_THRESHOLD")
            }

            if (!looksLikeCsrfMismatch && consecutivePollAuthErrors >= POLL_AUTH_ERROR_LOGOUT_THRESHOLD) {
                AndroidLog.w("POLL_ERROR", "연속 ${POLL_AUTH_ERROR_LOGOUT_THRESHOLD}회 인증 에러 → 로그아웃 감지")
                consecutivePollAuthErrors = 0
                handleLogoutDetected("poll_error_consecutive_${status}")
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastAlarmAuthRecoveryAt < 2500L) {
                AndroidLog.d("POLL_ERROR", "Skip repeated auth recovery (status=$status)")
                return
            }
            lastAlarmAuthRecoveryAt = now
            when (status) {
                "403", "401" -> {
                    // auth 에러 시 토큰 삭제/로그아웃 대신 메인 WebView에서 재추출 시도
                    alarmPollerStarted = false
                    alarmPollerPending = false
                    AndroidLog.d("POLL_ERROR", "Auth($status) detected - requesting token refresh from main WebView")

                    // 메인 WebView에 토큰 재추출 요청
                    mainWebView?.post {
                        mainWebView?.evaluateJavascript("""
                            (() => {
                                try {
                                    // 토큰 확인 (여러 위치 체크)
                                    const xtoken = sessionStorage.getItem('entry_xToken')
                                        || localStorage.getItem('entry_xToken')
                                        || localStorage.getItem('playentry_token');
                                    const csrf = sessionStorage.getItem('entry_csrfToken')
                                        || localStorage.getItem('entry_csrfToken')
                                        || document.querySelector('meta[name="csrf-token"]')?.content;

                                    if (xtoken && csrf) {
                                        console.log('[403_RECOVERY] Tokens found, re-saving');
                                        AndroidApp.postMessage('SAVE_TOKENS:' + JSON.stringify({
                                            xToken: xtoken,
                                            csrfToken: csrf
                                        }));
                                    } else {
                                        console.log('[403_RECOVERY] No tokens in storage - already logged in, skip re-login');
                                    }
                                } catch (e) {
                                    console.error('[403_RECOVERY] Error:', e);
                                }
                            })();
                        """.trimIndent(), null)
                    }
                }
            }

            // 토큰 재추출 후 적절한 대기 후 alarm poller 재시작 (과도한 루프로 인한 ANR 방지)
            mainHandler.postDelayed({
                val hasTokens = !preferenceManager.xToken.isNullOrBlank() && !preferenceManager.csrfToken.isNullOrBlank()
                if (hasTokens) {
                    AndroidLog.d("POLL_ERROR", "Tokens available, restarting alarm poller")
                    scheduleAlarmPollerRestart("poll_error_auth:$status", minDelayMs = 5000L)
                } else {
                    AndroidLog.w("POLL_ERROR", "Tokens still missing after recovery attempt")
                }
            }, 3000L)

            mainWebView?.post {
                runTokenExtractorIfReady(reason = "poll_error_recovery")
            }
            return
        }
        if (message.startsWith("POLL_STATUS:")) {
            if (!origin.equals("ALARM", ignoreCase = true)) {
                AndroidLog.d("POLL_STATUS", "Ignore non-alarm poll status from $origin: $message")
                return
            }
            val status = message.removePrefix("POLL_STATUS:").trim()
            // ✅ 성공적인 폴링 시 연속 에러 카운터 리셋
            if (status.startsWith("no_new") || status.startsWith("new_") || status == "empty") {
                if (consecutivePollAuthErrors > 0) {
                    AndroidLog.d("POLL_STATUS", "Poll success, resetting auth error counter (was $consecutivePollAuthErrors)")
                    consecutivePollAuthErrors = 0
                }
            }
            if (showPollerDebugToast) {
                showTriggerToast("폴러 상태: $status")
            }
            return
        }
        if (message.startsWith("POLL_DEBUG:")) {
            if (!origin.equals("ALARM", ignoreCase = true)) {
                AndroidLog.d("POLL_DEBUG", "Ignore non-alarm poll debug from $origin: $message")
                return
            }
            val payload = message.removePrefix("POLL_DEBUG:").trim()
            lastPollDebugPayload = payload
            if (showPollerDebugToast) {
                showTriggerToast("폴러 응답: $payload")
            }
            return
        }
        
        // ❌ OLD LEGACY SYSTEM DISABLED - Use unified UNIFIED_PROFILE handler instead
        // This old handler was causing timestamp issues (using System.currentTimeMillis() instead of preserving original time)
        // The unified system in handleProfileMessage() handles all notification types correctly
        /*
        // 기존 WebAppInterface의 메시지 처리 로직을 여기로 이동
        // ✅ JSON 형식의 PROFILE_RESULT 처리 (팔로잉 알림용)
        if (message.contains("\"type\":\"PROFILE_RESULT\"")) {
            try {
                val jo = JSONObject(message)
                val type = jo.optString("type")
                if (type == "PROFILE_RESULT") {
                    val userId = jo.optString("userId")
                    val profileUrl = jo.optString("url")
                    val error = jo.optString("error")

                    AndroidLog.d("PROFILE_RESULT", "[$origin] userId=$userId, url=$profileUrl, error=$error")

                    if (!error.isNullOrBlank()) {
                        AndroidLog.w("PROFILE_RESULT", "Profile fetch error for $userId: $error")
                        if (userId.isNotBlank()) updateFollowNotificationWithProfile(userId, "")
                    } else if (profileUrl.isNullOrBlank()) {
                        AndroidLog.d("PROFILE_RESULT", "No profile URL found for $userId")
                        if (userId.isNotBlank()) updateFollowNotificationWithProfile(userId, "")
                    } else {
                        AndroidLog.d("PROFILE_RESULT", "Profile URL found for $userId: $profileUrl")
                        if (userId.isNotBlank()) updateFollowNotificationWithProfile(userId, profileUrl)
                    }

                    // 처리 완료 마킹
                    val followKey = "$userId|${followDeepLinks[userId]}"
                    followProfileApplied.add(followKey)
                    followInProgress.remove(followKey)
                    return
                }
            } catch (e: Exception) {
                AndroidLog.e("PROFILE_RESULT", "Error parsing JSON profile result", e)
            }
        }
        */

        // ✅ 단순 문자열 형식의 PROFILE_RESULT 처리 (기존 호환성)
        if (message.startsWith("PROFILE_RESULT:")) {
            val url = message.removePrefix("PROFILE_RESULT:").trim()

            // ✅ pendingProfileCallback 우선 처리
            pendingProfileCallback?.let { callback ->
                callback.invoke(url.takeIf { it.isNotBlank() })
                pendingProfileCallback = null
            }

            // 기존 pendingProfileUpdate도 처리
            pendingProfileUpdate.entries.firstOrNull()?.let { (k, cb) ->
                cb.invoke(url); pendingProfileUpdate.remove(k)
            }
            return
        }
        
        if (message.startsWith("LIKE_COMMENT_RESULT:")) {
            val payload = message.removePrefix("LIKE_COMMENT_RESULT:").trim()
            handleLikeCommentResult(payload)
            return
        }

        if (message.startsWith("COMMENT_RESULT:")) {
            val payload = message.removePrefix("COMMENT_RESULT:").trim()

            // 디버깅 마커는 무시
            if (payload.startsWith("__")) {
                AndroidLog.d("COMMENT_RESULT", "marker:$payload")
                return
            }

            if (!payload.startsWith("{")) {
                when {
                    payload.equals("not_found", ignoreCase = true) -> {
                        val phase = commentPhase ?: "UNKNOWN"
                        val topic = commentPhaseTopic ?: lastTopicId ?: ""
                        when {
                            phase.startsWith("T1_WEBVIEW_GRAPHQL") ->
                                AndroidLog.w("COMMENT_FLOW", "[2] hidden WebView GraphQL not_found topic=$topic")
                            phase.startsWith("DOM_SCAN") ->
                                AndroidLog.w("COMMENT_FLOW", "[3] DOM scan not_found topic=$topic")
                            else ->
                                AndroidLog.w("COMMENT_FLOW", "[?] comment not_found phase=$phase topic=$topic")
                        }
                        completeTemplate1Graphql(false)
                        finishHiddenWebViewTask("not_found", commentPhaseTopic)
                    }
                    payload.startsWith("error_", ignoreCase = true) -> {
                        val phase = commentPhase ?: "UNKNOWN"
                        val topic = commentPhaseTopic ?: lastTopicId ?: ""
                        when {
                            phase.startsWith("T1_WEBVIEW_GRAPHQL") ->
                                AndroidLog.e("COMMENT_FLOW", "[2] hidden WebView GraphQL error topic=$topic payload=$payload")
                            phase.startsWith("DOM_SCAN") ->
                                AndroidLog.e("COMMENT_FLOW", "[3] DOM scan error topic=$topic payload=$payload")
                            else ->
                                AndroidLog.e("COMMENT_FLOW", "[?] comment error phase=$phase topic=$topic payload=$payload")
                        }
                        completeTemplate1Graphql(false)
                        finishHiddenWebViewTask("error", commentPhaseTopic)
                    }
                    payload.startsWith("{") -> { /* handled below */ }
                    payload.startsWith("\"type\"") -> {
                        try {
                            val root = JSONObject(payload)
                            val type = root.optString("type")
                            if (type == "COMMENT_RESULT") {
                                val topic = root.optString("topic", lastTopicId ?: "")
                                val inner = root.optJSONObject("payload") ?: JSONObject()
                                handleCommentResultJson(topic, inner)
                                finishHiddenWebViewTask("typed", topic)
                                return
                            }
                        } catch (e: Exception) {
                            AndroidLog.e("COMMENT_RESULT", "parse-typed-error: ${e.message}")
                            finishHiddenWebViewTask("parse-typed-error", commentPhaseTopic)
                        }
                    }
                }
                return
            }

            try {
                val o = JSONObject(payload)
                val rawTopic = o.optString("topicId")
                    .ifBlank { o.optString("topic") }
                    .ifBlank { o.optString("id") }
                val phaseTopic = commentPhaseTopic
                val topic = rawTopic.ifBlank {
                    phaseTopic ?: lastTopicId ?: "t:${o.optString("link", "")}"
                }
                if (rawTopic.isBlank() && phaseTopic != null) {
                    val matchType = o.optString("matchType").ifBlank { "" }
                    val kind = findCommentKind(phaseTopic) ?: ""
                    val kindOk = when (matchType.lowercase(Locale.ROOT)) {
                        "reply" -> kind.equals("REPLY", true)
                        "comment" -> kind.equals("COMMENT", true)
                        else -> true
                    }
                    if (!kindOk) {
                        AndroidLog.w(
                            "COMMENT_RESULT",
                            "topic missing + kind mismatch: matchType=$matchType phaseKind=$kind topic=$phaseTopic (skip)"
                        )
                        finishHiddenWebViewTask("kind-mismatch", phaseTopic)
                        return
                    }
                }
                if (rawTopic.isNotBlank() && commentPhaseTopic != null &&
                    !rawTopic.equals(commentPhaseTopic, ignoreCase = true)
                ) {
                    AndroidLog.w(
                        "COMMENT_RESULT",
                        "topic mismatch: payload=$rawTopic phase=$commentPhaseTopic (accept by payload)"
                    )
                }
                handleCommentResultJson(topic, o)
                finishHiddenWebViewTask("json", topic)
            } catch (e: Exception) {
                AndroidLog.e("COMMENT_RESULT", "parse-error: ${e.message}")
                completeTemplate1Graphql(false)
                finishHiddenWebViewTask("parse-error", commentPhaseTopic)
            }
            return
        }

        // NEW_NOTIF 메시지 처리
        if (message.startsWith("NEW_NOTIF:")) {
            if (!isAppForeground) {
                AndroidLog.d("NEW_NOTIF", "Ignore WebView NEW_NOTIF while background (origin=$origin)")
                return
            }
            try {
                val arr = JSONArray(message.removePrefix("NEW_NOTIF:"))
                if (arr.length() == 0) return  // 빈 배열 무시
                var processedCount = 0
                var skippedSeenCount = 0
                val now = SystemClock.elapsedRealtime()
                val shouldLog = (now - lastNewNotifLogAt) >= 3000L || arr.length() >= 5
                if (shouldLog) {
                    AndroidLog.d("NEW_NOTIF", "📬 Received ${arr.length()} notifications from $origin")
                    lastNewNotifLogAt = now
                }

                for (i in (arr.length() - 1) downTo 0) {
                    val o = arr.getJSONObject(i)
                    val topicId = o.optString("id", o.optString("_id", ""))
                    val isReadEntry = o.optBoolean("isRead", false)
                    if (isReadEntry) {
                        if (topicId.isNotBlank()) {
                            markTopicSeen(topicId)
                            preferenceManager.markTopicSeen(topicId)
                            lifecycleScope.launch(Dispatchers.IO) {
                                runCatching { notificationRepository.markReadByTopicId(topicId) }
                            }
                        }
                        continue
                    }
                    if (!DRY_RUN_NOTIF_ONLY && topicId.isNotBlank() && (isTopicSeen(topicId) || preferenceManager.isTopicSeen(topicId))) {
                        skippedSeenCount++
                        seenSkippedTopicBuffer.add(topicId)
                        AndroidLog.d("NEW_NOTIF_SKIP", "already seen topic id: $topicId")
                        continue
                    }

                    val link = o.optJSONObject("link")
                    val template = o.optString("template", "")
                    val msgText = o.optString("message", "")
                    val cat = link?.optString("category").orEmpty()
                        .ifBlank { o.optJSONObject("topicinfo")?.optString("category").orEmpty() }
                        .ifBlank { o.optString("category", "") }
                    val user = extractUsernameFromParams(o)
                    val contentTitleHint = extractContentTitleFromParams(o)
                    val created = o.optString("created", "")
                    val thumbUrlHint = o.optString("thumbUrl")
                    val targetId = extractNotifTargetId(o, link, topicId)
                    val deep = deepLinkFromLink(link, cat, targetId)

                    val type = classify(o)
                    AndroidLog.d("NEW_NOTIF_ITEM",
                        "id=$topicId type=$type cat=$cat linkCat=${link?.optString("category")} " +
                        "tpl=$template msg='$msgText' params=${o.opt("params")}"
                    )

                    if (!DRY_RUN_NOTIF_ONLY && preferenceManager.isSemanticDuplicate(type.name, user, created)) {
                        AndroidLog.d("NEW_NOTIF_SKIP", "already posted semantic duplicate: type=$type user=$user created=$created")
                        if (topicId.isNotBlank()) {
                            markTopicProcessed(buildDedupKey(topicId, template, created))
                            markCommentResolved(topicId)
                        }
                        continue
                    }

                    when (type) {
                        NotifType.COMMENT_PROJECT -> {
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved comment topic: $topicId")
                                continue
                            }
                            val dedupKey = buildDedupKey(topicId, template, created)
                            if (topicId.isNotBlank() && !markTopicProcessed(dedupKey)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "duplicate topic id: $topicId")
                                continue
                            }
                            if (DRY_RUN_NOTIF_ONLY) {
                                if (dryRunSeen.add(topicId)) {
                                    dryRunReceived.add(topicId)
                                    AndroidLog.d("DRYRUN", "comment collected: topic=$topicId user=$user created=$created")
                                } else {
                                    AndroidLog.d("DRYRUN", "skip dup (dry): $topicId")
                                }
                            }

                            val linkJson = link?.toString() ?: ""
                            val templateRaw = template
                            val linkTarget = link?.optString("target")
                            val commentItem = CommentNotifItem(
                                linkJson = linkJson,
                                actorName = user,
                                topicId = topicId,
                                deepLink = deep,
                                created = created,
                                template = templateRaw,
                                linkTarget = linkTarget,
                                linkCategoryHint = cat,
                                titleHint = contentTitleHint
                            )
                            enqueueNotif(NotifTask.Comment(commentItem))
                            processedCount++
                            AndroidLog.d("COMMENT_ENQUEUE", "댓글 알림 큐에 추가: user=$user, topicId=$topicId")
                        }
                        NotifType.COMMENT_SPACE -> {
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved comment topic: $topicId")
                                continue
                            }
                            val dedupKey = buildDedupKey(topicId, template, created)
                            if (topicId.isNotBlank() && !markTopicProcessed(dedupKey)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "duplicate topic id: $topicId")
                                continue
                            }
                            if (DRY_RUN_NOTIF_ONLY) {
                                if (dryRunSeen.add(topicId)) {
                                    dryRunReceived.add(topicId)
                                    AndroidLog.d("DRYRUN", "space(comment/reply) collected: topic=$topicId user=$user created=$created")
                                } else {
                                    AndroidLog.d("DRYRUN", "skip dup (dry): $topicId")
                                }
                            }

                            val linkJson = link?.toString() ?: ""
                            val templateRaw = template
                            val linkTarget = if (targetId.isNotBlank()) targetId else link?.optString("target")
                            val commentItem = CommentNotifItem(
                                linkJson = linkJson,
                                actorName = user,
                                topicId = topicId,
                                deepLink = deep,
                                created = created,
                                template = templateRaw,
                                linkTarget = linkTarget,
                                linkCategoryHint = "space",
                                titleHint = contentTitleHint.ifBlank { "스페이스 알림" }
                            )
                            enqueueNotif(NotifTask.Comment(commentItem))
                            processedCount++
                            AndroidLog.d(
                                "COMMENT_ENQUEUE",
                                "스페이스 댓글/답글 알림 큐에 추가: user=$user, topicId=$topicId, target=$targetId"
                            )
                        }
                        NotifType.LIKE_PROJECT -> {
                            // 좋아요류는 재시도가 필요없는 단발성 알림이므로, 한 번 처리하면
                            // JS 폴러가 나중에 같은 id를 재전송해도 다시 알림을 띄우지 않도록 즉시 resolved 처리한다.
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved topic: $topicId")
                                continue
                            }
                            if (DRY_RUN_NOTIF_ONLY) {
                                if (dryRunSeen.add(topicId)) {
                                    dryRunReceived.add(topicId)
                                    AndroidLog.d("DRYRUN", "like(project) collected: topic=$topicId user=$user created=$created")
                                } else {
                                    AndroidLog.d("DRYRUN", "skip dup (dry): $topicId")
                                }
                            }
                            postLikeProject(
                                user = user,
                                topicId = topicId,
                                created = created,
                                openUrl = deep,
                                projectIdHint = targetId,
                                thumbUrlHint = thumbUrlHint,
                                template = template,
                                contentTitleHint = contentTitleHint
                            )
                            markCommentResolved(topicId)
                            processedCount++
                        }
                        NotifType.LIKE_COMMENT -> {
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved topic: $topicId")
                                continue
                            }
                            if (DRY_RUN_NOTIF_ONLY) {
                                if (dryRunSeen.add(topicId)) {
                                    dryRunReceived.add(topicId)
                                    AndroidLog.d("DRYRUN", "like(comment) collected: topic=$topicId user=$user created=$created")
                                } else {
                                    AndroidLog.d("DRYRUN", "skip dup (dry): $topicId")
                                }
                            }
                            postLikeComment(
                                user = user,
                                topicId = topicId,
                                created = created,
                                openUrl = deep,
                                contentTitleHint = contentTitleHint,
                                projectIdHint = targetId,
                                thumbUrlHint = thumbUrlHint
                            )
                            markCommentResolved(topicId)
                            processedCount++
                        }
                        NotifType.PIN_PROJECT -> {
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved topic: $topicId")
                                continue
                            }
                            if (DRY_RUN_NOTIF_ONLY) {
                                if (dryRunSeen.add(topicId)) {
                                    dryRunReceived.add(topicId)
                                    AndroidLog.d("DRYRUN", "pin(project) collected: topic=$topicId user=$user created=$created")
                                } else {
                                    AndroidLog.d("DRYRUN", "skip dup (dry): $topicId")
                                }
                            }
                            postPinProject(
                                user = user,
                                topicId = topicId,
                                created = created,
                                openUrl = deep,
                                projectIdHint = targetId,
                                thumbUrlHint = thumbUrlHint,
                                template = template,
                                contentTitleHint = contentTitleHint
                            )
                            markCommentResolved(topicId)
                            processedCount++
                        }
                        NotifType.COMMENT_REPLY -> {
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved comment topic: $topicId")
                                continue
                            }
                            val dedupKey = buildDedupKey(topicId, template, created)
                            if (topicId.isNotBlank() && !markTopicProcessed(dedupKey)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "duplicate topic id: $topicId")
                                continue
                            }
                            val linkJson = link?.toString() ?: ""
                            val templateRaw = template
                            val linkTarget = link?.optString("target")
                            val commentItem = CommentNotifItem(
                                linkJson = linkJson,
                                actorName = user,
                                topicId = topicId,
                                deepLink = deep,
                                created = created,
                                template = templateRaw,
                                linkTarget = linkTarget,
                                linkCategoryHint = cat,
                                titleHint = contentTitleHint
                            )
                            enqueueNotif(NotifTask.Comment(commentItem))
                            processedCount++
                            AndroidLog.d("COMMENT_ENQUEUE", "답글 알림 큐에 추가: user=$user, topicId=$topicId")

                            val who = if (user.isNullOrBlank()) "새 답글" else user
                            val keyTarget = topicId.takeIf { it.isNotBlank() } ?: targetId
                            val key = notifKey("REPLY", who, "답글을 남겼습니다", keyTarget, created)
                            // rememberNotificationKey()는 "알림 기억하기" 설정이 꺼져있으면 항상 true를
                            // 반환해 매번 다시 게시하므로, JS 폴러가 이 topicId를 재전송할 때마다
                            // (아직 댓글 본문이 도착하지 않아 isCommentResolved가 false인 동안)
                            // 스와이프로 지운 답글 placeholder 알림이 계속 되살아나는 원인이 된다.
                            // findCommentTag로 이미 이 topicId에 대해 게시한 적이 있는지 먼저 확인한다.
                            if (findCommentTag(topicId, "REPLY") == null && rememberNotificationKey(key)) {
                                val notifId = stableIdFrom(key)
                                val deepLink = deep
                                val whenMillis = parseWhen(created)
                                rememberCommentKind(topicId, "REPLY")
                                val userHexId = extractUserHexIdFromNotification(o)
                                val posted = postCommentNotification(
                                    title = contentTitleHint.ifBlank { "알림" },
                                    text = "${who}님이 작품에 답글을 작성했어요",
                                    openUrl = deepLink,
                                    profileUrl = "",
                                    avatarName = who,
                                    tag = key,
                                    nid = notifId,
                                    whenMillis = whenMillis,
                                    commentPreview = null,
                                    topicIdHint = topicId,
                                    actorHexId = userHexId
                                )
                                if (posted) {
                                    rememberCommentTag(topicId, "REPLY", key)
                                    if (!userHexId.isNullOrBlank()) {
                                        val notifInfo = ProfileNotifInfo(
                                            tag = key,
                                            notifId = notifId,
                                            username = who,
                                            userId = userHexId,
                                            deepLink = deepLink,
                                            notifType = "REPLY",
                                            notifTitle = contentTitleHint.ifBlank { "알림" },
                                            notifText = "${who}님이 작품에 답글을 작성했어요",
                                            whenMillis = whenMillis,
                                            template = template,
                                            topicId = topicId
                                        )
                                        enqueueProfileFetch(who, userHexId, notifInfo)
                                    }
                                } else {
                                    releaseRememberedNotificationKey(key)
                                }
                            }
                            continue
                        }
                        NotifType.REMAKE_PROJECT -> {
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved topic: $topicId")
                                continue
                            }
                            val dedupKey = buildDedupKey(topicId, template, created)
                            if (topicId.isNotBlank() && !markTopicProcessed(dedupKey)) {
                                continue
                            }

                            handleRemakeProjectNotification(
                                user = user,
                                topicId = topicId,
                                created = created,
                                link = link,
                                deepLink = deep,
                                template = template,
                                rawParams = o.opt("params"),
                                contentTitleHint = contentTitleHint
                            )
                            markCommentResolved(topicId)
                            processedCount++
                        }
                        NotifType.SHARE_PROJECT -> {
                            // ✅ topicId+template 중복 체크 (한 번만 처리)
                            // markTopicProcessed는 "알림 기억하기" 설정이 꺼져있으면 항상 통과시키므로,
                            // JS 폴러의 재전송(TTL)에도 중복 알림이 뜨지 않도록 resolved 체크를 별도로 둔다.
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved topic: $topicId")
                                continue
                            }
                            val dedupKey = buildDedupKey(topicId, template, created)
                            if (topicId.isNotBlank() && !markTopicProcessed(dedupKey)) {
                                // 조용히 스킵
                                continue
                            }

                            handleShareProjectNotification(
                                user = user,
                                topicId = topicId,
                                created = created,
                                link = link,
                                deepLink = deep,
                                template = template,
                                contentTitleHint = contentTitleHint,
                                thumbUrlHint = thumbUrlHint
                            )
                            markCommentResolved(topicId)
                            processedCount++
                        }
                        NotifType.FOLLOW -> {
                            // ✅ topicId+template 중복 체크 (한 번만 처리)
                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved topic: $topicId")
                                continue
                            }
                            val dedupKey = buildDedupKey(topicId, template, created)
                            if (topicId.isNotBlank() && !markTopicProcessed(dedupKey)) {
                                // 조용히 스킵
                                continue
                            }

                            if (DRY_RUN_NOTIF_ONLY) {
                                if (dryRunSeen.add(topicId)) {
                                    dryRunReceived.add(topicId)
                                    AndroidLog.d("DRYRUN", "follow collected: topic=$topicId user=$user created=$created")
                                } else {
                                    AndroidLog.d("DRYRUN", "skip dup (dry): $topicId")
                                }
                            }
                            val followTarget = link?.optString("target").orEmpty().ifBlank { null }
                            handleFollowNotification(user, created, deep, followTarget, template, topicId)
                            markCommentResolved(topicId)
                            processedCount++
                        }
                        NotifType.UNKNOWN -> {
                            // 정의되지 않은 템플릿도 기본 알림으로는 전달해 사용자 누락을 막는다.
                            AndroidLog.d("NEW_NOTIF", "⚠️ UNKNOWN template: $template, msg='$msgText'")

                            if (topicId.isNotBlank() && isCommentResolved(topicId)) {
                                AndroidLog.d("NEW_NOTIF_SKIP", "already resolved topic: $topicId")
                                continue
                            }
                            val dedupKey = buildDedupKey(topicId, template, created)
                            if (topicId.isNotBlank() && !markTopicProcessed(dedupKey)) {
                                // 조용히 스킵
                                continue
                            }

                            if (DRY_RUN_NOTIF_ONLY) {
                                if (dryRunSeen.add(topicId)) {
                                    dryRunReceived.add(topicId)
                                    AndroidLog.d("DRYRUN", "unknown collected: topic=$topicId template=$template msg='$msgText'")
                                }
                                continue
                            }
                            val who = user.ifBlank { "알림" }
                            val fallbackTitle = contentTitleHint.ifBlank { "새 알림" }
                            val fallbackText = sanitizeNotificationText(
                                msgText.ifBlank { "$who 님의 알림이 도착했습니다." },
                                180
                            )
                            val fallbackKey = notifKey(
                                "UNKNOWN",
                                who,
                                template.ifBlank { "unknown_template" },
                                targetId,
                                created
                            )
                            markCommentResolved(topicId)
                            postSimpleNotification(
                                key = fallbackKey,
                                title = fallbackTitle,
                                text = fallbackText.ifBlank { "새 알림이 도착했습니다." },
                                whenMillis = parseWhen(created),
                                openUrl = deep,
                                topicId = topicId
                            )
                            processedCount++
                            continue
                        }
                        else -> {
                            AndroidLog.w("NEW_NOTIF", "Unexpected NotifType: $type (id=$topicId)")
                            continue
                        }
                    }
                }
                if (!DRY_RUN_NOTIF_ONLY && processedCount > 0) {
                    // 일부가 정상 처리되면 seen 복구 기회를 다시 열어 변동 payload(5/6개 교차)를 따라간다.
                    seenRecoveryAttempted = false
                }
                if (!DRY_RUN_NOTIF_ONLY && !seenRecoveryAttempted &&
                    processedCount == 0 && skippedSeenCount == arr.length() && arr.length() > 0
                ) {
                    seenRecoveryAttempted = true
                    val staleTopicIds = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val staleTopicId = item.optString("id", item.optString("_id", "")).trim()
                        if (staleTopicId.isNotBlank()) {
                            staleTopicIds.add(staleTopicId)
                        }
                    }
                    staleTopicIds.addAll(seenSkippedTopicBuffer)
                    clearSeenTopics(staleTopicIds)
                    seenSkippedTopicBuffer.clear()
                    AndroidLog.w("NEW_NOTIF_SKIP", "All items skipped by seen cache - cleared once for retry")
                    scheduleAlarmPollerRestart("seen_cache_reset", minDelayMs = 500L)
                }

                val toastMsg = if (processedCount == 0 && skippedSeenCount > 0) {
                    "알림 수신 ${arr.length()}건 / 중복 스킵 ${skippedSeenCount}건"
                } else {
                    "알림 수신 ${arr.length()}건 / 처리 ${processedCount}건"
                }
                showTriggerToast(toastMsg)
                if (skippedSeenCount > 0) {
                    AndroidLog.d("NEW_NOTIF_SKIP", "seen skip count=$skippedSeenCount/${arr.length()}")
                }

                if (DRY_RUN_NOTIF_ONLY && SHOW_DRYRUN_TOAST) {
                    AndroidLog.d("DRYRUN", "SUMMARY: total=${arr.length()}, collected=${dryRunReceived.size}, topics=$dryRunReceived")
                    Toast.makeText(this@MainActivity, "DRY-RUN: ${dryRunReceived.size}/${arr.length()} 수집", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AndroidLog.e("NEW_NOTIF", "처리 실패: ${e.message}")
            }
            return
        }
    }

    // ✅ URL에서 알림 정보를 추출하여 openEntryPage() 호출
    private fun handleNotificationUrl(url: String) {
        try {
            val safeUrl = sanitizeEntryUrl(url, "https://playentry.org/alarm")
            AndroidLog.d("NOTIF_URL", "Processing notification URL: $safeUrl")
            
            // URL 패턴 분석
            when {
                // 작품 페이지: /project/{id}
                safeUrl.contains("/project/") -> {
                    val projectId = safeUrl.substringAfter("/project/").substringBefore("?")
                    AndroidLog.d("NOTIF_URL", "Opening project: $projectId")
                    openEntryPage("project", projectId, null, "")
                }
                
                // 커뮤니티 페이지: /community/{category}/{id}
                safeUrl.contains("/community/") -> {
                    val path = safeUrl.substringAfter("/community/")
                    val parts = path.split("/")
                    if (parts.size >= 2) {
                        val category = parts[0]
                        val target = parts[1].substringBefore("?")
                        
                        // 커뮤니티로 잘못 생성된 사용자 경로 보정: /community/user/{id} → /profile/{id}/project
                        if (category == "user") {
                            AndroidLog.d("NOTIF_URL", "Rewriting community/user -> profile: $target")
                            openEntryPage("user", null, target, "")
                        } else {
                            AndroidLog.d("NOTIF_URL", "Opening community: $category/$target")
                            openEntryPage(category, target, null, "")
                        }
                    }
                }
                
                // 프로필 페이지: /profile/{user}/project
                safeUrl.contains("/profile/") && safeUrl.contains("/project") -> {
                    val userId = safeUrl.substringAfter("/profile/").substringBefore("/project")
                    AndroidLog.d("NOTIF_URL", "Opening profile: $userId")
                    openEntryPage("user", null, userId, "")
                    // 클릭 후 해당 userId의 팔로우 이벤트 재발행 억제 (3초)
                    followSuppressUntil[userId] = System.currentTimeMillis() + 3000
                }
                
                // 스페이스 월드: space.playentry.org/world/{id}
                safeUrl.contains("space.playentry.org/world/") -> {
                    val worldId = safeUrl.substringAfter("/world/").substringBefore("?")
                    AndroidLog.d("NOTIF_URL", "Opening space world: $worldId")
                    openEntryPage("space", worldId, null, "")
                }
                
                // 기본 알림 페이지
                else -> {
                    AndroidLog.d("NOTIF_URL", "Opening default alarm page")
                    openEntryPage(null, null, null, "")
                }
            }
        } catch (e: Exception) {
            AndroidLog.e("NOTIF_URL", "Error processing notification URL: $url", e)
            // 오류 시 기본 알림 페이지로
            openEntryPage(null, null, null, "")
        }
    }
}
