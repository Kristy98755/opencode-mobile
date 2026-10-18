package app.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opencode.mobile.PermissionRequest
import app.opencode.mobile.QuestionRequest
import app.opencode.mobile.ui.theme.Surface2
import app.opencode.mobile.ui.theme.TextSecondary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun PermissionDialog(req: PermissionRequest, onReply: (String, String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.GppBad, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Permission required", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    req.permission.ifEmpty { "tool access" },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
                if (req.patterns.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    req.patterns.forEach { p ->
                        Text(
                            p,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Surface2, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
                val meta = req.metadata
                if (meta != null && meta.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    meta.forEach { (k, v) ->
                        val value = (v as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        if (value.length > 120) return@forEach
                        Text(
                            "$k: $value",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (req.always.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Also match: ${req.always.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                OutlinedButton(onClick = { onReply("reject", null) }) { Text("Deny") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { onReply("once", null) }) { Text("Allow") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onReply("always", null) }) { Text("Always") }
        },
    )
}

@Composable
fun QuestionDialog(req: QuestionRequest, onAnswer: (List<List<String>>) -> Unit, onReject: () -> Unit) {
    if (req.questions.isEmpty()) { onReject(); return }
    val q = req.questions.first()
    val title = (q["title"] as? JsonPrimitive)?.contentOrNull
        ?: (q["question"] as? JsonPrimitive)?.contentOrNull
        ?: "Question"
    val description = (q["description"] as? JsonPrimitive)?.contentOrNull
    val header = (q["header"] as? JsonPrimitive)?.contentOrNull
    val options = (q["options"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: emptyList()
    val multi = (q["multi"] as? JsonPrimitive)?.contentOrNull?.toBoolean() == true

    var selected by remember(req.id) { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(Icons.Outlined.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Column {
                if (header != null) {
                    Text(header, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
                Text(title, fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (!description.isNullOrBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                }
                options.forEach { opt ->
                    val isSel = selected.contains(opt)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (multi) {
                                    if (isSel) selected - opt else selected + opt
                                } else {
                                    setOf(opt)
                                }
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            if (isSel) "●" else "○",
                            color = if (isSel) MaterialTheme.colorScheme.primary else TextSecondary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(opt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val answers = if (selected.isEmpty()) emptyList() else listOf(selected.toList())
                    onAnswer(answers)
                },
                enabled = selected.isNotEmpty(),
            ) { Text("Answer") }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("Skip") }
        },
    )
}
