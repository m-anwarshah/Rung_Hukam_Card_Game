package com.champ.rung.engine

import com.champ.rung.model.Card
import com.champ.rung.model.GameMode
import com.champ.rung.model.Msg
import com.champ.rung.model.Phase
import com.champ.rung.model.PlayedCard
import com.champ.rung.model.RoundResult
import com.champ.rung.model.SeatInfo
import com.champ.rung.model.Suit
import com.champ.rung.model.TableState
import com.champ.rung.model.freshDeck
import com.champ.rung.model.sortedForDisplay
import com.champ.rung.net.ClientConn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Authoritative game state lives here. All mutations happen on a single actor
 * coroutine (Dispatchers.Default, so socket writes never touch the main thread),
 * which keeps the logic single-threaded without locks. Timed phases use delay().
 *
 * Seats: 0 = host. Teams: even seats = A, odd seats = B. Turn order is anti-
 * clockwise = (seat + 1) % 4.
 */
class HostGameController(
    private val scope: CoroutineScope,
    private val roomCode: String,
    hostName: String,
    private val hostIps: List<String>,
    private val gameMode: GameMode,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onHostState(table: TableState)
        fun onHostToast(text: String)
    }

    sealed class Action {
        data class FromClient(val conn: ClientConn, val msg: Msg) : Action()
        data class Gone(val conn: ClientConn) : Action()
        data object StartGame : Action()
        data object NextRound : Action()
        data class HostChoseRung(val suit: Suit) : Action()
        data class HostPlayed(val card: Card) : Action()
        data object Refresh : Action()
    }

    // ---- seat state ----
    private val names = arrayOf(hostName.ifBlank { "Player 1" }, "", "", "")
    private val connected = booleanArrayOf(true, false, false, false)
    private val hands = Array(4) { mutableListOf<Card>() }
    val conns = AtomicReferenceArray<ClientConn?>(4) // index 0 unused (host plays locally)

    // ---- round state ----
    private var phase = Phase.LOBBY
    private var roundNumber = 1
    private var deck = mutableListOf<Card>()
    private var trump: Suit? = null
    private var rungSelector = -1
    private var turn = -1
    private val trick = mutableListOf<PlayedCard>()
    private var trickWinner = -1
    private val tricksWon = intArrayOf(0, 0) // [A, B]
    private var completedTricks = 0
    private var firstLiftDone = false
    private var tricksSinceLastLift = 0
    private var pendingPile = 0
    private val doubleSir = DoubleSirTracker()   // used only in DOUBLE_SIR mode
    private var tossCards: List<Card>? = null
    private var tossLowest = -1
    private var banner = ""
    private var roundResult: RoundResult? = null
    private val roundsWon = intArrayOf(0, 0)
    private var pendingNextSelector = -1

    private val mailbox = Channel<Action>(Channel.UNLIMITED)

    private val job = scope.launch(Dispatchers.Default) {
        for (action in mailbox) {
            try {
                handle(action)
            } catch (_: Throwable) {
                // keep the actor alive on unexpected errors
            }
        }
    }

    fun post(action: Action) {
        mailbox.trySend(action)
    }

    fun stop() {
        mailbox.close()
        job.cancel()
    }

    // ---- helpers ----
    private fun teamOf(seat: Int) = seat % 2            // 0 -> A, 1 -> B
    private fun partnerOf(seat: Int) = (seat + 2) % 4
    private fun nextSeat(seat: Int) = (seat + 1) % 4

    private fun seatList(): List<SeatInfo> = (0 until 4).map { SeatInfo(names[it], connected[it]) }
    private fun connectedCount(): Int = (0 until 4).count { names[it].isNotEmpty() && connected[it] }

    private fun isPaused(): Boolean =
        phase != Phase.LOBBY && (0 until 4).any { names[it].isNotEmpty() && !connected[it] }

    private fun pausedNote(): String {
        val waiting = (0 until 4).filter { names[it].isNotEmpty() && !connected[it] }.map { names[it] }
        return if (waiting.isEmpty()) "" else "Waiting for ${waiting.joinToString(", ")} to reconnect\u2026"
    }

    private suspend fun handle(action: Action) {
        when (action) {
            is Action.FromClient -> onClientMsg(action.conn, action.msg)
            is Action.Gone -> onGone(action.conn)
            is Action.StartGame -> startGame()
            is Action.NextRound -> nextRound()
            is Action.HostChoseRung -> onChooseRung(0, action.suit)
            is Action.HostPlayed -> onPlay(0, action.card)
            is Action.Refresh -> pushAll()
        }
    }

    // ---- connection handling ----
    private suspend fun onClientMsg(conn: ClientConn, msg: Msg) {
        if (conn.seat < 0) {
            if (msg is Msg.Hello) onHello(conn, msg)
            else {
                conn.send(Msg.Rejected("Say hello first."))
                conn.close()
            }
            return
        }
        when (msg) {
            is Msg.ChooseRung -> onChooseRung(conn.seat, msg.suit)
            is Msg.Play -> onPlay(conn.seat, msg.card)
            is Msg.TakeSeat -> onTakeSeat(conn, msg.seat)
            else -> { /* ignore */ }
        }
    }

    private fun onHello(conn: ClientConn, hello: Msg.Hello) {
        if (hello.code != roomCode) {
            conn.send(Msg.Rejected("Wrong room code."))
            conn.close()
            return
        }
        val wanted = hello.name.trim().ifEmpty { "Player" }

        if (phase == Phase.LOBBY) {
            var seat = -1
            for (s in 1..3) if (names[s].isEmpty()) { seat = s; break }
            if (seat < 0) {
                conn.send(Msg.Rejected("Room is full."))
                conn.close()
                return
            }
            names[seat] = uniqueName(wanted, seat)
            connected[seat] = true
            conn.seat = seat
            conns.set(seat, conn)
            conn.send(Msg.Welcome(seat))
            pushAll()
            return
        }

        // in-game: reconnect by name first, else take over any disconnected seat
        var seat = -1
        for (s in 0..3) {
            if (names[s].isNotEmpty() && !connected[s] && names[s].equals(wanted, ignoreCase = true)) {
                seat = s; break
            }
        }
        if (seat < 0) {
            for (s in 0..3) if (names[s].isNotEmpty() && !connected[s]) { seat = s; break }
            if (seat >= 0) names[seat] = uniqueName(wanted, seat)
        }
        if (seat < 0) {
            conn.send(Msg.Rejected("Game already in progress."))
            conn.close()
            return
        }
        val old = conns.get(seat)
        if (old != null && old !== conn) {
            old.seat = -1
            old.close()
        }
        connected[seat] = true
        conn.seat = seat
        conns.set(seat, conn)
        conn.send(Msg.Welcome(seat))
        pushAll()
    }

    private fun uniqueName(wanted: String, seat: Int): String {
        var candidate = wanted
        var n = 2
        while ((0 until 4).any { it != seat && names[it].equals(candidate, ignoreCase = true) }) {
            candidate = "$wanted $n"; n++
        }
        return candidate
    }

    /**
     * Lobby-only team/seat selection. A joiner taps a seat: if it's free they
     * move there; if another joiner holds it, the two swap. Seat 0 belongs to
     * whoever created the room and never moves — Team A is anchored on the host.
     */
    private fun onTakeSeat(conn: ClientConn, target: Int) {
        val from = conn.seat
        if (from !in 1..3) return
        if (phase != Phase.LOBBY) {
            conn.send(Msg.Toast("Seats are locked once the game starts."))
            return
        }
        if (target == from) return
        if (target !in 1..3) {
            conn.send(Msg.Toast("The host keeps that seat \u2014 pick another."))
            return
        }
        val teamName = if (target % 2 == 0) "A" else "B"
        if (names[target].isEmpty()) {
            names[target] = names[from]
            connected[target] = connected[from]
            names[from] = ""
            connected[from] = false
            conns.set(target, conn)
            conns.set(from, null)
            conn.seat = target
            toastAll("${names[target]} moved to Team $teamName")
        } else {
            val other = conns.get(target)
            val tmpName = names[target]
            names[target] = names[from]
            names[from] = tmpName
            val tmpConn = connected[target]
            connected[target] = connected[from]
            connected[from] = tmpConn
            conns.set(target, conn)
            conns.set(from, other)
            conn.seat = target
            other?.seat = from
            toastAll("${names[target]} and ${names[from]} swapped seats")
        }
        pushAll()
    }

    private fun onGone(conn: ClientConn) {
        val seat = conn.seat
        if (seat < 0) return
        if (conns.get(seat) !== conn) return // a newer conn already owns this seat
        conns.set(seat, null)
        if (phase == Phase.LOBBY) {
            names[seat] = ""
            connected[seat] = false
        } else {
            connected[seat] = false
            toastAll("${names[seat]} disconnected")
        }
        pushAll()
    }

    // ---- game start / rounds ----
    private suspend fun startGame() {
        if (phase != Phase.LOBBY) return
        if (connectedCount() < 4) {
            callbacks.onHostToast("Need 4 players to start.")
            return
        }
        roundNumber = 1
        resetRoundData()
        runTossThenDeal()
    }

    private suspend fun runTossThenDeal() {
        phase = Phase.TOSS
        while (true) {
            val d = freshDeck().also { it.shuffle() }
            val cards = (0 until 4).map { d[it] }
            tossCards = cards
            tossLowest = -1
            banner = "Toss\u2026"
            pushAll()
            delay(1600)

            val lowestValue = cards.minOf { it.value }
            val lowestSeats = (0 until 4).filter { cards[it].value == lowestValue }
            if (lowestSeats.size > 1) {
                banner = "Tie on lowest \u2014 tossing again"
                pushAll()
                delay(2600)
            } else {
                val low = lowestSeats[0]
                tossLowest = low
                rungSelector = nextSeat(low)
                banner = "${names[low]} had the lowest \u2014 ${names[rungSelector]} selects Rung"
                pushAll()
                delay(3200)
                break
            }
        }
        tossCards = null
        tossLowest = -1
        startDealing()
    }

    private suspend fun startDealing() {
        phase = Phase.DEALING
        deck = freshDeck().also { it.shuffle() }
        for (h in hands) h.clear()
        banner = "Dealing\u2026"
        pushAll()
        delay(800)

        dealRound(5)
        pushAll()
        delay(900)

        phase = Phase.CHOOSE_RUNG
        banner = "${names[rungSelector]} is choosing Rung"
        pushAll()
    }

    /** Deal [count] cards to each seat, anti-clockwise from the Rung selector. */
    private fun dealRound(count: Int) {
        for (i in 0 until count) {
            for (k in 0 until 4) {
                val seat = (rungSelector + k) % 4
                if (deck.isNotEmpty()) hands[seat].add(deck.removeAt(deck.size - 1))
            }
        }
    }

    private suspend fun onChooseRung(seat: Int, suit: Suit) {
        if (phase != Phase.CHOOSE_RUNG) return
        if (isPaused()) { rejectTo(seat, "Game is paused."); return }
        if (seat != rungSelector) { rejectTo(seat, "Only the Rung selector can choose."); return }

        trump = suit
        phase = Phase.DEALING
        banner = "Rung is ${suit.displayName} ${suit.symbol}"
        pushAll()
        delay(800)

        dealRound(4)
        pushAll()
        delay(800)

        dealRound(4)
        phase = Phase.PLAYING
        turn = rungSelector
        trick.clear()
        trickWinner = -1
        banner = "${names[rungSelector]} leads"
        pushAll()
    }

    private suspend fun onPlay(seat: Int, card: Card) {
        if (phase != Phase.PLAYING) { rejectTo(seat, "Not now."); return }
        if (isPaused()) { rejectTo(seat, "Game is paused."); return }
        if (seat != turn) { rejectTo(seat, "Not your turn."); return }

        val hand = hands[seat]
        if (!hand.contains(card)) { rejectTo(seat, "You don't have that card."); return }

        if (trick.isNotEmpty()) {
            val lead = trick[0].card.suit
            if (hand.any { it.suit == lead } && card.suit != lead) {
                rejectTo(seat, "You must follow ${lead.displayName}.")
                return
            }
        }

        hand.remove(card)
        trick.add(PlayedCard(seat, card))

        if (trick.size < 4) {
            turn = nextSeat(seat)
            pushAll()
        } else {
            resolveTrick()
        }
    }

    private suspend fun resolveTrick() {
        val winnerSeat = winningPlay(trick, trump).seat
        val winningCard = trick.first { it.seat == winnerSeat }.card
        trickWinner = winnerSeat
        phase = Phase.TRICK_DONE
        turn = -1
        banner = "${names[winnerSeat]} wins the trick"
        pushAll()
        delay(2600)

        completedTricks++
        var claimNote = ""
        if (gameMode == GameMode.DOUBLE_SIR) {
            when (val out = doubleSir.onTrickWon(winnerSeat, winningCard.isAce)) {
                is DoubleSirTracker.Outcome.Claimed -> {
                    tricksWon[teamOf(winnerSeat)] += out.cards / 4
                    claimNote = "${names[winnerSeat]} claims ${out.cards} cards (${out.cards / 4} tricks)"
                    toastAll(claimNote)
                }
                is DoubleSirTracker.Outcome.AceBlocked -> {
                    claimNote = "Ace can't make the first claim \u2014 pile stays"
                    toastAll(claimNote)
                }
                is DoubleSirTracker.Outcome.TooEarly -> {
                    claimNote = "First lift needs ${out.needTricks} tricks \u2014 pile has ${out.haveTricks}"
                    toastAll(claimNote)
                }
                is DoubleSirTracker.Outcome.Accumulated -> { /* pile keeps growing */ }
            }
        } else {
            tricksWon[teamOf(winnerSeat)]++
            pendingPile += 4
            applyLifting(winnerSeat, winningCard)
        }

        trick.clear()
        trickWinner = -1

        if (completedTricks >= 13) {
            finishRound(winnerSeat)
        } else {
            phase = Phase.PLAYING
            turn = winnerSeat
            banner = if (claimNote.isEmpty()) "${names[winnerSeat]} leads"
                     else "$claimNote \u00B7 ${names[winnerSeat]} leads"
            pushAll()
        }
    }

    private fun winningPlay(plays: List<PlayedCard>, trumpSuit: Suit?): PlayedCard {
        val trumps = if (trumpSuit != null) plays.filter { it.card.suit == trumpSuit } else emptyList()
        if (trumps.isNotEmpty()) return trumps.maxByOrNull { it.card.value }!!
        val lead = plays[0].card.suit
        return plays.filter { it.card.suit == lead }.maxByOrNull { it.card.value }!!
    }

    /**
     * Lifting per spec: first lift only after >=5 completed tricks and only if the
     * winning card is not an Ace (otherwise the pile stays pending and the counter
     * does NOT advance). After the first lift, lift every 2 tricks; Ace allowed.
     */
    private fun applyLifting(winnerSeat: Int, winningCard: Card) {
        if (!firstLiftDone) {
            if (completedTricks >= 5) {
                if (!winningCard.isAce) {
                    firstLiftDone = true
                    tricksSinceLastLift = 0
                    val lifted = pendingPile
                    pendingPile = 0
                    toastAll("${names[winnerSeat]} lifts $lifted cards")
                } else {
                    toastAll("Ace can't take the first lift \u2014 pile stays")
                }
            }
        } else {
            tricksSinceLastLift++
            if (tricksSinceLastLift >= 2) {
                tricksSinceLastLift = 0
                val lifted = pendingPile
                pendingPile = 0
                toastAll("${names[winnerSeat]} lifts $lifted cards")
            }
        }
    }

    private fun finishRound(lastWinnerSeat: Int) {
        if (gameMode == GameMode.DOUBLE_SIR) {
            // Whatever is still on the table goes to whoever won the 13th trick.
            val leftover = doubleSir.flushRemainder()
            if (leftover > 0) {
                tricksWon[teamOf(lastWinnerSeat)] += leftover / 4
                toastAll(
                    "${names[lastWinnerSeat]} takes the remaining $leftover cards " +
                        "(${leftover / 4} tricks)"
                )
            }
        } else {
            pendingPile = 0 // sweep the display-only pile
        }
        val rungTeam = teamOf(rungSelector)
        val rungTricks = tricksWon[rungTeam]
        val kind: String
        val winnerTeam: Int
        val nextSelector: Int
        when {
            rungTricks == 13 -> {
                kind = "COURT"; winnerTeam = rungTeam; nextSelector = partnerOf(rungSelector)
            }
            rungTricks == 0 -> {
                kind = "GC"; winnerTeam = 1 - rungTeam; nextSelector = nextSeat(rungSelector)
            }
            else -> {
                kind = "NORMAL"
                winnerTeam = if (tricksWon[0] > tricksWon[1]) 0 else 1
                nextSelector = nextSeat(rungSelector)
            }
        }
        roundsWon[winnerTeam]++
        pendingNextSelector = nextSelector
        roundResult = RoundResult(
            kind = kind,
            winnerTeam = if (winnerTeam == 0) "A" else "B",
            tricksA = tricksWon[0],
            tricksB = tricksWon[1],
            nextSelector = nextSelector
        )
        phase = Phase.ROUND_OVER
        banner = ""
        pushAll()
    }

    private suspend fun nextRound() {
        if (phase != Phase.ROUND_OVER) return
        if (isPaused()) {
            callbacks.onHostToast("Can't start while a player is disconnected.")
            return
        }
        roundNumber++
        val ns = pendingNextSelector
        resetRoundData()
        rungSelector = ns
        startDealing()
    }

    private fun resetRoundData() {
        trump = null
        turn = -1
        trick.clear()
        trickWinner = -1
        tricksWon[0] = 0; tricksWon[1] = 0
        completedTricks = 0
        firstLiftDone = false
        tricksSinceLastLift = 0
        pendingPile = 0
        doubleSir.reset()
        tossCards = null
        tossLowest = -1
        roundResult = null
        for (h in hands) h.clear()
    }

    // ---- broadcast ----
    private fun stateFor(seat: Int): TableState {
        val myHand = if (seat in 0..3) hands[seat].sortedForDisplay() else emptyList()
        return TableState(
            phase = phase,
            roomCode = roomCode,
            roundNumber = roundNumber,
            seats = seatList(),
            mySeat = seat,
            hand = myHand,
            handCounts = (0 until 4).map { hands[it].size },
            trump = trump,
            rungSelector = rungSelector,
            turn = turn,
            trick = trick.toList(),
            trickWinner = trickWinner,
            tricksA = tricksWon[0],
            tricksB = tricksWon[1],
            completedTricks = completedTricks,
            pendingPile = if (gameMode == GameMode.DOUBLE_SIR) doubleSir.pendingPile else pendingPile,
            gameMode = gameMode,
            streakSeat = if (gameMode == GameMode.DOUBLE_SIR) doubleSir.streakSeat else -1,
            firstClaimDone = if (gameMode == GameMode.DOUBLE_SIR) doubleSir.firstClaimDone else false,
            tossCards = tossCards,
            tossLowest = tossLowest,
            banner = banner,
            paused = isPaused(),
            pausedNote = pausedNote(),
            roundResult = roundResult,
            roundsA = roundsWon[0],
            roundsB = roundsWon[1],
            hostIps = hostIps
        )
    }

    private fun pushAll() {
        callbacks.onHostState(stateFor(0))
        for (s in 1..3) {
            val c = conns.get(s)
            if (c != null && connected[s]) c.send(Msg.State(stateFor(s)))
        }
    }

    private fun toastAll(text: String) {
        callbacks.onHostToast(text)
        for (s in 1..3) conns.get(s)?.send(Msg.Toast(text))
    }

    private fun rejectTo(seat: Int, reason: String) {
        if (seat == 0) callbacks.onHostToast(reason) else conns.get(seat)?.send(Msg.Toast(reason))
    }

    /** Best-effort "game over" broadcast on a daemon thread; returns it so callers can join. */
    fun shutdownNowThread(reason: String): Thread {
        val snapshot = (1..3).map { conns.get(it) }
        return Thread {
            for (c in snapshot) if (c != null) {
                try { c.send(Msg.Cancelled(reason)) } catch (_: Exception) {}
                try { c.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }
}
