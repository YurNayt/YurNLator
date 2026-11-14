package com.winlator.cmod.store

import android.content.Context
import android.os.Environment
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.ExeIconExtractor
import com.winlator.cmod.xenvironment.ImageFs
import java.io.File
import java.nio.file.Files

/** Library shortcuts and files of games installed from the stores (Steam, Epic). */
object StoreShortcuts {
    fun name(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]+"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Game" }

    fun containers(context: Context): List<Container> = ContainerManager(context).containers

    /** Shortcuts (any container) that start a game from [dir]. */
    fun of(context: Context, dir: String): List<File> =
        containers(context).flatMap { c ->
            c.desktopDir.listFiles { f -> f.name.endsWith(".desktop") }.orEmpty().filter { f ->
                runCatching { f.readText().contains(dir) }.getOrDefault(false)
            }
        }

    private fun wineHome(context: Context, container: Container): String {
        val imagefs = File(context.filesDir, "imagefs").absolutePath.trimEnd('/')
        val root = container.rootDir.absolutePath.trimEnd('/')
        return if (root.startsWith(imagefs)) root.substring(imagefs.length) else "/home/" + ImageFs.USER
    }

    /** Adds [exe] to [container]'s library as [title]; [extras] go to the shortcut's Extra Data. */
    fun add(context: Context, container: Container, title: String, exe: File, args: String, extras: Map<String, String>): File {
        val name = name(title)
        val desktop = File(container.desktopDir.apply { mkdirs() }, "$name.desktop")
        val prefix = wineHome(context, container) + "/.wine"
        desktop.writeText(buildString {
            append("[Desktop Entry]\n")
            append("Name=").append(name).append('\n')
            append("Exec=env WINEPREFIX=\"").append(prefix).append("\" wine \"").append(exe.absolutePath).append("\"\n")
            append("Type=Application\n")
            append("Icon=").append(name).append('\n')
            append("container_id:").append(container.id).append('\n')
            append("\n[Extra Data]\n")
            extras.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
            if (args.isNotBlank()) append("execArgs=").append(args).append('\n')
        })
        runCatching { ExeIconExtractor.extractIcon(exe, File(container.getIconsDir(64).apply { mkdirs() }, "$name.png")) }
        return exe
    }

    /** The game's store picture as its library cover (YurNLator/covers/<name>.png). */
    fun saveCover(title: String, bytes: ByteArray) {
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "YurNLator/covers").apply { mkdirs() }
            File(dir, name(title) + ".png").writeBytes(bytes)
        }
    }

    /** Deletes a folder tree without ever following a link out of it. */
    fun deleteTree(f: File) {
        if (Files.isSymbolicLink(f.toPath())) { f.delete(); return }
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }
}
