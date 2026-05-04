package com.airplay.firetv.airplay

import android.content.Context
import android.net.wifi.WifiManager
import com.airplay.firetv.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS service broadcaster using jmDNS (Java Multicast DNS).
 *
 * WHY jmDNS instead of Android NsdManager:
 * Fire TV devices have severely limited NsdManager support. jmDNS is a pure-Java
 * implementation that works reliably across all Android TV/Fire TV devices.
 *
 * iOS discovery requirements:
 * - AirPlay service: _airplay._tcp, port 7000, with deviceid/model/srcvers txt records
 * - RAOP service: _raop._tcp, port 7000, service name = "MAC@DisplayName"
 * - MAC must be 12 hex chars (uppercase, no separators) for RAOP service name
 */
class MdnsService(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var jmdns: JmDNS? = null
    private var airplayService: ServiceInfo? = null
    private var raopService: ServiceInfo? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Registers both _airplay._tcp and _raop._tcp services.
     *
     * @param displayName  Human-readable name shown on iOS devices
     * @param macAddress   Device MAC address (any format, will be normalized)
     * @param persistentUuid  Persistent UUID for this device
     */
    fun start(
        displayName: String,
        macAddress: String,
        persistentUuid: String
    ) {
        if (jmdns != null) {
            Timber.w("mDNS already running, skipping start")
            return
        }

        serviceScope.launch {
            try {
                // Acquire multicast lock — required on Android for mDNS to receive responses
                val lock = wifiManager.createMulticastLock("airplay_mdns")
                lock.setReferenceCounted(true)
                lock.acquire()
                multicastLock = lock

                // Resolve local IP address for jmDNS binding
                val bindAddr = resolveLocalAddress()
                Timber.d("Binding jmDNS to $bindAddr")

                jmdns = JmDNS.create(bindAddr, displayName)

                // Normalize MAC: uppercase hex, no separators, exactly 12 chars
                val cleanMac = macAddress.uppercase()
                    .replace(":", "")
                    .replace("-", "")
                    .replace(".", "")
                    .take(12)
                    .padEnd(12, '0')
                val formattedMac = cleanMac.chunked(2).joinToString(":")

                // Register AirPlay service
                val airplayProps = HashMap<String, String>().apply {
                    put("deviceid", formattedMac)
                    put("features", "0x5A7FFFF7,0x1E")
                    put("model", "AppleTV5,3")
                    put("srcvers", "220.68")
                    put("vv", "2")
                    put("flags", "0x4")
                    put("pi", persistentUuid)
                    // pk = public key hash (optional, required for encrypted sessions)
                }
                airplayService = ServiceInfo.create(
                    "_airplay._tcp.local.",
                    displayName,
                    7000,
                    0,
                    0,
                    airplayProps
                )
                jmdns?.registerService(airplayService)
                Timber.i("AirPlay service registered: name='$displayName', port=7000")

                // Register RAOP (Remote Audio Output Protocol) service
                // Service name MUST be "MAC@DisplayName" for iOS to recognize it
                val raopProps = HashMap<String, String>().apply {
                    put("ch", "2")          // channels: stereo
                    put("cn", "0,1")        // audio codecs: PCM, ALAC
                    put("et", "0,3,5")      // encryption types
                    put("md", "0,1,2")      // metadata types
                    put("pw", "false")      // password protected
                    put("sm", "false")      // supports mute
                    put("sr", "44100")      // sample rate
                    put("ss", "16")         // sample size
                    put("sv", "false")      // supports volume
                    put("tp", "UDP")        // transport protocol
                    put("txtvers", "1")     // TXT record version
                    put("vn", "65537")      // protocol version
                    put("vs", "220.68")     // server version
                }
                val raopName = "${cleanMac}@${displayName}"
                raopService = ServiceInfo.create(
                    "_raop._tcp.local.",
                    raopName,
                    7000,
                    0,
                    0,
                    raopProps
                )
                jmdns?.registerService(raopService)
                Timber.i("RAOP service registered: name='$raopName', port=7000")

            } catch (e: Exception) {
                Timber.e(e, "Failed to start mDNS services")
                stop()
            }
        }
    }

    /**
     * Unregisters all mDNS services and releases resources.
     */
    fun stop() {
        try {
            airplayService?.let { jmdns?.unregisterService(it) }
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering AirPlay service")
        }
        airplayService = null

        try {
            raopService?.let { jmdns?.unregisterService(it) }
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering RAOP service")
        }
        raopService = null

        try {
            jmdns?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing jmDNS")
        }
        jmdns = null

        try {
            multicastLock?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error releasing multicast lock")
        }
        multicastLock = null

        Timber.i("mDNS services stopped")
    }

    /**
     * Resolves a suitable local IPv4 address for jmDNS binding.
     *
     * jmDNS requires a concrete local address (not 0.0.0.0). We try to find
     * the WiFi or Ethernet interface that has a valid non-loopback IPv4 address.
     */
    private fun resolveLocalAddress(): InetAddress {
        return try {
            val ip = NetworkUtils.getLocalIpAddress(appContext)
            if (ip != "127.0.0.1") {
                return InetAddress.getByName(ip)
            }

            // Fallback: scan network interfaces
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.interfaceAddresses.asSequence() }
                .map { it.address }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { !it.isLoopbackAddress && it.hostAddress != "127.0.0.1" }
                .firstOrNull()
                ?: InetAddress.getByName("127.0.0.1")

        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve local address, falling back to loopback")
            InetAddress.getByName("127.0.0.1")
        }
    }
}
