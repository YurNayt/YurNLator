package com.winlator.cmod.core

/**
 * Readable GPU driver names, DroidDeck-style: a short title plus a line saying what the
 * build is for, instead of the raw file name.
 *
 *   "Turnip-v27.0.0-20261030-r2-710-720-Test-Linux"  -> "Turnip v27.0.0 R2" | "Adreno 710–720 · Test · Linux · 30.10.2026"
 *   "Turnip_v27.0.0-R1-One ui"                       -> "Turnip v27.0.0 R1" | "OneUI fix"
 *   "tu_v32 — mainline-turnip-sync-V32.zip"          -> "Turnip v32"        | "Mainline sync"
 *   "Turnip_MTR_v1.9.1-p_Axxx"                       -> "Turnip MTR v1.9.1-p" | "All Adreno"
 *
 * Unknown words stay in the title, so nothing is lost when a new naming style appears.
 */
object DriverNames {
    data class Parsed(val title: String, val tags: List<String>)

    private val WORD_TAGS: List<Pair<Regex, String>> = listOf(
        "oneui" to "OneUI fix",
        "8g2|8gen2" to "8 Gen 2",
        "test" to "Test",
        "linux" to "Linux",
        "gmem" to "GMEM",
        "sysmem" to "Prefer sysmem",
        "auto" to "Auto",
        "hotfix" to "Hotfix",
        "sdkfix" to "SDK fix",
        "patch(ed)?" to "Patched",
        "hacks?" to "Hacks",
        "mainline" to "Mainline",
        "stable" to "Stable",
        "rework" to "Rework",
        "flushall" to "Flush-all",
        "debug" to "Debug",
        "adpkg" to "Qualcomm proprietary",
        "axxx" to "All Adreno",
    ).map { (rx, tag) -> Regex(rx) to tag }

    /** Words that carry no meaning for the user and are dropped. */
    private val NOISE = setOf("cb", "main", "yuck", "github", "run", "full")

    private val cache = HashMap<String, Parsed>()

    @JvmStatic
    fun parse(name: String): Parsed = synchronized(cache) { cache.getOrPut(name) { parseUncached(name) } }

    private fun adrenoTag(token: String): String? {
        val t = token.lowercase()
        Regex("a?([678])xx").matchEntire(t)?.let { return "Adreno ${it.groupValues[1]}xx" }
        Regex("gen([678])").matchEntire(t)?.let { return "Adreno ${it.groupValues[1]}xx" }
        Regex("a([678]\\d\\d)p?").matchEntire(t)?.let { return "Adreno ${it.groupValues[1]}" }
        return null
    }

    private fun parseUncached(name: String): Parsed {
        var raw = name.trim()
        var left: String? = null
        // "v26.0.0-rc08 — Turnip_v26.0.0_R8.zip": the file name is the descriptive part.
        val dash = raw.indexOf(" — ")
        if (dash >= 0) {
            left = raw.substring(0, dash)
            raw = raw.substring(dash + 3)
        }
        raw = raw.replace(Regex("(?i)\\.zip$"), "")
        val toks = raw.split(Regex("[_\\-\\s]+")).filter { it.isNotEmpty() }

        val tags = ArrayList<String>()
        val title = ArrayList<String>()
        var date: String? = null
        fun add(tag: String) { if (tag !in tags) tags += tag }

        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val tl = t.lowercase()
            val next = toks.getOrNull(i + 1)?.lowercase()

            if (tl == "one" && next == "ui") { add("OneUI fix"); i += 2; continue }
            if (tl == "prefer" && next == "sysmem") { add("Prefer sysmem"); i += 2; continue }
            if (tl == "perf" && next == "fix") { add("Perf fix"); i += 2; continue }
            // "710-720": a range of Adreno models
            if (t.matches(Regex("[678]\\d\\d")) && toks.getOrNull(i + 1)?.matches(Regex("[678]\\d\\d")) == true) {
                add("Adreno $t–${toks[i + 1]}"); i += 2; continue
            }
            val adreno = adrenoTag(t)
            if (adreno != null) { add(adreno); i++; continue }
            run {
                when {
                    t.matches(Regex("20\\d{6}")) -> { date = "${t.substring(6, 8)}.${t.substring(4, 6)}.${t.substring(0, 4)}" }
                    tl == "sync" -> {
                        when {
                            "Mainline" in tags -> tags[tags.indexOf("Mainline")] = "Mainline sync"
                            "Stable" in tags -> tags[tags.indexOf("Stable")] = "Stable sync"
                            else -> add("Synced")
                        }
                    }
                    tl.matches(Regex("8elite\\d?")) -> {
                        val gen = tl.removePrefix("8elite")
                        add("8 Elite" + if (gen.isNotEmpty()) " Gen $gen" else "")
                    }
                    tl == "git" -> add("Dev build")
                    tl in NOISE -> Unit
                    // MTR-style suffix: "v1.9.1-p" stays one version
                    tl.matches(Regex("[a-z]")) && title.lastOrNull()?.matches(Regex("v\\d.*")) == true ->
                        title[title.size - 1] = title.last() + "-" + tl
                    else -> {
                        val tag = WORD_TAGS.firstOrNull { it.first.matches(tl) }?.second
                        if (tag != null) add(tag) else {
                            val word = when {
                                tl == "tu" || tl == "turnip" || tl == "turnio" -> "Turnip"
                                tl == "mesa" -> "Mesa"
                                tl == "qualcomm" -> "Qualcomm"
                                tl == "mtr" -> "MTR"
                                tl.matches(Regex("r\\d+")) -> t.uppercase()
                                tl.matches(Regex("v\\d[\\w.]*")) -> "v" + t.substring(1)
                                else -> t
                            }
                            if (title.lastOrNull() != word) title += word
                        }
                    }
                }
                i++
            }
        }

        date?.let { tags += it }
        // "tu_v25 — a8xx-gen8-V25": only a version is left, these are Turnip builds.
        if (title.firstOrNull()?.matches(Regex("v\\d.*")) == true && !raw.contains("qualcomm", ignoreCase = true)) {
            title.add(0, "Turnip")
        }
        var text = title.joinToString(" ").trim()
        if (text.isEmpty() || text.matches(Regex("[\\d.]+"))) {
            text = left ?: raw
            if (text.matches(Regex("[\\d.]+"))) text = "v$text"
        }
        return Parsed(text, tags)
    }
}
