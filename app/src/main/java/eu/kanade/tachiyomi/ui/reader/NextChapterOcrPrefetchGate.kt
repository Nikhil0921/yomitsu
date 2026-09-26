package eu.kanade.tachiyomi.ui.reader

/**
 * One-shot guard for next-chapter OCR prefetch: at most one submission per
 * next chapter id per reader session. Prevents duplicate enqueue of the same
 * background OCR scan chapter while the reader is open.
 */
internal class NextChapterOcrPrefetchGate {
    private var submittedChapterId: Long? = null

    fun shouldSubmit(chapterId: Long): Boolean {
        if (submittedChapterId == chapterId) return false
        submittedChapterId = chapterId
        return true
    }
}
