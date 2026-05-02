package com.airplay.firetv.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import com.airplay.firetv.airplay.ReceiverState
import com.airplay.firetv.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

class ServiceController(private val context: Context) {

    private var service: PhairPlayService? = null
    private var isBound = false

    val isRunning: StateFlow<Boolean>?
        get() = service?.isRunning

    val receiverState: StateFlow<ReceiverState>?
        get() = service?.receiverState

    val streaming: StateFlow<Boolean>?
        get() = service?.streaming

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? PhairPlayService.LocalBinder
            service = localBinder?.getService()
            Timber.i("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Timber.i("Service disconnected")
        }
    }

    fun bind() {
        if (isBound) return
        val intent = Intent(context, PhairPlayService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = true
        Timber.d("Binding to service")
    }

    fun unbind() {
        if (!isBound) return
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            Timber.w(e, "Error unbinding service")
        }
        isBound = false
        service = null
        Timber.d("Unbound from service")
    }

    fun start(settings: AppSettings) {
        val intent = Intent(context, PhairPlayService::class.java).apply {
            putExtra(PhairPlayService.EXTRA_DISPLAY_NAME, settings.displayName)
            putExtra(PhairPlayService.EXTRA_AUTO_START, settings.autoStart)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        bind()
        Timber.i("Service start requested")
    }

    fun stop() {
        service?.stopService()
        unbind()
        Timber.i("Service stop requested")
    }

    fun setSurface(surface: Surface?) {
        service?.setSurface(surface)
    }

    fun isServiceRunning(): Boolean {
        return service?.isRunning?.value == true
    }
}
