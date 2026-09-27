package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import mihon.domain.ocr.model.PpOcrAssets
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Device gate for the local PP-OCRv5 engine. **Not run by CI** — the workflow executes
 * `spotlessCheck`, `testDebugUnitTest`, `verifySqlDelightMigration` and `assembleRelease`, never
 * `androidTest`; and the weights are an on-demand download, so no CI machine has them. Nor can a
 * JVM unit test stand in for it: `onnxruntime-android` ships no JVM artifact, the weights are not in
 * the repository, and container-x86 timings are not phone-ARM timings.
 *
 * The pages are **drawn in-process** rather than loaded from disk. An earlier version required 20+
 * fixture PNGs pushed to `externalFilesDir/ocr_benchmark/`, which made the gate unusable in practice
 * and added a file-format failure mode that had nothing to do with OCR. `Canvas.drawText` also means
 * the pages contain real glyphs, so detection *and* recognition are both exercised.
 *
 * Run it on the device — no fixtures needed, but the weights must already be installed:
 *
 * ```bash
 * ./gradlew :data:assembleDebugAndroidTest
 * adb install -r data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk
 * adb shell am instrument -w -e class mihon.data.ocr.PpOcrV5EngineBenchmarkTest \
 *   tachiyomi.data.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The same numbers are logged by the engine itself as `OCR(ppocr) Runtime: det=…ms rec=…ms`, so a
 * plain logcat capture is enough if the harness is skipped.
 */
@RunWith(AndroidJUnit4::class)
class PpOcrV5EngineBenchmarkTest {

    private lateinit var context: Context
    private lateinit var modelDirectory: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        modelDirectory = File(
            File(context.filesDir, PpOcrAssets.MODEL_DIRECTORY),
            PpOcrAssets.VERSION_DIRECTORY,
        )
    }

    @Test
    fun modelsAreInstalled() {
        // Separate test so a missing install fails with a clear reason instead of a timeout.
        assumeTrue(
            "PP-OCRv5 weights are not installed; download them from the app settings first",
            modelDirectory.resolve(PpOcrAssets.DETECTOR.name).isFile &&
                modelDirectory.resolve(PpOcrAssets.RECOGNIZER.name).isFile,
        )
    }

    @Test
    fun singlePageLocalInferenceStaysWithinBudget() = runBlocking {
        assumeTrue("PP-OCRv5 weights are not installed", modelDirectory.resolve(PpOcrAssets.DETECTOR.name).isFile)

        val pages = (0 until PAGE_COUNT).map { index -> drawPage(index) }
        val engine = PpOcrV5Engine(modelDirectory, TextPostprocessor())
        try {
            // Warm-up: the first run pays ORT session creation and graph optimization.
            pages.forEach { engine.detectTextRegions(it) }

            val detections = pages.map { page ->
                measureNanoTime { engine.detectTextRegions(page) } / 1_000_000
            }
            val detMedian = percentile(detections, 0.50)
            val detP90 = percentile(detections, 0.90)

            // Recognition is measured over real crops from the first page, so a transposed tensor
            // (the recognizer pins its height at 48) fails here instead of passing silently.
            val recognitions = pages.first().let { page ->
                val boxes = engine.detectTextRegions(page)
                boxes.mapNotNull { box ->
                    val crop = crop(page, box) ?: return@mapNotNull null
                    try {
                        val elapsed = measureNanoTime { engine.recognizeRegion(crop) } / 1_000_000 to crop
                        elapsed.second.recycle()
                        elapsed.first
                    } finally {
                        if (!crop.isRecycled) crop.recycle()
                    }
                }
            }
            val recMedian = percentile(recognitions, 0.50)
            val recP90 = percentile(recognitions, 0.90)

            println(
                "OCR(ppocr) benchmark pages=$PAGE_COUNT boxes=${recognitions.size} " +
                    "det p50=${detMedian}ms p90=${detP90}ms | " +
                    "rec n=${recognitions.size} p50=${recMedian}ms p90=${recP90}ms | budget=${BUDGET_MS}ms",
            )

            assertTrue(
                "detection p90 ${detP90}ms exceeds the ${BUDGET_MS}ms budget (p50 ${detMedian}ms)",
                detP90 <= BUDGET_MS,
            )
        } finally {
            engine.close()
            pages.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    /** White page with black text lines, alternating the tall-strip and portrait shapes. */
    private fun drawPage(index: Int): Bitmap {
        val tall = index % 2 == 0
        val width = if (tall) 64 else 800
        val height = if (tall) 960 else 1200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            textSize = if (tall) 11f else 34f
        }
        val margin = if (tall) 4 else 24
        var y = if (tall) 24f else 60f
        var line = 0
        while (y < height - margin) {
            canvas.drawText(LINES[line % LINES.size], margin.toFloat(), y, paint)
            y += if (tall) 20f else 52f
            line++
        }
        return bitmap
    }

    private fun crop(image: Bitmap, box: mihon.domain.ocr.model.OcrBoundingBox): Bitmap? {
        val left = (box.left * image.width).toInt().coerceIn(0, image.width - 1)
        val top = (box.top * image.height).toInt().coerceIn(0, image.height - 1)
        val right = (box.right * image.width).toInt().coerceIn(left + 1, image.width)
        val bottom = (box.bottom * image.height).toInt().coerceIn(top + 1, image.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
    }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private companion object {
        const val BUDGET_MS = 250L
        const val PAGE_COUNT = 20

        /** English text: the recognizer is the `en` export, so CJK would decode to noise. */
        val LINES = listOf(
            "I CAN'T BELIEVE THIS",
            "OKAY, I'M READY",
            "STOP!!",
            "WHO ARE YOU?",
            "RUN AWAY NOW",
            "IT'S OVER",
        )
    }
}
