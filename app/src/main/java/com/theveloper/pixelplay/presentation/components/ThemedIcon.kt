package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.ui.theme.LocalThemeDefinition
import com.theveloper.pixelplay.ui.theme.ThemeIconStyle

/** Semantic icon slots — the finite, curated set actually needed for the
 * transport controls in the flagship screenshot (play/pause, next/prev,
 * shuffle, repeat, favorite), not an attempt to cover every icon in the app.
 * Extending this enum is how a future icon gets theme-awareness; it doesn't
 * scale to "every icon anywhere" and isn't meant to. */
enum class ThemedIconType(val materialIcon: ImageVector, val bracketLabel: String) {
    PLAY(Icons.Rounded.PlayArrow, "[>]"),
    PAUSE(Icons.Rounded.Pause, "[||]"),
    NEXT(Icons.Rounded.SkipNext, "[>>|]"),
    PREVIOUS(Icons.Rounded.SkipPrevious, "[|<<]"),
    SHUFFLE_OFF(Icons.Rounded.Shuffle, "[SHUF]"),
    SHUFFLE_ON(Icons.Rounded.Shuffle, "[SHUF*]"),
    REPEAT_OFF(Icons.Rounded.Repeat, "[RPT]"),
    REPEAT_ALL(Icons.Rounded.Repeat, "[RPT*]"),
    REPEAT_ONE(Icons.Rounded.RepeatOne, "[RPT 1]"),
    FAVORITE_ON(Icons.Rounded.Favorite, "[<3]"),
    FAVORITE_OFF(Icons.Rounded.FavoriteBorder, "[ 3]")
}

/** Renders a Material icon or its bracket-text equivalent depending on the
 * active theme's iconStyle — this, not a color swap, is what actually makes
 * transport controls look like the TUI screenshot instead of just recolored
 * Material icons. Callers don't branch on theme themselves; this is the one
 * place that decision gets made. */
@Composable
fun ThemedIcon(
    type: ThemedIconType,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val theme = LocalThemeDefinition.current
    val clickableModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier

    when (theme.iconStyle) {
        ThemeIconStyle.MATERIAL -> Icon(
            imageVector = type.materialIcon,
            contentDescription = contentDescription,
            modifier = clickableModifier
        )
        ThemeIconStyle.BRACKET_TEXT -> Box(
            modifier = clickableModifier.padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = type.bracketLabel,
                color = LocalContentColor.current,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
