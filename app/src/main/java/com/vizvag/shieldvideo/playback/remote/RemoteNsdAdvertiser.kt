package com.vizvag.shieldvideo.playback.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class RemoteNsdAdvertiser(context: Context) {
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var registered = false

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registered = true
            Log.i(TAG, "NSD registered: ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            registered = false
            Log.w(TAG, "NSD register failed: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            registered = false
            Log.i(TAG, "NSD unregistered: ${serviceInfo.serviceName}")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "NSD unregister failed: $errorCode")
        }
    }

    fun register(deviceId: String, port: Int) {
        unregister()
        val id = deviceId.trim().lowercase().ifBlank { "pallas" }
        if (port <= 0) return
        val info = NsdServiceInfo().apply {
            serviceName = id
            serviceType = RemoteControlHttpServer.SERVICE_TYPE
            setPort(port)
            setAttribute("id", id)
            setAttribute("ver", "1")
        }
        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure {
            Log.w(TAG, "registerService: ${it.message}")
        }
    }

    fun unregister() {
        if (!registered) return
        runCatching {
            nsdManager.unregisterService(registrationListener)
        }
        registered = false
    }

    companion object {
        private const val TAG = "RemoteNsd"
    }
}
