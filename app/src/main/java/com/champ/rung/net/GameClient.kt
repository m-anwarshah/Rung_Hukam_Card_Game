package com.champ.rung.net

import com.champ.rung.model.Card
import com.champ.rung.model.Msg
import com.champ.rung.model.Suit
import com.champ.rung.model.WireJson
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connects to a host and streams [Msg]s. Runs entirely on its own daemon thread;
 * all listener callbacks fire off that thread (the ViewModel updates thread-safe flows).
 */
class GameClient(
    private val name: String,
    private val code: String,
    private val host: String,
    private val ports: List<Int>,
    private val listener: Listener
) {
    interface Listener {
        fun onWelcome(seat: Int)
        fun onState(msg: Msg.State)
        fun onToast(text: String)
        fun onRejected(reason: String)
        fun onCancelled(reason: String)
        fun onConnectionLost()
        fun onReconnecting(attempt: Int, max: Int)
        fun onConnectionRestored()
        fun onGaveUp(message: String)
    }

    private enum class Result { OK_THEN_LOST, NO_ROUTE, REJECTED, CANCELLED, USER_CLOSED }

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var closedByUser = false
    @Volatile private var everWelcomed = false

    private val maxReconnect = 20
    private val maxInitial = 8
    private val retryDelayMs = 2500L
    private val connectTimeoutMs = 1500

    fun start() {
        Thread { runLoop() }.apply { isDaemon = true; start() }
    }

    private fun runLoop() {
        var attempt = 0
        while (!closedByUser) {
            when (connectOnce()) {
                Result.USER_CLOSED, Result.REJECTED, Result.CANCELLED -> return
                Result.OK_THEN_LOST -> {
                    if (closedByUser) return
                    attempt = 0
                    listener.onConnectionLost()
                }
                Result.NO_ROUTE -> {
                    if (closedByUser) return
                    attempt++
                    val cap = if (everWelcomed) maxReconnect else maxInitial
                    if (attempt >= cap) {
                        listener.onGaveUp(
                            if (everWelcomed) "Lost connection to the host."
                            else "Couldn't reach the host. Check the Wi-Fi/hotspot and the host IP, then try again."
                        )
                        return
                    }
                    listener.onReconnecting(attempt, cap)
                }
            }
            if (closedByUser) return
            try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) { return }
        }
    }

    private fun connectOnce(): Result {
        var sock: Socket? = null
        for (p in ports) {
            if (closedByUser) return Result.USER_CLOSED
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, p), connectTimeoutMs)
                s.tcpNoDelay = true
                sock = s
                break
            } catch (_: Exception) {
                // try next port
            }
        }
        if (sock == null) return Result.NO_ROUTE
        socket = sock

        return try {
            val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
            writer = w
            sendRaw(Msg.Hello(name, code))

            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = try {
                    WireJson.decodeFromString(Msg.serializer(), line)
                } catch (_: Exception) {
                    continue
                }
                when (msg) {
                    is Msg.Welcome -> {
                        val reconnect = everWelcomed
                        everWelcomed = true
                        listener.onWelcome(msg.seat)
                        if (reconnect) listener.onConnectionRestored()
                    }
                    is Msg.State -> listener.onState(msg)
                    is Msg.Toast -> listener.onToast(msg.text)
                    is Msg.Rejected -> {
                        listener.onRejected(msg.reason)
                        closeSocketQuietly()
                        return Result.REJECTED
                    }
                    is Msg.Cancelled -> {
                        listener.onCancelled(msg.reason)
                        closeSocketQuietly()
                        return Result.CANCELLED
                    }
                    else -> { /* ignore client->host kinds */ }
                }
            }
            closeSocketQuietly()
            if (closedByUser) Result.USER_CLOSED else Result.OK_THEN_LOST
        } catch (_: Exception) {
            closeSocketQuietly()
            if (closedByUser) Result.USER_CLOSED else Result.OK_THEN_LOST
        }
    }

    private fun sendRaw(msg: Msg) {
        val w = writer ?: return
        synchronized(w) {
            try {
                w.write(WireJson.encodeToString(Msg.serializer(), msg))
                w.write("\n")
                w.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun sendAsync(msg: Msg) {
        Thread { sendRaw(msg) }.apply { isDaemon = true; start() }
    }

    fun sendPlay(card: Card) = sendAsync(Msg.Play(card))
    fun sendChooseRung(suit: Suit) = sendAsync(Msg.ChooseRung(suit))

    private fun closeSocketQuietly() {
        try { socket?.close() } catch (_: Exception) {}
    }

    fun close() {
        closedByUser = true
        Thread { closeSocketQuietly() }.apply { isDaemon = true; start() }
    }
}
