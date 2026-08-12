package com.theveloper.pixelplay.presentation.components.tui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.formatDuration

@Composable
fun TuiPlayerContent(
    song: Song,
    currentPosition: Long,
    totalDuration: Long,
    isPlaying: Boolean,
    isFavorite: Boolean,
    repeatMode: Int,
    isShuffleEnabled: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCollapse: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onShowQueueClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (totalDuration > 0) {
        currentPosition.toFloat() / totalDuration.toFloat()
    } else 0f

    val bitrateText = buildString {
        song.bitrate?.let { append("${it / 1000}kbps / ") }
        song.sampleRate?.let { append("${it}Hz / ") }
        song.mimeType?.let { append(it.substringAfterLast("/").uppercase()) }
    }.trimEnd(' ', '/')

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status bar
        TuiStatusBar(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))

        // Top row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TuiBracketButton(label = "⌄", onClick = onCollapse)
            TuiBracketButton(label = "♫", onClick = onShowQueueClicked)
        }
        Spacer(modifier = Modifier.height(12.dp))

        // ASCII Album Art
        TuiDashedBox(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(4.dp)
        ) {
            TuiAsciiArt(
                imageUri = song.albumArtUriString,
                widthChars = 36,
                heightChars = 36,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Metadata row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TuiBracketButton(
                label = if (isFavorite) "♥" else "♡",
                onClick = onFavoriteToggle,
                accent = isFavorite
            )
            Text(
                text = bitrateText.ifEmpty { "UNKNOWN FORMAT" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
            TuiBracketButton(label = "::", onClick = { })
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = song.title,
            fontFamily = FontFamily.Monospace,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        // Artist / Album
        Text(
            text = "${song.displayArtist} / ${song.album}",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Progress bar
        TuiDashedProgressBar(
            progress = progress,
            currentTime = formatDuration(currentPosition),
            totalTime = formatDuration(totalDuration),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Playback controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val repeatLabel = when (repeatMode) {
                1 -> "RPT 1"
                2 -> "RPT ALL"
                else -> "RPT OFF"
            }
            TuiBracketButton(
                label = repeatLabel,
                onClick = onRepeatToggle,
                accent = repeatMode != 0
            )
            TuiBracketButton(label = "|<<", onClick = onPrevious)
            TuiBracketButton(
                label = if (isPlaying) "PAUSE" else "PLAY",
                onClick = onPlayPause,
                accent = true
            )
            TuiBracketButton(label = ">>|", onClick = onNext)
            TuiBracketButton(
                label = "SHUFFLE",
                onClick = onShuffleToggle,
                accent = isShuffleEnabled
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom nav
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TuiBracketButton(label = "|||", onClick = onShowQueueClicked)
            TuiBracketButton(label = "⌂", onClick = onCollapse)
            TuiBracketButton(label = "⬚", onClick = { })
            TuiBracketButton(label = "<", onClick = onCollapse)
        }
    }
}
