package com.naiwa.androidpet.update

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class UpdateCoordinatorTest {
    private class Store : UpdateStore {
        override var lastCheck = 0L
        override var ignoredVersion = 0L
        override var notifiedVersion = 0L
    }

    @Test fun `concurrent manual and background checks share one request`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val store = Store()
        val coordinator = UpdateCoordinator(UpdateSource {
            count.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS)
            CheckResult.Unavailable("尚未发布正式 Release")
        }, store, { 123L })
        val worker = thread { coordinator.check() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(coordinator.check())
        release.countDown(); worker.join(5000)
        assertEquals(1, count.get())
        assertEquals(123L, store.lastCheck)
        assertTrue(coordinator.state is CheckResult.Unavailable)
    }

    @Test fun `network failure is visible and a subsequent check can recover`() {
        var fail = true
        val coordinator = UpdateCoordinator(UpdateSource {
            if (fail) throw java.io.IOException("offline") else CheckResult.UpToDate
        }, Store(), { 10L })
        assertTrue(coordinator.check())
        assertTrue(coordinator.state is CheckResult.Failure)
        fail = false
        assertTrue(coordinator.check())
        assertEquals(CheckResult.UpToDate, coordinator.state)
    }

    @Test fun `job specific source does not replace the normal source`() {
        val coordinator = UpdateCoordinator(UpdateSource { CheckResult.Unavailable("normal") }, Store(), { 1L })
        coordinator.check(UpdateSource { CheckResult.UpToDate })
        assertEquals(CheckResult.UpToDate, coordinator.state)
        coordinator.check()
        assertEquals(CheckResult.Unavailable("normal"), coordinator.state)
    }
}
