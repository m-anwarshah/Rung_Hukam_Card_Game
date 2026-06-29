package com.champ.rung.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

/**
 * mDNS/NSD discovery so joiners can find a host on the same Wi-Fi without typing an IP.
 * Manual-IP join remains as a fallback because NSD can be flaky on some hotspots.
 */
class NsdHelper(context: Context) {

    data class FoundRoom(val code: String, val host: String, val port: Int)

    companion object {
        const val SERVICE_TYPE = "_rung._tcp."
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun register(code: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "Rung-$code"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(p0: NsdServiceInfo?) {}
            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
            override fun onServiceUnregistered(p0: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
        }
        registrationListener = listener
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    fun unregister() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrationListener = null
    }

    @Suppress("DEPRECATION")
    fun startDiscovery(onFound: (FoundRoom) -> Unit) {
        stopDiscovery()
        try {
            multicastLock = wifiManager.createMulticastLock("rung-nsd").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(p0: String?, p1: Int) {}
            override fun onStopDiscoveryFailed(p0: String?, p1: Int) {}
            override fun onDiscoveryStarted(p0: String?) {}
            override fun onDiscoveryStopped(p0: String?) {}
            override fun onServiceLost(p0: NsdServiceInfo?) {}
            override fun onServiceFound(info: NsdServiceInfo?) {
                info ?: return
                if (info.serviceType?.contains("_rung._tcp") != true) return
                try {
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(p0: NsdServiceInfo?, p1: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo?) {
                            resolved ?: return
                            val name = resolved.serviceName ?: return
                            val code = name.substringAfter("Rung-", "")
                            val hostAddr = resolved.host?.hostAddress ?: return
                            val port = resolved.port
                            if (code.isNotEmpty() && port > 0) {
                                onFound(FoundRoom(code, hostAddr, port))
                            }
                        }
                    })
                } catch (_: Exception) {
                }
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
        multicastLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) {}
        }
        multicastLock = null
    }
}
