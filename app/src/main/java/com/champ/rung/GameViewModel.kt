package com.champ.rung

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.champ.rung.engine.HostGameController
import com.champ.rung.model.Card
import com.champ.rung.model.Msg
import com.champ.rung.model.Suit
import com.champ.rung.model.TableState
import com.champ.rung.net.GameClient
import com.champ.rung.net.HostServer
import com.champ.rung.net.NetUtils
import com.champ.rung.net.NsdHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

enum class Screen { HOME, JOIN, ROOM }

data class DiscoveredRoom(val code: String, val host: String, val port: Int)

data class Ui(
    val screen: Screen = Screen.HOME,
    val myName: String = "",
    val isHost: Boolean = false,
    val mySeat: Int = -1,
    val table: TableState? = null,
    val connectionNote: String = "",
    val discovered: List<DiscoveredRoom> = emptyList(),
    val joinStatus: String = "",
    val joining: Boolean = false
)

class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts = _toasts.asSharedFlow()

    private val nsd = NsdHelper(app)

    private var controller: HostGameController? = null
    private var server: HostServer? = null
    private var client: GameClient? = null

    private fun toast(text: String) { _toasts.tryEmit(text) }

    // ---------------- HOME ----------------
    fun setName(name: String) { _ui.update { it.copy(myName = name) } }

    // ---------------- HOST ----------------
    fun createRoom() {
        val name = _ui.value.myName.trim().ifEmpty { "Player 1" }
        val code = Random.nextInt(1000, 10000).toString()
        val ips = NetUtils.localIpAddresses()

        val ctrl = HostGameController(
            scope = viewModelScope,
            roomCode = code,
            hostName = name,
            hostIps = ips,
            callbacks = object : HostGameController.Callbacks {
                override fun onHostState(table: TableState) {
                    _ui.update { it.copy(table = table, mySeat = 0) }
                }
                override fun onHostToast(text: String) { toast(text) }
            }
        )
        val srv = HostServer(
            onClientMsg = { conn, msg -> ctrl.post(HostGameController.Action.FromClient(conn, msg)) },
            onClientGone = { conn -> ctrl.post(HostGameController.Action.Gone(conn)) }
        )
        val boundPort = srv.start()
        if (boundPort <= 0) {
            toast("Couldn't start the host (no free port). Try again.")
            ctrl.stop()
            return
        }
        controller = ctrl
        server = srv
        nsd.register(code, boundPort)
        ctrl.post(HostGameController.Action.Refresh)
        _ui.update { it.copy(screen = Screen.ROOM, isHost = true, myName = name, mySeat = 0) }
    }

    fun hostStart() { controller?.post(HostGameController.Action.StartGame) }
    fun hostNextRound() { controller?.post(HostGameController.Action.NextRound) }

    // ---------------- JOIN ----------------
    fun openJoin() {
        val name = _ui.value.myName.trim().ifEmpty { "Player" }
        _ui.update {
            it.copy(screen = Screen.JOIN, myName = name, discovered = emptyList(), joinStatus = "")
        }
        nsd.startDiscovery { room ->
            _ui.update { st ->
                val exists = st.discovered.any { it.host == room.host && it.code == room.code }
                if (exists) st
                else st.copy(discovered = st.discovered + DiscoveredRoom(room.code, room.host, room.port))
            }
        }
    }

    fun closeJoin() {
        nsd.stopDiscovery()
        client?.close()
        client = null
        _ui.update { it.copy(screen = Screen.HOME, joining = false, joinStatus = "") }
    }

    fun join(code: String, host: String, knownPort: Int? = null) {
        val name = _ui.value.myName.trim().ifEmpty { "Player" }
        if (code.length != 4 || code.any { !it.isDigit() }) {
            _ui.update { it.copy(joinStatus = "Enter the 4-digit room code.") }
            return
        }
        if (host.isBlank()) {
            _ui.update { it.copy(joinStatus = "Pick a room or enter the host IP.") }
            return
        }
        nsd.stopDiscovery()
        val ports = if (knownPort != null)
            listOf(knownPort) + NetUtils.PORTS.filter { it != knownPort }
        else NetUtils.PORTS

        _ui.update { it.copy(joining = true, joinStatus = "Connecting\u2026", myName = name) }
        val c = GameClient(name, code, host.trim(), ports, clientListener)
        client = c
        c.start()
    }

    private val clientListener = object : GameClient.Listener {
        override fun onWelcome(seat: Int) {
            _ui.update {
                it.copy(
                    screen = Screen.ROOM, isHost = false, mySeat = seat,
                    joining = false, joinStatus = "", connectionNote = ""
                )
            }
        }
        override fun onState(msg: Msg.State) {
            _ui.update { it.copy(table = msg.table, mySeat = msg.table.mySeat) }
        }
        override fun onToast(text: String) { toast(text) }
        override fun onRejected(reason: String) {
            if (_ui.value.screen == Screen.ROOM) resetToHome("Removed: $reason")
            else _ui.update { it.copy(joining = false, joinStatus = reason) }
            client = null
        }
        override fun onCancelled(reason: String) {
            resetToHome(reason)
            client = null
        }
        override fun onConnectionLost() {
            _ui.update { it.copy(connectionNote = "Connection lost \u2014 reconnecting\u2026") }
        }
        override fun onReconnecting(attempt: Int, max: Int) {
            _ui.update { it.copy(connectionNote = "Reconnecting ($attempt/$max)\u2026") }
        }
        override fun onConnectionRestored() {
            _ui.update { it.copy(connectionNote = "") }
            toast("Reconnected")
        }
        override fun onGaveUp(message: String) {
            resetToHome(message)
            client = null
        }
    }

    fun chooseRung(suit: Suit) {
        if (_ui.value.isHost) controller?.post(HostGameController.Action.HostChoseRung(suit))
        else client?.sendChooseRung(suit)
    }

    fun playCard(card: Card) {
        if (_ui.value.isHost) controller?.post(HostGameController.Action.HostPlayed(card))
        else client?.sendPlay(card)
    }

    // ---------------- leaving ----------------
    fun leaveRoom() {
        if (_ui.value.isHost) {
            val ctrl = controller
            val srv = server
            controller = null
            server = null
            nsd.unregister()
            val t = ctrl?.shutdownNowThread("Host ended the game")
            Thread {
                try { t?.join(1200) } catch (_: Exception) {}
                ctrl?.stop()
                srv?.stop()
            }.apply { isDaemon = true; start() }
        } else {
            client?.close()
            client = null
        }
        nsd.stopDiscovery()
        _ui.update { Ui(myName = it.myName) }
    }

    private fun resetToHome(message: String) {
        if (_ui.value.isHost) {
            val ctrl = controller
            val srv = server
            controller = null
            server = null
            nsd.unregister()
            ctrl?.shutdownNowThread(message)
            Thread { ctrl?.stop(); srv?.stop() }.apply { isDaemon = true; start() }
        }
        nsd.stopDiscovery()
        _ui.update { Ui(myName = it.myName) }
        toast(message)
    }

    override fun onCleared() {
        super.onCleared()
        val ctrl = controller
        val srv = server
        if (ctrl != null) {
            val t = ctrl.shutdownNowThread("Host left")
            try { t.join(500) } catch (_: Exception) {}
            ctrl.stop()
        }
        srv?.stop()
        client?.close()
        nsd.stopDiscovery()
        nsd.unregister()
    }
}
