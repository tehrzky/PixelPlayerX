package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.ui.theme.LocalThemeDefinition
import com.theveloper.pixelplay.ui.theme.ThemeBorderStyle

/** A container that reads its corner radius and border style from the active
 * ThemeDefinition instead of a hardcoded RoundedCornerShape(16.dp) — this is
 * the concrete reason the schema needed a `shape` field at all. Existing
 * screens using plain Card()/RoundedCornerShape(16.dp) directly don't pick
 * this up automatically; adopting ThemedCard in place of those is the actual
 * screen-migration work described in the roadmap, done one screen at a time.
 *
 * containerColor defaults to the theme's surface color when not specified,
 * so callers don't need to know about ThemeDefinition just to get a themed
 * background.
 */
@Composable
fun ThemedCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable () -> Unit
) {
    val theme = LocalThemeDefinition.current
    val shape = RoundedCornerShape(theme.shape.cornerRadius)
    val bgColor = containerColor ?: theme.colors.surface

    Box(
        modifier = modifier
            .background(bgColor, shape)
            .let { base ->
                when (theme.shape.borderStyle) {
                    ThemeBorderStyle.NONE -> base
                    ThemeBorderStyle.SOLID -> base.dashedOrSolidBorder(
                        color = theme.colors.accent,
                        widthDp = theme.shape.borderWidth.value,
                        cornerRadiusDp = theme.shape.cornerRadius.value,
                        dashed = false
                    )
                    ThemeBorderStyle.DASHED -> base.dashedOrSolidBorder(
                        color = theme.colors.accent,
                        widthDp = theme.shape.borderWidth.value,
                        cornerRadiusDp = theme.shape.cornerRadius.value,
                        dashed = true
                    )
                }
            }
    ) {
        content()
    }
}

/** Material's border()/BorderStroke has no dashed option, so a real drawn
 * stroke with a dash PathEffect is the only way to get the TUI look — this
 * is drawn manually rather than reached for a library that doesn't exist in
 * this project. */
private fun Modifier.dashedOrSolidBorder(
    color: Color,
    widthDp: Float,
    cornerRadiusDp: Float,
    dashed: Boolean
): Modifier = this.then(
    Modifier.then(
        androidx.compose.ui.draw.drawBehind {
            val strokeWidthPx = widthDp * density
            val cornerPx = cornerRadiusDp * density
            val style = if (dashed) {
                Stroke(
                    width = strokeWidthPx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            } else {
                Stroke(width = strokeWidthPx)
            }
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = style
            )
        }
    )
)
