package com.winlator.cmod.store.epic

import android.content.Context
import android.util.Log
import com.winlator.cmod.store.StoreNet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One owned Epic game. */
data class EpicGame(
    val appName: String,
    val namespace: String,
    val catalogItemId: String,
    val title: String,
    val developer: String,
    /** Wide picture (DieselGameBox / Thumbnail), tall one (DieselGameBoxTall). */
    val wide: String?,
    val tall: String?,
) {
    fun toJson(): JSONObject = JSONObject().put("app", appName).put("ns", namespace).put("id", catalogItemId).put("title", title)
        .put("dev", developer).put("wide", wide ?: "").put("tall", tall ?: "")

    companion object {
        fun fromJson(o: JSONObject) = EpicGame(o.optString("app"), o.optString("ns"), o.optString("id"), o.optString("title"),
            o.optString("dev"), o.optString("wide").ifBlank { null }, o.optString("tall").ifBlank { null })
    }
}

/**
 * The account's Epic games: the library service (owned items, paged) plus the catalog (title,
 * pictures), DLCs and Unreal Engine assets left out. Kept in files/epic/library.json.
 * After Bannerlator's EpicApiClient (GPL-3.0).
 */
object EpicLibrary {
    private const val TAG = "YurnEpicLib"
    private const val LIBRARY = "https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true"
    private const val CATALOG = "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace"

    private val _games = MutableStateFlow<List<EpicGame>?>(null)
    val games: StateFlow<List<EpicGame>?> = _games
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    @Volatile var lastError: String? = null
        private set

    private fun cache(c: Context) = File(c.filesDir, "epic/library.json")

    fun loadCached(context: Context) {
        if (_games.value != null) return
        runCatching {
            val a = JSONArray(cache(context).readText())
            _games.value = (0 until a.length()).map { EpicGame.fromJson(a.getJSONObject(it)) }
        }
    }

    fun clear(context: Context) {
        _games.value = null
        cache(context).delete()
    }

    private fun get(url: String, token: String): Pair<Int, String?> = runCatching {
        StoreNet.client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("User-Agent", EpicAccount.USER_AGENT).build()).execute().use { r ->
            r.code to (if (r.isSuccessful) r.body?.string() else null)
        }
    }.getOrElse { -1 to null }

    /** Fetches the list from Epic (blocking). */
    fun refresh(context: Context): Boolean {
        if (_loading.value) return false
        _loading.value = true
        try {
            val token = EpicAccount.token(context) ?: run { lastError = "sign-in expired"; return false }
            data class Rec(val app: String, val ns: String, val id: String)
            val recs = ArrayList<Rec>()
            var cursor: String? = null
            do {
                val url = LIBRARY + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
                val (code, body) = get(url, token)
                if (body == null) { lastError = "HTTP $code"; return false }
                val j = JSONObject(body)
                val records = j.optJSONArray("records") ?: JSONArray()
                for (i in 0 until records.length()) {
                    val r = records.getJSONObject(i)
                    val app = r.optString("appName")
                    val ns = r.optString("namespace")
                    if (app.isEmpty() || app == "1" || ns == "ue" || ns == "89efe5924d3d467c839449ab6ab52e7f") continue
                    if (r.optString("sandboxType") == "PRIVATE") continue
                    val platforms = r.optJSONArray("platform")
                    if (platforms != null && platforms.length() > 0 &&
                        (0 until platforms.length()).none { platforms.optString(it) == "Windows" || platforms.optString(it) == "Win32" }) continue
                    recs += Rec(app, ns, r.optString("catalogItemId"))
                }
                val next = j.optJSONObject("responseMetadata")?.optString("nextCursor")
                cursor = if (next.isNullOrEmpty() || next == cursor) null else next
            } while (cursor != null)

            val pool = Executors.newFixedThreadPool(6)
            val out = try {
                recs.distinctBy { it.app }.map { rec ->
                    pool.submit(Callable {
                        val (_, body) = get("$CATALOG/${rec.ns}/bulk/items?id=${rec.id}&includeDLCDetails=true&includeMainGameDetails=true&country=US&locale=${Locale.getDefault().toLanguageTag()}", token)
                        val root = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                        val item = root?.optJSONObject(rec.id) ?: root?.keys()?.takeIf { it.hasNext() }?.let { root.optJSONObject(it.next()) }
                        if (item != null && item.has("mainGameItem")) return@Callable null // a DLC
                        var wide: String? = null
                        var tall: String? = null
                        item?.optJSONArray("keyImages")?.let { imgs ->
                            for (i in 0 until imgs.length()) {
                                val im = imgs.getJSONObject(i)
                                val u = im.optString("url").ifBlank { null } ?: continue
                                when (im.optString("type")) {
                                    "DieselGameBox" -> wide = u
                                    "DieselGameBoxTall" -> tall = u
                                    "Thumbnail", "OfferImageWide" -> if (wide == null) wide = u
                                }
                            }
                        }
                        EpicGame(rec.app, rec.ns, rec.id, item?.optString("title")?.ifBlank { null } ?: rec.app,
                            item?.optString("developer") ?: "", wide, tall)
                    })
                }.mapNotNull { f -> runCatching { f.get(60, TimeUnit.SECONDS) }.getOrNull() }
            } finally {
                pool.shutdownNow()
            }
            val list = out.sortedBy { it.title.lowercase(Locale.ROOT) }
            _games.value = list
            lastError = null
            runCatching { cache(context).apply { parentFile?.mkdirs() }.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()) }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "library", t)
            lastError = t.message ?: t.javaClass.simpleName
            return false
        } finally {
            _loading.value = false
        }
    }
}
