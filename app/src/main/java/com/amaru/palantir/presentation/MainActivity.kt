package com.amaru.palantir

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
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
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val TAG = "PALANTIR_MAIN"

    // API Key Gratuita de Google AI Studio (https://aistudio.google.com/)
    private val GEMINI_API_KEY = "TU API KEY"

    private lateinit var geminiService: GeminiService
    private var tts: TextToSpeech? = null

    // Control de audio
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    // Estados reactivos UI
    private var currentState by mutableStateOf(PalantirState.IDLE)
    private var currentStyle by mutableStateOf(UiStyle.AUDIO_REACTIVE)
    private var amplitude by mutableIntStateOf(0)
    private var statusText by mutableStateOf("Toca para hablar")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            statusText = "Sin permiso de micro"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        geminiService = GeminiService(GEMINI_API_KEY)
        tts = TextToSpeech(this, this)

        checkPermissions()

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable {
                            // Tocar cualquier parte cambia la interacción
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
                        // Muestra la interfaz seleccionada (EYES, SCI_FI u AUDIO_REACTIVE)
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

                        // Botón pequeño para alternar estilo visual
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
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onScreenTapped() {
        when (currentState) {
            PalantirState.IDLE -> startRecording()
            PalantirState.LISTENING -> stopRecordingAndProcess()
            PalantirState.SPEAKING -> stopSpeaking()
            PalantirState.THINKING -> {} // Esperando respuesta
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("es", "ES"))
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    currentState = PalantirState.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    currentState = PalantirState.IDLE
                    amplitude = 0
                }

                override fun onError(utteranceId: String?) {
                    currentState = PalantirState.IDLE
                    amplitude = 0
                }
            })
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PalantirTTS")
    }

    private fun stopSpeaking() {
        tts?.stop()
        currentState = PalantirState.IDLE
        statusText = "Toca para hablar"
        amplitude = 0
    }

    // --- GRABACIÓN Y PROCESAMIENTO ---

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText = "Sin permiso de micro"
            return
        }

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
            statusText = "Escuchando..."

            recordingThread = Thread {
                writeWavFile(wavFile, sampleRate, bufferSize)
            }
            recordingThread?.start()

        } catch (e: SecurityException) {
            Log.e(TAG, "Error de micro: ${e.message}")
            statusText = "Error al abrir micro"
        }
    }

    private fun stopRecordingAndProcess() {
        if (!isRecording) return

        isRecording = false
        currentState = PalantirState.THINKING
        statusText = "Pensando..."
        amplitude = 0

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener grabador: ${e.message}")
        }

        lifecycleScope.launch {
            val wavFile = File(externalCacheDir, "audio_record.wav")
            val response = geminiService.processVoiceQuery(wavFile)

            statusText = response
            speak(response)
        }
    }

    private fun writeWavFile(file: File, sampleRate: Int, bufferSize: Int) {
        val data = ByteArray(bufferSize)
        val outputStream = FileOutputStream(file)

        outputStream.write(ByteArray(44))

        while (isRecording) {
            val read = audioRecord?.read(data, 0, bufferSize) ?: 0
            if (read > 0) {
                outputStream.write(data, 0, read)

                // Cálculo de la amplitud máxima instantánea para animar la interfaz en tiempo real
                var maxAmplitude = 0
                for (i in 0 until read - 1 step 2) {
                    val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    val absSample = Math.abs(sample.toShort().toInt())
                    if (absSample > maxAmplitude) maxAmplitude = absSample
                }
                amplitude = maxAmplitude
            }
        }

        outputStream.close()

        // Escribir cabecera WAV real
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

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }
}