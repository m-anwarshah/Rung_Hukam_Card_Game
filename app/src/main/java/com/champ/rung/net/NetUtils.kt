package com.champ.rung.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    const val BASE_PORT = 47815
    const val PORT_COUNT = 10
    val PORTS: List<Int> = (BASE_PORT until BASE_PORT + PORT_COUNT).toList()

    /** Site-local IPv4 addresses, preferring 192.168.* (typical hotspot range). */
    fun localIpAddresses(): List<String> {
        val result = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (iface in ifaces) {
                try {
                    if (!iface.isUp || iface.isLoopback) continue
                } catch (_: Exception) {
                    continue
                }
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { if (!result.contains(it)) result.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.sortedByDescending { it.startsWith("192.168.") }
    }
}
