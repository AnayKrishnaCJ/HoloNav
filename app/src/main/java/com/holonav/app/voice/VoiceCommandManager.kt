package com.holonav.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Voice Command Manager — Speech-to-Text for destination navigation.
 *
 * Listens for voice commands like "Navigate to Computer Lab" and
 * fuzzy-matches the spoken destination against BuildingGraph names.
 *
 * Uses Android's built-in SpeechRecognizer (no external libraries).
 */
class VoiceCommandManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommand"

        /** Keywords that precede the destination name */
        private val NAVIGATE_PREFIXES = listOf(
            "navigate to",
            "go to",
            "take me to",
            "directions to",
            "find",
            "route to"
        )
    }

    /** Callback interface for voice command results */
    interface VoiceCommandCallback {
        fun onListeningStarted()
        fun onResult(destinationName: String, destinationId: String)
        fun onNoMatch(spokenText: String)
        fun onError(message: String)
        fun onListeningStopped()
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var callback: VoiceCommandCallback? = null
    private var isListening = false

    /** Available destinations: list of (id, name) pairs */
    private var destinations: List<Pair<String, String>> = emptyList()

    /**
     * Set the list of available destinations for matching.
     * Should be called with BuildingGraph.getDestinations().
     */
    fun setDestinations(destinations: List<Pair<String, String>>) {
        this.destinations = destinations
    }

    fun setCallback(callback: VoiceCommandCallback) {
        this.callback = callback
    }

    /**
     * Check if speech recognition is available on this device.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Start listening for a voice command.
     */
    fun startListening() {
        if (isListening) return
        if (!isAvailable()) {
            callback?.onError("Speech recognition not available")
            return
        }

        // Create recognizer
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    callback?.onListeningStarted()
                    Log.d(TAG, "Ready for speech")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    callback?.onListeningStopped()
                    Log.d(TAG, "End of speech")
                }

                override fun onError(error: Int) {
                    isListening = false
                    callback?.onListeningStopped()
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        else -> "Recognition error ($error)"
                    }
                    Log.w(TAG, "Speech error: $errorMsg")
                    callback?.onError(errorMsg)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    callback?.onListeningStopped()

                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    ) ?: emptyList()

                    if (matches.isEmpty()) {
                        callback?.onError("No speech recognized")
                        return
                    }

                    Log.d(TAG, "Speech results: $matches")
                    processVoiceResults(matches)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        // Build recognizer intent
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        speechRecognizer?.startListening(intent)
    }

    /**
     * Stop listening.
     */
    fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            callback?.onListeningStopped()
        }
    }

    /**
     * Process voice results: extract destination and fuzzy-match.
     *
     * Tries each recognized result string:
     * 1. Strip "navigate to" / "go to" prefixes
     * 2. Fuzzy-match remaining text against destination names
     */
    private fun processVoiceResults(results: List<String>) {
        for (spokenText in results) {
            val cleaned = spokenText.trim().lowercase()

            // Try to extract destination after a prefix
            var query = cleaned
            for (prefix in NAVIGATE_PREFIXES) {
                if (cleaned.startsWith(prefix)) {
                    query = cleaned.removePrefix(prefix).trim()
                    break
                }
            }

            // If no prefix found, use the full text as the query
            if (query.isEmpty()) query = cleaned

            // Fuzzy match against destinations
            val match = findBestMatch(query)
            if (match != null) {
                Log.d(TAG, "Matched '$query' → '${match.second}' (${match.first})")
                callback?.onResult(match.second, match.first)
                return
            }
        }

        // No match found in any result
        callback?.onNoMatch(results.firstOrNull() ?: "")
    }

    /**
     * Find the best matching destination for the given query.
     *
     * Matching strategy (in priority order):
     * 1. Exact match (case-insensitive)
     * 2. Query is a substring of the destination name
     * 3. Destination name is a substring of the query
     * 4. Any word in the query matches a word in the destination name
     *
     * @return Pair(id, name) or null if no match
     */
    private fun findBestMatch(query: String): Pair<String, String>? {
        val queryLower = query.lowercase()
        val queryWords = queryLower.split("\\s+".toRegex())

        // Priority 1: Exact match
        for ((id, name) in destinations) {
            if (name.lowercase() == queryLower) return Pair(id, name)
        }

        // Priority 2: Query is substring of destination name
        for ((id, name) in destinations) {
            if (name.lowercase().contains(queryLower)) return Pair(id, name)
        }

        // Priority 3: Destination name contains the query
        for ((id, name) in destinations) {
            val nameLower = name.lowercase()
            if (queryLower.contains(nameLower)) return Pair(id, name)
        }

        // Priority 4: Word-level match (any query word matches a destination word)
        var bestMatch: Pair<String, String>? = null
        var bestScore = 0
        for ((id, name) in destinations) {
            val nameWords = name.lowercase().split("\\s+".toRegex(), "[()]".toRegex())
                .map { it.trim() }.filter { it.isNotEmpty() }

            val score = queryWords.count { qw ->
                nameWords.any { nw -> nw.contains(qw) || qw.contains(nw) }
            }

            if (score > bestScore) {
                bestScore = score
                bestMatch = Pair(id, name)
            }
        }

        return if (bestScore > 0) bestMatch else null
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
