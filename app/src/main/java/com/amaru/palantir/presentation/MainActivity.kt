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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val TAG = "PALANTIR_MAIN"

    // #1: API key hardcodeada como fallback. Se override por SharedPreferences si existe.
    private val DEFAULT_API_KEY = ""

    private lateinit var prefs: SharedPreferences

    private var apiKey: String = DEFAULT_API_KEY
    private var selectedVoiceName: String? = null
    private var selectedLanguage: String = "ES"
    private var selectedGender: String = "MALE"
    private var speechRate: Float = 0.94f
    private var pitch: Float = 0.95f

    private lateinit var geminiService: GeminiService
    private var tts: TextToSpeech? = null
    private var availableVoicesList by mutableStateOf<List<Voice>>(emptyList())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningWakeWord = false

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    // #7: Job en vez de Thread crudo
    private var recordingJob: Job? = null

    private var currentState by mutableStateOf(PalantirState.WAITING_WAKE_WORD)
    private var currentStyle by mutableStateOf(UiStyle.MYSTIC)
    private var amplitude by mutableIntStateOf(0)
    private var statusText by mutableStateOf("Di \"Oye Palantir\"...")
    private var showSettingsDialog by mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    // #2: Duración máxima de grabación en ms
    private val MAX_RECORDING_DURATION_MS = 30_000L

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
        // #1: Carga desde SharedPreferences; si no existe, usa DEFAULT_API_KEY
        apiKey = prefs.getString("gemini_api_key", DEFAULT_API_KEY) ?: DEFAULT_API_KEY
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
                                // #16: Recargar voces al abrir settings
                                reloadTtsVoices()
                                showSettingsDialog = true
                            },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color.DarkGray.copy(alpha = 0.6f)
                            )
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                val r = size.minDimension / 2f
                                val innerR = r * 0.55f
                                val toothCount = 8
                                val toothAngle = (2f * PI / toothCount).toFloat()
                                val path = androidx.compose.ui.graphics.Path()
                                for (i in 0 until toothCount) {
                                    val a1 = i * toothAngle
                                    val a2 = a1 + toothAngle * 0.35f
                                    val a3 = a1 + toothAngle * 0.65f
                                    val a4 = a1 + toothAngle
                                    if (i == 0) path.moveTo(
                                        center.x + r * cos(a1), center.y + r * sin(a1)
                                    )
                                    path.lineTo(center.x + r * cos(a2), center.y + r * sin(a2))
                                    path.lineTo(center.x + innerR * cos(a3), center.y + innerR * sin(a3))
                                    path.lineTo(center.x + innerR * cos(a4), center.y + innerR * sin(a4))
                                    val nextA1 = (i + 1) * toothAngle
                                    path.lineTo(center.x + r * cos(nextA1), center.y + r * sin(nextA1))
                                }
                                path.close()
                                drawPath(path, color = Color.White)
                                drawCircle(Color.Black, radius = innerR * 0.55f)
                            }
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

                                // #3: No recrear GeminiService si la key no cambió
                                geminiService.updateApiKey(apiKey)
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

            // #7: Usar coroutine en vez de Thread crudo
            recordingJob = lifecycleScope.launch(Dispatchers.IO) {
                writeWavFileWithSilenceDetection(wavFile, sampleRate, bufferSize)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando grabación: ${e.message}")
            resetToWakeWordState()
        }
    }

    private suspend fun writeWavFileWithSilenceDetection(file: File, sampleRate: Int, bufferSize: Int) {
        val shortBuffer = ShortArray(bufferSize / 2)
        // #13: Usar ByteArrayOutputStream para evitar 2 aperturas de archivo
        val audioStream = ByteArrayOutputStream()

        val silenceThresholdDb = -35.0
        val silenceDurationMs = 3000L
        var lastSoundTime = System.currentTimeMillis()
        // #2: Control de duración máxima
        val recordingStartTime = System.currentTimeMillis()

        while (isRecording && currentCoroutineContext().isActive) {
            // #2: Check duración máxima
            if (System.currentTimeMillis() - recordingStartTime >= MAX_RECORDING_DURATION_MS) {
                Log.w(TAG, "Duración máxima de grabación alcanzada (${MAX_RECORDING_DURATION_MS}ms)")
                break
            }

            val readShorts = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
            if (readShorts > 0) {
                // Convertir PCM shorts a bytes y escribir al stream
                val pcmBytes = ByteArray(readShorts * 2)
                for (i in 0 until readShorts) {
                    val sample = shortBuffer[i].toInt()
                    pcmBytes[i * 2] = (sample and 0x00FF).toByte()
                    pcmBytes[i * 2 + 1] = ((sample shr 8) and 0x00FF).toByte()
                }
                audioStream.write(pcmBytes)

                var maxAmplitude = 0
                var sumSquares = 0.0
                for (i in 0 until readShorts) {
                    val absSample = Math.abs(shortBuffer[i].toInt())
                    if (absSample > maxAmplitude) maxAmplitude = absSample
                    sumSquares += (shortBuffer[i] * shortBuffer[i]).toDouble()
                }
                // #9: Sincronizar amplitude via mainHandler
                mainHandler.post { amplitude = maxAmplitude }

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
            // #8: Yield al CPU para evitar spin-loop al 100%
            Thread.sleep(10)
        }

        // #13: Escribir WAV completo en un solo paso
        val audioData = audioStream.toByteArray()
        if (audioData.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                FileOutputStream(file).use { fos ->
                    writeWavHeader(fos, sampleRate, audioData.size)
                    fos.write(audioData)
                }
            }
        }

        mainHandler.post { finishQueryRecording() }
    }

    // #13: Escribir header WAV directamente (sin RandomAccessFile)
    private fun writeWavHeader(out: java.io.OutputStream, sampleRate: Int, dataSize: Int) {
        val totalDataLen = dataSize + 36
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8

        out.write("RIFF".toByteArray())
        out.write(intToLittleEndian(totalDataLen))
        out.write("WAVEfmt ".toByteArray())
        out.write(intToLittleEndian(16))
        out.write(shortToLittleEndian(1))  // PCM format
        out.write(shortToLittleEndian(channels.toShort()))
        out.write(intToLittleEndian(sampleRate))
        out.write(intToLittleEndian(byteRate))
        out.write(shortToLittleEndian((channels * bitsPerSample / 8).toShort()))
        out.write(shortToLittleEndian(bitsPerSample.toShort()))
        out.write("data".toByteArray())
        out.write(intToLittleEndian(dataSize))
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToLittleEndian(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )

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

                @Suppress("DEPRECATION")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { resetToWakeWordState() }
                }
            })
        }
    }

    // #16: Recargar voces dinámicamente
    private fun reloadTtsVoices() {
        tts?.let {
            val allVoices = it.voices ?: emptySet()
            availableVoicesList = allVoices.toList()
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
        recordingJob?.cancel()
        speechRecognizer?.destroy()
        tts?.stop()
        // #15: TTS shutdown en background para evitar ANR
        lifecycleScope.launch(Dispatchers.IO) {
            tts?.shutdown()
        }
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
    var selectedLanguage by remember { mutableStateOf(currentLanguage) }
    var selectedGender by remember { mutableStateOf(currentGender) }
    var rate by remember { mutableFloatStateOf(currentRate) }
    var pitch by remember { mutableFloatStateOf(currentPitch) }

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
            // #10: LazyColumn para lazy loading de voces
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Text(
                        text = "Ajustes",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

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

                    Text("Voces Disponibles (${filteredVoices.size})", fontSize = 10.sp, color = Color.White)
                    if (filteredVoices.isEmpty()) {
                        Text("Sin voces para este filtro", fontSize = 9.sp, color = Color.Gray)
                    }
                }

                // #10: LazyColumn items para lazy loading
                items(filteredVoices, key = { it.name }) { voice ->
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

                item {
                    Spacer(modifier = Modifier.height(10.dp))

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
}
