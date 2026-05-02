package com.airplay.firetv.util

import android.content.Context
import android.net.wifi.WifiManager
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

object NetworkUtils {

    fun getMacAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val macFromWifi = wifiManager?.connectionInfo?.macAddress
        if (!macFromWifi.isNullOrBlank() && macFromWifi != "02:00:00:00:00:00") {
            return macFromWifi.replace(":", "").uppercase()
        }

        // Fallback: read from NetworkInterface
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.name.startsWith("wlan") || !it.isLoopback }
                .flatMap { it.hardwareAddress?.asIterable() ?: emptyList() }
                .take(6)
                .toList()
                .takeIf { it.size == 6 }
                ?.joinToString("") { "%02X".format(it) }
                ?: "A1B2C3D4E5F6"
        } catch (e: Exception) {
            Timber.e(e, "Failed to get MAC address")
            "A1B2C3D4E5F6"
        }
    }

    fun getLocalIpAddress(context: Context): String {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.name.startsWith("wlan") || !it.isLoopback }
                .flatMap { it.interfaceAddresses.asSequence() }
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
                ?: "127.0.0.1"
        } catch (e: Exception) {
            Timber.e(e, "Failed to get local IP")
            "127.0.0.1"
        }
    }

    fun generatePersistentUuid(macAddress: String): UUID {
        val seed = macAddress.uppercase().replace(":", "")
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))
    }
}
