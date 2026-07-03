package com.champ.rung.engine

/**
 * Pure Double Sir pile logic. No Android or coroutine dependencies so the
 * exact shipped rules can be verified by the offline simulation harness.
 *
 * Rules implemented (as played by the house):
 *  - Won tricks are NOT credited immediately; each completed trick's 4 cards
 *    join a face-down pending pile in the middle.
 *  - When the SAME PLAYER wins two tricks in a row, they claim the whole
 *    pending pile (including both of those tricks) for their team.
 *  - Minimums: the FIRST claim of the round requires at least
 *    [FIRST_CLAIM_MIN_TRICKS] tricks in the pile; later claims require at
 *    least [LATER_CLAIM_MIN_TRICKS]. The minimum is compulsory, a maximum is
 *    not — nothing ever forces a lift, the pile simply keeps growing until a
 *    consecutive pair lands at or past the minimum. A too-early pair leaves
 *    the pile untouched and the streak alive.
 *  - Ace restriction: the FIRST claim of the round cannot be made if the
 *    trick that would complete the pair was won with an Ace. The pile stays
 *    pending, and the player's streak is NOT broken — if they win the next
 *    trick too (without an Ace, or after any first claim has been made),
 *    that completes a new consecutive pair and claims everything.
 *  - After a successful claim the streak resets: a fresh pair of consecutive
 *    wins is needed for the next claim, starting from an empty pile.
 *  - Whatever is still pending after the 13th trick goes to the winner of
 *    that last trick (handled by the caller via [flushRemainder]).
 */
class DoubleSirTracker {

    companion object {
        /** The first pile can only be lifted once it holds this many tricks. */
        const val FIRST_CLAIM_MIN_TRICKS = 5

        /**
         * Later piles need this many tricks. With same-player pairs and the
         * post-claim streak reset this is always satisfied automatically, but
         * it is checked explicitly because it is a stated rule of the game.
         */
        const val LATER_CLAIM_MIN_TRICKS = 2
    }

    /** Cards currently sitting unclaimed in the middle. */
    var pendingPile = 0
        private set

    /** Seat that won the previous trick (-1 if none / streak broken by a claim). */
    var streakSeat = -1
        private set

    /** True once any pile has been successfully claimed this round. */
    var firstClaimDone = false
        private set

    sealed class Outcome {
        /** Trick joined the pile; no claim this time. */
        data object Accumulated : Outcome()

        /** Same player won twice in a row and takes every pending card. */
        data class Claimed(val seat: Int, val cards: Int) : Outcome()

        /** A pair was completed, but an Ace can't make the first claim. */
        data class AceBlocked(val seat: Int) : Outcome()

        /** A pair was completed before the pile reached the minimum size. */
        data class TooEarly(val seat: Int, val needTricks: Int, val haveTricks: Int) : Outcome()
    }

    /**
     * Record a completed trick. The 4 played cards enter the pending pile,
     * then the consecutive-win rule is applied.
     */
    fun onTrickWon(winnerSeat: Int, wonWithAce: Boolean): Outcome {
        pendingPile += 4

        if (winnerSeat == streakSeat) {
            // Second consecutive win by the same player -> claim attempt.
            val pileTricks = pendingPile / 4
            val minTricks = if (firstClaimDone) LATER_CLAIM_MIN_TRICKS else FIRST_CLAIM_MIN_TRICKS
            if (pileTricks < minTricks) {
                // Streak survives: streakSeat already equals winnerSeat.
                return Outcome.TooEarly(winnerSeat, minTricks, pileTricks)
            }
            if (!firstClaimDone && wonWithAce) {
                // Streak survives: streakSeat already equals winnerSeat.
                return Outcome.AceBlocked(winnerSeat)
            }
            firstClaimDone = true
            val claimed = pendingPile
            pendingPile = 0
            streakSeat = -1 // a fresh pair is required for the next claim
            return Outcome.Claimed(winnerSeat, claimed)
        }

        streakSeat = winnerSeat
        return Outcome.Accumulated
    }

    /**
     * End of round: hand whatever is still pending to the last trick's winner.
     * Returns the number of cards flushed (possibly 0).
     */
    fun flushRemainder(): Int {
        val cards = pendingPile
        pendingPile = 0
        streakSeat = -1
        return cards
    }

    fun reset() {
        pendingPile = 0
        streakSeat = -1
        firstClaimDone = false
    }
}
