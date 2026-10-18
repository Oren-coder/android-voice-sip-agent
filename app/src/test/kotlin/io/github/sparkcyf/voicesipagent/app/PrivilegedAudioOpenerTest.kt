package io.github.sparkcyf.voicesipagent.app

import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.util.Log
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAudioStatus
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import io.github.sparkcyf.voicesipagent.privileged.PcmSocketPort
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.*

class PrivilegedAudioOpenerTest {
    private val connector = mock(ShizukuConnector::class.java)
    private val service = mock(IPrivilegedService::class.java)
    private val pfd = mock(ParcelFileDescriptor::class.java)
    private val port = mock(PcmSocketPort::class.java)
    private lateinit var log: MockedStatic<Log>

    @Before fun setup() {
        log = mockStatic(Log::class.java)
        doReturn(service).`when`(connector).service
        doReturn(pfd).`when`(service).openCallAudio(AudioMethod.AUTO, 8000)
        doReturn("open").`when`(service).audioStats()
        report(CallAudioStatus())
    }
    @After fun teardown() { log.close() }

    private fun opener(mute: Boolean = false,
        directMuteBlocked: Boolean = false,
        onIncompatible: () -> Unit = {},
        factory: (ParcelFileDescriptor, PcmFormat, (String) -> Unit) -> PcmSocketPort = { _, _, _ -> port }) =
        PrivilegedAudioOpener(connector, { AudioMethod.AUTO }, { mute }, { PcmFormat.NARROWBAND }, factory,
            directMuteBlocked = { directMuteBlocked }, onDirectMuteIncompatible = onIncompatible)

    private fun report(status: CallAudioStatus) {
        val bundle = mock(Bundle::class.java)
        doReturn(status.active).`when`(bundle).getBoolean("active")
        doReturn(status.backend).`when`(bundle).getString("backend")
        doReturn(status.muteRequested).`when`(bundle).getBoolean("mute_requested")
        doReturn(status.outputMute).`when`(bundle).getString("output_mute")
        doReturn(status.muteDetail).`when`(bundle).getString("mute_detail")
        doReturn(status.ownsOutputChange).`when`(bundle).getBoolean("owns_output_change")
        doReturn(status.restorationPending).`when`(bundle).getBoolean("restoration_pending")
        doReturn(status.restoreDetail).`when`(bundle).getString("restore_detail")
        doReturn(status.voiceCallStreamMuted != null).`when`(bundle).containsKey("voice_call_stream_muted")
        doReturn(status.voiceCallStreamMuted == true).`when`(bundle).getBoolean("voice_call_stream_muted")
        doReturn(status.directMuteIncompatible).`when`(bundle).getBoolean("direct_mute_incompatible")
        doReturn(status.recoveryDetail).`when`(bundle).getString("recovery_detail")
        doReturn(bundle).`when`(service).callAudioStatus()
    }

    @Test fun portConstructionFailureClosesDescriptorAndHelperTap() {
        val result = runCatching { opener { _, _, _ -> error("port construction failed") }.open {} }
        assertTrue(result.isFailure)
        verify(pfd).close()
        verify(service).closeCallAudio()
    }

    @Test fun readerStartupFailureClosesDescriptorAndHelperTap() {
        doThrow(IllegalStateException("reader start failed")).`when`(port).start()
        assertTrue(runCatching { opener().open {} }.isFailure)
        verify(port).close()
        verify(pfd).close()
        verify(service).closeCallAudio()
    }

    @Test fun statsFailureDoesNotLoseOwnershipOfOpenTap() {
        doThrow(IllegalStateException("diagnostics unavailable")).`when`(service).audioStats()
        val handle = opener().open {}
        assertSame(port, handle.port)
        handle.close()
        verify(port).close()
        verify(service).closeCallAudio()
    }

    @Test fun staleHandleCloseDoesNotCloseNewHelperTap() {
        val nextPort = mock(PcmSocketPort::class.java)
        var calls = 0
        val opener = opener { _, _, _ -> if (calls++ == 0) port else nextPort }
        report(CallAudioStatus(active = true, backend = "direct"))
        val old = opener.open {}
        report(CallAudioStatus(active = true, backend = "redirection-helper"))
        val next = opener.open {}
        val current = opener.status.value
        old.close()
        assertEquals(current, opener.status.value)
        verify(port).close()
        verify(service, never()).closeCallAudio()
        next.close()
        next.close()
        verify(nextPort).close()
        verify(service).closeCallAudio()
    }

    @Test fun localCloseFailureStillClosesHelperAndIsNotRepeated() {
        doThrow(IllegalStateException("local socket failure")).`when`(port).close()
        val handle = opener().open {}
        assertTrue(runCatching { handle.close() }.isFailure)
        handle.close()
        verify(port).close()
        verify(service).closeCallAudio()
    }

    @Test fun statusQueryFailurePreservesOwnershipAndCanRecover() {
        val applied = CallAudioStatus(active = true, backend = "direct", muteRequested = true,
            outputMute = CallAudioStatus.APPLIED, ownsOutputChange = true, voiceCallStreamMuted = true)
        report(applied)
        val opener = opener(mute = true)
        val handle = opener.open {}
        doThrow(IllegalStateException("status unavailable")).`when`(service).callAudioStatus()
        opener.refreshStatus()
        assertEquals(applied.copy(available = false), opener.status.value)
        report(applied.copy(outputMute = CallAudioStatus.NOT_APPLIED, voiceCallStreamMuted = false))
        opener.refreshStatus()
        assertTrue(opener.status.value.available)
        assertEquals(CallAudioStatus.NOT_APPLIED, opener.status.value.outputMute)
        handle.close()
    }

    @Test fun firstStatusQueryFailureKeepsTheTapActiveForPolling() {
        doThrow(IllegalStateException("status unavailable")).`when`(service).callAudioStatus()
        val opener = opener()
        val handle = opener.open {}
        assertTrue(opener.status.value.active)
        assertFalse(opener.status.value.available)
        report(CallAudioStatus(active = true, backend = "redirection-manual"))
        opener.refreshStatus()
        assertEquals("Cellular call redirection", opener.status.value.backendLabel)
        assertTrue(opener.status.value.available)
        handle.close()
    }

    @Test fun muteTransactionFailureRemainsVisibleWhenHelperDidNotReceiveTheRequest() {
        report(CallAudioStatus(active = true, backend = "direct"))
        doThrow(IllegalStateException("mute transaction failed")).`when`(service).setLocalOutputMuted(true)
        val opener = opener(mute = true)
        val handle = opener.open {}
        assertTrue(opener.status.value.muteRequested)
        assertEquals(CallAudioStatus.UNKNOWN, opener.status.value.outputMute)
        assertTrue(opener.status.value.muteDetail.contains("mute transaction failed"))
        handle.close()
    }

    @Test fun helperDeathKeepsDirectStreamRestorationUncertainUntilUnmuted() {
        report(CallAudioStatus(active = true, backend = "direct", muteRequested = true,
            outputMute = CallAudioStatus.APPLIED, ownsOutputChange = true, voiceCallStreamMuted = true))
        val opener = opener(mute = true)
        val handle = opener.open {}
        doReturn(null).`when`(connector).service
        opener.helperDisconnected()
        assertFalse(opener.status.value.active)
        assertFalse(opener.status.value.ownsOutputChange)
        assertTrue(opener.status.value.restorationPending)
        doReturn(service).`when`(connector).service
        // Simulate a fresh helper with no knowledge of the dead helper's mute lease.
        report(CallAudioStatus(voiceCallStreamMuted = true))
        opener.refreshStatus()
        assertTrue(opener.status.value.restorationPending)
        opener.retryRestoration()
        verify(service, never()).setLocalOutputMuted(false)
        report(CallAudioStatus(voiceCallStreamMuted = false))
        opener.refreshStatus()
        assertFalse(opener.status.value.restorationPending)
        handle.close()
    }

    @Test fun retryRestorationWaitsForTapClosureAndRestoresOnlyOwnedChanges() {
        val pending = CallAudioStatus(active = true, backend = "direct", muteRequested = true,
            outputMute = CallAudioStatus.APPLIED, ownsOutputChange = true, restorationPending = true,
            restoreDetail = "failed: restoration pending", voiceCallStreamMuted = true)
        report(pending)
        val opener = opener(mute = true)
        val handle = opener.open {}
        opener.retryRestoration()
        verify(service, never()).setLocalOutputMuted(false)
        report(pending.copy(active = false))
        handle.close()
        assertTrue(opener.status.value.restorationPending)
        doAnswer {
            report(CallAudioStatus(restoreDetail = "restored voice-call stream (requested)", voiceCallStreamMuted = false))
            "restored"
        }.`when`(service).setLocalOutputMuted(false)
        opener.retryRestoration()
        verify(service).setLocalOutputMuted(false)
        assertFalse(opener.status.value.restorationPending)
        assertFalse(opener.status.value.ownsOutputChange)
    }

    @Test fun knownIncompatibleDeviceBlocksDirectStreamMuteBeforeTheTapOpens() {
        val handle = opener(mute = true, directMuteBlocked = true).open {}
        val order = inOrder(service)
        order.verify(service).setDirectOutputMuteBlocked(true)
        order.verify(service).openCallAudio(AudioMethod.AUTO, 8000)
        order.verify(service).setLocalOutputMuted(true)
        handle.close()
        // Without a mute request there is nothing to block.
        opener(mute = false, directMuteBlocked = true).open {}.close()
        verify(service).setDirectOutputMuteBlocked(false)
    }

    @Test fun blockTransactionFailureDoesNotPreventTheTap() {
        doThrow(IllegalStateException("old helper")).`when`(service).setDirectOutputMuteBlocked(anyBoolean())
        val handle = opener(mute = true, directMuteBlocked = true).open {}
        assertSame(port, handle.port)
        handle.close()
    }

    @Test fun inCallMuteIncompatibilityIsReportedOnceAndKeepsTheTap() {
        var reported = 0
        report(CallAudioStatus(active = true, backend = "direct", muteRequested = true,
            outputMute = CallAudioStatus.APPLIED, ownsOutputChange = true, voiceCallStreamMuted = true))
        val opener = opener(mute = true, onIncompatible = { reported++ })
        val handle = opener.open {}
        val recovered = CallAudioStatus(active = true, backend = "direct", muteRequested = true,
            outputMute = CallAudioStatus.UNSUPPORTED, muteDetail = "unsupported: voice-call stream mute stopped direct capture",
            voiceCallStreamMuted = false, directMuteIncompatible = true,
            recoveryDetail = "re-created direct record after record read 0 at 8000 Hz with voice-call stream muted")
        report(recovered)
        opener.refreshStatus()
        opener.refreshStatus()
        assertEquals(1, reported)
        assertEquals(recovered.copy(available = true), opener.status.value)
        assertTrue(opener.status.value.active)
        verify(service, never()).closeCallAudio()
        handle.close()
    }
}
