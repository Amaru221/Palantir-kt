# Wear OS Gemini Voice Assistant

A lightweight, hands-free personal assistant for Wear OS designed for quick, on-the-go interactions. It captures voice input, queries Google's Gemini LLM, and dictates concise, natural responses directly through text-to-speech.

## Key Features

* 🎙️ **Voice Processing:** Captures and processes audio input directly from your smartwatch.
* 🤖 **Gemini AI Powered:** Leverages large language models (LLMs) like Gemini for accurate, context-aware responses.
* ⚡ **Wrist-Optimized Output:** Prompts the AI to deliver short, direct answers tailored for glanceable and quick audio consumption.
* 🔊 **Text-to-Speech Dictation:** Speaks the generated response back to you for a complete hands-free experience.


## How It Works

1. **Audio Capture:** The smartwatch microphone records user voice input via Wear OS APIs.
2. **Processing & API Call:** Audio is processed and dispatched to the Gemini API with a system prompt enforcing concise responses.
3. **Response Handling:** The app parses Gemini's text response.
4. **Speech Synthesis:** Text-To-Speech (TTS) engine dictates the answer back to the user hands-free.