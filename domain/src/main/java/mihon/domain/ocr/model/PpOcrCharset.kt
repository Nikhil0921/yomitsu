package mihon.domain.ocr.model

/**
 * Character set of the PP-OCRv5 `en` mobile recognition model, copied verbatim from the
 * `character_dict` of the official export config
 * `PaddlePaddle/en_PP-OCRv5_mobile_rec_onnx/inference.yml`.
 *
 * CTC index mapping: the model's final Softmax emits `CHARACTERS.size + 1` classes where index 0
 * is the CTC blank, so class `i` decodes to `CHARACTERS[i - 1]`. The engine asserts the model's
 * real output width against [CLASS_COUNT] at init, so a mismatched model/dict pairing fails loudly
 * instead of silently decoding to garbage.
 *
 * [CHARACTERS] exists because two dictionary entries (`𝑢` U+1D462, `𝜓` U+1D4D3) are
 * astral-plane characters. A Kotlin `String` indexes UTF-16 code units, so walking [CHARSET]
 * directly would split those two into lone surrogates and hand the decoder half a character.
 * Slicing by code point keeps all 436 entries addressable, which is what the class indices assume.
 */
object PpOcrCharset {
    /** 436 characters: digits, ASCII letters, ASCII punctuation, Latin-1/Greek/math symbols. */
    const val CHARSET: String =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!\"#\$%&'()*+,-./:;<=>?@[\\]_`{|}^~©®℉" +
            "№Ω℮™∆✓✔✗✘✕☑☒●▪▫◼▶◀⬆¤¦§¨ª«¬¯°²³´µ¶¸¹º»¼½¾¿×‐‑‒—―‖‗‘’‚‛“”„‟†‡‣․…‧‰‴‵‶‷‸‹›※‼‽‾−₤₡₹₽₴₿¢€£¥ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫⅰⅱ" +
            "ⅲⅳⅴⅵⅶⅷⅸⅹⅺⅻ➀➁➂➃➄➅➆➇➈➉➊➋➌➍➎➏➐➑➒➓❶❷❸❹❺❻❼❽❾❿①②③④⑤⑥⑦⑧⑨⑩↑→↓↕←↔⇒⇐⇔∀∃∄∴∵∝∞∩∪∂∫∬∭∮∯∰∑∏√∛∜∱∲∳∶∷∼∖∗≈≠≡≤≥⊂⊃⊥⊾⊿□∥" +
            "∋ƒ′″ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩαβγδεζηθικλ" +
            "μνξοπρσςτυφχψωÅℏ⌀⍺⍵𝑢𝜓०‥︽﹥•÷∕∙⋅·±∓∟∠∡∢℧☺"

    /**
     * The trailing CTC class, which the model reserves for a literal space.
     *
     * PaddleOCR's `CTCLabelDecode` builds its label list as `['blank'] + dict + [' ']`, so a
     * 436-character dictionary yields **438** classes, not 437 — confirmed against the real export,
     * whose output shape is `[-1, -1, 438]`. Decoding without this class silently dropped word
     * boundaries and produced run-on text such as "Helloworld".
     */
    const val SPACE_CLASS: Int = 437

    /** The 436 dictionary entries, each a whole code point. Index i decodes CTC class i + 1. */
    val CHARACTERS: List<String> = buildList {
        var index = 0
        while (index < CHARSET.length) {
            val codePoint = CHARSET.codePointAt(index)
            add(String(Character.toChars(codePoint)))
            index += Character.charCount(codePoint)
        }
    }

    /** Number of CTC classes the model must emit: blank + charset + space. */
    val CLASS_COUNT: Int = SPACE_CLASS + 1
}
