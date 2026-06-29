package com.champ.rung.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.champ.rung.model.Card
import com.champ.rung.ui.theme.Cream
import com.champ.rung.ui.theme.FeltLight
import com.champ.rung.ui.theme.Gold
import com.champ.rung.ui.theme.RedSuit
import com.champ.rung.ui.theme.BlackSuit

@Composable
fun PlayingCard(
    card: Card,
    width: Dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    selected: Boolean = false,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val height = width * 1.42f
    val suitColor = if (card.suit.isRed) RedSuit else BlackSuit
    var m = modifier
    if (selected) m = m.offset(y = (-12).dp)
    m = m
        .alpha(if (dimmed) 0.4f else 1f)
        .size(width, height)
        .shadow(if (selected) 10.dp else 4.dp, RoundedCornerShape(8.dp))
        .clip(RoundedCornerShape(8.dp))
        .background(Cream)
        .border(
            width = if (selected || highlight) 3.dp else 1.dp,
            color = if (selected || highlight) Gold else Color(0x33000000),
            shape = RoundedCornerShape(8.dp)
        )
    if (onClick != null) m = m.clickable { onClick() }

    Box(modifier = m) {
        Column(modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 3.dp)) {
            Text(
                card.rank,
                color = suitColor,
                fontWeight = FontWeight.Bold,
                fontSize = (width.value * 0.28f).sp,
                lineHeight = (width.value * 0.30f).sp
            )
            Text(
                card.suit.symbol,
                color = suitColor,
                fontSize = (width.value * 0.24f).sp,
                lineHeight = (width.value * 0.26f).sp
            )
        }
        Text(
            card.suit.symbol,
            color = suitColor,
            fontSize = (width.value * 0.5f).sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun CardBack(width: Dp, modifier: Modifier = Modifier) {
    val height = width * 1.42f
    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .background(FeltLight)
            .border(1.dp, Gold, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("\u2660", color = Color(0x55D9B45B), fontSize = (width.value * 0.5f).sp)
    }
}

/**
 * Overlapping fan of the local player's hand. Tap once to lift a card, tap the
 * same card again to play it. Unplayable cards (when it's your turn) are dimmed.
 */
@Composable
fun FanHand(
    cards: List<Card>,
    enabled: Boolean,
    isPlayable: (Card) -> Boolean,
    onPlay: (Card) -> Unit
) {
    var selected by remember(cards) { mutableStateOf<Card?>(null) }
    val cardW = 58.dp

    if (cards.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        return
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardW * 1.42f + 16.dp)
    ) {
        val n = cards.size
        val step: Dp = if (n <= 1) 0.dp
        else ((maxWidth - cardW) / (n - 1)).coerceAtMost(cardW * 0.62f)

        cards.forEachIndexed { i, card ->
            val playable = enabled && isPlayable(card)
            val isSel = selected == card
            PlayingCard(
                card = card,
                width = cardW,
                dimmed = enabled && !playable,
                selected = isSel,
                onClick = if (enabled) {
                    {
                        if (playable) {
                            if (isSel) {
                                onPlay(card)
                                selected = null
                            } else {
                                selected = card
                            }
                        }
                    }
                } else null,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = step * i)
                    .zIndex(if (isSel) 1f else 0f)
            )
        }
    }
}
