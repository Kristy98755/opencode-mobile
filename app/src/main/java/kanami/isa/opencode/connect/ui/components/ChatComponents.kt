package kanami.isa.opencode.connect.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kanami.isa.opencode.connect.net.MessageWithParts
import kanami.isa.opencode.connect.net.OpencodeApi
import kanami.isa.opencode.connect.net.Part
import kotlinx.coroutines.delay
import kanami.isa.opencode.connect.net.str
import kanami.isa.opencode.connect.ui.theme.Border
import kanami.isa.opencode.connect.ui.theme.Surface1
import kanami.isa.opencode.connect.ui.theme.TextSecondary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val Green = Color(0xFF3FB950)
private val RedSoft = Color(0xFFF85149)
private val YellowSoft = Color(0xFFD29922)

@Composable
fun ChatMessage(
    message: MessageWithParts,
    modifier: Modifier = Modifier,
    shimmer: Boolean = false,
    onExpand: (() -> Unit)? = null,
    api: OpencodeApi? = null,
) {
    val isUser = message.info.role == "user"
    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            if (message.truncated) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onExpand?.invoke() }
                        .padding(bottom = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.UnfoldMore,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "fragment shown — tap to load the rest",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
            if (!isUser && message.info.error != null) {
                ErrorCard(
                    text = message.info.error.str("message")
                        ?: message.info.error.str("title")
                        ?: "Error",
                    onDismiss = {},
                    dismissible = false,
                )
                Spacer(Modifier.height(6.dp))
            }
            message.parts.forEach { part ->
                when (part.type) {
                    "text" -> {
                        if (!part.text.isNullOrBlank()) {
                            if (isUser) UserText(part.text, shimmer) else AssistantText(part.text)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    "error" -> {
                        ErrorCard(
                            text = part.text
                                ?: part.state?.str("message")
                                ?: part.state?.str("title")
                                ?: "Unknown error",
                            onDismiss = {},
                            dismissible = false,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    "reasoning" -> {
                        if (!part.text.isNullOrBlank()) ReasoningCard(part.text, part.state)
                        Spacer(Modifier.height(4.dp))
                    }
                    "tool" -> {
                        ToolCard(part)
                        Spacer(Modifier.height(4.dp))
                    }
                    "file" -> {
                        if (hasMedia(part)) {
                            MessageMedia(part, api)
                        } else {
                            Chip(
                                icon = Icons.Outlined.AttachFile,
                                label = part.filename ?: part.url ?: "file",
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    "patch" -> {
                        val files = part.files.orEmpty()
                        if (files.isNotEmpty()) {
                            Chip(
                                icon = Icons.Outlined.Code,
                                label = files.joinToString(", ").ifEmpty { "patch" },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    "agent" -> Chip(icon = Icons.Outlined.Code, label = part.text ?: "agent")
                    "image" -> {
                        MessageMedia(part, api)
                        Spacer(Modifier.height(4.dp))
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun UserText(text: String, shimmer: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "userShimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
    var size by remember { mutableStateOf(IntSize.Zero) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .onSizeChanged { size = it }
                    .background(Surface1, RoundedCornerShape(12.dp))
                    .then(
                        if (shimmer) Modifier.background(shimmerBrush(progress, size), RoundedCornerShape(12.dp))
                        else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/**
 * Shown between "message sent" and the first token of the answer:
 * italic *Processing* with a running ellipsis.
 */
@Composable
fun ProcessingBubble(modifier: Modifier = Modifier) {
    var dots by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(380)
            dots = if (dots >= 3) 1 else dots + 1
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.Start, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Surface1, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Outlined.HourglassBottom,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "Processing" + ".".repeat(dots),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = TextSecondary,
                )
            }
        }
    }
}

private fun shimmerBrush(progress: Float, size: IntSize): Brush {
    if (size.width == 0) {
        return Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val band = size.width * 0.6f
    val x = progress * size.width - band
    return Brush.linearGradient(
        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent),
        start = Offset(x, 0f),
        end = Offset(x + band, size.height.toFloat()),
    )
}

@Composable
fun ErrorCard(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x2EF85149), RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, RedSoft), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = RedSoft,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelMedium, color = RedSoft)
                }
            }
        }
        if (dismissible) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Clear,
                contentDescription = "Dismiss",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp).clickable { onDismiss() },
            )
        }
    }
}

@Composable
private fun AssistantText(text: String) {
    MarkdownText(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun Chip(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Surface1, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReasoningCard(text: String, state: JsonObject?) {
    val status = state?.str("status")
    var expanded by remember { mutableStateOf(status == "running") }
    LaunchedEffect(status) {
        if (status == "running") expanded = true
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x14444C56), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (status == "running") "Thinking…" else "Reasoning",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
fun ToolCard(part: Part) {
    val state = part.state
    val status = state?.str("status") ?: "pending"
    val title = state?.str("title")
    val output = state?.str("output")
    val toolName = part.tool ?: "tool"
    var expanded by remember { mutableStateOf(false) }

    val (icon, color) = when (status) {
        "completed" -> Icons.Outlined.CheckCircle to Green
        "error" -> Icons.Outlined.ErrorOutline to RedSoft
        else -> Icons.Outlined.HourglassBottom to YellowSoft
    }
    val anim by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(180),
        label = "toolExpand",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = status, tint = color, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                toolName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (title != null) {
                Text(
                    " · $title",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(15.dp),
            )
        }

        val inputPreview = inputSummary(state?.get("input") as? JsonObject)
        if (!expanded && inputPreview.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                inputPreview,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (expanded && anim >= 0f) {
            Spacer(Modifier.height(6.dp))
            val input = state?.get("input") as? JsonObject
            if (input != null && input.isNotEmpty()) {
                Text("Input", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Spacer(Modifier.height(2.dp))
                input.forEach { (k, v) ->
                    val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                    Text(
                        "$k: ${value.take(400)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (!output.isNullOrBlank()) {
                Text("Output", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Spacer(Modifier.height(2.dp))
                Text(
                    output.take(4000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x141C2129), RoundedCornerShape(4.dp))
                        .padding(6.dp),
                )
            }
        }
    }
}

private fun inputSummary(input: JsonObject?): String {
    if (input == null || input.isEmpty()) return ""
    return input.entries.take(3).joinToString(" ") { (k, v) ->
        val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
        "$k=${value.take(60)}"
    }
}
