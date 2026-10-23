package kanami.isa.opencode.connect.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kanami.isa.opencode.connect.AppViewModel
import kanami.isa.opencode.connect.pcParent
import kanami.isa.opencode.connect.net.RelayState
import kanami.isa.opencode.connect.net.Session
import kanami.isa.opencode.connect.ui.theme.Border
import kanami.isa.opencode.connect.ui.theme.Surface2
import kanami.isa.opencode.connect.ui.theme.Surface1
import kanami.isa.opencode.connect.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    vm: AppViewModel,
    onOpenChat: (Session) -> Unit,
    onOpenFiles: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val loading by vm.sessionsLoading.collectAsStateWithLifecycle()
    val relayState by vm.relayState.collectAsStateWithLifecycle()
    val agentOnline by vm.agentOnline.collectAsStateWithLifecycle()
    val showProjects by vm.showProjects.collectAsStateWithLifecycle()
    val busySessions by vm.busySessions.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSubagents by rememberSaveable { mutableStateOf(false) }
    var sortAlpha by rememberSaveable { mutableStateOf(false) }
    var newProjectOpen by remember { mutableStateOf(false) }
    var expandedDirs by rememberSaveable { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) { vm.refreshSessions() }

    val base = remember(sessions, showSubagents) {
        sessions.filter { showSubagents || it.parentID == null }
    }
    val visible = remember(base, query) {
        base.filter { matchesQuery(it, query) }
    }
    val rows = remember(base, visible, sortAlpha, showProjects, query) {
        if (showProjects) {
            buildProjectRows(base, query, sortAlpha)
        } else {
            val sorted = if (sortAlpha) visible.sortedWith(alphaSessionOrder)
            else visible.sortedByDescending { sessionStamp(it) }
            sorted.map { SessionRow.Single(it, sessionStamp(it)) }
        }
    }
    val groupDirs = remember(rows) {
        rows.filterIsInstance<SessionRow.Group>().map { it.dir }
    }
    LaunchedEffect(showProjects, groupDirs) {
        if (groupDirs.isNotEmpty() && expandedDirs.isEmpty()) {
            expandedDirs = groupDirs.toSet()
        }
    }
    val knownDirs = remember(sessions) {
        sessions.mapNotNull { it.directory?.takeIf { d -> d.isNotBlank() } }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("OpenCode", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    StatusDot(agentOnline = agentOnline, relayState = relayState)
                    IconButton(onClick = { vm.refreshSessions(force = true) }) {
                        Icon(Icons.Outlined.Sync, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (showProjects) "Show chats" else "Show projects") },
                            onClick = {
                                vm.setShowProjects(!showProjects)
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("New project…") },
                            leadingIcon = { Icon(Icons.Filled.Add, null) },
                            onClick = {
                                menuOpen = false
                                newProjectOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (sortAlpha) "Sort: by newest" else "Sort: alphabetically") },
                            leadingIcon = {
                                Icon(
                                    if (sortAlpha) Icons.Outlined.Schedule else Icons.Outlined.SortByAlpha,
                                    null,
                                )
                            },
                            onClick = {
                                sortAlpha = !sortAlpha
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showSubagents) "Hide subagent sessions" else "Show subagent sessions") },
                            leadingIcon = {
                                Icon(
                                    if (showSubagents) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                    null,
                                )
                            },
                            onClick = {
                                showSubagents = !showSubagents
                                menuOpen = false
                            },
                        )
                        if (groupDirs.isNotEmpty()) {
                            val allExpanded = groupDirs.all { it in expandedDirs }
                            DropdownMenuItem(
                                text = { Text(if (allExpanded) "Collapse all groups" else "Expand all groups") },
                                leadingIcon = {
                                    Icon(
                                        if (allExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                                        null,
                                    )
                                },
                                onClick = {
                                    expandedDirs = if (allExpanded) emptySet() else groupDirs.toSet()
                                    menuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Files") },
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                            onClick = {
                                menuOpen = false
                                onOpenFiles("~")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                            onClick = { menuOpen = false; onDisconnect() },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { newProjectOpen = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New project")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading && sessions.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No sessions yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to start a new conversation\nwith your desktop OpenCode",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("Search sessions") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                    )
                    if (rows.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "No sessions match",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (query.isNotEmpty()) "Try another search query"
                                else "Enable \"Show subagent sessions\" in the menu",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(rows, key = { it.key }) { row ->
                                when (row) {
                                    is SessionRow.Single -> SessionCard(
                                        session = row.session,
                                        busy = busySessions[row.session.id] == true,
                                        onClick = { onOpenChat(row.session) },
                                        onDelete = { deleteTarget = row.session },
                                    )
                                    is SessionRow.Group -> {
                                        val expanded = row.dir in expandedDirs
                                        GroupHeader(group = row, expanded = expanded) {
                                            expandedDirs = if (expanded) expandedDirs - row.dir else expandedDirs + row.dir
                                        }
                                        val shown = if (expanded) row.sessions else emptyList()
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            shown.forEach { session ->
                                                SessionCard(
                                                    session = session,
                                                    busy = busySessions[session.id] == true,
                                                    onClick = { onOpenChat(session) },
                                                    onDelete = { deleteTarget = session },
                                                )
                                            }
                                            if (expanded) {
                                                BranchCard {
                                                    vm.newSession(row.dir) { onOpenChat(it) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(64.dp)) }
                        }
                    }
                }
            }

            deleteTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { deleteTarget = null },
                    title = { Text("Delete session?") },
                    text = { Text(target.title ?: target.slug ?: target.id) },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.deleteSession(target.id)
                            deleteTarget = null
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                    },
                )
            }

            if (newProjectOpen) {
                NewProjectDialog(
                    vm = vm,
                    knownDirs = knownDirs,
                    onDismiss = { newProjectOpen = false },
                    onCreate = { dir ->
                        newProjectOpen = false
                        vm.newSession(dir) { onOpenChat(it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusDot(agentOnline: Boolean, relayState: RelayState) {
    val color = when {
        relayState is RelayState.Ready && agentOnline -> kanami.isa.opencode.connect.ui.theme.Green
        relayState is RelayState.Ready -> kanami.isa.opencode.connect.ui.theme.Yellow
        else -> kanami.isa.opencode.connect.ui.theme.Red
    }
    val desc = when {
        relayState !is RelayState.Ready -> "relay offline"
        agentOnline -> "PC online"
        else -> "PC offline"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(desc, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(session: Session, busy: Boolean = false, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title ?: session.slug ?: session.id,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    session.directory?.takeIf { it.isNotBlank() }?.let { add(folderName(it)) }
                    session.agent?.let { add(it) }
                    session.cost?.takeIf { it > 0 }?.let { add(String.format(Locale.US, "$%.3f", it)) }
                    session.time?.updated?.takeIf { it > 0 }?.let { add(formatTime(it)) }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (busy) {
                // turn running in this session (possibly in the background)
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = kanami.isa.opencode.connect.ui.theme.Yellow,
                )
                Spacer(Modifier.width(8.dp))
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Options", tint = TextSecondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
    }
}

private sealed interface SessionRow {
    val key: String
    val lastUpdate: Long

    data class Single(val session: Session, override val lastUpdate: Long) : SessionRow {
        override val key: String get() = session.id
    }

    data class Group(val dir: String, val sessions: List<Session>, override val lastUpdate: Long) : SessionRow {
        override val key: String get() = "group::$dir"
    }
}

private fun sessionStamp(s: Session): Long = s.time?.updated ?: s.time?.created ?: 0L

private fun matchesQuery(s: Session, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return listOfNotNull(s.title, s.slug, s.directory, s.agent)
        .any { it.lowercase().contains(q) }
}

private fun titleOf(s: Session): String = s.title ?: s.slug ?: s.id

private fun displayName(row: SessionRow): String = when (row) {
    is SessionRow.Single -> titleOf(row.session)
    is SessionRow.Group -> folderName(row.dir).ifEmpty { row.dir }
}

private fun layoutRank(s: String): Int {
    for (ch in s) {
        when {
            ch.isWhitespace() || ch == '"' || ch == '\'' || ch == '«' || ch == '»' -> continue
            ch in '\u0400'..'\u04FF' -> return 0
            ch in 'a'..'z' || ch in 'A'..'Z' -> return 1
            ch.isLetter() -> return 2
            else -> return 3
        }
    }
    return 3
}

private val alphaSessionOrder: Comparator<Session> =
    compareBy({ layoutRank(titleOf(it)) }, { titleOf(it).lowercase() })

private val alphaRowOrder: Comparator<SessionRow> =
    compareBy({ layoutRank(displayName(it)) }, { displayName(it).lowercase() })

private fun buildProjectRows(sessions: List<Session>, query: String, alpha: Boolean): List<SessionRow> {
    val q = query.trim().lowercase()
    val byDir = sessions.groupBy { it.directory?.takeIf { d -> d.isNotBlank() } ?: "" }
    val rows = buildList {
        byDir.forEach { (dir, list) ->
            val dirMatches = q.isEmpty() ||
                folderName(dir).lowercase().contains(q) ||
                dir.lowercase().contains(q)
            val chats = if (dirMatches) list else list.filter { matchesQuery(it, q) }
            if (chats.isEmpty()) return@forEach
            val sorted = if (alpha) chats.sortedWith(alphaSessionOrder)
            else chats.sortedByDescending { sessionStamp(it) }
            add(SessionRow.Group(dir, sorted, sorted.maxOf { sessionStamp(it) }))
        }
    }
    return if (alpha) rows.sortedWith(alphaRowOrder)
    else rows.sortedByDescending { it.lastUpdate }
}

private fun folderName(path: String): String =
    path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifEmpty { path }

@Composable
private fun BranchCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CallSplit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "New branch",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NewProjectDialog(
    vm: AppViewModel,
    knownDirs: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                Text(
                    "A project lives in a folder on your PC.\nPick an existing folder or type a path",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Directory path") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    trailingIcon = {
                        IconButton(onClick = { pickerOpen = true }) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = "Pick directory",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
                if (knownDirs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Existing folders",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 170.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        knownDirs.forEach { dir ->
                            Text(
                                folderName(dir).ifEmpty { dir },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { path = dir }
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = path.isNotBlank(),
                onClick = { onCreate(path.trim()) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (pickerOpen) {
        DirectoryPickerDialog(
            vm = vm,
            startPath = path,
            onDismiss = { pickerOpen = false },
            onPick = { picked ->
                path = picked
                pickerOpen = false
            },
        )
    }
}

/**
 * Folder icon inside the new-project path field opens this: a browser over the
 * PC's real directories (same vm.files state the file screen uses), with
 * "../" + "Level Up", an inline "New Folder" and the Cancel / Choose
 * Directory pair at the bottom.
 */
@Composable
private fun DirectoryPickerDialog(
    vm: AppViewModel,
    startPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val files by vm.files.collectAsStateWithLifecycle()
    val opBusy by vm.fileOpBusy.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.loadDir(startPath.trim().ifEmpty { "~" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose directory") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.loadDir("~") }) { Text("~") }
                    Text(
                        files.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New folder name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { creating = false; newName = "" }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = newName.isNotBlank() && !opBusy,
                            onClick = {
                                vm.createFolder(newName)
                                newName = ""
                                creating = false
                            },
                        ) { Text("Create") }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .fillMaxWidth(),
                    ) {
                        item(key = "__up") {
                            val canUp = pcParent(files.path) != null
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canUp) { vm.goUpDir() }
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                            ) {
                                Text(
                                    "../",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (canUp) MaterialTheme.colorScheme.primary else TextSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "Level Up ⤴",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (canUp) MaterialTheme.colorScheme.primary else TextSecondary,
                                )
                            }
                        }
                        item(key = "__newdir") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !opBusy) { creating = true }
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "New Folder",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        val dirs = files.nodes.filter { it.type == "directory" }
                        when {
                            files.error != null -> item(key = "__error") {
                                Text(
                                    files.error ?: "error",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp, horizontal = 2.dp),
                                )
                            }
                            !files.loading && dirs.isEmpty() -> item(key = "__empty") {
                                Text(
                                    "No subfolders",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp, horizontal = 2.dp),
                                )
                            }
                        }
                        items(dirs, key = { it.absolute.ifEmpty { it.path + it.name } }) { node ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.loadDir(node.absolute.ifEmpty { node.path })
                                    }
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    node.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && files.error == null,
                onClick = { onPick(files.path) },
            ) { Text("Choose Directory") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun GroupHeader(group: SessionRow.Group, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "groupChevron",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = Surface2),
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    folderName(group.dir).ifEmpty { "(no directory)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    group.dir,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${group.sessions.size} chats",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp).rotate(rotation),
            )
        }
    }
}
