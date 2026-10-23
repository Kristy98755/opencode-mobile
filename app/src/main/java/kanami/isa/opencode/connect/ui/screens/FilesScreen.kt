package kanami.isa.opencode.connect.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kanami.isa.opencode.connect.AppViewModel
import kanami.isa.opencode.connect.net.FileNode
import kanami.isa.opencode.connect.pcName
import kanami.isa.opencode.connect.pcParent
import kanami.isa.opencode.connect.ui.theme.Border
import kanami.isa.opencode.connect.ui.theme.Surface1
import kanami.isa.opencode.connect.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val files by vm.files.collectAsStateWithLifecycle()
    val viewer by vm.viewer.collectAsStateWithLifecycle()
    val pendingOp by vm.pendingOp.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val opBusy by vm.fileOpBusy.collectAsStateWithLifecycle()

    var menuNode by remember { mutableStateOf<FileNode?>(null) }
    var renameNode by remember { mutableStateOf<FileNode?>(null) }
    var deleteNode by remember { mutableStateOf<FileNode?>(null) }
    var downloadTarget by remember { mutableStateOf<FileNode?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val node = downloadTarget
        downloadTarget = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && node != null) {
            vm.downloadFile(node, uri)
        }
    }

    fun askDownload(node: FileNode) {
        // Android 10+: straight into Download/OpenCodeConnect, no picker, no
        // permission (MediaStore lets the app insert its own files).
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            vm.downloadFile(node)
            return
        }
        // Android 8-9: public Downloads needs WRITE_EXTERNAL_STORAGE there, so
        // fall back to the SAF picker (system grants the write itself).
        downloadTarget = node
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, node.name)
        }
        saveLauncher.launch(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            pcName(files.path).ifEmpty { "Files" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            files.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.goUpDir() }, enabled = pcParent(files.path) != null) {
                        Icon(Icons.Filled.ExpandLess, contentDescription = "Up")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                files.loading && files.nodes.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                files.error != null -> Text(
                    files.error ?: "error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                files.nodes.isEmpty() -> Text(
                    "Empty directory",
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(files.nodes, key = { it.absolute.ifEmpty { it.path + it.name } }) { node ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (node.type == "directory") vm.loadDir(node.absolute.ifEmpty { node.path })
                                    else vm.openFile(node.absolute.ifEmpty { node.path })
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                if (node.type == "directory") Icons.Outlined.Folder else Icons.Outlined.Description,
                                contentDescription = null,
                                tint = if (node.type == "directory") MaterialTheme.colorScheme.primary else TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    node.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (node.ignored) {
                                    Text(
                                        "ignored",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { menuNode = node }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "Actions",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuNode == node,
                                    onDismissRequest = { menuNode = null },
                                ) {
                                    if (node.type == "file") {
                                        DropdownMenuItem(
                                            text = { Text("Download") },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Download, contentDescription = null)
                                            },
                                            onClick = {
                                                menuNode = null
                                                askDownload(node)
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Edit, contentDescription = null)
                                        },
                                        onClick = {
                                            menuNode = null
                                            renameNode = node
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                                        },
                                        onClick = {
                                            menuNode = null
                                            vm.startPendingOp("copy", node)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Move") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.DriveFileMove, contentDescription = null)
                                        },
                                        onClick = {
                                            menuNode = null
                                            vm.startPendingOp("move", node)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Delete",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            menuNode = null
                                            deleteNode = node
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (opBusy) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            pendingOp?.let { op ->
                Surface(
                    color = Surface1,
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            (if (op.kind == "copy") "Copy" else "Move") + " \"" + op.name + "\" here?",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            files.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(onClick = { vm.cancelPendingOp() }) { Text("Cancel") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { vm.pasteHere() },
                                enabled = !opBusy,
                            ) {
                                Text(if (op.kind == "copy") "Copy here" else "Move here")
                            }
                        }
                    }
                }
            }
        }
    }

    viewer?.let { v ->
        AlertDialog(
            onDismissRequest = { vm.closeViewer() },
            title = {
                Text(
                    pcName(v.path),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                if (v.binary) {
                    Text("Binary file — no preview", color = TextSecondary)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            v.content.take(20000),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.closeViewer() }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val parent = pcParent(v.path)
                    vm.closeViewer()
                    if (parent != null) vm.loadDir(parent)
                }) { Text("Open dir") }
            },
        )
    }

    renameNode?.let { node ->
        var name by remember(node.absolute) { mutableStateOf(node.name) }
        AlertDialog(
            onDismissRequest = { renameNode = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name.trim() != node.name && !opBusy,
                    onClick = {
                        val newName = name.trim()
                        renameNode = null
                        vm.renameFile(node, newName)
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameNode = null }) { Text("Cancel") }
            },
        )
    }

    deleteNode?.let { node ->
        AlertDialog(
            onDismissRequest = { deleteNode = null },
            title = { Text("Delete to recycle bin") },
            text = {
                Column {
                    Text(
                        node.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The item is moved to the Windows recycle bin on the PC — " +
                            "restore it there any time. Nothing is destroyed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    SlideToConfirm(
                        label = "Slide to the edge to delete",
                        onConfirmed = {
                            deleteNode = null
                            vm.deleteFile(node)
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { deleteNode = null }) { Text("Cancel") }
            },
        )
    }

    download?.let { d ->
        AlertDialog(
            onDismissRequest = { if (!d.running) vm.dismissDownload() },
            title = {
                Text(
                    when {
                        d.error != null -> "Download failed"
                        d.running -> "Downloading…"
                        else -> "Downloaded"
                    },
                )
            },
            text = {
                Column {
                    Text(
                        d.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (d.error != null) {
                        Text(
                            d.error ?: "error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        if (d.total > 0) {
                            LinearProgressIndicator(
                                progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            humanBytes(d.done) + " / " + humanBytes(d.total),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                    }
                }
            },
            confirmButton = {
                if (!d.running) {
                    TextButton(onClick = { vm.dismissDownload() }) { Text("Close") }
                }
            },
            dismissButton = {
                if (d.running) {
                    TextButton(onClick = { vm.cancelDownload() }) { Text("Cancel") }
                }
            },
        )
    }
}

/**
 * Slide-to-confirm: the knob must be dragged to the far edge of the track and
 * released there; a partial swipe snaps back. Used for destructive actions
 * (delete) so no accidental tap can trigger them.
 */
@Composable
private fun SlideToConfirm(
    label: String,
    onConfirmed: () -> Unit,
) {
    val trackHeight = 52.dp
    val knobSize = 44.dp
    val density = LocalDensity.current
    val knobPx = with(density) { knobSize.toPx() }
    val padPx = with(density) { 5.dp.toPx() }
    var trackW by remember { mutableFloatStateOf(0f) }
    var pos by remember { mutableFloatStateOf(0f) }
    val maxDrag = (trackW - knobPx - padPx * 2).coerceAtLeast(0f)
    val animatedPos by animateFloatAsState(
        targetValue = pos.coerceIn(0f, maxDrag),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "slideToConfirm",
    )
    val progress = if (maxDrag > 0f) animatedPos / maxDrag else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .onSizeChanged { trackW = it.width.toFloat() }
            .pointerInput(maxDrag) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val confirmed = maxDrag > 0f && pos >= maxDrag * 0.9f
                        pos = 0f
                        if (confirmed) onConfirmed()
                    },
                    onDragCancel = { pos = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    pos = (pos + dragAmount).coerceIn(0f, maxDrag)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 54.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.55f + 0.45f * progress),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((padPx + animatedPos).roundToInt(), 0) }
                .size(knobSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp),
            )
        }
    }
}

private fun humanBytes(n: Long): String = when {
    n >= 1073741824L -> String.format("%.1f GB", n / 1073741824.0)
    n >= 1048576L -> String.format("%.1f MB", n / 1048576.0)
    n >= 1024L -> String.format("%.1f KB", n / 1024.0)
    else -> "$n B"
}
