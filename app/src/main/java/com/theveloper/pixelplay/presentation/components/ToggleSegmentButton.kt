            ToggleSegmentButton(
                modifier = commonModifier,
                active = isShuffleEnabled,
                activeColor = activeColorMain,
                activeCornerRadius = rowCorners,
                activeContentColor = onActiveColorMain,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onClick = onShuffleToggle,
                iconId = R.drawable.rounded_shuffle_24,
                contentDesc = "Shuffle",
                themedIconType = if (isShuffleEnabled)
                    com.theveloper.pixelplay.presentation.components.ThemedIconType.SHUFFLE_ON
                else
                    com.theveloper.pixelplay.presentation.components.ThemedIconType.SHUFFLE_OFF
            )
            val repeatActive = repeatMode != Player.REPEAT_MODE_OFF
            val repeatIcon = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.rounded_repeat_one_24
                Player.REPEAT_MODE_ALL -> R.drawable.rounded_repeat_24
                else -> R.drawable.rounded_repeat_24
            }
            val repeatThemedType = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> com.theveloper.pixelplay.presentation.components.ThemedIconType.REPEAT_ONE
                Player.REPEAT_MODE_ALL -> com.theveloper.pixelplay.presentation.components.ThemedIconType.REPEAT_ALL
                else -> com.theveloper.pixelplay.presentation.components.ThemedIconType.REPEAT_OFF
            }
            ToggleSegmentButton(
                modifier = commonModifier,
                active = repeatActive,
                activeColor = activeColorSecondary,
                activeCornerRadius = rowCorners,
                activeContentColor = onActiveColorSecondary,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onClick = onRepeatToggle,
                iconId = repeatIcon,
                contentDesc = "Repeat",
                themedIconType = repeatThemedType
            )
            ToggleSegmentButton(
                modifier = commonModifier,
                active = isFavorite,
                activeColor = activeColorTertiary,
                activeCornerRadius = rowCorners,
                activeContentColor = onActiveColorTertiary,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onClick = onFavoriteToggle,
                iconId = if (isFavorite) R.drawable.round_favorite_24 else R.drawable.rounded_favorite_24,
                contentDesc = "Favorite",
                themedIconType = if (isFavorite)
                    com.theveloper.pixelplay.presentation.components.ThemedIconType.FAVORITE_ON
                else
                    com.theveloper.pixelplay.presentation.components.ThemedIconType.FAVORITE_OFF
            )
