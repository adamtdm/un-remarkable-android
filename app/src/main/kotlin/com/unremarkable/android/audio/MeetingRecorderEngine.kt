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

    fun isSpeechRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startRecording(title: String = "Meeting") {
        if (isRecording) return
        isRecording = true
        startTimeMillis = System.currentTimeMillis()
        accumulatedTranscript.clear()
        currentPartialText = ""

        // 1. Setup audio output file and MediaRecorder
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
        } catch (_: Exception) {
            // If MediaRecorder fails (e.g. in test env or missing mic hardware), continue gracefully with speech recognizer
            mediaRecorder = null
        }

        // 2. Setup SpeechRecognizer for Live Streaming Voice-to-Text
        startSpeechListeningSession()

        // 3. Duration Timer
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isRecording) {
                delay(1000)
                durationSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000
                onDurationTick?.invoke(durationSeconds)
            }
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
                        // Normalize -2dB..10dB to 0.0..1.0
                        val norm = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                        onRmsChanged?.invoke(norm)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        // On pause or error, seamlessly restart listening if recording is still active
                        if (isRecording) {
                            scope.launch {
                                delay(300)
                                startSpeechListeningSession()
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
        } catch (_: Exception) {
            // Speech recognizer fallback
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
