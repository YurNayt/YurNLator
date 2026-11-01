package com.winlator.cmod.core

import com.winlator.cmod.contents.ContentCatalogs
import java.util.Locale

/**
 * Groups versions of the same component "family" so long lists stay short: one row per
 * family (its newest build), the other versions behind an arrow.
 *
 * The family is the name without its version numbers, commit hashes and layer "(vNN)":
 *   "Proton-11.0-2-arm64ec (v16)", "Proton-11.0-1-arm64ec (v15)", "proton-10.0-arm64ec" -> Proton arm64ec
 *   "dxvk-gplasync-arm64ec-3.1.1-1", "dxvk-gplasync-arm64ec-3.0-1"                     -> DXVK gplasync arm64ec
 *   "FEXCore-2610-d321f49fb", "FEXCore-2610-fb48e2d19"                                -> FEXCore nightly
 * Words that only repeat the type ("dxvk-", "vkd3d-proton-") are ignored, so "2.7.1" from
 * one catalog and "dxvk-2.7.1" from another land in the same family.
 */
object VersionGroups {
    private val layerRe = Regex("\\(v(\\d+)\\)")
    private val numericRe = Regex("^v?(\\d+(?:\\.\\d+)*)\\.?([a-z])?$")
    private val hashRe = Regex("^[0-9a-f]{7,40}$")
    private val nightlyRe = Regex("FEXCore-(\\d{4})-([0-9a-f]{7,40})", RegexOption.IGNORE_CASE)

    private val typeWords = mapOf(
        "dxvk" to setOf("dxvk"),
        "vkd3d" to setOf("vkd3d", "vk3dk", "proton"),
        "fexcore" to setOf("fexcore", "fex"),
        "box64" to setOf("box64"),
        "wowbox64" to setOf("wowbox64"),
        "proton" to setOf("proton"),
        "wine" to setOf("wine"),
    )

    data class Parsed(
        val family: String,
        val version: List<Long>,
        val hash: String?,
        /** Extra words besides the version ("ppa", "unix"…): 0 for the plain build. */
        val words: Int = 0,
        /** Numbers of the first version token: "dxvk-gplasync-2.3.1-1" -> [2, 3, 1]. */
        val lead: List<Long> = emptyList(),
        /** Same build under another spelling ("2609" / "FEXCore-2609") has the same ident. */
        val ident: String = ""
    )

    @JvmStatic
    fun parse(type: String, name: String): Parsed {
        val t = type.lowercase(Locale.ENGLISH)
        var n = name.lowercase(Locale.ENGLISH)
        val layer = layerRe.find(n)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        n = layerRe.replace(n, " ").replace("x86_64", "x8664").replace("x86-64", "x8664")
        val skip = typeWords[t].orEmpty()
        val family = sortedSetOf<String>()
        val numbers = mutableListOf<Long>()
        var hash: String? = null
        var lead: List<Long>? = null
        for (tok in n.split('-', '_', '+', ' ', '/').filter { it.isNotBlank() }) {
            val m = numericRe.matchEntire(tok)
            if (m != null) {
                val digits = m.groupValues[1]
                // FEX dates as YYMMDD ("fexcore_241214") compare as YYMM + day, like "2610".
                if (t == "fexcore" && digits.length == 6 && '.' !in digits) {
                    numbers += digits.take(4).toLong(); numbers += digits.drop(4).toLong()
                } else digits.split('.').mapNotNullTo(numbers) { it.toLongOrNull() }
                m.groupValues[2].firstOrNull()?.let { numbers += (it - 'a' + 1).toLong() }
                if (lead == null) lead = numbers.toList()
                continue
            }
            if (hashRe.matches(tok) && tok.any { it.isLetter() }) {
                hash = tok
                continue
            }
            if (tok !in skip) family += tok
        }
        val words = family.size
        if (hash != null && t == "fexcore") family += "nightly"
        val padded = numbers + List((4 - numbers.size).coerceAtLeast(0)) { 0L }
        val fam = "$t:" + family.joinToString(" ")
        return Parsed(fam, padded + layer, hash, words, lead.orEmpty(),
            fam + "|" + (numbers + layer).joinToString(".") + "|" + hash.orEmpty())
    }

    private fun compare(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0L }.compareTo(b.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * Families in order of first appearance; inside each, newest first (version, then the
     * nightly build date; otherwise the original order is kept).
     */
    @JvmStatic
    fun <T> arrange(
        items: List<T>,
        type: (T) -> String,
        name: (T) -> String,
        installed: (T) -> Boolean = { false }
    ): List<Pair<String, List<T>>> {
        // The same build from two catalogs ("2609" and "FEXCore-2609") is shown once,
        // the installed copy if there is one.
        val unique = LinkedHashMap<String, Pair<T, Parsed>>()
        items.forEach { item ->
            val p = parse(type(item), name(item))
            val cur = unique[p.ident]
            if (cur == null || (!installed(cur.first) && installed(item))) unique[p.ident] = item to p
        }
        val parsed = unique.values.toList()
        if (parsed.isNotEmpty() && parsed.all { it.second.family.substringBefore(':') in BY_VERSION }) {
            return arrangeByVersion(parsed, name)
        }
        val groups = LinkedHashMap<String, MutableList<Pair<T, Parsed>>>()
        parsed.forEach { groups.getOrPut(it.second.family) { mutableListOf() } += it }
        return groups.map { (family, members) ->
            val sorted = members.sortedWith { a, b ->
                val c = compare(b.second.version, a.second.version)
                if (c != 0) c
                else (dateOf(b.second.hash) ?: 0L).compareTo(dateOf(a.second.hash) ?: 0L)
            }
            family to sorted.map { it.first }
        }
    }

    /** Types grouped by version (variants behind the arrow) instead of by family. */
    private val BY_VERSION = setOf("fexcore", "dxvk", "vkd3d", "box64", "wowbox64")

    /**
     * One row per version, its variants behind the arrow, newest first:
     *   DXVK 2.3.1 -> gplasync, arm64ec, gplasync arm64ec…;  FEX 2610 -> PPA, unix, PPA unix;
     *   daily FEX builds -> one row per day ("20261030").
     * The plain build leads each group.
     */
    private fun <T> arrangeByVersion(parsed: List<Pair<T, Parsed>>, name: (T) -> String): List<Pair<String, List<T>>> {
        val groups = LinkedHashMap<String, MutableList<Pair<T, Parsed>>>()
        fun lead(p: Parsed) = if (p.family.startsWith("fexcore:")) p.lead.take(1) else p.lead
        parsed.forEach { item ->
            val p = item.second
            val t = p.family.substringBefore(':')
            val key = when {
                t == "fexcore" && p.hash != null -> "fexcore:build:" + (dateOf(p.hash)?.toString() ?: p.hash)
                p.lead.isEmpty() -> p.ident
                else -> "$t:v" + lead(p).joinToString(".")
            }
            groups.getOrPut(key) { mutableListOf() } += item
        }
        fun order(p: Parsed) = lead(p) + listOf(dateOf(p.hash) ?: 0L)
        return groups.entries
            .sortedWith { a, b -> compare(order(b.value.first().second), order(a.value.first().second)) }
            .map { (key, members) ->
                // Plain build first; "FEXCore-2609" rather than a bare "2609"; then the original order.
                key to members.sortedWith(
                    compareBy<Pair<T, Parsed>> { it.second.words }
                        .thenBy { if (name(it.first).firstOrNull()?.isLetter() == true) 0 else 1 }
                ).map { it.first }
            }
    }

    private fun dateOf(hash: String?): Long? = hash?.let { ContentCatalogs.nightlyDate(it) }?.toLongOrNull()

    /** Name as shown: daily FEX builds get their build day, "FEXCore-2610-d321f49fb" -> "FEXCore-20261030". */
    @JvmStatic
    fun title(name: String): String {
        val m = nightlyRe.find(name) ?: return name
        val date = ContentCatalogs.nightlyDate(m.groupValues[2]) ?: return name
        return name.replaceRange(m.range, "FEXCore-$date")
    }

    /** Like [title], and a bare version ("2609", "2.7.1-arm64ec") gets its type in front: "FEXCore 2609". */
    @JvmStatic
    fun title(name: String, type: String): String {
        val t = title(name)
        if (t.firstOrNull()?.isDigit() != true) return t
        val label = when (type.lowercase(Locale.ENGLISH)) {
            "fexcore" -> "FEXCore"; "dxvk" -> "DXVK"; "vkd3d" -> "VKD3D"; "box64" -> "Box64"; "wowbox64" -> "WOWBox64"
            else -> return t
        }
        return "$label $t"
    }

    /** Short note under the name: "Nightly · d321f49fb" for daily FEX builds. */
    @JvmStatic
    fun note(name: String): String? {
        val m = nightlyRe.find(name) ?: return null
        if (ContentCatalogs.nightlyDate(m.groupValues[2]) == null) return null
        return "Nightly · ${m.groupValues[2]}"
    }

    /** "+3 versions" / "+1 version". */
    @JvmStatic
    fun moreLabel(count: Int): String = if (count == 1) "+1 version" else "+$count versions"
}
