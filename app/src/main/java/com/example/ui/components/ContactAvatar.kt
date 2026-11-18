package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Displays a circular contact photo when [photoUri] is present, or a clean
 * monogram avatar matching the Material 3 theme.
 */
@Composable
fun ContactAvatar(
    displayName: String,
    photoUri: String?,
    size: Dp = 46.dp,
    fontSize: TextUnit = 18.sp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    borderColor: Color = Color.Transparent,
    modifier: Modifier = Modifier
) {
    val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    var photoLoaded by remember(photoUri) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(1.5.dp, borderColor, CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!photoLoaded) {
            Text(
                text = initial,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
        if (!photoUri.isNullOrBlank()) {
            val parsedUri = remember(photoUri) { runCatching { Uri.parse(photoUri) }.getOrNull() }
            if (parsedUri != null) {
                AsyncImage(
                    model = parsedUri,
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    onSuccess = { photoLoaded = true },
                    onError = { photoLoaded = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
