package com.airplay.firetv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.airplay.firetv.R
import com.airplay.firetv.airplay.AirPlayReceiver
import com.airplay.firetv.airplay.ReceiverState
import com.airplay.firetv.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class PhairPlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var receiver: AirPlayReceiver? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _receiverState = MutableStateFlow(ReceiverState.IDLE)
    val receiverState: StateFlow<ReceiverState> = _receiverState.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): PhairPlayService = this@PhairPlayService
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("PhairPlayService onCreate")
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("PhairPlayService onStartCommand")
        val settings = AppSettings(
            displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: "FireTV AirPlay",
            autoStart = intent?.getBooleanExtra(EXTRA_AUTO_START, true) ?: true
        )
        startService(settings)
        return START_STICKY
    }

    fun startService(settings: AppSettings) {
        if (_isRunning.value) {
            Timber.w("Service already running")
            return
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))
            _isRunning.value = true

            receiver = AirPlayReceiver(this).apply {
                start(settings)

                serviceScope.launch {
                    state.collectLatest { state ->
                        _receiverState.value = state
                        updateNotification(state)
                    }
                }

                serviceScope.launch {
                    streaming.collectLatest { isStreaming ->
                        _streaming.value = isStreaming
                    }
                }
            }

            Timber.i("PhairPlayService started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start service")
            stopService()
        }
    }

    fun stopService() {
        Timber.i("PhairPlayService stopping...")

        receiver?.release()
        receiver = null

        _isRunning.value = false
        _receiverState.value = ReceiverState.IDLE
        _streaming.value = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setSurface(surface: Surface?) {
        receiver?.setOutputSurface(surface)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("PhairPlayService onDestroy")
        receiver?.release()
        receiver = null
        serviceScope.cancel()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirPlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps AirPlay receiver running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FireTV AirPlay")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(state: ReceiverState) {
        val text = when (state) {
            ReceiverState.IDLE -> "Idle"
            ReceiverState.ADVERTISING -> "Waiting for connection..."
            ReceiverState.CONNECTED -> "Client connected"
            ReceiverState.STREAMING -> "Streaming video"
            ReceiverState.ERROR -> "Error occurred"
        }

        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_AUTO_START = "auto_start"
    }
}
