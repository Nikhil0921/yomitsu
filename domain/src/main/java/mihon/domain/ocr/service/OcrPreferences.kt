package mihon.domain.ocr.service

import mihon.domain.ocr.model.OcrModel
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class OcrPreferences(
    private val preferenceStore: PreferenceStore,
) {

    // GLENS is the default: LEGACY is deprecated and is no longer offered in the model
    // selector, so defaulting to it would leave a fresh install showing a value the user
    // cannot pick. LEGACY already resolves to the GLENS engine, so this changes no behaviour
    // for anyone who still has LEGACY stored.
    fun ocrModel() = preferenceStore.getEnum("pref_ocr_model", OcrModel.GLENS)

    fun autoOcrOnDownload() = preferenceStore.getBoolean("auto_ocr_on_download", false)

    fun owocrAddress() = preferenceStore.getString("pref_owocr_address", "ws://10.0.2.2:7331")

    fun useFallbackModels() = preferenceStore.getBoolean("pref_use_fallback_models", true)
}
