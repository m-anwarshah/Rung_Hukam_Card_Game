package com.champ.rung.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.champ.rung.GameViewModel
import com.champ.rung.Screen
import com.champ.rung.model.GameMode
import com.champ.rung.ui.theme.Cream
import com.champ.rung.ui.theme.DangerRed
import com.champ.rung.ui.theme.DisplayFont
import com.champ.rung.ui.theme.Felt
import com.champ.rung.ui.theme.FeltDark
import com.champ.rung.ui.theme.Gold

@Composable
fun RungApp(vm: GameViewModel) {
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.toasts.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = FeltDark,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (ui.screen) {
                Screen.HOME -> HomeScreen(
                    name = ui.myName,
                    onName = vm::setName,
                    onCreate = { mode -> vm.createRoom(mode) },
                    onJoin = vm::openJoin
                )
                Screen.JOIN -> JoinScreen(vm)
                Screen.ROOM -> RoomScreen(vm)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    name: String,
    onName: (String) -> Unit,
    onCreate: (GameMode) -> Unit,
    onJoin: () -> Unit
) {
    var mode by rememberSaveable { mutableStateOf(GameMode.DOUBLE_SIR) }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp).verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("\u2660", fontSize = 60.sp, color = Gold)
        Text(
            "RUNG",
            fontSize = 50.sp,
            fontWeight = FontWeight.Bold,
            color = Cream,
            letterSpacing = 8.sp,
            fontFamily = DisplayFont
        )
        Text("Court Piece \u00B7 Hukam", color = Gold, fontSize = 15.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(22.dp))

        // Host a game: the game type is chosen HERE, by the host only.
        Surface(
            color = Felt.copy(alpha = 0.45f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Gold.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "HOST A GAME",
                    color = Gold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose the game type, then create the room. Only the host picks this \u2014 everyone else just joins.",
                    color = Cream.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModeCard(
                        title = "Single Sir",
                        detail = "Every trick scores the moment it's won.",
                        selected = mode == GameMode.SINGLE_SIR,
                        modifier = Modifier.weight(1f)
                    ) { mode = GameMode.SINGLE_SIR }
                    ModeCard(
                        title = "Double Sir",
                        detail = "Tricks pile up \u2014 win 2 in a row to claim them.",
                        selected = mode == GameMode.DOUBLE_SIR,
                        modifier = Modifier.weight(1f)
                    ) { mode = GameMode.DOUBLE_SIR }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onCreate(mode) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Create room", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Cream.copy(alpha = 0.15f))
            Text("  or  ", color = Cream.copy(alpha = 0.5f), fontSize = 13.sp)
            HorizontalDivider(modifier = Modifier.weight(1f), color = Cream.copy(alpha = 0.15f))
        }
        Spacer(Modifier.height(18.dp))

        // Join a game: no mode choice here; team is chosen in the lobby.
        OutlinedButton(
            onClick = onJoin,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Join a game", fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Enter a room's code, then pick your team in the lobby.",
            color = Cream.copy(alpha = 0.5f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeCard(
    title: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) Felt else FeltDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) Gold else Cream.copy(alpha = 0.25f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                title,
                color = if (selected) Gold else Cream,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                detail,
                color = Cream.copy(alpha = if (selected) 0.85f else 0.55f),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun JoinScreen(vm: GameViewModel) {
    val ui by vm.ui.collectAsState()
    BackHandler { vm.closeJoin() }

    var code by rememberSaveable { mutableStateOf("") }
    var ip by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scroll)
    ) {
        Text(
            "Join a room",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Cream,
            fontFamily = DisplayFont
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) code = it },
            label = { Text("Room code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Text("Rooms nearby", color = Gold, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        if (ui.discovered.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                    color = Gold
                )
                Spacer(Modifier.width(10.dp))
                Text("Searching\u2026", color = Cream.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        } else {
            ui.discovered.forEach { room ->
                Surface(
                    color = Felt,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !ui.joining) { vm.join(room.code, room.host, room.port) }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Room ${room.code}",
                                color = Cream,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(room.host, color = Cream.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        Text("Join \u2192", color = Gold, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = Cream.copy(alpha = 0.15f))
        Spacer(Modifier.height(20.dp))

        Text("Or join by host IP", color = Gold, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("Host IP (e.g. 192.168.43.1)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.join(code, ip) },
            enabled = !ui.joining,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Connect", fontWeight = FontWeight.Bold)
        }

        if (ui.joinStatus.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                ui.joinStatus,
                color = if (ui.joining) Cream else DangerRed,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { vm.closeJoin() }) {
            Text("Back", color = Cream.copy(alpha = 0.7f))
        }
    }
}
