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

    @Test
    fun `empty notifier list completes without error`() = runBlocking {
        notifyAll(emptyList(), sampleStatus)
        // no exception = pass
    }

    @Test
    fun `all notifiers throwing calls onError for each`() = runBlocking {
        val errors = mutableListOf<Pair<WatchNotifier, Exception>>()
        val t1 = ThrowingNotifier()
        val t2 = ThrowingNotifier()

        notifyAll(listOf(t1, t2), sampleStatus) { notifier, e -> errors.add(notifier to e) }

        assertEquals(2, errors.size)
        assertEquals(t1, errors[0].first)
        assertEquals(t2, errors[1].first)
        assert(errors[0].second is RuntimeException)
        assert(errors[1].second is RuntimeException)
    }

    @Test
    fun `failure after recording notifier does not prevent earlier notifier from receiving`() = runBlocking {
        val before = RecordingNotifier()

        notifyAll(listOf(before, ThrowingNotifier()), sampleStatus)

        assertEquals(1, before.received.size)
    }

    @Test
    fun `every ACTION variant is passed through unchanged`() = runBlocking {
        val actions = listOf(
            CommuteStatus.ACTION_NORMAL,
            CommuteStatus.ACTION_MINOR_DELAYS,
            CommuteStatus.ACTION_REROUTE,
            CommuteStatus.ACTION_STAY_HOME
        )
        for (action in actions) {
            val status = CommuteStatus(
                action = action,
                summary = "Test summary",
                affectedRoutes = if (action == CommuteStatus.ACTION_NORMAL) "" else "N",
                rerouteHint = null,
                timestamp = 1_000_000L
            )
            val recorder = RecordingNotifier()
            notifyAll(listOf(recorder), status)
            assertEquals("action mismatch for $action", action, recorder.received[0].action)
        }
    }

    @Test
    fun `rerouteHint null and non-null both arrive unchanged`() = runBlocking {
        val withHint = sampleStatus.copy(rerouteHint = "Take the F train")
        val withNull = sampleStatus.copy(rerouteHint = null)

        val r1 = RecordingNotifier()
        val r2 = RecordingNotifier()
        notifyAll(listOf(r1), withHint)
        notifyAll(listOf(r2), withNull)

        assertEquals("Take the F train", r1.received[0].rerouteHint)
        assertEquals(null, r2.received[0].rerouteHint)
    }

    @Test
    fun `onError receives exact notifier instance and exception that threw`() = runBlocking {
        var capturedNotifier: WatchNotifier? = null
        var capturedException: Exception? = null
        val thrower = ThrowingNotifier()

        notifyAll(listOf(thrower), sampleStatus) { n, e ->
            capturedNotifier = n
            capturedException = e
        }

        assertEquals(thrower, capturedNotifier)
        assert(capturedException is RuntimeException)
        assertEquals("simulated failure", capturedException!!.message)
    }
}
