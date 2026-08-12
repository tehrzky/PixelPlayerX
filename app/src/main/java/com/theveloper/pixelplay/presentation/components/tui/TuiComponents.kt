package com.theveloper.pixelplay.presentation.components.tui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TuiBracketButton(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val color = if (accent) MaterialTheme.colorScheme.primary else LocalContentColor.current
    Text(
        text = "[$label]",
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = color,
        modifier = modifier.clickable(onClick = onClick),
        textAlign = TextAlign.Center
    )
}

@Composable
fun TuiDashedProgressBar(
    progress: Float,
    currentTime: String,
    totalTime: String,
    modifier: Modifier = Modifier
) {
    val segments = 32
    val filled = (progress * segments).toInt().coerceIn(0, segments)
    val bar = buildString {
        repeat(filled) { append("─") }
        append("●")
        repeat(segments - filled) { append("─") }
    }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = bar,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = 0.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                currentTime,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                totalTime,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TuiDashedBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.padding(1.dp)) {
        content()
        val outline = MaterialTheme.colorScheme.outline
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(
                color = outline,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                        0f
                    )
                )
            )
        }
    }
}

@Composable
fun TuiStatusBar(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "[||] ▲ 65",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "PURE AUDIO",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "[G] [B]84% 11:22 AM",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
