package eu.kanade.tachiyomi.animeextension.en.animealpha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The data of an Anubis check page (`<script id="anubis_challenge">`). */
internal class AnubisChallenge(
    val id: String?,
    val randomData: String,
    val difficulty: Int,
)

internal class AnubisSolution(
    val nonce: Long,
    val hash: String,
    val elapsedMs: Long,
)

/**
 * animealpha.cc sits behind Anubis, a proof-of-work bot check: a fresh client is served a
 * "verifying you are not a bot" page, finds a nonce for which `sha256(randomData + nonce)` starts
 * with `difficulty` zero hex digits and hands it to the pass-challenge endpoint, which answers with
 * a signed cookie. A browser needs a few seconds for this; solving it natively takes a fraction.
 */
internal object Anubis {
    const val PASS_PATH = "/.within.website/x/cmd/anubis/api/pass-challenge"
    const val MARKER = "anubis_challenge"

    private const val MAX_DIFFICULTY = 7
    private const val SOLVE_TIMEOUT_MS = 90_000L
    private const val DIGEST_LENGTH = 32
    private const val MAX_NONCE_DIGITS = 20
    private const val DEADLINE_CHECK_MASK = 0x3FFF

    private val SCRIPT_REGEX = Regex("""<script[^>]*id=["']anubis_challenge["'][^>]*>([\s\S]*?)</script>""")

    /** Reads the challenge out of a check page; `null` when the page carries none we understand. */
    fun parse(html: String): AnubisChallenge? {
        val raw =
            SCRIPT_REGEX
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: return null
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val rules = root["rules"] as? JsonObject
        val challenge = root["challenge"]
        val details = challenge as? JsonObject

        // Current versions send an object ({id, randomData, …}); older ones a bare string.
        val randomData =
            details?.string("randomData")
                ?: details?.string("challenge")
                ?: (challenge as? JsonPrimitive)?.contentOrNull
                ?: return null
        val difficulty = rules?.int("difficulty") ?: details?.int("difficulty") ?: return null
        return AnubisChallenge(details?.string("id"), randomData, difficulty)
    }

    /** Finds a valid nonce on all cores. */
    fun solve(challenge: AnubisChallenge): AnubisSolution {
        if (challenge.difficulty !in 0..MAX_DIFFICULTY) {
            throw IOException("AnimeAlpha: the site's bot check is too demanding (difficulty ${challenge.difficulty})")
        }
        val started = System.nanoTime()
        val deadline = started + SOLVE_TIMEOUT_MS * 1_000_000L
        val prefix = challenge.randomData.toByteArray(Charsets.UTF_8)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val found = AtomicReference<Pair<Long, ByteArray>?>(null)

        val threads =
            List(workers) { index ->
                thread(isDaemon = true, name = "anubis-solver-$index") {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(prefix.size + MAX_NONCE_DIGITS)
                    System.arraycopy(prefix, 0, buffer, 0, prefix.size)
                    val out = ByteArray(DIGEST_LENGTH)
                    var nonce = index.toLong()
                    var rounds = 0
                    while (found.get() == null) {
                        val length = prefix.size + writeDecimal(nonce, buffer, prefix.size)
                        digest.update(buffer, 0, length)
                        digest.digest(out, 0, DIGEST_LENGTH)
                        if (hasLeadingZeroDigits(out, challenge.difficulty)) {
                            found.compareAndSet(null, nonce to out.copyOf())
                            return@thread
                        }
                        nonce += workers
                        rounds++
                        if ((rounds and DEADLINE_CHECK_MASK) == 0 && System.nanoTime() > deadline) return@thread
                    }
                }
            }
        threads.forEach { it.join() }

        val (nonce, hash) =
            found.get()
                ?: throw IOException("AnimeAlpha: could not solve the site's bot check in time")
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        return AnubisSolution(nonce, hash.joinToString("") { "%02x".format(it) }, elapsed)
    }

    /** `true` when the hex form of [hash] starts with [difficulty] zero digits. */
    fun hasLeadingZeroDigits(
        hash: ByteArray,
        difficulty: Int,
    ): Boolean {
        val wholeBytes = difficulty / 2
        for (i in 0 until wholeBytes) {
            if (hash[i].toInt() != 0) return false
        }
        return difficulty % 2 == 0 || (hash[wholeBytes].toInt() and 0xF0) == 0
    }

    /** Writes [value] as ASCII digits at [offset] and returns how many bytes that took. */
    private fun writeDecimal(
        value: Long,
        buffer: ByteArray,
        offset: Int,
    ): Int {
        if (value == 0L) {
            buffer[offset] = '0'.code.toByte()
            return 1
        }
        var digits = 0
        var rest = value
        while (rest > 0) {
            digits++
            rest /= 10
        }
        var position = offset + digits
        rest = value
        while (rest > 0) {
            position--
            buffer[position] = ('0'.code + (rest % 10).toInt()).toByte()
            rest /= 10
        }
        return digits
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
