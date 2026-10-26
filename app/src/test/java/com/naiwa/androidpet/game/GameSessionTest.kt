package com.naiwa.androidpet.game

import org.junit.Assert.*
import org.junit.Test

class GameSessionTest {
    @Test fun `ready paused and finished games cannot accept scoring input`() {
        val game = GameSession(GameKind.COMBO,"one",7)
        game.tap(); assertEquals(0,game.score)
        game.start(); game.advance(100); game.tap(); assertEquals(11,game.score)
        game.pause(); game.advance(9000); game.tap()
        assertEquals(100L,game.elapsedMillis); assertEquals(11,game.score)
        game.resume(); game.advance(15_000)
        assertEquals(GamePhase.FINISHED,game.phase)
        game.tap(); assertEquals(11,game.score)
    }
    @Test fun `rhythm judges only one input per beat and distinguishes timing quality`() {
        val game = GameSession(GameKind.DRUM,"drum",1)
        game.start(); game.advance(1000)
        assertEquals(HitFeedback.PERFECT,game.tap()); assertEquals(100,game.score)
        assertEquals(HitFeedback.IGNORED,game.tap()); assertEquals(100,game.score)
        game.advance(740)
        assertEquals(HitFeedback.GOOD,game.tap()); assertEquals(160,game.score)
        game.advance(450)
        assertEquals(HitFeedback.PERFECT,game.tap())
    }
    @Test fun `combo rate limit and nine hundred millisecond window prevent duplicate scoring`() {
        val game = GameSession(GameKind.COMBO,"combo",1)
        game.start(); game.tap(); repeat(100) { game.tap() }
        assertEquals(11,game.score)
        game.advance(100);game.tap();assertEquals(2,game.combo)
        game.advance(1000);game.tap();assertEquals(1,game.combo)
        assertEquals(2,game.maxCombo)
    }
    @Test fun `bubble crossing scores once when aligned and counts a miss outside the catcher`() {
        val game = GameSession(GameKind.BUBBLES,"bubble",7)
        game.start();game.advance(1)
        val first = game.bubbles.first()
        game.moveTo(first.x)
        game.advance(2200)
        assertEquals(10,game.score)
        game.moveTo(if (game.bubbles.first().x > .5f) 0f else 1f)
        game.advance(750)
        assertTrue(game.misses >= 1)
        assertEquals(10,game.score)
    }
    @Test fun `restored game preserves random sequence score timer and already judged beats`() {
        val game = GameSession(GameKind.DRUM,"persistent",17)
        game.start();game.advance(1000);game.tap();game.pause()
        val restored = GameSession.restore(game.snapshot())
        assertEquals(GamePhase.PAUSED,restored.phase)
        assertEquals(100,restored.score)
        restored.resume();assertEquals(HitFeedback.IGNORED,restored.tap())
        restored.advance(600); assertEquals(HitFeedback.PERFECT,restored.tap())
        assertEquals("persistent",restored.id)
    }
    @Test fun `a long frame ends exactly at the deadline and cannot spawn unbounded work`() {
        val game = GameSession(GameKind.BUBBLES,"long",3)
        game.start();game.advance(Long.MAX_VALUE)
        assertEquals(30_000L,game.elapsedMillis)
        assertEquals(GamePhase.FINISHED,game.phase)
        assertTrue(game.bubbles.size < 8)
    }
}
