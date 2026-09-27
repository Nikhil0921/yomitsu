package mihon.domain.ocr.model

/**
 * One downloadable PP-OCRv5 weight file. The sha256 is mandatory: the downloader refuses to
 * install a file whose whole-content hash does not match, so a truncated mirror or a hijacked
 * CDN response can never reach the interpreter.
 */
data class PpOcrModelFile(
    val name: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

/**
 * The on-demand model manifest for the local PP-OCRv5 engine.
 *
 * Both files are the official PaddlePaddle ONNX exports, pinned by content hash. They are the only
 * PP-OCRv5 mobile artefacts in a form a runtime can load: PaddlePaddle also publishes
 * `inference.json` + `inference.pdiparams`, which nothing on Android can execute. LiteRT cannot
 * load them either — `libLiteRt.so` is a TensorFlow Lite build with no ONNX parser — which is why
 * inference runs on ONNX Runtime.
 *
 * Install layout: `filesDir/app_ocr_models/pp_ocr_v5/v1/`. The version directory means a model
 * upgrade is a new directory plus an atomic manifest rename, never a half-upgraded state.
 */
object PpOcrAssets {
    const val MODEL_DIRECTORY = "app_ocr_models/pp_ocr_v5"
    const val VERSION_DIRECTORY = "v1"
    const val MANIFEST_NAME = "MANIFEST.json"

    /** DBNet text detector: 4.83 MB, the half that was missing from the whole local stack. */
    val DETECTOR = PpOcrModelFile(
        name = "ppocrv5_mobile_det.onnx",
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx",
        sha256 = "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d",
        bytes = 4_826_518,
    )

    /** English recognizer (SVTR_LCNet + CTC head, softmax included) plus [PpOcrCharset]. */
    val RECOGNIZER = PpOcrModelFile(
        name = "ppocrv5_mobile_rec_en.onnx",
        url = "https://huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx",
        sha256 = "b5f833dfc5d0eb71da397b4efa06ebeee9b431b690a47d6af40d77d8eabc557f",
        bytes = 7_848_423,
    )

    val FILES = listOf(DETECTOR, RECOGNIZER)

    /** Total on-demand download, shown to the user before they opt in. */
    val INSTALL_BYTES: Long = FILES.sumOf { it.bytes }
}
