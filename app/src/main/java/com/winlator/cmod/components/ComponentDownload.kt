package com.winlator.cmod.components

import com.winlator.cmod.contents.Downloader
import java.io.File

/** Downloads with 0..1 progress, the shape the ported component installers expect. */
internal object ComponentDownload {
    fun file(url: String, dest: File, progress: (Float) -> Unit): Boolean {
        dest.parentFile?.mkdirs()
        return Downloader.downloadFile(url, dest) { p -> progress((p ?: 0) / 100f) }
    }
}
