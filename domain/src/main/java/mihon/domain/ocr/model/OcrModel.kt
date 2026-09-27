package mihon.domain.ocr.model

/**
 * Represents the available OCR models.
 */
enum class OcrModel {
    /**
     * Legacy and slower model, supports GPU/CPU.
     */
    LEGACY,

    /**
     * Faster model designed for ARM CPU.
     */
    FAST,

    /**
     * Online Google Lens OCR model.
     */
    GLENS,

    /**
     * Self-hosted OwOCR model.
     */
    OWOCR,

    /**
     * On-device PP-OCRv5 (DBNet detector + English SVTR_LCNet recognizer). Requires the on-demand
     * model download; falls back to [GLENS] until the weights are installed.
     */
    PPOCR,

    /**
     * Local PP-OCRv5 first, whole-page GLENS escalation when the local result cannot be trusted.
     */
    ADAPTIVE,
}
