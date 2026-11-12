package com.winlator.cmod.components

import com.winlator.cmod.contents.Downloader
import com.winlator.cmod.core.DownloadTracker
import java.io.File

/** Downloads with 0..1 progress, the shape the ported component installers expect. */
internal object ComponentDownload {
    /**
     * DownloadTracker key of the component being installed (set by the Windows components
     * screen): its downloads then show a bar with pause / resume / cancel. A cancelled
     * download returns false like a failed one, so the installers clean up as usual.
     */
    @Volatile @JvmStatic var trackKey: String? = null
    @Volatile @JvmStatic var cancelled = false

    fun file(url: String, dest: File, progress: (Float) -> Unit): Boolean {
        dest.parentFile?.mkdirs()
        val key = trackKey ?: return Downloader.downloadFile(url, dest) { p -> progress((p ?: 0) / 100f) }
        return try {
            DownloadTracker.download(key, url, dest).also { if (it) { progress(1f); DownloadTracker.installing(key) } }
        } catch (e: DownloadTracker.CancelledException) {
            cancelled = true
            false
        }
    }
}
