package mihon.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrTensorReader
import mihon.domain.ocr.model.PpOcrAssets
import mihon.domain.ocr.model.PpOcrCharset
import mihon.domain.ocr.model.PpOcrCtcDecode
import mihon.domain.ocr.model.PpOcrDbPostprocess
import mihon.domain.ocr.model.PpOcrPreprocess
import mihon.domain.ocr.model.PpOcrRecognition
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureNanoTime

/**
 * Local PP-OCRv5: DBNet text detection plus English SVTR_LCNet CTC recognition, on ONNX Runtime.
 *
 * Why ONNX Runtime and not the LiteRT already in the app: PP-OCRv5 publishes only Paddle-inference
 * and ONNX exports, and `libLiteRt.so` (LiteRT 2.1.6) is a TensorFlow Lite build with no ONNX
 * parser, so `CompiledModel.create(path)` can only load `.tflite`. ORT loads the official
 * PaddlePaddle files as they are, which keeps the weights a 12.7 MB on-demand download and the
 * APK carrying only the runtime.
 *
 * Layering: every decision that does not need a `Bitmap` (input geometry, DB post-processing, CTC
 * decode) lives in `:domain` and is unit-tested there. This class only moves pixels and calls the
 * interpreter.
 *
 * Telemetry is switched off explicitly — the ORT Android build reports usage by default and this
 * app keeps OCR content and usage on the device.
 *
 * `ponytail:` CPU only, mirroring `FastOcrEngine`. ORT's NNAPI delegate
 * (`SessionOptions.addNnapi()`) would probably be faster but is device and Android-version
 * dependent, so measure before enabling it. Detection also rescales any page so its long side is
 * at most 960px, the upstream default; that costs accuracy on very tall webtoon strips — tile long
 * pages before lowering the ceiling.
 */
internal class PpOcrV5Engine(
    private val modelDirectory: File,
    private val textPostprocessor: TextPostprocessor,
) : DetOcrEngine, OcrEngine {

    private val environment = OrtEnvironment.getEnvironment().apply {
        runCatching { setTelemetry(false) }
    }

    /** ORT sessions tolerate concurrent `run`, but the scratch buffers below do not. */
    private val mutex = Mutex()

    private var detector: OrtSession? = null
    private var recognizer: OrtSession? = null

    /** Cached first initialization failure; see [ensureInitialized]. */
    private var initializationFailure: OcrException? = null
    private var detectorInput = ""
    private var recognizerInput = ""

    private var pixelScratch = IntArray(0)
    private var detectionScratch = FloatArray(0)
    private var recognitionScratch = FloatArray(0)
    private var recognitionOutputScratch = FloatArray(0)

    /** True when both weights are on disk. Cheap: no hashing, no session creation. */
    fun isInstalled(): Boolean =
        File(modelDirectory, PpOcrAssets.DETECTOR.name).isFile &&
            File(modelDirectory, PpOcrAssets.RECOGNIZER.name).isFile

    override suspend fun detectTextRegions(image: Bitmap): List<OcrBoundingBox> = mutex.withLock {
        ensureInitialized()
        val size = PpOcrPreprocess.detectionInputSize(image.width, image.height)
        val (inputWidth, inputHeight) = size.width to size.height
        val plane = inputWidth * inputHeight
        val buffer = FloatArray(3 * plane).also { detectionScratch = it }
        normalize(image, inputWidth, inputHeight, buffer, DET_MEAN, DET_STD)

        var probability: FloatArray? = null
        val elapsed = infer(
            session = detector!!,
            inputName = detectorInput,
            buffer = buffer,
            shape = longArrayOf(1L, 3L, inputHeight.toLong(), inputWidth.toLong()),
        ) { output ->
            // The DB graph ends in a single-channel probability map: [1, 1, height, width], which ORT
            // hands back as float[][][][]. Walked positionally in :domain, never cast blind.
            probability = OcrTensorReader.probabilityMap(output.value, expectedSize = plane)
                ?: throw OcrException.InitializationError(
                    IllegalStateException("PP-OCRv5 detector returned an unexpected output shape"),
                )
        }

        val boxes = PpOcrDbPostprocess.boxes(
            probability = probability!!,
            mapWidth = inputWidth,
            mapHeight = inputHeight,
            imageWidth = image.width.toFloat(),
            imageHeight = image.height.toFloat(),
        )
        logcat(LogPriority.DEBUG) {
            "OCR(ppocr) Runtime: det=${elapsed}ms in=${inputWidth}x$inputHeight boxes=${boxes.size}"
        }
        // Box geometry, in page-normalised l/t/r/b. Added 2026-09-28 because the gap bound turned
        // out not to be what keeps single-glyph regions alive: raising it from 0.8 to 1.5 left the
        // per-page region counts byte-identical, so "which test rejected this box" could only be
        // answered by looking at the boxes. Keep it DEBUG-only; it is verbose by design.
        if (boxes.isNotEmpty()) {
            logcat(LogPriority.DEBUG) {
                "OCR(ppocr) boxes: n=${boxes.size} " +
                    boxes.joinToString(" ") { box ->
                        val w = box.right - box.left
                        val h = box.bottom - box.top
                        "%.3f/%.3f/%.3f/%.3f(w=%.3f,h=%.3f,a=%.3f)".format(
                            box.left,
                            box.top,
                            box.right,
                            box.bottom,
                            w,
                            h,
                            w * h,
                        )
                    }
            }
        }
        boxes
    }

    /** [OcrEngine] contract: one cropped line in, its text out. */
    override suspend fun recognizeText(image: Bitmap): String = recognizeRegion(image).text

    /**
     * Text plus the mean CTC probability. That confidence is the only one anywhere in the OCR
     * stack — the cloud proto is never asked for a score — so the adaptive router escalates on it
     * and it must not be dropped on the way out.
     */
    suspend fun recognizeRegion(image: Bitmap): PpOcrRecognition = mutex.withLock {
        ensureInitialized()
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        // A crop with a few pixels on each side cannot hold a character; let it decode as blank so
        // the region is dropped rather than spoken as a stray letter. Measured 2026-09-28: crops of
        // 8x6 and 9x8 px were reaching the recognizer and producing conf=0.0 or a lone character.
        if (width < MIN_CROP_SIDE_PX && height < MIN_CROP_SIDE_PX) {
            return@withLock PpOcrRecognition(text = "", confidence = 0f)
        }
        recognizeInRange(image, depth = 0)
    }

    /**
     * Recognizes [image] if its aspect is inside the usable range, otherwise splits it along
     * whichever axis is over-long and re-checks each piece.
     *
     * The re-check matters: splitting a very wide box horizontally can yield pieces that are
     * themselves square, and feeding one of those straight to the recognizer is exactly the
     * starvation this whole change exists to remove. Measured 2026-09-28 at depth 0 only, 50 crops
     * still arrived at a tensor <= 64px wide, every one of them a piece of a wider box.
     */
    private fun recognizeInRange(image: Bitmap, depth: Int): PpOcrRecognition {
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        if (width < MIN_CROP_SIDE_PX && height < MIN_CROP_SIDE_PX) {
            return PpOcrRecognition(text = "", confidence = 0f)
        }
        val aspect = width.toFloat() / height
        return when {
            depth >= MAX_SPLIT_DEPTH -> recognizeWholeCrop(image)
            aspect > MAX_RECOGNITION_ASPECT -> recognizeChunked(image, splitVertically = false, depth)
            aspect < MIN_RECOGNITION_ASPECT -> recognizeChunked(image, splitVertically = true, depth)
            else -> recognizeWholeCrop(image)
        }
    }

    /**
     * Splits a crop the recognizer cannot take whole, and joins the decoded text back together.
     *
     * The recognizer's tensor is height-pinned to 48 and width-clamped to
     * `RECOGNITION_MAX_WIDTH`, so the usable aspect range is narrow and crops fall out of it on BOTH
     * ends. A line of text is too wide and gets squashed; a speech bubble is roughly square and gets
     * crushed to 7 CTC steps. Both decode to noise. Splitting along whichever axis is over-long
     * brings either one back into range.
     *
     * Measured on hardware 2026-09-28: 34% of recognition calls arrived at a tensor <= 64px wide,
     * and of those 96 decoded blank (the region is dropped, so the phrase is never spoken) and 98
     * decoded a single character (the region survives, so a bare letter is spoken). Those two
     * numbers are the whole reported symptom pair.
     */
    private fun recognizeChunked(
        image: Bitmap,
        splitVertically: Boolean,
        depth: Int,
    ): PpOcrRecognition {
        val target = if (splitVertically) {
            // Aim a little past the middle of the range: a strip this shape gives the CTC head
            // room to breathe without pushing the tensor back to its width clamp.
            (MAX_RECOGNITION_ASPECT + MIN_RECOGNITION_ASPECT) / 2f
        } else {
            MAX_RECOGNITION_ASPECT
        }
        val along = if (splitVertically) image.height else image.width
        val across = if (splitVertically) image.width else image.height
        val count = ceil(along / (across * target)).toInt().coerceAtLeast(2)
        val slice = ceil(along.toFloat() / count).toInt().coerceIn(1, along)
        // Overlap so a character on a boundary is not cut in half. The duplicated text is dropped
        // by joinOcrChunks, so the repetition costs compute, not correctness.
        val step = (slice * (1f - CHUNK_OVERLAP_RATIO)).toInt().coerceAtLeast(1)
        val texts = mutableListOf<String>()
        var confidence = 0f
        var counted = 0
        var offset = 0
        while (offset < along) {
            val end = minOf(offset + slice, along)
            val piece = if (splitVertically) {
                Bitmap.createBitmap(image, 0, offset, image.width, end - offset)
            } else {
                Bitmap.createBitmap(image, offset, 0, end - offset, image.height)
            }
            try {
                val result = recognizeInRange(piece, depth + 1)
                texts += result.text
                if (result.text.isNotBlank()) {
                    confidence += result.confidence
                    counted++
                }
            } finally {
                if (!piece.isRecycled) piece.recycle()
            }
            if (end >= along) break
            offset += step
        }
        return PpOcrRecognition(
            text = joinOcrChunks(texts),
            // Mean of the chunks that actually decoded: a blank chunk has no confidence worth
            // averaging in, and the adaptive router reads this to decide whether to escalate.
            confidence = if (counted == 0) 0f else confidence / counted,
        )
    }

    private fun recognizeWholeCrop(image: Bitmap): PpOcrRecognition {
        val size = PpOcrPreprocess.recognitionInputSize(image.width, image.height)
        // Named, never destructured: the recognizer pins height at 48, so a transposed tensor is an
        // ORT_INVALID_ARGUMENT ("index: 2 Got: 192 Expected: 48") and every wide bubble crop died.
        val inputWidth = size.width
        val inputHeight = size.height
        val buffer = FloatArray(3 * inputWidth * inputHeight).also { recognitionScratch = it }
        normalize(image, inputWidth, inputHeight, buffer, REC_MEAN, REC_STD)

        var timeSteps = 0
        var classCount = 0
        val elapsed = infer(
            session = recognizer!!,
            inputName = recognizerInput,
            buffer = buffer,
            shape = longArrayOf(1L, 3L, inputHeight.toLong(), inputWidth.toLong()),
        ) { output ->
            // [1, timeSteps, classes], returned as float[][][].
            val rows = OcrTensorReader.timeSteps(output.value)
            if (rows == null || rows.isEmpty()) {
                throw OcrException.InitializationError(
                    IllegalStateException("PP-OCRv5 recognizer returned an unexpected output shape"),
                )
            }
            classCount = rows.first().size
            timeSteps = rows.size
            val needed = timeSteps * classCount
            if (recognitionOutputScratch.size < needed) {
                recognitionOutputScratch = FloatArray(needed)
            }
            rows.forEachIndexed { step, row ->
                row.copyInto(recognitionOutputScratch, step * classCount)
            }
        }

        val recognition = PpOcrCtcDecode.decode(
            probabilities = recognitionOutputScratch,
            timeSteps = timeSteps,
            classCount = classCount,
        )
        logcat(LogPriority.DEBUG) {
            "OCR(ppocr) Runtime: rec=${elapsed}ms in=${inputWidth}x$inputHeight " +
                "steps=$timeSteps conf=${recognition.confidence} " +
                // src is the crop this tensor came from. Added 2026-09-28: 34% of crops reach the
                // recognizer at a tensor <= 64px wide, which is <= 8 CTC time steps and cannot
                // physically decode a line, yet the boxes feeding them have a median pixel aspect
                // of 30:1. The box and the crop disagree and nothing logged the crop.
                "src=${image.width}x${image.height}"
        }
        return PpOcrRecognition(textPostprocessor.postprocess(recognition.text), recognition.confidence)
    }

    override fun close() {
        runCatching { detector?.close() }
        runCatching { recognizer?.close() }
        detector = null
        recognizer = null
        initializationFailure = null
    }

    /**
     * Creates both sessions once.
     *
     * The failure is **sticky** on purpose: creating an ORT session runs graph optimization, and a
     * first device run re-created the sessions on all 23 scanned pages because the failure was not
     * remembered — hundreds of milliseconds of CPU each time, under the shared local-engine lock,
     * while the caller waited. Now the first failure is cached and rethrown immediately.
     */
    private fun ensureInitialized() {
        if (detector != null && recognizer != null) return
        initializationFailure?.let { throw it }
        if (!isInstalled()) throw OcrException.DetectionUnavailable()

        try {
            createSessions()
        } catch (error: Throwable) {
            val failure = when (error) {
                is OcrException -> error
                else -> OcrException.InitializationError(error)
            }
            initializationFailure = failure
            throw failure
        }
    }

    private fun createSessions() {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        val det = runCatching {
            environment.createSession(File(modelDirectory, PpOcrAssets.DETECTOR.name).path, options)
        }.getOrElse { throw OcrException.InitializationError(it) }
        val rec = runCatching {
            environment.createSession(File(modelDirectory, PpOcrAssets.RECOGNIZER.name).path, options)
        }.getOrElse { error ->
            det.close()
            throw OcrException.InitializationError(error)
        }

        val input = rec.inputNames.firstOrNull()
        val output = rec.outputNames.firstOrNull()
        val classes = output?.let { rec.outputInfo[it] }?.let { nodeInfo -> nodeInfo.info }?.shapeClassCount()
        val detectorName = det.inputNames.firstOrNull()
        if (input == null || output == null || classes != PpOcrCharset.CLASS_COUNT || detectorName == null) {
            // A dict/model mismatch would otherwise decode to silent garbage; fail loudly instead.
            det.close()
            rec.close()
            throw OcrException.InitializationError(
                IllegalStateException(
                    "PP-OCRv5 recognizer exposes ${classes ?: 0} classes but the en dictionary has " +
                        "${PpOcrCharset.CLASS_COUNT}: the installed file is not the expected export",
                ),
            )
        }

        detectorInput = detectorName
        recognizerInput = input
        detector = det
        recognizer = rec
        logcat(LogPriority.INFO) {
            "OCR(ppocr) init ok detIn=$detectorInput recIn=$input recOut=$output classes=$classes"
        }
    }

    /**
     * `OrtSession.getOutputInfo()` returns a `NodeInfo`; the shape lives on its `ValueInfo`, which
     * only a `TensorInfo` implements. Casting `NodeInfo` straight to `TensorInfo` compiles (Java
     * platform types) and is **always null at runtime** — that is what made the first device run
     * report "recognizer exposes 0 classes".
     */
    private fun ai.onnxruntime.ValueInfo?.shapeClassCount(): Int? =
        (this as? TensorInfo)?.shape?.lastOrNull()?.toInt()

    /** One inference pass; the returned millis cover the whole `run` plus the output copy. */
    private fun infer(
        session: OrtSession,
        inputName: String,
        buffer: FloatArray,
        shape: LongArray,
        read: (OnnxTensor) -> Unit,
    ): Long {
        val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(buffer), shape)
        var elapsed = 0L
        try {
            elapsed = measureNanoTime {
                session.run(mapOf(inputName to input)).use { result ->
                    (result.get(0) as? OnnxTensor)?.let(read)
                }
            } / 1_000_000
        } finally {
            runCatching { input.close() }
        }
        return elapsed
    }

    /** Bitmap to a normalised NCHW float tensor through one reusable pixel buffer. */
    private fun normalize(
        image: Bitmap,
        width: Int,
        height: Int,
        buffer: FloatArray,
        mean: FloatArray,
        std: FloatArray,
    ) {
        val plane = width * height
        if (pixelScratch.size < plane) pixelScratch = IntArray(plane)

        val scaled = if (image.width == width && image.height == height) {
            image
        } else {
            Bitmap.createScaledBitmap(image, width, height, true)
        }
        try {
            scaled.getPixels(pixelScratch, 0, width, 0, 0, width, height)
        } finally {
            if (scaled !== image) scaled.recycle()
        }

        for (index in 0 until min(3 * plane, buffer.size)) {
            val pixel = pixelScratch[index % plane]
            val channel = index / plane
            val raw = when (channel) {
                0 -> (pixel shr 16 and 0xFF)
                1 -> (pixel shr 8 and 0xFF)
                else -> (pixel and 0xFF)
            } / 255f
            buffer[index] = (raw - mean[channel]) / std[channel]
        }
    }

    private companion object {
        /** Upstream `NormalizeImage` for the DB detector (ImageNet statistics). */
        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** Upstream `NormalizeImage` for the recognition head. */
        val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}

/**
 * Stitches the text of a wide crop that was recognized in horizontal chunks back into one string.
 *
 * The chunks overlap on purpose so a character sitting on a boundary is not cut in half and
 * mis-decoded; the cost is that it is decoded twice, so the join drops the tail of what has been
 * accumulated when it is also the head of the next chunk.
 *
 * Top level in this file, not a file of its own, because on this build a new top-level declaration
 * in a new main file does not reach unit tests: the class lands in the compile and runtime jars and
 * is still unresolved from `src/test`, reproduced on 2026-09-28 in both `:domain` and `:data` with
 * clean builds and no configuration cache. Adding a declaration to an existing file works, which is
 * why this is here and not in its own file.
 */
internal fun joinOcrChunks(chunks: List<String>): String {
    val out = StringBuilder()
    for (raw in chunks) {
        val chunk = raw.trim()
        if (chunk.isEmpty()) continue
        if (out.isEmpty()) {
            out.append(chunk)
            continue
        }
        val overlap = sharedLength(out, chunk)
        if (overlap == 0) out.append(' ')
        out.append(chunk, overlap, chunk.length)
    }
    return out.toString()
}

/**
 * Longest suffix of [accumulated] that is also a prefix of [chunk], capped so a chunk is never
 * consumed entirely, and capped at 8 characters because a genuine repeat that long is far less
 * likely than a boundary character.
 */
private fun sharedLength(accumulated: StringBuilder, chunk: String): Int {
    val max = minOf(accumulated.length, chunk.length, MAX_OCR_CHUNK_OVERLAP_CHARS)
    for (length in max downTo 1) {
        var matches = true
        for (i in 0 until length) {
            if (accumulated[accumulated.length - length + i] != chunk[i]) {
                matches = false
                break
            }
        }
        if (matches) return length
    }
    return 0
}

private const val MAX_OCR_CHUNK_OVERLAP_CHARS = 8

/**
 * Fraction of each chunk repeated in the next one, so a character on a boundary is not cut in
 * half. 0.12 is roughly two glyph widths at the sizes these crops are seen at, which is enough for
 * any single character to be whole in at least one chunk without wasting much compute on repeats.
 */
private const val CHUNK_OVERLAP_RATIO = 0.12f

/**
 * Widest crop the recognizer can take unsqueezed: `RECOGNITION_MAX_WIDTH / RECOGNITION_HEIGHT`.
 * Anything above this was being compressed horizontally.
 */
private const val MAX_RECOGNITION_ASPECT =
    PpOcrPreprocess.RECOGNITION_MAX_WIDTH.toFloat() / PpOcrPreprocess.RECOGNITION_HEIGHT

/**
 * Narrowest crop worth handing over whole. Below this the tensor width stops being 48 and the CTC
 * head is left with too few time steps: the head downsamples time by 8, so a 48px tensor is 6 steps
 * and a square speech bubble cannot decode at all. Measured 2026-09-28, a 458x427 bubble was
 * arriving as a 48x48 tensor with 7 steps and decoding to nothing.
 */
private const val MIN_RECOGNITION_ASPECT = 3f

/**
 * Splits are applied to their own output at most this many times. Two is enough for any crop the
 * detector produces; the cap exists so a pathological one cannot recurse forever.
 */
private const val MAX_SPLIT_DEPTH = 2

/** A crop smaller than this on both sides is detector noise, not a character. */
private const val MIN_CROP_SIDE_PX = 12
