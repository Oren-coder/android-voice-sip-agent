package io.github.sparkcyf.voicesipagent.privileged.shell

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * Helpers for code running inside the Shizuku UserService process (uid 2000). Audio APIs check
 * the caller's attribution, so we present ourselves as com.android.shell rather than as the app
 * package whose code we are running.
 */
internal object ShellEnv {
    const val SHELL_UID = 2000
    const val SHELL_PACKAGE = "com.android.shell"

    private var operationPackagePrepared = false

    fun shellContext(fallback: Context): Context {
        val base = systemContext() ?: fallback
        return if (Build.VERSION.SDK_INT >= 31) ModernAudioContext.create(base) else base
    }

    fun hasMatchingDefaultAudioAttribution(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && ModernAudioContext.hasMatchingDefaultAttribution(context)

    fun prepareAudioAttribution(context: Context) {
        prepareOperationPackage()
        // API 31–33 normally has a record context setter only; the track uses process attribution.
        if (Build.VERSION.SDK_INT >= 31 &&
            (!AudioBuilderContext.supports(AudioRecord.Builder::class.java, Context::class.java) ||
                !AudioBuilderContext.supports(AudioTrack.Builder::class.java, Context::class.java))) {
            ModernAudioContext.prepareProcessAttribution(context)
        }
    }

    /** API 30 builders use ActivityThread.currentOpPackageName(), not an explicit context. */
    @Synchronized
    fun prepareOperationPackage() {
        if (Build.VERSION.SDK_INT != 30 || operationPackagePrepared) return
        val uid = Process.myUid()
        check(uid == SHELL_UID || uid == 0) { "unsupported helper uid $uid" }
        val at = Class.forName("android.app.ActivityThread")
        val application = at.getMethod("currentApplication").invoke(null) as? Application
        // Older Shizuku has no initial Application: playback already uses an empty package.
        if (application != null) {
            val expected = if (uid == SHELL_UID) SHELL_PACKAGE else ""
            val wrapper = object : ContextWrapper(application.baseContext) {
                override fun getOpPackageName() = expected
            }
            val field = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
            field.set(application, wrapper)
            check(at.getMethod("currentOpPackageName").invoke(null) == expected) {
                "API 30 operation package adapter did not take effect"
            }
        }
        operationPackagePrepared = true
    }

    fun hasPermission(permission: String, ctx: Context): Boolean =
        ctx.checkPermission(permission, Process.myPid(), Process.myUid()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun systemContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
            ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    } catch (t: Throwable) {
        null
    }

    fun audioManager(ctx: Context): AudioManager {
        val c = AudioManager::class.java.getDeclaredConstructor(Context::class.java)
        c.isAccessible = true
        return c.newInstance(ctx)
    }

    fun isPstnCallAudioInterceptable(): Boolean? = try {
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        val svc = Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, b)
        svc.javaClass.getMethod("isPstnCallAudioInterceptable").invoke(svc) as Boolean
    } catch (t: Throwable) {
        null
    }

    /**
     * Android 11 grants RECORD_AUDIO only to uids holding the while-in-use microphone capability,
     * which the cached shell uid lacks even with the op allowed. Showing a shell activity briefly
     * makes uid 2000 top; audioserver treats the shell uid as always active, so a record created
     * and started inside that window keeps running after the window is removed.
     */
    fun <T> withMicCapability(block: () -> T): T {
        if (Build.VERSION.SDK_INT != 30 || Process.myUid() != SHELL_UID || recordAllowed() != false) return block()
        run("am", "start", "-W", "-n", MIC_WINDOW)
        val deadline = SystemClock.elapsedRealtime() + MIC_WINDOW_WAIT_MS
        while (recordAllowed() == false && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        try {
            return block()
        } finally {
            removeMicWindow()
        }
    }

    private fun recordAllowed(): Boolean? = try {
        val ops = (systemContext() ?: return null).getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, SHELL_UID, SHELL_PACKAGE) == AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        null
    }

    /** `am stack list` prints "Stack id=N ..." followed by its tasks. */
    private fun removeMicWindow() {
        var stack: String? = null
        for (line in run("am", "stack", "list").out.lineSequence()) {
            val id = Regex("""^\s*Stack id=(\d+)""").find(line)?.groupValues?.get(1)
            if (id != null) stack = id
            if (stack != null && line.contains(MIC_WINDOW_COMPONENT)) run("am", "stack", "remove", stack)
        }
    }

    private const val MIC_WINDOW = "$SHELL_PACKAGE/.HeapDumpActivity"
    private const val MIC_WINDOW_COMPONENT = "$SHELL_PACKAGE/$SHELL_PACKAGE.HeapDumpActivity"
    private const val MIC_WINDOW_WAIT_MS = 2000L

    data class Result(val exit: Int, val out: String)

    /** Runs a fixed command (never a shell string) with a timeout. */
    fun run(vararg cmd: String, timeoutSec: Long = 10): Result {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(out) { out.appendLine(it) } } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Result(-1, "timeout")
        }
        reader.join(1000)
        return Result(p.exitValue(), synchronized(out) { out.toString() })
    }

}
