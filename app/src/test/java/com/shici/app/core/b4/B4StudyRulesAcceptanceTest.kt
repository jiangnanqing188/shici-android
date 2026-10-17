package com.shici.app.core.b4
import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class B4StudyRulesAcceptanceTest {
 private val now=1_800_000_000_000L
 @Test fun booksExactlyRequested(){assertEquals(listOf("ky","cet6","personal"),StudyBook.entries.map{it.id});assertEquals(StudyBook.KAOYAN,StudyBook.default);assertNull(StudyBook.fromId("cet4"))}
 @Test fun defaultsAndGoalBounds(){assertEquals(20,StudySettings().dailyNewGoal);assertEquals(1,StudyRules.clampGoal(Int.MIN_VALUE));assertEquals(200,StudyRules.clampGoal(Int.MAX_VALUE));assertEquals(80,StudyRules.clampGoal(80))}
 @Test fun knowSequenceAndCap(){var stage=-1;for(days in listOf(1,3,7,14,30,30)){assertEquals(now+days*StudyRules.DAY_MILLIS,StudyRules.dueAt(now,stage,FeedbackKind.KNOW));stage=StudyRules.nextStage(stage,FeedbackKind.KNOW)}}
 @Test fun fuzzyExistingDoesNotUpgrade(){for(stage in 0..4){assertEquals(stage,StudyRules.nextStage(stage,FeedbackKind.FUZZY));assertEquals(now+StudyRules.DAY_MILLIS,StudyRules.dueAt(now,stage,FeedbackKind.FUZZY))}}
 @Test fun forgetDelay(){for(stage in -1..4)assertEquals(now+600000,StudyRules.dueAt(now,stage,FeedbackKind.FORGET))}
 @Test fun forgottenThenKnowRestartsAtOneDay(){val stage=StudyRules.nextStage(4,FeedbackKind.FORGET);assertEquals("relearning must restart at 1d",now+StudyRules.DAY_MILLIS,StudyRules.dueAt(now,stage,FeedbackKind.KNOW))}
 @Test fun newFuzzyThenKnowDoesNotSkipOneDay(){val stage=StudyRules.nextStage(-1,FeedbackKind.FUZZY);assertEquals("fuzzy is not a promotion",now+StudyRules.DAY_MILLIS,StudyRules.dueAt(now,stage,FeedbackKind.KNOW))}
 @Test fun dueDoesNotWrapIntoPast(){for(k in FeedbackKind.entries){try{val due=StudyRules.dueAt(Long.MAX_VALUE-1,4,k);assertTrue("overflow $k due=$due",due>0)}catch(e:ArithmeticException){/* explicit rejection is safe */}}}
 @Test fun dayBucketsAndDst(){val at=Instant.parse("2026-10-16T20:00:00Z").toEpochMilli();assertEquals("2026-10-17",StudyRules.dayKey(at,ZoneId.of("Asia/Shanghai")));assertEquals("2026-10-16",StudyRules.dayKey(at,ZoneOffset.UTC));assertEquals("2026-03-08",StudyRules.dayKey(Instant.parse("2026-03-08T10:00:00Z").toEpochMilli(),ZoneId.of("America/Los_Angeles")))}
}
