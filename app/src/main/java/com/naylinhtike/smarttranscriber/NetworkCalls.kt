package com.naylinhtike.smarttranscriber

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Cancellation stays attached until the complete Gemini response arrives. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val contentType = response.body?.contentType()
                val body = response.use { (it.body?.bytes() ?: ByteArray(0)).toResponseBody(contentType) }
                val buffered = response.newBuilder().body(body).build()
                @Suppress("DEPRECATION")
                continuation.resume(buffered) { buffered.close() }
            } catch (e: IOException) {
                response.close()
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        }
    })
}
