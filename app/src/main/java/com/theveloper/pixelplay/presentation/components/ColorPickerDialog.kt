package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** HSV(+alpha)-backed color picker: a circular hue/saturation wheel, a
 * brightness slider, an alpha slider, and bidirectionally-synced hex + RGB
 * text fields. HSV+alpha is the single source of truth internally — every
 * input path (wheel drag, sliders, hex text, RGB text) converts into that
 * representation, so nothing can get out of sync with anything else. */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    title: String = "Pick a color",
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit
) {
    val initialHsv = remember { rgbToHsv(initialColor) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember { mutableFloatStateOf(initialColor.alpha) }

    val currentColor = hsvToColor(hue, saturation, brightness, alpha)

    var hexText by remember { mutableStateOf(colorToHex(currentColor)) }
    var rText by remember { mutableStateOf((currentColor.red * 255).toInt().toString()) }
    var gText by remember { mutableStateOf((currentColor.green * 255).toInt().toString()) }
    var bText by remember { mutableStateOf((currentColor.blue * 255).toInt().toString()) }

    // Re-derive text fields whenever the wheel/sliders move the color, without
    // fighting the user while they're actively typing in those same fields —
    // simplest safe approach: always resync on hue/sat/val/alpha change, since
    // those only change from wheel/slider drags, never from text input directly.
    fun resyncTextFromColor(c: Color) {
        hexText = colorToHex(c)
        rText = (c.red * 255).toInt().toString()
        gText = (c.green * 255).toInt().toString()
        bText = (c.blue * 255).toInt().toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Live preview swatch
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(currentColor, RoundedCornerShape(8.dp))
                )

                HueSaturationWheel(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onHueSaturationChange = { h, s ->
                        hue = h; saturation = s
                        resyncTextFromColor(hsvToColor(hue, saturation, brightness, alpha))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(top = 12.dp, bottom = 8.dp)
                )

                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = brightness,
                    onValueChange = {
                        brightness = it
                        resyncTextFromColor(hsvToColor(hue, saturation, brightness, alpha))
                    },
                    valueRange = 0f..1f
                )

                Text("Alpha", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = alpha,
                    onValueChange = {
                        alpha = it
                        resyncTextFromColor(hsvToColor(hue, saturation, brightness, alpha))
                    },
                    valueRange = 0f..1f
                )

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        hexText = input
                        parseHex(input)?.let { parsed ->
                            val hsv = rgbToHsv(parsed)
                            hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                            alpha = parsed.alpha
                            rText = (parsed.red * 255).toInt().toString()
                            gText = (parsed.green * 255).toInt().toString()
                            bText = (parsed.blue * 255).toInt().toString()
                        }
                    },
                    label = { Text("Hex (#RRGGBB or #AARRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RgbField("R", rText, { rText = it }) { updated ->
                        val c = rgbFieldsToColor(updated, gText, bText, alpha) ?: return@RgbField
                        val hsv = rgbToHsv(c)
                        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                        hexText = colorToHex(c)
                    }
                    RgbField("G", gText, { gText = it }) { updated ->
                        val c = rgbFieldsToColor(rText, updated, bText, alpha) ?: return@RgbField
                        val hsv = rgbToHsv(c)
                        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                        hexText = colorToHex(c)
                    }
                    RgbField("B", bText, { bText = it }) { updated ->
                        val c = rgbFieldsToColor(rText, gText, updated, alpha) ?: return@RgbField
                        val hsv = rgbToHsv(c)
                        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                        hexText = colorToHex(c)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(currentColor) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RgbField(label: String, value: String, onValueChange: (String) -> Unit, onCommit: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val digitsOnly = input.filter { it.isDigit() }.take(3)
            onValueChange(digitsOnly)
            onCommit(digitsOnly)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.weight(1f)
    )
}

@Composable
private fun HueSaturationWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onHueSaturationChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { offset -> updateFromOffset(offset, size.width, size.height, onHueSaturationChange) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    updateFromOffset(change.position, size.width, size.height, onHueSaturationChange)
                }
            }
    ) {
        val radius = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Hue ring via angular sweep, saturation via radial white-to-transparent
        // overlay — two brushes composited, both driven by the same brightness
        // value so the wheel visually darkens as brightness drops.
        val hueColors = (0..360 step 10).map { deg ->
            Color.hsv(deg.toFloat(), 1f, brightness)
        }
        drawCircle(
            brush = Brush.sweepGradient(hueColors, center = center),
            radius = radius,
            center = center
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 1f), Color.White.copy(alpha = 0f)),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
        drawCircle(color = Color.Black.copy(alpha = 0.15f), radius = radius, center = center, style = Stroke(2f))

        // Selection indicator ring at the current hue/saturation position
        val angleRad = Math.toRadians(hue.toDouble())
        val dist = saturation * radius
        val indicatorPos = Offset(
            x = center.x + (dist * cos(angleRad)).toFloat(),
            y = center.y + (dist * sin(angleRad)).toFloat()
        )
        drawCircle(color = Color.White, radius = 10f, center = indicatorPos, style = Stroke(3f))
        drawCircle(color = Color.Black.copy(alpha = 0.4f), radius = 10f, center = indicatorPos, style = Stroke(1f))
    }
}

private fun updateFromOffset(
    offset: Offset,
    width: Int,
    height: Int,
    onChange: (hue: Float, saturation: Float) -> Unit
) {
    val radius = min(width, height) / 2f
    val center = Offset(width / 2f, height / 2f)
    val dx = offset.x - center.x
    val dy = offset.y - center.y
    val dist = sqrt(dx * dx + dy * dy).coerceAtMost(radius)
    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    if (angle < 0) angle += 360f
    onChange(angle, (dist / radius).coerceIn(0f, 1f))
}

private fun rgbToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0) it + 360f else it }
    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}

private fun hsvToColor(hue: Float, saturation: Float, brightness: Float, alpha: Float): Color {
    val base = Color.hsv(hue, saturation, brightness)
    return base.copy(alpha = alpha)
}

private fun colorToHex(color: Color): String {
    val argb = color.toArgb()
    return "#%08X".format(argb)
}

private fun parseHex(input: String): Color? {
    val cleaned = input.removePrefix("#").trim()
    return try {
        when (cleaned.length) {
            6 -> Color(("FF$cleaned").toLong(16).toInt())
            8 -> Color(cleaned.toLong(16).toInt())
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private fun rgbFieldsToColor(r: String, g: String, b: String, alpha: Float): Color? {
    val ri = r.toIntOrNull()?.coerceIn(0, 255) ?: return null
    val gi = g.toIntOrNull()?.coerceIn(0, 255) ?: return null
    val bi = b.toIntOrNull()?.coerceIn(0, 255) ?: return null
    return Color(red = ri / 255f, green = gi / 255f, blue = bi / 255f, alpha = alpha)
}
