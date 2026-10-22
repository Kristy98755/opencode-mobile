package kanami.isa.opencode.connect.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kanami.isa.opencode.connect.AppViewModel
import kanami.isa.opencode.connect.net.FileAttachment
import kanami.isa.opencode.connect.net.ProvidersResponse
import kanami.isa.opencode.connect.ui.components.ChatMessage
import kanami.isa.opencode.connect.ui.components.ErrorCard
import kanami.isa.opencode.connect.ui.components.ProcessingBubble
import kanami.isa.opencode.connect.ui.theme.Border
import kanami.isa.opencode.connect.ui.theme.Green
import kanami.isa.opencode.connect.ui.theme.Surface1
import kanami.isa.opencode.connect.ui.theme.Surface2
import kanami.isa.opencode.connect.ui.theme.TextSecondary
import kanami.isa.opencode.connect.ui.theme.Yellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: AppViewModel,
    sessionId: String,
    onBack: () -> Unit,
    onOpenFiles: (String) -> Unit,
) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val agentOnline by vm.agentOnline.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val question by vm.question.collectAsStateWithLifecycle()
    val permission by vm.permission.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val atBottom = !listState.canScrollForward
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    // While a question/permission dialog for THIS session is open the turn is
    // waiting on the user, not on opencode — the bubble would otherwise half-stick
    // out from behind the dialog.
    val turnBlockedOnUser =
        (question != null && question?.sessionID == chat.sessionId) ||
            (permission != null && permission?.sessionID == chat.sessionId)
    val showProcessing = !turnBlockedOnUser &&
        chat.messages.lastOrNull()?.info?.role == "user" &&
        (chat.awaitingUpstream || chat.busy)
    // messages + optional processing bubble + optional error card + trailing spacer
    val bottomIndex = if (chat.messages.isEmpty()) 0
    else chat.messages.size - 1 +
        (if (showProcessing) 1 else 0) +
        (if (chat.error != null) 1 else 0) + 1

    // `atBottom` is read during composition, i.e. from the layout of the *previous*
    // frame: exactly the "was I at the end before this update?" answer. The old
    // `!canScrollForward` check ran after the new item landed, so it never fired
    // (long cached chats opened at the top and never followed the stream).
    // `scrollOnSend` (armed by the composer) wins over `atBottom`: sending always
    // brings the user's own message into view, even from an old scroll position.
    var scrollOnSend by remember { mutableStateOf(false) }
    LaunchedEffect(chat.messages, bottomIndex) {
        if (chat.messages.isNotEmpty()) {
            if (scrollOnSend) {
                scrollOnSend = false
                listState.animateScrollToItem(bottomIndex)
            } else if (atBottom) {
                listState.scrollToItem(bottomIndex)
            }
        }
    }

    var prependAnchor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .collect { (idx, scrolling) ->
                if (scrolling && idx == 0) {
                    prependAnchor = chat.messages.firstOrNull()?.info?.id
                    vm.loadMoreMessages()
                }
            }
    }
    LaunchedEffect(chat.messages) {
        val anchor = prependAnchor
        if (anchor != null) {
            val idx = chat.messages.indexOfFirst { it.info.id == anchor }
            if (idx >= 0) {
                listState.scrollToItem(idx)
            }
            prependAnchor = null
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
                        val dir = vm.projectDirectory()
                        if (dir.isNotEmpty()) onOpenFiles(dir)
                        else vm.toast("No working directory known for this session")
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
                                val dir = vm.projectDirectory()
                                if (dir.isNotEmpty()) onOpenFiles(dir)
                                else vm.toast("No working directory known for this session")
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
                draft = chat.draft,
                onDraftChange = { vm.setDraft(it) },
                busy = chat.busy,
                attachments = attachments,
                onAttach = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemoveAttachment = { attachments.remove(it) },
                onSend = {
                    scrollOnSend = true
                    val text = chat.draft
                    val uris = attachments.toList()
                    attachments.clear()
                    if (uris.isEmpty()) {
                        vm.sendPrompt(text, emptyList())
                    } else {
                        scope.launch {
                            val files = withContext(Dispatchers.Default) {
                                uris.mapNotNull { uriToAttachment(context, it) }
                            }
                            vm.sendPrompt(text, files)
                        }
                    }
                },
                onStop = { vm.abortPrompt() },
                modelLabel = chat.model?.id?.ifEmpty { null } ?: "default",
                providers = providers,
                selectedProvider = chat.model?.providerID ?: "",
                selectedModel = chat.model?.id ?: "",
                onSelectModel = { p, m -> vm.selectSessionModel(p, m) },
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
                    val emptyErr = chat.error
                    if (emptyErr != null) {
                        Spacer(Modifier.height(14.dp))
                        ErrorCard(text = emptyErr, onDismiss = { vm.clearChatError() })
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(chat.messages, key = { it.info.id }) { msg ->
                        val awaiting = msg.info.id == chat.pendingUserMsgId && chat.awaitingUpstream
                        ChatMessage(
                            msg,
                            Modifier.animateItem(),
                            shimmer = awaiting,
                            onExpand = { vm.loadMoreMessages() },
                        )
                    }
                    val lastRole = chat.messages.lastOrNull()?.info?.role
                    if (showProcessing && lastRole == "user") {
                        item(key = "processing") {
                            ProcessingBubble(Modifier.animateItem())
                        }
                    }
                    val err = chat.error
                    if (err != null) {
                        item(key = "chat-error") {
                            ErrorCard(text = err, onDismiss = { vm.clearChatError() })
                        }
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }

            if (chat.messages.isNotEmpty() && !atBottom) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(bottomIndex)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp)
                        .size(38.dp),
                    containerColor = Surface2,
                    contentColor = TextSecondary,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun SessionStub(id: String) = kanami.isa.opencode.connect.net.Session(id = id)

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
    modelLabel: String,
    providers: ProvidersResponse?,
    selectedProvider: String,
    selectedModel: String,
    onSelectModel: (String, String) -> Unit,
) {
    var modelMenu by remember { mutableStateOf(false) }
    var draftValue by remember { mutableStateOf(TextFieldValue(draft)) }
    LaunchedEffect(draft) {
        if (draftValue.text != draft) {
            draftValue = TextFieldValue(draft, TextRange(draft.length))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Surface2, RoundedCornerShape(8.dp))
                        .clickable { modelMenu = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Choose model",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(
                    expanded = modelMenu,
                    onDismissRequest = { modelMenu = false },
                    modifier = Modifier.heightIn(max = 340.dp),
                ) {
                    // /provider carries 232 providers / ~9k models. Rendering them
                    // flat froze the main thread, and a LazyColumn inside this popup
                    // crashed the app. Collapsible sections (one open at a time) keep
                    // the composed row count in the dozens.
                    val connected = providers?.connected.orEmpty()
                    val providerList = providers?.all.orEmpty()
                        .filter { p -> p.models.isNotEmpty() }
                        .sortedByDescending { it.id in connected }
                    var pinnedOpen by remember { mutableStateOf<String?>(null) }
                    val openId = pinnedOpen ?: selectedProvider
                    if (providerList.isEmpty()) {
                        Text(
                            "No providers",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(14.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            providerList.forEach { provider ->
                                val isOpen = provider.id == openId
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pinnedOpen = if (isOpen) "__closed__" else provider.id
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    if (provider.id in connected) {
                                        Icon(
                                            Icons.Outlined.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        provider.name ?: provider.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (isOpen) "–" else provider.models.size.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = TextSecondary,
                                    )
                                }
                                if (isOpen) {
                                    provider.models.values.sortedBy { it.name ?: it.id }.forEach { model ->
                                        val sel = provider.id == selectedProvider && model.id == selectedModel
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onSelectModel(provider.id, model.id)
                                                    modelMenu = false
                                                }
                                                .padding(start = 30.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.CheckCircle,
                                                contentDescription = null,
                                                tint = if (sel) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                model.name ?: model.id,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (sel) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
                value = draftValue,
                onValueChange = {
                    draftValue = it
                    onDraftChange(it.text)
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask OpenCode…", color = TextSecondary) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
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
