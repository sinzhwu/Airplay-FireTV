package com.airplay.firetv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.airplay.firetv.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.i("Boot completed, checking auto-start setting")

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            try {
                val repository = SettingsRepository(context)
                val settings = repository.settings.first()

                if (settings.autoStart) {
                    Timber.i("Auto-start enabled, launching PhairPlayService")
                    val serviceIntent = Intent(context, PhairPlayService::class.java).apply {
                        putExtra(PhairPlayService.EXTRA_DISPLAY_NAME, settings.displayName)
                        putExtra(PhairPlayService.EXTRA_AUTO_START, true)
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } else {
                    Timber.i("Auto-start disabled, skipping")
                }
            } catch (e: Exception) {
                Timber.e(e, "Boot receiver error")
            }
        }
    }
}
