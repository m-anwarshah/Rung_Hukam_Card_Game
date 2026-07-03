package com.champ.rung.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.champ.rung.GameViewModel
import com.champ.rung.Ui
import com.champ.rung.engine.DoubleSirTracker
import com.champ.rung.model.Card
import com.champ.rung.model.GameMode
import com.champ.rung.model.Phase
import com.champ.rung.model.SeatInfo
import com.champ.rung.model.Suit
import com.champ.rung.model.TableState
import com.champ.rung.ui.theme.BlackSuit
import com.champ.rung.ui.theme.Cream
import com.champ.rung.ui.theme.DangerRed
import com.champ.rung.ui.theme.Felt
import com.champ.rung.ui.theme.FeltDark
import com.champ.rung.ui.theme.FeltLight
import com.champ.rung.ui.theme.Gold
import com.champ.rung.ui.theme.GoldBright
import com.champ.rung.ui.theme.RedSuit

@Composable
fun RoomScreen(vm: GameViewModel) {
    val ui by vm.ui.collectAsState()
    var showLeave by remember { mutableStateOf(false) }
    BackHandler { showLeave = true }

    val table = ui.table

    Box(Modifier.fillMaxSize()) {
        if (table == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Gold)
            }
        } else {
            when (table.phase) {
                Phase.LOBBY -> LobbyScreen(vm, ui, table)
                Phase.TOSS -> TossScreen(table)
                else -> TableScreen(vm, ui, table)
            }
            if (table.phase == Phase.ROUND_OVER && table.roundResult != null) {
                RoundOverOverlay(vm, ui, table)
            }
            Column(Modifier.align(Alignment.TopCenter)) {
                if (ui.connectionNote.isNotEmpty()) ConnectionBar(ui.connectionNote)
                if (table.paused && table.phase != Phase.LOBBY) PausedBar(table.pausedNote)
            }
        }
    }

    if (showLeave) {
        LeaveDialog(
            isHost = ui.isHost,
            onDismiss = { showLeave = false },
            onConfirm = { showLeave = false; vm.leaveRoom() }
        )
    }
}

// ---------------- LOBBY ----------------
@Composable
private fun LobbyScreen(vm: GameViewModel, ui: Ui, table: TableState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        Text("ROOM CODE", color = Gold, fontSize = 13.sp, letterSpacing = 3.sp)
        Text(
            table.roomCode,
            color = Cream,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 12.sp
        )
        if (ui.isHost && table.hostIps.isNotEmpty()) {
            Text(
                "Host IP: ${table.hostIps.joinToString("  \u00B7  ")}",
                color = Cream.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            color = Felt,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Gold)
        ) {
            Text(
                table.gameMode.label.uppercase(),
                color = Gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
        if (table.gameMode == GameMode.DOUBLE_SIR) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Tricks pile up in the middle. Win two tricks in a row to claim the pile \u2014 " +
                    "the first lift needs at least 5 tricks piled and can't be made with an Ace.",
                color = Cream.copy(alpha = 0.6f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(20.dp))

        // Pick your team: two labelled groups so joiners clearly choose who to partner.
        TeamGroup(
            teamName = "A",
            accent = Gold,
            seats = listOf(0, 2),
            table = table,
            isHost = ui.isHost,
            onTake = { seat -> vm.takeSeat(seat) }
        )
        Spacer(Modifier.height(12.dp))
        TeamGroup(
            teamName = "B",
            accent = Color(0xFF74A9C7),
            seats = listOf(1, 3),
            table = table,
            isHost = ui.isHost,
            onTake = { seat -> vm.takeSeat(seat) }
        )

        Spacer(Modifier.height(10.dp))
        Text(
            if (ui.isHost)
                "You're the host, anchoring Team A. The others choose a team above \u2014 start once all four seats are filled."
            else
                "Choose your team: tap an empty seat to join it, or a taken seat to swap. You and your partner share a team.",
            color = Cream.copy(alpha = 0.55f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        val ready = table.seats.count { it.name.isNotEmpty() && it.connected }
        if (ui.isHost) {
            Button(
                onClick = { vm.hostStart() },
                enabled = ready == 4,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(
                    if (ready == 4) "Start game" else "Waiting for players ($ready/4)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        } else {
            Text("Waiting for the host to start\u2026", color = Cream.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { vm.leaveRoom() }) { Text("Leave", color = DangerRed) }
    }
}

@Composable
private fun TeamGroup(
    teamName: String,
    accent: Color,
    seats: List<Int>,
    table: TableState,
    isHost: Boolean,
    onTake: (Int) -> Unit
) {
    val filled = seats.count { (table.seats.getOrNull(it)?.name ?: "").isNotEmpty() }
    Surface(
        color = Felt.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TEAM $teamName",
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.width(8.dp))
                Text("partners \u00B7 $filled/2", color = Cream.copy(alpha = 0.45f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            seats.forEach { seat ->
                val info = table.seats.getOrNull(seat) ?: SeatInfo()
                val canTap = !isHost && seat != 0 && seat != table.mySeat
                SeatRow(
                    seat = seat,
                    info = info,
                    isMe = seat == table.mySeat,
                    isHostSeat = seat == 0,
                    onTap = if (canTap) ({ onTake(seat) }) else null
                )
            }
        }
    }
}

@Composable
private fun SeatRow(
    seat: Int,
    info: SeatInfo,
    isMe: Boolean,
    isHostSeat: Boolean,
    onTap: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isMe) FeltLight else Felt)
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(10.dp).clip(CircleShape).background(
                when {
                    info.name.isEmpty() -> Cream.copy(alpha = 0.25f)
                    info.connected -> Color(0xFF4CAF50)
                    else -> DangerRed
                }
            )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (info.name.isEmpty()) "Empty seat" else info.name,
                color = if (info.name.isEmpty()) Cream.copy(alpha = 0.4f) else Cream,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            val tags = buildList {
                if (isHostSeat) add("host")
                if (isMe) add("you")
            }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" \u00B7 "), color = GoldBright, fontSize = 11.sp)
            }
        }
        if (onTap != null) {
            Text(
                if (info.name.isEmpty()) "sit here \u21C4" else "swap \u21C4",
                color = Gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ---------------- TOSS ----------------
@Composable
private fun TossScreen(table: TableState) {
    val toss = table.tossCards
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("TOSS", color = Gold, fontSize = 18.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        if (toss != null && toss.size == 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (0 until 4).forEach { seat ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PlayingCard(
                            card = toss[seat],
                            width = 56.dp,
                            highlight = seat == table.tossLowest
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            table.seats.getOrNull(seat)?.name ?: "P${seat + 1}",
                            color = if (seat == table.tossLowest) Gold else Cream,
                            fontSize = 12.sp,
                            fontWeight = if (seat == table.tossLowest) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(table.banner, color = Cream, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

// ---------------- TABLE ----------------
@Composable
private fun TableScreen(vm: GameViewModel, ui: Ui, table: TableState) {
    Column(Modifier.fillMaxSize()) {
        ScoreBar(table)
        if (table.banner.isNotEmpty()) {
            Text(
                table.banner,
                color = GoldBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val mySeat = table.mySeat

            (0 until 4).forEach { seat ->
                if (seat == mySeat) return@forEach
                val r = (seat - mySeat + 4) % 4
                val align = when (r) {
                    1 -> Alignment.CenterEnd
                    2 -> Alignment.TopCenter
                    else -> Alignment.CenterStart
                }
                Box(Modifier.align(align).padding(6.dp)) {
                    OpponentTile(table, seat)
                }
            }

            Box(Modifier.align(Alignment.Center)) {
                TrickArea(table)
            }

            if (table.gameMode == GameMode.DOUBLE_SIR || table.pendingPile > 0) {
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardBack(width = 22.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${table.pendingPile}",
                            color = if (table.pendingPile > 0) GoldBright else Cream.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("pile", color = Cream.copy(alpha = 0.55f), fontSize = 10.sp)
                }
            }
        }

        if (table.phase == Phase.CHOOSE_RUNG && table.mySeat == table.rungSelector) {
            ChooseRungPanel(onPick = { vm.chooseRung(it) })
        }

        StatusStrip(table)

        val myTurn = table.turn == table.mySeat
        val canPlay = table.phase == Phase.PLAYING && myTurn && !table.paused
        FanHand(
            cards = table.hand,
            enabled = canPlay,
            isPlayable = { card -> isPlayable(table, card) },
            onPlay = { vm.playCard(it) }
        )
        Spacer(Modifier.height(6.dp))
    }
}

private fun isPlayable(table: TableState, card: Card): Boolean {
    if (table.trick.isEmpty()) return true
    val lead = table.trick[0].card.suit
    val hasLead = table.hand.any { it.suit == lead }
    return if (hasLead) card.suit == lead else true
}

@Composable
private fun ScoreBar(table: TableState) {
    val myTeamA = table.mySeat % 2 == 0
    val trickNum = if (table.phase == Phase.PLAYING || table.phase == Phase.TRICK_DONE)
        minOf(table.completedTricks + 1, 13) else table.completedTricks
    Row(
        modifier = Modifier.fillMaxWidth().background(Felt).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TeamScore(
            "A", table.tricksA, table.roundsA, mine = myTeamA,
            isRung = table.rungSelector >= 0 && table.rungSelector % 2 == 0
        )
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            TrumpChip(table.trump)
            Spacer(Modifier.height(2.dp))
            Text(
                "Trick $trickNum/13 \u00B7 Round ${table.roundNumber}",
                color = Cream.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
            if (table.gameMode == GameMode.DOUBLE_SIR) {
                Text(
                    "DOUBLE SIR",
                    color = Gold.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        TeamScore(
            "B", table.tricksB, table.roundsB, mine = !myTeamA,
            isRung = table.rungSelector >= 0 && table.rungSelector % 2 == 1
        )
    }
}

@Composable
private fun TeamScore(team: String, tricks: Int, rounds: Int, mine: Boolean, isRung: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isRung) Text("\uD83D\uDC51 ", fontSize = 12.sp)
            Text(
                "Team $team" + if (mine) " (you)" else "",
                color = if (mine) Gold else Cream,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text("$tricks", color = Cream, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("rounds $rounds", color = Cream.copy(alpha = 0.55f), fontSize = 10.sp)
    }
}

@Composable
private fun TrumpChip(trump: Suit?) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(FeltDark)
            .border(1.dp, Gold, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Rung ", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (trump == null) {
            Text("\u2014", color = Cream, fontSize = 14.sp)
        } else {
            Text(
                trump.symbol,
                color = if (trump.isRed) RedSuit else Cream,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun OpponentTile(table: TableState, seat: Int) {
    val info = table.seats.getOrNull(seat) ?: SeatInfo()
    val isTurn = table.turn == seat
    val count = table.handCounts.getOrElse(seat) { 0 }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isTurn) FeltLight else Felt)
            .border(
                width = if (isTurn) 2.dp else 0.dp,
                color = if (isTurn) Gold else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardBack(width = 20.dp)
            Spacer(Modifier.width(4.dp))
            Text("\u00D7$count", color = Cream, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (seat == table.rungSelector) Text("\uD83D\uDC51 ", fontSize = 11.sp)
            Text(
                if (info.name.isEmpty()) "P${seat + 1}" else info.name,
                color = if (isTurn) GoldBright else Cream,
                fontSize = 13.sp,
                fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal
            )
        }
        if (!info.connected && info.name.isNotEmpty()) {
            Text("offline", color = DangerRed, fontSize = 10.sp)
        }
        if (table.gameMode == GameMode.DOUBLE_SIR && table.streakSeat == seat &&
            (table.phase == Phase.PLAYING || table.phase == Phase.TRICK_DONE)
        ) {
            Text("\u25C6 won last trick", color = GoldBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TrickArea(table: TableState) {
    val mySeat = table.mySeat
    Box(
        modifier = Modifier.size(width = 200.dp, height = 200.dp),
        contentAlignment = Alignment.Center
    ) {
        table.trick.forEach { pc ->
            val r = (pc.seat - mySeat + 4) % 4
            val align = when (r) {
                0 -> Alignment.BottomCenter
                1 -> Alignment.CenterEnd
                2 -> Alignment.TopCenter
                else -> Alignment.CenterStart
            }
            val isWin = table.phase == Phase.TRICK_DONE && pc.seat == table.trickWinner
            Box(Modifier.align(align)) {
                PlayingCard(card = pc.card, width = 50.dp, highlight = isWin)
            }
        }
    }
}

@Composable
private fun StatusStrip(table: TableState) {
    val myTurn = table.turn == table.mySeat
    val text: String
    val color: Color
    when {
        table.phase == Phase.CHOOSE_RUNG && table.mySeat == table.rungSelector -> {
            text = "Choose the Rung suit"; color = GoldBright
        }
        table.phase == Phase.CHOOSE_RUNG -> {
            val nm = table.seats.getOrNull(table.rungSelector)?.name ?: "Selector"
            text = "$nm is choosing Rung\u2026"; color = Cream
        }
        table.phase == Phase.PLAYING && myTurn -> {
            text = "Your turn"; color = GoldBright
        }
        table.phase == Phase.PLAYING -> {
            val nm = table.seats.getOrNull(table.turn)?.name ?: "\u2026"
            text = "$nm's turn"; color = Cream.copy(alpha = 0.8f)
        }
        else -> {
            text = ""; color = Cream
        }
    }
    if (text.isNotEmpty()) {
        Text(
            text,
            color = color,
            fontSize = 15.sp,
            fontWeight = if (myTurn) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
    }
    if (table.phase == Phase.PLAYING && myTurn && table.trick.isNotEmpty()) {
        val lead = table.trick[0].card.suit
        if (table.hand.any { it.suit == lead }) {
            Text(
                "Follow ${lead.displayName} ${lead.symbol}",
                color = Cream.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (table.gameMode == GameMode.DOUBLE_SIR && table.phase == Phase.PLAYING &&
        table.streakSeat == table.mySeat
    ) {
        val tricksAfter = (table.pendingPile + 4) / 4
        val wouldClaim = table.firstClaimDone ||
            tricksAfter >= DoubleSirTracker.FIRST_CLAIM_MIN_TRICKS
        Text(
            if (wouldClaim)
                "You won the last trick \u2014 win this one to claim the pile (${table.pendingPile + 4} cards)"
            else
                "You won the last trick \u2014 first lift needs " +
                    "${DoubleSirTracker.FIRST_CLAIM_MIN_TRICKS} tricks in the pile " +
                    "($tricksAfter after this one)",
            color = GoldBright,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun ChooseRungPanel(onPick: (Suit) -> Unit) {
    Surface(
        color = FeltDark,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Select Rung (trump)", color = Gold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Suit.entries.forEach { suit ->
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Cream)
                            .border(2.dp, Gold, RoundedCornerShape(10.dp))
                            .clickable { onPick(suit) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            suit.symbol,
                            color = if (suit.isRed) RedSuit else BlackSuit,
                            fontSize = 30.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundOverOverlay(vm: GameViewModel, ui: Ui, table: TableState) {
    val r = table.roundResult ?: return
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC07211A)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Felt,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(2.dp, Gold),
            modifier = Modifier.padding(28.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (r.kind) {
                    "COURT" -> {
                        Text("\uD83D\uDC51", fontSize = 40.sp)
                        Text(
                            "COURT", color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif, letterSpacing = 4.sp
                        )
                        Text(
                            "Team ${r.winnerTeam} swept all 13 tricks",
                            color = Cream, fontSize = 14.sp, textAlign = TextAlign.Center
                        )
                    }
                    "GC" -> {
                        Text("\uD83E\uDDF9", fontSize = 40.sp)
                        Text(
                            "GC", color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif, letterSpacing = 4.sp
                        )
                        Text(
                            if (table.gameMode == GameMode.DOUBLE_SIR)
                                "Rung team claimed nothing \u2014 Team ${r.winnerTeam} wins"
                            else
                                "Rung team took zero \u2014 Team ${r.winnerTeam} wins",
                            color = Cream, fontSize = 14.sp, textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        Text("Round ${table.roundNumber}", color = Gold, fontSize = 16.sp)
                        Text(
                            "Team ${r.winnerTeam} wins", color = Cream, fontSize = 26.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Tricks   A ${r.tricksA} : ${r.tricksB} B", color = Cream, fontSize = 16.sp)
                Text(
                    "Rounds   A ${table.roundsA} : ${table.roundsB} B",
                    color = Cream.copy(alpha = 0.7f), fontSize = 14.sp
                )
                Spacer(Modifier.height(10.dp))
                val nextName = table.seats.getOrNull(r.nextSelector)?.name ?: "\u2014"
                Text(
                    "Next Rung: $nextName",
                    color = GoldBright, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(20.dp))
                if (ui.isHost) {
                    Button(
                        onClick = { vm.hostNextRound() },
                        enabled = !table.paused,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text(
                            if (table.paused) "Waiting for players\u2026" else "Start next round",
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text("Waiting for the host\u2026", color = Cream.copy(alpha = 0.7f))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.leaveRoom() }) { Text("Leave game", color = DangerRed) }
            }
        }
    }
}

@Composable
private fun ConnectionBar(note: String) {
    Box(
        Modifier.fillMaxWidth().background(Color(0xDD7A2E2E)).padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(note, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PausedBar(note: String) {
    Box(
        Modifier.fillMaxWidth().background(Color(0xDD5A4A1E)).padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            note.ifEmpty { "Paused" },
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LeaveDialog(isHost: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isHost) "End the game?" else "Leave the game?") },
        text = {
            Text(
                if (isHost) "You're the host. Leaving ends the game for everyone."
                else "You can rejoin with the same code and name while the game is still going."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (isHost) "End game" else "Leave", color = DangerRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay") }
        }
    )
}
