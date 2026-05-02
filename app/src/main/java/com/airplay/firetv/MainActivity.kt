package com.airplay.firetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.airplay.firetv.airplay.ReceiverState
import com.airplay.firetv.service.ServiceController
import com.airplay.firetv.ui.screens.StreamingScreen
import com.airplay.firetv.ui.screens.WaitingScreen
import com.airplay.firetv.ui.theme.AirPlayTheme
import com.airplay.firetv.ui.viewmodel.StreamingViewModel
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val viewModel: StreamingViewModel by viewModels()
    private lateinit var serviceController: ServiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MainActivity onCreate")

        serviceController = ServiceController(this)
        viewModel.bindService()

        setContent {
            AirPlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    AirPlayApp(viewModel = viewModel, serviceController = serviceController)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startService()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.unbindService()
        Timber.i("MainActivity onDestroy")
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AirPlayApp(
    viewModel: StreamingViewModel,
    serviceController: ServiceController
) {
    val receiverState by viewModel.receiverState.collectAsStateWithLifecycle(ReceiverState.IDLE)

    LaunchedEffect(Unit) {
        Timber.d("AirPlayApp composition started")
    }

    when (receiverState) {
        ReceiverState.STREAMING -> {
            StreamingScreen(
                serviceController = serviceController,
                modifier = Modifier.fillMaxSize()
            )
        }

        else -> {
            WaitingScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
