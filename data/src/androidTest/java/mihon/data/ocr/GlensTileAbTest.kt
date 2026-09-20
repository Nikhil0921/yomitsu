package mihon.data.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.ocr.model.OcrRegion
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes

/**
 * Stage 4I isolation matrix for the taller-tile OCR loss (Stage 4H verdict: FAIL).
 *
 * Same local-only fixture (`mihon/data/ocr/ab-page.webp`, never committed). One
 * instrumentation run executes, in order: baseline x2 (determinism gate), rejected
 * 2.4x/2000 rule (repro sentinel), conservative intermediate height (2.0x, no extra
 * downscale), rejected height with wider overlap (0.35). Each config gets a fresh
 * Bitmap copy so log scanIds stay correlatable.
 *
 * Compares by text hash + IoU>=0.5 pairing only; OCR text is never logged.
 */
@RunWith(AndroidJUnit4::class)
class GlensTileAbTest {

    private val rule24: (Int) -> Int = { width -> minOf((width * 2.4f).toInt(), 2000) }
    private val rule20: (Int) -> Int = { width -> (width * 2.0f).toInt() }

    /** Stage 4H sentinel: hashes reproducibly absent under the rejected 2.4x rule. */
    private val knownMissing = setOf(1536, -692040333, -2048614523)

    private data class Config(
        val name: String,
        val heightRule: ((Int) -> Int)? = null,
        val overlap: Float? = null,
        val concurrency: Int? = null,
    )

    private data class Miss(val key: RegionKey, val bestIou: Float)

    @Test
    fun tileHeightIsolationMatrix(): Unit = runBlocking {
        withTimeout(15.minutes) {
            val source = loadFixture()
            assumeTrue(
                "fixture must exercise the tiled path (h > w*3 and h > 1500)",
                source.height > source.width * 3 && source.height > 1500,
            )
            println("GLENS-AB fixture=#{w=${source.width} h=${source.height}}")

            val engine = GlensOcrEngine()
            val base1 = runConfig(engine, source, Config("base1"))
            val base2 = runConfig(engine, source, Config("base2"))
            if (base1.keys != base2.keys) {
                fail(
                    "INCONCLUSIVE: provider nondeterminism (run1=${base1.keys.size} " +
                        "hash=${base1.keys.hashCode()} run2=${base2.keys.size} " +
                        "hash=${base2.keys.hashCode()})",
                )
            }
            println("GLENS-AB determinism=OK regions=${base1.keys.size}")

            // Repro sentinel first: the known failure must still reproduce.
            val repro = runConfig(engine, source, Config("exp24-ov20", rule24, 0.2f))
            val reproMiss = compare(base1.keys, repro, base1.tiling, repro.tiling, source.height, "exp24-ov20")
            if (!reproMiss.map { it.key.textHash }.toSet().containsAll(knownMissing)) {
                fail(
                    "INCONCLUSIVE: 4H sentinel did not reproduce " +
                        "(missing=${reproMiss.map { it.key.textHash }}); matrix discarded",
                )
            }
            println("GLENS-AB repro=OK sentinel recovered")

            // Intermediate: max height increase with zero preprocessing change
            // (H=1380 < 1500: no downscale) — pure boundary-placement probe.
            val mid = runConfig(engine, source, Config("mid20-ov20", rule20, 0.2f))
            compare(base1.keys, mid, base1.tiling, mid.tiling, source.height, "mid20-ov20")

            // Overlap isolation: rejected height, wider overlap only.
            val wide = runConfig(engine, source, Config("exp24-ov35", rule24, 0.35f))
            compare(base1.keys, wide, base1.tiling, wide.tiling, source.height, "exp24-ov35")

            // No emptiness gate here: this matrix measures. The verdict (PASS/FAIL
            // per config) is read off the printed GLENS-AB diff lines, with the
            // repro sentinel already enforced above.
        }
    }

    private data class RunResult(
        val name: String,
        val keys: List<RegionKey>,
        val ms: Long,
        val tiling: Tiling,
        val scan: Int,
    )

    private suspend fun runConfig(engine: GlensOcrEngine, source: Bitmap, config: Config): RunResult {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, false)
        try {
            val tiling = tileTopsFor(
                bitmap.width,
                bitmap.height,
                config.heightRule,
                overlapRatio = config.overlap ?: 0.2f,
            )
            // Prepared dims: exact preprocessing math mirrored from prepareImage.
            val tileH = tiling.tileHeight
            val maxDim = max(bitmap.width, tileH)
            val prep = if (maxDim > 1500) {
                val f = 1500f / maxDim
                "${(bitmap.width * f).toInt()}x${(tileH * f).toInt()} f=${"%.4f".format(f)}"
            } else {
                "${bitmap.width}x$tileH f=1.0"
            }
            println(
                "GLENS-AB cfg=#{name=${config.name} tiles=${tiling.tops.size} H=$tileH " +
                    "conc=${config.concurrency ?: 3} " +
                    "prep=$prep scan=${System.identityHashCode(bitmap)}}",
            )
            val start = System.nanoTime()
            val regions = engine.recognizePage(bitmap, config.heightRule, config.overlap, config.concurrency).regions
            val ms = (System.nanoTime() - start) / 1_000_000
            val keys = regions.map(::regionKey)
            println("GLENS-AB result=#{name=${config.name} ms=$ms regions=${keys.size}}")
            return RunResult(config.name, keys, ms, tiling, System.identityHashCode(bitmap))
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun compare(
        baseKeys: List<RegionKey>,
        result: RunResult,
        baseTiling: Tiling,
        tiling: Tiling,
        imageHeight: Int,
        name: String,
    ): List<Miss> {
        val expKeys = result.keys
        val expByText = expKeys.groupBy { it.textHash }
        val consumed = mutableSetOf<RegionKey>()
        val misses = mutableListOf<Miss>()
        for (base in baseKeys) {
            val candidates = (expByText[base.textHash] ?: emptyList()).filter { it !in consumed }
            val best = candidates.maxByOrNull { iou(base, it) }
            val bestIou = if (best != null) iou(base, best) else 0f
            if (best != null && bestIou >= 0.5f) {
                consumed.add(best)
            } else {
                misses.add(Miss(base, bestIou))
            }
        }
        val added = expKeys.filter { it !in consumed }
        val bands = boundaryBands(imageHeight, baseTiling) + boundaryBands(imageHeight, tiling)
        val interior = misses.filter { miss -> !nearBoundary(miss.key, bands) }
        val boundary = misses.filter { miss -> nearBoundary(miss.key, bands) }
        println(
            "GLENS-AB diff=#{name=$name missing=${misses.size} interior=${interior.size} " +
                "boundary=${boundary.size} added=${added.size} " +
                "missHashes=${misses.map { it.key.textHash }} " +
                "addedHashes=${added.take(10).map { it.textHash }} " +
                "bestIous=${misses.map { it.bestIou }}}",
        )
        val shared = baseKeys.map { it.textHash }.toSet()
            .intersect(expKeys.map { it.textHash }.toSet())
        val baseOrder = baseKeys.filter { it.textHash in shared }.map { it.textHash }
        val expOrder = expKeys.filter { it.textHash in shared }.map { it.textHash }
        println("GLENS-AB order=#{name=$name stable=${baseOrder == expOrder}}")
        return misses
    }

    /**
     * Stage 4J: C=3 x2 vs C=4 x2 on the same bitmap. Geometry identical; only the
     * semaphore width varies. Accuracy must be exact; provider statuses come from
     * logcat per scan (pulled right after the run). Measurement only — verdict
     * is read off GLENS-AB lines.
     */
    @Test
    fun concurrencyComparison(): Unit = runBlocking {
        withTimeout(15.minutes) {
            val source = loadFixture()
            assumeTrue(
                "fixture must exercise the tiled path (h > w*3 and h > 1500)",
                source.height > source.width * 3 && source.height > 1500,
            )
            println("GLENS-AB fixture=#{w=${source.width} h=${source.height}}")

            val engine = GlensOcrEngine()
            val c3a = runConfig(engine, source, Config("c3a"))
            val c3b = runConfig(engine, source, Config("c3b"))
            if (c3a.keys != c3b.keys) {
                fail(
                    "INCONCLUSIVE: C=3 baseline unstable (run1=${c3a.keys.size} " +
                        "run2=${c3b.keys.size}); range unusable",
                )
            }
            println("GLENS-AB c3range=#{${c3a.ms},${c3b.ms}} regions=${c3a.keys.size}")

            val c4a = runConfig(engine, source, Config("c4a", concurrency = 4))
            val c4b = runConfig(engine, source, Config("c4b", concurrency = 4))

            compare(c3a.keys, c4a, c3a.tiling, c4a.tiling, source.height, "c4a")
            compare(c3a.keys, c4b, c3a.tiling, c4b.tiling, source.height, "c4b")
        }
    }

    /**
     * Stage 4J 20-tile-class validation: two distinct real pages stacked vertically
     * (690x28000, 28 tiles). One artificial horizontal joint at the midpoint; all
     * other content is real dense manga text. Same composite bitmap for every config,
     * so the C=3 vs C=4 comparison stays fair. Measurement only.
     */
    @Test
    fun concurrencyComparison28Tile(): Unit = runBlocking {
        withTimeout(20.minutes) {
            val top = loadFixture("mihon/data/ocr/ab-page.webp")
            val bottom = loadFixture("mihon/data/ocr/ab-page-b.webp")
            assumeTrue("stacked pages must share width", top.width == bottom.width)
            val stacked = Bitmap.createBitmap(top.width, top.height + bottom.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(stacked)
            canvas.drawBitmap(top, 0f, 0f, null)
            canvas.drawBitmap(bottom, 0f, top.height.toFloat(), null)
            if (!top.isRecycled) top.recycle()
            if (!bottom.isRecycled) bottom.recycle()
            try {
                assumeTrue(
                    "stacked fixture must be 20-tile class",
                    tileTopsFor(stacked.width, stacked.height).tops.size >= 20,
                )
                println("GLENS-AB fixture28=#{w=${stacked.width} h=${stacked.height}}")

                // Interleaved C3/C4/C3/C4: separates provider time-drift from a
                // genuine concurrency-correlated divergence.
                val engine = GlensOcrEngine()
                val c3a = runConfig(engine, stacked, Config("c3a28"))
                val c4a = runConfig(engine, stacked, Config("c4a28", concurrency = 4))
                val c3b = runConfig(engine, stacked, Config("c3b28"))
                val c4b = runConfig(engine, stacked, Config("c4b28", concurrency = 4))

                val c3stable = c3a.keys == c3b.keys
                val c4stable = c4a.keys == c4b.keys
                println("GLENS-AB stability28=#{c3=$c3stable c4=$c4stable}")
                if (!c3stable) {
                    fail("INCONCLUSIVE: C=3 unstable across interleave")
                }
                println("GLENS-AB c3range28=#{${c3a.ms},${c3b.ms}} regions=${c3a.keys.size}")

                compare(c3a.keys, c4a, c3a.tiling, c4a.tiling, stacked.height, "c4a28")
                compare(c3a.keys, c4b, c3a.tiling, c4b.tiling, stacked.height, "c4b28")
            } finally {
                if (!stacked.isRecycled) stacked.recycle()
            }
        }
    }

    private fun loadFixture(): Bitmap {
        return loadFixture("mihon/data/ocr/ab-page.webp")
    }

    private fun loadFixture(path: String): Bitmap {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
        assumeTrue("local-only fixture $path absent; test aborts", stream != null)
        val bitmap = stream!!.use { BitmapFactory.decodeStream(it) }
        assumeTrue("fixture did not decode", bitmap != null)
        return bitmap!!
    }

    private data class RegionKey(
        val textHash: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private fun regionKey(region: OcrRegion): RegionKey = with(region.boundingBox) {
        RegionKey(
            textHash = region.text.hashCode(),
            left = (left * 1000).toInt(),
            top = (top * 1000).toInt(),
            right = (right * 1000).toInt(),
            bottom = (bottom * 1000).toInt(),
        )
    }

    /** Normalized y-intervals around every tile edge (overlap band on each side). */
    private fun boundaryBands(imageHeight: Int, tiling: Tiling): List<ClosedFloatingPointRange<Float>> {
        val band = tiling.tileHeight * 0.2f / imageHeight
        return tiling.tops.drop(1).map { top ->
            val edge = top.toFloat() / imageHeight
            max(0f, edge - band)..min(1f, edge + band)
        }
    }

    private fun nearBoundary(key: RegionKey, bands: List<ClosedFloatingPointRange<Float>>): Boolean {
        val top = key.top / 1000f
        val bottom = key.bottom / 1000f
        return bands.any { band -> top in band || bottom in band }
    }

    private fun iou(a: RegionKey, b: RegionKey): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }
}
