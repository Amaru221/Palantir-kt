package com.amaru.palantir

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(val model: String, val messages: List<ChatMessage>)

@Serializable
data class WhisperResponse(val text: String = "")

class OpenAiService(private val apiKey: String) {
    private val jsonConfig = Json { ignoreUnknownKeys = true }
    private val TAG = "PALANTIR_API"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonConfig)
        }
    }

    suspend fun transcribeAudio(audioFile: File): String {
        // Validar si el archivo realmente tiene datos de audio grabados
        Log.e(TAG, "Tamaño del archivo de audio: ${audioFile.length()} bytes")
        if (!audioFile.exists() || audioFile.length() <= 44) {
            Log.e(TAG, "El archivo WAV está vacío o solo contiene la cabecera.")
            return ""
        }

        return try {
            Log.e(TAG, "Enviando audio a Whisper OpenAI...")
            val response: HttpResponse = client.submitFormWithBinaryData(
                url = "https://api.openai.com/v1/audio/transcriptions",
                formData = formData {
                    append("model", "whisper-1")
                    append("language", "es")
                    append("file", audioFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"apollo_input.wav\"")
                    })
                }
            ) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            val bodyText = response.bodyAsText()
            Log.e(TAG, "Respuesta HTTP Status: ${response.status}")
            Log.e(TAG, "Respuesta Whisper Body: $bodyText")

            if (response.status == HttpStatusCode.OK) {
                val parsed = jsonConfig.decodeFromString<WhisperResponse>(bodyText)
                parsed.text
            } else {
                Log.e(TAG, "Error de la API de OpenAI: $bodyText")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción durante la transcripción: ${e.localizedMessage}", e)
            ""
        }
    }

    suspend fun getGptResponse(userText: String): String {
        return try {
            Log.e(TAG, "Enviando texto a GPT-4o-mini: $userText")
            val response: HttpResponse = client.post("https://api.openai.com/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = "gpt-4o-mini",
                        messages = listOf(
                            ChatMessage("system", "Eres Palantir, un asistente de voz conciso para smartwatch. Responde en máximo 2 frases breves."),
                            ChatMessage("user", userText)
                        )
                    )
                )
            }

            val body = response.bodyAsText()
            Log.e(TAG, "Respuesta GPT-4o-mini Status: ${response.status}")
            Log.e(TAG, "Respuesta GPT-4o-mini Body: $body")

            val regex = """"content":\s*"([^"]+)"""".toRegex()
            regex.find(body)?.groupValues?.get(1)?.replace("\\n", " ") ?: "No pude procesar la respuesta."
        } catch (e: Exception) {
            Log.e(TAG, "Excepción en GPT: ${e.localizedMessage}", e)
            "Error de conexión con OpenAI."
        }
    }
}