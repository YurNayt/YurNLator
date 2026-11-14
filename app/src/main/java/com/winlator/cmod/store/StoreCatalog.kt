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
    /** Short text when the list already has it (Epic). */
    val description: String = "",
)

/** A genre / tag of a store, for browsing. */
data class StoreCategory(val id: String, val name: String)

/** How a category's games are listed. */
enum class CategorySort { POPULAR, SPECIALS, NEW }

data class StoreDetails(
    val description: String,
    val header: String?,
    val developer: String,
    val publisher: String,
    val releaseDate: String,
    val genres: List<String>,
    val windows: Boolean = true,
    val screenshots: List<String> = emptyList(),
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
            listOf("specials", "top_sellers", "new_releases", "coming_soon").mapNotNull { key ->
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

    /** Main genres / tags as tag id -> (English, Russian) name; the store's own names replace them when it answers. */
    private val TAGS = linkedMapOf(
        "19" to ("Action" to "Экшен"), "21" to ("Adventure" to "Приключения"), "122" to ("RPG" to "Ролевые игры"),
        "9" to ("Strategy" to "Стратегии"), "599" to ("Simulation" to "Симуляторы"), "1774" to ("Shooter" to "Шутеры"),
        "1695" to ("Open World" to "Открытый мир"), "1667" to ("Horror" to "Хоррор"), "1662" to ("Survival" to "Выживание"),
        "3859" to ("Multiplayer" to "Мультиплеер"), "1685" to ("Co-op" to "Кооператив"), "492" to ("Indie" to "Инди"),
        "597" to ("Casual" to "Казуальные"), "701" to ("Sports" to "Спорт"), "699" to ("Racing" to "Гонки"),
        "1664" to ("Puzzle" to "Головоломки"), "1625" to ("Platformer" to "Платформеры"), "1743" to ("Fighting" to "Файтинги"),
        "1742" to ("Story Rich" to "Сюжетные"), "3810" to ("Sandbox" to "Песочницы"), "4085" to ("Anime" to "Аниме"),
        "113" to ("Free to Play" to "Бесплатные"),
    )

    fun categories(): List<StoreCategory> = cached("tags", 24 * 3600_000L) {
        val names = HashMap<String, String>()
        StoreNet.get("https://store.steampowered.com/tagdata/populartags/${steamLang()}")?.let { body ->
            runCatching {
                val a = JSONArray(body)
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { names[it.optLong("tagid").toString()] = it.optString("name") }
            }
        }
        val ru = Locale.getDefault().language == "ru"
        TAGS.map { (id, n) -> StoreCategory(id, names[id]?.ifBlank { null } ?: if (ru) n.second else n.first) }
    }

    /** One page (25) of a tag's games, from the store's search (its HTML rows). */
    fun browse(tag: String, sort: CategorySort, start: Int): List<StoreItem> = cached("b:$tag:$sort:$start", 15 * 60_000L) {
        val extra = when (sort) {
            CategorySort.POPULAR -> "&filter=topsellers"
            CategorySort.SPECIALS -> "&specials=1"
            CategorySort.NEW -> "&sort_by=Released_DESC"
        }
        val body = StoreNet.get("https://store.steampowered.com/search/results/?query&start=$start&count=25&infinite=1" +
            "&category1=998&tags=$tag$extra&cc=${country()}&l=${steamLang()}") ?: return@cached emptyList()
        runCatching { parseSearchHtml(JSONObject(body).optString("results_html")) }.getOrDefault(emptyList())
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()

    private fun parseSearchHtml(html: String): List<StoreItem> {
        val out = ArrayList<StoreItem>()
        for (row in html.split("<a ").drop(1)) {
            val id = Regex("data-ds-appid=\"(\\d+)\"").find(row)?.groupValues?.get(1) ?: continue
            val title = Regex("<span class=\"title\">([^<]*)</span>").find(row)?.groupValues?.get(1)?.let(::unescape) ?: continue
            val discount = Regex("data-discount=\"(\\d+)\"").find(row)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("discount_pct\">-?(\\d+)%").find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val price = Regex("discount_final_price[^\"]*\">([^<]*)<").find(row)?.groupValues?.get(1)?.let(::unescape).orEmpty()
            val cents = Regex("data-price-final=\"(\\d+)\"").find(row)?.groupValues?.get(1)?.toIntOrNull()
            out += StoreItem(Store.STEAM, id, title, header(id), if (cents == 0 && price.isEmpty()) "Free" else price, storeUrl(id), discount)
        }
        return out.distinctBy { it.id }
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
                screenshots = data.optJSONArray("screenshots")?.let { a ->
                    (0 until minOf(a.length(), 12)).mapNotNull { a.optJSONObject(it)?.optString("path_thumbnail")?.ifBlank { null } }
                }.orEmpty(),
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
                price, url, discount, o.optString("description").takeIf { it != title }.orEmpty())
        }
    }

    private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /** One searchStore call of the store's GraphQL (base games only). */
    private fun searchStore(
        count: Int, keywords: String? = null, sortBy: String = "relevancy", sortDir: String = "DESC",
        onSale: Boolean? = null, freeGame: Boolean? = null, releasedOnly: Boolean = false,
        tag: String? = null, start: Int = 0,
    ): List<StoreItem> {
        val args = StringBuilder("category:\"games/edition/base\",count:\$count,country:\$country,locale:\$locale," +
            "sortBy:\$sortBy,sortDir:\$sortDir,allowCountries:\$country")
        if (keywords != null) args.append(",keywords:\$keywords")
        if (onSale != null) args.append(",onSale:$onSale")
        if (freeGame != null) args.append(",freeGame:$freeGame")
        if (releasedOnly) args.append(",releaseDate:\"[,${nowIso()}]\"")
        if (tag != null) args.append(",tag:\"$tag\"")
        if (start > 0) args.append(",start:$start")
        val query = "query q(\$count:Int,\$country:String!,\$locale:String,\$sortBy:String,\$sortDir:String" +
            (if (keywords != null) ",\$keywords:String" else "") +
            "){Catalog{searchStore($args){elements{$FIELDS}}}}"
        val vars = JSONObject().put("count", count).put("country", country()).put("locale", locale())
            .put("sortBy", sortBy).put("sortDir", sortDir)
        if (keywords != null) vars.put("keywords", keywords)
        val body = StoreNet.postJson(GRAPHQL, JSONObject().put("query", query).put("variables", vars).toString()) ?: return emptyList()
        return runCatching {
            parse(JSONObject(body).optJSONObject("data")?.optJSONObject("Catalog")?.optJSONObject("searchStore")?.optJSONArray("elements"))
        }.getOrDefault(emptyList())
    }

    fun search(term: String): List<StoreItem> = cached("s:$term", 5 * 60_000L) { searchStore(30, keywords = term) }

    /** The store's genre tags (ids as epicstore_api / the store's browse filters use them). */
    private val TAGS = linkedMapOf(
        "1216" to ("Action" to "Экшен"), "1117" to ("Adventure" to "Приключения"), "1367" to ("RPG" to "Ролевые игры"),
        "1210" to ("Shooter" to "Шутеры"), "1115" to ("Strategy" to "Стратегии"), "1080" to ("Survival" to "Выживание"),
        "1298" to ("Puzzle" to "Головоломки"), "1212" to ("Racing" to "Гонки"), "1203" to ("Multiplayer" to "Мультиплеер"),
        "1370" to ("Single Player" to "Для одного игрока"),
    )

    fun categories(): List<StoreCategory> {
        val ru = Locale.getDefault().language == "ru"
        return TAGS.map { (id, n) -> StoreCategory(id, if (ru) n.second else n.first) }
    }

    /** One page (30) of a genre's games. */
    fun browse(tag: String, sort: CategorySort, start: Int): List<StoreItem> = cached("b:$tag:$sort:$start", 15 * 60_000L) {
        when (sort) {
            CategorySort.POPULAR -> searchStore(30, tag = tag, start = start)
            CategorySort.SPECIALS -> searchStore(30, tag = tag, start = start, onSale = true, sortBy = "currentPrice", sortDir = "ASC").filter { it.discountPercent > 0 }
            CategorySort.NEW -> searchStore(30, tag = tag, start = start, sortBy = "releaseDate", sortDir = "DESC", releasedOnly = true)
        }
    }

    /** A store collection (top-sellers, most-played…), as on the store's front page. */
    private fun collection(slug: String): List<StoreItem> {
        val query = "query q(\$locale:String,\$country:String!,\$slug:String){Storefront{collectionLayout(locale:\$locale,slug:\$slug){collectionOffers{$FIELDS}}}}"
        val vars = JSONObject().put("country", country()).put("locale", locale()).put("slug", slug)
        val body = StoreNet.postJson(GRAPHQL, JSONObject().put("query", query).put("variables", vars).toString()) ?: return emptyList()
        return runCatching {
            parse(JSONObject(body).optJSONObject("data")?.optJSONObject("Storefront")?.optJSONObject("collectionLayout")?.optJSONArray("collectionOffers"))
        }.getOrDefault(emptyList())
    }

    /**
     * Showcase like Bannerlator's: free this week, on sale, new releases, free to play. The four
     * requests run at once (one after another took several seconds on a slow connection).
     */
    fun featured(): List<Pair<String, List<StoreItem>>> = cached("featured", 30 * 60_000L) {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val free = pool.submit<List<StoreItem>> { freeNow() }
            val sale = pool.submit<List<StoreItem>> { searchStore(24, sortBy = "currentPrice", sortDir = "ASC", onSale = true).filter { it.discountPercent > 0 } }
            val fresh = pool.submit<List<StoreItem>> { searchStore(24, sortBy = "releaseDate", sortDir = "DESC", releasedOnly = true) }
            val f2p = pool.submit<List<StoreItem>> { searchStore(24, freeGame = true) }
            val top = pool.submit<List<StoreItem>> { collection("top-sellers") }
            val played = pool.submit<List<StoreItem>> { collection("most-played") }
            fun get(f: java.util.concurrent.Future<List<StoreItem>>) = runCatching { f.get(40, TimeUnit.SECONDS) }.getOrDefault(emptyList())
            listOf("free_now" to get(free), "top_sellers" to get(top), "specials" to get(sale), "most_played" to get(played),
                "new_releases" to get(fresh), "free_to_play" to get(f2p))
                .filter { it.second.isNotEmpty() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Free this week (the static promotions feed). */
    private fun freeNow(): List<StoreItem> {
        val body = StoreNet.get("$PROMOS?locale=${locale()}&country=${country()}&allowCountries=${country()}")
            ?: return emptyList()
        return runCatching {
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
            free.toList()
        }.getOrDefault(emptyList())
    }
}
