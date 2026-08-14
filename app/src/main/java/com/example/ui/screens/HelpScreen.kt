package com.example.ui.screens

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R

@Composable
fun HelpScreen() {
    val context = LocalContext.current

    val videoView = remember {
        VideoView(context).apply {
            setVideoURI(Uri.parse("android.resource://${context.packageName}/${R.raw.tutorial}"))
            setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.start()
            }
            setOnErrorListener { _, _, _ -> true }
        }
    }
    var isPlaying by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose { videoView.stopPlayback() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "使用教程",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        AndroidView(
            factory = { videoView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (videoView.isPlaying) {
                        videoView.pause()
                        isPlaying = false
                    } else {
                        videoView.start()
                        isPlaying = true
                    }
                }
            ) {
                Text(if (isPlaying) "暂停" else "播放")
            }
            OutlinedButton(
                onClick = {
                    videoView.seekTo(0)
                    videoView.start()
                    isPlaying = true
                }
            ) {
                Text("重新播放")
            }
        }
    }
}
