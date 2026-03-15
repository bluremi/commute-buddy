package com.commutebuddy.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNotifierOrchestratorTest {

    private val sampleStatus = CommuteStatus(
        action = CommuteStatus.ACTION_MINOR_DELAYS,
        summary = "Delays on N train",
        affectedRoutes = "N,W",
        rerouteHint = null,
        timestamp = 1_000_000L
    )

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    private class RecordingNotifier : WatchNotifier {
        val received = mutableListOf<CommuteStatus>()
        override fun initialize(context: Context) {}
        override suspend fun notify(status: CommuteStatus) { received.add(status) }
    }

    private class ThrowingNotifier : WatchNotifier {
        override fun initialize(context: Context) {}
        override suspend fun notify(status: CommuteStatus) {
            throw RuntimeException("simulated failure")
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `all notifiers are called`() = runBlocking {
        val a = RecordingNotifier()
        val b = RecordingNotifier()

        notifyAll(listOf(a, b), sampleStatus)

        assertEquals(1, a.received.size)
        assertEquals(1, b.received.size)
    }

    @Test
    fun `one failure does not prevent remaining notifiers from running`() = runBlocking {
        val after = RecordingNotifier()

        notifyAll(listOf(ThrowingNotifier(), after), sampleStatus)

        assertEquals(1, after.received.size)
    }

    @Test
    fun `correct CommuteStatus is passed to each notifier`() = runBlocking {
        val a = RecordingNotifier()
        val b = RecordingNotifier()

        notifyAll(listOf(a, b), sampleStatus)

        assertEquals(sampleStatus, a.received[0])
        assertEquals(sampleStatus, b.received[0])
    }
}
