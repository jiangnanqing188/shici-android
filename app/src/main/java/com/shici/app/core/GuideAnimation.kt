package com.shici.app.core

/**
 * Pure, Android-free model for the animated usage guide.
 *
 * The screen owns the actual drawing; this object only describes the finite set of demonstration
 * scenes, their one-sentence steps and the deterministic timing that turns elapsed milliseconds into
 * one [Frame]. Keeping it here means the timing can be checked without an Activity, a View, a
 * repository or a single real lookup: every duration is bounded, every frame is a pure function and
 * nothing in this file starts work, reads data or writes anything.
 *
 * The steps show the existing product behaviour with invented sample words. They never perform a
 * query, create a study record, request a permission, download a model or touch `guide_seen_v1`.
 */
object GuideAnimation {

    /** One demonstration scene. [action] is the real page hand-off offered separately by the UI. */
    enum class Demo(val title: String, val shortLabel: String, val action: GuideAction) {
        CAPTURE("划词查词演示", "划词", GuideAction.OPEN_HOME),
        VOCABULARY("生词本演示", "生词本", GuideAction.OPEN_VOCABULARY),
        // The page route stays the stable GuideRoutes.STUDY id; only the guide's display name
        // follows the four-choice review wording.
        STUDY("复习演示", "复习", GuideAction.OPEN_STUDY),
        LOOKUP_SUPPLEMENT("查词与补词演示", "查词/补词", GuideAction.OPEN_LOOKUP)
    }

    /** The visual situation a step draws. Drawing code switches on this, never on the copy. */
    enum class Kind {
        ENTRY,
        TAP_BUBBLE,
        DRAG,
        LIFT,
        RESULT,
        TAP_STAR,
        TAP_REMOVE,
        SCOPE,
        CONFIRM_ONE,
        CONFIRM_TWO,
        // Review is shown as it is now used: pick one of four Chinese meanings, see correct/wrong
        // feedback, then move to the next card.
        STUDY_CHOICE,
        STUDY_CORRECT,
        STUDY_WRONG,
        STUDY_NEXT,
        LOOKUP_INPUT,
        LOOKUP_SUCCESS,
        SUPPLEMENT_EDIT,
        SUPPLEMENT_SAVE,
        CONTRAST
    }

    /** One finite demonstration step: a short prompt, a TalkBack sentence and a bounded duration. */
    data class Step(
        val id: String,
        val demo: Demo,
        val kind: Kind,
        val prompt: String,
        val talkBack: String,
        val durationMs: Int
    )

    data class DemoSpec(val demo: Demo, val summary: String, val steps: List<Step>)

    /**
     * A deterministic frame. [highlight] is the scene's primary progress (switch knob, word
     * highlighter, star fill, dialog reveal or chip pop), [progress] is the overall step progress and
     * the finger coordinates are normalized to the drawing area. No frame implies a real operation.
     */
    data class Frame(
        val progress: Float,
        val highlight: Float,
        val fingerVisible: Boolean,
        val fingerX: Float,
        val fingerY: Float,
        val fingerPressed: Boolean,
        val resultVisible: Boolean
    )

    /** Bounds keep a malformed duration or a paused host from producing an endless animation. */
    const val MIN_STEP_MS: Int = 700
    const val MAX_STEP_MS: Int = 6000

    /** Total nominal duration of all scenes; still bounded because each step is bounded. */
    val totalDurationMs: Int get() = demos.sumOf { spec -> spec.steps.sumOf { it.durationMs } }

    private data class FingerTrack(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val from: Float,
        val to: Float
    )

    private fun track(kind: Kind): FingerTrack? = when (kind) {
        Kind.ENTRY -> FingerTrack(0.79f, 0.40f, 0.79f, 0.36f, 0.05f, 0.55f)
        Kind.TAP_BUBBLE -> FingerTrack(0.80f, 0.52f, 0.80f, 0.50f, 0.05f, 0.60f)
        Kind.DRAG -> FingerTrack(0.26f, 0.57f, 0.74f, 0.57f, 0.02f, 0.88f)
        Kind.LIFT -> FingerTrack(0.74f, 0.57f, 0.80f, 0.31f, 0.02f, 0.48f)
        Kind.TAP_STAR -> FingerTrack(0.83f, 0.30f, 0.83f, 0.29f, 0.05f, 0.60f)
        Kind.TAP_REMOVE -> FingerTrack(0.60f, 0.66f, 0.60f, 0.65f, 0.05f, 0.60f)
        Kind.SCOPE -> FingerTrack(0.34f, 0.52f, 0.34f, 0.50f, 0.05f, 0.60f)
        Kind.CONFIRM_ONE -> FingerTrack(0.64f, 0.74f, 0.64f, 0.73f, 0.05f, 0.60f)
        Kind.CONFIRM_TWO -> FingerTrack(0.64f, 0.74f, 0.64f, 0.73f, 0.05f, 0.60f)
        // The review demo taps the right-hand side of the first Chinese option (the correct one);
        // hairline coordinates keep the dot clear of the option's own label. On the last step the
        // finger presses "下一词" while the answered card is still on screen, then leaves before the
        // demonstration switches to the fresh card, so no finger is left drawn over a result.
        Kind.STUDY_CHOICE -> FingerTrack(0.42f, 0.61f, 0.42f, 0.60f, 0.05f, 0.60f)
        Kind.STUDY_NEXT -> FingerTrack(0.66f, 0.905f, 0.66f, 0.895f, 0.30f, 0.80f)
        Kind.LOOKUP_INPUT -> FingerTrack(0.80f, 0.34f, 0.80f, 0.33f, 0.05f, 0.60f)
        Kind.SUPPLEMENT_EDIT -> FingerTrack(0.52f, 0.55f, 0.52f, 0.54f, 0.05f, 0.60f)
        Kind.SUPPLEMENT_SAVE -> FingerTrack(0.30f, 0.82f, 0.30f, 0.81f, 0.05f, 0.60f)
        Kind.RESULT,
        Kind.STUDY_CORRECT,
        Kind.STUDY_WRONG,
        Kind.LOOKUP_SUCCESS,
        Kind.CONTRAST -> null
    }

    /** Clamped linear progress; a zero duration behaves like an already finished step. */
    fun progressAt(elapsedMs: Long, durationMs: Int): Float {
        val duration = durationMs.coerceIn(1, MAX_STEP_MS)
        if (elapsedMs <= 0L) return 0f
        return (elapsedMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /** Advances the primary scene reveal between [from] and [to] with a smooth cubic ease. */
    private fun smooth(progress: Float, from: Float, to: Float): Float {
        if (to <= from) return if (progress >= to) 1f else 0f
        val local = ((progress - from) / (to - from)).coerceIn(0f, 1f)
        val inverse = 1f - local
        return 1f - inverse * inverse * inverse
    }

    private fun resultVisibleAt(kind: Kind, progress: Float): Boolean = when (kind) {
        Kind.ENTRY -> progress >= 0.55f
        Kind.LIFT -> progress >= 0.78f
        Kind.RESULT,
        Kind.LOOKUP_SUCCESS -> progress >= 0.08f
        Kind.SCOPE,
        Kind.CONFIRM_ONE,
        Kind.CONFIRM_TWO -> progress >= 0.08f
        // The chosen option turns on only after the tap; the feedback block follows immediately.
        Kind.STUDY_CHOICE -> progress >= 0.55f
        Kind.STUDY_CORRECT,
        Kind.STUDY_WRONG -> progress >= 0.35f
        Kind.SUPPLEMENT_SAVE -> progress >= 0.60f
        // STUDY_NEXT has no "result" phase: it opens on the already answered card and the drawing
        // switches to the next card from its own progress threshold.
        else -> false
    }

    private fun highlightAt(kind: Kind, progress: Float): Float = when (kind) {
        Kind.ENTRY -> smooth(progress, 0.10f, 0.55f)
        Kind.DRAG -> progress.coerceIn(0f, 1f)
        Kind.TAP_STAR -> smooth(progress, 0.15f, 0.55f)
        Kind.TAP_BUBBLE,
        Kind.TAP_REMOVE,
        Kind.SCOPE,
        Kind.CONFIRM_ONE,
        Kind.CONFIRM_TWO,
        Kind.LOOKUP_INPUT,
        Kind.SUPPLEMENT_EDIT,
        Kind.SUPPLEMENT_SAVE -> smooth(progress, 0.15f, 0.60f)
        Kind.RESULT,
        Kind.LOOKUP_SUCCESS,
        Kind.STUDY_CORRECT,
        Kind.STUDY_WRONG,
        Kind.STUDY_NEXT,
        Kind.CONTRAST -> smooth(progress, 0.05f, 0.45f)
        Kind.STUDY_CHOICE -> smooth(progress, 0.05f, 0.50f)
        Kind.LIFT -> 1f
    }

    /**
     * One pure frame for [kind] at [elapsedMs]. The frame is always finite and clamped, so a late or
     * duplicated animation callback cannot move the drawing outside its area or reveal a result
     * before the step has started.
     */
    fun frameAt(kind: Kind, elapsedMs: Long, durationMs: Int): Frame {
        val progress = progressAt(elapsedMs, durationMs)
        val finger = track(kind)
        val visible = finger != null && progress >= finger.from && progress <= finger.to
        val local = if (finger != null && finger.to > finger.from) {
            ((progress - finger.from) / (finger.to - finger.from)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val pressed = visible && when (kind) {
            // The stroke stays pressed for the whole drawn drag; only the lift releases it.
            Kind.DRAG -> true
            Kind.LIFT -> progress <= 0.32f
            Kind.TAP_BUBBLE,
            Kind.TAP_STAR,
            Kind.TAP_REMOVE,
            Kind.SCOPE,
            Kind.CONFIRM_ONE,
            Kind.CONFIRM_TWO,
            Kind.STUDY_CHOICE,
            Kind.STUDY_NEXT,
            Kind.LOOKUP_INPUT,
            Kind.SUPPLEMENT_EDIT,
            Kind.SUPPLEMENT_SAVE -> local >= 0.25f && local <= 0.80f
            Kind.ENTRY -> local >= 0.30f && local <= 0.80f
            else -> false
        }
        return Frame(
            progress = progress,
            highlight = highlightAt(kind, progress),
            fingerVisible = visible,
            fingerX = if (finger == null) 0f else finger.startX + (finger.endX - finger.startX) * local,
            fingerY = if (finger == null) 0f else finger.startY + (finger.endY - finger.startY) * local,
            fingerPressed = pressed,
            resultVisible = resultVisibleAt(kind, progress)
        )
    }

    /**
     * Number of distinct static key frames for reduced-motion users. Tapping the illustration walks
     * these in order; the last one is always the completed stage.
     */
    fun keyPhaseCount(kind: Kind): Int = when (kind) {
        // The stroke needs its start, mid-drag and lift frames to read as one continuous line.
        Kind.DRAG -> 5
        // Two key frames: the untouched card, then the tap landed / outcome marked.
        Kind.STUDY_CHOICE,
        Kind.STUDY_CORRECT -> 2
        // Wrong answer has three: untouched, wrong pick marked, correct option also lit.
        Kind.STUDY_WRONG -> 3
        // The last review step needs its own three: the answered card, the tap on "下一词", the new card.
        Kind.STUDY_NEXT -> 3
        // Every other step is a simple before/after with one intermediate reveal.
        else -> 3
    }

    fun staticProgress(kind: Kind, phase: Int): Float {
        val count = keyPhaseCount(kind)
        val index = phase.coerceIn(0, count - 1)
        return index.toFloat() / (count - 1).toFloat()
    }

    /** A deep link to a topic with a demonstration selects that scene; other topics keep text help. */
    fun demoForTopic(topicId: String?): Demo? = when (topicId) {
        "start_capture", "capture_and_manual" -> Demo.CAPTURE
        "vocabulary_scope" -> Demo.VOCABULARY
        "study" -> Demo.STUDY
        "lookup_supplement_vocabulary" -> Demo.LOOKUP_SUPPLEMENT
        else -> null
    }

    val demos: List<DemoSpec> = listOf(
        DemoSpec(
            demo = Demo.CAPTURE,
            summary = "开启悬浮查词 → 点「词」→ 沿单词划一笔 → 抬笔 → 释义卡；真实查询成功才收录。",
            steps = listOf(
                Step(
                    "capture_enable", Demo.CAPTURE, Kind.ENTRY,
                    "① 在首屏点「开启悬浮划词」。",
                    "第一步，演示点按开启悬浮划词；演示不会申请权限。", 1300
                ),
                Step(
                    "capture_tap", Demo.CAPTURE, Kind.TAP_BUBBLE,
                    "② 点悬浮的「词」，准备划词。",
                    "第二步，演示点一下悬浮的词按钮。", 1000
                ),
                Step(
                    "capture_drag", Demo.CAPTURE, Kind.DRAG,
                    "③ 沿一个完整英文单词划一笔。",
                    "第三步，演示笔尖沿单词 harvest 划过，高亮跟着笔尖推进。", 1600
                ),
                Step(
                    "capture_lift", Demo.CAPTURE, Kind.LIFT,
                    "④ 抬笔松手，本机识别并查询。",
                    "第四步，演示抬笔后自动识别并查询。", 1300
                ),
                Step(
                    "capture_result", Demo.CAPTURE, Kind.RESULT,
                    "⑤ 查询成功才弹出释义并收录，没有额外保存按钮。",
                    "第五步，演示查询成功的释义卡；已自动收录，没有额外的保存按钮。", 1600
                )
            )
        ),
        DemoSpec(
            demo = Demo.VOCABULARY,
            summary = "收藏只是标记；移除要先选范围，再经过两次明确确认。",
            steps = listOf(
                Step(
                    "vocab_star", Demo.VOCABULARY, Kind.TAP_STAR,
                    "① 点「☆」只是收藏标记，不改学习进度。",
                    "第一步，演示点星标收藏；星标只是标记，不改变学习进度。", 1100
                ),
                Step(
                    "vocab_remove", Demo.VOCABULARY, Kind.TAP_REMOVE,
                    "② 点「移除学习记录…」，先选择范围。",
                    "第二步，演示打开移除学习记录的入口，先选范围。", 1100
                ),
                Step(
                    "vocab_scope", Demo.VOCABULARY, Kind.SCOPE,
                    "③ 选择仅撤销最近一次，或删除该词全部记录；没有默认项。",
                    "第三步，演示两个移除范围，没有默认选项，需要你明确选择。", 1400
                ),
                Step(
                    "vocab_confirm_one", Demo.VOCABULARY, Kind.CONFIRM_ONE,
                    "④ 第一次确认：核对范围和影响。",
                    "第四步，演示第一次确认对话框。", 1300
                ),
                Step(
                    "vocab_confirm_two", Demo.VOCABULARY, Kind.CONFIRM_TWO,
                    "⑤ 第二次确认后才执行；演示不会真的删除。",
                    "第五步，演示第二次确认；两次确认后才执行，演示不会真的删除。", 1500
                )
            )
        ),
        DemoSpec(
            demo = Demo.STUDY,
            summary = "一张卡看词，选一个中文释义，立即看到对错，再点下一词。",
            steps = listOf(
                Step(
                    "study_choice", Demo.STUDY, Kind.STUDY_CHOICE,
                    "① 看单词，从四个中文释义里选一个。",
                    "第一步，演示看单词，从四个中文释义里点选一个。", 1600
                ),
                Step(
                    "study_correct", Demo.STUDY, Kind.STUDY_CORRECT,
                    "② 选中后立即显示对错反馈。",
                    "第二步，演示选中后立即显示答对反馈。", 1400
                ),
                Step(
                    "study_wrong", Demo.STUDY, Kind.STUDY_WRONG,
                    "③ 选错会标出你选的项和正确答案。",
                    "第三步，演示选错时标出所选错误项，并标出正确答案。", 1600
                ),
                Step(
                    "study_next", Demo.STUDY, Kind.STUDY_NEXT,
                    "④ 点「下一词」继续复习。",
                    "第四步，演示点下一词，继续下一张复习卡。", 1400
                )
            )
        ),
        DemoSpec(
            demo = Demo.LOOKUP_SUPPLEMENT,
            summary = "手动查词成功才收录；补词只维护本机个人释义，两者不能互相替代。",
            steps = listOf(
                Step(
                    "lookup_type", Demo.LOOKUP_SUPPLEMENT, Kind.LOOKUP_INPUT,
                    "① 在查词页输入单词并点「查询」，不依赖悬浮权限。",
                    "第一步，演示在查词页手动输入单词并点查询；手动查词不依赖悬浮权限。", 1300
                ),
                Step(
                    "lookup_success", Demo.LOOKUP_SUPPLEMENT, Kind.LOOKUP_SUCCESS,
                    "② 只有真实查询成功才生成释义并收录。",
                    "第二步，演示真实查询成功的释义卡；只有成功查询才收录。", 1300
                ),
                Step(
                    "supplement_edit", Demo.LOOKUP_SUPPLEMENT, Kind.SUPPLEMENT_EDIT,
                    "③ 在添加/补词页编辑本机个人词性和释义。",
                    "第三步，演示在添加补词页编辑个人词性和释义。", 1300
                ),
                Step(
                    "supplement_save", Demo.LOOKUP_SUPPLEMENT, Kind.SUPPLEMENT_SAVE,
                    "④ 保存补词只写个人释义：不计查询、不收录、不进复习。",
                    "第四步，演示保存补词；只写个人释义，不计查询、不收录、不进复习。", 1400
                ),
                Step(
                    "lookup_contrast", Demo.LOOKUP_SUPPLEMENT, Kind.CONTRAST,
                    "⑤ 要收录就走真实查询；补词只改本机释义。",
                    "第五步，对照演示：要收录走真实查询，补词只改本机释义。", 1500
                )
            )
        )
    )

    fun demo(demo: Demo): DemoSpec = demos.first { it.demo == demo }

    fun step(scene: Demo, index: Int): Step {
        val steps = demo(scene).steps
        return steps[index.coerceIn(0, steps.lastIndex)]
    }
}
