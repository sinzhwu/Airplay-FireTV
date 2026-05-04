package com.airplay.firetv.ui.screens

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.airplay.firetv.service.ServiceController
import timber.log.Timber

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamingScreen(
    serviceController: ServiceController,
    onShowSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var surface by remember { mutableStateOf<Surface?>(null) }

    LaunchedEffect(surface) {
        serviceController.setSurface(surface)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surface = holder.surface
                            Timber.d("Surface created")
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            surface = holder.surface
                            Timber.d("Surface changed: ${width}x${height}")
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surface = null
                            Timber.d("Surface destroyed")
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Settings button overlay - accessible via TV remote
        Button(
            onClick = onShowSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            serviceController.setSurface(null)
            Timber.d("StreamingScreen disposed, surface cleared")
        }
    }
}
