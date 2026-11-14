package com.winlator.cmod.store

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.contentdialog.ContentDialog
import com.winlator.cmod.session.LaunchConfig
import com.winlator.cmod.store.epic.EpicInstaller
import com.winlator.cmod.store.steam.SteamInstaller
import com.winlator.cmod.ui.uiStr
import java.io.File
import java.util.Locale

/**
 * Removing a game from the library: the shortcut, and on request the game itself. A game from
 * Steam / Epic is uninstalled there (its files, its other shortcuts, the store's record); any other
 * game loses its folder, but only a folder that is clearly the game's own (never a shared one such
 * as Download, Program Files or the container).
 */
object ShortcutRemoval {
    private class Target(val label: String, val dir: File, val remove: (Context) -> Unit)

    private fun target(context: Context, shortcut: Shortcut): Target? {
        shortcut.getExtra("steamAppId").toIntOrNull()?.let { id ->
            SteamInstaller.load(context)
            val i = SteamInstaller.installed.value[id] ?: return null
            return Target("Steam", File(i.dir)) { c -> SteamInstaller.uninstall(c, id) }
        }
        shortcut.getExtra("epicApp").takeIf { it.isNotEmpty() }?.let { app ->
            EpicInstaller.load(context)
            val i = EpicInstaller.installed.value[app] ?: return null
            return Target("Epic", File(i.dir)) { c -> EpicInstaller.uninstall(c, app) }
        }
        val dir = gameDir(shortcut) ?: return null
        return Target("", dir) { _ -> StoreShortcuts.deleteTree(dir) }
    }

    /** The exe's folder (above Binaries/Win64 for Unreal games), when it's safe to delete. */
    private fun gameDir(shortcut: Shortcut): File? {
        val exe = LaunchConfig.exeFile(shortcut.path) ?: return null
        var dir = exe.parentFile ?: return null
        if (Regex("/Binaries/Win(64|32)$", RegexOption.IGNORE_CASE).containsMatchIn(dir.path)) {
            dir = dir.parentFile?.parentFile?.parentFile ?: return null
        }
        val path = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        if (!path.isDirectory) return null
        val shared = mutableListOf(
            Environment.getExternalStorageDirectory(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YurNLator"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YurNLator/Games"),
            SteamInstaller.root(), EpicInstaller.root(),
        )
        shortcut.container?.rootDir?.let { shared += it }
        val p = path.path
        // Never a folder holding (or inside the tops of) shared places.
        if (shared.any { s -> runCatching { s.canonicalPath }.getOrNull()?.let { it == p || it.startsWith("$p/") } == true }) return null
        val name = path.name.lowercase(Locale.ROOT)
        if (name in setOf("drive_c", "program files", "program files (x86)", "games", "download", "downloads", "documents", "desktop",
                "users", "xuser", "appdata", "windows", "steamapps", "common")) return null
        if (p.split('/').count { it.isNotEmpty() } < 4) return null
        if (File(path, ".wine").exists() || File(path, "drive_c").exists()) return null
        return path
    }

    /** Asks, then removes; [onRemoved] runs on the main thread when the shortcut is gone. */
    @JvmStatic
    fun ask(context: Context, shortcut: Shortcut, onRemoved: Runnable) {
        val t = target(context, shortcut)
        val dialog = ContentDialog(context)
        dialog.setMessage(uiStr(R.string.l_rm_question, shortcut.name))
        var box: CheckBox? = null
        if (t != null) {
            val frame = dialog.contentView.findViewById<FrameLayout>(R.id.FrameLayout)
            frame.visibility = View.VISIBLE
            box = CheckBox(context).apply {
                text = if (t.label.isNotEmpty()) uiStr(R.string.l_rm_store_too, t.label, t.dir.path) else uiStr(R.string.l_rm_folder_too, t.dir.path)
                setTextColor(ContextCompat.getColor(context, R.color.ui_text_primary))
                textSize = 14f
            }
            frame.addView(box)
        }
        dialog.setOnConfirmCallback {
            val withFiles = box?.isChecked == true && t != null
            val app = context.applicationContext
            if (!withFiles) {
                if (shortcut.remove()) onRemoved.run()
                else Toast.makeText(context, uiStr(R.string.l_sc_couldn_t_remove_the_shortcut_s), Toast.LENGTH_LONG).show()
                return@setOnConfirmCallback
            }
            Toast.makeText(context, uiStr(R.string.l_rm_removing), Toast.LENGTH_SHORT).show()
            Thread({
                runCatching { t!!.remove(app) }
                runCatching { if (shortcut.file.exists()) shortcut.remove() }
                Handler(Looper.getMainLooper()).post {
                    onRemoved.run()
                    Toast.makeText(app, uiStr(R.string.l_rm_removed), Toast.LENGTH_SHORT).show()
                }
            }, "RemoveGame").start()
        }
        dialog.show()
    }
}
