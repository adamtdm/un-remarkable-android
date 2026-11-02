package com.unremarkable.android.audio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.unremarkable.android.storage.PersistentDocumentRepository
import com.unremarkable.core.cloud.BlobStore
import com.unremarkable.core.model.DocumentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object MeetingNotebookGenerator {

    const val DEFAULT_MEETING_FOLDER_NAME = "Meeting Notes"

    private const val PAGE_WIDTH = 1404
    private const val PAGE_HEIGHT = 1872
    private const val MARGIN_X = 80f
    private const val USABLE_WIDTH = PAGE_WIDTH - (MARGIN_X * 2)

    fun resolveOrCreateMeetingNotesFolder(
        repository: com.unremarkable.core.model.SyncRepository,
        timestampMs: Long = System.currentTimeMillis()
    ): DocumentItem {
        val allDocs = repository.getAllDocuments()
        val existing = allDocs
            .filter { it.isFolder && !it.deleted && it.parent != "trash" && it.visibleName.trim().equals(DEFAULT_MEETING_FOLDER_NAME, ignoreCase = true) }
            .sortedWith(compareBy<DocumentItem> { it.parent.isNotEmpty() }.thenBy { it.localOnly })
            .firstOrNull()
        if (existing != null) return existing

        val created = DocumentItem(
            id = UUID.randomUUID().toString(),
            visibleName = DEFAULT_MEETING_FOLDER_NAME,
            parent = "",
            isFolder = true,
            lastModified = timestampMs,
            localOnly = true
        )
        repository.upsertDocument(created)
        return created
    }

    suspend fun generateMeetingNotebook(
        repository: PersistentDocumentRepository,
        blobStore: BlobStore,
        meetingTitle: String,
        durationSeconds: Long,
        userAgenda: String,
        transcriptText: String,
        audioFile: File? = null,
        dateTimestamp: Long = System.currentTimeMillis()
    ): DocumentItem = withContext(Dispatchers.Default) {
        val docId = UUID.randomUUID().toString()
        val pdfDoc = PdfDocument()

        val safeTitle = meetingTitle.ifBlank {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(dateTimestamp))
            "Meeting - $dateStr"
        }

        val dateFormatted = SimpleDateFormat("EEEE, MMMM d, yyyy • h:mm a", Locale.US).format(Date(dateTimestamp))
        val durationFormatted = MeetingRecorderEngine.formatDuration(durationSeconds)

        val transcriptLines = prepareWrappedTranscriptLines(transcriptText)
        val linesPerPage = 32
        val transcriptPagesCount = (transcriptLines.size + linesPerPage - 1) / linesPerPage
        val totalPages = 1 + transcriptPagesCount.coerceAtLeast(1)

        // ================= PAGE 1: MEETING OVERVIEW & NOTES WORKSPACE =================
        val page1Info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page1 = pdfDoc.startPage(page1Info)
        val canvas1 = page1.canvas
        canvas1.drawColor(Color.WHITE)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#18181B") }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#71717A") }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E4E4E7")
            strokeWidth = 1.5f
        }
        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F4F4F5")
            style = Paint.Style.FILL
        }

        var y = 100f

        // Document Category Pill
        canvas1.drawRoundRect(MARGIN_X, y - 28f, MARGIN_X + 220f, y + 10f, 16f, 16f, pillBgPaint)
        mutedPaint.textSize = 24f
        mutedPaint.isFakeBoldText = true
        canvas1.drawText("MEETING NOTEBOOK", MARGIN_X + 18f, y - 2f, mutedPaint)
        y += 54f

        // Title
        textPaint.textSize = 52f
        textPaint.isFakeBoldText = true
        canvas1.drawText(safeTitle, MARGIN_X, y, textPaint)
        y += 42f

        // Metadata Subtitle
        mutedPaint.textSize = 28f
        mutedPaint.isFakeBoldText = false
        canvas1.drawText(dateFormatted, MARGIN_X, y, mutedPaint)
        y += 48f

        // Badges Bar (Duration + Audio File Indicator + Transcript Pointer)
        val badgeW = 200f
        canvas1.drawRoundRect(MARGIN_X, y - 26f, MARGIN_X + badgeW, y + 16f, 12f, 12f, pillBgPaint)
        mutedPaint.textSize = 22f
        mutedPaint.isFakeBoldText = true
        canvas1.drawText("Duration: $durationFormatted", MARGIN_X + 16f, y, mutedPaint)

        if (audioFile != null) {
            val audioBadgeW = 240f
            val audioBadgeX = MARGIN_X + badgeW + 16f
            canvas1.drawRoundRect(audioBadgeX, y - 26f, audioBadgeX + audioBadgeW, y + 16f, 12f, 12f, pillBgPaint)
            canvas1.drawText("Audio File Attached", audioBadgeX + 16f, y, mutedPaint)
        }

        val transcriptBadgeX = if (audioFile != null) MARGIN_X + badgeW + 240f + 32f else MARGIN_X + badgeW + 16f
        canvas1.drawRoundRect(transcriptBadgeX, y - 26f, transcriptBadgeX + 310f, y + 16f, 12f, 12f, pillBgPaint)
        canvas1.drawText("Transcript: Page 2 of $totalPages", transcriptBadgeX + 16f, y, mutedPaint)
        y += 60f

        // Divider
        canvas1.drawLine(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y, linePaint)
        y += 50f

        // Pre-Meeting Agenda / Objectives Block
        textPaint.textSize = 32f
        textPaint.isFakeBoldText = true
        canvas1.drawText("Agenda & Objectives", MARGIN_X, y, textPaint)
        y += 36f

        mutedPaint.textSize = 26f
        mutedPaint.isFakeBoldText = false
        val agendaText = userAgenda.ifBlank { "Live audio recording & transcription session. Ready for handwritten notes and takeaways." }
        val wrappedAgenda = wrapText(agendaText, USABLE_WIDTH, mutedPaint)
        for (line in wrappedAgenda.take(3)) {
            canvas1.drawText(line, MARGIN_X, y, mutedPaint)
            y += 36f
        }
        y += 20f

        // Notes & Actions Section Header
        canvas1.drawLine(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y, linePaint)
        y += 46f

        textPaint.textSize = 32f
        textPaint.isFakeBoldText = true
        canvas1.drawText("Handwritten Notes & Actions", MARGIN_X, y, textPaint)
        mutedPaint.textSize = 22f
        mutedPaint.isFakeBoldText = false
        canvas1.drawText("Write directly with your stylus or finger below", MARGIN_X + 480f, y - 2f, mutedPaint)
        y += 36f

        // Ruled Note-Taking Template Lines
        val bottomMargin = PAGE_HEIGHT - 120f
        val remainingSpace = bottomMargin - y
        val noteLineSpacing = 56f
        val noteLinesCount = (remainingSpace / noteLineSpacing).toInt()

        for (i in 0 until noteLinesCount) {
            val lineY = y + (i * noteLineSpacing)
            canvas1.drawLine(MARGIN_X, lineY, PAGE_WIDTH - MARGIN_X, lineY, linePaint)
        }

        // Footer
        mutedPaint.textSize = 20f
        canvas1.drawText("Page 1 of $totalPages • Swipe left for Meeting Transcript", MARGIN_X, PAGE_HEIGHT - 60f, mutedPaint)

        pdfDoc.finishPage(page1)

        // ================= PAGE 2+ : MEETING TRANSCRIPT =================
        var lineIndex = 0
        var currentTranscriptPage = 2

        while (currentTranscriptPage <= totalPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, currentTranscriptPage).create()
            val page = pdfDoc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            var py = 100f

            // Transcript Header
            textPaint.textSize = 38f
            textPaint.isFakeBoldText = true
            canvas.drawText("Meeting Transcript", MARGIN_X, py, textPaint)

            mutedPaint.textSize = 24f
            mutedPaint.isFakeBoldText = false
            canvas.drawText("$safeTitle • Page $currentTranscriptPage of $totalPages", MARGIN_X + 420f, py - 2f, mutedPaint)
            py += 30f

            canvas.drawLine(MARGIN_X, py, PAGE_WIDTH - MARGIN_X, py, linePaint)
            py += 44f

            val pageEndLine = minOf(lineIndex + linesPerPage, transcriptLines.size)
            if (lineIndex >= transcriptLines.size) {
                // Empty transcript placeholder
                mutedPaint.textSize = 28f
                canvas.drawText("(No audible speech detected during recording)", MARGIN_X, py + 100f, mutedPaint)
            } else {
                for (i in lineIndex until pageEndLine) {
                    val line = transcriptLines[i]
                    if (line.startsWith("[") && line.contains("]")) {
                        // Speaker / Timestamp line
                        textPaint.textSize = 26f
                        textPaint.isFakeBoldText = true
                        textPaint.color = Color.parseColor("#2563EB") // Blue accent for timestamps
                        canvas.drawText(line, MARGIN_X, py, textPaint)
                        textPaint.color = Color.parseColor("#18181B")
                    } else {
                        // Transcript dialogue
                        textPaint.textSize = 25f
                        textPaint.isFakeBoldText = false
                        canvas.drawText(line, MARGIN_X + 16f, py, textPaint)
                    }

                    // Background faint ruled guide line
                    canvas.drawLine(MARGIN_X, py + 8f, PAGE_WIDTH - MARGIN_X, py + 8f, linePaint)
                    py += 44f
                }
            }

            lineIndex = pageEndLine

            // Page Footer
            mutedPaint.textSize = 20f
            canvas.drawText("Page $currentTranscriptPage of $totalPages", MARGIN_X, PAGE_HEIGHT - 60f, mutedPaint)

            pdfDoc.finishPage(page)
            currentTranscriptPage++
        }

        // ================= SAVE TO DISK & PERSISTENT REPOSITORY =================
        val baos = java.io.ByteArrayOutputStream()
        pdfDoc.writeTo(baos)
        pdfDoc.close()
        val pdfBytes = baos.toByteArray()
        val pdfHash = com.unremarkable.core.cloud.FileBlobStore.sha256(pdfBytes)
        blobStore.putBlob(pdfHash, pdfBytes)

        val meetingFolder = resolveOrCreateMeetingNotesFolder(repository, dateTimestamp)

        val docItem = DocumentItem(
            id = docId,
            visibleName = safeTitle,
            parent = meetingFolder.id,
            isFolder = false,
            pageCount = totalPages,
            pinned = true,
            lastModified = dateTimestamp,
            tags = listOf("Meeting", "Transcript"),
            fileType = "pdf",
            hash = pdfHash,
            subfiles = mapOf("$docId.pdf" to pdfHash),
            pages = (0 until totalPages).map { idx -> com.unremarkable.core.model.PageRef(UUID.randomUUID().toString(), idx) },
            localOnly = true
        )

        repository.upsertDocument(docItem)
        docItem
    }

    private fun prepareWrappedTranscriptLines(transcript: String): List<String> {
        if (transcript.isBlank()) return emptyList()

        val paint = Paint().apply { textSize = 25f }
        val result = mutableListOf<String>()

        val paragraphs = transcript.split("\n\n", "\n")
        for (paragraph in paragraphs) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("[") && trimmed.contains("]")) {
                val closeBracket = trimmed.indexOf("]")
                val timeStamp = trimmed.substring(0, closeBracket + 1)
                val speech = trimmed.substring(closeBracket + 1).trim()
                result.add(timeStamp)
                if (speech.isNotEmpty()) {
                    val wrapped = wrapText(speech, USABLE_WIDTH - 20f, paint)
                    result.addAll(wrapped)
                }
            } else {
                val wrapped = wrapText(trimmed, USABLE_WIDTH, paint)
                result.addAll(wrapped)
            }
        }
        return result
    }

    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val lines = mutableListOf<String>()
        val words = text.split(" ")
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = StringBuilder(testLine)
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                currentLine = StringBuilder(word)
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines
    }
}
