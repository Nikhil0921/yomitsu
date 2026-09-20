package eu.kanade.tachiyomi.ui.reader

/**
 * One-shot guard for reader-open OCR prefetch: at most one submission per
 * chapter id per reader session. Keyed by chapter id, so a chapter change is
 * inherently a new submittable key and a stale chapter can never resubmit.
 */
internal class ReaderOpenPrefetchGate {
    private var submittedChapterId: Long? = null

    fun shouldSubmit(chapterId: Long): Boolean {
        if (submittedChapterId == chapterId) return false
        submittedChapterId = chapterId
        return true
    }
}
