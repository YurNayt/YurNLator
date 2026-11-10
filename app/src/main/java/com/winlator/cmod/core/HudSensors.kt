package com.winlator.cmod.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Precise HUD sensors: Android keeps apps away from /proc/stat, the GPU's kgsl / Mali files and
 * most thermal zones, so without help the HUD estimates CPU load from clocks and MangoHud shows
 * nothing. With root (`su`) or ADB rights (through Shizuku, the shell user) a small shell reads
 * them for us once a second.
 */
object HudSensorAccess {
    const val OFF = "off"
    const val ROOT = "root"
    const val ADB = "adb"
    private const val KEY = "hud_sensor_access"
    private const val TAG = "HudSensors"

    fun mode(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, OFF) ?: OFF

    fun setMode(context: Context, mode: String) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, mode).apply()

    /** Shizuku: 0 not running / not installed, 1 running without our permission, 2 ready. */
    fun shizukuState(): Int = runCatching {
        if (!rikka.shizuku.Shizuku.pingBinder()) 0
        else if (rikka.shizuku.Shizuku.isPreV11()) 0
        else if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 2
        else 1
    }.getOrDefault(0)

    fun requestShizuku(onResult: (Boolean) -> Unit) {
        runCatching {
            val code = 7311
            val listener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != code) return
                    rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
            rikka.shizuku.Shizuku.requestPermission(code)
        }.onFailure { onResult(false) }
    }

    /** A shell with the chosen rights, or null. Blocking (the root manager may ask): off the main thread. */
    fun openShell(context: Context): Process? = when (mode(context)) {
        ROOT -> runCatching { ProcessBuilder("su").redirectErrorStream(true).start() }.getOrNull()
        ADB -> if (shizukuState() == 2) runCatching {
            // Shizuku's own process API (public until v13 made it private; still the documented way to run sh).
            val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            m.invoke(null, arrayOf("sh"), null, null) as Process
        }.onFailure { Log.w(TAG, "shizuku shell", it) }.getOrNull() else null
        else -> null
    }
}

/** One reading. -1 / 0 = not known. */
data class HudSensorSample(
    val cpuLoad: Int = -1, val cpuMhz: Int = 0, val cpuTemp: Int = -1,
    val gpuLoad: Int = -1, val gpuMhz: Int = 0, val gpuTemp: Int = -1,
) {
    fun cpuText(): String = parts(cpuLoad, cpuMhz, cpuTemp)
    fun gpuText(): String = parts(gpuLoad, gpuMhz, gpuTemp)

    private fun parts(load: Int, mhz: Int, temp: Int): String {
        val s = ArrayList<String>(3)
        if (load >= 0) s += "$load%"
        if (mhz > 0) s += if (mhz >= 1000) String.format(Locale.US, "%.2f GHz", mhz / 1000f) else "$mhz MHz"
        if (temp > 0) s += "$temp°C"
        return if (s.isEmpty()) "-" else s.joinToString("  ")
    }
}

/**
 * The feed the HUDs read: started with the game session when precise sensors are on. Each second
 * it reads everything in one shell round trip, keeps [latest] for our HUD and writes cpu / gpu
 * text files that MangoHud shows through its `exec` rows.
 */
object HudSensorFeed {
    private const val TAG = "HudSensors"
    @Volatile var latest: HudSensorSample? = null
        private set
    @Volatile private var thread: Thread? = null
    @Volatile private var mirrorDir: File? = null

    fun mirrorDir(imageFsRoot: File) = File(imageFsRoot, "usr/etc/mangohud/sensors")

    val running: Boolean get() = thread != null

    @Synchronized
    fun start(context: Context, imageFsRoot: File?) {
        if (thread != null || HudSensorAccess.mode(context) == HudSensorAccess.OFF) return
        val app = context.applicationContext
        mirrorDir = imageFsRoot?.let { mirrorDir(it).apply { mkdirs() } }
        mirrorDir?.let { dir -> write(File(dir, "cpu"), "-"); write(File(dir, "gpu"), "-") }
        thread = Thread({ loop(app) }, "HudSensors").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        latest = null
    }

    /** One reading now (Settings' test). Blocking. */
    fun probe(context: Context): HudSensorSample? {
        val shell = Shell.open(context) ?: return null
        return try {
            val r = Reader(shell)
            r.sample(); Thread.sleep(500); r.sample()
        } finally { shell.close() }
    }

    private fun loop(context: Context) {
        val shell = Shell.open(context)
        if (shell == null) { Log.w(TAG, "no shell for mode " + HudSensorAccess.mode(context)); thread = null; return }
        try {
            val reader = Reader(shell)
            while (!Thread.currentThread().isInterrupted && thread === Thread.currentThread()) {
                val s = reader.sample() ?: break
                latest = s
                mirrorDir?.let { dir ->
                    write(File(dir, "cpu"), s.cpuText())
                    write(File(dir, "gpu"), s.gpuText())
                }
                Thread.sleep(1000)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "feed", e)
        } finally {
            shell.close()
            if (thread === Thread.currentThread()) thread = null
        }
    }

    private fun write(f: File, text: String) = runCatching {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text + "\n")
        tmp.renameTo(f)
    }

    /** A privileged sh kept open; each request is one script ended by a marker line. */
    private class Shell(private val proc: Process) {
        private val out = OutputStreamWriter(proc.outputStream)
        private val input = BufferedReader(InputStreamReader(proc.inputStream))

        fun run(script: String): List<String>? = runCatching {
            out.write(script); out.write("\necho @@END\n"); out.flush()
            val lines = ArrayList<String>()
            while (true) {
                val l = input.readLine() ?: return@runCatching null
                if (l == "@@END") break
                lines += l
            }
            lines
        }.getOrNull()

        fun close() = runCatching { out.write("exit\n"); out.flush(); proc.waitFor(500, TimeUnit.MILLISECONDS); proc.destroy() }

        companion object {
            fun open(context: Context): Shell? = HudSensorAccess.openShell(context)?.let { Shell(it) }
        }
    }

    /** Turns raw files into a sample; finds the thermal zones once. */
    private class Reader(private val shell: Shell) {
        private var cpuZones: List<Int>? = null
        private var gpuZones: List<Int> = emptyList()
        private var prevTotal = -1L
        private var prevIdle = -1L

        private fun discover() {
            val lines = shell.run("for z in /sys/class/thermal/thermal_zone*; do echo \"\${z##*thermal_zone} \$(cat \$z/type 2>/dev/null)\"; done") ?: emptyList()
            val cpu = ArrayList<Int>(); val gpu = ArrayList<Int>()
            for (l in lines) {
                val sp = l.trim().split(Regex("\\s+"), 2)
                val n = sp.getOrNull(0)?.toIntOrNull() ?: continue
                val type = sp.getOrNull(1)?.lowercase(Locale.US) ?: continue
                when {
                    type.contains("gpu") -> gpu += n
                    type.contains("cpu") || type.startsWith("mtktscpu") || type.contains("cluster") || type.contains("soc_max") -> cpu += n
                }
            }
            cpuZones = cpu; gpuZones = gpu
        }

        fun sample(): HudSensorSample? {
            if (cpuZones == null) discover()
            val k = "/sys/class/kgsl/kgsl-3d0"
            val script = buildString {
                append("echo \"S \$(head -n 1 /proc/stat)\"\n")
                append("for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq; do echo \"F \$(cat \$f 2>/dev/null)\"; done\n")
                cpuZones?.forEach { append("echo \"TC \$(cat /sys/class/thermal/thermal_zone$it/temp 2>/dev/null)\"\n") }
                gpuZones.forEach { append("echo \"TG \$(cat /sys/class/thermal/thermal_zone$it/temp 2>/dev/null)\"\n") }
                // GPU load: Adreno, then Mali (Pixel / Exynos), MediaTek
                append("echo \"GB \$(cat $k/gpu_busy_percentage 2>/dev/null || cat /sys/kernel/gpu/gpu_busy 2>/dev/null || cat /sys/kernel/ged/hal/gpu_utilization 2>/dev/null || cat /sys/class/misc/mali0/device/utilization 2>/dev/null)\"\n")
                // GPU clock: Adreno MHz, Adreno Hz, Mali MHz, MediaTek kHz
                append("echo \"GM \$(cat $k/clock_mhz 2>/dev/null)\"\n")
                append("echo \"GH \$(cat $k/gpuclk 2>/dev/null)\"\n")
                append("echo \"GL \$(cat /sys/kernel/gpu/gpu_clock 2>/dev/null)\"\n")
                append("echo \"GK \$(cat /sys/kernel/ged/hal/current_freqency 2>/dev/null)\"\n")
                append("echo \"GT \$(cat $k/temp 2>/dev/null)\"\n")
            }
            val lines = shell.run(script) ?: return null
            var cpuLoad = -1; var cpuMhz = 0; var cpuTemp = -1
            var gpuLoad = -1; var gpuMhz = 0; var gpuTemp = -1; var zoneGpuTemp = -1
            for (l in lines) {
                val sp = l.trim().split(Regex("\\s+"))
                val v = sp.drop(1)
                when (sp[0]) {
                    "S" -> cpuLoad = cpuLoad(v)
                    "F" -> v.firstOrNull()?.toIntOrNull()?.let { cpuMhz = maxOf(cpuMhz, it / 1000) }
                    "TC" -> celsius(v.firstOrNull())?.let { cpuTemp = maxOf(cpuTemp, it) }
                    "TG" -> celsius(v.firstOrNull())?.let { zoneGpuTemp = maxOf(zoneGpuTemp, it) }
                    "GB" -> if (gpuLoad < 0) gpuLoad = gpuBusy(v)
                    "GM" -> v.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }?.let { gpuMhz = it }
                    "GH" -> if (gpuMhz == 0) v.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 }?.let { gpuMhz = (it / 1_000_000).toInt() }
                    "GL" -> if (gpuMhz == 0) v.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }?.let { gpuMhz = it }
                    "GK" -> if (gpuMhz == 0) v.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { gpuMhz = it / 1000 }
                    "GT" -> celsius(v.firstOrNull())?.let { gpuTemp = it }
                }
            }
            if (gpuTemp < 0) gpuTemp = zoneGpuTemp
            return HudSensorSample(cpuLoad, cpuMhz, cpuTemp, gpuLoad, gpuMhz, gpuTemp)
        }

        private fun cpuLoad(v: List<String>): Int {
            if (v.size < 5 || v[0] != "cpu") return -1
            val n = v.drop(1).mapNotNull { it.toLongOrNull() }
            if (n.size < 4) return -1
            val total = n.sum()
            val idle = n[3] + (n.getOrNull(4) ?: 0L)
            val pt = prevTotal; val pi = prevIdle
            prevTotal = total; prevIdle = idle
            if (pt < 0 || total <= pt) return -1
            return (((total - pt) - (idle - pi)).coerceAtLeast(0) * 100 / (total - pt)).toInt().coerceIn(0, 100)
        }

        /** "45 %", "45", MediaTek "45 0 0", Adreno gpubusy pairs aren't used. */
        private fun gpuBusy(v: List<String>): Int =
            v.firstOrNull()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()?.coerceIn(0, 100) ?: -1

        private fun celsius(raw: String?): Int? {
            val n = raw?.trim()?.toIntOrNull() ?: return null
            val c = if (n > 1000) (n + 500) / 1000 else n
            return c.takeIf { it in 1..150 }
        }
    }
}
