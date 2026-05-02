package com.airplay.firetv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airplay.firetv.airplay.ReceiverState
import com.airplay.firetv.service.ServiceController
import com.airplay.firetv.settings.AppSettings
import com.airplay.firetv.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class StreamingViewModel(application: Application) : AndroidViewModel(application) {

    private val serviceController = ServiceController(application)
    private val settingsRepository = SettingsRepository(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val receiverState: StateFlow<ReceiverState>?
        get() = serviceController.receiverState

    val streaming: StateFlow<Boolean>?
        get() = serviceController.streaming

    val isRunning: StateFlow<Boolean>?
        get() = serviceController.isRunning

    init {
        Timber.d("StreamingViewModel initialized")
    }

    fun startService() {
        viewModelScope.launch {
            try {
                val currentSettings = settings.value
                serviceController.start(currentSettings)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start service")
            }
        }
    }

    fun stopService() {
        serviceController.stop()
    }

    fun updateSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            settingsRepository.update { newSettings }
        }
    }

    fun bindService() {
        serviceController.bind()
    }

    fun unbindService() {
        serviceController.unbind()
    }

    override fun onCleared() {
        super.onCleared()
        serviceController.unbind()
        Timber.d("StreamingViewModel cleared")
    }
}
