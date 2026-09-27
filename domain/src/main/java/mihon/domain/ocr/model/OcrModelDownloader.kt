package mihon.domain.ocr.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * On-demand installer for the local PP-OCRv5 weights.
 *
 * Deliberately framework-free — it touches [File], [MessageDigest] and a [StateFlow] and nothing
 * else — so the whole download lifecycle is covered by plain JVM unit tests. The network call and
 * the WorkManager plumbing live in the app module and arrive as [fetch].
 *
 * Failure policy: a file whose SHA-256 does not match is a **failure, not a retry**. A corrupt
 * mirror retried forever is a worse bug than an error the user can see and re-trigger.
 */
class OcrModelDownloader(
    private val filesDir: File,
    private val fetch: suspend (file: PpOcrModelFile, target: File) -> Unit,
    /** Injected so tests can install a fixture manifest; production always uses [PpOcrAssets.FILES]. */
    private val files: List<PpOcrModelFile> = PpOcrAssets.FILES,
    private val version: String = PpOcrAssets.VERSION_DIRECTORY,
) {
    /** `filesDir/app_ocr_models/pp_ocr_v5/<version>/`. Declared first: the initial state reads it. */
    val modelDirectory: File = File(File(filesDir, PpOcrAssets.MODEL_DIRECTORY), version)

    private val _state = MutableStateFlow(initialState())

    val state: StateFlow<OcrModelDownloadState> = _state

    /**
     * True only when the manifest parses **and** every weight still hashes to what the manifest
     * recorded. A restored backup, an interrupted write or a bit flip therefore reads as "absent"
     * and the UI offers the download again — the failure mode we want.
     */
    fun isInstalled(): Boolean {
        val manifest = readManifest() ?: return false
        if (manifest.version != version) return false
        if (manifest.files.size != files.size) return false
        return manifest.files.all { entry ->
            val file = File(modelDirectory, entry.name)
            file.isFile && file.length() == entry.bytes && sha256(file) == entry.sha256
        }
    }

    /**
     * Downloads anything missing and returns whether [isInstalled] holds afterwards, so a caller
     * can read the result as "the local engine may now be used" without re-checking.
     */
    suspend fun download(): Boolean {
        if (isInstalled()) {
            _state.value = installedState()
            return true
        }

        modelDirectory.mkdirs()
        val installed = mutableListOf<OcrModelManifestEntry>()
        var bytesDone = 0L
        // Integrity failures are permanent; transport failures are worth another attempt.
        var retryable = false
        _state.value = OcrModelDownloadState(
            status = OcrModelDownloadStatus.DOWNLOADING,
            filesTotal = files.size,
            bytesTotal = totalBytes(),
        )

        try {
            files.forEach { file ->
                val partial = File(modelDirectory, "${file.name}$PART_SUFFIX")
                fetch(file, partial)

                if (!partial.isFile || partial.length() == 0L) {
                    retryable = true
                    error("${file.name} is missing after download")
                }
                val actual = sha256(partial)
                if (actual != file.sha256) {
                    error("SHA-256 mismatch for ${file.name}: expected ${file.sha256}, got $actual")
                }
                if (!partial.renameTo(File(modelDirectory, file.name))) {
                    error("${file.name} could not be moved into place")
                }
                installed += OcrModelManifestEntry(file.name, file.sha256, file.bytes)

                bytesDone += file.bytes
                _state.value = _state.value.copy(filesDone = installed.size, bytesDone = bytesDone)
            }

            writeManifest(OcrModelManifest(version, installed))
        } catch (cancellation: CancellationException) {
            discard()
            throw cancellation
        } catch (error: Throwable) {
            discard()
            val message = error.message ?: error::class.simpleName ?: "download failed"
            _state.value = OcrModelDownloadState(
                status = OcrModelDownloadStatus.ERROR,
                filesTotal = files.size,
                bytesTotal = totalBytes(),
                errorMessage = message,
                errorRetryable = retryable || error is java.io.IOException,
            )
            logcat(LogPriority.WARN, error) { "OCR model download failed: $message" }
            return false
        }

        _state.value = installedState()
        return true
    }

    /** Removes the install so the user can re-download it; wired to the settings row. */
    fun delete() {
        modelDirectory.deleteRecursively()
        _state.value = initialState()
    }

    private fun totalBytes() = files.sumOf { it.bytes }

    private fun installedState() = OcrModelDownloadState(
        status = OcrModelDownloadStatus.DOWNLOADED,
        filesDone = files.size,
        filesTotal = files.size,
        bytesDone = totalBytes(),
        bytesTotal = totalBytes(),
        version = version,
    )

    private fun initialState(): OcrModelDownloadState =
        if (isInstalled()) installedState() else OcrModelDownloadState()

    private fun discard() {
        modelDirectory.listFiles()?.forEach { it.delete() }
    }

    private fun writeManifest(manifest: OcrModelManifest) {
        val partial = File(modelDirectory, "${PpOcrAssets.MANIFEST_NAME}$PART_SUFFIX")
        partial.writeText(JSON.encodeToString(OcrModelManifest.serializer(), manifest))
        if (!partial.renameTo(File(modelDirectory, PpOcrAssets.MANIFEST_NAME))) {
            error("${PpOcrAssets.MANIFEST_NAME} could not be moved into place")
        }
    }

    private fun readManifest(): OcrModelManifest? {
        val file = File(modelDirectory, PpOcrAssets.MANIFEST_NAME)
        if (!file.isFile) return null
        return runCatching { JSON.decodeFromString(OcrModelManifest.serializer(), file.readText()) }.getOrNull()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BUFFER_SIZE = 16 * 1024

        val JSON = Json { prettyPrint = false }
    }
}

enum class OcrModelDownloadStatus { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, ERROR }

@Serializable
internal data class OcrModelManifest(
    val version: String,
    val files: List<OcrModelManifestEntry>,
)

@Serializable
internal data class OcrModelManifestEntry(
    val name: String,
    val sha256: String,
    val bytes: Long,
)

/** Observable download state; [progress] is 0f..1f and only meaningful while DOWNLOADING. */
data class OcrModelDownloadState(
    val status: OcrModelDownloadStatus = OcrModelDownloadStatus.NOT_DOWNLOADED,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val errorMessage: String? = null,
    /** True when the failure was transport-level, so a retry can plausibly succeed. */
    val errorRetryable: Boolean = false,
    val version: String = "",
) {
    val progress: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}
