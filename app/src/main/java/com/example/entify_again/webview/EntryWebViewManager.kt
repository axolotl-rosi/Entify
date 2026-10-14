package com.example.entify_again.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.entify_again.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 엔트리 웹뷰 관리 클래스
 * 로그인, GraphQL 쿼리 실행, 댓글 작성 등을 처리합니다
 */
class EntryWebViewManager(private val context: Context) {
    
    companion object {
        private const val TAG = "EntryWebViewManager"
        private const val ENTRY_URL = "https://playentry.org"
        private const val GRAPHQL_URL = "https://playentry.org/graphql/SELECT_TOPICS"
        private const val COMMENTS_GRAPHQL_URL = "https://playentry.org/graphql/SELECT_COMMENTS"
    }
    
    private var webView: WebView? = null
    private val prefManager = PreferenceManager(context)
    
    /**
     * WebView 초기화
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun initWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 데스크톱 User-Agent로 고정 (Chrome on Windows)
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = WebViewClient()
            addJavascriptInterface(JSInterface(), "Android")

            webView = this
            Log.d(TAG, "WebView 초기화 완료")
        }
    }
    
    /**
     * 엔트리 로그인
     */
    suspend fun login(username: String, password: String): Boolean = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            val web = webView ?: initWebView()
            
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    
                    // 로그인 스크립트 실행
                    val loginScript = """
                        (function() {
                            const usernameInput = document.querySelector('input[name="username"]') || 
                                                 document.querySelector('input[type="text"]');
                            const passwordInput = document.querySelector('input[name="password"]') || 
                                                 document.querySelector('input[type="password"]');
                            const loginBtn = document.querySelector('button[type="submit"]') ||
                                           document.querySelector('.login-button');
                            
                            if (usernameInput && passwordInput && loginBtn) {
                                usernameInput.value = '$username';
                                passwordInput.value = '$password';
                                
                                // 이벤트 트리거
                                usernameInput.dispatchEvent(new Event('input', { bubbles: true }));
                                passwordInput.dispatchEvent(new Event('input', { bubbles: true }));
                                
                                setTimeout(() => {
                                    loginBtn.click();
                                    
                                    // 로그인 성공 확인
                                    setTimeout(() => {
                                        const isLoggedIn = !window.location.href.includes('/login');
                                        Android.onLoginResult(isLoggedIn.toString());
                                    }, 2000);
                                }, 500);
                            } else {
                                Android.onLoginResult('false');
                            }
                        })();
                    """.trimIndent()
                    
                    view?.evaluateJavascript(loginScript, null)
                }
            }
            
            // JavaScript Interface로 결과 받기
            jsLoginCallback = { success ->
                if (success) {
                    // 쿠키 및 토큰 저장
                    saveCookiesAndTokens(web)
                    prefManager.isLoggedIn = true
                    prefManager.username = username
                }
                continuation.resume(success)
            }
            
            web.loadUrl("$ENTRY_URL/login")
        }
    }
    
    /**
     * 쿠키와 토큰 저장
     */
    private fun saveCookiesAndTokens(webView: WebView) {
        // 쿠키 저장
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(ENTRY_URL)
        prefManager.cookies = cookies
        
        // CSRF 토큰과 X-Token 추출
        val tokenScript = """
            (function() {
                const csrf = document.querySelector('meta[name="csrf-token"]')?.content || '';
                const xToken = localStorage.getItem('playentry_token') || '';
                Android.onTokensExtracted(csrf, xToken);
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(tokenScript, null)
    }
    
    /**
     * 알림 목록 조회 (GraphQL)
     */
    suspend fun fetchNotifications(): String? = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            val web = webView ?: initWebView()
            
            val script = """
                (async () => {
                    const csrf = document.querySelector('meta[name="csrf-token"]')?.content || '';
                    const xToken = localStorage.getItem('playentry_token') || '';
                    
                    try {
                        const res = await fetch("$GRAPHQL_URL", {
                            method: "POST",
                            headers: {
                                "content-type": "application/json",
                                ...(csrf && { "csrf-token": csrf }),
                                ...(xToken && { "x-token": xToken }),
                                "x-client-type": "Client"
                            },
                            body: JSON.stringify({
                                query: `
                                    query SELECT_TOPICS(${'$'}pageParam: PageParam){
                                        topicList(pageParam: ${'$'}pageParam){
                                            list {
                                                id
                                                params
                                                category
                                                template
                                                created
                                                isRead
                                            }
                                        }
                                    }
                                `,
                                variables: { pageParam: { display: 20 } }
                            }),
                            credentials: "include"
                        });
                        
                        const data = await res.json();
                        Android.onNotificationsFetched(JSON.stringify(data));
                    } catch (e) {
                        Android.onNotificationsFetched('{"error": "' + e.message + '"}');
                    }
                })();
            """.trimIndent()
            
            jsNotificationsCallback = { result ->
                continuation.resume(result)
            }
            
            web.loadUrl(ENTRY_URL)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(script, null)
                }
            }
        }
    }
    
    /**
     * 댓글 작성
     * 보안 및 계정보호/서비스 정책 준수를 위해 자동 댓글 작성 기능은 원천 차단(비활성화)되어 있습니다.
     */
    suspend fun postComment(
        workId: String,
        commentText: String,
        parentCommentId: String? = null
    ): Boolean {
        Log.w(TAG, "보안 정책: 자동 댓글/공식 API 작성 호출이 원천 차단되어 실행되지 않습니다.")
        return false
    }
    
    /**
     * 댓글 찾기 (닉네임과 시간으로 검색)
     * findComment.js의 로직을 Kotlin으로 구현
     */
    suspend fun findComment(
        targetId: String,
        nickname: String,
        timeString: String? = null
    ): CommentResult? = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            val web = webView ?: initWebView()
            
            val script = """
                (async () => {
                    const csrf = document.querySelector('meta[name="csrf-token"]')?.content || '';
                    const xToken = localStorage.getItem('playentry_token') || '';
                    
                    // 시간 파싱 함수
                    function parseEntryTime(s) {
                        if (!s) return null;
                        const m = String(s).match(/[I]?(\d{2})\.(\d{2})\.(\d{2})\s*[・\-.]?\s*(\d{2}):(\d{2})/);
                        if (!m) return null;
                        const [_, yy, MM, dd, hh, mm] = m;
                        return new Date(2000 + +yy, +MM - 1, +dd, +hh, +mm, 0);
                    }
                    
                    // 같은 분 판정
                    function sameMinute(a, b) {
                        try { return Math.abs(new Date(a) - new Date(b)) <= 60_000; }
                        catch { return false; }
                    }
                    
                    const wantedName = "$nickname".trim().toLowerCase();
                    const wantedTime = parseEntryTime("$timeString");
                    
                    let searchAfter = null;
                    let found = null;
                    
                    // 최대 10페이지까지 검색
                    for (let page = 0; page < 10; page++) {
                        try {
                            const res = await fetch("$COMMENTS_GRAPHQL_URL", {
                                method: "POST",
                                headers: {
                                    "content-type": "application/json",
                                    ...(csrf && { "csrf-token": csrf }),
                                    ...(xToken && { "x-token": xToken }),
                                    "x-client-type": "Client"
                                },
                                body: JSON.stringify({
                                    query: `
                                        query SELECT_COMMENTS(${'$'}pageParam: PageParam, ${'$'}target: String, ${'$'}searchAfter: JSON){
                                            commentList(pageParam:${'$'}pageParam, target:${'$'}target, searchAfter:${'$'}searchAfter){
                                                list{
                                                    id
                                                    content
                                                    created
                                                    user{ nickname profileImage{ filename } }
                                                    image{ filename }
                                                    sticker{ filename }
                                                }
                                                searchAfter
                                            }
                                        }
                                    `,
                                    variables: {
                                        pageParam: { display: 30, sort: "created", order: -1 },
                                        target: "$targetId",
                                        searchAfter: searchAfter
                                    }
                                }),
                                credentials: "include"
                            });
                            
                            const data = await res.json();
                            const list = data?.data?.commentList?.list || [];
                            
                            if (list.length === 0) break;
                            
                            // 닉네임과 시간으로 댓글 찾기
                            const hit = list.find(c => {
                                const n = (c.user?.nickname || "").trim().toLowerCase();
                                const nameOk = n === wantedName;
                                const timeOk = !wantedTime || sameMinute(c.created, wantedTime);
                                return nameOk && timeOk;
                            });
                            
                            if (hit) {
                                found = hit;
                                break;
                            }
                            
                            // 다음 페이지
                            searchAfter = data?.data?.commentList?.searchAfter;
                            if (!searchAfter && list.length > 0) {
                                const last = list[list.length - 1];
                                searchAfter = { created: last.created, id: last.id };
                            }
                            
                            if (!searchAfter) break;
                            
                        } catch (e) {
                            console.error("댓글 검색 오류:", e);
                            break;
                        }
                    }
                    
                    if (found) {
                        Android.onCommentFound(JSON.stringify({
                            nickname: found.user?.nickname || "",
                            created: found.created || "",
                            content: found.content || "",
                            profileImageFilename: found.user?.profileImage?.filename || "",
                            stickerFilename: found.sticker?.filename || "",
                            imageFilename: found.image?.filename || ""
                        }));
                    } else {
                        Android.onCommentFound("");
                    }
                })();
            """.trimIndent()
            
            jsFindCommentCallback = { result ->
                if (result.isNotEmpty()) {
                    try {
                        val json = org.json.JSONObject(result)
                        continuation.resume(CommentResult(
                            nickname = json.optString("nickname", ""),
                            created = json.optString("created", ""),
                            content = json.optString("content", ""),
                            profileImageFilename = json.optString("profileImageFilename", ""),
                            stickerFilename = json.optString("stickerFilename", ""),
                            imageFilename = json.optString("imageFilename", "")
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "댓글 파싱 오류", e)
                        continuation.resume(null)
                    }
                } else {
                    continuation.resume(null)
                }
            }
            
            web.loadUrl(ENTRY_URL)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(script, null)
                }
            }
        }
    }
    
    /**
     * 업로드 파일 URL 정규화
     * findComment.js의 normalizeUploadUrl 로직을 Kotlin으로 구현
     */
    suspend fun normalizeUploadUrl(
        filename: String?,
        kind: String = "profile"
    ): String? = withContext(Dispatchers.Main) {
        if (filename.isNullOrEmpty()) return@withContext null

        return@withContext suspendCancellableCoroutine { continuation ->
            val web = webView ?: initWebView()

            val script = """
                (async () => {
                    const toAbs = p => /^https?:\/\//i.test(p) ? p : (location.origin + (p.startsWith('/') ? p : '/' + p));

                    function hasExt(name) {
                        return /\.[a-z0-9]{3,4}$/i.test(name);
                    }

                    function ensureUploadsPrefix(pathLike) {
                        if (!pathLike) return "";
                        if (pathLike.includes("/uploads/")) return pathLike.slice(pathLike.indexOf("/uploads/"));
                        if (pathLike.startsWith("/")) return "/uploads" + pathLike;
                        return pathLike.includes("/") ? ("/uploads/" + pathLike.replace(/^\/+/, "")) : pathLike;
                    }

                    function splitFoldersFromSlug(slug) {
                        const a = slug.slice(0,2), b = slug.slice(2,4);
                        return `${'$'}{a}/${'$'}{b}/${'$'}{slug}`;
                    }

                    function resolveExt(baseWithoutExt, extList) {
                        return new Promise(resolve => {
                            let i = 0;
                            const tryNext = () => {
                                if (i >= extList.length) return resolve("");
                                const url = toAbs(`/uploads/${'$'}{baseWithoutExt}.${'$'}{extList[i++]}`);
                                const img = new Image();
                                img.onload = () => resolve(url);
                                img.onerror = tryNext;
                                img.decoding = "async";
                                img.referrerPolicy = "no-referrer";
                                img.src = url;
                            };
                            tryNext();
                        });
                    }

                    async function normalizeUploadUrl(raw, kind) {
                        if (!raw) return "";

                        // 확장자 우선순위
                        const extCandidates = kind === "sticker"
                            ? ["svg","png","webp","jpg","jpeg","gif"]
                            : kind === "image"
                            ? ["png","jpg","jpeg","webp","gif"]
                            : ["jpeg","svg","jpg","png","webp"]; // profile

                        // 1) 절대 URL
                        if (/^https?:\/\//i.test(raw)) {
                            if (hasExt(raw)) return raw;
                            const body = raw.replace(/^https?:\/\/[^/]+\/uploads\//i, "");
                            const guessed = await resolveExt(body.replace(/^\/?uploads\//i,"").replace(/^\//,""), extCandidates);
                            return guessed || raw;
                        }

                        // 2) 상대 경로
                        let path = ensureUploadsPrefix(String(raw).trim());
                        if (path.startsWith("/uploads/") && hasExt(path)) return toAbs(path);

                        // 3) 디렉토리 포함, 확장자 없음
                        if (path.startsWith("/uploads/") && !hasExt(path) && path.split("/").length >= 4) {
                            const body = path.replace(/^\/?uploads\//i,"").replace(/^\//,"");
                            const guessed = await resolveExt(body, extCandidates);
                            return guessed || toAbs(path);
                        }

                        // 4) slug만 있는 경우
                        if (!path.includes("/") || !path.startsWith("/uploads/")) {
                            const onlySlug = path.replace(/^\/?uploads\//i,"").replace(/^\//,"");
                            const body = splitFoldersFromSlug(onlySlug);
                            const guessed = await resolveExt(body, extCandidates);
                            return guessed || toAbs("/uploads/" + body);
                        }

                        return toAbs(path);
                    }

                    const result = await normalizeUploadUrl("$filename", "$kind");
                    Android.onUrlNormalized(result);
                })();
            """.trimIndent()

            jsUrlNormalizeCallback = { url ->
                continuation.resume(if (url.isNotEmpty()) url else null)
            }

            web.loadUrl(ENTRY_URL)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(script, null)
                }
            }
        }
    }

    /**
     * template_27 알림 페이지에서 프로필 이미지 추출
     * 사용자 프로필 페이지의 HTML을 분석하여 프로필 이미지 URL을 추출합니다
     */
    suspend fun extractProfileImageFromTemplate27(profileUrl: String): String? = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            val web = webView ?: initWebView()

            val script = """
                (function() {
                    // 1. style 속성이 있고 background-image에 "/uploads/" 포함된 요소 찾기
                    const candidates = Array.from(document.querySelectorAll('[style*="background-image"]'))
                        .filter(el => {
                            const bgImage = el.style.backgroundImage || '';
                            return bgImage.includes('/uploads/');
                        });

                    // 2. 필터링: 제거 조건
                    const filtered = candidates.filter(el => {
                        // 2-1. avatarButton 제외 (헤더의 내 프로필)
                        if (el.tagName === 'EM' && el.dataset.testid === 'avatarButton') {
                            return false;
                        }

                        // 2-2. div가 아닌 요소 제외
                        if (el.tagName !== 'DIV') {
                            return false;
                        }

                        // 2-3. 근처에 닉네임 + 팔로잉/팔로워 텍스트가 있는지 확인
                        let parent = el.parentElement;
                        let depth = 0;
                        let hasUserInfo = false;

                        // 최대 5단계 부모까지 확인
                        while (parent && depth < 5) {
                            const text = parent.textContent || '';
                            // "팔로잉"과 "팔로워" 문자열이 모두 포함되어 있는지 확인
                            if (text.includes('팔로잉') && text.includes('팔로워')) {
                                hasUserInfo = true;
                                break;
                            }
                            parent = parent.parentElement;
                            depth++;
                        }

                        return hasUserInfo;
                    });

                    // 3. 첫 번째 유효한 요소에서 /uploads/[^")]+ 패턴 추출
                    if (filtered.length > 0) {
                        const el = filtered[0];
                        const bgImage = el.style.backgroundImage || '';

                        // background-image: url("/uploads/...") 또는 url('/uploads/...') 형식
                        const match = bgImage.match(/\/uploads\/[^"')]+/);

                        if (match) {
                            const path = match[0];
                            // 4. https://playentry.org + 경로
                            const fullUrl = 'https://playentry.org' + path;
                            Android.onProfileImageExtracted(fullUrl);
                            return;
                        }
                    }

                    // 프로필 이미지를 찾지 못한 경우
                    Android.onProfileImageExtracted('');
                })();
            """.trimIndent()

            jsProfileImageCallback = { url ->
                continuation.resume(if (url.isNotEmpty()) url else null)
            }

            web.loadUrl(profileUrl)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(script, null)
                }
            }
        }
    }

    /**
     * template_28 알림 페이지에서 프로필 이미지 추출
     * 작품 페이지의 HTML을 분석하여 작성자의 프로필 이미지 URL을 추출합니다
     */
    suspend fun extractProfileImageFromTemplate28(projectUrl: String): String? = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            val web = webView ?: initWebView()

            val script = """
                (function() {
                    // 1. style 속성이 있고 background-image에 "/uploads/" 포함된 요소 수집
                    const candidates = Array.from(document.querySelectorAll('[style*="background-image"]'))
                        .filter(el => {
                            const bgImage = el.style.backgroundImage || '';
                            return bgImage.includes('/uploads/');
                        });

                    // 2. em[data-testid=avatarButton] 제거
                    const step2 = candidates.filter(el => {
                        if (el.tagName === 'EM' && el.dataset.testid === 'avatarButton') {
                            return false;
                        }
                        return true;
                    });

                    // 3. tag != a 제거 (a 태그만 통과)
                    const step3 = step2.filter(el => el.tagName === 'A');

                    // 4. 댓글/유사작품 문맥 제거
                    const step4 = step3.filter(el => {
                        let parent = el.parentElement;
                        let depth = 0;

                        // 최대 10단계 부모까지 확인
                        while (parent && depth < 10) {
                            const text = parent.textContent || '';
                            const className = parent.className || '';

                            // 댓글 관련 텍스트나 클래스 확인
                            if (text.includes('댓글') || className.includes('comment')) {
                                return false;
                            }

                            // 유사작품 관련 텍스트 확인
                            if (text.includes('유사한 작품') || text.includes('similar')) {
                                return false;
                            }

                            parent = parent.parentElement;
                            depth++;
                        }

                        return true;
                    });

                    // 5. 팔로잉/팔로워 텍스트가 같은 블록에 있는 a만 통과
                    const filtered = step4.filter(el => {
                        let parent = el.parentElement;
                        let depth = 0;
                        let hasFollowInfo = false;

                        // 최대 5단계 부모까지 확인
                        while (parent && depth < 5) {
                            const text = parent.textContent || '';

                            // "팔로잉"과 "팔로워" 문자열이 모두 포함되어 있는지 확인
                            if (text.includes('팔로잉') && text.includes('팔로워')) {
                                hasFollowInfo = true;
                                break;
                            }

                            parent = parent.parentElement;
                            depth++;
                        }

                        return hasFollowInfo;
                    });

                    // 6. 첫 번째 유효한 요소에서 /uploads/[^")]+ 패턴 추출
                    if (filtered.length > 0) {
                        const el = filtered[0];
                        const bgImage = el.style.backgroundImage || '';

                        // background-image: url("/uploads/...") 또는 url('/uploads/...') 형식
                        const match = bgImage.match(/\/uploads\/[^"')]+/);

                        if (match) {
                            const path = match[0];
                            // 7. https://playentry.org와 결합
                            const fullUrl = 'https://playentry.org' + path;
                            Android.onProfileImageExtracted(fullUrl);
                            return;
                        }
                    }

                    // 프로필 이미지를 찾지 못한 경우
                    Android.onProfileImageExtracted('');
                })();
            """.trimIndent()

            jsProfileImageCallback = { url ->
                continuation.resume(if (url.isNotEmpty()) url else null)
            }

            web.loadUrl(projectUrl)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(script, null)
                }
            }
        }
    }
    
    /**
     * 리소스 해제
     */
    fun destroy() {
        webView?.destroy()
        webView = null
    }
    
    // JavaScript Interface
    private inner class JSInterface {
        @JavascriptInterface
        fun onLoginResult(success: String) {
            jsLoginCallback?.invoke(success.toBoolean())
        }

        @JavascriptInterface
        fun onTokensExtracted(csrf: String, xToken: String) {
            prefManager.csrfToken = csrf
            prefManager.xToken = xToken
            Log.d(TAG, "토큰 저장 완료")
        }

        @JavascriptInterface
        fun onNotificationsFetched(json: String) {
            jsNotificationsCallback?.invoke(json)
        }

        @JavascriptInterface
        fun onCommentPosted(success: String) {
            jsCommentCallback?.invoke(success.toBoolean())
        }

        @JavascriptInterface
        fun onCommentFound(json: String) {
            jsFindCommentCallback?.invoke(json)
        }

        @JavascriptInterface
        fun onUrlNormalized(url: String) {
            jsUrlNormalizeCallback?.invoke(url)
        }

        @JavascriptInterface
        fun onProfileImageExtracted(url: String) {
            jsProfileImageCallback?.invoke(url)
        }
    }

    // Callbacks
    private var jsLoginCallback: ((Boolean) -> Unit)? = null
    private var jsNotificationsCallback: ((String) -> Unit)? = null
    private var jsCommentCallback: ((Boolean) -> Unit)? = null
    private var jsFindCommentCallback: ((String) -> Unit)? = null
    private var jsUrlNormalizeCallback: ((String) -> Unit)? = null
    private var jsProfileImageCallback: ((String) -> Unit)? = null
}

/**
 * 댓글 검색 결과
 */
data class CommentResult(
    val nickname: String,
    val created: String,
    val content: String,
    val profileImageFilename: String,
    val stickerFilename: String,
    val imageFilename: String
)
