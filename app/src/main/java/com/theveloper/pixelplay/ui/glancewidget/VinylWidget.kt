package com.theveloper.pixelplay.ui.glancewidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.theveloper.pixelplay.MainActivity
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.PlayerInfo
import timber.log.Timber

class VinylWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PlayerInfoStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val playerInfo = currentState<PlayerInfo>()
            val size = LocalSize.current
            
            GlanceTheme {
                VinylWidgetUi(playerInfo, size)
            }
        }
    }

    @Composable
    private fun VinylWidgetUi(playerInfo: PlayerInfo, size: DpSize) {
        val context = LocalContext.current
        val style = playerInfo.vinylBackgroundStyle
        
        // Use default theme colors as base
        val colors = playerInfo.getWidgetColors()
        
        // Fix: Theme color logic
        val backgroundColor = when (style) {
            "WHITE" -> ColorProvider(day = Color.White, night = Color.White)
            "BLACK" -> ColorProvider(day = Color.Black, night = Color.Black)
            else -> colors.surface // ALBUM uses theme surface or adaptive art
        }
        
        val textColor = when (style) {
            "WHITE" -> ColorProvider(day = Color.Black, night = Color.Black)
            "BLACK" -> ColorProvider(day = Color.White, night = Color.White)
            else -> colors.onSurface
        }

        val artistTextColor = when (style) {
            "WHITE" -> ColorProvider(day = Color.DarkGray, night = Color.DarkGray)
            "BLACK" -> ColorProvider(day = Color.LightGray, night = Color.LightGray)
            else -> colors.artist
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(28.dp)
                .background(backgroundColor)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            // Background Layer (Album Art)
            if (style == "ALBUM") {
                AlbumArtImageGlance(
                    bitmapData = playerInfo.albumArtBitmapData,
                    albumArtUri = playerInfo.albumArtUri,
                    context = context,
                    cornerRadius = 0.dp,
                    modifier = GlanceModifier.fillMaxSize()
                )
                // Darken the background more for better contrast
                Box(modifier = GlanceModifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {}
            }

            Column(
                modifier = GlanceModifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Vinyl Player Area
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight(),
                    contentAlignment = Alignment.Center
                ) {
                    // Glassmorphic Vinyl Disc
                    Box(
                        modifier = GlanceModifier
                            .size(minOf(size.width, size.height) * 0.75f)
                            .cornerRadius(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Glass Base
                        Box(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.1f))
                                .cornerRadius(300.dp)
                        ) {}

                        // Subtle inner glow
                        Box(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .padding(2.dp)
                                .background(Color.White.copy(alpha = 0.05f))
                                .cornerRadius(300.dp)
                        ) {}

                        // Album Art in center (Circular + Rotated)
                        AlbumArtImageGlance(
                            bitmapData = playerInfo.albumArtBitmapData,
                            albumArtUri = playerInfo.albumArtUri,
                            size = minOf(size.width, size.height) * 0.35f,
                            context = context,
                            cornerRadius = 300.dp,
                            rotation = if (playerInfo.isPlaying) playerInfo.rotationDegrees else 0f,
                            modifier = GlanceModifier.size(minOf(size.width, size.height) * 0.35f),
                            isCircular = true
                        )
                        
                        // Reflection Overlays (Static Glass Look)
                        Box(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.08f))
                                .cornerRadius(300.dp)
                        ) {}
                        
                        // Center Hole Pin
                        Box(
                            modifier = GlanceModifier
                                .size(4.dp)
                                .background(Color.Gray)
                                .cornerRadius(2.dp)
                        ) {}
                    }
                    
                    // Tonearm System (Top Right)
                    Box(
                        modifier = GlanceModifier.fillMaxSize(),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Column(horizontalAlignment = Alignment.Horizontal.End) {
                            // Pivot Base
                            Box(
                                modifier = GlanceModifier
                                    .size(36.dp)
                                    .background(Color.Black.copy(alpha = 0.8f))
                                    .cornerRadius(18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = GlanceModifier
                                        .size(12.dp)
                                        .background(Color(0xFF555555))
                                        .cornerRadius(6.dp)
                                ) {}
                            }
                            
                            // Metallic Arm
                            Box(
                                modifier = GlanceModifier
                                    .padding(end = 16.dp)
                                    .width(4.dp)
                                    .height(minOf(size.width, size.height) * 0.45f)
                                    .background(Color(0xFFBBBBBB))
                            ) {}
                            
                            // Headshell
                            Box(
                                modifier = GlanceModifier
                                    .padding(end = 12.dp)
                                    .size(12.dp, 24.dp)
                                    .background(Color.Black)
                                    .cornerRadius(2.dp)
                            ) {}
                        }
                    }
                }

                Spacer(GlanceModifier.height(12.dp))

                // Song Info
                Column(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(
                        text = playerInfo.songTitle.ifEmpty { context.getString(R.string.app_name) },
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = playerInfo.artistName.ifEmpty { context.getString(R.string.widget_tap_to_open) },
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = artistTextColor
                        ),
                        maxLines = 1
                    )
                }

                Spacer(GlanceModifier.height(16.dp))

                // Bottom Row: Style Selector and Controls
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Style Selectors
                    Row(
                        modifier = GlanceModifier
                            .background(Color.Gray.copy(alpha = 0.2f))
                            .cornerRadius(20.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StyleButton(color = Color.White, style = "WHITE", currentStyle = style)
                        Spacer(GlanceModifier.width(8.dp))
                        StyleButton(color = Color.Black, style = "BLACK", currentStyle = style)
                        Spacer(GlanceModifier.width(8.dp))
                        StyleButton(
                            albumArt = playerInfo.albumArtBitmapData ?: playerInfo.albumArtUri,
                            style = "ALBUM",
                            currentStyle = style,
                            context = context
                        )
                    }
                    
                    Spacer(GlanceModifier.defaultWeight())
                    
                    // Controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PreviousButton(
                            modifier = GlanceModifier.size(40.dp),
                            backgroundColor = colors.prevNextBackground,
                            iconColor = colors.prevNextIcon,
                            cornerRadius = 20.dp
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        PlayPauseButton(
                            modifier = GlanceModifier.size(48.dp),
                            isPlaying = playerInfo.isPlaying,
                            backgroundColor = colors.playPauseBackground,
                            iconColor = colors.playPauseIcon,
                            cornerRadius = 24.dp,
                            iconSize = 24.dp
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        NextButton(
                            modifier = GlanceModifier.size(40.dp),
                            backgroundColor = colors.prevNextBackground,
                            iconColor = colors.prevNextIcon,
                            cornerRadius = 20.dp
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StyleButton(
        color: Color? = null,
        albumArt: Any? = null,
        style: String,
        currentStyle: String,
        context: Context? = null
    ) {
        val isSelected = style == currentStyle
        val modifier = GlanceModifier
            .size(24.dp)
            .cornerRadius(12.dp)
            .clickable(actionRunCallback<VinylStyleActionCallback>(
                actionParametersOf(VinylStyleActionCallback.styleKey to style)
            ))
            
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            if (color != null) {
                Box(modifier = GlanceModifier.fillMaxSize().background(color).cornerRadius(12.dp)) {}
            } else if (albumArt != null && context != null) {
                AlbumArtImageGlance(
                    bitmapData = if (albumArt is ByteArray) albumArt else null,
                    albumArtUri = if (albumArt is String) albumArt else null,
                    size = 24.dp,
                    context = context,
                    cornerRadius = 12.dp,
                    modifier = GlanceModifier.fillMaxSize(),
                    isCircular = true
                )
            } else {
                 Box(modifier = GlanceModifier.fillMaxSize().background(Color.Gray).cornerRadius(12.dp)) {}
            }
            
            if (isSelected) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .cornerRadius(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.rounded_check_circle_24),
                        contentDescription = null,
                        modifier = GlanceModifier.size(16.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(day = Color.White, night = Color.White))
                    )
                }
            }
        }
    }

    @Composable
    fun AlbumArtImageGlance(
        bitmapData: ByteArray?,
        albumArtUri: String? = null,
        size: Dp? = null,
        context: Context,
        modifier: GlanceModifier = GlanceModifier,
        cornerRadius: Dp = 16.dp,
        rotation: Float = 0f,
        isCircular: Boolean = false
    ) {
        val sizingModifier = if (size != null) modifier.size(size) else modifier
        val widgetDpSize = LocalSize.current

        val imageProvider = bitmapData?.let { data ->
            val cacheKey = AlbumArtBitmapCache.getKey(data) + ":rot:$rotation:circ:$isCircular"
            var bitmap = AlbumArtBitmapCache.getBitmap(cacheKey)

            if (bitmap == null) {
                try {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)

                    val targetWidthPx: Int
                    val targetHeightPx: Int
                    with(context.resources.displayMetrics) {
                        if (size != null) {
                            val targetSizePx = (size.value * density).toInt()
                            targetWidthPx = targetSizePx
                            targetHeightPx = targetSizePx
                        } else {
                            targetWidthPx = (widgetDpSize.width.value * density).toInt()
                            targetHeightPx = (widgetDpSize.height.value * density).toInt()
                        }
                    }

                    var inSampleSize = 1
                    if (options.outHeight > targetHeightPx || options.outWidth > targetWidthPx) {
                        val halfHeight = options.outHeight / 2
                        val halfWidth = options.outWidth / 2
                        while (halfHeight / inSampleSize >= targetHeightPx && halfWidth / inSampleSize >= targetWidthPx) {
                            inSampleSize *= 2
                        }
                    }

                    options.inSampleSize = inSampleSize
                    options.inJustDecodeBounds = false
                    val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    
                    if (decoded != null) {
                        var processed = decoded
                        if (isCircular) {
                            processed = getCircularBitmap(processed)
                        }
                        if (rotation != 0f) {
                            val matrix = Matrix().apply { postRotate(rotation) }
                            processed = Bitmap.createBitmap(processed, 0, 0, processed.width, processed.height, matrix, true)
                        }
                        bitmap = processed
                    }
                    
                    bitmap?.let { AlbumArtBitmapCache.putBitmap(cacheKey, it) }
                } catch (e: Exception) {
                    bitmap = null
                }
            }
            bitmap?.let { ImageProvider(it) }
        } ?: albumArtUri?.let { rawUri ->
            val cacheKey = "uri:$rawUri:rot:$rotation:circ:$isCircular"
            var bitmap = AlbumArtBitmapCache.getBitmap(cacheKey)
            if (bitmap == null) {
                val (targetWidthPx, targetHeightPx) = with(context.resources.displayMetrics) {
                    if (size != null) {
                        val target = (size.value * density).toInt().coerceAtLeast(1)
                        target to target
                    } else {
                        val width = (widgetDpSize.width.value * density).toInt().coerceAtLeast(1)
                        val height = (widgetDpSize.height.value * density).toInt().coerceAtLeast(1)
                        width to height
                    }
                }
                val decoded = decodeWidgetAlbumArtBitmap(context, rawUri, targetWidthPx, targetHeightPx)
                if (decoded != null) {
                    var processed = decoded
                    if (isCircular) {
                        processed = getCircularBitmap(processed)
                    }
                    if (rotation != 0f) {
                        val matrix = Matrix().apply { postRotate(rotation) }
                        processed = Bitmap.createBitmap(processed, 0, 0, processed.width, processed.height, matrix, true)
                    }
                    bitmap = processed
                }
                bitmap?.let { AlbumArtBitmapCache.putBitmap(cacheKey, it) }
            }
            bitmap?.let { ImageProvider(it) }
        }

        Box(modifier = sizingModifier) {
            if (imageProvider != null) {
                Image(
                    provider = imageProvider,
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(cornerRadius),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(cornerRadius)
                        .background(GlanceTheme.colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_music_placeholder),
                        contentDescription = null,
                        modifier = GlanceModifier.size(size?.times(0.6f) ?: 48.dp),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                    )
                }
            }
        }
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val color = 0xff424242.toInt()
        val paint = Paint()
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = color
        canvas.drawOval(rectF, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val srcRect = Rect(
            (bitmap.width - size) / 2,
            (bitmap.height - size) / 2,
            (bitmap.width + size) / 2,
            (bitmap.height + size) / 2
        )
        canvas.drawBitmap(bitmap, srcRect, rect, paint)
        return output
    }
}

class VinylStyleActionCallback : ActionCallback {
    companion object {
        val styleKey = ActionParameters.Key<String>("vinylStyleKey")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val newStyle = parameters[styleKey] ?: return
        updateAppWidgetState(context, PlayerInfoStateDefinition, glanceId) { playerInfo ->
            playerInfo.copy(vinylBackgroundStyle = newStyle)
        }
        VinylWidget().update(context, glanceId)
    }
}
