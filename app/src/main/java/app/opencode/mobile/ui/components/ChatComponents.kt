package app.opencode.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opencode.mobile.net.MessageWithParts
import app.opencode.mobile.net.Part
import app.opencode.mobile.net.str
import app.opencode.mobile.ui.theme.Border
import app.opencode.mobile.ui.theme.Surface1
import app.opencode.mobile.ui.theme.TextSecondary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val Green = Color(0xFF3FB950)
private val RedSoft = Color(0xFFF85149)
private val YellowSoft = Color(0xFFD29922)

@Composable
fun ChatMessage(message: MessageWithParts) {
    val isUser = message.info.role == "user"
    Column(modifier = Modifier.fillMaxWidth()) {
        message.parts.forEach { part ->
            when (part.type) {
                "text" -> {
                    if (!part.text.isNullOrBlank()) {
                        if (isUser) UserText(part.text) else AssistantText(part.text)
                        Spacer(Modifier.height(6.dp))
                    }
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
                    Chip(
                        icon = Icons.Outlined.AttachFile,
                        label = part.filename ?: part.url ?: "file",
                    )
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
                else -> {}
            }
        }
    }
}

@Composable
private fun UserText(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .background(Surface1, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
    var expanded by remember { mutableStateOf(false) }
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
