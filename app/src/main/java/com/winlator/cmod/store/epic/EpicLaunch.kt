package com.winlator.cmod.store.epic

import android.content.Context
import android.util.Log
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.store.StoreNet
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

/**
 * What the Epic launcher passes to a game: a one-time sign-in code (so games with Epic online
 * services / DRM see the account) and the account's id and name. The code lives a few minutes, so
 * it is made right before the game starts and only put into this launch, never saved. As Legendary.
 */
object EpicLaunch {
    private const val TAG = "YurnEpicLaunch"
    private const val EXCHANGE = "https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/exchange"

    @JvmStatic
    fun beforeLaunch(context: Context, shortcut: Shortcut?) {
        val app = shortcut?.getExtra("epicApp", "").orEmpty()
        if (app.isEmpty()) return
        runCatching {
            val token = EpicAccount.token(context) ?: return
            val code = StoreNet.client.newCall(Request.Builder().url(EXCHANGE).header("Authorization", "Bearer $token")
                .header("User-Agent", EpicAccount.USER_AGENT).build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body?.string() ?: "").optString("code") else null
            }
            if (code.isNullOrEmpty()) return
            val name = EpicAccount.name.value ?: context.getSharedPreferences("yurn_epic", Context.MODE_PRIVATE).getString("name", "") ?: ""
            val auth = "-AUTH_LOGIN=unused -AUTH_PASSWORD=$code -AUTH_TYPE=exchangecode -epicapp=$app -epicenv=Prod -EpicPortal " +
                "-epicusername=\"$name\" -epicuserid=${EpicAccount.accountId(context)} -epiclocale=${Locale.getDefault().language}"
            val own = shortcut!!.getExtra("execArgs", "")
            shortcut.putExtra("execArgs", (own + " " + auth).trim()) // this launch only: the shortcut isn't saved
        }.onFailure { Log.w(TAG, "launch args", it) }
    }
}
