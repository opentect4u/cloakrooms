package com.synergic.cloakroom.data.remote

import com.synergic.cloakroom.data.session.TokenStore
import com.synergic.cloakroom.domain.remote.ServerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where the API lives: `<baseUrl>/api/v1` (API_GUIDE.md §0). */
data class ApiConfig(val baseUrl: String)

/** One page of a list endpoint: `data` plus `meta { page, limit, total }`. */
data class Page<T>(val items: List<T>, val page: Int, val limit: Int, val total: Int)

/** A report page: rows, the number of rows in the whole result and the server's totals (`meta.totals`). */
data class ReportPage<T>(val items: List<T>, val total: Int, val totals: JsonObject?)

/**
 * Tells the auth repository that the server ended the session (logged out elsewhere, force
 * logout, revoked token), so the app goes back to login.
 */
@Singleton
class AuthEvents @Inject constructor() {
    private val _sessionEnded = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sessionEnded: SharedFlow<String> = _sessionEnded.asSharedFlow()

    fun sessionEnded(message: String) {
        _sessionEnded.tryEmit(message)
    }

    private val _passwordChangeRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The server answered 403 PASSWORD_CHANGE_REQUIRED: open the change-password screen. */
    val passwordChangeRequired: SharedFlow<Unit> = _passwordChangeRequired.asSharedFlow()

    fun passwordChangeRequired() {
        _passwordChangeRequired.tryEmit(Unit)
    }
}

/**
 * The only place that talks HTTP. Reads the common envelope `{ success, message, code, data,
 * meta, errors, requestId }` (§2) and turns every failure into [ServerException]. Adds the bearer
 * token, refreshes it once on `TOKEN_EXPIRED` (one refresh at a time, §3) and sends an
 * `X-Request-Id`. Tokens and passwords are never logged.
 */
@Singleton
class ApiClient @Inject constructor(
    private val config: ApiConfig,
    private val tokens: TokenStore,
    private val events: AuthEvents,
) {
    internal var http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val refreshMutex = Mutex()

    data class Response(val data: JsonElement, val meta: JsonObject?)

    suspend fun <T> get(path: String, serializer: DeserializationStrategy<T>, query: Map<String, Any?> = emptyMap(), auth: Boolean = true): T =
        json.decodeFromJsonElement(serializer, send("GET", path, null, query, auth).data)

    suspend fun <T> getPage(path: String, itemSerializer: DeserializationStrategy<T>, query: Map<String, Any?> = emptyMap()): Page<T> {
        val r = send("GET", path, null, query, auth = true)
        val items = (r.data as? JsonArray).orEmpty().map { json.decodeFromJsonElement(itemSerializer, it) }
        fun meta(name: String, default: Int) = r.meta?.get(name)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: default
        return Page(items, meta("page", 1), meta("limit", items.size), meta("total", items.size))
    }

    /**
     * A report page (API_GUIDE.md §9): rows plus `meta.total` and `meta.totals`. [headers] carries
     * `X-Report-Password` when REPORT_PWD_FLAG = Y.
     */
    suspend fun <T> getReport(
        path: String, itemSerializer: DeserializationStrategy<T>, query: Map<String, Any?>, headers: Map<String, String> = emptyMap(),
    ): ReportPage<T> {
        val r = send("GET", path, null, query, auth = true, headers = headers)
        val items = (r.data as? JsonArray).orEmpty().map { json.decodeFromJsonElement(itemSerializer, it) }
        val total = r.meta?.get("total")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: items.size
        return ReportPage(items, total, r.meta?.get("totals") as? JsonObject)
    }

    suspend fun <T> post(path: String, body: JsonElement?, serializer: DeserializationStrategy<T>, auth: Boolean = true): T =
        json.decodeFromJsonElement(serializer, send("POST", path, body, emptyMap(), auth).data)

    suspend fun <T> patch(path: String, body: JsonElement, serializer: DeserializationStrategy<T>): T =
        json.decodeFromJsonElement(serializer, send("PATCH", path, body, emptyMap(), auth = true).data)

    /** Soft delete (§2): the server marks the row is_deleted = Y. */
    suspend fun delete(path: String) {
        send("DELETE", path, null, emptyMap(), auth = true)
    }

    /**
     * multipart/form-data POST (logo upload): [fields] as text parts plus one file part. A new
     * body is built for each try, so a retry after a token refresh sends the file again.
     */
    suspend fun <T> postMultipart(
        path: String, fields: Map<String, String>, fileField: String, fileName: String, bytes: ByteArray, mediaType: String,
        serializer: DeserializationStrategy<T>,
    ): T {
        val build = {
            MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (k, v) -> addFormDataPart(k, v) }
                addFormDataPart(fileField, fileName, bytes.toRequestBody(mediaType.toMediaType()))
            }.build()
        }
        return json.decodeFromJsonElement(serializer, send("POST", path, null, emptyMap(), auth = true, raw = build).data)
    }

    suspend fun send(
        method: String, path: String, body: JsonElement?, query: Map<String, Any?>, auth: Boolean,
        raw: (() -> RequestBody)? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val usedToken = if (auth) tokens.accessToken else null
        try {
            return execute(method, path, body, query, usedToken, raw, headers)
        } catch (e: ServerException) {
            if (!auth) throw e
            if (e.code == ServerException.TOKEN_EXPIRED) {
                refresh(usedToken)
                return try {
                    execute(method, path, body, query, tokens.accessToken, raw, headers)
                } catch (again: ServerException) {
                    throw endSessionIfOver(again)
                }
            }
            throw endSessionIfOver(e)
        }
    }

    private fun endSessionIfOver(e: ServerException): ServerException {
        if (e.code in ServerException.SESSION_OVER) events.sessionEnded(sessionEndedMessage(e))
        if (e.code == ServerException.PASSWORD_CHANGE_REQUIRED) events.passwordChangeRequired()
        return e
    }

    /** POST /auth/refresh once for everybody who got TOKEN_EXPIRED with the same token. */
    private suspend fun refresh(expiredToken: String?) = refreshMutex.withLock {
        if (tokens.accessToken != expiredToken) return@withLock // someone else refreshed already
        val refreshToken = tokens.refreshToken ?: throw endSessionIfOver(
            ServerException(ServerException.UNAUTHORIZED, "Please log in again.", 401)
        )
        val data = try {
            execute("POST", "/auth/refresh", buildJsonObject { put("refreshToken", refreshToken) }, emptyMap(), null).data
        } catch (e: ServerException) {
            if (e.code != ServerException.NETWORK) events.sessionEnded(sessionEndedMessage(e))
            throw e
        }
        val pair = json.decodeFromJsonElement(TokenPairDto.serializer(), data)
        tokens.saveTokens(pair.accessToken, pair.refreshToken)
    }

    private suspend fun execute(
        method: String, path: String, body: JsonElement?, query: Map<String, Any?>, token: String?,
        raw: (() -> RequestBody)? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        withContext(Dispatchers.IO) {
            val url = "${config.baseUrl.trimEnd('/')}$API_PREFIX$path".toHttpUrl().newBuilder().apply {
                query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
            }.build()
            val requestBody = when {
                raw != null -> raw()
                body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_TYPE)
                method == "GET" || method == "DELETE" -> null
                else -> "".toRequestBody(JSON_TYPE)
            }
            val request = Request.Builder()
                .url(url)
                .method(method, requestBody)
                .header("Accept", "application/json")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val (status, text) = try {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw ServerException(ServerException.NETWORK, "Cannot reach the server. Check the internet connection and try again.")
            }
            parse(status, text)
        }

    companion object {
        const val API_PREFIX = "/api/v1"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        val json = Json {
            ignoreUnknownKeys = true
            // Settings values may come as numbers ("30" or 30).
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }

        /** Reads the envelope; returns data + meta on success, throws [ServerException] otherwise. */
        fun parse(status: Int, text: String): Response {
            val envelope = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw ServerException("HTTP_$status", "The server answered with an error ($status). Try again later.", status)
            val success = envelope["success"]?.jsonPrimitive?.booleanOrNull == true
            if (success && status in 200..299) {
                return Response(envelope["data"] ?: JsonNull, envelope["meta"] as? JsonObject)
            }
            val code = envelope.text("code") ?: "HTTP_$status"
            val message = envelope.text("message") ?: "Request failed ($status)."
            val errors = envelope["errors"]
            val fieldErrors = (errors as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val path = o.text("path") ?: return@mapNotNull null
                path to (o.text("message") ?: "Invalid")
            }.toMap()
            val details = (errors as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }.orEmpty()
            throw ServerException(code, message, status, fieldErrors, details, envelope.text("requestId"))
        }

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        fun sessionEndedMessage(e: ServerException): String = when (e.code) {
            ServerException.SESSION_ENDED -> "Your session was ended (logged out by an admin or on another device). Please log in again."
            ServerException.TOKEN_REUSED -> "Your session was closed for safety. Please log in again."
            ServerException.SUBSCRIPTION_EXPIRED -> "The subscription has ended. Please contact Synergic."
            else -> "Please log in again."
        }
    }
}
