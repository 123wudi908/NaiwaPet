package com.naiwa.androidpet.game

import org.junit.Assert.*
import org.junit.Test

class DrumRhythmTest {
    @Test fun inclusiveTimingWindowsMatchTheVisibleCue() {
        for ((offset,expected) in listOf(-261L to HitFeedback.MISS,-260L to HitFeedback.GOOD,
            -131L to HitFeedback.GOOD,-130L to HitFeedback.PERFECT,0L to HitFeedback.PERFECT,
            130L to HitFeedback.PERFECT,131L to HitFeedback.GOOD,260L to HitFeedback.GOOD)) {
            val game=GameSession(GameKind.DRUM,"window",1)
            game.start();game.advance(DrumRhythm.FIRST_BEAT_MILLIS+offset)
            assertEquals("offset=$offset",expected,game.tap())
            assertEquals(expected!=HitFeedback.MISS,DrumRhythm.cueAt(game.elapsedMillis)!!.hittable)
        }
    }

    @Test fun mistimedTapKeepsComboAndCanBeCorrectedInTheSameBeat() {
        val game=GameSession(GameKind.DRUM,"correct",1)
        game.start();game.advance(1400);game.tap()
        game.advance(500)
        assertEquals(HitFeedback.MISS,game.tap())
        assertEquals(1,game.combo);assertEquals(0,game.misses)
        game.advance(400)
        assertEquals(HitFeedback.PERFECT,game.tap())
        assertEquals(2,game.combo);assertEquals(200,game.score)
        game.advance(120)
        assertEquals(HitFeedback.IGNORED,game.tap())
        assertEquals(200,game.score)
    }

    @Test fun tempoIncreasesInThreeStagesAndEveryFinalWindowFits() {
        assertEquals(1400L,DrumRhythm.beats.first())
        assertEquals(1,DrumRhythm.stageAt(7999));assertEquals(2,DrumRhythm.stageAt(8000))
        assertEquals(3,DrumRhythm.stageAt(16000))
        assertEquals(setOf(900L,800L,720L),DrumRhythm.beats.zipWithNext { a,b -> b-a }.toSet())
        assertTrue(DrumRhythm.beats.last()+260<=24000)
        assertNull(DrumRhythm.cueAt(24000))
        val game=GameSession(GameKind.DRUM,"deadline",1)
        game.start();game.advance(Long.MAX_VALUE)
        assertEquals(GamePhase.FINISHED,game.phase)
        assertEquals(24000L,game.elapsedMillis)
        assertEquals(DrumRhythm.beats.size,game.misses)
        assertEquals(HitFeedback.IGNORED,game.tap())
    }

    @Test fun perfectFullRoundFinishesAndRestoringDoesNotRepeatJudgments() {
        var game=GameSession(GameKind.DRUM,"round",1)
        game.start()
        DrumRhythm.beats.forEachIndexed { index,time ->
            game.advance(time-game.elapsedMillis)
            assertEquals(HitFeedback.PERFECT,game.tap())
            if(index==8) {
                game.pause();game=GameSession.restore(game.snapshot())
                game.advance(5000);assertEquals(time,game.elapsedMillis)
                game.resume();assertEquals(HitFeedback.IGNORED,game.tap())
            }
        }
        game.advance(game.remainingMillis)
        assertEquals(GamePhase.FINISHED,game.phase)
        assertEquals(DrumRhythm.beats.size*100,game.score)
        assertEquals(DrumRhythm.beats.size,game.maxCombo)
        assertEquals(0,game.misses)
    }
}
