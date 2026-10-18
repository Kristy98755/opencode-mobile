package app.opencode.mobile.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.opencode.mobile.AppViewModel
import app.opencode.mobile.net.RelayState
import app.opencode.mobile.ui.screens.ChatScreen
import app.opencode.mobile.ui.screens.ConnectScreen
import app.opencode.mobile.ui.screens.FilesScreen
import app.opencode.mobile.ui.screens.SessionsScreen
import app.opencode.mobile.ui.screens.SettingsScreen

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

    val startDest = if (relayState is RelayState.Ready) "sessions" else "connect"

    LaunchedEffect(relayState) {
        if (relayState is RelayState.Ready) {
            nav.navigate("sessions") {
                popUpTo("connect") { inclusive = true }
                launchSingleTop = true
            }
        }
    }

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
}
