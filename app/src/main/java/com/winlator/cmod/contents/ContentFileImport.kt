package com.winlator.cmod.contents

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import com.winlator.cmod.core.LocalInstalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Installs a component package (.wcp: DXVK, VKD3D, FEXCore, Box64, WOWBox64, Wine/Proton)
 * picked from the phone's storage. Works offline.
 */
object ContentFileImport {
    /** Bumped after every import so open settings screens reload their version lists. */
    val revision = mutableIntStateOf(0)

    sealed class Result {
        /** Installed (or already installed). [type] is what the package really is. */
        data class Ok(val versionName: String, val type: String) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun import(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val manager = ContentsManager(context)
        runCatching { manager.syncContents() }
        suspendCancellableCoroutine { cont ->
            fun done(r: Result) { if (cont.isActive) cont.resume(r) }
            manager.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) =
                    done(Result.Failed(failureText(reason)))

                override fun onSucceed(extracted: ContentProfile) {
                    val ok = Result.Ok(extracted.verName.orEmpty(), extracted.type.toString())
                    manager.finishInstallContent(extracted, object : ContentsManager.OnInstallFinishedCallback {
                        override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                            if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) finish(context, ok)
                            done(if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) ok else Result.Failed(failureText(reason)))
                        }

                        override fun onSucceed(profile: ContentProfile) {
                            finish(context, ok)
                            done(ok)
                        }
                    })
                }
            })
        }
    }

    private fun finish(context: Context, ok: Result.Ok) {
        LocalInstalls.mark(context, ok.type, ok.versionName)
        revision.intValue++
    }

    private fun failureText(reason: ContentsManager.InstallFailedReason) = when (reason) {
        ContentsManager.InstallFailedReason.ERROR_NOSPACE -> "Not enough free space"
        ContentsManager.InstallFailedReason.ERROR_BADTAR, ContentsManager.InstallFailedReason.ERROR_NOPROFILE ->
            "Not a component package (.wcp)"
        else -> "Installation failed: $reason"
    }
}
