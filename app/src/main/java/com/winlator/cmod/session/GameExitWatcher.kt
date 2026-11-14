package com.winlator.cmod.session

import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Ends the session when the game is closed (from the task manager or by itself): Wine keeps its
 * desktop (explorer) running after the game's process exits, so the container stayed up and had
 * to be closed by hand, in the benchmark too.
 *
 * Every 2 s it lists the session's Windows programs from /proc (Wine's processes run as this app's
 * user; their command line carries the .exe). Once the shortcut's own .exe has run, the session
 * ends when no program other than Wine's own (explorer, services, winedevice, …, anything in
 * C:\windows) has been left for [GRACE_POLLS] polls in a row. A launcher that starts the real game
 * and quits keeps the session alive while the game runs; a game restarting itself has the grace
 * period. A shortcut whose .exe never shows up (a .lnk, another name) never ends anything.
 */
class GameExitWatcher(private val exeName: String, private val onGameGone: () -> Unit) {
    @Volatile private var thread: Thread? = null

    fun start() {
        if (thread != null || exeName.isBlank()) return
        thread = Thread({ run() }, "GameExitWatcher").apply { isDaemon = true; start() }
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val target = exeName.lowercase()
        val begin = System.currentTimeMillis()
        var seen = false
        var emptyPolls = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(POLL_MS)
                val programs = programs()
                if (!seen) {
                    if (target in programs) {
                        seen = true
                        Log.i(TAG, "game running: $exeName")
                    } else if (System.currentTimeMillis() - begin > FIND_LIMIT_MS) {
                        Log.i(TAG, "$exeName never showed up: not watching")
                        return
                    }
                    continue
                }
                emptyPolls = if (programs.isEmpty()) emptyPolls + 1 else 0
                if (emptyPolls >= GRACE_POLLS) {
                    Log.i(TAG, "$exeName and its programs closed: ending the session")
                    onGameGone()
                    return
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    /** Lower-case .exe names of the session's programs, Wine's own left out. */
    private fun programs(): Set<String> {
        val out = HashSet<String>()
        val uid = Process.myUid()
        val self = Process.myPid()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            if (pid == self) return@forEach
            val exe = runCatching {
                if (Os.stat("/proc/$pid").st_uid != uid) return@forEach
                windowsExe(File("/proc/$pid/cmdline").readBytes())
            }.getOrNull() ?: return@forEach
            val lower = exe.lowercase()
            if (lower.contains("\\windows\\") || lower.contains("/windows/")) return@forEach
            val base = lower.substringAfterLast('\\').substringAfterLast('/')
            if (base in SYSTEM) return@forEach
            out += base
        }
        return out
    }

    /**
     * The first .exe path of the command line ("C:\Games\TR\TombRaider.exe"), or null. Wine may
     * put the whole Windows command line in one argument ("…\TombRaider.exe -benchmark"), so the
     * path runs from the argument's start (or a quote) to the first ".exe".
     */
    private fun windowsExe(cmdline: ByteArray): String? {
        if (cmdline.isEmpty()) return null
        val s = String(cmdline, Charsets.UTF_8)
        var from = 0
        while (true) {
            val i = s.indexOf(".exe", from, ignoreCase = true)
            if (i < 0) return null
            val end = i + 4
            val next = s.getOrNull(end)
            if (next == null || next == '\u0000' || next == ' ' || next == '"') {
                val start = maxOf(s.lastIndexOf('\u0000', i), s.lastIndexOf('"', i)) + 1
                return s.substring(start, end).trim()
            }
            from = end
        }
    }

    companion object {
        private const val TAG = "GameExitWatcher"
        private const val POLL_MS = 2000L
        private const val GRACE_POLLS = 3
        /** A game that takes longer than this to start (installers, first-run setup) isn't watched. */
        private const val FIND_LIMIT_MS = 10 * 60 * 1000L

        /** Wine's and the emulator's own programs: they stay after the game closes. */
        private val SYSTEM = setOf(
            "explorer.exe", "winhandler.exe", "wfm.exe", "services.exe", "winedevice.exe", "plugplay.exe",
            "svchost.exe", "rpcss.exe", "tabtip.exe", "conhost.exe", "wineboot.exe", "start.exe",
            "winemenubuilder.exe", "rundll32.exe", "cmd.exe", "winedbg.exe", "mscorsvw.exe", "ngen.exe",
            "taskmgr.exe", "regedit.exe", "notepad.exe", "control.exe", "wineconsole.exe", "msiexec.exe",
        )

        /** The .exe file name of [shortcutPath] ("C:\Games\TR\TombRaider.exe" -> "TombRaider.exe"); "" for a .lnk. */
        @JvmStatic
        fun exeOf(shortcutPath: String?): String {
            val clean = shortcutPath?.replace("\"", "")?.trim() ?: return ""
            var name = clean.substringAfterLast('\\').substringAfterLast('/')
            val dot = name.lowercase().indexOf(".exe")
            if (dot < 0) return ""
            name = name.substring(0, dot + 4)
            return name
        }
    }
}
