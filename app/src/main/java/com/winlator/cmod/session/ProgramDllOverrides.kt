package com.winlator.cmod.session

import android.util.Log
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.WineRegistryEditor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A game's own per-program DLL choices (advanced shortcut settings): for one exe of the game, a
 * DLL loads Wine's build, the Windows one, or none. Wine's AppDefaults\<exe>\DllOverrides, so a
 * launcher can differ from the game it starts. Stored as the shortcut's "dllOverrides" extra; what
 * was written last time is remembered so removed entries leave the registry too.
 */
object ProgramDllOverrides {
    const val KEY = "dllOverrides"
    private const val APPLIED = "dllOverridesApplied"

    /** Wine override value: "builtin", "native,builtin" or "" (disabled). */
    data class Entry(val exe: String, val dll: String, val mode: String)

    @JvmStatic
    fun read(shortcut: Shortcut?): List<Entry> = runCatching {
        val a = JSONArray(shortcut?.getExtra(KEY).orEmpty().ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val exe = o.optString("exe").trim(); val dll = o.optString("dll").trim().removeSuffix(".dll")
            if (exe.isEmpty() || dll.isEmpty()) null else Entry(exe, dll, o.optString("mode", "builtin"))
        }
    }.getOrDefault(emptyList())

    @JvmStatic
    fun save(shortcut: Shortcut, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("exe", it.exe).put("dll", it.dll).put("mode", it.mode)) }
        shortcut.putExtra(KEY, if (list.isEmpty()) null else a.toString())
        shortcut.saveData()
    }

    private fun key(exe: String) = "Software\\Wine\\AppDefaults\\$exe\\DllOverrides"

    /** Before Wine starts. */
    @JvmStatic
    fun beforeLaunch(container: Container?, shortcut: Shortcut?) {
        if (container == null || shortcut == null) return
        val now = read(shortcut)
        val before = shortcut.getExtra(APPLIED).split(';').filter { it.contains('|') }
        if (now.isEmpty() && before.isEmpty()) return
        runCatching {
            WineRegistryEditor(File(container.rootDir, ".wine/user.reg")).use { reg ->
                val keep = now.map { "${it.exe}|${it.dll}" }.toSet()
                before.filter { it !in keep }.forEach { reg.removeValue(key(it.substringBefore('|')), it.substringAfter('|')) }
                now.forEach { reg.setStringValue(key(it.exe), it.dll, it.mode) }
            }
            shortcut.putExtra(APPLIED, now.joinToString(";") { "${it.exe}|${it.dll}" }.ifEmpty { null })
            shortcut.saveData()
            if (now.isNotEmpty()) Log.i("ProgramDllOverrides", now.joinToString { "${it.exe}: ${it.dll}=${it.mode.ifEmpty { "disabled" }}" })
        }.onFailure { Log.w("ProgramDllOverrides", "registry", it) }
    }
}
