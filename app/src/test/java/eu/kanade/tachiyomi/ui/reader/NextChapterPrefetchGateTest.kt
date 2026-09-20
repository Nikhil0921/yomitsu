package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NextChapterPrefetchGateTest {

    @Test
    fun sameActiveChapterCannotSubmitTwice() {
        val gate = NextChapterPrefetchGate()

        assertTrue(gate.shouldSubmit(8084L))
        assertFalse(gate.shouldSubmit(8084L))
        assertFalse(gate.shouldSubmit(8084L))
    }

    @Test
    fun chapterChangePermitsNewChapter() {
        val gate = NextChapterPrefetchGate()

        assertTrue(gate.shouldSubmit(8084L))
        assertTrue(gate.shouldSubmit(8085L))
        assertFalse(gate.shouldSubmit(8085L))
    }
}
