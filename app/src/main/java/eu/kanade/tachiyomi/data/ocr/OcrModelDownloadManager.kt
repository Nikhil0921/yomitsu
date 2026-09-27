package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import mihon.domain.ocr.model.OcrModelDownloadState
import mihon.domain.ocr.model.OcrModelDownloader
import mihon.domain.ocr.model.PpOcrAssets
import mihon.domain.ocr.model.PpOcrModelFile
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException

/**
 * App-side half of the PP-OCRv5 model install: the network fetch, plus the observable state the
 * settings row renders. All install logic — hashing, `.part` staging, the manifest, the version
 * directory — lives in [OcrModelDownloader] in `:domain`, where it is unit-tested without Android.
 *
 * The only outbound request this feature makes is for the model weights themselves, from the same
 * `huggingface.co` host the CI build already fetches ML assets from. No page, prompt or usage data
 * is sent anywhere.
 */
class OcrModelDownloadManager(context: Context) {

    private val appContext = context.applicationContext

    val downloader: OcrModelDownloader = OcrModelDownloader(
        filesDir = appContext.filesDir,
        fetch = ::fetch,
    )

    val state: StateFlow<OcrModelDownloadState> get() = downloader.state

    /** `filesDir/app_ocr_models/pp_ocr_v5/v1/` — the directory the engines load their weights from. */
    val modelDirectory: File get() = downloader.modelDirectory

    val installBytes: Long get() = PpOcrAssets.INSTALL_BYTES

    fun isInstalled(): Boolean = downloader.isInstalled()

    /** Enqueues the download; [OcrModelDownloadJob] owns the retry policy. */
    fun enqueue() {
        OcrModelDownloadJob.start(appContext)
    }

    private suspend fun fetch(file: PpOcrModelFile, target: File) = withContext(Dispatchers.IO) {
        val client = Injekt.get<NetworkHelper>().client
        val request = Request.Builder().url(file.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${file.name}")
            }
            val body = response.body ?: throw IOException("Empty body for ${file.name}")
            target.outputStream().buffered().use { output -> body.byteStream().copyTo(output) }
        }
    }
}
