package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderOpenPrefetchGateTest {

    @Test
    fun sameChapterCannotSubmitTwice() {
        val gate = ReaderOpenPrefetchGate()

        assertTrue(gate.shouldSubmit(8084L))
        assertFalse(gate.shouldSubmit(8084L))
        assertFalse(gate.shouldSubmit(8084L))
    }

    @Test
    fun chapterChangePermitsNewChapter() {
        val gate = ReaderOpenPrefetchGate()

        assertTrue(gate.shouldSubmit(8084L))
        assertTrue(gate.shouldSubmit(9999L))
        assertFalse(gate.shouldSubmit(9999L))
    }
}
