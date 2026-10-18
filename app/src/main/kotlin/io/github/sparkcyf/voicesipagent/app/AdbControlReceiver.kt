package io.github.sparkcyf.voicesipagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * adb-only control (protected by the DUMP permission). Examples:
 *   adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op status
 *   ... --es op provision --es server pbx.example.org --ei port 5060 --es user gateway --es password '***' [--es target gateway]
 *   ... --es op setup | sip-restart | loopback --ez on true | takeover --ez on false
 *   ... --es op earpiece-mute --ez on false
 *   ... --es op sip-stack --es stack pjsua2|builtin
 *   ... --es op provision --es server sip.example.org --ei port 5061 --es transport tls --es domain sip.example.org [--es srtp optional]
 *   ... --es op provision --ei audio_method 2      (0 auto, 1 redirection, 2 direct)
 *   ... --es op sip-debug --ez on true      (pjsip info-level logs; off by default)
 *
 * Every op replies once the runtime has processed it, e.g.
 *   Broadcast completed: result=1, data="ok: ready=true (Gateway ready) takeover=on sip=ready ..."
 * [RESULT_OK] = 1, [RESULT_ERROR] = 2 (bad op/argument), [RESULT_TIMEOUT] = 3 (still queued; see logcat).
 * result=0 with no data means the broadcast never reached this receiver (e.g. a misspelled component).
 * Replies never contain the SIP password.
 */
class AdbControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rt = GatewayRuntime.get(context)
        val op = intent.getStringExtra("op")
        val reply = Reply(goAsync())
        val describe = try {
            dispatch(rt, op, intent)
        } catch (e: IllegalArgumentException) {
            AppLog.w("adb control: ${e.message}")
            reply.send(RESULT_ERROR, "error: ${e.message}")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            reply.send(RESULT_TIMEOUT, "timeout: op '$op' still queued after ${TIMEOUT_MS / 1000} s; see logcat")
        }, TIMEOUT_MS)
        rt.afterQueued {
            val data = runCatching { describe() }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            reply.send(RESULT_OK, "ok: $data")
        }
    }

    /** Submits [op] and returns how to describe its outcome; throws IllegalArgumentException on bad input. */
    private fun dispatch(rt: GatewayRuntime, op: String?, intent: Intent): () -> String {
        val on = intent.getBooleanExtra("on", true)
        return when (op) {
            "status" -> {
                rt.afterQueued { rt.statusReport().lines().filter { it.isNotBlank() }.forEach { AppLog.i("status: $it") } }
                rt::statusLine
            }
            "setup" -> { rt.runSetup(); rt::statusLine }
            "sip-restart" -> { rt.restartSip(); rt::statusLine }
            "loopback" -> { rt.setLoopbackTest(on); rt::statusLine }
            "takeover" -> { rt.setTakeover(on); rt::statusLine }
            "earpiece-mute" -> { rt.setMuteEarpiece(on); rt::settingsLine }
            "sip-debug" -> { rt.setSipDebugLog(on); rt::settingsLine }
            "sip-stack" -> {
                val stack = requireNotNull(SipStack.of(intent.getStringExtra("stack"))) {
                    "sip-stack needs --es stack builtin|pjsua2"
                }
                rt.applySettings { it.copy(sipStack = stack) }
                rt::settingsLine
            }
            "provision" -> {
                validateProvision(intent)
                val transport = intent.getStringExtra("transport")?.let { t ->
                    requireNotNull(SipTransport.of(t)) { "transport must be udp|tcp|tls" }.id
                }
                val srtp = intent.getStringExtra("srtp")?.let { v ->
                    requireNotNull(SrtpPolicy.of(v)) { "srtp must be off|optional|mandatory" }.id
                }
                rt.applySettings { cur ->
                    cur.copy(
                        server = intent.getStringExtra("server") ?: cur.server,
                        port = intent.getIntExtra("port", cur.port),
                        sipTransport = transport ?: cur.sipTransport,
                        srtp = srtp ?: cur.srtp,
                        username = intent.getStringExtra("user") ?: cur.username,
                        password = intent.getStringExtra("password") ?: cur.password,
                        domain = intent.getStringExtra("domain") ?: cur.domain,
                        inboundTarget = intent.getStringExtra("target") ?: cur.inboundTarget,
                        allowedPrefixes = intent.getStringExtra("prefixes") ?: cur.allowedPrefixes,
                        audioMethod = intent.getIntExtra("audio_method", cur.audioMethod),
                        callAccess = intent.getIntExtra("call_access", cur.callAccess),
                    )
                }
                rt::settingsLine
            }
            null -> throw IllegalArgumentException("missing --es op")
            else -> throw IllegalArgumentException("unknown op '$op'")
        }
    }

    /** The runtime would otherwise drop an invalid edit silently on its own thread. */
    private fun validateProvision(intent: Intent) {
        fun intIn(key: String, range: IntRange) {
            if (!intent.hasExtra(key)) return
            // A non-int extra (e.g. --es) reads as the default.
            require(intent.getIntExtra(key, Int.MIN_VALUE) in range) { "$key must be an --ei integer in $range" }
        }
        intIn("port", 1..65535)
        intIn("audio_method", 0..2)
        intIn("call_access", 0..2)
    }

    /** Completes the pending broadcast exactly once, whichever of reply and timeout comes first. */
    private class Reply(private val pending: PendingResult) {
        private val done = AtomicBoolean()
        fun send(code: Int, data: String) {
            if (!done.compareAndSet(false, true)) return
            pending.setResult(code, data, null)
            pending.finish()
        }
    }

    companion object {
        const val RESULT_OK = 1
        const val RESULT_ERROR = 2
        const val RESULT_TIMEOUT = 3
        /** Well under the 10 s foreground-broadcast limit. */
        private const val TIMEOUT_MS = 5_000L
    }
}
