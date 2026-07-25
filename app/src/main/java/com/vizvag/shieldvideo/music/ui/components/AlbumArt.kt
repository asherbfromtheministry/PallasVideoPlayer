package com.vizvag.shieldvideo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

@Composable
fun AlbumArt(
    imageUrl: Any?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val model = when (imageUrl) {
        is String -> imageUrl.takeIf { it.isNotBlank() }
        else -> imageUrl
    }
    if (model == null) {
        AlbumArtPlaceholder(title = title, modifier = modifier)
        return
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = title,
        modifier = modifier,
        contentScale = contentScale,
        // Dark hold while loading — letter placeholder flashes and fights the backdrop.
        loading = {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1410)))
        },
        error = { AlbumArtPlaceholder(title = title, modifier = Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent() },
    )
}

@Composable
private fun AlbumArtPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
) {
    val color1 = colorFromString(title)
    val color2 = colorFromStringSecondary(title)
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(color1, color2))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(2).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
            ),
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ArtistAvatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        colorFromString(name),
                        colorFromStringSecondary(name),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}
