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
                "steps=$timeSteps conf=${recognition.confidence}"
        }
        PpOcrRecognition(textPostprocessor.postprocess(recognition.text), recognition.confidence)
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
