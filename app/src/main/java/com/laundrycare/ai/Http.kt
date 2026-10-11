package com.laundrycare.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val JSON_MEDIA = "application/json".toMediaType()

/** One client for both providers: shared pool and timeouts. */
internal val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // The reply arrives in one piece at the end, so this is the whole
        // reading time, thinking included.
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
}

/** Runs the call so that cancelling the coroutine cancels the request. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, unread, _ -> unread.close() }
        }
    })
}

internal fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

internal fun networkError(e: Exception): AiException =
    AiException("Network error reaching the model: ${e.message ?: e.javaClass.simpleName}. Check your connection.", retryable = true)
