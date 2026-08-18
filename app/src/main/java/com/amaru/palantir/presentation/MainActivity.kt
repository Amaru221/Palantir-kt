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

    private lateinit var prefs: SharedPreferences

    // Valores de configuración
    private var apiKey: String = ""
    private var selectedVoiceName: String? = null
    private var selectedLanguage: String = "ES" // "ES" o "US"
    private var selectedGender: String = "MALE"  // "MALE" o "FEMALE"
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

        prefs = getSharedPreferences("palantir_settings", Context.MODE_PRIVATE)
        apiKey = prefs.getString("gemini_api_key", apiKey) ?: apiKey
        selectedVoiceName = prefs.getString("tts_voice_name", null)
        selectedLanguage = prefs.getString("tts_language", "ES") ?: "ES"
        selectedGender = prefs.getString("tts_gender", "MALE") ?: "MALE"
        speechRate = prefs.getFloat("tts_speech_rate", 0.94f)
        pitch = prefs.getFloat("tts_pitch", 0.95f)

        geminiService = GeminiService(apiKey)
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
                                    UiStyle.EYES -> UiStyle.SCI_FI
                                    UiStyle.SCI_FI -> UiStyle.AUDIO_REACTIVE
                                    UiStyle.AUDIO_REACTIVE -> UiStyle.MYSTIC
                                    UiStyle.MYSTIC -> UiStyle.HALO
                                    UiStyle.HALO -> UiStyle.RADIAL
                                    UiStyle.RADIAL -> UiStyle.EYES
                                }
                            }
                        )
                    }

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

                    if (showSettingsDialog) {
                        SettingsScreen(
                            currentApiKey = apiKey,
                            currentLanguage = selectedLanguage,
                            currentGender = selectedGender,
                            currentVoiceName = selectedVoiceName,
                            currentRate = speechRate,
                            currentPitch = pitch,
                            allVoices = availableVoicesList,
                            onSave = { newApiKey, newLang, newGender, newVoice, newRate, newPitch ->
                                apiKey = newApiKey
                                selectedLanguage = newLang
                                selectedGender = newGender
                                selectedVoiceName = newVoice?.name
                                speechRate = newRate
                                pitch = newPitch

                                prefs.edit().apply {
                                    putString("gemini_api_key", apiKey)
                                    putString("tts_language", selectedLanguage)
                                    putString("tts_gender", selectedGender)
                                    putString("tts_voice_name", selectedVoiceName)
                                    putFloat("tts_speech_rate", speechRate)
                                    putFloat("tts_pitch", pitch)
                                    apply()
                                }

                                geminiService = GeminiService(apiKey)
                                applyTtsSettings()

                                showSettingsDialog = false
                                resetToWakeWordState()
                            },
                            onDismiss = {
                                showSettingsDialog = false
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

    // --- RECONOCEDOR WAKE WORD ---

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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (selectedLanguage == "US") "en-US" else "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        isListeningWakeWord = true
        currentState = PalantirState.WAITING_WAKE_WORD
        statusText = if (selectedLanguage == "US") "Say \"Oye Palantir\"..." else "Di \"Oye Palantir\"..."

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

    // --- GRABACIÓN Y VAD ---

    private fun startQueryRecording() {
        stopSpeaking()

        val wavFile = File(externalCacheDir, "audio_record.wav")
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)

        try {
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
            statusText = if (selectedLanguage == "US") "Listening..." else "Escuchando consulta..."

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
        statusText = if (selectedLanguage == "US") "Thinking..." else "Pensando..."
        amplitude = 0

        lifecycleScope.launch(Dispatchers.Main) {
            val wavFile = File(externalCacheDir, "audio_record.wav")
            val response = geminiService.processVoiceQuery(wavFile)

            statusText = response
            speak(response)
        }
    }

    // --- TTS ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            val allVoices = tts?.voices ?: emptySet()
            availableVoicesList = allVoices.toList()

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
        val targetLocale = if (selectedLanguage == "US") Locale.US else Locale("es", "ES")
        tts?.setLanguage(targetLocale)
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)

        if (!selectedVoiceName.isNullOrEmpty()) {
            val foundVoice = availableVoicesList.find { it.name == selectedVoiceName }
            if (foundVoice != null) {
                tts?.voice = foundVoice
                return
            }
        }

        // Selección por defecto filtrada por idioma y género
        val filtered = availableVoicesList.filter { voice ->
            val langMatch = if (selectedLanguage == "US") voice.locale.language == "en" else voice.locale.language == "es"
            val name = voice.name.lowercase(Locale.getDefault())
            val isFemale = name.contains("female") || name.contains("esf") || name.contains("zoraida")
            val genderMatch = if (selectedGender == "FEMALE") isFemale else !isFemale
            langMatch && genderMatch
        }

        filtered.firstOrNull()?.let { tts?.voice = it }
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
        statusText = if (selectedLanguage == "US") "Say \"Oye Palantir\"..." else "Di \"Oye Palantir\"..."
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

// --- PANTALLA DE CONFIGURACIÓN ---

@Composable
fun SettingsScreen(
    currentApiKey: String,
    currentLanguage: String,
    currentGender: String,
    currentVoiceName: String?,
    currentRate: Float,
    currentPitch: Float,
    allVoices: List<Voice>,
    onSave: (apiKey: String, language: String, gender: String, selectedVoice: Voice?, rate: Float, pitch: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKeyText by remember { mutableStateOf(currentApiKey) }
    var selectedLanguage by remember { mutableStateOf(currentLanguage) } // "ES" o "US"
    var selectedGender by remember { mutableStateOf(currentGender) }     // "MALE" o "FEMALE"
    var rate by remember { mutableFloatStateOf(currentRate) }
    var pitch by remember { mutableFloatStateOf(currentPitch) }

    // Filtrar voces según Idioma (ES / US) y Género (Hombre / Mujer)
    val filteredVoices = remember(selectedLanguage, selectedGender, allVoices) {
        allVoices.filter { voice ->
            val langMatch = if (selectedLanguage == "US") {
                voice.locale.language == "en"
            } else {
                voice.locale.language == "es"
            }

            val name = voice.name.lowercase(Locale.getDefault())
            val isFemale = name.contains("female") || name.contains("esf") || name.contains("zoraida") || name.contains("monica")
            val genderMatch = if (selectedGender == "FEMALE") isFemale else !isFemale

            langMatch && genderMatch
        }
    }

    var selectedVoice by remember(filteredVoices) {
        mutableStateOf(
            filteredVoices.find { it.name == currentVoiceName } ?: filteredVoices.firstOrNull()
        )
    }

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

                // 2. Selector de IDIOMA (ES / US)
                Text("Idioma", fontSize = 10.sp, color = Color.White)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CompactChip(
                        label = { Text("ES", fontSize = 9.sp, color = if (selectedLanguage == "ES") Color.Black else Color.White) },
                        onClick = { selectedLanguage = "ES" },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (selectedLanguage == "ES") Color.Cyan else Color.DarkGray
                        )
                    )
                    CompactChip(
                        label = { Text("US", fontSize = 9.sp, color = if (selectedLanguage == "US") Color.Black else Color.White) },
                        onClick = { selectedLanguage = "US" },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (selectedLanguage == "US") Color.Cyan else Color.DarkGray
                        )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 3. Selector de GÉNERO (Hombre / Mujer)
                Text("Género", fontSize = 10.sp, color = Color.White)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CompactChip(
                        label = { Text("Hombre", fontSize = 9.sp, color = if (selectedGender == "MALE") Color.Black else Color.White) },
                        onClick = { selectedGender = "MALE" },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (selectedGender == "MALE") Color.Cyan else Color.DarkGray
                        )
                    )
                    CompactChip(
                        label = { Text("Mujer", fontSize = 9.sp, color = if (selectedGender == "FEMALE") Color.Black else Color.White) },
                        onClick = { selectedGender = "FEMALE" },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (selectedGender == "FEMALE") Color.Cyan else Color.DarkGray
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 4. Velocidad
                Text("Velocidad: ${(rate * 100).roundToInt() / 100.0}", fontSize = 10.sp, color = Color.White)
                InlineSlider(
                    value = rate,
                    onValueChange = { rate = it },
                    steps = 9,
                    decreaseIcon = { Icon(imageVector = InlineSliderDefaults.Decrease, contentDescription = "-") },
                    increaseIcon = { Icon(imageVector = InlineSliderDefaults.Increase, contentDescription = "+") },
                    valueRange = 0.5f..1.5f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 5. Tono
                Text("Tono: ${(pitch * 100).roundToInt() / 100.0}", fontSize = 10.sp, color = Color.White)
                InlineSlider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    steps = 9,
                    decreaseIcon = { Icon(imageVector = InlineSliderDefaults.Decrease, contentDescription = "-") },
                    increaseIcon = { Icon(imageVector = InlineSliderDefaults.Increase, contentDescription = "+") },
                    valueRange = 0.5f..1.5f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 6. Lista de Voces (filtradas dinámicamente)
                Text("Voces Disponibles (${filteredVoices.size})", fontSize = 10.sp, color = Color.White)
                if (filteredVoices.isEmpty()) {
                    Text("Sin voces para este filtro", fontSize = 9.sp, color = Color.Gray)
                } else {
                    filteredVoices.forEach { voice ->
                        val isSelected = voice.name == selectedVoice?.name
                        Chip(
                            label = {
                                Text(
                                    text = voice.name.takeLast(16),
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
                            onSave(apiKeyText, selectedLanguage, selectedGender, selectedVoice, rate, pitch)
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