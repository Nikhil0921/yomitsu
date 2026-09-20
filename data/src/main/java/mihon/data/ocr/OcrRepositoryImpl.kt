package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.ai.edge.litert.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrScanPriority
import mihon.domain.ocr.model.OcrTextOrientation
import mihon.domain.ocr.repository.OcrRepository
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.util.system.logcat
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * OCR repository implementation that manages engine selection, page scanning, and OCR cache.
 */
class OcrRepositoryImpl(
    private val context: Context,
) : OcrRepository {
    private val preferenceStore = AndroidPreferenceStore(context)
    private val ocrModelPref = preferenceStore.getEnum("pref_ocr_model", OcrModel.LEGACY)
    private val useFallbackModelsPref = preferenceStore.getBoolean("pref_use_fallback_models", true)

    private val environmentResult by lazy {
        runCatching { Environment.create() }
            .onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "LiteRT environment unavailable; local OCR engines will fall back"
                }
            }
    }

    private val textPostprocessor by lazy { TextPostprocessor() }
    private val cacheStore by lazy { OcrCacheStore(context) }

    private var fastEngine: FastOcrEngine? = null
    private var glensEngine: GlensOcrEngine? = null
    private var owOcrEngine: OwOcrEngine? = null
    private var detEngine: DetOcrEngine? = null

    private val engineLocks = OcrEngineLocks()
    private val cleanupMutex = Mutex()
    private val sessionMutex = Mutex()
    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskQueue = PrioritizedTaskQueue(scope) {
        scope.launch {
            performDeferredCleanupIfIdle()
        }
    }

    private var cleanupRequested = false

    private var activeScanSessions = 0
    private var activeOperations = 0

    internal enum class EngineType {
        LEGACY,
        FAST,
        GLENS,
        OWOCR,
    }

    private data class ScanKey(val chapterId: Long, val pageIndex: Int)

    /**
     * In-flight page scans keyed by (chapter, page). Concurrent identical requests
     * (playback acquire racing its own prefetch, navigation rebuilds, chapter scanner)
     * join the running scan instead of issuing a duplicate network pass.
     */
    private val inFlightScans = mutableMapOf<ScanKey, CompletableDeferred<OcrPageResult>>()
    private val inFlightMutex = Mutex()

    private fun selectedEngineType(): EngineType {
        return when (ocrModelPref.get()) {
            OcrModel.LEGACY -> EngineType.LEGACY
            OcrModel.FAST -> EngineType.FAST
            OcrModel.GLENS -> EngineType.GLENS
            OcrModel.OWOCR -> EngineType.OWOCR
        }
    }

    private fun isConnectivityFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (
                current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current.message?.contains("Unable to resolve host", ignoreCase = true) == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun fallbackFor(type: EngineType): EngineType {
        return when (type) {
            EngineType.GLENS -> EngineType.FAST
            EngineType.FAST -> EngineType.GLENS
            EngineType.LEGACY -> EngineType.GLENS
            EngineType.OWOCR -> EngineType.GLENS
        }
    }

    private fun requireEnvironment(): Environment {
        return environmentResult.getOrElse { cause ->
            throw OcrException.InitializationError(cause)
        }
    }

    private fun engineFor(type: EngineType): OcrEngine {
        return when (type) {
            EngineType.LEGACY -> glensEngine ?: GlensOcrEngine().also {
                glensEngine = it
            }
            EngineType.FAST -> {
                fastEngine ?: FastOcrEngine(context, requireEnvironment(), textPostprocessor).also {
                    fastEngine = it
                }
            }
            EngineType.GLENS -> {
                glensEngine ?: GlensOcrEngine().also {
                    glensEngine = it
                }
            }
            EngineType.OWOCR -> {
                owOcrEngine ?: OwOcrEngine(context).also {
                    owOcrEngine = it
                }
            }
        }
    }

    private fun detectionEngine(): DetOcrEngine {
        // TODO: replace with a real local-model DetOcrEngine when available;
        // until then every scan redirects to Glens via the fallback chain.
        return detEngine ?: UnavailableDetOcrEngine().also {
            detEngine = it
        }
    }

    private suspend fun recognizeWithEngine(type: EngineType, image: Bitmap): String {
        return engineLocks.withTextEngineLock(type) {
            engineFor(type).recognizeText(image)
        }
    }

    private suspend fun recognizeWithFallback(primary: EngineType, image: Bitmap): String {
        return try {
            recognizeWithEngine(primary, image)
        } catch (primaryError: Throwable) {
            if (primaryError is CancellationException) throw primaryError

            if (!useFallbackModelsPref.get()) {
                throw primaryError
            }

            val fallback = fallbackFor(primary)
            if (fallback == primary) {
                throw primaryError
            }

            logcat(LogPriority.WARN, primaryError) {
                "OCR (${primary.name.lowercase()}) failed, falling back to ${fallback.name.lowercase()}"
            }

            try {
                recognizeWithEngine(fallback, image)
            } catch (fallbackError: Throwable) {
                if (fallbackError is CancellationException) throw fallbackError
                primaryError.addSuppressed(fallbackError)
                throw primaryError
            }
        }
    }

    override suspend fun recognizeText(image: OcrImage): String {
        return withActiveOperation {
            submitTask(PrioritizedTaskQueue.Priority.HIGH) {
                image.useBitmap { bitmap ->
                    val type = selectedEngineType()
                    // Local recognition engines (LEGACY/FAST) are JP-vocab models that
                    // garble arbitrary English crops into kana/kanji; the scan path never
                    // uses them directly (detection stub throws -> Glens). Mirror that
                    // redirect for text recognition while detection stays unavailable.
                    // ponytail: drop this redirect when a real DetOcrEngine lands.
                    val effective = when (type) {
                        EngineType.LEGACY, EngineType.FAST -> EngineType.GLENS
                        EngineType.GLENS, EngineType.OWOCR -> type
                    }
                    recognizeWithFallback(effective, bitmap)
                }
            }
        }
    }

    override suspend fun scanPage(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        priority: OcrScanPriority,
    ): OcrPageResult {
        getCachedPage(chapterId, pageIndex)?.let { cached ->
            logcat(LogPriority.DEBUG) {
                "OCR scan cache hit chapter=$chapterId page=$pageIndex model=${cached.ocrModel}"
            }
            return cached
        }

        return withActiveOperation {
            val key = ScanKey(chapterId, pageIndex)
            var owner = false
            val deferred = inFlightMutex.withLock {
                val existing = inFlightScans[key]
                if (existing != null) {
                    logcat(LogPriority.DEBUG) {
                        "OCR scan joining in-flight scan chapter=$chapterId page=$pageIndex"
                    }
                    existing
                } else {
                    owner = true
                    CompletableDeferred<OcrPageResult>().also { inFlightScans[key] = it }
                }
            }

            try {
                if (owner) {
                    val result = dispatchScan(chapterId, pageIndex, image, priority)
                    deferred.complete(result)
                    result
                } else {
                    deferred.await()
                }
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
                throw error
            } finally {
                if (owner) {
                    inFlightMutex.withLock {
                        if (inFlightScans[key] === deferred) inFlightScans.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun dispatchScan(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        priority: OcrScanPriority,
    ): OcrPageResult = when (val selectedModel = ocrModelPref.get()) {
        OcrModel.GLENS -> scanWithGlens(
            chapterId = chapterId,
            pageIndex = pageIndex,
            image = image,
            modelKey = selectedModel,
            priority = priority,
        )
        OcrModel.LEGACY -> scanLocalOrFallback(
            chapterId = chapterId,
            pageIndex = pageIndex,
            image = image,
            modelKey = selectedModel,
            type = EngineType.LEGACY,
            priority = priority,
        )
        OcrModel.FAST -> scanLocalOrFallback(
            chapterId = chapterId,
            pageIndex = pageIndex,
            image = image,
            modelKey = selectedModel,
            type = EngineType.FAST,
            priority = priority,
        )
        OcrModel.OWOCR -> scanOwOcrOrFallback(
            chapterId = chapterId,
            pageIndex = pageIndex,
            image = image,
            modelKey = selectedModel,
            priority = priority,
        )
    }

    override suspend fun getCachedPage(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult? {
        return cacheStore.getPage(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = ocrModelPref.get(),
        )
    }

    override suspend fun getCachedChapterIds(chapterIds: Collection<Long>): Set<Long> {
        return cacheStore.getCachedChapterIds(
            chapterIds = chapterIds,
        )
    }

    override suspend fun clearCachedChapter(chapterId: Long) {
        cacheStore.clearChapter(chapterId)
    }

    override suspend fun clearCache() {
        cacheStore.clear()
    }

    override suspend fun getCacheSizeBytes(): Long {
        return cacheStore.sizeBytes()
    }

    override suspend fun <T> withScanSession(block: suspend () -> T): T {
        sessionMutex.withLock {
            activeScanSessions++
        }

        return try {
            block()
        } finally {
            sessionMutex.withLock {
                activeScanSessions--
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun scanLocalOrFallback(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        modelKey: OcrModel,
        type: EngineType,
        priority: OcrScanPriority,
    ): OcrPageResult {
        return try {
            scanLocally(
                chapterId = chapterId,
                pageIndex = pageIndex,
                image = image,
                modelKey = modelKey,
                type = type,
                priority = priority,
            )
        } catch (e: OcrException.DetectionUnavailable) {
            if (!useFallbackModelsPref.get()) {
                throw e
            }
            logcat(LogPriority.WARN, e) {
                "OCR scanning redirected to glens because local detection is unavailable"
            }
            scanWithGlens(
                chapterId = chapterId,
                pageIndex = pageIndex,
                image = image,
                modelKey = modelKey,
                priority = priority,
            )
        }
    }

    /** True for transient server-side failures worth one retry (HTTP 5xx / 429 rate limit). */
    private fun isTransientHttpFailure(error: Throwable): Boolean {
        val message = error.message ?: return false
        return message.contains("HTTP 5") || message.contains("HTTP 429")
    }

    private suspend fun scanWithGlens(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        modelKey: OcrModel,
        priority: OcrScanPriority,
    ): OcrPageResult {
        try {
            return scanWithGlensOnce(chapterId, pageIndex, image, modelKey, priority)
        } catch (firstError: Throwable) {
            if (firstError is CancellationException) throw firstError
            // Transient server failures (HTTP 502/429 observed on the Lens endpoint)
            // get exactly one retry, mirroring the recognizeText fallback policy.
            if (!isTransientHttpFailure(firstError)) throw firstError

            logcat(LogPriority.WARN, firstError) {
                "OCR (glens) transient scan failure, retrying once"
            }
            return try {
                scanWithGlensOnce(chapterId, pageIndex, image, modelKey, priority)
            } catch (retryError: Throwable) {
                if (retryError is CancellationException) throw retryError
                firstError.addSuppressed(retryError)
                throw firstError
            }
        }
    }

    private suspend fun scanWithGlensOnce(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        modelKey: OcrModel,
        priority: OcrScanPriority,
    ): OcrPageResult {
        return try {
            submitTask(
                priority.toQueuePriority(),
                diagnosticLabel = if (tachiyomi.data.BuildConfig.DEBUG) "chapter=$chapterId page=$pageIndex" else null,
            ) {
                // Bitmap lifecycle and caching live inside the task: callers may abandon the
                // await on navigation, but a queued task runs to completion, recycles its own
                // bitmap, and leaves the result cached for whoever asks next.
                image.useBitmap { bitmap ->
                    val regions = engineLocks.withTextEngineLock(EngineType.GLENS) {
                        val engine = glensEngine ?: GlensOcrEngine().also {
                            glensEngine = it
                        }
                        if (tachiyomi.data.BuildConfig.DEBUG) {
                            logcat(LogPriority.DEBUG) {
                                "OCR scan glens chapter=$chapterId page=$pageIndex priority=$priority " +
                                    "scan=${System.identityHashCode(bitmap)} startNs=${System.nanoTime()}"
                            }
                        }
                        engine.recognizePage(bitmap).regions
                    }
                    OcrPageResult(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        ocrModel = modelKey,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        regions = regions,
                    )
                }.also {
                    val cacheStartedAt = if (tachiyomi.data.BuildConfig.DEBUG) System.nanoTime() else 0L
                    try {
                        cacheStore.upsert(it)
                    } finally {
                        if (tachiyomi.data.BuildConfig.DEBUG) {
                            val endedAt = System.nanoTime()
                            logcat(LogPriority.DEBUG) {
                                "OCR cache write chapter=$chapterId page=$pageIndex startNs=$cacheStartedAt " +
                                    "endNs=$endedAt elapsedMs=${(endedAt - cacheStartedAt) / 1_000_000}"
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (isConnectivityFailure(error)) {
                throw OcrException.ConnectionError(error)
            }
            throw error
        }
    }

    private suspend fun scanWithOwOcr(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        modelKey: OcrModel,
        priority: OcrScanPriority,
    ): OcrPageResult {
        return try {
            submitTask(priority.toQueuePriority()) {
                image.useBitmap { bitmap ->
                    val regions = engineLocks.withTextEngineLock(EngineType.OWOCR) {
                        val engine = owOcrEngine ?: OwOcrEngine(context).also {
                            owOcrEngine = it
                        }
                        engine.recognizePage(bitmap)
                    }
                    OcrPageResult(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        ocrModel = modelKey,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        regions = regions,
                    )
                }.also { cacheStore.upsert(it) }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (isConnectivityFailure(error)) {
                throw OcrException.ConnectionError(error)
            }
            throw error
        }
    }

    private suspend fun scanOwOcrOrFallback(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        modelKey: OcrModel,
        priority: OcrScanPriority,
    ): OcrPageResult {
        return try {
            scanWithOwOcr(
                chapterId = chapterId,
                pageIndex = pageIndex,
                image = image,
                modelKey = modelKey,
                priority = priority,
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (!useFallbackModelsPref.get()) {
                throw e
            }
            logcat(LogPriority.WARN, e) {
                "OwOCR scanning failed, falling back to glens"
            }
            scanWithGlens(
                chapterId = chapterId,
                pageIndex = pageIndex,
                image = image,
                modelKey = modelKey,
                priority = priority,
            )
        }
    }

    private suspend fun scanLocally(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        modelKey: OcrModel,
        type: EngineType,
        priority: OcrScanPriority,
    ): OcrPageResult {
        return submitTask(priority.toQueuePriority()) {
            image.useBitmap { bitmap ->
                val boxes = engineLocks.withDetectionLock {
                    detectionEngine().detectTextRegions(bitmap)
                }.filter(OcrBoundingBox::isValid)

                val regions = boxes.mapIndexedNotNull { index, box ->
                    val crop = cropBitmap(bitmap, box) ?: return@mapIndexedNotNull null
                    try {
                        val text = recognizeWithEngine(type, crop).trim()
                        if (text.isBlank()) {
                            null
                        } else {
                            OcrRegion(
                                order = index,
                                text = text,
                                boundingBox = box,
                                textOrientation = OcrTextOrientation.Horizontal,
                            )
                        }
                    } finally {
                        if (!crop.isRecycled) {
                            crop.recycle()
                        }
                    }
                }

                OcrPageResult(
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                    ocrModel = modelKey,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    regions = regions,
                )
            }
        }
    }

    private fun cropBitmap(
        image: Bitmap,
        box: OcrBoundingBox,
    ): Bitmap? {
        val left = (box.left * image.width).toInt().coerceIn(0, image.width - 1)
        val top = (box.top * image.height).toInt().coerceIn(0, image.height - 1)
        val right = (box.right * image.width).toInt().coerceIn(left + 1, image.width)
        val bottom = (box.bottom * image.height).toInt().coerceIn(top + 1, image.height)

        val rect = Rect(left, top, right, bottom)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return null
        }

        return Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height())
    }

    override fun cleanup() {
        scope.launch {
            cleanupMutex.withLock {
                cleanupRequested = true
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun <T> submitTask(
        priority: PrioritizedTaskQueue.Priority,
        diagnosticLabel: String? = null,
        block: suspend () -> T,
    ): T {
        return taskQueue.submit(priority, diagnosticLabel, block)
    }

    private fun OcrScanPriority.toQueuePriority(): PrioritizedTaskQueue.Priority = when (this) {
        OcrScanPriority.HIGH -> PrioritizedTaskQueue.Priority.HIGH
        OcrScanPriority.NORMAL -> PrioritizedTaskQueue.Priority.NORMAL
    }

    private suspend fun <T> withActiveOperation(block: suspend () -> T): T {
        operationMutex.withLock {
            activeOperations++
        }

        return try {
            block()
        } finally {
            operationMutex.withLock {
                activeOperations--
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun performDeferredCleanupIfIdle() {
        val shouldCleanup = cleanupMutex.withLock {
            if (!cleanupRequested || !taskQueue.isIdle() || hasActiveOperations() || hasActiveScanSessions()) {
                return@withLock false
            }

            cleanupRequested = false
            true
        }

        if (!shouldCleanup) {
            return
        }

        try {
            closeEngines()
            cacheStore.close()
            logcat(LogPriority.INFO) { "OcrRepositoryImpl cleaned up successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error cleaning up OcrRepositoryImpl" }
        }
    }

    private suspend fun <T> OcrImage.useBitmap(
        block: suspend (Bitmap) -> T,
    ): T {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try {
            block(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun hasActiveScanSessions(): Boolean {
        return sessionMutex.withLock { activeScanSessions > 0 }
    }

    private suspend fun hasActiveOperations(): Boolean {
        return operationMutex.withLock { activeOperations > 0 }
    }

    private suspend fun closeEngines() {
        engineLocks.withAllLocks {
            fastEngine?.close()
            fastEngine = null

            glensEngine?.close()
            glensEngine = null

            owOcrEngine?.close()
            owOcrEngine = null

            detEngine?.close()
            detEngine = null
        }
    }
}
