package eu.kanade.tachiyomi.animeextension.en.animealpha

import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Passes the site's Anubis bot check on its own. Whenever the site answers with the "verifying you
 * are not a bot" page, the proof-of-work is solved natively, the signed cookie lands in the shared
 * cookie jar and the original request is simply sent again, so the rest of the extension never
 * sees the check. (This replaces having to open the site in WebView and wait for it to finish.)
 */
internal class AnubisInterceptor(
    private val isSite: (HttpUrl) -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        if (!isSite(request.url)) return response

        repeat(MAX_SOLVES) {
            val challenge = challengeOf(response) ?: return response
            response.close()

            val solution = Anubis.solve(challenge)
            chain.proceed(passRequest(request, challenge, solution)).close()
            response = chain.proceed(request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build())
        }

        if (challengeOf(response) != null) {
            response.close()
            throw IOException("AnimeAlpha: the site's bot check was not accepted. Open the site in WebView once, then retry.")
        }
        return response
    }

    /** Reads the challenge of [response]; an unreadable check page closes the response and fails loudly. */
    private fun challengeOf(response: Response): AnubisChallenge? = try {
        response.anubisChallenge()
    } catch (e: IOException) {
        response.close()
        throw e
    }

    private fun passRequest(
        request: Request,
        challenge: AnubisChallenge,
        solution: AnubisSolution,
    ): Request {
        val url =
            request.url
                .newBuilder()
                .encodedPath(Anubis.PASS_PATH)
                .query(null)
                .fragment(null)
                .apply { challenge.id?.let { addQueryParameter("id", it) } }
                .addQueryParameter("response", solution.hash)
                .addQueryParameter("nonce", solution.nonce.toString())
                .addQueryParameter("redir", request.url.toString())
                .addQueryParameter("elapsedTime", solution.elapsedMs.toString())
                .build()
        val headers =
            request.headers
                .newBuilder()
                .removeAll("X-DDL-Token")
                .removeAll("X-Requested-With")
                .removeAll("Origin")
                .removeAll("Range")
                .set("Accept", ACCEPT_HTML)
                .set("Referer", request.url.toString())
                .set("Sec-Fetch-Dest", "document")
                .set("Sec-Fetch-Mode", "navigate")
                .set("Sec-Fetch-Site", "same-origin")
                .build()
        return Request
            .Builder()
            .url(url)
            .headers(headers)
            .get()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
    }

    /** The challenge carried by this response, `null` for every normal page. */
    private fun Response.anubisChallenge(): AnubisChallenge? {
        if (header("Content-Type")?.contains("html", ignoreCase = true) != true) return null
        val head = runCatching { peekBody(PEEK_BYTES).string() }.getOrDefault("")
        if (!head.contains(Anubis.MARKER)) return null
        return Anubis.parse(head)
            ?: throw IOException("AnimeAlpha: the site's bot check uses a format this extension does not know yet.")
    }

    private companion object {
        const val MAX_SOLVES = 2
        const val PEEK_BYTES = 96L * 1024
        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    }
}

/**
 * The site's own gateway blacklists VPN / proxy / datacenter addresses and fresh clients that never
 * visited the home page (which is what issues the identity cookie). A block page is therefore
 * answered by visiting the home page and trying again; if that does not help, the user gets a
 * clear message instead of an unparsable page.
 */
internal class GatewayInterceptor(
    private val isSite: (HttpUrl) -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        if (!isSite(request.url)) return response

        repeat(BLOCK_RETRIES) {
            if (!response.isBlockPage()) return response
            response.close()
            runCatching { chain.proceed(warmupRequest(request)).close() }
            response = chain.proceed(request)
        }
        if (response.isBlockPage()) {
            response.close()
            throw IOException(
                "AnimeAlpha blocked this connection. The site blacklists VPN, proxy and datacenter IPs — " +
                    "turn off any VPN or private DNS, open the site once in WebView, then retry.",
            )
        }
        return response
    }

    private fun warmupRequest(request: Request): Request {
        val home =
            request.url
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
        return request
            .newBuilder()
            .url(home)
            .get()
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .removeHeader("X-DDL-Token")
            .removeHeader("X-Requested-With")
            .removeHeader("Origin")
            .removeHeader("Referer")
            .removeHeader("Range")
            .build()
    }

    private fun Response.isBlockPage(): Boolean {
        if (request.url.encodedPath.startsWith("/home/blocked")) return true
        if (code != 200 && code != 403 && code != 429) return false
        if (header("Content-Type")?.contains("html", ignoreCase = true) != true) return false
        val head = runCatching { peekBody(BLOCK_PEEK_BYTES).string() }.getOrDefault("")
        return BLOCK_MARKERS.any { head.contains(it, ignoreCase = true) }
    }

    private companion object {
        const val BLOCK_RETRIES = 2
        const val BLOCK_PEEK_BYTES = 64L * 1024
        val BLOCK_MARKERS = listOf("Connection blacklisted", "Access Blocked", "/home/blocked?ip=")
    }
}

/**
 * Hands real Cloudflare challenges (managed challenge / Turnstile) to the WebView solver from
 * lib/cloudflareinterceptor. Everything else, including the site's own block page, passes straight
 * through, so the WebView only ever starts when a challenge is actually served.
 */
internal class ChallengeInterceptor(
    private val solver: Interceptor,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isChallenge()) return response
        response.close()
        return solver.intercept(chain)
    }

    private fun Response.isChallenge(): Boolean {
        if (header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
        if (code != 403 && code != 503 && code != 429) return false
        if (header("Server")?.startsWith("cloudflare", ignoreCase = true) != true) return false
        val head = runCatching { peekBody(16L * 1024).string() }.getOrDefault("")
        return CHALLENGE_MARKERS.any { head.contains(it, ignoreCase = true) }
    }

    private companion object {
        val CHALLENGE_MARKERS = listOf("challenge-platform", "Just a moment", "cf-turnstile", "cf_chl_opt")
    }
}
