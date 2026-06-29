package com.champ.rung.net

import com.champ.rung.model.Msg
import com.champ.rung.model.WireJson
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** One connected remote player. [seat] is assigned by the controller (-1 until seated). */
class ClientConn(val socket: Socket, val id: Int) {
    @Volatile var seat: Int = -1

    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    @Synchronized
    fun send(msg: Msg): Boolean = try {
        writer.write(WireJson.encodeToString(Msg.serializer(), msg))
        writer.write("\n")
        writer.flush()
        true
    } catch (_: Exception) {
        false
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

class HostServer(
    private val onClientMsg: (ClientConn, Msg) -> Unit,
    private val onClientGone: (ClientConn) -> Unit
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val idGen = AtomicInteger(1)

    var port: Int = -1
        private set

    /** Bind the first free port in the range. Returns the port, or -1 if none free. */
    fun start(): Int {
        for (p in NetUtils.PORTS) {
            try {
                server = ServerSocket(p)
                port = p
                break
            } catch (_: BindException) {
                // try next
            } catch (_: Exception) {
                // try next
            }
        }
        val s = server ?: return -1
        running = true
        Thread {
            while (running) {
                val sock = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                try { sock.tcpNoDelay = true } catch (_: Exception) {}
                val conn = ClientConn(sock, idGen.getAndIncrement())
                startReadLoop(conn)
            }
        }.apply { isDaemon = true; start() }
        return port
    }

    private fun startReadLoop(conn: ClientConn) {
        Thread {
            try {
                val reader = BufferedReader(
                    InputStreamReader(conn.socket.getInputStream(), Charsets.UTF_8)
                )
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val msg = try {
                        WireJson.decodeFromString(Msg.serializer(), line)
                    } catch (_: Exception) {
                        continue
                    }
                    onClientMsg(conn, msg)
                }
            } catch (_: Exception) {
                // fall through
            } finally {
                conn.close()
                onClientGone(conn)
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }
}
