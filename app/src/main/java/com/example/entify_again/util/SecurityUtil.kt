package com.example.entify_again.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.entify_again.util.PrivacyLog as Log
import com.example.entify_again.webview.EntryOriginPolicy
import java.util.Locale

/**
 * Entify 통합 보안 유틸리티
 *
 * 외부 공격(인젝션, 악성 URL 리다이렉션, ReDoS, Bidi 스푸핑, 제어 문자 삽입 등)과
 * 비정상적인 데이터로 인한 클라이언트 프리징(UI 마비)을 원천 차단하기 위한 보안 모듈입니다.
 */
object SecurityUtil {

    private const val TAG = "SecurityUtil"

    // 최대 허용 길이 제한 (메모리 고갈 및 렌더링 마비 방지)
    const val MAX_URL_LENGTH = 1024
    const val MAX_TEXT_DISPLAY_LENGTH = 1000
    const val MAX_PREVIEW_TEXT_LENGTH = 300

    // 엔트리 공식 도메인 화이트리스트 패턴
    // playentry.org 및 *.playentry.org (space.playentry.org 등)
    private val ALLOWED_HOST_REGEX = Regex(
        "^([a-zA-Z0-9-]+\\.)*playentry\\.org$",
        RegexOption.IGNORE_CASE
    )

    // 신뢰할 수 있는 엔트리 CDN / 이미지 호스트 (네이버 CDN pstatic.net, naver.com 포함)
    private val ALLOWED_IMAGE_HOST_REGEX = Regex(
        "^(([a-zA-Z0-9-]+\\.)*playentry\\.org|entry-uploads\\.playentry\\.org|([a-zA-Z0-9-]+\\.)*pstatic\\.net|([a-zA-Z0-9-]+\\.)*naver\\.com)$",
        RegexOption.IGNORE_CASE
    )

    // 위험한 URI 스킴 블랙리스트 (javascript, data, file, content, intent 등)
    private val DANGEROUS_SCHEMES = setOf(
        "javascript", "data", "file", "content", "intent", "about", "blob", "chrome", "ws", "wss"
    )

    /**
     * 엔트리 공식 HTTPS URL인지 엄격하게 검증합니다.
     * 1. 길이 제한 (1024자 이하)
     * 2. 오직 https 스킴만 허용 (http, javascript, intent, file 등 차단)
     * 3. host가 playentry.org 또는 *.playentry.org와 완전 일치
     * 4. 사용자 정보(@)나 비정상 포트 등 피싱/우회 기법 차단
     * 5. 경로에 상위 디렉터리 순회(..) 및 null 바이트 미포함
     */
    fun isValidEntryUrl(urlRaw: String?): Boolean {
        if (urlRaw.isNullOrBlank()) return false
        val trimmed = urlRaw.trim()
        if (trimmed.length > MAX_URL_LENGTH) return false
        return EntryOriginPolicy.isTrusted(trimmed)
    }

    /**
     * 이미지 URL이 안전한 엔트리 미디어 호스트인지 검증합니다.
     * 외부 추적 픽셀이나 악성 스크립트 인젝션 이미지를 차단합니다.
     */
    fun isValidImageUrl(urlRaw: String?): Boolean {
        if (urlRaw.isNullOrBlank()) return false
        val trimmed = urlRaw.trim()
        if (trimmed.length > 2048) return false

        // Base64 Data URL 허용 (제한된 이미지 타입만)
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            return isSafeImageDataUrl(trimmed)
        }

        return EntryOriginPolicy.isTrustedImageHost(trimmed)
    }

    private fun isSafeImageDataUrl(dataUrl: String): Boolean {
        if (dataUrl.length > 512 * 1024) return false // 512KB 초과 제한
        val pattern = Regex(
            "^data:image/(png|jpe?g|webp|gif|svg\\+xml);base64,[a-zA-Z0-9+/=\\r\\n]+$",
            RegexOption.IGNORE_CASE
        )
        return pattern.matches(dataUrl)
    }

    /**
     * 텍스트 렌더링 전 유니코드 인젝션 및 클라이언트 마비 문자 제거:
     * - Null 문자 및 비출력 제어 문자(\u0000~\u001F, \u007F~\u009F)
     * - Bidi 텍스트 반전/스푸핑 제어 문자 (\u202A~\u202E, \u2066~\u2069, \u200E, \u200F)
     * - 제로 폭(Zero-width) 투명 문자 (\u200B, \u200C, \u200D, \uFEFF)
     * - 최대 길이 강제 제한 (ReDoS 및 UI 레이아웃 프리징 방지)
     */
    fun sanitizeDisplayText(input: String?, maxLength: Int = MAX_TEXT_DISPLAY_LENGTH): String {
        if (input.isNullOrBlank()) return ""

        val sb = StringBuilder(input.length.coerceAtMost(maxLength))
        var count = 0

        for (ch in input) {
            if (count >= maxLength) break

            // 필터링할 제어/스푸핑 문자 검사
            val code = ch.code
            val isControl = (code in 0x0000..0x0008) ||
                    (code in 0x000B..0x000C) ||
                    (code in 0x000E..0x001F) ||
                    (code in 0x007F..0x009F)
            val isBidi = (code in 0x202A..0x202E) || (code in 0x2066..0x2069) || code == 0x200E || code == 0x200F
            val isZeroWidth = (code in 0x200B..0x200D) || code == 0xFEFF

            if (!isControl && !isBidi && !isZeroWidth) {
                sb.append(ch)
                count++
            } else if (code == 0x000A || code == 0x000D) {
                // 줄바꿈 허용 (단, 단일 공백이나 개행으로 정규화)
                sb.append('\n')
                count++
            }
        }

        // 연속된 과도한 줄바꿈 제거 (최대 2연속 개행만 유지)
        val result = sb.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return if (result.length > maxLength) result.take(maxLength) else result
    }

    /**
     * 안전하게 브라우저로 엔트리 페이지를 엽니다.
     * 검증되지 않은 링크는 실행을 거부하여 의도치 않은 인텐트 하이재킹을 방지합니다.
     */
    fun launchSafeEntryUrl(context: Context, url: String?): Boolean {
        if (!isValidEntryUrl(url)) {
            Log.w(TAG, "위험하거나 유효하지 않은 URL 이동 요청 차단: $url")
            return false
        }
        val safeUri = Uri.parse(url!!.trim())
        val intent = Intent(Intent.ACTION_VIEW, safeUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "브라우저를 찾을 수 없음: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "보안 예외로 인한 이동 실패: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "URL 실행 중 오류 발생: ${e.message}")
            false
        }
    }

    /**
     * 엔트리 프로필 마이페이지 URL 또는 텍스트에서 24자리 Hex ID를 추출합니다.
     * 예: https://playentry.org/profile/61409f26389b55ababc8d4f4/project?sort=created... -> 61409f26389b55ababc8d4f4
     * URL이 잘리거나 뒤에 쿼리가 붙어도 24자리 hexID를 정확하게 찾아내며,
     * 24자리 hexID가 없을 경우 마이페이지 유저네임 또는 앞뒤 공백을 제거한 원본을 반환합니다.
     */
    fun extractProfileHexId(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val trimmed = input.trim()
        // 1) profile/ 뒤의 24자리 hex 추출 우선
        val profileRegex = Regex("""profile/([0-9a-fA-F]{24})""", RegexOption.IGNORE_CASE)
        profileRegex.find(trimmed)?.let {
            return it.groupValues[1].lowercase(Locale.ROOT)
        }
        // 2) 문자열 내에 존재하는 24자리 hex 추출
        val hexRegex = Regex("""\b([0-9a-fA-F]{24})\b""")
        hexRegex.find(trimmed)?.let {
            return it.groupValues[1].lowercase(Locale.ROOT)
        }
        // 3) 만약 profile/ 뒤의 닉네임 형식인 경우 (e.g. profile/username)
        val profileUserRegex = Regex("""profile/([^/?#]+)""", RegexOption.IGNORE_CASE)
        profileUserRegex.find(trimmed)?.let {
            return it.groupValues[1]
        }
        return trimmed
    }

    /**
     * 토큰이나 쿠키 문자열을 로그에 남길 때 민감정보를 마스킹합니다.
     */
    fun maskToken(token: String?): String {
        if (token.isNullOrBlank()) return ""
        val trimmed = token.trim()
        return if (trimmed.length > 12) {
            "${trimmed.take(4)}...${trimmed.takeLast(4)}"
        } else {
            "***"
        }
    }
}
