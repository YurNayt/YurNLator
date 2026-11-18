package com.winlator.cmod.store.epic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.ui.graphics.Color
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.store.StoreNet
import com.winlator.cmod.ui.ingame.AchievementToast
import com.winlator.cmod.ui.uiStr
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An in-game card, in Epic's look, when a game from the Epic library earns an achievement.
 *
 * The game itself reports it to Epic (Epic Online Services, signed in through the exchange code
 * that [EpicLaunch] passes), so the account's record on Epic's servers is the truth: while the game
 * runs it is asked every [POLL_MS] which achievements are unlocked, and every new one gets a card
 * with its picture, name, description and XP (the pill's dot in the tier's colour). The list of the
 * game's achievements (names in the device's language) is fetched once and kept in
 * files/epic/achievements/. Same GraphQL as the Epic launcher (launcher.store.epicgames.com); the
 * game's sandbox is its catalog namespace. What happened goes to epic_achievements.txt beside the logs.
 */
object EpicAchievements {
    private const val TAG = "YurnEpicAch"
    private const val GRAPHQL = "https://launcher.store.epicgames.com/graphql"
    private const val POLL_MS = 15_000L
    private const val SHOWN_MS = 5_000L
    @Volatile private var thread: Thread? = null
    @Volatile private var traceFile: File? = null

    class Ach(val name: String, val title: String, val description: String, val icon: String?, val xp: Int, val tier: Int?)

    private const val SCHEMA_Q = """query Achievement(${'$'}sandboxId: String!, ${'$'}locale: String!) {
  Achievement { productAchievementsRecordBySandbox(sandboxId: ${'$'}sandboxId, locale: ${'$'}locale) {
    totalAchievements
    achievements { achievement { name hidden unlockedDisplayName unlockedDescription lockedDescription unlockedIconLink XP tier { hexColor } } }
  } } }"""

    private const val PLAYER_Q = """query PlayerAchievement(${'$'}epicAccountId: String!, ${'$'}sandboxId: String!) {
  PlayerAchievement { playerAchievementGameRecordsBySandbox(epicAccountId: ${'$'}epicAccountId, sandboxId: ${'$'}sandboxId) {
    records { totalUnlocked playerAchievements { playerAchievement { achievementName unlocked } } }
  } } }"""

    @JvmStatic
    fun start(context: Context, root: FrameLayout?, shortcut: Shortcut?) {
        val app = shortcut?.getExtra("epicApp", "").orEmpty()
        if (app.isEmpty() || root == null) return
        val ctx = context.applicationContext
        if (!EpicAccount.signedIn(ctx)) return
        stop()
        val t = Thread({
            try { watch(ctx, root, app) }
            catch (_: InterruptedException) {}
            catch (t: Throwable) { Log.w(TAG, "watcher", t); trace(ctx, "$app: watcher stopped: $t") }
        }, "EpicAchievements")
        t.isDaemon = true
        thread = t
        t.start()
    }

    @JvmStatic
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun watch(ctx: Context, root: FrameLayout, app: String) {
        val ns = namespaceOf(ctx, app)
        if (ns.isNullOrEmpty()) { trace(ctx, "$app: no namespace in the Epic library, not watching"); return }
        val account = EpicAccount.accountId(ctx)
        var schema: Map<String, Ach>? = null
        var known: Set<String>? = null
        val queue = LinkedBlockingQueue<String>()
        var polls = 0
        trace(ctx, "$app: watching achievements (sandbox $ns)")
        while (!Thread.currentThread().isInterrupted) {
            val name = queue.poll()
            if (name == null) {
                if (polls++ > 0) Thread.sleep(POLL_MS)
                val now = unlocked(ctx, ns, account) ?: continue
                if (known == null) {
                    known = now
                    if (schema == null) schema = schema(ctx, ns)
                    trace(ctx, "$app: ${now.size} unlocked of ${schema?.size ?: "?"} at the start")
                    if (schema.isNullOrEmpty()) { trace(ctx, "$app: the game has no Epic achievements"); return }
                } else {
                    val fresh = now - known
                    if (fresh.isNotEmpty()) {
                        trace(ctx, "$app: earned ${fresh.joinToString()}")
                        fresh.forEach { queue.add(it) }
                        known = known + now
                    }
                }
                continue
            }
            val a = schema?.get(name) ?: Ach(name, name, "", null, 0, null)
            val picture = a.icon?.let { runCatching { download(it) }.getOrNull() }
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                AchievementToast.show(root, AchievementToast.Look.EPIC, uiStr(R.string.l_ach_unlocked), a.title,
                    a.description.takeIf { it.isNotBlank() && it != a.title }, picture, SHOWN_MS,
                    a.xp.takeIf { it > 0 }?.let { "+$it XP" }, a.tier?.let { Color(it) }) { done.countDown() }
            }
            done.await(SHOWN_MS * 4, TimeUnit.MILLISECONDS)
        }
    }

    private fun namespaceOf(ctx: Context, app: String): String? {
        EpicLibrary.loadCached(ctx)
        return EpicLibrary.games.value?.firstOrNull { it.appName == app }?.namespace
    }

    private fun graphql(ctx: Context, query: String, vars: JSONObject, auth: Boolean): JSONObject? {
        val token = if (auth) EpicAccount.token(ctx) ?: return null else null
        val body = JSONObject().put("query", query).put("variables", vars).toString()
            .toRequestBody("application/json".toMediaType())
        val b = Request.Builder().url(GRAPHQL).post(body).header("User-Agent", EpicAccount.USER_AGENT)
        if (token != null) b.header("Authorization", "bearer $token")
        return runCatching {
            StoreNet.client.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) { trace(ctx, "graphql HTTP ${r.code}: ${text.take(200)}"); return null }
                val j = JSONObject(text)
                j.optJSONArray("errors")?.let { if (it.length() > 0) trace(ctx, "graphql errors: ${it.toString().take(300)}") }
                j.optJSONObject("data")
            }
        }.onFailure { trace(ctx, "graphql failed: $it") }.getOrNull()
    }

    /** Names of the achievements the account has unlocked in this game; null when Epic didn't answer. */
    private fun unlocked(ctx: Context, ns: String, account: String): Set<String>? {
        val data = graphql(ctx, PLAYER_Q, JSONObject().put("epicAccountId", account).put("sandboxId", ns), auth = true) ?: return null
        val records = data.optJSONObject("PlayerAchievement")?.optJSONObject("playerAchievementGameRecordsBySandbox")
            ?.optJSONArray("records") ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until records.length()) {
            val list = records.optJSONObject(i)?.optJSONArray("playerAchievements") ?: continue
            for (k in 0 until list.length()) {
                val p = list.optJSONObject(k)?.optJSONObject("playerAchievement") ?: continue
                if (p.optBoolean("unlocked", false)) p.optString("achievementName").takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out
    }

    private fun cacheFile(ctx: Context, ns: String, locale: String) = File(ctx.filesDir, "epic/achievements/${ns}_$locale.json")

    /** The game's achievements by name, in the device's language when Epic has it, English otherwise. */
    private fun schema(ctx: Context, ns: String): Map<String, Ach> {
        val lang = Locale.getDefault().language.ifBlank { "en" }
        for (locale in listOf(lang, "en").distinct()) {
            val file = cacheFile(ctx, ns, locale)
            val cached = runCatching { JSONObject(file.readText()) }.getOrNull()
                ?.takeIf { System.currentTimeMillis() - file.lastModified() < 7L * 24 * 3600 * 1000 }
            val rec = cached ?: graphql(ctx, SCHEMA_Q, JSONObject().put("sandboxId", ns).put("locale", locale), auth = false)
                ?.optJSONObject("Achievement")?.optJSONObject("productAchievementsRecordBySandbox")
                ?.also { r -> runCatching { file.parentFile?.mkdirs(); file.writeText(r.toString()) } }
            val parsed = rec?.let { parse(it) }.orEmpty()
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyMap()
    }

    private fun parse(rec: JSONObject): Map<String, Ach> {
        val arr = rec.optJSONArray("achievements") ?: return emptyMap()
        val out = LinkedHashMap<String, Ach>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i)?.optJSONObject("achievement") ?: continue
            val name = a.optString("name").takeIf { it.isNotEmpty() } ?: continue
            val tier = a.optJSONObject("tier")?.optString("hexColor")?.takeIf { it.isNotBlank() }?.let { hex ->
                runCatching { android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") }.getOrNull()
            }
            out[name] = Ach(name, a.optString("unlockedDisplayName").ifBlank { name },
                a.optString("unlockedDescription").ifBlank { a.optString("lockedDescription") },
                a.optString("unlockedIconLink").takeIf { it.startsWith("http") }, a.optInt("XP", 0), tier)
        }
        return out
    }

    private fun download(url: String): Bitmap? =
        StoreNet.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()?.let { decodeSmall(it) }
        }

    /** Epic's pictures can be large; the card needs about 128 px. */
    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 128 && o.outHeight / (s * 2) >= 128) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun trace(context: Context, line: String) {
        Log.i(TAG, line)
        runCatching {
            val f = traceFile ?: File(com.winlator.cmod.widget.LogView.getLogFile(context).parentFile, "epic_achievements.txt")
                .also { traceFile = it }
            if (f.length() > 128 * 1024) f.writeText(f.readLines().takeLast(300).joinToString("\n", postfix = "\n"))
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(java.util.Date())
            f.appendText("$time  $line\n")
        }
    }
}
