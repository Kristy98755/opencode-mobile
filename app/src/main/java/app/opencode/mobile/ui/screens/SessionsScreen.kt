package app.opencode.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.opencode.mobile.AppViewModel
import app.opencode.mobile.net.RelayState
import app.opencode.mobile.net.Session
import app.opencode.mobile.ui.theme.Border
import app.opencode.mobile.ui.theme.Surface2
import app.opencode.mobile.ui.theme.Surface1
import app.opencode.mobile.ui.theme.TextSecondary
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

    var menuOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("OpenCode", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    StatusDot(agentOnline = agentOnline, relayState = relayState)
                    IconButton(onClick = { vm.refreshSessions() }) {
                        Icon(Icons.Outlined.Sync, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("New session") },
                            leadingIcon = { Icon(Icons.Filled.Add, null) },
                            onClick = {
                                menuOpen = false
                                vm.newSession { onOpenChat(it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Files") },
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                            onClick = {
                                menuOpen = false
                                val root = sessions.firstOrNull()?.directory ?: ""
                                if (root.isNotEmpty()) onOpenFiles(root) else vm.toast("No project directory known")
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
                onClick = { vm.newSession { onOpenChat(it) } },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New session")
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
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onClick = { onOpenChat(session) },
                            onDelete = { deleteTarget = session },
                        )
                    }
                    item { Spacer(Modifier.height(64.dp)) }
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
        }
    }
}

@Composable
private fun StatusDot(agentOnline: Boolean, relayState: RelayState) {
    val color = when {
        relayState is RelayState.Ready && agentOnline -> app.opencode.mobile.ui.theme.Green
        relayState is RelayState.Ready -> app.opencode.mobile.ui.theme.Yellow
        else -> app.opencode.mobile.ui.theme.Red
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
private fun SessionCard(session: Session, onClick: () -> Unit, onDelete: () -> Unit) {
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
                    session.directory?.let { add(java.io.File(it).name) }
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
