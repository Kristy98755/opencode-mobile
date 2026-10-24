package kanami.isa.opencode.connect.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kanami.isa.opencode.connect.net.FileChunk
import kanami.isa.opencode.connect.net.OpencodeApi
import kanami.isa.opencode.connect.net.Part
import kanami.isa.opencode.connect.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun isImagePart(part: Part): Boolean =
    part.type == "image" || (part.type == "file" && part.mime?.startsWith("image/") == true)

fun isVideoPart(part: Part): Boolean =
    part.type == "file" && part.mime?.startsWith("video/") == true

fun isAudioPart(part: Part): Boolean =
    part.type == "file" && part.mime?.startsWith("audio/") == true

fun hasMedia(part: Part): Boolean = isImagePart(part) || isVideoPart(part) || isAudioPart(part)

private const val MAX_BITMAP_PX = 2048

private fun decodeScaled(bytes: ByteArray): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var sample = 1
    while (opts.outWidth / sample > MAX_BITMAP_PX || opts.outHeight / sample > MAX_BITMAP_PX) sample *= 2
    val real = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
}

private fun dataUrlBytes(url: String): ByteArray? = try {
    Base64.decode(url.substringAfter("base64,"), Base64.DEFAULT)
} catch (_: Exception) {
    null
}

/** Fetches a PC-side file through the agent in 64KB chunks into the app cache. */
private suspend fun downloadPcFile(api: OpencodeApi, part: Part, target: File): Boolean = withContext(Dispatchers.IO) {
    try {
        if (target.exists() && target.length() > 0) return@withContext true
        val tmp = File(target.absolutePath + ".part")
        val out = tmp.outputStream()
        var offset = 0L
        while (true) {
            val chunk: FileChunk = api.fileChunk(part.url ?: return@withContext false, offset)
            if (chunk.data.isNotEmpty()) out.write(Base64.decode(chunk.data, Base64.DEFAULT))
            offset += chunk.data.length * 3 / 4
            if (chunk.eof || chunk.data.isEmpty()) break
        }
        out.close()
        tmp.renameTo(target)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Resolves any media part to a playable/viewable Uri: data-URLs and PC paths are
 * materialized into the cache, http(s) links pass straight through.
 */
private suspend fun resolveMediaUri(context: android.content.Context, part: Part, api: OpencodeApi?): Uri? {
    val url = part.url ?: return null
    return withContext(Dispatchers.IO) {
        try {
            when {
                url.startsWith("data:") -> {
                    val bytes = dataUrlBytes(url) ?: return@withContext null
                    val ext = part.mime?.substringAfter('/') ?: "bin"
                    val f = File(context.cacheDir, "media_" + part.id.hashCode() + "." + ext)
                    if (!f.exists() || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
                    Uri.fromFile(f)
                }
                url.startsWith("http://") || url.startsWith("https://") -> Uri.parse(url)
                else -> {
                    if (api == null) return@withContext null
                    val ext = part.filename?.substringAfterLast('.') ?: "bin"
                    val f = File(context.cacheDir, "media_" + part.id.hashCode() + "." + ext)
                    if (downloadPcFile(api, part, f)) Uri.fromFile(f) else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Entry point: renders the media of one message part, or nothing at all. */
@Composable
fun MessageMedia(part: Part, api: OpencodeApi?, modifier: Modifier = Modifier) {
    when {
        isImagePart(part) -> ImagePartView(part, api, modifier)
        isVideoPart(part) -> VideoPartCard(part, api, modifier)
        isAudioPart(part) -> AudioPartCard(part, api, modifier)
    }
}

@Composable
private fun ImagePartView(part: Part, api: OpencodeApi?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = part.url ?: return
    var fullScreen by remember { mutableStateOf(false) }

    if (url.startsWith("http://") || url.startsWith("https://")) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
            contentDescription = part.filename,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .heightIn(max = 280.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { fullScreen = true },
        )
    } else {
        val bitmap by produceState<Bitmap?>(initialValue = null, part.id) {
            value = withContext(Dispatchers.IO) {
                val bytes = if (url.startsWith("data:")) dataUrlBytes(url)
                else if (api != null && part.url != null) {
                    val f = File(context.cacheDir, "media_" + part.id.hashCode() + ".img")
                    if (downloadPcFile(api, part, f)) f.readBytes() else null
                } else null
                bytes?.let { decodeScaled(it) }
            }
        }
        when (val bmp = bitmap) {
            null -> Box(
                modifier
                    .heightIn(max = 280.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x14FFFFFF)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            else -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = part.filename,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .heightIn(max = 280.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { fullScreen = true },
            )
        }
    }

    if (fullScreen) {
        Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xE6000000))
                    .clickable { fullScreen = false },
                contentAlignment = Alignment.Center,
            ) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(url).build(),
                        contentDescription = part.filename,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                } else {
                    val bmp by produceState<Bitmap?>(initialValue = null, part.id + "-full") {
                        value = withContext(Dispatchers.IO) {
                            val bytes = if (url.startsWith("data:")) dataUrlBytes(url) else null
                            bytes?.let { decodeScaled(it) }
                        }
                    }
                    bmp?.let {
                        Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
                    } ?: CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MediaCardShell(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x14FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun VideoPartCard(part: Part, api: OpencodeApi?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    if (failed) {
        MediaCardShell(
            icon = { Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp)) },
            title = part.filename ?: "video",
            subtitle = "failed to load video",
            onClick = {},
            modifier = modifier,
        )
        return
    }

    MediaCardShell(
        icon = { Icon(Icons.Outlined.VideoFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
        title = part.filename ?: "video",
        subtitle = part.mime ?: "video",
        onClick = { playing = true },
        trailing = { Icon(Icons.Outlined.PlayCircle, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
        modifier = modifier,
    )

    if (playing) {
        val uri by produceState<Uri?>(initialValue = null, part.id) {
            value = resolveMediaUri(context, part, api)
        }
        Dialog(onDismissRequest = { playing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier
                    .fillMaxWidth(0.96f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                when (val u = uri) {
                    null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    else -> VideoPlayer(u) { failed = true; playing = false }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(uri: Uri, onError: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onError()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidViewPlayer(player)
}

@Composable
private fun AndroidViewPlayer(player: ExoPlayer) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AudioPartCard(part: Part, api: OpencodeApi?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(1f) }
    var failed by remember { mutableStateOf(false) }

    val uri by produceState<Uri?>(initialValue = null, part.id) {
        value = resolveMediaUri(context, part, api)
    }

    val player = remember(uri) {
        uri?.let {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(it))
                prepare()
            }
        }
    }
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (true) {
            position = player.currentPosition.toFloat().coerceAtLeast(0f)
            duration = player.duration.toFloat().coerceAtLeast(1f)
            playing = player.isPlaying
            kotlinx.coroutines.delay(300)
        }
    }
    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (failed) Icons.Outlined.BrokenImage else Icons.Outlined.AudioFile,
                contentDescription = null,
                tint = if (failed) TextSecondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(part.filename ?: "audio", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (failed) "failed to load audio" else (part.mime ?: "audio"),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
            IconButton(
                enabled = !failed && player != null,
                onClick = {
                    val p = player ?: return@IconButton
                    if (p.isPlaying) p.pause() else p.play()
                },
            ) {
                Icon(
                    if (playing) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        LinearProgressIndicator(
            progress = { (position / duration).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}
