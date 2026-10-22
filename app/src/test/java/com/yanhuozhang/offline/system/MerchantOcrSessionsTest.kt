package com.yanhuozhang.offline.system

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantOcrSessionsTest {
    @Test
    fun `one payment page takes only one screenshot`() {
        val sessions = MerchantOcrSessions()
        val first = sessions.obtain(404, 1_000) { "page-1000-404" }

        val second = sessions.obtain(404, 1_500) { error("同一笔支付不应新建会话") }

        assertSame(first, second)
        assertTrue(sessions.claimScreenshot(first) { CompletableDeferred<String?>() })
        assertFalse(sessions.claimScreenshot(second) { CompletableDeferred<String?>() })
    }

    @Test
    fun `returning to the same payment within window reuses session`() {
        val sessions = MerchantOcrSessions(reuseWindowMillis = 30_000)
        val session = sessions.obtain(404, 1_000) { "page-1000-404" }

        assertSame(session, sessions.markPageAbsent(2_000))
        assertNull(sessions.markPageAbsent(2_500))
        val returned = sessions.obtain(404, 20_000) { error("窗口内应沿用原会话") }

        assertSame(session, returned)
        assertEquals("page-1000-404", returned.sourceEventId)
    }

    @Test
    fun `new payment after window or with another amount starts new session`() {
        val sessions = MerchantOcrSessions(reuseWindowMillis = 30_000)
        val first = sessions.obtain(404, 0) { "page-0-404" }
        sessions.markPageAbsent(1_000)

        val later = sessions.obtain(404, 40_000) { "page-40000-404" }
        val other = sessions.obtain(990, 41_000) { "page-41000-990" }

        assertNotSame(first, later)
        assertEquals("page-40000-404", later.sourceEventId)
        assertEquals("page-41000-990", other.sourceEventId)
    }

    @Test
    fun `find never creates a session`() {
        val sessions = MerchantOcrSessions()

        assertNull(sessions.find(404, 1_000))
    }

    @Test
    fun `ingests once and only re-ingests to fill a missing merchant`() {
        val sessions = MerchantOcrSessions()
        val session = sessions.obtain(404, 1_000) { "page-1000-404" }

        assertTrue(sessions.claimIngest(session, null))
        assertFalse(sessions.claimIngest(session, null))
        assertTrue(sessions.claimIngest(session, "京东"))
        assertFalse(sessions.claimIngest(session, "京东"))
        assertFalse(sessions.claimIngest(session, null))
    }

    @Test
    fun `merchant ingest is never downgraded to blank`() {
        val sessions = MerchantOcrSessions()
        val session = sessions.obtain(404, 1_000) { "page-1000-404" }

        assertTrue(sessions.claimIngest(session, "京东"))

        assertFalse(sessions.claimIngest(session, null))
        assertFalse(sessions.claimIngest(session, "京乐"))
    }

    @Test
    fun `ingested session is not handed off when page disappears`() {
        val sessions = MerchantOcrSessions()
        val session = sessions.obtain(404, 1_000) { "page-1000-404" }
        sessions.claimIngest(session, "京东")

        assertNull(sessions.markPageAbsent(2_000))
    }

    @Test
    fun `decision only upgrades from blank to merchant`() {
        val sessions = MerchantOcrSessions()
        val session = sessions.obtain(404, 1_000) { "page-1000-404" }

        assertNull(sessions.decide(session, null))
        assertEquals("京东", sessions.decide(session, "京东"))
        assertEquals("京东", sessions.decide(session, "京乐"))
        assertEquals("京东", sessions.decide(session, null))
    }
}
