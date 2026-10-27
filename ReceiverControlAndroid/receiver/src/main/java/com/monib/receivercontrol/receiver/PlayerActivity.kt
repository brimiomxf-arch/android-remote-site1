package com.monib.receivercontrol.receiver

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        setContent {
            MaterialTheme {
                if (path.isNullOrBlank()) Text("File not found") else ReceiverMediaViewer(File(path))
            }
        }
    }

    companion object { const val EXTRA_PATH = "file_path" }
}

@Composable
private fun ReceiverMediaViewer(file: File) {
    val context = LocalContext.current
    val extension = file.extension.lowercase()
    val isImage = extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    val isVideo = extension in setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts")
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            isImage -> {
                val bitmap = remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
                if (bitmap != null) Image(bitmap, contentDescription = file.name, modifier = Modifier.fillMaxSize())
                else Text("Unable to display image", color = Color.White, modifier = Modifier.padding(24.dp))
            }
            isVideo -> {
                val player = remember(file.absolutePath) { ExoPlayer.Builder(context).build() }
                LaunchedEffect(player, file.absolutePath) {
                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    player.prepare()
                    player.playWhenReady = true
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                    update = { it.player = player }
                )
                DisposableEffect(player) { onDispose { player.release() } }
            }
            else -> Text("${file.name}\nUnsupported preview format", color = Color.White, modifier = Modifier.padding(24.dp))
        }
    }
}
