@file:JvmName("ExecuteAsyncKt")

/*
 * okhttp3.coroutines.executeAsync for JavaSteam, from Bannerlator (GPL-3.0,
 * store/compat/ExecuteAsyncKt.kt). The okhttp-coroutines artifact is excluded in app/build.gradle;
 * this is its only provider. suspendCoroutine needs nothing beyond kotlin-stdlib.
 */
package okhttp3.coroutines

import kotlin.coroutines.suspendCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

@Suppress("RedundantSuspendModifier")
suspend fun Call.executeAsync(): Response = suspendCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resumeWith(Result.success(response))
        }
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWith(Result.failure(e))
        }
    })
}
