package com.example.entify_again.util

import com.example.entify_again.util.PrivacyLog as Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

object UserProfileFetcher {

    private const val TAG = "UserProfileFetcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class UserProfileResult(val nickname: String?, val avatarUrl: String?)

    data class ProjectResult(
        val name: String?,
        val thumbUrl: String?,
        val authorId: String?,
        val authorNickname: String?,
        val authorAvatarUrl: String?
    )

    suspend fun fetchProfile(
        idOrNickname: String,
        cookies: String? = null,
        csrfToken: String? = null,
        xToken: String? = null
    ): UserProfileResult? = withContext(Dispatchers.IO) {
        val cleanId = idOrNickname.trim()
        if (cleanId.isBlank()) return@withContext null

        val liveCookie = runCatching {
            CookieManager.getInstance().getCookie("https://playentry.org").orEmpty()
        }.getOrElse { "" }.ifBlank { cookies.orEmpty() }

        val effectiveCsrf = csrfToken?.takeIf { it.isNotBlank() }
            ?: extractCsrfFromCookie(liveCookie)
            ?: fetchCsrfTokenFast(liveCookie)

        val query = """
            query FIND_USERSTATUS_BY_USERNAME(${'$'}id: String) {
              userstatus(id: ${'$'}id) {
                id
                nickname
                username
                profileImage {
                  filename
                  imageType
                }
              }
            }
        """.trimIndent()
        val bodyJson = JSONObject()
            .put("operationName", "FIND_USERSTATUS_BY_USERNAME")
            .put("query", query)
            .put("variables", JSONObject().put("id", cleanId))
            .toString()
        val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url("https://playentry.org/graphql")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://playentry.org")
            .header("Referer", "https://playentry.org/profile/$cleanId")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("x-client-type", "Client")
            .post(body)

        if (!effectiveCsrf.isNullOrBlank()) {
            requestBuilder.header("csrf-token", effectiveCsrf)
        }
        if (!xToken.isNullOrBlank()) {
            requestBuilder.header("x-token", xToken)
        }
        if (liveCookie.isNotBlank()) {
            requestBuilder.header("Cookie", liveCookie)
        }

        val graphqlResult = try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val respStr = SafeResponseReader.readText(response.body).orEmpty()
                if (response.isSuccessful && respStr.isNotBlank()) {
                    val root = JSONObject(respStr)
                    val userstatus = root.optJSONObject("data")?.optJSONObject("userstatus")
                    if (userstatus != null) {
                        val nickname = userstatus.optString("nickname").ifBlank { userstatus.optString("username") }
                        val profileImgObj = userstatus.optJSONObject("profileImage")
                        val filename = profileImgObj?.optString("filename").orEmpty()
                        val imageType = profileImgObj?.optString("imageType").orEmpty()
                        val avatarUrl = if (filename.isNotBlank()) {
                            buildUploadUrlFromSlug(filename, imageType)
                        } else null

                        Log.d(TAG, "fetchProfile GraphQL success for $cleanId: nickname=$nickname, avatarUrl=$avatarUrl")
                        UserProfileResult(
                            nickname = nickname.takeIf { it.isNotBlank() },
                            avatarUrl = avatarUrl
                        )
                    } else {
                        val errors = root.optJSONArray("errors")
                        Log.w(TAG, "fetchProfile GraphQL userstatus null for $cleanId: ${errors?.optJSONObject(0)?.optString("message")}")
                        null
                    }
                } else {
                    Log.w(TAG, "fetchProfile HTTP ${response.code} for $cleanId: ${respStr.take(150)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchProfile GraphQL exception for $cleanId: ${e.message}")
            null
        }

        if (graphqlResult != null && (!graphqlResult.nickname.isNullOrBlank() || !graphqlResult.avatarUrl.isNullOrBlank())) {
            return@withContext graphqlResult
        }

        // GraphQL 실패 시 프로필 페이지 HTML 직접 스크래핑 폴백 (네트워크 오류 메시지 오탐 방지)
        Log.d(TAG, "fetchProfile falling back to HTML extraction for $cleanId")
        fetchProfileFromHtml(cleanId, liveCookie)
    }

    private fun extractCsrfFromCookie(cookieHeader: String): String? {
        val match = Regex("(?:csrf-token|entry_csrfToken)=([^;\\s]+)").find(cookieHeader)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun fetchCsrfTokenFast(cookieHeader: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://playentry.org/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = SafeResponseReader.readText(
                    resp.body,
                    SafeResponseReader.MAX_HTML_BYTES
                ).orEmpty()
                val match = Regex("<meta\\s+name=[\"']csrf-token[\"']\\s+content=[\"']([^\"']+)[\"']").find(html)
                    ?: Regex("[\"']csrfToken[\"']\\s*:\\s*[\"']([^\"']+)[\"']").find(html)
                match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    private fun fetchProfileFromHtml(cleanId: String, cookieHeader: String): UserProfileResult? {
        return try {
            val req = Request.Builder()
                .url("https://playentry.org/profile/$cleanId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = SafeResponseReader.readText(
                    resp.body,
                    SafeResponseReader.MAX_HTML_BYTES
                ).orEmpty()
                if (html.isBlank()) return null

                // 1) Next.js __NEXT_DATA__ 에서 닉네임 & 프로필 이미지 추출 시도
                var avatarUrl: String? = null
                var nickname: String? = null

                val nextDataMatch = Regex("<script id=\"__NEXT_DATA__\"[^>]*>([\\s\\S]*?)</script>").find(html)
                if (nextDataMatch != null) {
                    val jsonStr = nextDataMatch.groupValues[1]
                    val json = runCatching { JSONObject(jsonStr) }.getOrNull()
                    val userStatus = json?.optJSONObject("props")
                        ?.optJSONObject("pageProps")
                        ?.optJSONObject("userstatus")
                        ?: json?.optJSONObject("initialState")
                            ?.optJSONObject("common")
                            ?.optJSONObject("user")
                    if (userStatus != null) {
                        nickname = userStatus.optString("nickname").ifBlank { userStatus.optString("username") }.takeIf { it.isNotBlank() }
                        val pImg = userStatus.optJSONObject("profileImage")
                        val fn = pImg?.optString("filename").orEmpty()
                        val it = pImg?.optString("imageType").orEmpty()
                        if (fn.isNotBlank()) {
                            avatarUrl = buildUploadUrlFromSlug(fn, it)
                        }
                    }
                }

                // 2) HTML 스타일/태그 정규식 폴백 (/uploads/... 중 bm/thumb 제외)
                if (avatarUrl.isNullOrBlank()) {
                    val uploadMatch = Regex("/uploads/((?!bm/|thumb/)[a-zA-Z0-9/_.-]+\\.(?:png|jpg|jpeg|webp|svg))", RegexOption.IGNORE_CASE).find(html)
                    if (uploadMatch != null) {
                        val path = uploadMatch.groupValues[0]
                        avatarUrl = if (path.startsWith("http")) path else "https://playentry.org$path"
                    }
                }

                // 3) 닉네임이 없으면 title 태그 등에서 추출
                if (nickname.isNullOrBlank()) {
                    val titleMatch = Regex("<title[^>]*>(.*?)(?:\\s*\\|\\s*엔트리)?</title>").find(html)
                    val rawTitle = titleMatch?.groupValues?.get(1)?.trim().orEmpty()
                    if (rawTitle.isNotBlank() && rawTitle != "엔트리") {
                        nickname = rawTitle
                    } else {
                        nickname = cleanId
                    }
                }

                if (avatarUrl != null || nickname != null) {
                    Log.d(TAG, "fetchProfileFromHtml success for $cleanId: nick=$nickname, avatar=$avatarUrl")
                    UserProfileResult(nickname = nickname, avatarUrl = avatarUrl)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchProfileFromHtml error for $cleanId: ${e.message}")
            null
        }
    }

    suspend fun fetchProject(
        projectId: String,
        cookies: String? = null,
        csrfToken: String? = null,
        xToken: String? = null
    ): ProjectResult? = withContext(Dispatchers.IO) {
        val cleanId = projectId.trim()
        if (cleanId.isBlank()) return@withContext null
        val query = """
            query SELECT_PROJECT_LITE(${'$'}id: ID!, ${'$'}groupId: ID) {
              project(id: ${'$'}id, groupId: ${'$'}groupId) {
                id
                name
                thumb
                user {
                  id
                  nickname
                  username
                  profileImage {
                    filename
                    imageType
                  }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject()
            .put("id", cleanId)
            .put("groupId", JSONObject.NULL)
        val bodyJson = JSONObject()
            .put("operationName", "SELECT_PROJECT_LITE")
            .put("query", query)
            .put("variables", variables)
            .toString()
        val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

        val liveCookie = runCatching {
            CookieManager.getInstance().getCookie("https://playentry.org").orEmpty()
        }.getOrElse { "" }.ifBlank { cookies.orEmpty() }

        val effectiveCsrf = csrfToken?.takeIf { it.isNotBlank() }
            ?: extractCsrfFromCookie(liveCookie)
            ?: fetchCsrfTokenFast(liveCookie)

        val requestBuilder = Request.Builder()
            .url("https://playentry.org/graphql")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://playentry.org")
            .header("Referer", "https://playentry.org/project/$cleanId")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("x-client-type", "Client")
            .post(body)

        if (!effectiveCsrf.isNullOrBlank()) {
            requestBuilder.header("csrf-token", effectiveCsrf)
        }
        if (!xToken.isNullOrBlank()) {
            requestBuilder.header("x-token", xToken)
        }
        if (liveCookie.isNotBlank()) {
            requestBuilder.header("Cookie", liveCookie)
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val respStr = SafeResponseReader.readText(response.body).orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchProject HTTP ${response.code} for $cleanId: ${respStr.take(200)}")
                    return@withContext null
                }
                if (respStr.isBlank()) return@withContext null
                val root = JSONObject(respStr)
                val errors = root.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    Log.w(TAG, "fetchProject GraphQL error for $cleanId: ${errors.optJSONObject(0)?.optString("message")}")
                }
                val project = root.optJSONObject("data")?.optJSONObject("project") ?: return@withContext null
                val name = project.optString("name").takeIf { it.isNotBlank() }
                val thumbRaw = project.optString("thumb").takeIf { it.isNotBlank() }
                val thumbUrl = thumbRaw?.let { buildThumbUrl(it) }

                val userObj = project.optJSONObject("user")
                val authorId = userObj?.optString("id")?.takeIf { it.isNotBlank() }
                val authorNickname = userObj?.optString("nickname")?.ifBlank { userObj.optString("username") }?.takeIf { it.isNotBlank() }
                val profileImgObj = userObj?.optJSONObject("profileImage")
                val filename = profileImgObj?.optString("filename").orEmpty()
                val imageType = profileImgObj?.optString("imageType").orEmpty()
                val authorAvatarUrl = if (filename.isNotBlank()) {
                    buildUploadUrlFromSlug(filename, imageType)
                } else null

                Log.d(TAG, "fetchProject success for $cleanId: name=$name, thumbUrl=$thumbUrl, author=$authorNickname")
                ProjectResult(
                    name = name,
                    thumbUrl = thumbUrl,
                    authorId = authorId,
                    authorNickname = authorNickname,
                    authorAvatarUrl = authorAvatarUrl
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchProject exception for $cleanId: ${e.message}", e)
            null
        }
    }

    fun buildThumbUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val withoutSlash = trimmed.removePrefix("/")
        if (withoutSlash.startsWith("uploads/", ignoreCase = true)) {
            return "https://playentry.org/$withoutSlash"
        }
        if (withoutSlash.contains("/")) {
            return "https://playentry.org/uploads/$withoutSlash"
        }
        return buildUploadUrlFromSlug(withoutSlash, "png")
    }

    fun buildUploadUrlFromSlug(filename: String, imageType: String?): String {
        val trimmed = filename.trim()
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
        val withoutPrefix = trimmed.removePrefix("/").removePrefix("uploads/").removePrefix("/")
        val slugPath = when {
            withoutPrefix.contains("/") -> withoutPrefix
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
        val ext = when (imageType?.trim()?.lowercase(Locale.ROOT)) {
            "image/png", "png" -> ".png"
            "image/jpeg", "image/jpg", "jpg", "jpeg" -> ".jpg"
            "image/gif", "gif" -> ".gif"
            "image/webp", "webp" -> ".webp"
            "image/svg+xml", "svg" -> ".svg"
            else -> if (slugPath.contains(".")) "" else ".png"
        }
        val fullSlug = if (slugPath.endsWith(ext, ignoreCase = true)) slugPath else "$slugPath$ext"
        return "https://playentry.org/uploads/$fullSlug"
    }
}
