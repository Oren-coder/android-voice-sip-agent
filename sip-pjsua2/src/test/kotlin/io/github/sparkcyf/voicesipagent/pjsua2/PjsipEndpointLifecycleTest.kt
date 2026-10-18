package io.github.sparkcyf.voicesipagent.pjsua2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PjsipEndpointLifecycleTest {
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
