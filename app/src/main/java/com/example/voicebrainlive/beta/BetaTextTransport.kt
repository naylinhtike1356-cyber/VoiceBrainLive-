package com.example.voicebrainlive.beta

import com.example.voicebrainlive.agent.brain.BrainFailure
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** Text disclosure review only, never a tool/biometric grant. Not serializable or restored. */
internal class BetaTextReview private constructor(val request: BetaTextRequest, val createdAtMillis: Long, val expiresAtMillis: Long) {
    private val claimed = AtomicBoolean(false)
    internal fun claimOnce() = claimed.compareAndSet(false, true)
    override fun toString() = "BetaTextReview(content=<redacted>)"

    companion object {
        fun prepare(model: String, goal: String, reviewed: Boolean, nowMillis: Long): BetaTextReview {
            if (!reviewed) throw BrainFailure("PILOT_TEXT_REVIEW_REQUIRED")
            require(nowMillis in 0..Long.MAX_VALUE - 60_000)
            return BetaTextReview(BetaTextProtocol.request(model, goal), nowMillis, nowMillis + 60_000)
        }
        fun prepareChat(model:String,text:String,reviewed:Boolean,nowMillis:Long):BetaTextReview {
            if(!reviewed)throw BrainFailure("PILOT_TEXT_REVIEW_REQUIRED")
            require(nowMillis in 0..Long.MAX_VALUE-60_000)
            return BetaTextReview(BetaTextProtocol.chatRequest(model,text),nowMillis,nowMillis+60_000)
        }
    }
}

/**
 * Prepared transport, NOT connected to the APK's provider factory. Default authorization is OFF.
 * Host tests inject a fake Call.Factory. A later approved composition must supply a fail-closed
 * credential getter and foreground session ownership; no credential is collected by this phase.
 */
internal class BetaGeminiTextExchange(
    private val review: BetaTextReview,
    private val credential: () -> String,
    private val current: () -> Boolean,
    private val networkAuthorized: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val calls: Call.Factory? = null
) : BetaTextExchange {
    private fun checkActive() {
        if (!networkAuthorized()) throw BrainFailure("PILOT_LIVE_APPROVAL_REQUIRED")
        if (!current()) throw BrainFailure("PILOT_STALE_SESSION")
        if (now() !in review.createdAtMillis until review.expiresAtMillis) throw BrainFailure("PILOT_REVIEW_EXPIRED")
    }

    override suspend fun exchange(request: BetaTextRequest): Pair<Int, String> {
        currentCoroutineContext().ensureActive()
        checkActive()
        if (request != review.request) throw BrainFailure("PILOT_CHANGED_REQUEST")
        if (!review.claimOnce()) throw BrainFailure("PILOT_SESSION_OR_CALL_LIMIT")
        val key = try {
            credential().also { if (!BetaTextProtocol.validKey(it)) throw IllegalArgumentException() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            throw BrainFailure("PILOT_SECURE_CREDENTIAL_UNAVAILABLE")
        }
        // Recheck after credential retrieval. Never fall back to BuildConfig/plain preferences.
        checkActive()
        val bytes = request.body.toByteArray(Charsets.UTF_8)
        if (bytes.size > 16_384) throw BrainFailure("PILOT_REQUEST_BYTES")
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true // Prevent HTTP follow-ups, including a 503 Retry-After: 0.
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val http = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${request.model}:generateContent")
            .header("x-goog-api-key", key) // Never put a credential in a URL/query string.
            .header("Accept", "application/json")
            .post(body).build()
        val call = try { (calls ?: lockedClient()).newCall(http) }
        catch (_: Exception) { throw BrainFailure("PILOT_PROVIDER_UNAVAILABLE") }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            val delivered = AtomicBoolean(false)
            fun fail(code: String) {
                if (delivered.compareAndSet(false, true) && continuation.isActive)
                    continuation.resumeWithException(BrainFailure(code))
            }
            try {
                checkActive()
                if (!continuation.isActive) { call.cancel(); return@suspendCancellableCoroutine }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        fail("PILOT_NETWORK_FAILURE_NO_RETRY") // Do not expose exception/body/key text.
                    }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!continuation.isActive || delivered.get()) return
                            try {
                                checkActive()
                                val result = if (it.code != 200) it.code to "" else {
                                    val b = it.body ?: throw BrainFailure("PILOT_INVALID_OR_UNSAFE_RESPONSE")
                                    val type = b.contentType()
                                    if (type?.type != "application" || type.subtype != "json")
                                        throw BrainFailure("PILOT_INVALID_OR_UNSAFE_RESPONSE")
                                    if (b.contentLength() > MAX_RESPONSE_BYTES)
                                        throw BrainFailure("PILOT_RESPONSE_BYTES")
                                    val data = ByteArrayOutputStream()
                                    val buffer = ByteArray(4096)
                                    b.byteStream().use { input ->
                                        while (true) {
                                            checkActive()
                                            if (!continuation.isActive) { call.cancel(); return }
                                            val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - data.size()))
                                            if (read == -1) break
                                            data.write(buffer, 0, read)
                                            if (data.size() > MAX_RESPONSE_BYTES) throw BrainFailure("PILOT_RESPONSE_BYTES")
                                        }
                                    }
                                    200 to data.toByteArray().decodeToString(throwOnInvalidSequence = true)
                                }
                                checkActive()
                                if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(result)
                            } catch (safe: BrainFailure) { fail(safe.code) }
                            catch (_: Exception) { fail("PILOT_INVALID_OR_UNSAFE_RESPONSE") }
                        }
                    }
                })
            } catch (safe: BrainFailure) { call.cancel(); fail(safe.code) }
            catch (_: Exception) { call.cancel(); fail("PILOT_PROVIDER_UNAVAILABLE") }
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 32_768
        internal fun lockedClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null)
            .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
            .build()
    }
}
