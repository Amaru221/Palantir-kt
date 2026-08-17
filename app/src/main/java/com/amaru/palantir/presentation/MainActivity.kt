package com.amaru.palantir.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import com.amaru.palantir.GeminiService
import com.amaru.palantir.InterfaceSelector
import com.amaru.palantir.PalantirState
import com.amaru.palantir.UiStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val TAG = "PALANTIR_MAIN"

    // SharedPreferences para guardar configuración localmente
    private lateinit var prefs: SharedPreferences

    // Valores de configuración (con valores por defecto)
    private var apiKey: String = "TU API KEY"
    private var selectedVoiceName: String? = null
    private var speechRate: Float = 0.94f
    private var pitch: Float = 0.95f

    private lateinit var geminiService: GeminiService
    private var tts: TextToSpeech? = null
    private var availableVoicesList by mutableStateOf<List<Voice>>(emptyList())

    // Reconocedor de la palabra clave (Wake Word)
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningWakeWord = false

    // Control de grabación
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    // Estados reactivos UI
    private var currentState by mutableStateOf(PalantirState.WAITING_WAKE_WORD)
    private var currentStyle by mutableStateOf(UiStyle.AUDIO_REACTIVE)
    private var amplitude by mutableIntStateOf(0)
    private var statusText by mutableStateOf("Di \"Oye Palantir\"...")
    private var showSettingsDialog by mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            initSpeechRecognizer()
            startWakeWordListening()
        } else {
            statusText = "Sin permiso de micro"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cargar preferencias guardadas en el reloj
        prefs = getSharedPreferences("palantir_settings", Context.MODE_PRIVATE)
        apiKey = prefs.getString("gemini_api_key", apiKey) ?: apiKey
        selectedVoiceName = prefs.getString("tts_voice_name", null)
        speechRate = prefs.getFloat("tts_speech_rate", 0.94f)
        pitch = prefs.getFloat("tts_pitch", 0.95f)

        geminiService = GeminiService(apiKey)

        // Forzar motor de Google TTS para evitar restricciones de fabricantes
        tts = TextToSpeech(this, this, "com.google.android.tts")

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { onScreenTapped() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        InterfaceSelector(
                            style = currentStyle,
                            state = currentState,
                            amplitude = amplitude
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = statusText,
                            color = Color.White,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = currentStyle.name,
                            color = Color.Gray,
                            fontSize = 9.sp,
                            modifier = Modifier.clickable {
                                currentStyle = when (currentStyle) {
                                    UiStyle.AUDIO_REACTIVE -> UiStyle.EYES
                                    UiStyle.EYES -> UiStyle.SCI_FI
                                    UiStyle.SCI_FI -> UiStyle.AUDIO_REACTIVE
                                }
                            }
                        )
                    }

                    // Botón de Ajustes en la esquina superior derecha
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 10.dp, end = 10.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Button(
                            onClick = {
                                        stopWakeWordListening()
                                        stopSpeaking()
                                        showSettingsDialog = true
                                      },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color.DarkGray.copy(alpha = 0.6f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Sharp.Settings,
                                contentDescription = "Ajustes",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Modal de Configuración
                    if (showSettingsDialog) {
                        SettingsScreen(
                            currentApiKey = apiKey,
                            currentVoiceName = selectedVoiceName,
                            currentRate = speechRate,
                            currentPitch = pitch,
                            voices = availableVoicesList,
                            onSave = { newApiKey, newVoice, newRate, newPitch ->
                                apiKey = newApiKey
                                selectedVoiceName = newVoice?.name
                                speechRate = newRate
                                pitch = newPitch

                                // Guardar en SharedPreferences
                                prefs.edit().apply {
                                    putString("gemini_api_key", apiKey)
                                    putString("tts_voice_name", selectedVoiceName)
                                    putFloat("tts_speech_rate", speechRate)
                                    putFloat("tts_pitch", pitch)
                                    apply()
                                }

                                // Re-aplicar cambios
                                geminiService = GeminiService(apiKey)
                                applyTtsSettings()

                                showSettingsDialog = false

                                // Reiniciar escucha del Wake Word al guardar
                                resetToWakeWordState()
                            },
                            onDismiss = {
                                showSettingsDialog = false
                                // Reiniciar escucha del Wake Word al cancelar/cerrar
                                resetToWakeWordState()
                            }
                        )
                    }
                }
            }
        }

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            initSpeechRecognizer()
            startWakeWordListening()
        }
    }

    private fun onScreenTapped() {
        if (showSettingsDialog) return
        when (currentState) {
            PalantirState.SPEAKING -> stopSpeakingAndReset()
            PalantirState.LISTENING -> finishQueryRecording()
            else -> {}
        }
    }

    // --- RECONOCEDOR WAKE WORD ("Oye Palantir") ---

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (isListeningWakeWord && !showSettingsDialog) {
                        mainHandler.postDelayed({ restartWakeWordListening() }, 500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""

                    if (text.contains("palantir") || text.contains("oye palantir")) {
                        stopWakeWordListening()
                        startQueryRecording()
                    } else if (isListeningWakeWord) {
                        restartWakeWordListening()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                    if (text.contains("palantir") || text.contains("oye palantir")) {
                        stopWakeWordListening()
                        startQueryRecording()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startWakeWordListening() {
        if (showSettingsDialog) return
        if (speechRecognizer == null) initSpeechRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        isListeningWakeWord = true
        currentState = PalantirState.WAITING_WAKE_WORD
        statusText = "Di \"Oye Palantir\"..."

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escucha wake word: ${e.message}")
        }
    }

    private fun restartWakeWordListening() {
        if (isListeningWakeWord && !showSettingsDialog) {
            speechRecognizer?.cancel()
            startWakeWordListening()
        }
    }

    private fun stopWakeWordListening() {
        isListeningWakeWord = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener wake word: ${e.message}")
        }
    }

    // --- GRABACIÓN Y DETECCIÓN DE SILENCIO (VAD) ---

    private fun startQueryRecording() {
        stopSpeaking()

        val wavFile = File(externalCacheDir, "audio_record.wav")
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true
            currentState = PalantirState.LISTENING
            statusText = "Escuchando consulta..."

            recordingThread = Thread {
                writeWavFileWithSilenceDetection(wavFile, sampleRate, bufferSize)
            }
            recordingThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando grabación: ${e.message}")
            resetToWakeWordState()
        }
    }

    private fun writeWavFileWithSilenceDetection(file: File, sampleRate: Int, bufferSize: Int) {
        val shortBuffer = ShortArray(bufferSize / 2)
        val data = ByteArray(bufferSize)
        val outputStream = FileOutputStream(file)

        outputStream.write(ByteArray(44))

        val silenceThresholdDb = -35.0
        val silenceDurationMs = 3000L
        var lastSoundTime = System.currentTimeMillis()

        while (isRecording) {
            val readShorts = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
            if (readShorts > 0) {
                for (i in 0 until readShorts) {
                    val sample = shortBuffer[i].toInt()
                    data[i * 2] = (sample and 0x00FF).toByte()
                    data[i * 2 + 1] = ((sample shr 8) and 0x00FF).toByte()
                }

                outputStream.write(data, 0, readShorts * 2)

                var maxAmplitude = 0
                var sumSquares = 0.0
                for (i in 0 until readShorts) {
                    val absSample = Math.abs(shortBuffer[i].toInt())
                    if (absSample > maxAmplitude) maxAmplitude = absSample
                    sumSquares += (shortBuffer[i] * shortBuffer[i]).toDouble()
                }
                amplitude = maxAmplitude

                val rms = sqrt(sumSquares / readShorts)
                val db = if (rms > 0) 20 * Math.log10(rms / 32767.0) else -100.0

                if (db > silenceThresholdDb) {
                    lastSoundTime = System.currentTimeMillis()
                } else {
                    val silenceElapsed = System.currentTimeMillis() - lastSoundTime
                    if (silenceElapsed >= silenceDurationMs) {
                        isRecording = false
                        break
                    }
                }
            }
        }

        outputStream.close()
        finalizeWavHeader(file, sampleRate)

        mainHandler.post { finishQueryRecording() }
    }

    private fun finalizeWavHeader(file: File, sampleRate: Int) {
        if (!file.exists() || file.length() <= 44) return

        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = sampleRate * 16 * channels / 8

        val randomAccessFile = RandomAccessFile(file, "rw")
        randomAccessFile.seek(0)
        randomAccessFile.writeBytes("RIFF")
        randomAccessFile.writeInt(Integer.reverseBytes(totalDataLen.toInt()))
        randomAccessFile.writeBytes("WAVEfmt ")
        randomAccessFile.writeInt(Integer.reverseBytes(16))
        randomAccessFile.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        randomAccessFile.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
        randomAccessFile.writeInt(Integer.reverseBytes(sampleRate))
        randomAccessFile.writeInt(Integer.reverseBytes(byteRate))
        randomAccessFile.writeShort(java.lang.Short.reverseBytes((channels * 16 / 8).toShort()).toInt())
        randomAccessFile.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
        randomAccessFile.writeBytes("data")
        randomAccessFile.writeInt(Integer.reverseBytes(totalAudioLen.toInt()))
        randomAccessFile.close()
    }

    private fun finishQueryRecording() {
        if (currentState != PalantirState.LISTENING && currentState != PalantirState.THINKING) return

        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo micro: ${e.message}")
        }

        currentState = PalantirState.THINKING
        statusText = "Pensando..."
        amplitude = 0

        lifecycleScope.launch(Dispatchers.Main) {
            val wavFile = File(externalCacheDir, "audio_record.wav")
            val response = geminiService.processVoiceQuery(wavFile)

            statusText = response
            speak(response)
        }
    }

    // --- TTS Y AJUSTES DE VOZ ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("es", "ES"))

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            val allVoices = tts?.voices ?: emptySet()
            availableVoicesList = allVoices.filter { it.locale.language == "es" }

            applyTtsSettings()

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    currentState = PalantirState.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post { resetToWakeWordState() }
                }

                override fun onError(utteranceId: String?) {
                    mainHandler.post { resetToWakeWordState() }
                }
            })
        }
    }

    private fun applyTtsSettings() {
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)

        if (!selectedVoiceName.isNullOrEmpty()) {
            val foundVoice = availableVoicesList.find { it.name == selectedVoiceName }
            if (foundVoice != null) {
                tts?.voice = foundVoice
                return
            }
        }

        val defaultMale = availableVoicesList.find { voice ->
            val name = voice.name.lowercase(Locale.getDefault())
            !name.contains("female") && !name.contains("esf") && (name.contains("male") || name.contains("esm") || name.contains("man"))
        } ?: availableVoicesList.firstOrNull()

        defaultMale?.let { tts?.voice = it }
    }

    private fun speak(text: String) {
        val cleanText = text
            .replace("%", " por ciento")
            .replace("€", " euros")
            .replace("$", " dólares")
            .replace("&", " y ")
            .replace("@", " arroba")
            .replace(Regex("[*#_`~>\\-]"), "")
            .replace(Regex("\n+"), ". ")
            .replace(Regex("\\.{2,}"), ".")
            .trim()

        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "PalantirTTS")
    }

    private fun stopSpeaking() {
        tts?.stop()
        amplitude = 0
    }

    private fun stopSpeakingAndReset() {
        stopSpeaking()
        resetToWakeWordState()
    }

    private fun resetToWakeWordState() {
        isRecording = false
        currentState = PalantirState.WAITING_WAKE_WORD
        statusText = "Di \"Oye Palantir\"..."
        amplitude = 0
        startWakeWordListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        isListeningWakeWord = false
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}

// --- PANTALLA DE CONFIGURACIÓN (UI EN DIÁLOGO MODAL) ---

@Composable
fun SettingsScreen(
    currentApiKey: String,
    currentVoiceName: String?,
    currentRate: Float,
    currentPitch: Float,
    voices: List<Voice>,
    onSave: (apiKey: String, selectedVoice: Voice?, rate: Float, pitch: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKeyText by remember { mutableStateOf(currentApiKey) }
    var selectedVoice by remember {
        mutableStateOf(voices.find { it.name == currentVoiceName } ?: voices.firstOrNull())
    }
    var rate by remember { mutableFloatStateOf(currentRate) }
    var pitch by remember { mutableFloatStateOf(currentPitch) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Ajustes",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // 1. API Key
                Text("API Key Gemini", fontSize = 10.sp, color = Color.Gray)
                BasicTextField(
                    value = apiKeyText,
                    onValueChange = { apiKeyText = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.Cyan, fontSize = 10.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.DarkGray, shape = CircleShape)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Velocidad (Speech Rate)
                Text("Velocidad: ${(rate * 100).roundToInt() / 100.0}", fontSize = 10.sp, color = Color.White)
                InlineSlider(
                    value = rate,
                    onValueChange = { rate = it },
                    steps = 9,
                    decreaseIcon = {
                        Icon(
                            imageVector = InlineSliderDefaults.Decrease,
                            contentDescription = "Disminuir velocidad"
                        )
                    },
                    increaseIcon = {
                        Icon(
                            imageVector = InlineSliderDefaults.Increase,
                            contentDescription = "Aumentar velocidad"
                        )
                    },
                    valueRange = 0.5f..1.5f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 3. Tono (Pitch)
                Text("Tono: ${(pitch * 100).roundToInt() / 100.0}", fontSize = 10.sp, color = Color.White)
                InlineSlider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    steps = 9,
                    decreaseIcon = {
                        Icon(
                            imageVector = InlineSliderDefaults.Decrease,
                            contentDescription = "Disminuir tono"
                        )
                    },
                    increaseIcon = {
                        Icon(
                            imageVector = InlineSliderDefaults.Increase,
                            contentDescription = "Aumentar tono"
                        )
                    },
                    valueRange = 0.5f..1.5f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 4. Selección de Voz
                Text("Voz de la App", fontSize = 10.sp, color = Color.White)
                voices.forEach { voice ->
                    val isSelected = voice.name == selectedVoice?.name
                    Chip(
                        label = {
                            Text(
                                text = voice.name.takeLast(15),
                                fontSize = 9.sp,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        },
                        onClick = { selectedVoice = voice },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (isSelected) Color.Cyan else Color.DarkGray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Botones Guardar y Cancelar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CompactButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                    ) {
                        Text("X", fontSize = 10.sp, color = Color.White)
                    }

                    CompactButton(
                        onClick = {
                            onSave(apiKeyText, selectedVoice, rate, pitch)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Green)
                    ) {
                        Text("✓", fontSize = 10.sp, color = Color.Black)
                    }
                }
            }
        }
    }
}