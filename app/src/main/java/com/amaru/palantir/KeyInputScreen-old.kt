package com.amaru.palantir

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*

@Composable
fun KeyInputScreen(
    onKeySaved: (String) -> Unit
) {
    var apiKeyText by remember { mutableStateOf("") }

    // Lanzador para invocar el teclado/dictado nativo de Wear OS
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Bundle? = RemoteInput.getResultsFromIntent(result.data)
        val input = results?.getCharSequence("input_key")?.toString()
            ?: result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.getOrNull(0)

        if (!input.isNullOrBlank()) {
            apiKeyText = input.trim()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "OpenAI API Key",
                style = MaterialTheme.typography.title3,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Chip para abrir el teclado/voz nativo del sistema
            Chip(
                onClick = {
                    val intent = Intent("android.settings.VOICE_INPUT_SETTINGS").apply {
                        // O lanza el selector de texto nativo
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    }
                    // Alternativa: Si usas Wear Remote Input
                    val remoteInputIntent = Intent("androidx.wear.input.action.REMOTE_INPUT")
                    launcher.launch(remoteInputIntent)
                },
                label = {
                    Text(
                        text = if (apiKeyText.isBlank()) "Toca para ingresar clave" else "sk-...${apiKeyText.takeLast(4)}",
                        maxLines = 1
                    )
                },
                colors = ChipDefaults.primaryChipColors()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (apiKeyText.isNotBlank()) {
                        onKeySaved(apiKeyText)
                    }
                },
                enabled = apiKeyText.isNotBlank()
            ) {
                Text("OK")
            }
        }
    }
}