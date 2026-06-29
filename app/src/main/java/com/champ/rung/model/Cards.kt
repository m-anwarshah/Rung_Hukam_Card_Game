package com.champ.rung.model

import kotlinx.serialization.Serializable

/**
 * Suits in the fixed display order used everywhere in the UI:
 * Spades, Hearts, Clubs, Diamonds (so colours alternate black/red/black/red).
 */
@Serializable
enum class Suit(val symbol: String, val isRed: Boolean) {
    SPADES("\u2660", false),
    HEARTS("\u2665", true),
    CLUBS("\u2663", false),
    DIAMONDS("\u2666", true);

    val displayName: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * A single card. [value] runs 2..14 where 11=J, 12=Q, 13=K, 14=A (Ace high).
 */
@Serializable
data class Card(val suit: Suit, val value: Int) {
    val rank: String
        get() = when (value) {
            14 -> "A"
            13 -> "K"
            12 -> "Q"
            11 -> "J"
            else -> value.toString()
        }

    val isAce: Boolean get() = value == 14
}

/** A fresh, ordered 52-card deck. */
fun freshDeck(): MutableList<Card> {
    val deck = ArrayList<Card>(52)
    for (suit in Suit.entries) {
        for (v in 2..14) deck.add(Card(suit, v))
    }
    return deck
}

/** Sort a hand for display: grouped by suit (S,H,C,D), high cards first. */
fun List<Card>.sortedForDisplay(): List<Card> =
    sortedWith(compareBy({ it.suit.ordinal }, { -it.value }))
