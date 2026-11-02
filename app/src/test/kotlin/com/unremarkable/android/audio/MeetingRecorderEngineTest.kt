package com.unremarkable.android.audio

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
}
