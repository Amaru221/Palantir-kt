package com.amaru.palantir

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class `AudioRecorder-old`(private val context: Context) {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var recordingJob: Job? = null

    fun getOutputFile(): File {
        return File(context.cacheDir, "apollo_input.wav")
    }

    @SuppressLint("MissingPermission")
    fun startListeningWithVad(
        onSilenceDetected: () -> Unit,
        onAmplitudeChanged: (Int) -> Unit
    ) {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize
        )

        val outputFile = getOutputFile()
        val fos = FileOutputStream(outputFile)

        audioRecord?.startRecording()
        isListening = true

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(minBufferSize)
            var lastVoiceTime = System.currentTimeMillis()
            val silenceThresholdTime = 1800L // Subido a 1.8 segundos de silencio
            val amplitudeSilenceLimit = 800  // Bajado el umbral para mayor sensibilidad
            val startTime = System.currentTimeMillis()

            // Escribimos cabecera provisional
            writeWavHeader(fos, channelConfig, sampleRate, audioFormat, 0)
            var totalAudioLen = 0

            while (isListening) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    fos.write(buffer, 0, read)
                    totalAudioLen += read

                    val maxAmplitude = calculateMaxAmplitude(buffer, read)
                    onAmplitudeChanged(maxAmplitude)

                    // Detectamos si la amplitud supera el ruido de fondo
                    if (maxAmplitude > amplitudeSilenceLimit) {
                        lastVoiceTime = System.currentTimeMillis()
                    } else {
                        // Solo corta si han pasado 1.8s de silencio Y la grabación duró al menos 1.5s en total
                        val hasMinDuration = (System.currentTimeMillis() - startTime) > 1500
                        val hasSilence = (System.currentTimeMillis() - lastVoiceTime) > silenceThresholdTime

                        if (hasSilence && hasMinDuration) {
                            isListening = false
                            break
                        }
                    }
                }
            }

            fos.close()
            updateWavHeader(outputFile)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            launch(Dispatchers.Main) {
                onSilenceDetected()
            }
        }
    }

    fun stop() {
        isListening = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord = null
        }
    }

    private fun calculateMaxAmplitude(buffer: ByteArray, bytesRead: Int): Int {
        var max = 0
        for (i in 0 until bytesRead - 1 step 2) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val absSample = abs(sample)
            if (absSample > max) {
                max = absSample
            }
        }
        return max
    }

    private fun writeWavHeader(out: FileOutputStream, channelConfig: Int, sampleRate: Int, audioFormat: Int, totalAudioLen: Int) {
        val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        out.write(header, 0, 44)
    }

    private fun updateWavHeader(file: File) {
        val totalAudioLen = (file.length() - 44).toInt()
        val randomAccessFile = java.io.RandomAccessFile(file, "rw")
        randomAccessFile.seek(4)
        val totalDataLen = totalAudioLen + 36
        randomAccessFile.write(byteArrayOf(
            (totalDataLen and 0xff).toByte(),
            ((totalDataLen shr 8) and 0xff).toByte(),
            ((totalDataLen shr 16) and 0xff).toByte(),
            ((totalDataLen shr 24) and 0xff).toByte()
        ))
        randomAccessFile.seek(40)
        randomAccessFile.write(byteArrayOf(
            (totalAudioLen and 0xff).toByte(),
            ((totalAudioLen shr 8) and 0xff).toByte(),
            ((totalAudioLen shr 16) and 0xff).toByte(),
            ((totalAudioLen shr 24) and 0xff).toByte()
        ))
        randomAccessFile.close()
    }
}