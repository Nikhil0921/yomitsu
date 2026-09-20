package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceResolver
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.tts.TtsChapterContext
import eu.kanade.tachiyomi.ui.reader.tts.TtsError
import eu.kanade.tachiyomi.ui.reader.tts.TtsEvent
import eu.kanade.tachiyomi.ui.reader.tts.TtsPhase
import eu.kanade.tachiyomi.ui.reader.tts.TtsPlaybackController
import eu.kanade.tachiyomi.ui.reader.tts.TtsPlaybackState
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.ocr.toOcrImage
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.interactor.AddOcrExclusionZone
import mihon.domain.ocr.interactor.DeleteOcrExclusionZone
import mihon.domain.ocr.interactor.GetCachedPageOcr
import mihon.domain.ocr.interactor.GetOcrExclusionZones
import mihon.domain.ocr.interactor.OcrProcessor
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.interactor.SetOcrExclusionZoneEnabled
import mihon.domain.ocr.interactor.WithOcrScanSession
import mihon.domain.ocr.model.OcrExclusionMatchType
import mihon.domain.ocr.model.OcrExclusionScope
import mihon.domain.ocr.model.OcrExclusionZone
import mihon.domain.ocr.model.flattenOcrTextForQuery
import mihon.domain.ocr.repository.OcrRepository
import mihon.domain.panel.repository.PanelDetectionRepository
import mihon.domain.tts.engine.TtsEngine
import mihon.domain.tts.service.TtsPreferences
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import java.util.Date

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setMangaViewerFlags: SetMangaViewerFlags = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val application: Application = Injekt.get(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val ocrProcessor: OcrProcessor by injectLazy()

    // Read-aloud (TTS) collaborators; created only when the user starts playback.
    private val ttsEngine: TtsEngine by injectLazy()
    private val ttsPreferences: TtsPreferences by injectLazy()
    private val getCachedPageOcr: GetCachedPageOcr by injectLazy()
    private val scanPageOcr: ScanPageOcr by injectLazy()
    private val withOcrScanSession: WithOcrScanSession by injectLazy()
    private val pageSourceResolver: OcrPageSourceResolver by injectLazy()
    private val getOcrExclusionZones: GetOcrExclusionZones by injectLazy()
    private val addOcrExclusionZone: AddOcrExclusionZone by injectLazy()
    private val deleteOcrExclusionZone: DeleteOcrExclusionZone by injectLazy()
    private val setOcrExclusionZoneEnabled: SetOcrExclusionZoneEnabled by injectLazy()

    private var ttsControllerInstance: TtsPlaybackController? = null
    private val ttsController: TtsPlaybackController
        get() = ttsControllerInstance ?: createTtsController().also { ttsControllerInstance = it }

    /** Stage 2D experiment: single eager TTS engine init job (no focus, no speech). */
    private var ttsEagerInitJob: Job? = null

    /** Debounce job for TTS page-selected during rapid swipes (avoids mass OCR). */
    private var ttsPageSelectedJob: Job? = null

    /** Reader-open OCR prefetch: one opportunistic first-page scan per chapter. */
    private var readerOpenPrefetchJob: Job? = null
    private val readerOpenPrefetchGate = ReaderOpenPrefetchGate()

    private fun createTtsController(): TtsPlaybackController {
        val controller = TtsPlaybackController(
            scope = viewModelScope,
            engine = ttsEngine,
            preferences = ttsPreferences,
            getCachedPageOcr = getCachedPageOcr,
            scanPageOcr = scanPageOcr,
            withOcrScanSession = withOcrScanSession,
            pageSourceResolver = pageSourceResolver,
            getExclusionZones = getOcrExclusionZones,
            provideContext = ::buildTtsChapterContext,
        )
        viewModelScope.launch {
            controller.state.collect { ttsState ->
                mutableState.update { it.copy(ttsState = ttsState) }
            }
        }
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is TtsEvent.AdvancePage -> eventChannel.send(Event.TtsAdvancePage(event.pageIndex))
                    TtsEvent.AdvanceChapter -> eventChannel.send(Event.TtsAdvanceChapter)
                    is TtsEvent.Failed -> {
                        if (event.error == TtsError.NoTextFound) {
                            eventChannel.send(Event.TtsNoTextFound)
                        } else {
                            eventChannel.send(Event.TtsError(event.error))
                        }
                    }
                    is TtsEvent.ScrollToRegion -> eventChannel.send(
                        Event.TtsScrollToRegion(event.pageIndex, event.bbox),
                    )
                }
            }
        }
        return controller
    }

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val chapters = runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            it.url,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private val incognitoMode: Boolean by lazy { getIncognitoState.await(manga?.source) }
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        // Rebind read-aloud playback to whatever chapter becomes active while it runs
        // (auto chapter advance and user navigation through chapter transitions).
        state.map { it.viewerChapters?.currChapter?.chapter?.id }
            .distinctUntilChanged()
            .drop(1)
            .onEach { onTtsChapterChanged() }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        ttsControllerInstance?.let { controller ->
            controller.stop()
            ttsEngine.shutdown()
            // Detach the controller from the singleton engine: the system TTS
            // service keeps a native GC root to the TextToSpeech callback, which
            // reaches the engine, and onFocusEvent would retain the dead
            // controller -> this ViewModel -> destroyed ReaderActivity (~100 MB,
            // LeakCanary 2026-08-28). Next session re-registers in controller init.
            ttsEngine.onFocusEvent = null
        }
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
        // Already checks if resources are initialized
        Injekt.get<OcrRepository>().cleanup()
        Injekt.get<PanelDetectionRepository>().cleanup()
        super.onCleared()
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        ttsControllerInstance?.stop()
        deletePendingChapters()
    }

    /**
     * Starts read-aloud playback for the current chapter at the visible page.
     * No-op when playback is already running or the page isn't ready.
     */
    fun startReadAloud() {
        val phase = state.value.ttsState.phase
        if (phase != TtsPhase.Idle && phase != TtsPhase.Finished && phase != TtsPhase.Error) return
        startReadAloudAt((state.value.currentPage - 1).coerceAtLeast(0))
    }

    private fun startReadAloudAt(pageIndex: Int) {
        val context = buildTtsChapterContext() ?: return
        ttsController.start(context, pageIndex)
    }

    /**
     * Reader-open OCR prefetch: once per chapter, best-effort NORMAL scan of
     * the current page so GLENS work overlaps reader dwell time. Later TTS
     * acquisition joins the in-flight scan or hits the cache. Skipped while a
     * TTS session is active (its own prefetch already covers these pages).
     */
    private fun maybePrefetchReaderOpenOcr(pageIndex: Int) {
        val instance = ttsControllerInstance
        if (instance != null) {
            val phase = instance.state.value.phase
            if (phase != TtsPhase.Idle && phase != TtsPhase.Finished && phase != TtsPhase.Error) return
        }
        val context = buildTtsChapterContext() ?: return
        if (!readerOpenPrefetchGate.shouldSubmit(context.chapter.id)) return
        readerOpenPrefetchJob?.cancel()
        readerOpenPrefetchJob = viewModelScope.launch {
            // Re-check currency: the chapter may have changed since selection.
            val current = buildTtsChapterContext()
            if (current == null || current.chapter.id != context.chapter.id) return@launch
            ttsController.prefetchReaderOpenPage(current, pageIndex)
        }
    }

    /** Builds playback context for the currently active chapter, or null while it isn't ready. */
    private fun buildTtsChapterContext(): TtsChapterContext? {
        val manga = manga ?: return null
        val chapter = getCurrentChapter() ?: return null
        val pages = chapter.pages ?: return null
        return TtsChapterContext(
            manga = manga,
            chapter = chapter.chapter.toDomainChapter()!!,
            totalPages = pages.size,
            hasNextChapter = state.value.viewerChapters?.nextChapter != null,
        )
    }

    /** Restarts playback after [TtsPhase.Error]; keeps the page the error occurred on. */
    fun retryReadAloud() {
        if (state.value.ttsState.phase != TtsPhase.Error) return
        startReadAloudAt(state.value.ttsState.pageIndex.coerceAtLeast(0))
    }

    fun toggleReadAloudPlayPause() {
        ttsController.togglePlayPause()
    }

    /** Writes the speech-rate preference; the controller's live collector applies it to the engine. */
    fun setReadAloudRate(rate: Float) {
        ttsPreferences.ttsSpeechRate().set(rate)
    }

    /** Pauses playback if active; safe no-op otherwise. Used from Activity.onStop. */
    fun pauseReadAloud() {
        ttsControllerInstance?.pause()
    }

    fun stopReadAloud() {
        ttsController.stop()
    }

    fun nextReadAloudSentence() {
        ttsController.nextSentence()
    }

    fun previousReadAloudSentence() {
        ttsController.previousSentence()
    }

    private fun onTtsChapterChanged() {
        val controller = ttsControllerInstance ?: return
        when (controller.state.value.phase) {
            TtsPhase.Idle, TtsPhase.Finished, TtsPhase.Error -> Unit
            else -> startReadAloudAt(0)
        }
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    suspend fun init(mangaId: Long, initialChapterId: Long): Result<Boolean> {
        if (!needsInit()) return Result.success(true)
        return withIOContext {
            try {
                val manga = getManga.await(mangaId)
                if (manga != null) {
                    sourceManager.isInitialized.first { it }
                    mutableState.update { it.copy(manga = manga) }
                    subscribeExclusionZones()
                    if (chapterId == -1L) chapterId = initialChapterId

                    val context = Injekt.get<Application>()
                    val source = sourceManager.getOrStub(manga.source)
                    loader = ChapterLoader(context, downloadManager, downloadProvider, manga, source)

                    loadChapter(loader!!, chapterList.first { chapterId == it.chapter.id })

                    // Initialize OCR model early - avoids delay on first text-recognition
                    ocrProcessor

                    // Stage 2D experiment: eager TTS engine init overlaps Google TTS
                    // service startup with reader preparation. Async, no audio
                    // focus, no speech; existing idempotent initialize() fast-path
                    // dedups against later Read-Aloud starts.
                    if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                        logcat(LogPriority.DEBUG) { "TTS eagerinit requested" }
                    }
                    if (ttsEagerInitJob?.isActive != true) {
                        ttsEagerInitJob = viewModelScope.launch {
                            val startNs = if (eu.kanade.tachiyomi.BuildConfig.DEBUG) System.nanoTime() else 0L
                            if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                                logcat(LogPriority.DEBUG) { "TTS eagerinit started" }
                            }
                            val ok = ttsEngine.initialize()
                            if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                                logcat(LogPriority.DEBUG) {
                                    "TTS eagerinit completed ok=$ok elapsedMs=${(System.nanoTime() - startNs) / 1_000_000}"
                                }
                            }
                        }
                    }

                    Result.success(true)
                } else {
                    // Unlikely but okay
                    Result.success(false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                Result.failure(e)
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
    ): ViewerChapters {
        loader.loadChapter(chapter)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        readerOpenPrefetchJob?.cancel()
        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
                maybePrefetchReaderOpenOcr(0)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
            // TTS auto-advance waits (Preparing) for the chapter this load was
            // supposed to make active; without a signal it wedges forever.
            // Only fail when it is actually waiting on this transition.
            ttsControllerInstance?.let { controller ->
                val phase = controller.state.value.phase
                if (phase == TtsPhase.Preparing || phase == TtsPhase.LoadingPage) {
                    controller.fail(TtsError.ChapterLoadFailed)
                }
            }
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                manga.title,
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        } else {
            ttsControllerInstance?.let { controller ->
                if (controller.hasPendingAdvance) {
                    // Advance confirmation must land immediately; never debounce it.
                    controller.onPageSelected(page.index)
                } else {
                    // User navigation: debounce so a fast swipe only rebuilds once,
                    // not once per intermediate page (avoids mass on-demand OCR).
                    ttsPageSelectedJob?.cancel()
                    ttsPageSelectedJob = viewModelScope.launch {
                        delay(TTS_PAGE_SELECTED_DEBOUNCE_MS)
                        controller.onPageSelected(page.index)
                    }
                }
            }
            maybePrefetchReaderOpenOcr(page.index)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                manga.title,
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            if (readerChapter.pages?.lastIndex == pageIndex) {
                updateChapterProgressOnComplete(readerChapter)
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                ),
            )
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id, read = true)
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Instant.now().toEpochMilli()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            chapterReadStartTime = null
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else {
            readerPreferences.cropBordersWebtoon.toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    /**
     * Internal helper to save a page to a specific location.
     */
    private fun savePage(page: ReaderPage, location: Location, customName: String? = null): Uri {
        val manga = manga ?: throw Exception("Manga not found")
        val filename = customName ?: generateFilename(manga, page)
        return imageSaver.save(
            image = Image.Page(
                inputStream = page.stream!!,
                name = filename,
                location = location,
            ),
        )
    }

    /**
     * Saves the current page to the cache and returns its URI.
     */
    fun getCurrentPageUri(): Uri? {
        val chapter = state.value.currentChapter ?: return null
        val pageIndex = (state.value.currentPage - 1).coerceAtLeast(0)
        val page = chapter.pages?.getOrNull(pageIndex)
        if (page?.status != Page.State.Ready || page.stream == null) return null

        return try {
            savePage(page, Location.Cache, "anki_export_${System.currentTimeMillis()}")
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun enterOcrMode() {
        mutableState.update { it.copy(ocrSelectionMode = true, menuVisible = false) }
    }

    fun exitOcrMode() {
        mutableState.update { it.copy(ocrSelectionMode = false) }
    }

    /** Opens the scope dialog for a freshly drag-selected exclusion region. */
    fun openExclusionZoneScopeDialog(
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        mutableState.update {
            it.copy(
                dialog = Dialog.ExclusionZoneScope(pageIndex, left, top, right, bottom),
                exclusionZonePending = PendingExclusionZone(pageIndex, left, top, right, bottom),
            )
        }
    }

    /**
     * Saves the pending exclusion region as a pure-rectangle ZONE rule. The scope
     * only decides where the (page-anchored) rectangle is looked up: CHAPTER/
     * MANGA/SOURCE reuse the same rect on the same page index in that wider
     * scope. COMBINED is opt-in: non-blank match text turns the rule into
     * rect+text, scoped exactly as chosen.
     */
    fun saveExclusionZone(scope: OcrExclusionScope, matchText: String? = null) {
        val pending = state.value.exclusionZonePending ?: return
        val manga = manga ?: return
        val chapter = state.value.currentChapter?.chapter?.id ?: return
        val text = matchText?.trim().orEmpty()
        val combined = text.isNotEmpty()
        val matchType = if (combined) OcrExclusionMatchType.COMBINED else OcrExclusionMatchType.ZONE
        val scopedChapterId = when (scope) {
            OcrExclusionScope.MANGA, OcrExclusionScope.SOURCE -> null
            else -> chapter
        }
        viewModelScope.launchNonCancellable {
            addOcrExclusionZone.await(
                mangaId = manga.id,
                sourceId = manga.source,
                chapterId = scopedChapterId,
                pageIndex = pending.pageIndex,
                scope = scope,
                leftNorm = pending.left,
                topNorm = pending.top,
                rightNorm = pending.right,
                bottomNorm = pending.bottom,
                matchType = matchType,
                matchText = if (combined) text else null,
            )
        }
        mutableState.update { it.copy(dialog = null, exclusionZonePending = null) }
    }

    fun deleteExclusionZone(id: Long) {
        viewModelScope.launchNonCancellable { deleteOcrExclusionZone.await(id) }
    }

    fun setExclusionZoneEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launchNonCancellable { setOcrExclusionZoneEnabled.await(id, enabled) }
    }

    private fun subscribeExclusionZones() {
        val manga = manga ?: return
        viewModelScope.launch {
            getOcrExclusionZones.subscribeForManga(manga.id, manga.source).collect { zones ->
                mutableState.update { it.copy(exclusionZones = zones) }
            }
        }
    }

    fun processOcrRegion(bitmap: Bitmap) {
        viewModelScope.launchIO {
            mutableState.update { it.copy(isProcessingOcr = true, ocrSelectionMode = false) }
            try {
                val text = ocrProcessor.getText(bitmap.toOcrImage())
                withUIContext {
                    val queryText = flattenOcrTextForQuery(text)
                    if (queryText.isNotBlank()) {
                        showOcrResult(
                            queryText = queryText,
                            origin = OcrResultOrigin.ManualSelection,
                        )
                        mutableState.update { it.copy(isProcessingOcr = false) }
                    } else {
                        mutableState.update { it.copy(isProcessingOcr = false) }
                        eventChannel.send(Event.OcrNoTextFound)
                    }
                }
            } catch (e: CancellationException) {
                // Handle coroutine cancellation (e.g., user navigates away)
                logcat(LogPriority.DEBUG) { "OCR processing cancelled" }
                withUIContext {
                    mutableState.update { it.copy(isProcessingOcr = false) }
                }
                // Re-throw to properly handle cancellation
                throw e
            } catch (e: OutOfMemoryError) {
                logcat(LogPriority.ERROR, e) { "Out of memory during OCR processing" }
                withUIContext {
                    mutableState.update { it.copy(isProcessingOcr = false) }
                    eventChannel.send(Event.OcrMemoryError)
                }
            } catch (e: OcrException.InitializationError) {
                logcat(LogPriority.ERROR, e) { "Cannot recognize text: OCR engine failed to initialize" }
                withUIContext {
                    mutableState.update { it.copy(isProcessingOcr = false) }
                    eventChannel.send(Event.OcrInitializationError)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "OCR processing failed" }
                withUIContext {
                    mutableState.update { it.copy(isProcessingOcr = false) }
                    eventChannel.send(Event.OcrError)
                }
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    fun showOcrResult(
        queryText: String,
        origin: OcrResultOrigin,
        initialSearchText: String = queryText,
    ) {
        mutableState.update {
            it.copy(dialog = Dialog.OcrResult(queryText, origin, initialSearchText))
        }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = savePage(page, Location.Pictures.create(relativePath))
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return

        viewModelScope.launchNonCancellable {
            try {
                val uri = savePage(page, Location.Cache)
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(Injekt.get(), stream())
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        val ocrSelectionMode: Boolean = false,
        val isProcessingOcr: Boolean = false,
        val ttsState: TtsPlaybackState = TtsPlaybackState(),
        val exclusionZonePending: PendingExclusionZone? = null,
        val exclusionZones: List<OcrExclusionZone> = emptyList(),
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
        data class OcrResult(
            val queryText: String,
            val origin: OcrResultOrigin,
            val initialSearchText: String,
        ) : Dialog
        data class ExclusionZoneScope(
            val pageIndex: Int,
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
        ) : Dialog
    }

    data class PendingExclusionZone(
        val pageIndex: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    enum class OcrResultOrigin {
        CachedPageTap,
        ManualSelection,
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
        data object OcrNoTextFound : Event
        data object OcrMemoryError : Event
        data object OcrInitializationError : Event
        data object OcrError : Event

        data class TtsAdvancePage(val pageIndex: Int) : Event
        data object TtsAdvanceChapter : Event
        data class TtsError(val error: eu.kanade.tachiyomi.ui.reader.tts.TtsError) : Event
        data object TtsNoTextFound : Event
        data class TtsScrollToRegion(val pageIndex: Int, val bbox: mihon.domain.ocr.model.OcrBoundingBox) : Event
    }

    private companion object {
        const val TTS_PAGE_SELECTED_DEBOUNCE_MS = 250L
    }
}
