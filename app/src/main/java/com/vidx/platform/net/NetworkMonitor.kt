package com.vidx.platform.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.vidx.app.App

/**
 * Connectivity watcher: feeds the download engine so it can pause on network
 * loss / Wi-Fi-only policy and resume when connectivity returns.
 */
class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun start() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            // Seed the engine with the true current state instead of a guess.
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                App.instance.engine.onNetworkChanged(true, currentIsWifi())
            } else {
                App.instance.engine.onNetworkChanged(false, false)
            }
        } catch (e: Exception) {
            App.instance.engine.onNetworkChanged(true, false)
        }
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) {}
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            App.instance.engine.onNetworkChanged(true, currentIsWifi())
        }

        override fun onLost(network: Network) {
            App.instance.engine.onNetworkChanged(false, false)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            App.instance.engine.onNetworkChanged(true, caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        }
    }

    private fun currentIsWifi(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
