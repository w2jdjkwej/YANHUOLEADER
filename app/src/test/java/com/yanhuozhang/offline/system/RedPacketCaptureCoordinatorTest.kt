package com.yanhuozhang.offline.system

import com.yanhuozhang.offline.core.RedPacketChatPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedPacketCaptureCoordinatorTest {
    @Test
    fun `new outgoing card after amount page completes candidate once`() {
        val coordinator = RedPacketCaptureCoordinator()
        coordinator.observeChat(snapshot(), nowMillis = 1_000)
        assertTrue(coordinator.observeAmount(amountCents = 1, nowMillis = 2_000))

        assertEquals(
            RedPacketCaptureResult.Completed(amountCents = 1),
            coordinator.observeChat(snapshot("card" to 1), nowMillis = 3_000),
        )
        assertNull(coordinator.observeChat(snapshot("card" to 1), nowMillis = 3_100))
    }

    @Test
    fun `an additional identical card is detected by increased count`() {
        val coordinator = RedPacketCaptureCoordinator()
        coordinator.observeChat(snapshot("same" to 1), nowMillis = 1_000)
        coordinator.observeAmount(amountCents = 2, nowMillis = 2_000)

        assertEquals(
            RedPacketCaptureResult.Completed(amountCents = 2),
            coordinator.observeChat(snapshot("same" to 2), nowMillis = 3_000),
        )
    }

    @Test
    fun `unchanged chat does not complete candidate`() {
        val coordinator = RedPacketCaptureCoordinator()
        coordinator.observeChat(snapshot("old" to 1), nowMillis = 1_000)
        coordinator.observeAmount(amountCents = 1, nowMillis = 2_000)

        assertNull(coordinator.observeChat(snapshot("old" to 1), nowMillis = 3_000))
    }

    @Test
    fun `new card during render grace completes candidate`() {
        val coordinator = RedPacketCaptureCoordinator()
        coordinator.observeChat(snapshot(), nowMillis = 1_000)
        coordinator.observeAmount(amountCents = 1, nowMillis = 2_000)
        assertNull(coordinator.observeChat(snapshot(), nowMillis = 3_000))

        assertEquals(
            RedPacketCaptureResult.Completed(amountCents = 1),
            coordinator.observeChat(snapshot("new" to 1), nowMillis = 7_500),
        )
    }

    @Test
    fun `new card after render grace does not complete canceled candidate`() {
        val coordinator = RedPacketCaptureCoordinator()
        coordinator.observeChat(snapshot(), nowMillis = 1_000)
        coordinator.observeAmount(amountCents = 1, nowMillis = 2_000)
        assertNull(coordinator.observeChat(snapshot(), nowMillis = 3_000))

        assertNull(coordinator.observeChat(snapshot("late" to 1), nowMillis = 8_001))
    }

    @Test
    fun `candidate expires after overall timeout`() {
        val coordinator = RedPacketCaptureCoordinator()
        coordinator.observeChat(snapshot(), nowMillis = 1_000)
        coordinator.observeAmount(amountCents = 1, nowMillis = 2_000)

        assertNull(coordinator.observeChat(snapshot("late" to 1), nowMillis = 92_001))
    }

    @Test
    fun `amount page requires a recent chat baseline`() {
        val coordinator = RedPacketCaptureCoordinator()

        assertFalse(coordinator.observeAmount(amountCents = 1, nowMillis = 2_000))

        coordinator.observeChat(snapshot(), nowMillis = 3_000)
        assertFalse(coordinator.observeAmount(amountCents = 1, nowMillis = 303_001))
    }

    @Test
    fun `non positive amount clears pending candidate`() {
        val coordinator = RedPacketCaptureCoordinator()
        coordinator.observeChat(snapshot(), nowMillis = 1_000)
        coordinator.observeAmount(amountCents = 1, nowMillis = 2_000)

        assertFalse(coordinator.observeAmount(amountCents = 0, nowMillis = 2_100))
        assertNull(coordinator.observeChat(snapshot("new" to 1), nowMillis = 3_000))
    }

    private fun snapshot(vararg cards: Pair<String, Int>) = RedPacketChatPage(
        outgoingCards = mapOf(*cards),
    )
}
