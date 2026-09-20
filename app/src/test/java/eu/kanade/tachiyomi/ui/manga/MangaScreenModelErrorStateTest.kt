package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.ocr.OcrScanManager
import eu.kanade.tachiyomi.data.ocr.OcrScanQueueState
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import logcat.LogPriority
import logcat.LogcatLogger
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.ocr.interactor.GetCachedChapterIdsOcr
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MangaScreenModelErrorStateTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeAll
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Unit tests have no android.util.Log; swallow logcat calls.
        LogcatLogger.install(
            object : LogcatLogger {
                override fun isLoggable(priority: LogPriority) = true
                override fun isLoggable(priority: LogPriority, tag: String) = true
                override fun log(priority: LogPriority, tag: String, message: String) {}
            },
        )
    }

    @AfterAll
    fun tearDown() {
        // Class-scoped setMain/resetMain: the models' flowWithLifecycle workers
        // (Voyager's cached screenModelScope is process-global in JVM tests)
        // touch Dispatchers.Main from IO threads; a per-test resetMain/setMain
        // gap lets a worker observe a null delegate and fail the next test.
        LogcatLogger.uninstall()
        Dispatchers.resetMain()
    }

    @Test
    fun `missing manga publishes Error missing instead of staying Loading`() = runTest(dispatcher) {
        val model = createModel(
            getMangaAndChapters = mockk {
                coEvery { subscribe(any(), any()) } returns MutableStateFlow(Pair(anyManga(), emptyList()))
                coEvery { awaitManga(any()) } throws NullPointerException("ResultSet returned no rows")
            },
        )

        val state = awaitNonLoading(model)

        assertTrue(state is MangaScreenModel.State.Error)
        assertTrue((state as MangaScreenModel.State.Error).missing)
    }

    @Test
    fun `generic load failure publishes retryable Error`() = runTest(dispatcher) {
        val model = createModel(
            getMangaAndChapters = mockk {
                coEvery { subscribe(any(), any()) } returns MutableStateFlow(Pair(anyManga(), emptyList()))
                coEvery { awaitManga(any()) } throws IllegalStateException("database closed")
            },
        )

        val state = awaitNonLoading(model)

        assertTrue(state is MangaScreenModel.State.Error)
        assertFalse((state as MangaScreenModel.State.Error).missing)
    }

    @Test
    fun `retry from generic error can recover to Success`() = runTest(dispatcher) {
        val manga = favoriteManga()
        val interactor = mockk<GetMangaWithChapters> {
            coEvery { subscribe(any(), any()) } returns MutableStateFlow(Pair(manga, emptyList()))
            coEvery { awaitManga(any()) } throws IllegalStateException("database closed")
        }
        val model = createModel(getMangaAndChapters = interactor)

        assertTrue(awaitNonLoading(model) is MangaScreenModel.State.Error)

        coEvery { interactor.awaitManga(any()) } returns manga
        coEvery { interactor.awaitChapters(any(), any()) } returns emptyList()
        model.load()
        val state = awaitNonLoading(model)

        assertTrue(state is MangaScreenModel.State.Success)
    }

    @Test
    fun `valid manga still reaches Success`() = runTest(dispatcher) {
        val manga = favoriteManga()
        val model = createModel(
            getMangaAndChapters = mockk {
                coEvery { subscribe(any(), any()) } returns MutableStateFlow(Pair(manga, emptyList()))
                coEvery { awaitManga(any()) } returns manga
                coEvery { awaitChapters(any(), any()) } returns emptyList()
            },
        )

        val state = awaitNonLoading(model)

        assertTrue(state is MangaScreenModel.State.Success)
    }

    /**
     * load() runs on Dispatchers.IO (launchIO), outside the test scheduler —
     * poll in real time until Loading resolves.
     */
    private fun awaitNonLoading(
        model: MangaScreenModel,
        timeoutMs: Long = 10_000,
    ): MangaScreenModel.State {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (model.state.value is MangaScreenModel.State.Loading) {
            check(System.nanoTime() < deadline) { "Timed out waiting for non-Loading state" }
            Thread.sleep(10)
        }
        return model.state.value
    }

    private fun anyManga() = Manga.create()

    private fun favoriteManga() = Manga.create().copy(id = 1L, favorite = true, initialized = true)

    private fun createModel(
        getMangaAndChapters: GetMangaWithChapters,
    ): MangaScreenModel {
        val lifecycleOwner = mockk<LifecycleOwner>()
        // Mocked lifecycle: collectors stay idle (no android Looper in unit
        // tests), while load() itself needs no lifecycle events.
        val lifecycle = mockk<Lifecycle>(relaxed = true)
        every { lifecycle.currentState } returns Lifecycle.State.INITIALIZED
        every { lifecycleOwner.lifecycle } returns lifecycle

        val downloadManager = mockk<DownloadManager> {
            every { queueState } returns MutableStateFlow<List<Download>>(emptyList())
            every { statusFlow() } returns MutableStateFlow(mockk<Download>(relaxed = true))
            every { progressFlow() } returns MutableStateFlow(mockk<Download>(relaxed = true))
        }
        val downloadCache = mockk<DownloadCache> {
            every { changes } returns MutableStateFlow(Unit)
        }
        val ocrScanManager = mockk<OcrScanManager> {
            every { cacheEvents } returns MutableStateFlow(mockk(relaxed = true))
            every { queueState } returns MutableStateFlow(
                OcrScanQueueState(
                    entries = emptyList(),
                    activeProgress = null,
                    isPaused = false,
                ),
            )
        }

        return MangaScreenModel(
            context = mockk(relaxed = true),
            lifecycle = lifecycle,
            mangaId = 1L,
            isFromSource = false,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            trackPreferences = TrackPreferences(InMemoryPreferenceStore()),
            readerPreferences = mockk(relaxed = true),
            trackerManager = mockk(relaxed = true),
            trackChapter = mockk(relaxed = true),
            downloadManager = downloadManager,
            downloadCache = downloadCache,
            getMangaAndChapters = getMangaAndChapters,
            getDuplicateLibraryManga = mockk(relaxed = true),
            getAvailableScanlators = mockk {
                every { subscribe(any()) } returns MutableStateFlow(emptySet())
                coEvery { await(any()) } returns emptySet()
            },
            getExcludedScanlators = mockk {
                every { subscribe(any()) } returns MutableStateFlow(emptySet())
                coEvery { await(any()) } returns emptySet()
            },
            setExcludedScanlators = mockk(relaxed = true),
            setMangaChapterFlags = mockk(relaxed = true),
            setMangaDefaultChapterFlags = mockk(relaxed = true),
            setReadStatus = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            getCategories = mockk(relaxed = true),
            getTracks = mockk(relaxed = true),
            addTracks = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            filterChaptersForDownload = mockk(relaxed = true),
            getCachedChapterIdsOcr = mockk(relaxed = true),
            ocrScanManager = ocrScanManager,
            updateMangaFromRemote = mockk(relaxed = true),
            sourceManager = mockk(relaxed = true),
        )
    }
}
