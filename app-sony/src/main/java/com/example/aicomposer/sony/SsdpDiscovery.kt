package com.example.aicomposer.sony

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Finds the Sony Camera Remote API endpoint on the camera's Wi-Fi via SSDP. */
object SsdpDiscovery {
    /** Fallback address when discovery fails (used by most Sony cameras). */
    const val FALLBACK_ENDPOINT = "http://192.168.122.1:8080/sony/camera"

    private const val SEARCH =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n\r\n"

    suspend fun discover(context: Context, network: Network?, client: OkHttpClient): String? =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi.createMulticastLock("ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                val location = findLocation(network) ?: return@withContext null
                val xml = client.newCall(Request.Builder().url(location).build()).execute()
                    .use { it.body?.string() } ?: return@withContext null
                parseCameraEndpoint(xml)
            } catch (e: Exception) {
                null
            } finally {
                lock.release()
            }
        }

    private fun findLocation(network: Network?): String? {
        DatagramSocket().use { socket ->
            network?.bindSocket(socket)
            socket.soTimeout = 1500
            val data = SEARCH.toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            repeat(3) {
                socket.send(DatagramPacket(data, data.size, group, 1900))
                val buf = ByteArray(4096)
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val loc = Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1)
                    if (loc != null) return loc
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return null
    }

    private fun parseCameraEndpoint(xml: String): String? {
        val services = Regex(
            "<av:X_ScalarWebAPI_Service>(.*?)</av:X_ScalarWebAPI_Service>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(xml)
        for (s in services) {
            val block = s.groupValues[1]
            val type = Regex("<av:X_ScalarWebAPI_ServiceType>\\s*(.*?)\\s*<").find(block)?.groupValues?.get(1)
            val url = Regex("<av:X_ScalarWebAPI_ActionList_URL>\\s*(.*?)\\s*<").find(block)?.groupValues?.get(1)
            if (type == "camera" && url != null) return url.trimEnd('/') + "/camera"
        }
        return null
    }
}
