package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SipShutdownTest {
    @Test fun lateRegistrationSuccessDuringUnregisterCannotRestoreReadiness() {
        DatagramSocket(0).use { peer ->
            peer.soTimeout = 2000
            val ua = SipUserAgent(SipConfig(SipAccount("127.0.0.1", peer.localPort, "test", "test"), localPort = 0))
            val readyCount = AtomicInteger()
            val stopped = CountDownLatch(1)
            ua.addStatusListener { _, status ->
                if (status.ready) readyCount.incrementAndGet()
                if (status.detail == "stopped") stopped.countDown()
            }
            try {
                ua.start()
                val packet = DatagramPacket(ByteArray(4096), 4096)
                peer.receive(packet)
                val registration = SipParser.parse(packet.data, packet.length) as SipRequest
                val stopping = CompletableFuture.runAsync { ua.stop() }
                var unregister: SipRequest
                do {
                    peer.receive(packet)
                    unregister = SipParser.parse(packet.data, packet.length) as SipRequest
                } while (unregister.headers["Expires"] != "0")

                // The original REGISTER succeeds after shutdown has already sent its un-REGISTER.
                for (request in listOf(registration, unregister)) {
                    val response = ua.buildResponse(request, 200).apply {
                        headers.set("Expires", request.headers["Expires"]!!)
                    }.encode()
                    peer.send(DatagramPacket(response, response.size, packet.socketAddress))
                }
                stopping.get(3, TimeUnit.SECONDS)
                assertTrue(stopped.await(2, TimeUnit.SECONDS))
                assertEquals(0, readyCount.get())
                assertFalse(ua.status.ready)
            } finally { ua.stop(timeoutMs = 50) }
        }
    }

    @Test fun resolutionFailureIsReportedAndRegistrationRecoversOnNetworkRefresh() {
        DatagramSocket(0).use { peer ->
            peer.soTimeout = 2000
            val attempts = AtomicInteger()
            val failed = CountDownLatch(1)
            val registered = CountDownLatch(1)
            val ua = SipUserAgent(SipConfig(SipAccount("127.0.0.1", peer.localPort, "test", "test"), localPort = 0),
                object : SipLog {
                    override fun info(msg: String) {}
                    override fun warn(msg: String, t: Throwable?) {}
                }, localAddressProvider = {
                    if (attempts.incrementAndGet() == 1) throw UnknownHostException("temporary resolution failure")
                    InetAddress.getLoopbackAddress()
                })
            ua.addStatusListener { _, status ->
                if (status.detail.startsWith("registration failed: cannot resolve")) failed.countDown()
                if (status.ready) registered.countDown()
            }
            try {
                ua.start()
                assertTrue(failed.await(2, TimeUnit.SECONDS))
                assertFalse(ua.status.ready)
                ua.refreshRegistration()
                val packet = DatagramPacket(ByteArray(4096), 4096)
                peer.receive(packet)
                val request = SipParser.parse(packet.data, packet.length) as SipRequest
                assertEquals("REGISTER", request.method)
                val response = ua.buildResponse(request, 200).apply { headers.set("Expires", "60") }.encode()
                peer.send(DatagramPacket(response, response.size, packet.socketAddress))
                assertTrue(registered.await(2, TimeUnit.SECONDS))
                assertEquals(2, attempts.get())
            } finally { ua.stop(timeoutMs = 50) }
        }
    }

    @Test fun unregisterTimeoutStillClosesSocketAndCancelsLongTransactionTimers() {
        DatagramSocket(0).use { peer ->
            val log = object : SipLog {
                override fun info(msg: String) {}
                override fun warn(msg: String, t: Throwable?) {}
            }
            val ua = SipUserAgent(SipConfig(SipAccount("127.0.0.1", peer.localPort, "test", "test"), localPort = 0), log)
            val ready = CountDownLatch(1)
            val thread = AtomicReference<Thread>()
            ua.addStatusListener { _, status ->
                if (status.detail == "registering") { thread.set(Thread.currentThread()); ready.countDown() }
            }
            try {
                ua.start()
                assertTrue(ready.await(2, TimeUnit.SECONDS))
                val port = ua.transport.localPort
                val lateTask = AtomicInteger()
                ua.schedule(60_000) { lateTask.incrementAndGet() }
                // The local peer deliberately never answers either REGISTER.
                ua.stop(timeoutMs = 50)
                thread.get().join(2000)
                assertFalse(thread.get().isAlive)
                assertFalse(ua.status.ready)
                assertEquals("stopped", ua.status.detail)
                assertEquals(0, lateTask.get())
                DatagramSocket(port).use { assertEquals(port, it.localPort) }
            } finally {
                if (thread.get()?.isAlive == true) ua.stop(timeoutMs = 50)
            }
        }
    }
}
