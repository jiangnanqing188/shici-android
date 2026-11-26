package com.shici.app.capture

/**
 * Pure, content-free wording for one capture/recognition attempt that produced no query.
 *
 * The model exists so that "nothing came back" is never collapsed into "the dictionary does not
 * have this word": no fresh frame, a changed page, an OCR engine failure, an empty OCR result and
 * a selection without an English word are different observations with different next steps.
 *
 * It carries no bitmap, OCR text, stroke, headword, credential or repository state, and it performs
 * no capture, OCR, dictionary read, retry or write. The caller keeps the existing one-shot frame,
 * page guard, gate/owner/lease and manual-query admissions unchanged; this class only names what
 * happened and what the user can do next.
 */
enum class RecognitionFailure {
    /** The clean entry frame stayed black until its bounded deadline expired. */
    NO_FRAME_ENTRY_BLACK,
    /** No new usable entry frame arrived before its bounded deadline. */
    NO_FRAME_ENTRY,
    /** The clean lift frame stayed black until its bounded deadline expired. */
    NO_FRAME_VERIFY_BLACK,
    /** No new usable lift frame arrived before its bounded deadline. */
    NO_FRAME_VERIFY,
    /** The lift frame no longer matched the page accepted at the start of the stroke. */
    PAGE_CHANGED,
    /** The local OCR task threw or reported a failure. */
    OCR_UNAVAILABLE,
    /** OCR completed, but no selectable text was found for the stroke. */
    OCR_EMPTY,
    /** OCR produced text, but none of it is a queryable English word. */
    NO_ENGLISH_WORD
}

/** Fixed user-facing wording for [failure]. It is a statement, never an automatic action. */
data class RecognitionNotice(
    val failure: RecognitionFailure,
    val message: String,
    val nextStep: String
)

object RecognitionFeedback {
    /**
     * Visible while the one-shot OCR task is in flight. This is a status statement, not a retry:
     * no new frame is requested and no query/record is started by showing it.
     */
    const val RECOGNIZING = "正在本地识别笔迹附近的文字…"

    /** Shared label/next-step for the service-owned in-page input box, never the app's home page. */
    const val MANUAL_INPUT_LABEL = "手动查词"
    const val MANUAL_INPUT_HINT = "也可点「手动查词」在当前页面的输入框中查词。"

    /**
     * True when [text] contains at least one ASCII Latin letter. It only separates "OCR found text
     * but no English word" from "OCR found no text"; it never decides dictionary membership and
     * never rewrites a recognized word. The dictionary's own normalize/lookup rules still decide
     * whether a word is a valid headword.
     */
    fun hasLatinLetter(text: String): Boolean = text.any { it in 'A'..'Z' || it in 'a'..'z' }

    fun notice(failure: RecognitionFailure): RecognitionNotice = when (failure) {
        RecognitionFailure.NO_FRAME_ENTRY_BLACK -> RecognitionNotice(
            failure,
            "未拿到新画面：只收到黑帧（页面可能尚未就绪、锁屏或禁止截图）。本次未识别文字、未查询、未收录；不会复用旧截图或绕过保护。",
            "请确认页面已点亮且允许截图后，点「重试取词」重新划词；$MANUAL_INPUT_HINT"
        )
        RecognitionFailure.NO_FRAME_ENTRY -> RecognitionNotice(
            failure,
            "未拿到新画面：没有收到新的有效屏幕画面（安全页面可能禁止截图）。本次未识别文字、未查询、未收录；不会复用旧截图。",
            "请回到允许截图的普通页面后点「重试取词」；$MANUAL_INPUT_HINT"
        )
        RecognitionFailure.NO_FRAME_VERIFY_BLACK -> RecognitionNotice(
            failure,
            "抬笔后没有拿到新画面：只收到黑帧。本次未查询、未收录，也没有写入生词本。",
            "请点亮页面后重新划词；$MANUAL_INPUT_HINT"
        )
        RecognitionFailure.NO_FRAME_VERIFY -> RecognitionNotice(
            failure,
            "抬笔后没有拿到新画面：未收到新的有效画面。本次未查询、未收录，也没有写入生词本。",
            "请回到允许截图的普通页面后重新划词；$MANUAL_INPUT_HINT"
        )
        RecognitionFailure.PAGE_CHANGED -> RecognitionNotice(
            failure,
            "抬笔后的画面与起点页面不一致（划选期间页面已改变）。本次未查询、未收录。",
            "请等页面稳定后重新划词；$MANUAL_INPUT_HINT"
        )
        RecognitionFailure.OCR_UNAVAILABLE -> RecognitionNotice(
            failure,
            "本地文字识别异常（OCR 未返回结果，不是词库未命中）。本次未查询、未收录。",
            "请点「重试取词」重新划词；$MANUAL_INPUT_HINT"
        )
        RecognitionFailure.OCR_EMPTY -> RecognitionNotice(
            failure,
            "没有选中可用文字（不是词库未命中）。本次未查询、未收录。",
            "请沿完整英文单词重划一笔；$MANUAL_INPUT_HINT"
        )
        RecognitionFailure.NO_ENGLISH_WORD -> RecognitionNotice(
            failure,
            "已识别到文字，但选区里没有可查询的英文单词（不是词库缺词）。本次未查询、未收录。",
            "请沿完整英文单词重划一笔；也可点「手动查词」在当前页面的输入框中查词，或纠正选中文字。"
        )
    }
}
