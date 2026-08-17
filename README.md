# Wear OS Gemini Voice Assistant (Palantir)

A hands-free, voice-activated personal assistant for Wear OS designed for quick, on-the-go interactions. It operates in a continuous loop: listens for a wake word, records your query with automatic silence detection, queries Google's Gemini LLM using direct audio input, and dictates concise responses via Text-to-Speech.

## Key Features

* 🗣️ **Wake Word Activation:** Completely hands-free operation triggered by saying *"Oye Palantir"* or *"Palantir"*.
* ⏱️ **Automatic Silence Detection (VAD):** Dynamically measures ambient audio decibels and automatically stops recording after 3 seconds of continuous silence.
* 🤖 **Gemini Multimodal Audio Processing:** Sends `.wav` audio files directly to `gemini-1.5-flash` for fast, accurate speech understanding and context-aware responses.
* 🎨 **Dynamic UI & Visual Styles:** Features multiple customizable UI themes (*Eyes*, *Sci-Fi Orb*, *Audio Reactive*) with real-time audio amplitude animations and state indicators (`WAITING_WAKE_WORD`, `LISTENING`, `THINKING`, `SPEAKING`).
* ⚡ **Wrist-Optimized Output:** Instructs the AI to deliver concise answers (maximum 3 brief sentences) tailored for smartwatch consumption.
* 🔊 **Hands-Free Loop & TTS Dictation:** Dictates responses back using Android's Text-To-Speech engine and automatically resets to standby listening mode when finished.


## How It Works

1. **Wake Word Listening:** The smartwatch listens in standby mode for the phrase *"Oye Palantir"*.
2. **Audio Capture & VAD:** Once triggered, it starts recording audio (`PCM 16-bit .wav`). Recording automatically stops when 3 seconds of continuous silence are detected (or on manual tap).
3. **Direct Audio Processing:** The raw audio file is sent directly to Google AI Studio's Gemini API (`gemini-1.5-flash`) as a binary blob.
4. **Speech Synthesis:** Text-To-Speech (TTS) dictates the AI's response aloud.
5. **Auto-Reset Loop:** When speech playback completes, the assistant seamlessly returns to standby, listening for the next wake word without requiring physical interaction.