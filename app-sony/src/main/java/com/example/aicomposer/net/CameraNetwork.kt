package com.example.aicomposer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds the Network object for "the camera's Wi-Fi".
 *
 * The camera's Wi-Fi has no internet, so Android routes requests over mobile data by default and the camera is unreachable.
 * We grab the Wi-Fi Network and route only requests to the camera through it (OkHttp socketFactory);
 * other requests (e.g. a future cloud model) can still use mobile data.
 */
class CameraNetwork(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun acquireWifi(timeoutMs: Long = 8000): Network? {
        release()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val network = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Network> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                }
                callback = cb
                cm.requestNetwork(request, cb)
            }
        }
        if (network == null) release()
        return network
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
