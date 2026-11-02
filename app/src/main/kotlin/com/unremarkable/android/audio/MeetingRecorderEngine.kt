package com.unremarkable.android.audio

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class MeetingRecordingResult(
    val audioFile: File?,
    val transcript: String,
    val durationSeconds: Long
)

class MeetingRecorderEngine(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioOutputFile: File? = null

    private var isRecording = false
    private var startTimeMillis = 0L
    private var durationSeconds = 0L

    private val accumulatedTranscript = StringBuilder()
    private var currentPartialText = ""

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var onTranscriptChanged: ((fullTranscript: String, livePartial: String) -> Unit)? = null
    var onRmsChanged: ((Float) -> Unit)? = null
    var onDurationTick: ((Long) -> Unit)? = null
    var onErrorOccurred: ((String) -> Unit)? = null

    fun isSpeechRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startRecording(title: String = "Meeting") {
        if (isRecording) return
        isRecording = true
        startTimeMillis = System.currentTimeMillis()
        accumulatedTranscript.clear()
        currentPartialText = ""

        val speechAvailable = isSpeechRecognitionAvailable()

        if (speechAvailable) {
            // Live speech-to-text mode: let SpeechRecognizer manage mic without hardware lock contention
            startSpeechListeningSession()
        } else {
            // Fallback audio file recorder mode if SpeechRecognizer is unavailable
            startMediaRecorderFallback(title)
        }

        // Duration Timer
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isRecording) {
                delay(1000)
                durationSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000
                onDurationTick?.invoke(durationSeconds)
            }
        }
    }

    private fun startMediaRecorderFallback(title: String) {
        try {
            val audioDir = File(context.filesDir, "recordings")
            if (!audioDir.exists()) audioDir.mkdirs()

            val safeTitle = title.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(30)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            audioOutputFile = File(audioDir, "${safeTitle}_$timeStamp.m4a")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(audioOutputFile!!.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
        } catch (e: Exception) {
            mediaRecorder = null
            onErrorOccurred?.invoke("Media recorder error: ${e.message}")
        }
    }

    private fun startSpeechListeningSession() {
        if (!isRecording) return

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {
                        val norm = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                        onRmsChanged?.invoke(norm)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service busy"
                            SpeechRecognizer.ERROR_SERVER -> "Speech server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            else -> "Recognition error: $error"
                        }

                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            onErrorOccurred?.invoke(errorMsg)
                            return
                        }

                        // Seamlessly continue listening on routine pause / silence / busy
                        if (isRecording) {
                            scope.launch {
                                val restartDelay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800L else 400L
                                delay(restartDelay)
                                if (isRecording) {
                                    startSpeechListeningSession()
                                }
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrBlank()) {
                            val timeStr = formatDuration(durationSeconds)
                            if (accumulatedTranscript.isNotEmpty()) {
                                accumulatedTranscript.append("\n\n")
                            }
                            accumulatedTranscript.append("[$timeStr] ").append(text)
                        }
                        currentPartialText = ""
                        onTranscriptChanged?.invoke(accumulatedTranscript.toString(), "")

                        // Continue listening for next utterance
                        if (isRecording) {
                            startSpeechListeningSession()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim().orEmpty()
                        currentPartialText = partial
                        onTranscriptChanged?.invoke(accumulatedTranscript.toString(), partial)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            onErrorOccurred?.invoke("Speech listening error: ${e.message}")
        }
    }

    fun stopRecording(): MeetingRecordingResult {
        isRecording = false
        timerJob?.cancel()
        timerJob = null

        // Stop Audio Recorder
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {}
        mediaRecorder = null

        // Stop Speech Recognizer
        try {
            speechRecognizer?.apply {
                stopListening()
                destroy()
            }
        } catch (_: Exception) {}
        speechRecognizer = null

        // Flush remaining partial text
        if (currentPartialText.isNotBlank()) {
            val timeStr = formatDuration(durationSeconds)
            if (accumulatedTranscript.isNotEmpty()) {
                accumulatedTranscript.append("\n\n")
            }
            accumulatedTranscript.append("[$timeStr] ").append(currentPartialText)
            currentPartialText = ""
        }

        val finalTranscript = accumulatedTranscript.toString()
        onTranscriptChanged?.invoke(finalTranscript, "")

        return MeetingRecordingResult(
            audioFile = audioOutputFile?.takeIf { it.exists() && it.length() > 0 },
            transcript = finalTranscript,
            durationSeconds = durationSeconds
        )
    }

    companion object {
        fun formatDuration(seconds: Long): String {
            val m = seconds / 60
            val s = seconds % 60
            return String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
