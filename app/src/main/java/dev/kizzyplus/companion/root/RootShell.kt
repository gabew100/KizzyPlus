package dev.kizzyplus.companion.root

import android.os.Process
import android.os.SystemClock
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One long-lived `su` shell shared by the whole app. Polling through a single shell means
 * Magisk asks (and toasts) once instead of on every check, and each check costs one fork.
 */
object RootShell {
    class Result(val lines: List<String>, val exitCode: Int) {
        val text: String get() = lines.joinToString("\n")

        /** The shell itself failed (no root, denied, died or timed out), not just the command. */
        val shellFailed: Boolean get() = exitCode == NO_SHELL || exitCode == TIMED_OUT
    }

    const val NO_SHELL = -1
    const val TIMED_OUT = -2

    private const val EOF = "\u0000eof"
    private const val DENIED_COOLDOWN_MS = 5 * 60_000L

    private val lock = Any()
    private var process: java.lang.Process? = null
    private var stdin: OutputStream? = null
    private var queue: LinkedBlockingQueue<String>? = null
    private var fresh = false
    private var cooldownUntil = 0L

    // Magisk's default "inherit" mount namespace gives su the app's own namespace, where Android's
    // app data isolation hides every other app's /data/data folder. Ask for the global namespace,
    // and if the shell still isn't in init's namespace, run each command through nsenter.
    private var mountMaster = true
    private var namespaceChecked = false
    private var nsenterWrap = false

    /** null until a command has run. */
    @Volatile
    var granted: Boolean? = null
        private set

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Drops the cooldown that follows a denial, so the next command asks for root again. */
    fun retry() = synchronized(lock) {
        cooldownUntil = 0L
        granted = null
    }

    fun isRootAvailable(): Boolean = run("id -u").lines.firstOrNull()?.trim() == "0"

    fun run(command: String, timeoutMs: Long = 15_000): Result {
        val lines = ArrayList<String>()
        val code = stream(command, timeoutMs) { lines.add(it) }
        // stream() prints a newline before its marker; drop the blank line that leaves behind.
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return Result(lines, code)
    }

    /** Runs [command] and hands each output line to [onLine]. Returns the exit code or a negative shell error. */
    fun stream(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int = synchronized(lock) {
        if (!namespaceChecked || process?.isAlive != true) {
            val code = startAndCheckNamespace()
            if (code < 0) return code
        }
        val wrapped = if (nsenterWrap) "nsenter -t 1 -m -- /system/bin/sh -c ${quote(command)}" else command
        return send(wrapped, timeoutMs, onLine)
    }

    private fun startAndCheckNamespace(): Int {
        // Two tries: the second one runs plain `su` if the root manager rejected --mount-master.
        repeat(2) {
            if (!ensureStarted()) return NO_SHELL
            val probe = ArrayList<String>()
            val code = send(
                "echo \"init=\$(readlink /proc/1/ns/mnt)\"; echo \"self=\$(readlink /proc/self/ns/mnt)\"; " +
                    "command -v nsenter >/dev/null && echo nsenter=yes",
                15_000,
            ) { probe.add(it) }
            if (code == NO_SHELL && !mountMaster && granted != false) return@repeat
            if (code < 0) return code
            val init = probe.firstOrNull { it.startsWith("init=") }?.removePrefix("init=")
            val self = probe.firstOrNull { it.startsWith("self=") }?.removePrefix("self=")
            nsenterWrap = !init.isNullOrBlank() && init != self && probe.contains("nsenter=yes")
            namespaceChecked = true
            return 0
        }
        return NO_SHELL
    }

    private fun send(command: String, timeoutMs: Long, onLine: (String) -> Unit): Int {
        val q = queue ?: return NO_SHELL
        val out = stdin ?: return NO_SHELL
        val early = ArrayList<String>()
        val marker = "__kzp_done_${SystemClock.elapsedRealtimeNanos()}__"
        val script = "{ $command\n} </dev/null 2>/dev/null\n__kzp_rc=\$?\necho\necho \"$marker \$__kzp_rc\"\n"
        try {
            out.write(script.toByteArray())
            out.flush()
        } catch (e: IOException) {
            kill()
            return NO_SHELL
        }
        // The first command waits on the Magisk/KernelSU prompt, which can take a while.
        val limit = if (fresh) maxOf(timeoutMs, 60_000L) else timeoutMs
        val deadline = SystemClock.elapsedRealtime() + limit
        while (true) {
            val wait = deadline - SystemClock.elapsedRealtime()
            if (wait <= 0) {
                kill()
                return TIMED_OUT
            }
            val line = q.poll(wait, TimeUnit.MILLISECONDS) ?: continue
            if (line == EOF) {
                if (fresh) {
                    val said = early.joinToString(" ").lowercase()
                    if (mountMaster && REJECTED_FLAG.any { said.contains(it) }) {
                        mountMaster = false
                    } else {
                        markDenied()
                    }
                }
                kill()
                return NO_SHELL
            }
            if (line.startsWith(marker)) {
                fresh = false
                granted = true
                return line.substring(marker.length).trim().toIntOrNull() ?: NO_SHELL
            }
            if (fresh && early.size < 20) early.add(line)
            onLine(line)
        }
    }

    private val REJECTED_FLAG = listOf("mount-master", "unrecognized", "invalid option", "unknown option", "usage:")

    /** Reads a small file as root. */
    fun readBytes(path: String): ByteArray? {
        val r = run("base64 ${quote(path)}", 20_000)
        if (r.exitCode != 0 || r.lines.isEmpty()) return null
        return try {
            Base64.decode(r.lines.joinToString(""), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun readText(path: String): String? {
        val r = run("cat ${quote(path)}", 20_000)
        return if (r.exitCode == 0) r.text else null
    }

    /**
     * Copies a root-only file into this app's own storage. The copy is re-owned and relabelled
     * to match the destination folder; if that doesn't leave it readable, the bytes are piped
     * through the shell as base64 instead, so no root-created file is involved at all.
     */
    fun copyToPrivate(src: String, dst: File): Boolean {
        val dir = dst.parentFile ?: return false
        dir.mkdirs()
        dst.delete()
        val uid = Process.myUid()
        val s = quote(src)
        val d = quote(dst.absolutePath)
        val parent = quote(dir.absolutePath)
        val cmd = "if [ -f $s ]; then " +
            "ctx=\$(stat -c %C $parent 2>/dev/null); " +
            "[ -n \"\$ctx\" ] || ctx=\$(ls -Zd $parent | cut -d' ' -f1); " +
            "cat $s > $d && chown $uid:$uid $d && chmod 600 $d && { [ -z \"\$ctx\" ] || chcon \"\$ctx\" $d; }; " +
            "else false; fi"
        val r = run(cmd, 60_000)
        if (r.exitCode == 0 && isReadable(dst)) return true
        dst.delete()
        if (r.shellFailed) return false
        if (run("[ -f $s ]").exitCode != 0) return false
        return streamCopy(src, dst)
    }

    private fun streamCopy(src: String, dst: File): Boolean = try {
        val code = FileOutputStream(dst).use { out ->
            stream("base64 ${quote(src)}", 180_000) { line ->
                if (line.isNotEmpty()) out.write(Base64.decode(line, Base64.DEFAULT))
            }
        }
        (code == 0 && dst.length() > 0).also { if (!it) dst.delete() }
    } catch (e: Exception) {
        dst.delete()
        false
    }

    private fun isReadable(file: File): Boolean = try {
        // canRead() only checks permissions; SELinux denials show up when the file is opened.
        FileInputStream(file).use { it.read() } >= 0
    } catch (e: IOException) {
        false
    }

    private fun ensureStarted(): Boolean {
        if (process?.isAlive == true) return true
        kill()
        if (SystemClock.elapsedRealtime() < cooldownUntil) return false
        return try {
            val command = if (mountMaster) listOf("su", "--mount-master") else listOf("su")
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            val q = LinkedBlockingQueue<String>()
            Thread({
                try {
                    p.inputStream.bufferedReader().forEachLine { q.put(it) }
                } catch (_: IOException) {
                }
                q.put(EOF)
            }, "kizzyplus-root").apply { isDaemon = true }.start()
            process = p
            stdin = p.outputStream
            queue = q
            fresh = true
            true
        } catch (e: IOException) {
            markDenied()
            false
        }
    }

    private fun markDenied() {
        granted = false
        cooldownUntil = SystemClock.elapsedRealtime() + DENIED_COOLDOWN_MS
    }

    private fun kill() {
        try {
            stdin?.close()
        } catch (_: IOException) {
        }
        process?.destroy()
        process = null
        stdin = null
        queue = null
        fresh = false
        namespaceChecked = false
    }
}
