package com.amaru.palantir

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class PalantirStateOld { IDLE, LISTENING, THINKING, SPEAKING }

class MainActivityold : ComponentActivity() {

    private lateinit var keyManager: `KeyManager-old`
    private lateinit var recorder: AudioRecorder
    private lateinit var tts: TtsManager
    // API KEY OPENAI
    private var openAiService: OpenAiService? = OpenAiService(apiKey = "TU API KEY")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keyManager = `KeyManager-old`(this)
        recorder = AudioRecorder(this)
        tts = TtsManager(this)

        checkPermissions()

        setContent {
            var apiKey by remember { mutableStateOf(keyManager.getApiKey()) }

            if (apiKey.isBlank() && openAiService == null) {
                // Si no hay clave ni en KeyManager ni hardcoded, muestra la pantalla de entrada
                KeyInputScreen(
                    onKeySaved = { newKey ->
                        keyManager.saveApiKey(newKey)
                        apiKey = newKey
                        openAiService = OpenAiService(apiKey = newKey)
                    }
                )
            } else {
                // Si ya hay clave o se definió por defecto, abre la interfaz principal
                if (openAiService == null && apiKey.isNotBlank()) {
                    openAiService = OpenAiService(apiKey = apiKey)
                }
                PalantirApp()
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
        }
    }

    @Composable
    fun PalantirApp() {
        var state by remember { mutableStateOf(PalantirState.IDLE) }
        var currentStyle by remember { mutableStateOf(UiStyle.EYES) }
        var currentAmplitude by remember { mutableStateOf(0) }
        var statusText by remember { mutableStateOf("Toca para activar") }

        val scope = rememberCoroutineScope()

        fun startListeningCycle() {
            state = PalantirState.LISTENING
            statusText = "Escuchando..."

            recorder.startListeningWithVad(
                onSilenceDetected = {
                    state = PalantirState.THINKING
                    statusText = "Pensando..."

                    scope.launch(Dispatchers.IO) {
                        val file = recorder.getOutputFile()

                        // Uso seguro de openAiService mediante ?.let
                        openAiService?.let { service ->
                            val text = service.transcribeAudio(file)

                            if (text.isNotBlank()) {
                                val response = service.getGptResponse(text)
                                state = PalantirState.SPEAKING
                                statusText = response

                                tts.speak(response) {
                                    startListeningCycle()
                                }
                            } else {
                                state = PalantirState.IDLE
                                statusText = "No te escuché"
                            }
                        } ?: run {
                            state = PalantirState.IDLE
                            statusText = "Error: Sin API Key"
                        }
                    }
                },
                onAmplitudeChanged = { amp ->
                    currentAmplitude = amp
                }
            )
        }

        Scaffold(timeText = { TimeText() }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        if (state == PalantirState.IDLE) {
                            startListeningCycle()
                        } else {
                            recorder.stop()
                            tts.stop()
                            state = PalantirState.IDLE
                            statusText = "Toca para activar"
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(8.dp)
                ) {
                    // Renderiza la interfaz seleccionada
                    InterfaceSelector(
                        style = currentStyle,
                        state = state,
                        amplitude = currentAmplitude
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.body2
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Botón para rotar el estilo visual (Ojos -> Sci-Fi -> Reactivo)
                    CompactChip(
                        onClick = {
                            currentStyle = when (currentStyle) {
                                UiStyle.EYES -> UiStyle.SCI_FI
                                UiStyle.SCI_FI -> UiStyle.AUDIO_REACTIVE
                                UiStyle.AUDIO_REACTIVE -> UiStyle.EYES
                            }
                        },
                        label = { Text("Estilo: ${currentStyle.name}") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder.stop()
        tts.shutdown()
    }
}