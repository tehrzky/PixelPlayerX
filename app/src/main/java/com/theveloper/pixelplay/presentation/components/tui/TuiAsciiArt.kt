package com.theveloper.pixelplay.presentation.components.tui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ASCII_CHARS = " .'`^\",:;Il!i><~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$"

suspend fun uriToAscii(context: Context, uri: Any?, width: Int = 34, height: Int = 34): String? {
    uri ?: return null
    return withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(width, height)
            .allowHardware(false)
            .build()
        when (val result = context.imageLoader.execute(request)) {
            is SuccessResult -> {
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                bitmapToAscii(bitmap, width, height)
            }
            else -> null
        }
    }
}

private fun bitmapToAscii(bitmap: Bitmap, w: Int, h: Int): String {
    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
    val sb = StringBuilder()
    for (y in 0 until h) {
        for (x in 0 until w) {
            val pixel = scaled.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val brightness = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            val idx = (brightness * (ASCII_CHARS.length - 1)).toInt()
                .coerceIn(0, ASCII_CHARS.length - 1)
            sb.append(ASCII_CHARS[ASCII_CHARS.length - 1 - idx])
        }
        if (y < h - 1) sb.append('\n')
    }
    scaled.recycle()
    return sb.toString()
}

@Composable
fun TuiAsciiArt(
    imageUri: Any?,
    widthChars: Int = 34,
    heightChars: Int = 34,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var ascii by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(imageUri) {
        ascii = uriToAscii(context, imageUri, widthChars, heightChars)
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ascii?.let {
            Text(
                text = it,
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                lineHeight = 7.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                letterSpacing = 0.sp
            )
        }
    }
}
