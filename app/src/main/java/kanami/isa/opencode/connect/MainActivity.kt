package kanami.isa.opencode.connect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kanami.isa.opencode.connect.ui.AppRoot
import kanami.isa.opencode.connect.ui.theme.Bg
import kanami.isa.opencode.connect.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "opencode.session_id"
    }

    private var vmRef: AppViewModel? = null
    private var pendingSessionId: String? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            OpenCodeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    val vm: AppViewModel = viewModel()
                    vmRef = vm
                    pendingSessionId?.let { sid ->
                        pendingSessionId = null
                        vm.openSessionFromNotification(sid)
                    }
                    val relayState by vm.relayState.collectAsStateWithLifecycle()
                    AppRoot(vm = vm, relayState = relayState)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sid = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val vm = vmRef
        if (vm != null) vm.openSessionFromNotification(sid) else pendingSessionId = sid
    }

    override fun onStart() {
        super.onStart()
        vmRef?.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        vmRef?.setForeground(false)
    }
}
