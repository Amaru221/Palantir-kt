package com.amaru.palantir

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GeminiService(apiKey: String) {
    private val TAG = "PALANTIR_API"

    // Modelo ligero y rápido de Gemini
    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.5-flash",
        apiKey = apiKey
    )

    /**
     * Envía directamente el archivo de audio (.wav) a Gemini,
     * el cual transcribe e interpreta la instrucción.
     */
    suspend fun processVoiceQuery(audioFile: File): String = withContext(Dispatchers.IO) {
        Log.e(TAG, "=== INICIANDO CONSULTA A GEMINI ===")
        Log.e(TAG, "Ruta archivo: ${audioFile.absolutePath}")
        Log.e(TAG, "Existe: ${audioFile.exists()} | Tamaño: ${audioFile.length()} bytes")

        if (!audioFile.exists() || audioFile.length() <= 44) {
            Log.e(TAG, "ERROR: El archivo de audio está vacío o no existe.")
            return@withContext "No escuché nada. Intenta de nuevo."
        }

        try {
            val audioBytes = audioFile.readBytes()

            // Preparamos el contenido enviando los bytes del audio + la instrucción del sistema
            val inputContent = content {
                blob(mimeType = "audio/wav", blob = audioBytes)
                text(
                    "Escucha el audio adjunto. Responde de forma directa y concisa a lo que " +
                            "pregunta el usuario. Tu respuesta debe ser de un máximo de 3 frases breves"
                )
            }

            Log.e(TAG, "Enviando audio a Gemini API...")
            val response = generativeModel.generateContent(inputContent)

            val responseText = response.text ?: "No pude procesar la respuesta."
            Log.e(TAG, "RESPUESTA DE GEMINI: $responseText")

            return@withContext responseText

        } catch (e: Throwable) {
            Log.e(TAG, "EXCEPCIÓN O ERROR EN GEMINI: ${e.message}", e)
            return@withContext "Ocurrió un error al conectar con Gemini."
        }
    }
}