package eu.kanade.tachiyomi.animeextension.en.twodhive

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography the 2Dhive servers use.
 *
 * Three unrelated schemes live here:
 *
 * - **MegaPlay** hands the playable URL over encrypted with AES/CBC/PKCS5Padding under a pair of
 *   seeds its own client script publishes, and expects the resulting CDN URL to carry an HMAC
 *   signature. The seeds are refreshed from that script; the pair compiled in here is the one the
 *   script shipped with before it started publishing them.
 * - **BabaStream** speaks AES/GCM/NoPadding with a per-page-load key that the embed page hands
 *   over base64 encoded. Its wire format is IV first, tag last, which is what Web Crypto produces
 *   and what `Cipher.doFinal` produces as well.
 * - **Cap** — the proof of work BabaStream gates its API behind — derives its salts and targets
 *   from the challenge token with a small string hash of its own.
 */
object TwoDHiveCrypto {

    /** The seeds the MegaPlay client shipped with before it started publishing them. */
    private const val FALLBACK_KEY_SEED = "i?LMTAx0Q6,:}50U"
    private const val FALLBACK_IV_SEED = "W0;27ToaUpl_P%'c"

    /** The secret the MegaPlay CDN signs its URLs with. */
    private const val MEGAPLAY_TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"

    /** How long a signed MegaPlay URL stays valid. */
    private const val MEGAPLAY_TOKEN_LIFETIME_SECONDS = 7L * 24 * 60 * 60

    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    /** The seeds published by MegaPlay's client script. */
    private val megaPlaySeeds = AtomicReference<Pair<String, String>?>(null)

    /**
     * The pair of sixteen byte seeds the site's player script uses.
     *
     * The script publishes them next to each other, so both are read in one go.
     */
    fun megaPlaySeedPair(): Pair<String, String> = megaPlaySeeds.get()
        ?: (FALLBACK_KEY_SEED to FALLBACK_IV_SEED)

    /**
     * Stores the seeds read from MegaPlay's client script.
     *
     * Both halves have to be exactly sixteen bytes, which is what the cipher below expects, so
     * anything else is ignored rather than silently producing garbage.
     */
    fun updateMegaPlaySeeds(keySeed: String?, ivSeed: String?) {
        if (keySeed == null || ivSeed == null) return
        if (keySeed.length != 16 || ivSeed.length != 16) return
        megaPlaySeeds.set(keySeed to ivSeed)
    }

    /**
     * Reads the seeds out of MegaPlay's client script.
     *
     * The script names the two halves and falls back to the compiled in pair when nothing
     * overrides it, so both spellings are looked for: the named ones first and the plain pair
     * afterwards.
     *
     * @return the seeds, or `null` when the script does not publish a usable pair.
     */
    fun parseMegaPlaySeeds(script: String): Pair<String, String>? {
        val key = quotedSeedAfter(script, "trustAesKey")
        val iv = quotedSeedAfter(script, "trustAesIv")
        if (key != null && iv != null) return key to iv

        val pair = MEGAPLAY_SEED_PAIR_REGEX.find(script) ?: return null
        val pairKey = pair.groupValues[1]
        val pairIv = pair.groupValues[2]
        if (pairKey.length != 16 || pairIv.length != 16) return null
        return pairKey to pairIv
    }

    /**
     * The first sixteen character string the script quotes after [name].
     *
     * The script names the setting and then quotes the value somewhere after it, so the value is
     * looked up by position rather than by a pattern that has to match the whole expression.
     */
    private fun quotedSeedAfter(script: String, name: String): String? {
        val index = script.indexOf(name, ignoreCase = true)
        if (index < 0) return null
        val match = QUOTED_SEED_REGEX.find(script, index) ?: return null
        val value = match.groupValues[1]
        return value.takeIf { it.length == 16 }
    }

    /**
     * Decrypts the `enc` payload of a MegaPlay `getSources` answer.
     *
     * The payload is base64url encoded without padding, so the alphabet is normalised and the
     * padding restored before decoding.
     *
     * @return the decrypted text, or `null` when the payload cannot be read.
     */
    fun decryptMegaPlay(enc: String): String? {
        val (keySeed, ivSeed) = megaPlaySeedPair()

        return runCatching {
            val normalized = enc.replace('-', '+').replace('_', '/')
            val padding = (4 - normalized.length % 4) % 4
            val encrypted = Base64.decode(normalized + "=".repeat(padding), Base64.DEFAULT)
            if (encrypted.isEmpty() || encrypted.size % 16 != 0) return null

            val keyBytes = ByteArray(32)
            val keySource = keySeed.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(keySource, 0, keyBytes, 0, keySource.size.coerceAtMost(32))

            val ivBytes = ByteArray(16)
            val ivSource = ivSeed.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(ivSource, 0, ivBytes, 0, ivSource.size.coerceAtMost(16))

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Reads the playable URL out of a decrypted MegaPlay payload.
     *
     * The payload names the file in a `file` field; the surrounding JSON has changed shape before,
     * so the field is read out of the text rather than parsed.
     */
    fun megaPlayFile(plain: String): String? =
        MEGAPLAY_FILE_REGEX.find(plain)?.groupValues?.get(1)?.takeIf(String::isNotBlank)

    /**
     * Signs a MegaPlay CDN URL with the token the CDN expects.
     *
     * The signature covers `<expiry>|<first>/<second>`, the two hex directory names the URL is
     * built from, and travels as `?token=<payload>.<signature>`.
     *
     * @return the signed URL, or the original one when it is not a CDN URL that needs a token.
     */
    fun signMegaPlayUrl(url: String): String {
        val match = MEGAPLAY_PATH_KEY_REGEX.find(url) ?: return url
        if (MEGAPLAY_TOKEN_PARAM_REGEX.containsMatchIn(url)) return url

        val expiry = System.currentTimeMillis() / 1000L + MEGAPLAY_TOKEN_LIFETIME_SECONDS
        val payload = "$expiry|${match.groupValues[1].lowercase()}/${match.groupValues[2].lowercase()}"

        return runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(
                SecretKeySpec(MEGAPLAY_TOKEN_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"),
            )
            val signature = base64Url(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))

            val separator = if ('?' in url) "&" else "?"
            "$url${separator}token=${base64Url(payload.toByteArray(StandardCharsets.UTF_8))}.$signature"
        }.getOrDefault(url)
    }

    /**
     * Encrypts [plaintext] the way the BabaStream embed page does.
     *
     * @param key the raw AES key taken from the page's `pk`.
     * @return base64 of IV ‖ ciphertext ‖ tag.
     */
    fun encryptAesGcm(key: ByteArray, plaintext: String, ivLength: Int = GCM_IV_LENGTH): String {
        val iv = ByteArray(ivLength).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH, iv),
        )
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts an AES/GCM payload in the IV-first, tag-last layout used by BabaStream.
     *
     * @return the decrypted text, or `null` when the payload cannot be read.
     */
    fun decryptAesGcm(
        key: ByteArray,
        encryptedBase64: String,
        ivLength: Int = GCM_IV_LENGTH,
        tagLength: Int = GCM_TAG_LENGTH,
    ): String? = runCatching {
        val data = Base64.decode(encryptedBase64, Base64.DEFAULT)
        if (data.size <= ivLength + tagLength / 8) return null

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(tagLength, data, 0, ivLength),
        )
        String(
            cipher.doFinal(data, ivLength, data.size - ivLength),
            Charsets.UTF_8,
        )
    }.getOrNull()

    /**
     * Cap's own string derivation: an FNV-1a flavoured mix of [value] seeding an xorshift32
     * stream, emitted as [length] hex characters.
     *
     * The accumulator is a JavaScript `Number` in the original, but every operation agrees modulo
     * 2^32, so unsigned arithmetic reproduces it exactly. Cap indexes the string by UTF-16 code
     * unit, so this walks `Char`s rather than encoded bytes — a non-Latin token would otherwise
     * derive a different salt here than on the server.
     */
    fun capSeededHex(value: String, length: Int): String {
        var state = 2166136261u
        for (char in value) {
            state = state xor char.code.toUInt()
            state += (state shl 1) + (state shl 4) + (state shl 7) + (state shl 8) + (state shl 24)
        }
        val out = StringBuilder(length + 8)
        while (out.length < length) {
            state = state xor (state shl 13)
            state = state xor (state shr 17)
            state = state xor (state shl 5)
            out.append(state.toString(16).padStart(8, '0'))
        }
        return out.substring(0, length)
    }

    /**
     * Solves one Cap proof: the counter whose `sha256(salt + counter)` digest starts with [target].
     *
     * The digest is compared byte-wise; formatting every attempt into a hex string would allocate
     * millions of short lived strings for a single challenge.
     */
    fun solveCapProof(salt: String, target: String, digest: MessageDigest = MessageDigest.getInstance("SHA-256")): Long {
        val wholeBytes = target.length / 2
        val expected = ByteArray(wholeBytes) {
            target.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        // An odd length target ends on a nibble, which is the high half of the byte that follows.
        val hasHalfByte = target.length % 2 == 1
        val halfByte = if (hasHalfByte) target.last().digitToInt(16) else 0

        var counter = 0L
        while (true) {
            digest.reset()
            val hash = digest.digest("$salt$counter".toByteArray(StandardCharsets.UTF_8))

            var matches = true
            for (index in 0 until wholeBytes) {
                if (hash[index] != expected[index]) {
                    matches = false
                    break
                }
            }
            if (matches && hasHalfByte) {
                matches = (hash[wholeBytes].toInt() ushr 4 and 0xF) == halfByte
            }
            if (matches) return counter

            counter++
        }
    }

    /**
     * The proofs a counted Cap challenge asks for.
     *
     * The counter is one-based; deriving it from zero produces proofs the server rejects, which is
     * an expensive way to be wrong.
     */
    fun capProofs(token: String, count: Int, saltLength: Int, difficulty: Int): List<Pair<String, String>> =
        (1..count).map { counter ->
            capSeededHex("$token$counter", saltLength) to capSeededHex("$token${counter}d", difficulty)
        }

    /**
     * Solves every proof of a challenge, spread across the CPU.
     *
     * The proofs are independent and take wildly different numbers of attempts, so they are dealt
     * out round-robin rather than in contiguous blocks: a block that happened to collect the
     * unlucky ones would hold up every other core.
     */
    suspend fun solveCapProofs(proofs: List<Pair<String, String>>): List<Long> {
        if (proofs.isEmpty()) return emptyList()

        val lanes = minOf(proofs.size, Runtime.getRuntime().availableProcessors().coerceIn(1, 8))

        return withContext(Dispatchers.Default) {
            val lanes = proofs.indices.groupBy { it % lanes }.values
            coroutineScope {
                lanes.map { lane ->
                    async {
                        val digest = MessageDigest.getInstance("SHA-256")
                        lane.map { index ->
                            // Solving one proof is a tight loop with no suspension point in it, so
                            // a viewer who navigates away mid-solve would otherwise leave every
                            // lane hashing to the end of its list. Checking between proofs bounds
                            // the overrun to the one already in flight.
                            ensureActive()
                            val (salt, target) = proofs[index]
                            index to solveCapProof(salt, target, digest)
                        }
                    }
                }.awaitAll()
            }.flatten().sortedBy { it.first }.map { it.second }
        }
    }

    /** Base64 without padding, in the alphabet a URL can carry. */
    fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private val MEGAPLAY_FILE_REGEX = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")

    private val MEGAPLAY_PATH_KEY_REGEX = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

    private val MEGAPLAY_TOKEN_PARAM_REGEX = Regex("""[?&]token=""", RegexOption.IGNORE_CASE)

    /** The two seeds as the client script names them. */
    private val QUOTED_SEED_REGEX = Regex("""["']([^"']{16})["']""")

    /** Any two sixteen character strings published next to each other. */
    private val MEGAPLAY_SEED_PAIR_REGEX = Regex(
        """[A-Za-z]\w*="([^"]{16})"\s*,\s*[A-Za-z]\w*="([^"]{16})"""",
    )
}
