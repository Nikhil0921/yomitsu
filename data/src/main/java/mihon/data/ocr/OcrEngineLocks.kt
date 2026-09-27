package mihon.data.ocr

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class OcrEngineLocks {
    private val fastMutex = Mutex()
    private val glensMutex = Mutex()
    private val owOcrMutex = Mutex()
    private val detectionMutex = Mutex()

    suspend fun <T> withTextEngineLock(
        type: OcrRepositoryImpl.EngineType,
        block: suspend () -> T,
    ): T {
        // GLENS is a stateless network client (its own tiled path already runs
        // TILE_CONCURRENCY requests in parallel through the shared instance), so
        // GLENS text scans run unlocked — serializing them was the dominant cost
        // of uncached Read-Aloud latency. Local engines keep their locks: their
        // interpreters are not thread-safe.
        return when (type) {
            OcrRepositoryImpl.EngineType.GLENS, OcrRepositoryImpl.EngineType.LEGACY -> block()
            else -> mutexFor(type).withLock { block() }
        }
    }

    suspend fun <T> withDetectionLock(block: suspend () -> T): T {
        return detectionMutex.withLock {
            block()
        }
    }

    suspend fun <T> withAllLocks(block: suspend () -> T): T {
        return fastMutex.withLock {
            glensMutex.withLock {
                owOcrMutex.withLock {
                    detectionMutex.withLock {
                        block()
                    }
                }
            }
        }
    }

    private fun mutexFor(type: OcrRepositoryImpl.EngineType): Mutex {
        return when (type) {
            // LEGACY engine removed; persisted LEGACY selection redirects to GLENS
            // (unlocked, see withTextEngineLock).
            OcrRepositoryImpl.EngineType.LEGACY -> glensMutex
            OcrRepositoryImpl.EngineType.GLENS -> glensMutex
            OcrRepositoryImpl.EngineType.FAST -> fastMutex
            // Shares the Fast lock: both are local CPU interpreters, and a new mutex tier would
            // only add a way for the two to run at once and fight over the same cores.
            OcrRepositoryImpl.EngineType.PPOCR -> fastMutex
            OcrRepositoryImpl.EngineType.OWOCR -> owOcrMutex
        }
    }
}
