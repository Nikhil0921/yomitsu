package mihon.domain.ocr.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class OcrModelDownloaderTest {

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Fixture manifest. The real [PpOcrAssets] hashes pin 12.7 MB of ONNX weights that no unit test
     * can produce, so the fixture hashes are computed from the fixture payload. The real manifest
     * is pinned separately, by [PpOcrAssetsTest].
     */
    private val payload = "fixture-payload".toByteArray()
    private val payloadSha = digest(payload)

    private val fixture = listOf(
        PpOcrModelFile("det.onnx", "https://example.invalid/det.onnx", payloadSha, payload.size.toLong()),
        PpOcrModelFile("rec.onnx", "https://example.invalid/rec.onnx", payloadSha, payload.size.toLong()),
    )

    private val installBytes = fixture.sumOf { it.bytes }

    private fun modelDir(filesDir: File) =
        File(File(filesDir, PpOcrAssets.MODEL_DIRECTORY), PpOcrAssets.VERSION_DIRECTORY)

    private fun downloader(
        filesDir: File,
        onFetch: (suspend (PpOcrModelFile, File) -> Unit)? = null,
    ) = OcrModelDownloader(
        filesDir = filesDir,
        files = fixture,
        fetch = { file, target ->
            if (onFetch != null) onFetch(file, target) else target.writeBytes(payload)
        },
    )

    @Test
    fun `starts not downloaded when the model directory is empty`(@TempDir filesDir: File) {
        val downloader = downloader(filesDir)
        downloader.state.value.status shouldBe OcrModelDownloadStatus.NOT_DOWNLOADED
        downloader.isInstalled() shouldBe false
    }

    @Test
    fun `happy path installs both weights and the manifest`(@TempDir filesDir: File) = runTest {
        val downloader = downloader(filesDir)
        downloader.download() shouldBe true

        val dir = modelDir(filesDir)
        fixture.forEach { file -> File(dir, file.name).readBytes() shouldBe payload }
        File(dir, PpOcrAssets.MANIFEST_NAME).exists() shouldBe true
        downloader.state.value.status shouldBe OcrModelDownloadStatus.DOWNLOADED
        downloader.state.value.version shouldBe PpOcrAssets.VERSION_DIRECTORY
        downloader.isInstalled() shouldBe true
    }

    @Test
    fun `no partial file survives a successful install`(@TempDir filesDir: File) = runTest {
        downloader(filesDir).download() shouldBe true

        modelDir(filesDir).listFiles().orEmpty().map { it.name }.sorted() shouldBe
            (fixture.map { it.name } + PpOcrAssets.MANIFEST_NAME).sorted()
    }

    @Test
    fun `progress is reported per file while downloading`(@TempDir filesDir: File) = runTest {
        val seen = mutableListOf<OcrModelDownloadState>()
        lateinit var downloader: OcrModelDownloader
        downloader = OcrModelDownloader(
            filesDir = filesDir,
            files = fixture,
            fetch = { _, target ->
                seen += downloader.state.value
                target.writeBytes(payload)
            },
        )

        downloader.download() shouldBe true

        seen.map { it.status }.distinct() shouldBe listOf(OcrModelDownloadStatus.DOWNLOADING)
        seen.map { it.filesDone } shouldBe listOf(0, 1)
        seen.map { it.filesTotal }.distinct() shouldBe listOf(2)
        seen.last().bytesTotal shouldBe installBytes
        // Mid-flight the second file has not landed yet, so progress is partial by design.
        seen.last().bytesDone shouldBe fixture[0].bytes
        downloader.state.value.progress shouldBe 1f
    }

    @Test
    fun `checksum mismatch errors and installs nothing`(@TempDir filesDir: File) = runTest {
        val downloader = downloader(filesDir) { _, target -> target.writeText("tampered") }

        downloader.download() shouldBe false

        val state = downloader.state.value
        state.status shouldBe OcrModelDownloadStatus.ERROR
        state.errorMessage!! shouldContain "SHA-256"
        downloader.isInstalled() shouldBe false
        File(modelDir(filesDir), PpOcrAssets.MANIFEST_NAME).exists() shouldBe false
    }

    @Test
    fun `a fetch that produces no file is an error`(@TempDir filesDir: File) = runTest {
        val downloader = downloader(filesDir) { _, _ -> Unit }

        downloader.download() shouldBe false

        downloader.state.value.status shouldBe OcrModelDownloadStatus.ERROR
        downloader.state.value.errorMessage!! shouldContain "missing"
        downloader.isInstalled() shouldBe false
    }

    @Test
    fun `a throwing fetch surfaces the error and keeps nothing partial`(@TempDir filesDir: File) = runTest {
        val downloader = downloader(filesDir) { file, _ ->
            if (file == fixture[0]) error("network down")
        }

        downloader.download() shouldBe false

        downloader.state.value.status shouldBe OcrModelDownloadStatus.ERROR
        downloader.state.value.errorMessage!! shouldContain "network down"
        modelDir(filesDir).listFiles().orEmpty() shouldBe emptyList<File>()
    }

    @Test
    fun `a half written model directory is not installed`(@TempDir filesDir: File) {
        val dir = modelDir(filesDir).apply { mkdirs() }
        File(dir, fixture[0].name).writeBytes(payload)

        val downloader = downloader(filesDir)
        downloader.isInstalled() shouldBe false
        downloader.state.value.status shouldBe OcrModelDownloadStatus.NOT_DOWNLOADED
    }

    @Test
    fun `a manifest without every weight is not installed`(@TempDir filesDir: File) = runTest {
        val downloader = downloader(filesDir)
        downloader.download() shouldBe true

        File(modelDir(filesDir), fixture[1].name).delete()

        downloader.isInstalled() shouldBe false
    }

    @Test
    fun `a corrupted installed model is detected and can be reinstalled`(@TempDir filesDir: File) = runTest {
        val downloader = downloader(filesDir)
        downloader.download() shouldBe true

        // A restored backup or a bit flip: right name, right length class, wrong bytes.
        File(modelDir(filesDir), fixture[1].name).writeBytes("corrupted!!".toByteArray())

        downloader.isInstalled() shouldBe false

        downloader.download() shouldBe true
        downloader.isInstalled() shouldBe true
    }

    @Test
    fun `an existing install is reported as downloaded without refetching`(@TempDir filesDir: File) = runTest {
        var fetches = 0
        val downloader = OcrModelDownloader(
            filesDir = filesDir,
            files = fixture,
            fetch = { _, target ->
                fetches++
                target.writeBytes(payload)
            },
        )
        downloader.download() shouldBe true
        downloader.state.value.status shouldBe OcrModelDownloadStatus.DOWNLOADED

        downloader.download() shouldBe true
        // Only the first call fetched: the second found a valid install and returned early.
        fetches shouldBe 2
    }

    @Test
    fun `deleting the install returns the downloader to not downloaded`(@TempDir filesDir: File) = runTest {
        val downloader = downloader(filesDir)
        downloader.download() shouldBe true

        downloader.delete()

        downloader.isInstalled() shouldBe false
        downloader.state.value.status shouldBe OcrModelDownloadStatus.NOT_DOWNLOADED
    }

    @Test
    fun `manifest records the version and the hashes it verified`(@TempDir filesDir: File) = runTest {
        downloader(filesDir).download() shouldBe true

        val manifest = File(modelDir(filesDir), PpOcrAssets.MANIFEST_NAME).readText()
        manifest shouldContain PpOcrAssets.VERSION_DIRECTORY
        fixture.forEach { file ->
            manifest shouldContain file.name
            manifest shouldContain file.sha256
        }
    }

    @Test
    fun `a transport failure is retryable but an integrity failure is not`(@TempDir filesDir: File) = runTest {
        val corrupt = downloader(filesDir) { _, target -> target.writeText("tampered") }
        corrupt.download() shouldBe false
        corrupt.state.value.errorRetryable shouldBe false

        val truncated = downloader(filesDir) { _, _ -> Unit }
        truncated.download() shouldBe false
        truncated.state.value.errorRetryable shouldBe true
    }

    @Test
    fun `the production manifest is well formed`(@TempDir filesDir: File) {
        // Guards the fixture above from drifting away from the production manifest's shape.
        PpOcrAssets.FILES.size shouldBe 2
        PpOcrAssets.FILES.all { it.sha256.length == 64 && it.sha256 == it.sha256.lowercase() } shouldBe true
    }
}
