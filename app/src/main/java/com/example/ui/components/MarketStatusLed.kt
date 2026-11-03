package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.market.status.MarketLedState
import com.example.market.status.MarketStatusEvaluation
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BearishRedSoft
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.BullishGreenSoft
import com.example.ui.theme.NeutralAmber
import com.example.ui.theme.NeutralAmberSoft
import com.example.ui.theme.TextMutedDark
import com.example.ui.theme.TextSecondaryDark

/**
 * Isolated Market Status LED Dot component.
 *
 * - GREEN (`OPEN`): Market active & data feed healthy. Pulses gently with a smooth opacity animation
 *   (~1.75s full cycle = 875ms reverse tween) applied in `graphicsLayer` so it never triggers
 *   parent Watchlist or Chart recompositions.
 * - GRAY (`CLOSED`): Market closed. Static dot, no animation.
 * - ORANGE (`STALE`): Market open, but quote is delayed or stale. Static dot.
 * - RED (`ERROR`): Provider disconnected or feed error. Static dot.
 * - NEUTRAL (`UNKNOWN`): Schedule unknown. Static dot.
 */
@Composable
fun MarketStatusLedDot(
    status: MarketStatusEvaluation,
    modifier: Modifier = Modifier,
    dotSize: Dp = 7.dp,
    testTag: String = "market_status_led_${status.symbol}"
) {
    val dotColor = ledColorFor(status.ledState)

    if (status.shouldAnimatePulse) {
        // 875ms reverse tween = 1.75s per full cycle (within the 1.5s-2.0s specification)
        val transition = rememberInfiniteTransition(label = "market_led_pulse_${status.symbol}")
        val pulseAlpha by transition.animateFloat(
            initialValue = 0.38f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 875, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "market_led_opacity"
        )

        Box(
            modifier = modifier
                .size(dotSize)
                .graphicsLayer { alpha = pulseAlpha }
                .clip(CircleShape)
                .background(dotColor)
                .semantics {
                    contentDescription = "${status.symbol} ${status.readableStatusText}"
                }
                .testTag(testTag)
        )
    } else {
        Box(
            modifier = modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor)
                .semantics {
                    contentDescription = "${status.symbol} ${status.readableStatusText}"
                }
                .testTag(testTag)
        )
    }
}

/**
 * Compact Market Status Pill combining the LED dot with a readable status label
 * (`OPEN`, `CLOSED`, `STALE`, `FEED ERROR`, `UNKNOWN`) so status never relies on color alone.
 */
@Composable
fun MarketStatusPill(
    status: MarketStatusEvaluation,
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    testTagPrefix: String = "market_status"
) {
    val fgColor = ledColorFor(status.ledState)
    val bgColor = ledSoftBgFor(status.ledState)

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
            .border(1.dp, fgColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .semantics {
                contentDescription = "${status.symbol} ${status.readableStatusText}"
            }
            .testTag("${testTagPrefix}_pill_${status.symbol}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MarketStatusLedDot(
                status = status,
                dotSize = dotSize,
                testTag = "${testTagPrefix}_dot_${status.symbol}"
            )
            Text(
                text = status.shortBadgeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = fgColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("${testTagPrefix}_label_${status.symbol}")
            )
        }
    }
}

fun ledColorFor(state: MarketLedState): Color {
    return when (state) {
        MarketLedState.OPEN -> BullishGreen
        MarketLedState.CLOSED -> TextMutedDark
        MarketLedState.STALE -> NeutralAmber
        MarketLedState.ERROR -> BearishRed
        MarketLedState.UNKNOWN -> TextSecondaryDark
    }
}

fun ledSoftBgFor(state: MarketLedState): Color {
    return when (state) {
        MarketLedState.OPEN -> BullishGreenSoft
        MarketLedState.CLOSED -> Color(0x2664748B)
        MarketLedState.STALE -> NeutralAmberSoft
        MarketLedState.ERROR -> BearishRedSoft
        MarketLedState.UNKNOWN -> Color(0x2694A3B8)
    }
}
