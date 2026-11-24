package eu.kanade.tachiyomi.animeextension.en.twodhive

import android.util.Base64
import android.util.Log
import keiyoushi.network.post
import keiyoushi.utils.JSON_MEDIA_TYPE
import keiyoushi.utils.bodyString
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for the BabaStream embed the 2Dhive episode page ships with.
 *
 * The embed does not hand out a stream URL. It declares a per-page-load session — a session id
 * and an AES key — and expects the client to talk to its own API with the session id and an
 * AES/GCM encrypted body. The answer is encrypted the same way, and until a Cap proof of work has
 * been redeemed the API answers with an error asking for one.
 *
 * Everything the client needs is read off the embed page rather than written down, because a
 * redeploy can reshape that page: the session, the AES parameters and the API paths all come from
 * the markup.
 */
class TwoDHiveBabaStream(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val scope: CoroutineScope,
) {

    /** The session the embed page declares. */
    class Session(
        val sessionId: String,
        /** The AES key, still base64 encoded, exactly as the page declares it. */
        val key: String,
        val capBase: String?,
    )

    /** The AES parameters the page's own crypto calls use. */
    class Crypto(
        val ivLength: Int,
        val tagBits: Int,
    )

    /** The API paths the embed page calls. */
    class Routes(
        val resolve: String?,
        val verify: String?,
    )

    /** One subtitle track the embed offers alongside the video. */
    class Track(
        val url: String,
        val label: String,
    )

    /** What the embed's API answered. */
    class Payload(
        val type: String?,
        val url: String?,
        val message: String?,
        val tracks: List<Track>,
    )

    /** A redeemed Cap token and the moment it stops being accepted. */
    private class CapToken(val value: String, val expiresAt: Long) {
        fun isValid(): Boolean = expiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()
    }

    private val tokenLock = Mutex()

    @Volatile
    private var spareToken: CapToken? = null

    /**
     * The session the embed page declares.
     *
     * The page declares several `var x = {…}` objects and only one of them is the session: the
     * others are JavaScript object literals with unquoted keys that do not parse as JSON, and the
     * request bodies the page builds are derived from the session itself. Carrying a session id
     * and a key is what identifies the right object, rather than the variable being called `CFG`,
     * so a minifier renaming it costs nothing.
     */
    fun parseSession(html: String): Session? = CONFIG_REGEX.findAll(html).firstNotNullOfOrNull { match ->
        val root = runCatching { jsonInstance.parseToJsonElement(match.groupValues[1]) as? JsonObject }
            .getOrNull()
        val sid = root?.get("sid")?.asString()
        val pk = root?.get("pk")?.asString()
        if (sid.isNullOrBlank() || pk.isNullOrBlank()) return@firstNotNullOfOrNull null

        Session(
            sessionId = sid,
            key = pk,
            capBase = root?.get("cap")?.asString()?.takeIf(String::isNotBlank),
        )
    }

    /**
     * The AES parameters the page uses, read from its own `crypto.subtle` calls.
     *
     * The wire format below — IV first, tag last — is GCM's, and quietly treating a CBC page as
     * GCM would fail after the round trip instead of before it, so the algorithm is checked rather
     * than assumed.
     */
    fun parseCrypto(html: String): Crypto? {
        val algorithm = ALGORITHM_REGEX.find(html)?.groupValues?.get(1)
        if (!algorithm.equals("AES-GCM", ignoreCase = true)) return null

        val ivLength = IV_LENGTH_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..16 }
            ?: return null

        // A tag length left out of a Web Crypto call is the 128-bit default, which is the case
        // here, so its absence is the answer rather than a parse failure.
        val tagBits = TAG_LENGTH_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 128

        return Crypto(ivLength, tagBits)
    }

    /**
     * The resolve and verification endpoints, matched on what they do rather than on their
     * spelling.
     *
     * The page also calls `/api/download-token`, so taking the first `fetch` on the page would be
     * wrong.
     */
    fun parseRoutes(html: String): Routes {
        val routes = FETCH_ROUTE_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        return Routes(
            resolve = routes.firstOrNull { it.contains("resolve", ignoreCase = true) },
            verify = routes.firstOrNull { it.contains("verify", ignoreCase = true) },
        )
    }

    /**
     * Resolves an embed page to the media behind it.
     *
     * @param html the embed page, already downloaded.
     * @param embedUrl the URL the page was downloaded from, used for the `Referer`.
     * @return the decrypted answer of the embed's API.
     */
    suspend fun resolve(html: String, embedUrl: String): Payload? {
        val session = parseSession(html) ?: return null
        val crypto = parseCrypto(html) ?: return null
        val routes = parseRoutes(html)
        val resolveRoute = routes.resolve ?: return null

        val key = runCatching { Base64.decode(session.key, Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.size == 16 || it.size == 24 || it.size == 32 }
            ?: return null

        val origin = embedUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return null

        suspend fun call(route: String, body: String): Payload? {
            val request = BabaRequestDto(
                s = session.sessionId,
                d = TwoDHiveCrypto.encryptAesGcm(key, body, crypto.ivLength),
            )
            val answer = runCatching {
                client.post(
                    "$origin$route",
                    apiHeaders(embedUrl),
                    request.toJsonRequestBody(),
                    ensureSuccess = false,
                ).use { response ->
                    if (!response.isSuccessful) return@use null
                    response.parseAs<BabaEnvelopeDto>().d
                }
            }.getOrNull() ?: return null

            val plain = TwoDHiveCrypto.decryptAesGcm(key, answer, crypto.ivLength, crypto.tagBits)
                ?: return null

            val payload = runCatching { jsonInstance.parseToJsonElement(plain) as? JsonObject }
                .getOrNull() ?: return null

            return Payload(
                type = payload["t"]?.asString(),
                url = payload["u"]?.asString(),
                message = payload["m"]?.asString(),
                tracks = (payload["tracks"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val track = element as? JsonObject ?: return@mapNotNull null
                    val url = track["u"]?.asString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    Track(url, track["label"]?.asString()?.takeIf(String::isNotBlank) ?: "Subtitle")
                },
            )
        }

        var payload = call(resolveRoute, """{"ts":${System.currentTimeMillis()}}""")

        if (payload.needsVerification() && routes.verify != null && session.capBase != null) {
            val token = takeToken(session.capBase)
            if (token != null) {
                val verified = call(
                    routes.verify,
                    """{"ts":${System.currentTimeMillis()},"token":"$token","mode":"invisible"}""",
                )
                if (verified?.type == "ok") {
                    payload = call(resolveRoute, """{"ts":${System.currentTimeMillis()}}""")
                }
            }
        }

        return payload
    }

    /**
     * Starts earning a Cap token in the background.
     *
     * A challenge asks for about five million digests, which is seconds of a phone's whole CPU.
     * Doing that when somebody presses play puts the wait in front of the episode, so it is paid
     * for while the episode list is still on screen.
     */
    fun warmUp(capBase: String?) {
        val base = capBase ?: return
        scope.launch {
            tokenLock.withLock {
                if (spareToken?.isValid() == true) return@withLock
                spareToken = solveToken(base)
            }
        }
    }

    /**
     * A Cap token to verify with.
     *
     * A redeemed token is single-use, so a spare is earned in the background right after one is
     * handed out.
     */
    private suspend fun takeToken(capBase: String): String? {
        val existing = tokenLock.withLock {
            val token = spareToken
            spareToken = null
            token
        }

        if (existing != null && existing.isValid()) {
            warmUp(capBase)
            return existing.value
        }

        val solved = solveToken(capBase)
        if (solved != null) warmUp(capBase)
        return solved?.value
    }

    /**
     * Solves one Cap challenge and redeems it.
     *
     * The challenge asks for a number of proofs, a salt length and a difficulty; both the salt and
     * the target are derived from the challenge token rather than sent, which is why the payload
     * stays tiny while the work does not.
     */
    private suspend fun solveToken(capBase: String): CapToken? {
        val challenge = postJson(
            join(capBase, "challenge"),
            "{}".toRequestBody(JSON_MEDIA_TYPE),
            capBase,
        ) ?: return null

        val token = challenge["token"]?.asString()?.takeIf(String::isNotBlank) ?: return null

        val solutions = solutionsFor(challenge, token) ?: return null

        val redeemed = postJson(
            join(capBase, "redeem"),
            CapRedeemRequestDto(token = token, solutions = solutions).toJsonRequestBody(),
            capBase,
        ) ?: return null

        if (redeemed["success"]?.asBoolean() != true) {
            Log.w(TAG, "Cap redemption failed: ${redeemed["error"]?.asString()}")
            return null
        }

        val value = redeemed["token"]?.asString()?.takeIf(String::isNotBlank) ?: return null

        val expires = redeemed["expires"]?.asLong()
            ?: (System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MS)

        return CapToken(value, expires)
    }

    /**
     * The solutions for whichever challenge form the server sent.
     *
     * The counted form — a token plus `c`/`s`/`d`, with both sides deriving salt and target from
     * it — is what BabaStream serves. Cap also defines a form that sends the salts and targets
     * outright; that branch is written to spec, so it is the fallback rather than the thing tried
     * first.
     */
    private suspend fun solutionsFor(challenge: JsonObject, token: String): JsonArray? {
        val counted = challenge["challenge"] as? JsonObject
        if (counted != null) {
            val count = counted["c"]?.asInt() ?: return null
            val saltLength = counted["s"]?.asInt() ?: return null
            val difficulty = counted["d"]?.asInt() ?: return null
            if (!isAffordable(count, difficulty)) return null

            val proofs = TwoDHiveCrypto.capProofs(token, count, saltLength, difficulty)
            val nonces = withContext(Dispatchers.Default) {
                TwoDHiveCrypto.solveCapProofs(proofs)
            }
            return JsonArray(nonces.map { JsonPrimitive(it) })
        }

        val listed = challenge["challenges"] as? JsonArray ?: return null
        val proofs = listed.mapIndexedNotNull { _, element ->
            val payload = (element as? JsonObject)?.get("payload") as? JsonObject
                ?: return@mapIndexedNotNull null
            val salt = payload["salt"]?.asString() ?: return@mapIndexedNotNull null
            val target = payload["target"]?.asString() ?: return@mapIndexedNotNull null
            salt to target
        }
        if (proofs.isEmpty() || proofs.size != listed.size) return null
        if (!isAffordable(proofs.size, proofs.first().second.length)) return null

        val nonces = withContext(Dispatchers.Default) {
            TwoDHiveCrypto.solveCapProofs(proofs)
        }
        return JsonArray(nonces.map { JsonObject(mapOf("nonce" to JsonPrimitive(it))) })
    }

    /**
     * Whether a challenge is worth starting, given what it would cost.
     *
     * The parameters arrive from the server on every challenge, so the work is theirs to set:
     * raising the difficulty by one would ask for sixteen times the digests with nothing about the
     * payload looking different. A budget here means that shows up as one provider declining
     * rather than as a device pinned at full load for minutes while somebody waits.
     */
    private fun isAffordable(count: Int, difficulty: Int): Boolean {
        if (count !in 1..MAX_CAP_PROOFS || difficulty !in 1..MAX_CAP_DIFFICULTY) return false
        val digests = count.toLong() * Math.pow(16.0, difficulty.toDouble()).toLong()
        return digests <= MAX_CAP_DIGESTS
    }

    private suspend fun postJson(url: String, body: RequestBody, referer: String): JsonObject? =
        runCatching {
            client.post(
                url,
                headers.newBuilder()
                    .set("Accept", "application/json, text/plain, */*")
                    .set("Content-Type", "application/json")
                    .set("Referer", referer)
                    .build(),
                body,
                ensureSuccess = false,
            ).use { response ->
                if (!response.isSuccessful) return@use null
                jsonInstance.parseToJsonElement(response.bodyString()) as? JsonObject
            }
        }.getOrNull()

    private fun apiHeaders(referer: String): Headers = headers.newBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Content-Type", "application/json")
        .set("Referer", referer)
        .set("Origin", referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }.orEmpty())
        .build()

    private fun join(base: String, path: String): String = "${base.trimEnd('/')}/$path"

    private fun Payload?.needsVerification(): Boolean {
        if (this == null) return true
        if (type != "error") return false
        return message?.contains("verify", ignoreCase = true) == true
    }

    private fun kotlinx.serialization.json.JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement?.asInt(): Int? =
        (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun kotlinx.serialization.json.JsonElement?.asLong(): Long? =
        (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    private fun kotlinx.serialization.json.JsonElement?.asBoolean(): Boolean? =
        (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    companion object {
        private const val TAG = "TwoDHiveBabaStream"

        private const val MAX_CAP_PROOFS = 512
        private const val MAX_CAP_DIFFICULTY = 8

        /**
         * Roughly four times the challenge BabaStream serves today, which leaves room for the
         * service to make its challenge meaningfully harder without leaving room for it to ask a
         * phone for minutes of work.
         */
        private const val MAX_CAP_DIGESTS = 20_000_000L

        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val DEFAULT_TOKEN_LIFETIME_MS = 30 * 60 * 1000L

        private val CONFIG_REGEX = Regex("""\b(?:var|let|const)\s+\w+\s*=\s*(\{[^;]+\})\s*;""")
        private val ALGORITHM_REGEX = Regex("""name\s*:\s*["'](AES-[A-Z]+)["']""")
        private val IV_LENGTH_REGEX = Regex("""getRandomValues\(\s*new Uint8Array\((\d+)\)""")
        private val TAG_LENGTH_REGEX = Regex("""tagLength\s*:\s*(\d+)""")
        private val FETCH_ROUTE_REGEX = Regex("""fetch\(\s*["']([^"']+)["']""")
    }
}
