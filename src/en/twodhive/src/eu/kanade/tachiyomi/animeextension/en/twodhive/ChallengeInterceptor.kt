package eu.kanade.tachiyomi.animeextension.en.twodhive

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Hands real Cloudflare challenges (managed challenge / Turnstile) to the WebView solver from
 * lib/cloudflareinterceptor. Everything else, including the site's own block page, passes
 * straight through, so the WebView only ever starts when a challenge is actually served.
 */
internal class ChallengeInterceptor(
    private val solver: Interceptor,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isChallenge()) return response

        // The solver starts by sending the request itself, so the answer that is already in hand
        // is handed over instead of asking the site for the very same challenge a second time.
        return solver.intercept(ReplayChain(chain, response))
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

/**
 * A chain that answers the first request with an answer that was already fetched.
 *
 * Cloudflare answers every request that comes without a clearance cookie with a fresh challenge,
 * so asking twice for the same page earns a second challenge and costs a second round trip. The
 * solver only needs the answer to recognise the challenge — everything it sends afterwards goes
 * out for real.
 */
private class ReplayChain(
    private val delegate: Interceptor.Chain,
    private val answer: Response,
) : Interceptor.Chain by delegate {

    private var replayed = false

    override fun proceed(request: Request): Response {
        if (!replayed) {
            replayed = true
            return answer
        }
        return delegate.proceed(request)
    }
}
