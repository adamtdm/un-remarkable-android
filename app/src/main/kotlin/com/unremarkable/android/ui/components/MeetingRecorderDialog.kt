package com.unremarkable.android.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.unremarkable.android.audio.MeetingNotebookGenerator
import com.unremarkable.android.audio.MeetingRecorderEngine
import com.unremarkable.android.storage.PersistentDocumentRepository
import com.unremarkable.android.ui.theme.Zinc800
import com.unremarkable.android.ui.theme.Zinc900
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.model.DocumentItem
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class MeetingModeTab {
    LIVE_RECORD,
    GOOGLE_MEET_IMPORT
}

@Composable
fun MeetingRecorderDialog(
    repository: PersistentDocumentRepository,
    blobStore: BlobStore,
    onNotebookCreated: (DocumentItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(MeetingModeTab.LIVE_RECORD) }

    val defaultTitle = remember {
        val dateStr = SimpleDateFormat("EEEE, MMM d • h:mm a", Locale.US).format(Date())
        "Meeting - $dateStr"
    }
    var meetingTitle by remember { mutableStateOf(defaultTitle) }
    var userAgenda by remember { mutableStateOf("") }

    // Live Recording Engine State
    val engine = remember { MeetingRecorderEngine(context) }
    var isRecording by remember { mutableStateOf(false) }
    var durationSeconds by remember { mutableLongStateOf(0L) }
    var rmsVolume by remember { mutableFloatStateOf(0.1f) }
    var accumulatedTranscript by remember { mutableStateOf("") }
    var livePartialText by remember { mutableStateOf("") }
    var recordedAudioFile by remember { mutableStateOf<File?>(null) }

    // Google Meet Import State
    var googleMeetText by remember { mutableStateOf("") }

    var isCreatingNotebook by remember { mutableStateOf(false) }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isRecording = true
            engine.startRecording(meetingTitle)
        } else {
            Toast.makeText(context, "Microphone permission is required to record meetings.", Toast.LENGTH_LONG).show()
        }
    }

    // Connect engine callbacks
    LaunchedEffect(engine) {
        engine.onTranscriptChanged = { full, partial ->
            accumulatedTranscript = full
            livePartialText = partial
        }
        engine.onRmsChanged = { rms ->
            rmsVolume = rms
        }
        engine.onDurationTick = { sec ->
            durationSeconds = sec
        }
    }

    DisposableEffect(engine) {
        onDispose {
            if (isRecording) {
                engine.stopRecording()
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (isRecording) {
                engine.stopRecording()
            }
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Meeting Companion",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Record, transcribe, and convert into a reMarkable notebook",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = {
                        if (isRecording) engine.stopRecording()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tabs: Live Record vs Google Meet Import
                TabRow(
                    selectedTabIndex = activeTab.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = activeTab == MeetingModeTab.LIVE_RECORD,
                        onClick = { activeTab = MeetingModeTab.LIVE_RECORD },
                        text = { Text("Live Audio & Transcription") },
                        icon = { Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = activeTab == MeetingModeTab.GOOGLE_MEET_IMPORT,
                        onClick = { activeTab = MeetingModeTab.GOOGLE_MEET_IMPORT },
                        text = { Text("Google Meet Import") },
                        icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Meeting Title
                OutlinedTextField(
                    value = meetingTitle,
                    onValueChange = { meetingTitle = it },
                    label = { Text("Meeting Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (activeTab) {
                    MeetingModeTab.LIVE_RECORD -> {
                        // Live Audio & Voice Transcription View
                        Surface(
                            color = Zinc900,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Status Bar: Pulsing indicator & Timer
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isRecording) {
                                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                            val scale by infiniteTransition.animateFloat(
                                                initialValue = 0.8f,
                                                targetValue = 1.3f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(600, easing = FastOutSlowInEasing),
                                                    repeatMode = RepeatMode.Reverse
                                                ),
                                                label = "scale"
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .scale(scale)
                                                    .background(Color(0xFFEF4444), CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "RECORDING & TRANSCRIBING",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFEF4444)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(Color(0xFF71717A), CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "MIC READY",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFA1A1AA)
                                            )
                                        }
                                    }

                                    Text(
                                        text = MeetingRecorderEngine.formatDuration(durationSeconds),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Sound Waveform Bars Animation
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val barCount = 20
                                    for (i in 0 until barCount) {
                                        val factor = if (isRecording) {
                                            ((Math.sin((i.toDouble() + (durationSeconds * 2)) * 0.8).toFloat() + 1.2f) * rmsVolume * 0.5f).coerceIn(0.1f, 1.0f)
                                        } else {
                                            0.1f
                                        }
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .fillMaxHeight(factor)
                                                .background(
                                                    if (isRecording) Color(0xFF3B82F6) else Color(0xFF3F3F46),
                                                    RoundedCornerShape(2.dp)
                                                )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Live Transcript Box
                                Text(
                                    text = "Live Meeting Transcript",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA1A1AA)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 100.dp, max = 180.dp)
                                        .padding(top = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF18181B))
                                        .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    val displayString = buildString {
                                        if (accumulatedTranscript.isNotBlank()) {
                                            append(accumulatedTranscript)
                                        }
                                        if (livePartialText.isNotBlank()) {
                                            if (isNotEmpty()) append(" ")
                                            append("⏳ $livePartialText...")
                                        }
                                    }

                                    if (displayString.isBlank()) {
                                        Text(
                                            text = if (isRecording) "Listening... Speak naturally." else "Press Start Recording to begin live speech-to-text.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF71717A)
                                        )
                                    } else {
                                        Text(
                                            text = displayString,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFF4F4F5),
                                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Recording Toggle Button
                                if (!isRecording) {
                                    Button(
                                        onClick = {
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED

                                            if (hasPermission) {
                                                isRecording = true
                                                engine.startRecording(meetingTitle)
                                            } else {
                                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Start Meeting Recording")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            val result = engine.stopRecording()
                                            isRecording = false
                                            recordedAudioFile = result.audioFile
                                            accumulatedTranscript = result.transcript
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Stop Recording")
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Agenda / Objectives Input
                        OutlinedTextField(
                            value = userAgenda,
                            onValueChange = { userAgenda = it },
                            label = { Text("Agenda / Key Objectives (Optional)") },
                            placeholder = { Text("e.g. Q4 roadmap priorities and release deadline") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    MeetingModeTab.GOOGLE_MEET_IMPORT -> {
                        // Google Meet Transcript Import View
                        Column {
                            Text(
                                text = "Paste Google Meet Transcript or Captions",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Download the transcript from Google Meet or copy captions directly. It will be formatted onto Page 2+ of your reMarkable notebook.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = googleMeetText,
                                onValueChange = { googleMeetText = it },
                                placeholder = {
                                    Text("00:01 Alex: Let's begin the review...\n00:05 Sarah: The mobile app companion is ready...")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Create Notebook Action
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isCreatingNotebook = true
                            try {
                                if (isRecording) {
                                    val res = engine.stopRecording()
                                    isRecording = false
                                    recordedAudioFile = res.audioFile
                                    accumulatedTranscript = res.transcript
                                }

                                val finalTranscript = if (activeTab == MeetingModeTab.GOOGLE_MEET_IMPORT) {
                                    googleMeetText
                                } else {
                                    accumulatedTranscript
                                }

                                val notebook = MeetingNotebookGenerator.generateMeetingNotebook(
                                    repository = repository,
                                    blobStore = blobStore,
                                    meetingTitle = meetingTitle,
                                    durationSeconds = durationSeconds,
                                    userAgenda = userAgenda,
                                    transcriptText = finalTranscript,
                                    audioFile = recordedAudioFile
                                )

                                Toast.makeText(context, "Created Notebook: ${notebook.visibleName} (${notebook.pageCount} pages)", Toast.LENGTH_SHORT).show()
                                onNotebookCreated(notebook)
                                onDismiss()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Creation error: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isCreatingNotebook = false
                            }
                        }
                    },
                    enabled = !isCreatingNotebook,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCreatingNotebook) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generating Multi-Page Notebook...")
                    } else {
                        Icon(Icons.Default.AutoStories, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Meeting Notebook (Notes on p.1 • Transcript on p.2)")
                    }
                }
            }
        }
    }
}
