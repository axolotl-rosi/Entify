package com.example.entify_again.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.entify_again.data.entity.NotificationEntity
import com.example.entify_again.ui.theme.CategoryColors
import com.example.entify_again.util.EntryTemplateFormatter
import com.example.entify_again.util.NotificationSemantics
import com.example.entify_again.util.SecurityUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun NotificationCard(
    notification: NotificationEntity,
    onCardClick: (() -> Unit)? = null,
    onRequestNavigate: ((String) -> Unit)? = null,
    onMediaClick: ((url: String, title: String) -> Unit)? = null,
    onStarToggle: () -> Unit,
    onRequestBlockUser: ((NotificationEntity) -> Unit)? = null,
    onUnblockUser: ((NotificationEntity) -> Unit)? = null,
    onDeleteNotification: (() -> Unit)? = null,
    showActions: Boolean = true,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val displayCategory = remember(notification.template, notification.body, notification.category) {
        NotificationSemantics.resolve(
            template = notification.template,
            message = notification.body,
            declaredCategory = notification.category
        ).category
    }
    val displayIconType = when (displayCategory) {
        "COMMENT", "REPLY" -> EntryTemplateFormatter.LeftIconType.COMMENT
        "LIKE" -> EntryTemplateFormatter.LeftIconType.LIKE
        "FOLLOW" -> EntryTemplateFormatter.LeftIconType.FOLLOW
        "SHARE", "REMAKE" -> EntryTemplateFormatter.LeftIconType.SHARE
        "WARNING" -> EntryTemplateFormatter.LeftIconType.WARNING
        else -> EntryTemplateFormatter.LeftIconType.AI_SYSTEM
    }
    val categoryColor = CategoryColors.forCategory(displayCategory)
    var expanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val (actionText, commentContent) = remember(notification.body) {
        parseNotificationBody(notification.body)
    }
    val templateInfo = remember(notification.template, notification.actor, notification.body) {
        EntryTemplateFormatter.format(
            template = notification.template,
            rawParams = null,
            fallbackActor = notification.actor,
            fallbackBody = notification.body
        )
    }
    val hasLongBody = (commentContent?.length ?: notification.body.length) > 70 || notification.body.contains('\n')
    val canNavigate = remember(notification.deepLink) {
        SecurityUtil.isValidEntryUrl(notification.deepLink) && !isNonInteractiveDeepLink(notification.deepLink)
    }

    // 댓글 좋아요 알림에서 상대방 Hex ID 및 프로젝트 링크가 둘 다 없는 특수 케이스 식별
    val isLikeWithoutProjectOrAvatar = templateInfo.isLikeWithoutId || (
        (notification.category.equals("LIKE", ignoreCase = true) || notification.template == "template_36") &&
            notification.actorImageUrl.isNullOrBlank() &&
            notification.stickerUrl.isNullOrBlank() &&
            notification.imageUrl.isNullOrBlank() &&
            notification.deepLink.isNullOrBlank()
    )
    val safeProjectThumbnail = remember(notification.projectThumbnailUrl) {
        val raw = notification.projectThumbnailUrl?.trim().orEmpty()
        val absolute = when {
            raw.isBlank() -> null
            raw.startsWith("http", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://playentry.org$raw"
            else -> "https://playentry.org/$raw"
        }
        absolute?.takeIf(SecurityUtil::isValidImageUrl)
    }
    val safeActorImage = remember(notification.actorImageUrl) {
        val raw = notification.actorImageUrl?.trim().orEmpty()
        val absolute = when {
            raw.isBlank() -> null
            raw.startsWith("http", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://playentry.org$raw"
            else -> "https://playentry.org/$raw"
        }
        absolute?.takeIf(SecurityUtil::isValidImageUrl)
    }
    val isProjectNotification = displayCategory in setOf("SHARE", "REMAKE", "LIKE") ||
        notification.template in setOf("template_28", "template_29", "template_5", "template_6", "template_3", "template_37")
    val rightImageUrl = if (isProjectNotification) {
        safeProjectThumbnail ?: safeActorImage
    } else {
        safeActorImage ?: safeProjectThumbnail
    }
    val isShowingProjectThumbnail = isProjectNotification && safeProjectThumbnail != null
    val rightImageShape = if (isShowingProjectThumbnail) RoundedCornerShape(9.dp) else CircleShape
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val highlightedActionText = remember(actionText, notification.actor, onSurfaceColor) {
        buildHighlightedActionText(actionText, notification.actor, onSurfaceColor)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (isSelectionMode) {
                    onToggleSelect?.invoke()
                } else {
                    onCardClick?.invoke()
                    if (hasLongBody) expanded = !expanded
                }
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                notification.isRead -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (notification.isRead) 0.5.dp else 2.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = when {
                isSelected -> MaterialTheme.colorScheme.primary
                notification.isRead -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                else -> categoryColor.copy(alpha = 0.35f)
            }
        )
    ) {
        Row(modifier = Modifier.height(intrinsicSize = androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            // Left Category Indicator Bar
            Box(
                modifier = Modifier
                    .width(4.5.dp)
                    .fillMaxHeight()
                    .background(categoryColor, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // [LEFT ICON] Notification category / type badge icon
                Surface(
                    shape = CircleShape,
                    color = categoryColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, categoryColor.copy(alpha = 0.3f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (displayIconType) {
                                EntryTemplateFormatter.LeftIconType.COMMENT -> Icons.Default.ChatBubble
                                EntryTemplateFormatter.LeftIconType.LIKE -> Icons.Default.Favorite
                                EntryTemplateFormatter.LeftIconType.FOLLOW -> Icons.Default.PersonAdd
                                EntryTemplateFormatter.LeftIconType.SHARE -> Icons.Default.Share
                                EntryTemplateFormatter.LeftIconType.WARNING -> Icons.Default.Warning
                                EntryTemplateFormatter.LeftIconType.AI_SYSTEM -> Icons.Default.SmartToy
                            },
                            contentDescription = notification.category,
                            tint = categoryColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // [CENTER] Notification Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .animateContentSize()
                ) {
                    // Header Row: Author Name + Category Chip + Time + Blocked
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val actorName = notification.actor.ifBlank {
                            if (actionText.contains("님이")) actionText.substringBefore("님이")
                            else notification.title
                        }
                        Text(
                            text = actorName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        // Category Pill Tag
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = categoryColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = when (displayCategory) {
                                    "COMMENT" -> "댓글"
                                    "REPLY" -> "답글"
                                    "LIKE" -> "좋아요"
                                    "FOLLOW" -> "팔로우"
                                    "REMAKE" -> "리메이크"
                                    "SHARE" -> if (notification.template == "template_5" || notification.template == "template_6") "리메이크" else "공유"
                                    "WARNING" -> "제재/주의"
                                    else -> "알림"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = categoryColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }

                        if (notification.isBlocked) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Block,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "차단됨",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "· ${formatRelativeTime(notification.createdAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Notification Action Description ("~님이 작품에 댓글을 작성했어요")
                    Text(
                        text = highlightedActionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (commentContent != null) 2 else (if (expanded) Int.MAX_VALUE else 2),
                        overflow = TextOverflow.Ellipsis
                    )

                    // Actual Comment in SNS-style Quote Bubble (Bold & Distinct Color)
                    if (!commentContent.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, categoryColor.copy(alpha = 0.22f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(categoryColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = commentContent,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Sticker Thumbnail Display
                    val isSafeSticker = remember(notification.stickerUrl) {
                        SecurityUtil.isValidImageUrl(notification.stickerUrl)
                    }
                    if (isSafeSticker && !notification.stickerUrl.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, categoryColor.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .clickable { onMediaClick?.invoke(notification.stickerUrl, "스티커") }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                SubcomposeAsyncImage(
                                    model = notification.stickerUrl,
                                    contentDescription = "스티커",
                                    modifier = Modifier.size(68.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }

                    // Attached User Image Display
                    val isSafeImage = remember(notification.imageUrl) {
                        SecurityUtil.isValidImageUrl(notification.imageUrl)
                    }
                    if (isSafeImage && !notification.imageUrl.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onMediaClick?.invoke(notification.imageUrl, "첨부 이미지") }
                        ) {
                            SubcomposeAsyncImage(
                                model = notification.imageUrl,
                                contentDescription = "첨부 이미지",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }

                    // Safe Navigation Button (External Link confirmation prevented by explicit button)
                    if (canNavigate) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                            modifier = Modifier.clickable {
                                notification.deepLink?.let { onRequestNavigate?.invoke(it) }
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "바로가기",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (hasLongBody) {
                        Text(
                            text = if (expanded) "접기" else "더보기",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { expanded = !expanded }
                                .padding(top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // [RIGHT SECTION]
                // Actor's Profile Avatar (우측 Big Icon) & Selection/Action Controls
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSelect?.invoke() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    // 작품 알림은 실제 작품 썸네일, 나머지는 실제 사용자 프로필을 표시한다.
                    Surface(
                        shape = rightImageShape,
                        border = BorderStroke(1.2.dp, categoryColor.copy(alpha = 0.35f)),
                        modifier = Modifier.size(if (isShowingProjectThumbnail) 48.dp else 38.dp)
                    ) {
                        if (rightImageUrl != null) {
                            SubcomposeAsyncImage(
                                model = rightImageUrl,
                                contentDescription = if (isShowingProjectThumbnail) "작품 썸네일" else notification.actor,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(rightImageShape),
                                contentScale = ContentScale.Crop,
                                error = {
                                    FallbackRightIcon(isLikeWithoutProjectOrAvatar, templateInfo, categoryColor)
                                },
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(categoryColor.copy(alpha = 0.08f))
                                    )
                                }
                            )
                        } else {
                            FallbackRightIcon(isLikeWithoutProjectOrAvatar, templateInfo, categoryColor)
                        }
                    }

                    if (!isSelectionMode && showActions) {
                        Spacer(modifier = Modifier.height(4.dp))
                        // Star toggle button
                        IconButton(onClick = onStarToggle, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = if (notification.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "즐겨찾기",
                                tint = if (notification.isStarred) CategoryColors.Like else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // More options dropdown
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "더보기",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                if (canNavigate) {
                                    DropdownMenuItem(
                                        text = { Text("게시글 바로가기") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            notification.deepLink?.let { onRequestNavigate?.invoke(it) }
                                        }
                                    )
                                }

                                if (notification.isBlocked) {
                                    DropdownMenuItem(
                                        text = { Text("차단 해제") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.LockOpen,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onUnblockUser?.invoke(notification)
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("이 사용자 차단") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Block,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onRequestBlockUser?.invoke(notification)
                                        }
                                    )
                                }

                                if (onDeleteNotification != null) {
                                    DropdownMenuItem(
                                        text = { Text("이 알림 삭제", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onDeleteNotification.invoke()
                                        }
                                    )
                                }
                            }
                        }

                        if (hasLongBody) {
                            IconButton(
                                onClick = { expanded = !expanded },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (expanded) "접기" else "펼치기",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FallbackRightIcon(
    isLikeWithoutProjectOrAvatar: Boolean,
    templateInfo: EntryTemplateFormatter.FormattedMessage,
    categoryColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(categoryColor.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        val (icon, tint) = when {
            isLikeWithoutProjectOrAvatar -> Pair(Icons.Default.Favorite, CategoryColors.Like)
            templateInfo.iconType == EntryTemplateFormatter.LeftIconType.WARNING -> Pair(Icons.Default.Warning, CategoryColors.Warning)
            else -> Pair(Icons.Default.Person, categoryColor)
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun parseNotificationBody(body: String): Pair<String, String?> {
    val sanitized = SecurityUtil.sanitizeDisplayText(body, maxLength = 1000)
    val trimmed = sanitized.trim()
    if (trimmed.contains('\n')) {
        val parts = trimmed.split('\n', limit = 2)
        val action = parts[0].trim()
        val rawComment = parts[1].trim()
        val cleanedComment = rawComment.removeSurrounding("\"").removeSurrounding("“", "”").trim()
        if (cleanedComment.isNotBlank()) {
            return Pair(action, cleanedComment)
        }
        return Pair(action, null)
    }
    val quoteStart = trimmed.indexOfAny(charArrayOf('"', '“'))
    if (quoteStart > 0 && (trimmed.endsWith('"') || trimmed.endsWith('”'))) {
        val action = trimmed.substring(0, quoteStart).trim().removeSuffix(":").trim()
        val rawComment = trimmed.substring(quoteStart).trim()
        val cleanedComment = rawComment.removeSurrounding("\"").removeSurrounding("“", "”").trim()
        if (cleanedComment.isNotBlank()) {
            return Pair(action, cleanedComment)
        }
    }
    return Pair(trimmed, null)
}

private fun formatRelativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "방금 전"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}분 전"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}시간 전"
        diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}일 전"
        else -> SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(millis))
    }
}

private fun isNonInteractiveDeepLink(url: String?): Boolean {
    val clean = url?.trim().orEmpty()
    return clean.contains("space.playentry.org", ignoreCase = true)
}

private fun buildHighlightedActionText(
    text: String,
    actor: String?,
    boldColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        val boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = boldColor)

        // Bold actor name if found
        if (!actor.isNullOrBlank() && actor != "누군가") {
            var startIndex = 0
            while (startIndex < text.length) {
                val idx = text.indexOf(actor, startIndex)
                if (idx == -1) break
                addStyle(boldStyle, idx, idx + actor.length)
                startIndex = idx + actor.length
            }
        }

        // Bold project title in single quotes '...'
        val quoteRegex = Regex("'([^']+)'")
        quoteRegex.findAll(text).forEach { matchResult ->
            val range = matchResult.range
            addStyle(boldStyle, range.first, range.last + 1)
        }
    }
}
