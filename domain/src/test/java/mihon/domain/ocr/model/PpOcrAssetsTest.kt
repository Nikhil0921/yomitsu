package mihon.domain.ocr.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class PpOcrAssetsTest {

    @Test
    fun `manifest ships exactly the detector and the english recognizer`() {
        PpOcrAssets.FILES.map { it.name } shouldBe listOf("ppocrv5_mobile_det.onnx", "ppocrv5_mobile_rec_en.onnx")
    }

    @Test
    fun `install directory is app_ocr_models under the version folder`() {
        PpOcrAssets.MODEL_DIRECTORY shouldBe "app_ocr_models/pp_ocr_v5"
        PpOcrAssets.VERSION_DIRECTORY shouldBe "v1"
        PpOcrAssets.MANIFEST_NAME shouldBe "MANIFEST.json"
    }

    @Test
    fun `every file pins a lowercase 64 hex sha256`() {
        PpOcrAssets.FILES.forEach { file ->
            file.sha256.length shouldBe 64
            file.sha256 shouldBe file.sha256.lowercase()
            file.sha256.all { it in "0123456789abcdef" } shouldBe true
        }
    }

    @Test
    fun `declared byte sizes match the sum advertised to the user`() {
        PpOcrAssets.FILES.sumOf { it.bytes } shouldBe PpOcrAssets.INSTALL_BYTES
        PpOcrAssets.INSTALL_BYTES shouldBe 12_674_941L
    }

    @Test
    fun `models come from the official paddlepaddle huggingface exports over https`() {
        PpOcrAssets.FILES.forEach { file ->
            file.url.startsWith("https://huggingface.co/PaddlePaddle/") shouldBe true
            file.url.endsWith("/resolve/main/inference.onnx") shouldBe true
        }
    }

    @Test
    fun `detector and recognizer are addressable by role`() {
        PpOcrAssets.DETECTOR.bytes shouldBe 4_826_518L
        PpOcrAssets.RECOGNIZER.bytes shouldBe 7_848_423L
        PpOcrAssets.FILES.toSet() shouldBe setOf(PpOcrAssets.DETECTOR, PpOcrAssets.RECOGNIZER)
    }
}
