package kanami.isa.opencode.connect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kanami.isa.opencode.connect.PermissionRequest
import kanami.isa.opencode.connect.QuestionRequest
import kanami.isa.opencode.connect.ui.theme.Surface2
import kanami.isa.opencode.connect.ui.theme.TextSecondary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    val qCount = req.questions.size

    fun isMulti(i: Int): Boolean {
        val q = req.questions[i]
        return (q["multiple"] as? JsonPrimitive)?.booleanOrNull == true ||
            (q["multi"] as? JsonPrimitive)?.booleanOrNull == true
    }

    var tab by remember(req.id) { mutableStateOf(0) }
    var selected by remember(req.id) { mutableStateOf(List(qCount) { setOf<String>() }) }
    var customTexts by remember(req.id) { mutableStateOf(List(qCount) { "" }) }
    var customOns by remember(req.id) { mutableStateOf(List(qCount) { false }) }

    fun answerFor(i: Int): List<String> {
        val text = customTexts[i].trim()
        return if (!isMulti(i)) {
            if (customOns[i]) {
                if (text.isNotEmpty()) listOf(text) else emptyList()
            } else {
                selected[i].toList()
            }
        } else {
            selected[i].toList() + (if (customOns[i] && text.isNotEmpty()) listOf(text) else emptyList())
        }
    }

    val q = req.questions[tab]
    val title = (q["title"] as? JsonPrimitive)?.contentOrNull
        ?: (q["question"] as? JsonPrimitive)?.contentOrNull
        ?: "Question"
    val description = (q["description"] as? JsonPrimitive)?.contentOrNull
    val header = (q["header"] as? JsonPrimitive)?.contentOrNull
    val options = (q["options"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.let { it to null }
                is kotlinx.serialization.json.JsonObject ->
                    (el["label"] as? JsonPrimitive)?.contentOrNull?.let {
                        it to (el["description"] as? JsonPrimitive)?.contentOrNull
                    }
                else -> null
            }
        }
        ?: emptyList()
    val multi = isMulti(tab)

    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(Icons.Outlined.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Column {
                if (header != null) {
                    Text(header, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
                if (qCount > 1) {
                    Text(
                        "Question ${tab + 1} of $qCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
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
                options.forEach { (opt, optDesc) ->
                    val isSel = selected[tab].contains(opt)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = selected.toMutableList().also { list ->
                                    list[tab] = if (multi) {
                                        if (isSel) selected[tab] - opt else selected[tab] + opt
                                    } else {
                                        setOf(opt)
                                    }
                                }
                                if (!multi) {
                                    customOns = customOns.toMutableList().also { it[tab] = false }
                                }
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            if (isSel) "●" else "○",
                            color = if (isSel) MaterialTheme.colorScheme.primary else TextSecondary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column {
                            Text(opt, style = MaterialTheme.typography.bodyMedium)
                            if (!optDesc.isNullOrBlank()) {
                                Text(optDesc, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val customOn = customOns[tab]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (customOn) "●" else "○",
                        color = if (customOn) MaterialTheme.colorScheme.primary else TextSecondary,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable {
                                customOns = customOns.toMutableList().also { it[tab] = !customOn }
                            },
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!customOn) {
                                    customOns = customOns.toMutableList().also { it[tab] = true }
                                }
                            },
                    ) {
                        Text("Type your own answer", style = MaterialTheme.typography.bodyMedium)
                        if (!customOn) {
                            Text(
                                customTexts[tab].ifBlank { "Type your answer..." },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
                if (customOn) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customTexts[tab],
                        onValueChange = { v ->
                            customTexts = customTexts.toMutableList().also { it[tab] = v }
                        },
                        placeholder = { Text("Type your answer...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (tab < qCount - 1) {
                        tab++
                    } else {
                        onAnswer(List(qCount) { answerFor(it) })
                    }
                },
            ) { Text(if (tab < qCount - 1) "Next" else "Submit") }
        },
        dismissButton = {
            Row {
                if (tab > 0) {
                    TextButton(onClick = { tab-- }) { Text("Back") }
                }
                TextButton(onClick = onReject) { Text("Dismiss") }
            }
        },
    )
}
