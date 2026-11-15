package com.meridiantheschool.mtsstaff

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

/**
 * Voice for the Smart Assistant: Android speech recognition (ask by speaking, Hindi or English)
 * and text-to-speech (hear the answer). Replaces the browser's Web Speech API, which WebView lacks.
 */
class Speech(private val context: Context, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())

    /* ---------- text to speech ---------- */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queued: (() -> Unit)? = null

    fun speak(text: String, lang: String, rate: Float) = main.post {
        val job = {
            tts?.let { t ->
                val locale = if (lang.isNotBlank()) Locale.forLanguageTag(lang) else Locale.getDefault()
                val ok = t.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
                t.language = if (ok) locale else Locale.getDefault()
                t.setSpeechRate(rate.coerceIn(0.5f, 2f))
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mts")
            }
            Unit
        }
        if (tts == null) {
            queued = job
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                main.post { if (ttsReady) queued?.invoke(); queued = null }
            }
        } else if (ttsReady) {
            job()
        } else {
            queued = job
        }
    }

    fun stopSpeaking() = main.post { tts?.stop() }

    /* ---------- speech recognition ---------- */
    private var recognizer: SpeechRecognizer? = null
    private var currentId: String? = null

    fun recognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private fun emit(id: String, type: String, text: String = "", isFinal: Boolean = false, error: String = "") {
        val js = "window.__mtsStt && window.__mtsStt(${JSONObject.quote(id)}, ${JSONObject.quote(type)}, " +
            "${JSONObject.quote(text)}, $isFinal, ${JSONObject.quote(error)})"
        main.post { web.evaluateJavascript(js, null) }
    }

    /** Ends the current listening session once: error (optional) then end. */
    private fun finish(id: String, error: String? = null) {
        if (currentId != id) return
        currentId = null
        if (error != null) emit(id, "error", error = error)
        emit(id, "end")
        recognizer?.destroy()
        recognizer = null
    }

    fun fail(id: String, error: String) {
        emit(id, "error", error = error)
        emit(id, "end")
    }

    fun listen(id: String, lang: String, interim: Boolean) {
        currentId?.let { finish(it, "aborted") }
        if (!recognitionAvailable()) { fail(id, "service-not-allowed"); return }
        currentId = id
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = emit(id, "start")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (interim && currentId == id && !text.isNullOrBlank()) emit(id, "result", text, false)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (currentId == id && !text.isNullOrBlank()) emit(id, "result", text, true)
                finish(id, if (text.isNullOrBlank()) "no-speech" else null)
            }

            override fun onError(error: Int) {
                val code = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                    SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                    else -> "aborted"
                }
                finish(id, code)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (lang.isNotBlank()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        try { rec.startListening(intent) } catch (e: Exception) { finish(id, "aborted") }
    }

    fun stopListening() { recognizer?.stopListening() }

    fun shutdown() {
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }
}
