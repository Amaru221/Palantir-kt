package com.amaru.palantir

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GeminiService(apiKey: String) {
    private val TAG = "PALANTIR_API"

    // Modelo multimodal estable para procesar audio
    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.5-flash",
        apiKey = apiKey
    )

    /**
     * Envía directamente el archivo de audio (.wav) a Gemini,
     * el cual transcribe e interpreta la instrucción.
     */
    suspend fun processVoiceQuery(audioFile: File): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== INICIANDO CONSULTA A GEMINI ===")
        Log.d(TAG, "Ruta archivo: ${audioFile.absolutePath}")
        Log.d(TAG, "Existe: ${audioFile.exists()} | Tamaño: ${audioFile.length()} bytes")

        if (!audioFile.exists() || audioFile.length() <= 44) {
            Log.e(TAG, "ERROR: El archivo de audio está vacío o no existe.")
            return@withContext "No escuché nada. Intenta de nuevo."
        }

        try {
            val audioBytes = audioFile.readBytes()

            // Preparamos el contenido enviando los bytes del audio + la instrucción
            val inputContent = content {
                blob(mimeType = "audio/wav", blob = audioBytes)
                text(
                    """
                    Eres Palantir, un asistente de voz para smartwatch.
            
                    REGLAS:
                    - Escucha el audio y responde de forma directa, en una o dos frases fluidas en prosa.
                    - Sin saludos, sin listas ni preámbulos. Ve directo al grano.
                    - Si el audio no se entiende, responde únicamente: "No he podido escucharte bien, ¿puedes repetirlo?".
                    """.trimIndent()
                )
            }

            Log.d(TAG, "Enviando audio a Gemini API...")
            val response = generativeModel.generateContent(inputContent)

            val responseText = response.text ?: "No pude procesar la respuesta."
            Log.d(TAG, "RESPUESTA DE GEMINI: $responseText")

            return@withContext responseText

        } catch (e: Throwable) {
            Log.e(TAG, "EXCEPCIÓN O ERROR EN GEMINI: ${e.message}", e)
            return@withContext "Ocurrió un error al conectar con Gemini."
        }
    }
}