package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/**
 * Lightweight glass-inspired surface: gradients and borders only.
 * Avoids realtime blur, offscreen layers, and animation on low-end devices.
 */
fun Modifier.premiumPanel(cornerRadius: Int = 14): Modifier = composed {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(cornerRadius.dp)
    val background = scheme.surface
    this
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    Color(0xFF6ADDED).copy(alpha = 0.15f).compositeOver(background),
                    scheme.surfaceVariant.copy(alpha = 0.35f).compositeOver(background),
                    Color(0xFFA78BFA).copy(alpha = 0.07f).compositeOver(background)
                )
            )
        )
        .border(1.dp, scheme.outline.copy(alpha = 0.42f), shape)
}

@Composable
fun PremiumSectionSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.premiumPanel().padding(12.dp)) { content() }
}
