package eu.kanade.tachiyomi.ui.reader

/**
 * One-shot guard for next-chapter image prefetch: at most one submission per
 * active chapter id per reader session. When the active chapter changes the
 * gate naturally re-arms for the new next chapter.
 */
internal class NextChapterPrefetchGate {
    private var submittedActiveChapterId: Long? = null

    fun shouldSubmit(activeChapterId: Long): Boolean {
        if (submittedActiveChapterId == activeChapterId) return false
        submittedActiveChapterId = activeChapterId
        return true
    }
}
