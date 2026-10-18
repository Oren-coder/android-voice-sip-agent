package io.github.sparkcyf.voicesipagent.pjsua2

import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PjsipEndpointLifecycleTest {
    @Test fun workerFailureClearsReadinessAndEndsCallsWithoutWaitingForNativeCallbacks() {
        val endpoint = endpoint()
        val statuses = mutableListOf<EndpointStatus>()
        endpoint.addStatusListener { _, status -> statuses += status }
        setStatus(endpoint, EndpointStatus(true, "registered"))
        val leg = PjsipCall(endpoint, LegDirection.OUTBOUND, Party("test"), "test", "p1")
        // Exercise a connected local leg without loading the device-only JNI library on the host.
        AbstractCallLeg::class.java.getDeclaredMethod("transition", LegState::class.java, HangupCause::class.java)
            .apply { isAccessible = true }.invoke(leg, LegState.CONNECTED, null)
        endpoint.calls[1] = leg

        endpoint.onWorkerFailure(IllegalStateException("poll failed"))
        assertEquals(EndpointStatus(false, "SIP worker failed: poll failed"), endpoint.status)
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(HangupCause.NETWORK_ERROR, leg.endCause)
        assertTrue(endpoint.calls.isEmpty())

        // Duplicate failure delivery and late registration callbacks cannot revive the endpoint.
        endpoint.onWorkerFailure(IllegalStateException("duplicate"))
        setStatus(endpoint, EndpointStatus(true, "late registration"))
        endpoint.start()
        assertEquals(2, statuses.size)
        assertFalse(endpoint.status.ready)
        assertEquals(LegState.ENDED, endpoint.dial("test", Party("test")).state)
    }

    private fun endpoint() = PjsipEndpoint(PjsipConfig(server = "pbx.invalid", username = "test", password = "test"))

    private fun setStatus(endpoint: PjsipEndpoint, status: EndpointStatus) {
        PjsipEndpoint::class.java.getDeclaredMethod("setStatus", EndpointStatus::class.java)
            .apply { isAccessible = true }.invoke(endpoint, status)
    }

    @Test fun stoppingQueuedStartupSkipsNativeInitialization() {
        val startup = PjsipEndpoint::class.java.getDeclaredField("startup").apply { isAccessible = true }
            .get(null) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val warnings = CopyOnWriteArrayList<String>()
        val endpoint = PjsipEndpoint(
            PjsipConfig(server = "pbx.invalid", username = "test", password = "test"),
            object : PjsipLog {
                override fun info(msg: String) {}
                override fun warn(msg: String, t: Throwable?) { warnings += msg }
            },
        )
        // Hold the startup queue so cancellation happens before any JNI or DNS work.
        startup.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(PjsuaLib.running)
            endpoint.start()
            assertEquals("registering", endpoint.status.detail)
            endpoint.stop()
            release.countDown()
            startup.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals("stopped", endpoint.status.detail)
            assertFalse(endpoint.status.ready)
            assertFalse(PjsuaLib.running)
            assertTrue(warnings.toString(), warnings.isEmpty())
        } finally {
            release.countDown()
            endpoint.stop(timeoutMs = 0)
            PjsipEndpoint.stopLibrary()
            startup.submit {}.get(3, TimeUnit.SECONDS)
        }
    }
}
