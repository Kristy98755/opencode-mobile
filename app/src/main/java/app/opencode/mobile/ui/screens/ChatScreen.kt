package app.opencode.mobile.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.opencode.mobile.AppViewModel
import app.opencode.mobile.net.FileAttachment
import app.opencode.mobile.ui.components.ChatMessage
import app.opencode.mobile.ui.components.PermissionDialog
import app.opencode.mobile.ui.components.QuestionDialog
import app.opencode.mobile.ui.theme.Border
import app.opencode.mobile.ui.theme.Green
import app.opencode.mobile.ui.theme.Surface1
import app.opencode.mobile.ui.theme.Surface2
import app.opencode.mobile.ui.theme.TextSecondary
import app.opencode.mobile.ui.theme.Yellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: AppViewModel,
    sessionId: String,
    onBack: () -> Unit,
    onOpenFiles: (String) -> Unit,
) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val permission by vm.permission.collectAsStateWithLifecycle()
    val question by vm.question.collectAsStateWithLifecycle()
    val agentOnline by vm.agentOnline.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val attachments = remember { mutableStateListOf<String>() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris: List<Uri> ->
        attachments.addAll(uris.map { it.toString() })
    }

    LaunchedEffect(sessionId) {
        val session = vm.sessions.value.find { it.id == sessionId } ?: SessionStub(sessionId)
        vm.openChat(session)
    }

    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) {
            listState.animateScrollToItem(chat.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            chat.title ?: "Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        when {
                                            !agentOnline -> Color(0xFFF85149)
                                            chat.busy -> Yellow
                                            else -> Green
                                        },
                                        CircleShape,
                                    ),
                            )
                            Spacer(Modifier.width(5.dp))
                            val statusText = when {
                                !agentOnline -> "PC offline"
                                chat.busy -> "working..."
                                else -> "ready"
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.closeChat()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onOpenFiles(vm.projectDirectory())
                    }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Files")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Files") },
                            onClick = {
                                menuOpen = false
                                onOpenFiles(vm.projectDirectory())
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear messages (local)") },
                            onClick = {
                                menuOpen = false
                                vm.toast("Open sessions are kept on the server")
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                busy = chat.busy,
                attachments = attachments,
                onAttach = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemoveAttachment = { attachments.remove(it) },
                onSend = {
                    val text = draft
                    val files = attachments.toList().mapNotNull { uriToAttachment(context, it) }
                    draft = ""
                    attachments.clear()
                    vm.sendPrompt(text, files)
                },
                onStop = { vm.abortPrompt() },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (chat.loading && chat.messages.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (chat.messages.isEmpty() && !chat.loading) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Empty session", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Send a message to start working\nwith your desktop OpenCode",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(chat.messages, key = { it.info.id }) { msg ->
                        ChatMessage(msg)
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }

            chat.error?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }

    permission?.let { req ->
        PermissionDialog(
            req = req,
            onReply = { reply, msg -> vm.answerPermission(reply, msg) },
            onDismiss = { vm.answerPermission("reject", null) },
        )
    }
    question?.let { req ->
        QuestionDialog(
            req = req,
            onAnswer = { vm.answerQuestion(it) },
            onReject = { vm.rejectQuestion() },
        )
    }
}

private fun SessionStub(id: String) = app.opencode.mobile.net.Session(id = id)

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    attachments: List<String>,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { uri ->
                    val name = uri.substringAfterLast('/')
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Surface2, RoundedCornerShape(6.dp))
                            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
                        IconButton(onClick = { onRemoveAttachment(uri) }, modifier = Modifier.size(20.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove",
                                tint = TextSecondary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask OpenCode…", color = TextSecondary) },
                maxLines = 5,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface1,
                    unfocusedContainerColor = Surface1,
                    disabledContainerColor = Surface1,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onAttach,
                enabled = !busy,
                modifier = Modifier
                    .size(44.dp)
                    .background(Surface2, CircleShape),
            ) {
                Icon(Icons.Outlined.Image, contentDescription = "Attach", tint = TextSecondary)
            }
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = { if (busy) onStop() else onSend() },
                enabled = busy || draft.isNotBlank() || attachments.isNotEmpty(),
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    if (busy) Icons.Filled.Stop else Icons.AutoMirrored.Outlined.Send,
                    contentDescription = if (busy) "Stop" else "Send",
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun uriToAttachment(context: android.content.Context, uri: String): FileAttachment? {
    return try {
        val parsed = Uri.parse(uri)
        val resolver = context.contentResolver
        val mime = resolver.getType(parsed) ?: "image/png"
        val name = resolver.query(parsed, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: parsed.lastPathSegment ?: "attachment"
        FileAttachment(
            mime = mime,
            name = name,
            url = "data:${mime};base64," + encodeBase64(resolver, parsed),
        )
    } catch (e: Exception) {
        null
    }
}

private fun encodeBase64(resolver: android.content.ContentResolver, uri: Uri): String {
    resolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > 8L * 1024 * 1024) return ""
            out.write(buf, 0, n)
        }
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }
    return ""
}
