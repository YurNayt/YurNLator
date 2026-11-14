package com.winlator.cmod.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Which C library a Wine / Proton package was built for. YurNLator runs Wine on glibc (its
 * image file system); builds for Android's own libc (bionic: Bannerlator's Proton, GE-Proton,
 * Proton-CachyOS and DroidDeck-Proton layers) can't start there and the container crashed.
 */
object WineBuild {
    private val PROBES = listOf("bin/wineserver", "lib/wine/aarch64-unix/wineserver", "bin/wine", "lib/wine/aarch64-unix/wine")

    /** True when the package in [winePath] is a bionic build (its programs ask for Android's linker). */
    @JvmStatic
    fun isBionic(winePath: String?): Boolean {
        if (winePath.isNullOrBlank()) return false
        for (rel in PROBES) {
            val f = File(winePath, rel)
            if (!f.isFile || f.length() < 64) continue
            val head = runCatching {
                RandomAccessFile(f, "r").use { r -> ByteArray(minOf(8192L, r.length()).toInt()).also { r.readFully(it) } }
            }.getOrNull() ?: continue
            if (head.size < 4 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte()) continue
            return String(head, Charsets.ISO_8859_1).contains("/system/bin/linker64")
        }
        return false
    }

    /** A catalog entry that is a bionic build, by its link or name ("…-Bionic.wcp", "bionic-layers-…"). */
    @JvmStatic
    fun isBionicEntry(remoteUrl: String?, verName: String?): Boolean =
        (remoteUrl?.contains("bionic", ignoreCase = true) == true) || (verName?.contains("bionic", ignoreCase = true) == true)
}
