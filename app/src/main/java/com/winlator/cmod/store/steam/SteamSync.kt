package com.winlator.cmod.store.steam

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.SaveBackup
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.xenvironment.ImageFs
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.Stats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import `in`.dragonbra.javasteam.types.SteamID
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Steam Cloud saves and achievements for games installed from the Steam library (their shortcut has
 * steamAppId), around each session:
 *
 *  - before the game starts: the newer cloud saves come down into the container (local files that
 *    are newer stay, and the container's saves are backed up first); the game's achievements (names
 *    and the ones already earned) are given to the Goldberg emulator, so the game shows them;
 *  - after it ends: changed saves go up to Steam Cloud (never deleting anything there), and
 *    achievements earned in the game are set on the Steam profile (switchable in the library).
 *
 * Cloud names follow Steam's layout: a root token fused to the path ("%WinMyDocuments%My Games/…"),
 * or a bare name for the files the game keeps through Steam's API (under Goldberg those live in
 * AppData/Roaming/GSE Saves/<app>/remote). Written after Bannerlator's SteamCloudSaveManager /
 * SteamCloudSavePaths / SteamAchievementStore (GPL-3.0), much reduced.
 */
object SteamSync {
    private const val TAG = "YurnSteamSync"
    private const val JOB_S = 30L
    private const val HTTP_MS = 60_000
    private const val MAX_UPLOAD = 100L * 1024 * 1024

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("yurn_steam", Context.MODE_PRIVATE)

    /** Achievements earned in a game go to the Steam profile. On by default. */
    fun pushAchievements(c: Context) = prefs(c).getBoolean("push_achievements", true)
    fun setPushAchievements(c: Context, on: Boolean) = prefs(c).edit().putBoolean("push_achievements", on).apply()

    /** "In game" on the Steam profile while a Steam-library game runs. On by default. */
    fun showPlaying(c: Context) = prefs(c).getBoolean("show_playing", true)
    fun setShowPlaying(c: Context, on: Boolean) = prefs(c).edit().putBoolean("show_playing", on).apply()

    /** Where Goldberg records the achievements earned in the container. */
    internal fun gseAchievements(container: Container, appId: Int) = File(Paths(container, File("."), appId).gse, "achievements.json")

    internal fun appIdOf(shortcut: Shortcut?): Int = shortcut?.getExtra("steamAppId", "")?.toIntOrNull() ?: 0

    private fun installed(context: Context, appId: Int): SteamInstaller.Installed? {
        SteamInstaller.load(context)
        return SteamInstaller.installed.value[appId]
    }

    // ── Paths ─────────────────────────────────────────────────────────────────

    private class Paths(container: Container, val installDir: File, val appId: Int) {
        val driveC = File(container.rootDir, ".wine/drive_c")
        val profile: File = File(driveC, "users/${ImageFs.USER}").takeIf { it.isDirectory }
            ?: File(driveC, "users").listFiles()?.firstOrNull { it.isDirectory && !it.name.equals("Public", true) }
            ?: File(driveC, "users/${ImageFs.USER}")
        val gse = File(profile, "AppData/Roaming/GSE Saves/$appId")
        val remote = File(gse, "remote")

        /** Steam's spelling of each root and its folder here. */
        val roots: List<Pair<String, File>> = listOf(
            "GameInstall" to installDir,
            "WinProgramData" to File(driveC, "ProgramData"),
            "SteamUserBaseStorage" to remote,
            "WinSavedGames" to File(profile, "Saved Games"),
            "WinAppDataLocalLow" to File(profile, "AppData/LocalLow"),
            "WinAppDataLocal" to File(profile, "AppData/Local"),
            "WinAppDataRoaming" to File(profile, "AppData/Roaming"),
            "WinMyDocuments" to File(profile, "Documents"),
            "Root" to profile,
        )
        private val aliases = mapOf("winappdata" to "winappdataroaming", "steamclouddocuments" to "winmydocuments",
            "steamuserdata" to "steamuserbasestorage", "root_mod" to "root", "winhome" to "root", "windowshome" to "root")

        fun root(token: String): Pair<String, File>? {
            val t = token.trim('%').lowercase(Locale.ROOT)
            val key = aliases[t] ?: t
            return roots.firstOrNull { it.first.lowercase(Locale.ROOT) == key }
        }

        /** The local file of a cloud name, or null (unknown root, or it would leave its folder). */
        fun local(name: String): File? {
            val n = name.replace('\\', '/')
            val m = Regex("^%([^%]+)%/*(.*)$").find(n)
            val (base, rest) = if (m == null) remote to n.trimStart('/')
                else (root(m.groupValues[1])?.second ?: return null) to m.groupValues[2]
            if (rest.isEmpty() || rest.split('/').any { it == ".." }) return null
            val f = File(base, rest)
            return runCatching {
                if (f.canonicalPath.startsWith(base.canonicalPath + File.separator)) f else null
            }.getOrNull()
        }
    }

    private fun sha1(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest()
    }

    private class CloudFile(val name: String, val sha: ByteArray, val time: Long, val size: Long)

    private fun cloud(): SteamCloud? = SteamSession.client?.getHandler(SteamCloud::class.java)

    private fun listCloud(sc: SteamCloud, appId: Int): List<CloudFile> {
        val list = sc.getAppFileListChange(appId).get(JOB_S, TimeUnit.SECONDS)
        val prefixes = list.pathPrefixes
        return list.files.map { f ->
            val prefix = if (f.pathPrefixIndex in prefixes.indices) prefixes[f.pathPrefixIndex] else ""
            val name = when {
                prefix.isEmpty() -> f.filename
                f.filename.isEmpty() -> prefix
                else -> prefix.trimEnd('/', '\\') + "/" + f.filename.trimStart('/', '\\')
            }
            CloudFile(name, f.shaFile, f.timestamp.time, f.rawFileSize.toLong())
        }
    }

    private fun download(sc: SteamCloud, appId: Int, name: String, dest: File): Boolean {
        val info = sc.clientFileDownload(appId, name).get(JOB_S, TimeUnit.SECONDS)
        if (info.urlHost.isEmpty()) return false
        val conn = URL((if (info.useHttps) "https://" else "http://") + info.urlHost + info.urlPath).openConnection() as HttpURLConnection
        conn.connectTimeout = HTTP_MS; conn.readTimeout = HTTP_MS
        info.requestHeaders.forEach { conn.setRequestProperty(it.name, it.value) }
        try {
            if (conn.responseCode !in 200..299) return false
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".yurn_dl")
            conn.inputStream.use { raw ->
                if (info.fileSize != info.rawFileSize) {
                    ZipInputStream(raw).use { zip -> if (zip.nextEntry == null) return false; tmp.outputStream().use { zip.copyTo(it) } }
                } else tmp.outputStream().use { raw.copyTo(it) }
            }
            if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) { tmp.delete(); return false } }
            runCatching { dest.setLastModified(info.timestamp.time) }
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun upload(sc: SteamCloud, appId: Int, file: File, name: String, batch: Long): Boolean {
        val sha = sha1(file)
        val size = file.length().toInt()
        val info = sc.beginFileUpload(appId = appId, fileSize = size, rawFileSize = size, fileSha = sha,
            timestamp = Date(file.lastModified()), filename = name, uploadBatchId = batch).get(JOB_S, TimeUnit.SECONDS)
        var ok = true
        RandomAccessFile(file, "r").use { raf ->
            for (b in info.blockRequests) {
                val buf = ByteArray(b.blockLength)
                raf.seek(b.blockOffset)
                raf.readFully(buf)
                val conn = URL((if (b.useHttps) "https://" else "http://") + b.urlHost + b.urlPath).openConnection() as HttpURLConnection
                conn.connectTimeout = HTTP_MS; conn.readTimeout = HTTP_MS
                conn.doOutput = true
                conn.requestMethod = "PUT"
                b.requestHeaders.forEach { conn.setRequestProperty(it.name, it.value) }
                try {
                    conn.setFixedLengthStreamingMode(buf.size)
                    conn.outputStream.use { it.write(buf) }
                    if (conn.responseCode !in 200..299) ok = false
                } catch (t: Throwable) {
                    Log.w(TAG, "block upload $name", t); ok = false
                } finally {
                    conn.disconnect()
                }
            }
        }
        sc.commitFileUpload(ok, appId, sha, name).get(JOB_S, TimeUnit.SECONDS)
        return ok
    }

    // ── Before the game ──────────────────────────────────────────────────────────

    /**
     * Blocking, bounded (~40 s at worst when Steam answers slowly; nothing when offline). Call on the
     * launch's worker thread before the game process starts.
     */
    @JvmStatic
    fun beforeLaunch(context: Context, container: Container?, shortcut: Shortcut?) {
        val appId = appIdOf(shortcut)
        if (appId <= 0 || container == null) return
        if (!com.winlator.cmod.store.StoreLaunchPrefs.enabled(context, com.winlator.cmod.store.StoreLaunchPrefs.steam(appId))) return
        val inst = installed(context, appId) ?: return
        if (!SteamSession.signedIn(context) || !SteamSession.awaitOnline(context, 15_000L)) {
            Log.i(TAG, "$appId: Steam offline, starting with the local saves")
            return
        }
        val p = Paths(container, File(inst.dir), appId)
        runCatching { cloudDown(context, container, p) }.onFailure { Log.w(TAG, "$appId: cloud down", it) }
        runCatching { achievementsIn(p, inst) }.onFailure { Log.w(TAG, "$appId: achievements", it) }
    }

    private fun cloudDown(context: Context, container: Container, p: Paths) {
        val sc = cloud() ?: return
        val files = listCloud(sc, p.appId)
        val todo = files.mapNotNull { f ->
            val local = p.local(f.name) ?: return@mapNotNull null
            when {
                !local.isFile -> f to local
                sha1(local).contentEquals(f.sha) -> null
                local.lastModified() > f.time + 2_000 -> null // played here since: keep it, it goes up after the game
                else -> f to local
            }
        }
        if (todo.isEmpty()) { Log.i(TAG, "${p.appId}: cloud saves already here (${files.size})"); return }
        if (todo.any { it.second.isFile }) SaveBackup.backup(container, SaveBackup.Reason.CLOUD)
        var got = 0
        for ((f, local) in todo) if (runCatching { download(sc, p.appId, f.name, local) }.getOrDefault(false)) got++
        Log.i(TAG, "${p.appId}: $got/${todo.size} cloud saves downloaded")
        if (got > 0) toast(context, uiStr(R.string.l_sts_cloud_down, got))
    }

    private fun userStats(appId: Int): Pair<SteamUserStats, UserStatsCallback>? {
        val us = SteamSession.client?.getHandler(SteamUserStats::class.java) ?: return null
        val id = SteamSession.steamId.takeIf { it != 0L } ?: return null
        val cb = us.getUserStats(appId, SteamID(id)).toFuture().get(JOB_S, TimeUnit.SECONDS) ?: return null
        return us to cb
    }

    private fun achievementsIn(p: Paths, inst: SteamInstaller.Installed) {
        val (_, cb) = userStats(p.appId) ?: return
        val list: List<AchievementBlocks> = runCatching { cb.getExpandedAchievements() }.getOrDefault(emptyList())
        if (list.isEmpty()) return
        // Names for the emulator (it shows only achievements it knows of).
        val schema = JSONArray()
        val progress = achievementProgress(cb)
        list.forEach { a ->
            val name = a.name ?: return@forEach
            schema.put(JSONObject().put("name", name).put("hidden", if (a.hidden) 1 else 0)
                .put("displayName", JSONObject().put("english", a.displayName ?: name))
                .put("description", JSONObject().put("english", a.description ?: ""))
                .put("icon", "img/" + (a.icon ?: "steam_default_icon_unlocked.jpg"))
                .put("icon_gray", "img/" + (a.iconGray ?: "steam_default_icon_locked.jpg"))
                .apply { progress[name]?.let { put("progress", it) } })
        }
        // The game's stats too: the emulator refuses a stat it doesn't know (SetStat fails), and
        // achievements by a stat ("10 clicks") never come without it.
        val stats = numericStats(cb)
        val statsJson = JSONArray()
        stats.values.forEach { st ->
            statsJson.put(JSONObject().put("name", st.name).put("type", if (st.float) "float" else "int")
                .put("default", st.default).put("global", "0"))
        }
        Goldberg.settingsDirs(inst).forEach { d ->
            runCatching {
                d.mkdirs()
                File(d, "achievements.json").writeText(schema.toString())
                if (statsJson.length() > 0) File(d, "stats.json").writeText(statsJson.toString())
            }
        }
        // The profile's values as the starting point (the game counts on from there).
        val statsDir = File(p.gse, "stats")
        val profileValues = HashMap<Int, Int>().apply { cb.stats.forEach { put(it.statId, it.statValue) } }
        for ((id, st) in stats) {
            val v = profileValues[id] ?: continue
            runCatching {
                val f = statFile(statsDir, st.name) ?: File(statsDir, st.name)
                val here = readStat(f)
                if (here == null || (if (st.float) Float.fromBits(v) > Float.fromBits(here) else v > here)) {
                    statsDir.mkdirs()
                    f.writeBytes(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array())
                }
            }
        }
        // What is already earned on the profile, merged with what the emulator recorded here before.
        val file = File(p.gse, "achievements.json")
        val local = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis() / 1000
        list.forEach { a ->
            val name = a.name ?: return@forEach
            val l = local.optJSONObject(name)
            val earnedHere = l != null && (l.optBoolean("earned", false) || l.optInt("earned", 0) != 0)
            if (a.isUnlocked || earnedHere) {
                val t = maxOf(a.unlockTimestamp.toLong(), l?.optLong("earned_time", 0L) ?: 0L).takeIf { it > 0 } ?: now
                local.put(name, JSONObject().put("earned", true).put("earned_time", t))
            } else if (l == null) {
                local.put(name, JSONObject().put("earned", false).put("earned_time", 0))
            }
        }
        runCatching { p.gse.mkdirs(); file.writeText(local.toString()) }
    }

    // ── After the game ───────────────────────────────────────────────────────────

    /** In the background, after Wine stopped (its files are written). */
    @JvmStatic
    fun afterExitAsync(context: Context, container: Container?, shortcut: Shortcut?) {
        val appId = appIdOf(shortcut)
        if (appId <= 0 || container == null) return
        if (!com.winlator.cmod.store.StoreLaunchPrefs.enabled(context, com.winlator.cmod.store.StoreLaunchPrefs.steam(appId))) return
        val app = context.applicationContext
        Thread({
            val inst = installed(app, appId) ?: return@Thread
            if (!SteamSession.signedIn(app) || !SteamSession.awaitOnline(app, 60_000L)) return@Thread
            val p = Paths(container, File(inst.dir), appId)
            runCatching { cloudUp(app, p) }.onFailure { Log.w(TAG, "$appId: cloud up", it) }
            if (pushAchievements(app)) runCatching { achievementsOut(app, p) }.onFailure { Log.w(TAG, "$appId: achievements", it) }
        }, "SteamSync").start()
    }

    /** Files the game declares as its saves (Steam's Auto-Cloud rules of the app). */
    private fun ruleFiles(p: Paths, out: MutableMap<String, File>) {
        val ufs = SteamLibrary.appInfo(p.appId)?.get("ufs")?.get("savefiles") ?: return
        val sid = SteamSession.steamId
        for (rule in ufs.children) {
            val platforms = rule.get("platforms").children.mapNotNull { it.value?.lowercase(Locale.ROOT) }
            if (platforms.isNotEmpty() && "windows" !in platforms && "all" !in platforms) continue
            val (token, base) = p.root(rule.get("root").value ?: continue) ?: continue
            val path = (rule.get("path").value ?: "").replace('\\', '/')
                .replace("{64BitSteamID}", sid.toString()).replace("{Steam3AccountID}", (sid and 0xFFFFFFFFL).toString())
            if (path.split('/').any { it == ".." }) continue
            val dir = if (path.isBlank()) base else File(base, path)
            val pattern = rule.get("pattern").value ?: "*"
            val regex = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it).replace("\\?", ".") }, RegexOption.IGNORE_CASE)
            val recursive = rule.get("recursive").value == "1"
            fun walk(d: File, depth: Int) {
                d.listFiles()?.forEach { f ->
                    if (Files.isSymbolicLink(f.toPath())) return@forEach
                    if (f.isDirectory) { if (recursive && depth < 8) walk(f, depth + 1) }
                    else if (regex.matches(f.name)) {
                        val rel = runCatching { f.canonicalFile.relativeTo(base.canonicalFile).path.replace(File.separatorChar, '/') }.getOrNull()
                        if (rel != null && !rel.startsWith("..")) out["%$token%$rel"] = f
                    }
                }
            }
            walk(dir, 0)
        }
    }

    private fun cloudUp(context: Context, p: Paths) {
        val sc = cloud() ?: return
        val cloudFiles = listCloud(sc, p.appId).associateBy { it.name }
        val candidates = LinkedHashMap<String, File>()
        // What is in the cloud already, the emulator's Steam-API store, and the game's save rules.
        cloudFiles.keys.forEach { n -> p.local(n)?.takeIf { it.isFile }?.let { candidates[n] = it } }
        if (p.remote.isDirectory) p.remote.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.forEach { f ->
            candidates.putIfAbsent(f.relativeTo(p.remote).path.replace(File.separatorChar, '/'), f)
        }
        runCatching { ruleFiles(p, candidates) }
        val todo = candidates.filter { (name, f) ->
            if (f.length() > MAX_UPLOAD) return@filter false
            val c = cloudFiles[name] ?: return@filter true
            !sha1(f).contentEquals(c.sha) && f.lastModified() + 2_000 >= c.time // a newer cloud copy is never overwritten
        }
        if (todo.isEmpty()) { Log.i(TAG, "${p.appId}: nothing to upload"); return }
        val batch = sc.beginAppUploadBatch(p.appId, "YurNLator (${Build.MODEL})", todo.keys.toList(), emptyList(), 0L, 0L)
            .get(JOB_S, TimeUnit.SECONDS).batchID
        var sent = 0
        for ((name, f) in todo) if (runCatching { upload(sc, p.appId, f, name, batch) }.onFailure { Log.w(TAG, "upload $name", it) }.getOrDefault(false)) sent++
        runCatching { sc.completeAppUploadBatch(p.appId, batch, if (sent == todo.size) EResult.OK else EResult.Fail).get(JOB_S, TimeUnit.SECONDS) }
        Log.i(TAG, "${p.appId}: $sent/${todo.size} saves uploaded")
        if (sent > 0) toast(context, uiStr(R.string.l_sts_cloud_up, sent))
    }

    /** A number stat of the game's schema (int / float; "permission" ≥ 2 = only its servers may set it). */
    private class NumStat(val id: Int, val name: String, val float: Boolean, val default: String, val permission: Int)

    private fun numericStats(cb: UserStatsCallback): Map<Int, NumStat> {
        val out = LinkedHashMap<Int, NumStat>()
        cb.schemaKeyValues?.get("stats")?.children?.forEach { st ->
            val id = st.name?.toIntOrNull() ?: return@forEach
            val type = st.get("type").value
            if (type != "1" && type != "2") return@forEach
            val name = st.get("name").value?.takeIf { it.isNotBlank() } ?: return@forEach
            out[id] = NumStat(id, name, type == "2", st.get("default").value ?: "0", st.get("permission").value?.toIntOrNull() ?: 0)
        }
        return out
    }

    private fun kvToJson(kv: `in`.dragonbra.javasteam.types.KeyValue): Any =
        if (kv.children.isEmpty()) (kv.value ?: "") else JSONObject().apply { kv.children.forEach { c -> c.name?.let { put(it, kvToJson(c)) } } }

    /** Each achievement's link to a stat ("unlocks when clicks reach 10"), as the emulator reads it. */
    private fun achievementProgress(cb: UserStatsCallback): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        cb.schemaKeyValues?.get("stats")?.children?.forEach { st ->
            if (st.get("type").value != "4") return@forEach
            st.get("bits").children.forEach { bit ->
                val name = bit.get("name").value ?: return@forEach
                val pr = bit.get("progress")
                if (pr.children.isNotEmpty()) (kvToJson(pr) as? JSONObject)?.let { out[name] = it }
            }
        }
        return out
    }

    /** The emulator's file for a stat (it may keep the name in another case). */
    private fun statFile(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }

    private fun readStat(f: File): Int? = runCatching {
        val b = f.readBytes()
        if (b.size < 4) null else java.nio.ByteBuffer.wrap(b, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    }.getOrNull()

    /** Achievements that only the game's own servers may give (their stat is protected). */
    private fun protectedAchievements(cb: UserStatsCallback): Set<String> {
        val out = HashSet<String>()
        cb.schemaKeyValues?.get("stats")?.children?.forEach { st ->
            if (st.get("type").value != "4") return@forEach
            if ((st.get("permission").value?.toIntOrNull() ?: 0) < 2) return@forEach
            st.get("bits").children.forEach { bit -> bit.get("name").value?.let { out += it } }
        }
        return out
    }

    /**
     * Number stats the emulator counted higher than the profile has (clicks, kills…): sent to the
     * profile, where Steam unlocks the achievements tied to them. Returns how many went in.
     */
    private fun statsOut(p: Paths): Int {
        val dir = File(p.gse, "stats")
        if (!dir.isDirectory) return 0
        val (us, cb) = userStats(p.appId) ?: return 0
        val current = HashMap<Int, Int>().apply { cb.stats.forEach { put(it.statId, it.statValue) } }
        val changed = ArrayList<Stats>()
        for (st in numericStats(cb).values) {
            if (st.permission >= 2) continue
            val v = statFile(dir, st.name)?.let { readStat(it) } ?: continue
            val cur = current[st.id] ?: 0
            val higher = if (st.float) Float.fromBits(v) > Float.fromBits(cur) else v > cur
            if (higher) changed += Stats(st.id, v)
        }
        if (changed.isEmpty()) return 0
        val self = SteamID(SteamSession.steamId)
        val before = runCatching { cb.getExpandedAchievements() }.getOrDefault(emptyList()).count { it.isUnlocked }
        val ok = runCatching { us.storeUserStats(p.appId, changed, self, self, cb.crcStats, false).toFuture().get(JOB_S, TimeUnit.SECONDS).result == EResult.OK }
            .getOrDefault(false)
        Log.i(TAG, "${p.appId}: ${changed.size} stat(s) to the profile: $ok")
        if (!ok) return -1
        // What Steam unlocked by the new values.
        val after = runCatching { userStats(p.appId)?.second?.getExpandedAchievements()?.count { it.isUnlocked } }.getOrNull() ?: before
        return maxOf(0, after - before)
    }

    private fun achievementsOut(context: Context, p: Paths) {
        // Stats first: some achievements come from them alone.
        val byStats = runCatching { statsOut(p) }.onFailure { Log.w(TAG, "${p.appId}: stats", it) }.getOrDefault(0)
        if (byStats > 0) toast(context, uiStr(R.string.l_sts_ach_sent, byStats))
        val local = runCatching { JSONObject(File(p.gse, "achievements.json").readText()) }.getOrNull() ?: return
        val earned = local.keys().asSequence().filter { k ->
            val o = local.optJSONObject(k)
            o != null && (o.optBoolean("earned", false) || o.optInt("earned", 0) != 0)
        }.toSet()
        if (earned.isEmpty()) return
        val (us, cb) = userStats(p.appId) ?: return
        val locked = protectedAchievements(cb)
        // apiName -> (stat, bit) from the schema; each achievement is one bit of a stat.
        val bitOf = HashMap<String, Pair<Int, Int>>()
        cb.schemaKeyValues?.get("stats")?.children?.forEach { stat ->
            val statId = stat.name?.toIntOrNull() ?: return@forEach
            stat.get("bits").children.forEach { bit ->
                val i = bit.name?.toIntOrNull() ?: return@forEach
                bit.get("name").value?.takeIf { it.isNotEmpty() }?.let { bitOf[it] = statId to i }
            }
        }
        val already = runCatching { cb.getExpandedAchievements() }.getOrDefault(emptyList()).filter { it.isUnlocked }.mapNotNull { it.name }.toSet()
        val current = HashMap<Int, Int>().apply { cb.stats.forEach { put(it.statId, it.statValue) } }
        val changed = HashMap<Int, Int>()
        var count = 0
        val serverOnly = (earned - already).filter { it in locked }
        for (name in earned - already) {
            if (name in locked) continue
            val (stat, bit) = bitOf[name] ?: continue
            if (bit !in 0..31) continue
            changed[stat] = (changed[stat] ?: current[stat] ?: 0) or (1 shl bit)
            count++
        }
        if (changed.isEmpty()) {
            // Earned in the game, but Steam takes them from the game's own servers only.
            if (serverOnly.isNotEmpty()) toast(context, uiStr(R.string.l_sts_ach_server_only, serverOnly.size))
            return
        }
        val self = SteamID(SteamSession.steamId)
        val list = changed.map { (id, v) -> Stats(id, v) }
        var ok = runCatching { us.storeUserStats(p.appId, list, self, self, cb.crcStats, false).toFuture().get(JOB_S, TimeUnit.SECONDS).result == EResult.OK }
            .getOrDefault(false)
        if (!ok) {
            // Some apps take stats only while "in game": say so for the moment of the write.
            val apps = SteamSession.apps
            runCatching {
                apps?.notifyGamesPlayed(listOf(GamePlayedInfo(gameId = p.appId.toLong(), processId = 0, ownerId = SteamSession.steamId.and(0xFFFFFFFFL).toInt(), gameBuildId = 0)), `in`.dragonbra.javasteam.enums.EOSType.Windows10)
                Thread.sleep(1500)
                ok = us.storeUserStats(p.appId, list, self, self, cb.crcStats, false).toFuture().get(JOB_S, TimeUnit.SECONDS).result == EResult.OK
            }
            runCatching { apps?.notifyGamesPlayed(emptyList(), `in`.dragonbra.javasteam.enums.EOSType.Windows10) }
        }
        Log.i(TAG, "${p.appId}: $count achievement(s) to the profile: $ok")
        if (ok) toast(context, uiStr(R.string.l_sts_ach_sent, count))
        else toast(context, uiStr(R.string.l_sts_ach_refused, count))
    }

    /** One achievement of the game, as on the Steam profile. */
    data class Achievement(val name: String, val title: String, val description: String, val icon: String?,
                           val unlocked: Boolean, val time: Long, val hidden: Boolean,
                           /** The coloured (earned) picture, whatever the profile's state. */
                           val iconEarned: String? = null)

    /** The game's achievements with the profile's progress (blocking; null when offline or the game has none). */
    fun achievements(context: Context, appId: Int): List<Achievement>? = runCatching {
        if (!SteamSession.awaitOnline(context, 20_000L)) return null
        val (_, cb) = userStats(appId) ?: return null
        val list = runCatching { cb.getExpandedAchievements(Goldberg.language()) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: cb.getExpandedAchievements()
        list.mapNotNull { a ->
            val name = a.name ?: return@mapNotNull null
            val file = (if (a.isUnlocked) a.icon else a.iconGray)?.takeIf { it.isNotBlank() }
            Achievement(name, a.displayName?.ifBlank { null } ?: name, a.description ?: "",
                file?.let { "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/$appId/$it" },
                a.isUnlocked, a.unlockTimestamp.toLong(), a.hidden,
                a.icon?.takeIf { it.isNotBlank() }?.let { "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/$appId/$it" })
        }.sortedWith(compareByDescending<Achievement> { it.unlocked }.thenByDescending { it.time })
    }.onFailure { Log.w(TAG, "achievements $appId", it) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() } }
    }
}
