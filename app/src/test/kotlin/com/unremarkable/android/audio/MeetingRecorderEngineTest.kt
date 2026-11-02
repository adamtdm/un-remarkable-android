package com.unremarkable.android.audio

import com.unremarkable.core.model.DocumentItem
import com.unremarkable.core.model.InMemorySyncRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MeetingRecorderEngineTest {

    @Test
    fun `test duration formatting for minutes and seconds`() {
        assertEquals("00:00", MeetingRecorderEngine.formatDuration(0))
        assertEquals("00:05", MeetingRecorderEngine.formatDuration(5))
        assertEquals("01:15", MeetingRecorderEngine.formatDuration(75))
        assertEquals("10:00", MeetingRecorderEngine.formatDuration(600))
        assertEquals("61:23", MeetingRecorderEngine.formatDuration(3683))
    }

    @Test
    fun `resolveOrCreateMeetingNotesFolder creates folder when absent and reuses existing`() {
        val repo = InMemorySyncRepository()

        // Creates "Meeting Notes" folder when absent
        val created = MeetingNotebookGenerator.resolveOrCreateMeetingNotesFolder(repo, timestampMs = 1000L)
        assertEquals("Meeting Notes", created.visibleName)
        assertTrue(created.isFolder)
        assertEquals("", created.parent)
        assertTrue(created.localOnly)
        assertEquals(created.id, repo.getDocument(created.id)?.id)

        // Calling again reuses the existing folder instead of duplicating
        val reused = MeetingNotebookGenerator.resolveOrCreateMeetingNotesFolder(repo, timestampMs = 2000L)
        assertEquals(created.id, reused.id)
        assertEquals(1, repo.getAllDocuments().count { it.isFolder })

        // Ignores trashed "Meeting Notes" folders and prefers active cloud folder
        val repoWithTrash = InMemorySyncRepository()
        repoWithTrash.upsertDocument(
            DocumentItem(id = "trashed-id", visibleName = "Meeting Notes", parent = "trash", isFolder = true)
        )
        repoWithTrash.upsertDocument(
            DocumentItem(id = "cloud-meeting-id", visibleName = "meeting notes", parent = "", isFolder = true, localOnly = false)
        )
        val resolved = MeetingNotebookGenerator.resolveOrCreateMeetingNotesFolder(repoWithTrash)
        assertEquals("cloud-meeting-id", resolved.id)
    }
}
