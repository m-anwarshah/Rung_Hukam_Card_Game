package com.champ.rung.engine

import kotlin.random.Random

/**
 * Offline verification for DoubleSirTracker (with claim minimums).
 *
 * A structurally independent reference model recomputes the expected outcome
 * of every trick from the full trick HISTORY, and both models are compared
 * trick-by-trick across deterministic scenarios and randomized rounds.
 */

data class TrickEvent(val winner: Int, val ace: Boolean)

sealed class RefOutcome {
    data object Acc : RefOutcome()
    data class Claim(val seat: Int, val cards: Int) : RefOutcome()
    data class Blocked(val seat: Int) : RefOutcome()
    data class Early(val seat: Int, val have: Int) : RefOutcome()
}

/** Reference: decide trick i's outcome purely from history[0..i]. */
class ReferenceModel {
    private val history = mutableListOf<TrickEvent>()
    private val outcomes = mutableListOf<RefOutcome>()

    fun play(ev: TrickEvent): RefOutcome {
        history.add(ev)
        val i = history.size - 1

        val prevWinner: Int = if (i == 0) -1 else {
            when (outcomes[i - 1]) {
                is RefOutcome.Claim -> -1
                else -> history[i - 1].winner
            }
        }
        val anyClaim = outcomes.any { it is RefOutcome.Claim }
        var lastClaimIdx = -1
        for (k in outcomes.indices) if (outcomes[k] is RefOutcome.Claim) lastClaimIdx = k
        val pendingWithThis = 4 * (i - lastClaimIdx)          // includes this trick
        val minTricks = if (anyClaim) 2 else 5

        val out: RefOutcome = if (ev.winner == prevWinner) {
            when {
                pendingWithThis / 4 < minTricks -> RefOutcome.Early(ev.winner, pendingWithThis / 4)
                !anyClaim && ev.ace -> RefOutcome.Blocked(ev.winner)
                else -> RefOutcome.Claim(ev.winner, pendingWithThis)
            }
        } else RefOutcome.Acc
        outcomes.add(out)
        return out
    }

    fun pendingAtEnd(): Int {
        var lastClaimIdx = -1
        for (k in outcomes.indices) if (outcomes[k] is RefOutcome.Claim) lastClaimIdx = k
        return 4 * (outcomes.size - 1 - lastClaimIdx)
    }
}

var assertions = 0L
fun check(cond: Boolean, msg: () -> String) {
    assertions++
    if (!cond) throw AssertionError(msg())
}

class RoundStats {
    var claims = 0; var earlies = 0; var blocks = 0
}

/** Run one full 13-trick round through both models and cross-check everything. */
fun runRound(events: List<TrickEvent>, stats: RoundStats? = null): IntArray {
    check(events.size == 13) { "need 13 tricks" }
    val tracker = DoubleSirTracker()
    val ref = ReferenceModel()
    val teamTricks = intArrayOf(0, 0)
    var claimedCards = 0
    var prevOutcomeWasClaim = false
    var stickySeat = -1          // seat that just got Blocked/TooEarly: next win by it can't be Acc
    var sawFirstClaim = false

    for ((idx, ev) in events.withIndex()) {
        val pileBefore = tracker.pendingPile
        val streakBefore = tracker.streakSeat
        val firstBefore = tracker.firstClaimDone

        val out = tracker.onTrickWon(ev.winner, ev.ace)
        val expected = ref.play(ev)

        // 1) models agree
        when (expected) {
            is RefOutcome.Acc -> check(out is DoubleSirTracker.Outcome.Accumulated) {
                "trick $idx: expected Accumulated, got $out (ev=$ev)"
            }
            is RefOutcome.Blocked -> {
                check(out is DoubleSirTracker.Outcome.AceBlocked && out.seat == expected.seat) {
                    "trick $idx: expected Blocked(${expected.seat}), got $out"
                }
            }
            is RefOutcome.Early -> {
                check(out is DoubleSirTracker.Outcome.TooEarly) { "trick $idx: expected Early, got $out" }
                out as DoubleSirTracker.Outcome.TooEarly
                check(out.seat == expected.seat && out.haveTricks == expected.have) {
                    "trick $idx: early mismatch got=(${out.seat},${out.haveTricks}) exp=(${expected.seat},${expected.have})"
                }
            }
            is RefOutcome.Claim -> {
                check(out is DoubleSirTracker.Outcome.Claimed) { "trick $idx: expected Claim, got $out" }
                out as DoubleSirTracker.Outcome.Claimed
                check(out.seat == expected.seat && out.cards == expected.cards) {
                    "trick $idx: claim mismatch got=(${out.seat},${out.cards}) exp=(${expected.seat},${expected.cards})"
                }
            }
        }

        // 2) structural rules on the tracker itself
        when (out) {
            is DoubleSirTracker.Outcome.Claimed -> {
                check(ev.winner == streakBefore) { "claim without consecutive win" }
                check(firstBefore || !ev.ace) { "first claim made with an Ace" }
                check(out.cards == pileBefore + 4) { "claim size wrong" }
                check(out.cards % 4 == 0) { "claim not 4-aligned" }
                if (!sawFirstClaim) {
                    check(out.cards >= 4 * DoubleSirTracker.FIRST_CLAIM_MIN_TRICKS) {
                        "first claim below minimum: ${out.cards} cards"
                    }
                } else {
                    check(out.cards >= 4 * DoubleSirTracker.LATER_CLAIM_MIN_TRICKS) {
                        "later claim below minimum: ${out.cards} cards"
                    }
                }
                check(tracker.pendingPile == 0) { "pile not emptied on claim" }
                check(tracker.streakSeat == -1) { "streak not reset after claim" }
                check(tracker.firstClaimDone) { "firstClaimDone not set" }
                check(!prevOutcomeWasClaim) { "claim immediately after a claim (needs fresh pair)" }
                teamTricks[ev.winner % 2] += out.cards / 4
                claimedCards += out.cards
                sawFirstClaim = true
                stickySeat = -1
                stats?.let { it.claims++ }
            }
            is DoubleSirTracker.Outcome.AceBlocked -> {
                check(ev.winner == streakBefore) { "block without consecutive win" }
                check(!firstBefore && ev.ace) { "block only applies to first claim with ace" }
                check(!sawFirstClaim) { "blocked after a claim already happened" }
                check((pileBefore + 4) / 4 >= DoubleSirTracker.FIRST_CLAIM_MIN_TRICKS) {
                    "ace block evaluated below the minimum (TooEarly should win)"
                }
                check(tracker.pendingPile == pileBefore + 4) { "blocked trick must stay in pile" }
                check(tracker.streakSeat == ev.winner) { "streak must survive an ace block" }
                stickySeat = ev.winner
                stats?.let { it.blocks++ }
            }
            is DoubleSirTracker.Outcome.TooEarly -> {
                check(ev.winner == streakBefore) { "too-early without consecutive win" }
                check(!firstBefore && !sawFirstClaim) {
                    "too-early can only happen before the first claim (min 2 is inherent afterwards)"
                }
                check(out.needTricks == DoubleSirTracker.FIRST_CLAIM_MIN_TRICKS) { "wrong minimum reported" }
                check(out.haveTricks == (pileBefore + 4) / 4) { "wrong pile count reported" }
                check(out.haveTricks < out.needTricks) { "too-early despite meeting minimum" }
                check(idx in 1..3) { "too-early outside tricks 2..4 (idx=$idx)" }
                check(tracker.pendingPile == pileBefore + 4) { "early trick must stay in pile" }
                check(tracker.streakSeat == ev.winner) { "streak must survive a too-early pair" }
                stickySeat = ev.winner
                stats?.let { it.earlies++ }
            }
            is DoubleSirTracker.Outcome.Accumulated -> {
                check(tracker.pendingPile == pileBefore + 4) { "accumulate didn't grow pile" }
                check(tracker.streakSeat == ev.winner) { "streak not set to winner" }
                check(stickySeat != ev.winner) { "same player after block/early must not just accumulate" }
                stickySeat = -1
            }
        }
        prevOutcomeWasClaim = out is DoubleSirTracker.Outcome.Claimed
    }

    // 3) round end flush to last-trick winner
    val refPending = ref.pendingAtEnd()
    val flushed = tracker.flushRemainder()
    check(flushed == refPending) { "flush mismatch: $flushed vs $refPending" }
    check(flushed % 4 == 0) { "flush not 4-aligned" }
    teamTricks[events.last().winner % 2] += flushed / 4
    check(claimedCards + flushed == 52) { "cards lost: claimed=$claimedCards flushed=$flushed" }
    check(teamTricks[0] + teamTricks[1] == 13) { "tricks don't sum to 13: ${teamTricks.toList()}" }
    check(tracker.pendingPile == 0) { "pile after flush" }
    return teamTricks
}

fun main() {
    // ---------- deterministic scenarios ----------
    // a) one player sweeps, no aces: pairs at t2..t4 are too early; first claim at
    //    t5 = 20 cards, then 8-card claims at t7, t9, t11, t13; nothing to flush.
    run {
        val tracker = DoubleSirTracker()
        val sizes = mutableListOf<Int>()
        var early = 0
        repeat(13) {
            when (val o = tracker.onTrickWon(2, false)) {
                is DoubleSirTracker.Outcome.Claimed -> sizes.add(o.cards)
                is DoubleSirTracker.Outcome.TooEarly -> early++
                else -> {}
            }
        }
        check(early == 3) { "a: expected 3 too-early pairs, got $early" }
        check(sizes == listOf(20, 8, 8, 8, 8)) { "a: claim schedule wrong: $sizes" }
        check(tracker.flushRemainder() == 0) { "a: nothing should remain" }
        val t = runRound(List(13) { TrickEvent(2, false) })
        check(t[0] == 13 && t[1] == 0) { "a: sweep scoring wrong: ${t.toList()}" }
    }
    // b) strict alternation: no pairs, all 52 flushed to the last winner (seat 0)
    run {
        val t = runRound(List(13) { TrickEvent(it % 2, false) })
        check(t[0] == 13 && t[1] == 0) { "b: alternation flush wrong: ${t.toList()}" }
    }
    // c) minimum first, then ace blocks, then claim: P0 wins t1..t7,
    //    aces on t5 and t6 -> TooEarly x3, AceBlocked x2, Claim 28 at t7.
    run {
        val tracker = DoubleSirTracker()
        val aces = setOf(4, 5) // 0-based tricks 5 and 6
        val outs = (0 until 7).map { tracker.onTrickWon(0, it in aces) }
        check(outs[0] is DoubleSirTracker.Outcome.Accumulated) { "c: t1" }
        for (k in 1..3) check(outs[k] is DoubleSirTracker.Outcome.TooEarly) { "c: t${k + 1} should be too early" }
        check(outs[4] is DoubleSirTracker.Outcome.AceBlocked) { "c: t5 should ace-block (minimum met)" }
        check(outs[5] is DoubleSirTracker.Outcome.AceBlocked) { "c: t6 should ace-block again" }
        val cl = outs[6]
        check(cl is DoubleSirTracker.Outcome.Claimed && cl.cards == 28 && cl.seat == 0) { "c: t7 claim 28, got $cl" }
    }
    // d) ace on the FIRST trick of a pair is irrelevant (pile already at minimum)
    run {
        val tracker = DoubleSirTracker()
        listOf(0, 1, 0, 1).forEach { tracker.onTrickWon(it, false) } // pile = 4 tricks
        tracker.onTrickWon(3, true)                                   // 5th trick, ace, new streak
        val c = tracker.onTrickWon(3, false)
        check(c is DoubleSirTracker.Outcome.Claimed && c.cards == 24) { "d: expected claim of 24, got $c" }
    }
    // e) after the first claim, ace pairs claim freely at the 2-trick minimum
    run {
        val tracker = DoubleSirTracker()
        repeat(5) { tracker.onTrickWon(0, false) }                    // first claim = 20 at t5
        tracker.onTrickWon(1, true)
        val c = tracker.onTrickWon(1, true)
        check(c is DoubleSirTracker.Outcome.Claimed && c.cards == 8) { "e: ace pair after first claim must claim, got $c" }
    }
    // f) first claim on the 13th trick takes all 52
    run {
        val evs = List(11) { TrickEvent(it % 2, false) } + listOf(TrickEvent(3, false), TrickEvent(3, false))
        val t = runRound(evs)
        check(t[1] == 13 && t[0] == 0) { "f: final-pair claim should take everything: ${t.toList()}" }
    }
    // g) an early pair is wasted but the streak idea survives across other wins
    run {
        val tracker = DoubleSirTracker()
        tracker.onTrickWon(2, false)
        check(tracker.onTrickWon(2, false) is DoubleSirTracker.Outcome.TooEarly) { "g: t2 early" }
        tracker.onTrickWon(1, false) // streak moves to seat 1
        tracker.onTrickWon(2, false) // streak back to seat 2
        val c = tracker.onTrickWon(2, false) // pair at t5, pile = 5 tricks
        check(c is DoubleSirTracker.Outcome.Claimed && c.cards == 20) { "g: expected claim of 20 at t5, got $c" }
    }

    // ---------- randomized rounds ----------
    val rnd = Random(20260703)
    var rounds = 0
    val teamRoundWins = intArrayOf(0, 0)
    var thirteenZero = 0
    val stats = RoundStats()
    repeat(250_000) {
        val sticky = rnd.nextDouble()
        val aceP = when (rnd.nextInt(3)) { 0 -> 0.05; 1 -> 0.25; else -> 0.6 }
        var w = rnd.nextInt(4)
        val evs = ArrayList<TrickEvent>(13)
        repeat(13) {
            if (rnd.nextDouble() > sticky) w = rnd.nextInt(4)
            evs.add(TrickEvent(w, rnd.nextDouble() < aceP))
        }
        val t = runRound(evs, stats)
        rounds++
        teamRoundWins[if (t[0] > t[1]) 0 else 1]++
        if (t[0] == 13 || t[1] == 13) thirteenZero++
    }

    println("Deterministic scenarios OK")
    println(
        "Random rounds OK: $rounds (13-0 rounds=$thirteenZero, team wins=${teamRoundWins.toList()}, " +
            "claims=${stats.claims}, tooEarly=${stats.earlies}, aceBlocks=${stats.blocks})"
    )
    println("ALL CHECKS PASSED: $assertions assertions")
}
