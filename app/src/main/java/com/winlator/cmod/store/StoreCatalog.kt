package com.winlator.cmod.store

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A game in a store's catalog (search result or showcase). */
data class StoreItem(
    val store: Store,
    val id: String,
    val title: String,
    /** Wide picture (capsule / header). */
    val image: String?,
    /** "1 299 ₽", "Free", "" (unknown). */
    val price: String,
    /** The product page on the store's site. */
    val url: String,
    val discountPercent: Int = 0,
)

data class StoreDetails(
    val description: String,
    val header: String?,
    val developer: String,
    val publisher: String,
    val releaseDate: String,
    val genres: List<String>,
    val windows: Boolean = true,
)

enum class Store(val title: String) { STEAM("Steam"), EPIC("Epic Games Store") }

/** HTTP for the stores' public catalog endpoints (a browser User-Agent: Epic refuses others). */
internal object StoreNet {
    private const val TAG = "StoreNet"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    }

    fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else { Log.w(TAG, "GET $url -> ${r.code}"); null }
        }
    }.onFailure { Log.w(TAG, "GET $url", it) }.getOrNull()

    fun postJson(url: String, json: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json")
            .post(json.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else { Log.w(TAG, "POST $url -> ${r.code}"); null }
        }
    }.onFailure { Log.w(TAG, "POST $url", it) }.getOrNull()
}

private fun country(): String = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"
private fun steamLang(): String = if (Locale.getDefault().language == "ru") "russian" else "english"
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/**
 * Steam's public store API (no login): showcase (featured categories), search, app details.
 * Endpoints as Bannerlator's SteamStoreSearch uses them (GPL-3.0).
 */
object SteamCatalog {
    private val cache = ConcurrentHashMap<String, Pair<Long, Any?>>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, ttlMs: Long, load: () -> T): T {
        cache[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < ttlMs) return v as T }
        return load().also { cache[key] = System.currentTimeMillis() to it }
    }

    private fun price(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("final_formatted").takeIf { it.isNotBlank() }?.let { return it }
        val cents = o.optInt("final", -1)
        if (cents < 0) return ""
        if (cents == 0) return "Free"
        return String.format(Locale.US, "%.2f %s", cents / 100.0, o.optString("currency"))
    }

    fun storeUrl(appId: String) = "https://store.steampowered.com/app/$appId/"
    fun header(appId: String) = "https://cdn.akamai.steamstatic.com/steam/apps/$appId/header.jpg"

    /** Search by name. */
    fun search(term: String): List<StoreItem> = cached("s:$term", 5 * 60_000L) {
        val body = StoreNet.get("https://store.steampowered.com/api/storesearch/?term=${enc(term)}&l=${steamLang()}&cc=${country()}")
            ?: return@cached emptyList()
        runCatching {
            val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
            (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("type") != "app") return@mapNotNull null
                val id = o.optLong("id").toString()
                StoreItem(Store.STEAM, id, o.optString("name"), header(id), price(o.optJSONObject("price")), storeUrl(id))
            }
        }.getOrDefault(emptyList())
    }

    /** Showcase: specials, top sellers, new releases (section title -> items). */
    fun featured(): List<Pair<String, List<StoreItem>>> = cached("featured", 30 * 60_000L) {
        val body = StoreNet.get("https://store.steampowered.com/api/featuredcategories?l=${steamLang()}&cc=${country()}")
            ?: return@cached emptyList()
        runCatching {
            val root = JSONObject(body)
            listOf("specials", "top_sellers", "new_releases").mapNotNull { key ->
                val sec = root.optJSONObject(key) ?: return@mapNotNull null
                val arr = sec.optJSONArray("items") ?: return@mapNotNull null
                val items = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optLong("id").takeIf { it > 0 }?.toString() ?: return@mapNotNull null
                    val cents = o.optInt("final_price", -1)
                    val p = when {
                        cents == 0 -> "Free"
                        cents > 0 -> String.format(Locale.US, "%.2f %s", cents / 100.0, o.optString("currency"))
                        else -> ""
                    }
                    StoreItem(Store.STEAM, id, o.optString("name"), o.optString("large_capsule_image").ifBlank { header(id) },
                        p, storeUrl(id), o.optInt("discount_percent"))
                }.distinctBy { it.id }
                if (items.isEmpty()) null else key to items
            }
        }.getOrDefault(emptyList())
    }

    fun details(appId: String): StoreDetails? = cached("d:$appId", 60 * 60_000L) {
        val body = StoreNet.get("https://store.steampowered.com/api/appdetails?appids=$appId&l=${steamLang()}&cc=${country()}")
            ?: return@cached null
        runCatching {
            val data = JSONObject(body).optJSONObject(appId)?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
                ?: return@runCatching null
            val genres = data.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optJSONObject(it).optString("description") } }.orEmpty()
            StoreDetails(
                description = data.optString("short_description").replace(Regex("<[^>]+>"), "").trim(),
                header = data.optString("header_image").ifBlank { null },
                developer = data.optJSONArray("developers")?.optString(0).orEmpty(),
                publisher = data.optJSONArray("publishers")?.optString(0).orEmpty(),
                releaseDate = data.optJSONObject("release_date")?.optString("date").orEmpty(),
                genres = genres,
                windows = data.optJSONObject("platforms")?.optBoolean("windows", true) ?: true,
            )
        }.getOrNull()
    }
}

/**
 * The Epic Games Store catalog through the store's own GraphQL endpoint (store.epicgames.com, with
 * a browser User-Agent) and the static free-games feed: as Bannerlator's EpicStoreCatalog (GPL-3.0).
 */
object EpicCatalog {
    private const val GRAPHQL = "https://store.epicgames.com/graphql"
    private const val PROMOS = "https://store-site-backend-static-ipv4.ak.epicgames.com/freeGamesPromotions"
    private val cache = ConcurrentHashMap<String, Pair<Long, Any?>>()
    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String, ttlMs: Long, load: () -> T): T {
        cache[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < ttlMs) return v as T }
        return load().also { cache[key] = System.currentTimeMillis() to it }
    }

    private const val FIELDS =
        "title id namespace description productSlug urlSlug keyImages{type url} seller{name} " +
            "catalogNs{mappings(pageType:\"productHome\"){pageSlug pageType}} " +
            "price(country:\$country){totalPrice{discountPrice originalPrice discount currencyCode fmtPrice(locale:\$locale){originalPrice discountPrice}}}"

    private fun locale() = if (Locale.getDefault().language == "ru") "ru" else "en-US"

    private fun parse(arr: JSONArray?): List<StoreItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").ifBlank { return@mapNotNull null }
            val images = o.optJSONArray("keyImages")
            fun img(vararg types: String): String? {
                if (images == null) return null
                for (t in types) for (k in 0 until images.length()) {
                    val im = images.optJSONObject(k) ?: continue
                    if (im.optString("type") == t) return im.optString("url")
                }
                return images.optJSONObject(0)?.optString("url")
            }
            val total = o.optJSONObject("price")?.optJSONObject("totalPrice")
            val fmt = total?.optJSONObject("fmtPrice")
            val discountPrice = total?.optInt("discountPrice", -1) ?: -1
            val original = total?.optInt("originalPrice", 0) ?: 0
            val price = when {
                total == null -> ""
                discountPrice == 0 -> "Free"
                else -> fmt?.optString("discountPrice").orEmpty()
            }
            val discount = if (original > 0 && discountPrice in 0 until original) ((original - discountPrice) * 100 / original) else 0
            val slug = o.optJSONObject("catalogNs")?.optJSONArray("mappings")?.optJSONObject(0)?.optString("pageSlug")
                ?.ifBlank { null } ?: o.optString("productSlug").ifBlank { null }
            val url = if (slug != null) "https://store.epicgames.com/p/${slug.substringBefore('/')}"
                else "https://store.epicgames.com/browse?q=${enc(title)}"
            StoreItem(Store.EPIC, o.optString("id"), title, img("OfferImageWide", "DieselStoreFrontWide", "Thumbnail"),
                price, url, discount)
        }
    }

    fun search(term: String): List<StoreItem> = cached("s:$term", 5 * 60_000L) {
        val query = "query q(\$count:Int,\$country:String!,\$locale:String,\$keywords:String){Catalog{searchStore(" +
            "category:\"games/edition/base\",count:\$count,country:\$country,locale:\$locale,keywords:\$keywords," +
            "sortBy:\"relevancy\",sortDir:\"DESC\",allowCountries:\$country){elements{$FIELDS}}}}"
        val vars = JSONObject().put("count", 30).put("country", country()).put("locale", locale()).put("keywords", term)
        val body = StoreNet.postJson(GRAPHQL, JSONObject().put("query", query).put("variables", vars).toString())
            ?: return@cached emptyList()
        runCatching {
            parse(JSONObject(body).optJSONObject("data")?.optJSONObject("Catalog")?.optJSONObject("searchStore")?.optJSONArray("elements"))
        }.getOrDefault(emptyList())
    }

    /** Showcase: free this week (static feed). */
    fun featured(): List<Pair<String, List<StoreItem>>> = cached("featured", 30 * 60_000L) {
        val body = StoreNet.get("$PROMOS?locale=${locale()}&country=${country()}&allowCountries=${country()}")
            ?: return@cached emptyList()
        runCatching {
            val els = JSONObject(body).optJSONObject("data")?.optJSONObject("Catalog")?.optJSONObject("searchStore")?.optJSONArray("elements")
            val now = Date()
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            fun activeFree(o: JSONObject): Boolean {
                val offers = o.optJSONObject("promotions")?.optJSONArray("promotionalOffers") ?: return false
                for (i in 0 until offers.length()) {
                    val list = offers.optJSONObject(i)?.optJSONArray("promotionalOffers") ?: continue
                    for (k in 0 until list.length()) {
                        val p = list.optJSONObject(k) ?: continue
                        val start = runCatching { iso.parse(p.optString("startDate").take(19)) }.getOrNull()
                        val end = runCatching { iso.parse(p.optString("endDate").take(19)) }.getOrNull()
                        val pct = p.optJSONObject("discountSetting")?.optInt("discountPercentage", -1) ?: -1
                        if (pct == 0 && start != null && end != null && now.after(start) && now.before(end)) return true
                    }
                }
                return false
            }
            val free = ArrayList<StoreItem>()
            if (els != null) for (i in 0 until els.length()) {
                val o = els.optJSONObject(i) ?: continue
                if (!activeFree(o)) continue
                parse(JSONArray().put(o)).firstOrNull()?.let { free += it.copy(price = "Free") }
            }
            if (free.isEmpty()) emptyList() else listOf("free_now" to free)
        }.getOrDefault(emptyList())
    }
}
