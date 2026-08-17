package com.amaru.palantir.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
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
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val TAG = "PALANTIR_MAIN"

    // Sustituye por tu API Key válida de Google AI Studio
    private val GEMINI_API_KEY = "TU_API_KEY"

    private lateinit var geminiService: GeminiService
    private var tts: TextToSpeech? = null

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

        geminiService = GeminiService(GEMINI_API_KEY)
        tts = TextToSpeech(this, this)

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable {
                            onScreenTapped()
                        },
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
                    if (isListeningWakeWord) {
                        // Reintentar escucha de wake word ante pequeños timeouts de red/micro
                        mainHandler.postDelayed({ restartWakeWordListening() }, 500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""

                    Log.d(TAG, "Texto detectado en WakeWord: $text")

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
        if (isListeningWakeWord) {
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

    // --- GRABACIÓN Y DETECCIÓN DE SILENCIO (VAD 3 Segundos) ---

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
            statusText = "Escuchando consulta..."

            recordingThread = Thread {
                writeWavFileWithSilenceDetection(wavFile, sampleRate, bufferSize)
            }
            recordingThread?.start()

        } catch (e: SecurityException) {
            Log.e(TAG, "Error permiso micro: ${e.message}")
            resetToWakeWordState()
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo micro: ${e.message}")
            resetToWakeWordState()
        }
    }

    private fun writeWavFileWithSilenceDetection(file: File, sampleRate: Int, bufferSize: Int) {
        val shortBuffer = ShortArray(bufferSize / 2)
        val data = ByteArray(bufferSize)
        val outputStream = FileOutputStream(file)

        // Reserva 44 bytes para la cabecera WAV
        outputStream.write(ByteArray(44))

        val silenceThresholdDb = -35.0  // Umbral de silencio en dB
        val silenceDurationMs = 3000L   // 3 segundos de silencio
        var lastSoundTime = System.currentTimeMillis()

        while (isRecording) {
            val readShorts = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
            if (readShorts > 0) {
                // Convertir ShortArray a ByteArray para guardar PCM 16-bit LE
                for (i in 0 until readShorts) {
                    val sample = shortBuffer[i].toInt()
                    data[i * 2] = (sample and 0x00FF).toByte()
                    data[i * 2 + 1] = ((sample shr 8) and 0x00FF).toByte()
                }

                outputStream.write(data, 0, readShorts * 2)

                // 1. Calcular amplitud relativa para animación en pantalla
                var maxAmplitude = 0
                var sumSquares = 0.0
                for (i in 0 until readShorts) {
                    val absSample = Math.abs(shortBuffer[i].toInt())
                    if (absSample > maxAmplitude) maxAmplitude = absSample
                    sumSquares += (shortBuffer[i] * shortBuffer[i]).toDouble()
                }
                amplitude = maxAmplitude

                // 2. Calcular energía en dB
                val rms = sqrt(sumSquares / readShorts)
                val db = if (rms > 0) 20 * Math.log10(rms / 32767.0) else -100.0

                // 3. Evaluar silenciador VAD
                if (db > silenceThresholdDb) {
                    lastSoundTime = System.currentTimeMillis()
                } else {
                    val silenceElapsed = System.currentTimeMillis() - lastSoundTime
                    if (silenceElapsed >= silenceDurationMs) {
                        Log.d(TAG, "3 segundos de silencio detectados. Finalizando grabación...")
                        isRecording = false
                        break
                    }
                }
            }
        }

        outputStream.close()

        // Escribir la cabecera WAV válida al inicio del archivo
        finalizeWavHeader(file, sampleRate)

        // Procesar la consulta al terminar la grabación
        mainHandler.post {
            finishQueryRecording()
        }
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
            Log.e(TAG, "Error al detener AudioRecord: ${e.message}")
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

    // --- TTS Y RESET DE CICLO ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("es", "ES"))
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

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PalantirTTS")
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