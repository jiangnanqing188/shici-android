package com.shici.app.core.b4.s5

import com.shici.app.core.FeedbackKind
import com.shici.app.core.StudyRules
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5 only: call real production rules; oracle never calls nextStage or reads interval constants. */
class B4S5TimeBoundsTest {
    private val day = 86_400_000L
    private val normalNow = 1_800_000_000_000L
    private val max = BigInteger.valueOf(Long.MAX_VALUE)

    private fun delay(stage: Int, feedback: FeedbackKind): Long = when (feedback) {
        FeedbackKind.KNOW -> longArrayOf(1, 3, 7, 14, 30, 30)[stage + 1] * day
        FeedbackKind.FUZZY -> day
        FeedbackKind.FORGET -> 600_000L
    }

    private fun allCases(time: (Long) -> Long) {
        for (stage in -1..4) for (feedback in FeedbackKind.entries) {
            val duration = delay(stage, feedback)
            val now = time(duration)
            val expected = BigInteger.valueOf(now).add(BigInteger.valueOf(duration)).min(max).longValueExact()
            val actual = StudyRules.dueAt(now, stage, feedback)
            val label = "stage=$stage feedback=$feedback now=$now delay=$duration"
            assertEquals(label, expected, actual)
            assertTrue("must not wrap backwards: $label due=$actual", actual >= now)
        }
    }

    @Test fun normalKnowIntervalsAndCapUnchanged() {
        for (stage in -1..4) assertEquals(normalNow + delay(stage, FeedbackKind.KNOW),
            StudyRules.dueAt(normalNow, stage, FeedbackKind.KNOW))
    }
    @Test fun normalFuzzyOneDayUnchanged() {
        for (stage in -1..4) assertEquals(normalNow + day, StudyRules.dueAt(normalNow, stage, FeedbackKind.FUZZY))
    }
    @Test fun normalForgetTenMinutesUnchanged() {
        for (stage in -1..4) assertEquals(normalNow + 600_000L, StudyRules.dueAt(normalNow, stage, FeedbackKind.FORGET))
    }
    @Test fun maximumSaturates() = allCases { Long.MAX_VALUE }
    @Test fun maximumMinusOneSaturates() = allCases { Long.MAX_VALUE - 1 }
    @Test fun exactMaximumMinusDelayReachesMaximum() = allCases { Long.MAX_VALUE - it }
    @Test fun oneBeforeUpperThresholdRemainsExact() = allCases { Long.MAX_VALUE - it - 1 }
    @Test fun oneAfterUpperThresholdSaturates() = allCases { Long.MAX_VALUE - it + 1 }
    @Test fun minimumAddsExactlyWithoutWrapping() = allCases { Long.MIN_VALUE }
    @Test fun minimumPlusOneAddsExactly() = allCases { Long.MIN_VALUE + 1 }
    @Test fun historicalNegativeTimestampStaysExact() = allCases { -1_800_000_000_000L }
    @Test fun negativeOneCrossesZeroSafely() = allCases { -1L }
    @Test fun exactNegativeDelayReachesZero() = allCases { -it }
    @Test fun oneBeforeNegativeDelayRemainsNegativeOne() = allCases { -it - 1 }
    @Test fun oneAfterNegativeDelayReachesPositiveOne() = allCases { -it + 1 }
    @Test fun zeroAddsExactDelay() = allCases { 0L }
}
