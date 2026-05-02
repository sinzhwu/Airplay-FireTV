package com.airplay.firetv.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

class MdnsService(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var airplayRegistration: NsdManager.RegistrationListener? = null
    private var raopRegistration: NsdManager.RegistrationListener? = null

    private val _state = MutableStateFlow<MdnsState>(MdnsState.Stopped)
    val state: StateFlow<MdnsState> = _state

    fun start(
        displayName: String,
        macAddress: String,
        persistentUuid: String
    ) {
        if (_state.value is MdnsState.Running) {
            Timber.w("mDNS service already running")
            return
        }

        _state.value = MdnsState.Starting

        // Acquire multicast lock
        val lock = wifiManager.createMulticastLock("airplay_mdns")
        lock.setReferenceCounted(true)
        lock.acquire()
        multicastLock = lock

        // Register AirPlay service
        val airplayInfo = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = "_airplay._tcp"
            port = 7000
            setAttribute("deviceid", macAddress)
            setAttribute("features", "0x5A7FFFF7,0x1E")
            setAttribute("model", "AppleTV5,3")
            setAttribute("srcvers", "220.68")
            setAttribute("vv", "2")
            setAttribute("flags", "0x4")
            setAttribute("pi", persistentUuid)
        }

        val airplayListener = createRegistrationListener("airplay")
        airplayRegistration = airplayListener
        nsdManager.registerService(airplayInfo, NsdManager.PROTOCOL_DNS_SD, airplayListener)

        // Register RAOP service
        val raopInfo = NsdServiceInfo().apply {
            serviceName = "${macAddress}@${displayName}"
            serviceType = "_raop._tcp"
            port = 7000
            setAttribute("ch", "2")
            setAttribute("cn", "0,1")
            setAttribute("et", "0,3,5")
            setAttribute("md", "0,1,2")
            setAttribute("pw", "false")
            setAttribute("sm", "false")
            setAttribute("sr", "44100")
            setAttribute("ss", "16")
            setAttribute("sv", "false")
            setAttribute("tp", "UDP")
            setAttribute("txtvers", "1")
            setAttribute("vn", "65537")
            setAttribute("vs", "220.68")
        }

        val raopListener = createRegistrationListener("raop")
        raopRegistration = raopListener
        nsdManager.registerService(raopInfo, NsdManager.PROTOCOL_DNS_SD, raopListener)
    }

    fun stop() {
        try {
            airplayRegistration?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to unregister airplay service")
        }
        airplayRegistration = null

        try {
            raopRegistration?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to unregister raop service")
        }
        raopRegistration = null

        multicastLock?.release()
        multicastLock = null

        _state.value = MdnsState.Stopped
    }

    private fun createRegistrationListener(type: String): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Timber.d("$type service registered: ${info.serviceName}")
                if (type == "airplay") {
                    _state.value = MdnsState.Running(info.serviceName)
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("$type registration failed: $errorCode")
                if (type == "airplay") {
                    _state.value = MdnsState.Error("Registration failed: $errorCode")
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Timber.d("$type service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("$type unregistration failed: $errorCode")
            }
        }
    }

    sealed class MdnsState {
        object Stopped : MdnsState()
        object Starting : MdnsState()
        data class Running(val registeredName: String) : MdnsState()
        data class Error(val message: String) : MdnsState()
    }
}
