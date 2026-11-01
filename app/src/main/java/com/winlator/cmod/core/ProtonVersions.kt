package com.winlator.cmod.core

/**
 * Compares Wine / Proton builds by name, to offer "a newer build of what you use"
 * (like Bannerlator's "Update layer"). Builds are the same line when flavor (Proton,
 * GE-Proton, DroidDeck-Proton, Proton-CachyOS, Wine), major version and architecture
 * match; then minor, build numbers / date and layer version "(vNN)" decide which is newer.
 *
 *   "Proton-11.0-1-arm64ec (v15)" < "Proton-11.0-2-arm64ec (v15)"
 *   "GE-Proton-11.0-7-arm64ec (v14)" < "GE-Proton-11.0-9-arm64ec (v15)"
 *   "proton-11.0-arm64ec" and "proton-10.0-arm64ec" are different lines (major differs)
 */
object ProtonVersions {
    data class Key(val line: String, val version: List<Int>)

    @JvmStatic
    fun key(name: String): Key? {
        val n = name.lowercase().replace('_', '-').replace(' ', '-')
        val arch = when {
            "arm64ec" in n -> "arm64ec"
            "x86-64" in n -> "x86_64"
            else -> return null
        }
        val flavor = when {
            n.startsWith("ge-proton") -> "ge-proton"
            n.startsWith("droiddeck-proton") -> "droiddeck-proton"
            "cachyos" in n -> "proton-cachyos"
            "proton" in n -> "proton"
            n.startsWith("wine") -> "wine"
            else -> return null
        }
        val m = Regex("(?:proton|wine)-?(?:cachyos-)?(\\d+)(?:\\.(\\d+))?((?:-\\d+(?:\\.\\d+)*)*)").find(n) ?: return null
        val major = m.groupValues[1]
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        val builds = m.groupValues[3].split('-', '.').filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }
        val layer = Regex("\\(v(\\d+)\\)").find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Key("$flavor-$major-$arch", listOf(minor) + builds.padTo(3) + layer)
    }

    private fun List<Int>.padTo(size: Int) = this + List((size - this.size).coerceAtLeast(0)) { 0 }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** The newest name in [candidates] that is a newer build of [current]'s line, or null. */
    @JvmStatic
    fun newerThan(current: String, candidates: List<String>): String? {
        val cur = key(current) ?: return null
        return candidates.mapNotNull { c -> key(c)?.let { c to it } }
            .filter { (_, k) -> k.line == cur.line && compare(k.version, cur.version) > 0 }
            .maxWithOrNull { a, b -> compare(a.second.version, b.second.version) }
            ?.first
    }
}
