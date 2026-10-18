package app.opencode.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opencode.mobile.ui.AppRoot
import app.opencode.mobile.ui.theme.Bg
import app.opencode.mobile.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenCodeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    val vm: AppViewModel = viewModel()
                    val relayState by vm.relayState.collectAsStateWithLifecycle()
                    AppRoot(vm = vm, relayState = relayState)
                }
            }
        }
    }
}
