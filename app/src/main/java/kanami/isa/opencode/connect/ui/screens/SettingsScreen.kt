package kanami.isa.opencode.connect.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kanami.isa.opencode.connect.AppViewModel
import kanami.isa.opencode.connect.ui.theme.Border
import kanami.isa.opencode.connect.ui.theme.Surface1
import kanami.isa.opencode.connect.ui.theme.Surface2
import kanami.isa.opencode.connect.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val agents by vm.agents.collectAsStateWithLifecycle()
    val selectedAgent by vm.selectedAgent.collectAsStateWithLifecycle()
    val relayState by vm.relayState.collectAsStateWithLifecycle()
    val agentOnline by vm.agentOnline.collectAsStateWithLifecycle()

    var agentSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionTitle("Agent")
            SettingCard(
                title = selectedAgent.ifEmpty { "Default agent" },
                subtitle = agents.find { it.name == selectedAgent }?.description ?: "used for new sessions",
                onClick = { agentSheet = true },
            )

            Spacer(Modifier.height(12.dp))
            SectionTitle("Connection")
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface1),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Relay", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            when {
                                relayState is kanami.isa.opencode.connect.net.RelayState.Ready -> "connected"
                                relayState is kanami.isa.opencode.connect.net.RelayState.Connecting -> "connecting…"
                                else -> "offline"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.SmartToy,
                            contentDescription = null,
                            tint = if (agentOnline) MaterialTheme.colorScheme.primary else TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("PC bridge", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (agentOnline) "online" else "offline",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (agentOnline) MaterialTheme.colorScheme.primary else TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        vm.disconnect()
                        onBack()
                        vm.toast("Disconnected — reconnect on the Connect screen")
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Disconnect")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("About")
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface1),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("OpenCode Connect", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Unofficial remote client for OpenCode.\nConnects through a self-hosted relay to the opencode server on your PC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (agentSheet) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { agentSheet = false },
            title = { Text("Choose agent") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val options = listOf("") + agents.map { it.name }
                    options.forEach { name ->
                        val sel = name == selectedAgent
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectAgent(name)
                                    agentSheet = false
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = if (sel) MaterialTheme.colorScheme.primary else Surface2,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    if (name.isEmpty()) "Default" else name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                agents.find { it.name == name }?.description?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { agentSheet = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SettingCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("Change", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
