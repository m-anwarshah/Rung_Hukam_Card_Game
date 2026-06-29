package com.champ.rung.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Phase {
    LOBBY, TOSS, DEALING, CHOOSE_RUNG, PLAYING, TRICK_DONE, ROUND_OVER
}

@Serializable
data class SeatInfo(
    val name: String = "",
    val connected: Boolean = false
)

@Serializable
data class PlayedCard(
    val seat: Int,
    val card: Card
)

@Serializable
data class RoundResult(
    val kind: String,        // "COURT" | "GC" | "NORMAL"
    val winnerTeam: String,  // "A" | "B"
    val tricksA: Int,
    val tricksB: Int,
    val nextSelector: Int
)

/**
 * Personalised per-seat snapshot. The host builds one of these for each seat,
 * so a phone only ever receives its own [hand]; everyone else is just a count.
 */
@Serializable
data class TableState(
    val phase: Phase = Phase.LOBBY,
    val roomCode: String = "",
    val roundNumber: Int = 1,
    val seats: List<SeatInfo> = emptyList(),
    val mySeat: Int = -1,
    val hand: List<Card> = emptyList(),
    val handCounts: List<Int> = listOf(0, 0, 0, 0),
    val trump: Suit? = null,
    val rungSelector: Int = -1,
    val turn: Int = -1,
    val trick: List<PlayedCard> = emptyList(),
    val trickWinner: Int = -1,
    val tricksA: Int = 0,
    val tricksB: Int = 0,
    val completedTricks: Int = 0,
    val pendingPile: Int = 0,
    val tossCards: List<Card>? = null,
    val tossLowest: Int = -1,
    val banner: String = "",
    val paused: Boolean = false,
    val pausedNote: String = "",
    val roundResult: RoundResult? = null,
    val roundsA: Int = 0,
    val roundsB: Int = 0,
    val hostIps: List<String> = emptyList()
)

@Serializable
sealed class Msg {
    // client -> host
    @Serializable @SerialName("hello")
    data class Hello(val name: String, val code: String) : Msg()

    @Serializable @SerialName("chooseRung")
    data class ChooseRung(val suit: Suit) : Msg()

    @Serializable @SerialName("play")
    data class Play(val card: Card) : Msg()

    // host -> client
    @Serializable @SerialName("welcome")
    data class Welcome(val seat: Int) : Msg()

    @Serializable @SerialName("rejected")
    data class Rejected(val reason: String) : Msg()

    @Serializable @SerialName("state")
    data class State(val table: TableState) : Msg()

    @Serializable @SerialName("toast")
    data class Toast(val text: String) : Msg()

    @Serializable @SerialName("cancelled")
    data class Cancelled(val reason: String) : Msg()
}

/** One JSON object per line on the wire. */
val WireJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
}
