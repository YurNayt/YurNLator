package com.winlator.cmod.store.steam

import android.content.Context
import android.util.Log
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.types.KeyValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One game (or program) of the account, from Steam's app info (PICS). */
data class SteamApp(
    val id: Int,
    val name: String,
    val type: String,
    /** Folder name Steam uses under steamapps/common. */
    val installDir: String,
    /** Windows launch entries: exe path (inside the game folder, with "/") and arguments. */
    val launches: List<Launch>,
    /** Download size of the Windows depots (0 = unknown). */
    val sizeBytes: Long,
) {
    data class Launch(val exe: String, val args: String, val description: String)

    val header get() = "https://cdn.akamai.steamstatic.com/steam/apps/$id/header.jpg"
    val portrait get() = "https://cdn.akamai.steamstatic.com/steam/apps/$id/library_600x900.jpg"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("dir", installDir)
        .put("size", sizeBytes).put("launch", JSONArray().apply {
            launches.forEach { put(JSONObject().put("exe", it.exe).put("args", it.args).put("desc", it.description)) }
        })

    companion object {
        fun fromJson(o: JSONObject): SteamApp {
            val l = o.optJSONArray("launch") ?: JSONArray()
            return SteamApp(o.getInt("id"), o.optString("name"), o.optString("type"), o.optString("dir"),
                (0 until l.length()).map { l.getJSONObject(it) }.map { Launch(it.optString("exe"), it.optString("args"), it.optString("desc")) },
                o.optLong("size"))
        }
    }
}

/**
 * The account's games: licenses -> packages -> app ids -> app info (name, folder, launch entries,
 * size). Kept in files/steam/library.json so the list shows at once next time.
 */
object SteamLibrary {
    private const val TAG = "YurnSteamLib"
    private val KEEP_TYPES = setOf("game", "application", "demo")

    private val _apps = MutableStateFlow<List<SteamApp>?>(null)
    val apps: StateFlow<List<SteamApp>?> = _apps
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    @Volatile var lastError: String? = null
        private set

    private fun cacheFile(context: Context) = File(context.filesDir, "steam/library.json")

    fun get(id: Int): SteamApp? = _apps.value?.firstOrNull { it.id == id }

    /** The saved list (fast), if there is one and nothing is loaded yet. */
    fun loadCached(context: Context) {
        if (_apps.value != null) return
        runCatching {
            val a = JSONArray(cacheFile(context).readText())
            _apps.value = (0 until a.length()).map { SteamApp.fromJson(a.getJSONObject(it)) }
        }
    }

    fun clear(context: Context) {
        _apps.value = null
        cacheFile(context).delete()
    }

    /** Fetches the list from Steam (blocking; call off the main thread). */
    fun refresh(context: Context): Boolean {
        if (_loading.value) return false
        _loading.value = true
        try {
            if (!SteamSession.awaitOnline(context)) { lastError = "offline"; return false }
            val sa = SteamSession.apps ?: return false
            val licenses = SteamSession.licenses.value
            val appIds = LinkedHashSet<Int>()
            licenses.filter { it.packageID != 0 }.map { PICSRequest(it.packageID, it.accessToken) }.chunked(100).forEach { chunk ->
                val rs = sa.picsGetProductInfo(emptyList(), chunk).toFuture().get(90, TimeUnit.SECONDS)
                rs.results.forEach { cb ->
                    cb.packages.values.forEach { p ->
                        p.keyValues.get("appids").children.forEach { v -> v.asInteger(0).takeIf { it > 0 }?.let { appIds += it } }
                    }
                }
            }
            val tokens = HashMap<Int, Long>()
            appIds.toList().chunked(200).forEach { chunk ->
                runCatching { tokens.putAll(sa.picsGetAccessTokens(chunk, emptyList<Int>()).toFuture().get(60, TimeUnit.SECONDS).appTokens) }
            }
            val out = ArrayList<SteamApp>()
            appIds.toList().chunked(100).forEach { chunk ->
                val reqs = chunk.map { PICSRequest(it, tokens[it] ?: 0L) }
                val rs = sa.picsGetProductInfo(reqs, emptyList()).toFuture().get(90, TimeUnit.SECONDS)
                rs.results.forEach { cb -> cb.apps.values.forEach { info -> parse(info.id, info.keyValues)?.let { out += it } } }
            }
            val list = out.distinctBy { it.id }.sortedBy { it.name.lowercase(Locale.ROOT) }
            _apps.value = list
            lastError = null
            runCatching {
                cacheFile(context).apply { parentFile?.mkdirs() }.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "library refresh failed", t)
            lastError = t.message ?: t.javaClass.simpleName
            return false
        } finally {
            _loading.value = false
        }
    }

    /** One app's raw app info from Steam (blocking; null when offline). */
    fun appInfo(appId: Int): KeyValue? = runCatching {
        val sa = SteamSession.apps ?: return null
        val token = runCatching {
            sa.picsGetAccessTokens(listOf(appId), emptyList<Int>()).toFuture().get(30, TimeUnit.SECONDS).appTokens[appId]
        }.getOrNull() ?: 0L
        val rs = sa.picsGetProductInfo(listOf(PICSRequest(appId, token)), emptyList()).toFuture().get(30, TimeUnit.SECONDS)
        rs.results.firstNotNullOfOrNull { it.apps[appId]?.keyValues }
    }.getOrNull()

    private fun KeyValue.child(name: String): KeyValue = get(name)
    private fun KeyValue.str(name: String): String = get(name).value ?: ""

    private fun windowsOk(os: String) = os.isBlank() || os.lowercase(Locale.ROOT).contains("windows")

    private fun parse(id: Int, kv: KeyValue): SteamApp? {
        val common = kv.child("common")
        val name = common.str("name")
        val type = common.str("type").lowercase(Locale.ROOT)
        if (name.isBlank() || type !in KEEP_TYPES) return null
        if (!windowsOk(common.str("oslist"))) return null
        val config = kv.child("config")
        val launches = config.child("launch").children.mapNotNull { l ->
            val exe = l.str("executable").replace('\\', '/').trim()
            if (exe.isBlank() || !windowsOk(l.child("config").str("oslist"))) return@mapNotNull null
            val arch = l.child("config").str("osarch")
            SteamApp.Launch(exe, l.str("arguments"), l.str("description")) to (arch == "64")
        }.sortedByDescending { it.second }.map { it.first }
        var size = 0L
        kv.child("depots").children.forEach { d ->
            if (d.name?.toIntOrNull() == null) return@forEach
            val dc = d.child("config")
            if (!windowsOk(dc.str("oslist"))) return@forEach
            val lang = dc.str("language")
            if (lang.isNotBlank() && lang != "english") return@forEach
            if (d.str("dlcappid").isNotBlank() || d.str("sharedinstall") == "1") return@forEach
            val pub = d.child("manifests").child("public")
            size += pub.child("size").value?.toLongOrNull() ?: d.str("maxsize").toLongOrNull() ?: 0L
        }
        return SteamApp(id, name, type, config.str("installdir").ifBlank { name }, launches, size)
    }
}
