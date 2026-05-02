package com.airplay.firetv

import android.app.Application
import com.airplay.firetv.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bouncycastle.jce.provider.BouncyCastleProvider
import timber.log.Timber
import java.security.Security

class AirPlayApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Register Bouncy Castle provider for AES-CTR and RSA operations
        Security.addProvider(BouncyCastleProvider())

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Initialize settings repository
        settingsRepository = SettingsRepository(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}