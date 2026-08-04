package com.vizvag.shieldvideo.playback.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class RemoteDeviceDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    @Volatile
    private var discovering = false

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Resolve failed ${serviceInfo.serviceName}: $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            val id = serviceInfo.attributes["id"]?.toString(Charsets.UTF_8)
                ?.takeIf { it.isNotBlank() }
                ?: serviceInfo.serviceName.trim().lowercase()
            if (id.isBlank() || port <= 0) return
            val device = RemoteDevice(deviceId = id, host = host, port = port)
            _devices.update { list ->
                (list.filterNot { it.deviceId.equals(id, true) } + device)
                    .sortedBy { it.deviceId }
            }
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            discovering = true
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discovering = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.contains("pallas", ignoreCase = true)) return
            runCatching {
                nsdManager.resolveService(serviceInfo, resolveListener)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val id = serviceInfo.serviceName.trim().lowercase()
            _devices.update { list -> list.filterNot { it.deviceId.equals(id, true) } }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            Log.w(TAG, "Start discovery failed: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Stop discovery failed: $errorCode")
        }
    }

    fun start() {
        if (discovering) return
        runCatching {
            nsdManager.discoverServices(
                RemoteControlHttpServer.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }.onFailure {
            Log.w(TAG, "discoverServices: ${it.message}")
        }
    }

    fun stop() {
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discovering = false
    }

    fun addManual(host: String, port: Int, deviceId: String) {
        val h = host.trim()
        val id = deviceId.trim().lowercase().ifBlank { h }
        if (h.isBlank() || port <= 0) return
        val device = RemoteDevice(deviceId = id, host = h, port = port)
        _devices.update { list ->
            (list.filterNot { it.deviceId.equals(id, true) } + device).sortedBy { it.deviceId }
        }
    }

    companion object {
        private const val TAG = "RemoteDiscovery"
    }
}
