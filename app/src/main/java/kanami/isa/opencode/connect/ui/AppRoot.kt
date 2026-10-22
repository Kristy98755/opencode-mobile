package kanami.isa.opencode.connect.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kanami.isa.opencode.connect.AppViewModel
import kanami.isa.opencode.connect.net.RelayState
import kanami.isa.opencode.connect.ui.components.PermissionDialog
import kanami.isa.opencode.connect.ui.components.QuestionDialog
import kanami.isa.opencode.connect.ui.screens.ChatScreen
import kanami.isa.opencode.connect.ui.screens.ConnectScreen
import kanami.isa.opencode.connect.ui.screens.FilesScreen
import kanami.isa.opencode.connect.ui.screens.SessionsScreen
import kanami.isa.opencode.connect.ui.screens.SettingsScreen

@Composable
fun AppRoot(vm: AppViewModel, relayState: RelayState) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.toast.collect { msg ->
            snackbar.showSnackbar(msg)
        }
    }

    var hasCreds by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { hasCreds = vm.hasSavedCredentials() }

    val openSessionId by vm.openSessionRequest.collectAsStateWithLifecycle()
    LaunchedEffect(hasCreds, relayState, openSessionId) {
        val sid = openSessionId ?: return@LaunchedEffect
        if (hasCreds != true) return@LaunchedEffect
        val from = nav.currentDestination?.route ?: return@LaunchedEffect
        if (from == "connect" && relayState !is RelayState.Ready) return@LaunchedEffect
        vm.clearOpenSessionRequest()
        if (from == "chat/$sid") return@LaunchedEffect
        nav.navigate("chat/$sid") {
            launchSingleTop = true
            if (from == "connect") popUpTo("connect") { inclusive = true }
        }
    }

    LaunchedEffect(relayState) {
        if (relayState is RelayState.Ready && nav.currentDestination?.route == "connect") {
            nav.navigate("sessions") {
                popUpTo("connect") { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    if (hasCreds == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDest = if (hasCreds == true) "sessions" else "connect"

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = nav,
                startDestination = startDest,
            ) {
                composable("connect") {
                    ConnectScreen(vm = vm, onConnected = {
                        nav.navigate("sessions") {
                            popUpTo("connect") { inclusive = true }
                        }
                    })
                }
                composable("sessions") {
                    SessionsScreen(
                        vm = vm,
                        onOpenChat = { session -> nav.navigate("chat/${session.id}") },
                        onOpenFiles = { path ->
                            vm.openFiles(path)
                            nav.navigate("files")
                        },
                        onOpenSettings = { nav.navigate("settings") },
                        onDisconnect = {
                            vm.disconnect()
                            nav.navigate("connect") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                }
                composable(
                    "chat/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { entry ->
                    val sid = entry.arguments?.getString("sessionId") ?: return@composable
                    ChatScreen(
                        vm = vm,
                        sessionId = sid,
                        onBack = { nav.popBackStack() },
                        onOpenFiles = { path ->
                            vm.openFiles(path)
                            nav.navigate("files")
                        },
                    )
                }
                composable("files") {
                    FilesScreen(vm = vm, onBack = { nav.popBackStack() })
                }
                composable("settings") {
                    SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                }
            }

            val loading = relayState is RelayState.Connecting
            if (loading && nav.currentDestination?.route == "connect") {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    // Questions and permissions are shown app-wide: the agent may ask while the
    // user is on the session list, in files or in settings.
    val permission by vm.permission.collectAsStateWithLifecycle()
    val question by vm.question.collectAsStateWithLifecycle()
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
