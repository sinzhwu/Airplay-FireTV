package com.airplay.firetv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import androidx.tv.material3.TextField
import com.airplay.firetv.settings.AppSettings
import com.airplay.firetv.ui.viewmodel.StreamingViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: StreamingViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings

    var displayName by remember { mutableStateOf(settings.displayName) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xDD000000)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Display Name
            Column(
                modifier = Modifier.fillMaxWidth(0.6f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Display Name",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Auto Start
            Row(
                modifier = Modifier.fillMaxWidth(0.6f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Auto Start on Boot",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Start AirPlay when device boots",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = autoStart,
                    onCheckedChange = { autoStart = it }
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = Color.Gray
                    )
                ) {
                    Text("Cancel")
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        viewModel.updateSettings(
                            AppSettings(
                                displayName = displayName,
                                autoStart = autoStart
                            )
                        )
                        onDismiss()
                    }
                ) {
                    Text("Save")
                }
            }
        }
    }
}
