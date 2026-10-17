package com.novaremote.agent

import java.security.SecureRandom

// the lock on Nova Remote. three gates, all have to pass before a laptop gets a session token:
//   1. same wifi  - the client sits on the robot's /24 (checked by the caller via Net)
//   2. MAC allow  - if the dev set remote.macAllow, the client's hardware address is on the list
//   3. the code   - the 9-digit number shown on the robot screen, re-rolled on every start
// wrong codes are rate limited per client so the number cannot be guessed.
class Pairing(
    private val macAllow: Set<String>,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val rng: SecureRandom = SecureRandom(),
) {
    // the code the dev reads off the robot. 9 digits, no leading-zero trimming.
    val code: String = (0 until 9).joinToString("") { rng.nextInt(10).toString() }

    private val tokens = HashSet<String>()
    private val fails = HashMap<String, Attempt>()

    private data class Attempt(var count: Int, var until: Long)

    data class Result(val ok: Boolean, val token: String?, val reason: String?)

    companion object {
        const val MAX_FAILS = 5
        const val LOCK_MS = 60_000L
    }

    @Synchronized
    fun lockedUntil(clientIp: String): Long = fails[clientIp]?.takeIf { it.count >= MAX_FAILS }?.until ?: 0L

    // called after Net.sameSubnet already passed. mac may be null when the arp table had no entry.
    @Synchronized
    fun pair(clientIp: String, mac: String?, submittedCode: String): Result {
        val lock = fails[clientIp]
        if (lock != null && lock.count >= MAX_FAILS) {
            if (now() < lock.until) return Result(false, null, "locked")
            fails.remove(clientIp) // lock expired, give them a fresh set of tries
        }

        if (macAllow.isNotEmpty() && (mac == null || mac.lowercase() !in macAllow)) {
            return Result(false, null, "mac")
        }

        if (submittedCode.trim() != code) {
            val a = fails.getOrPut(clientIp) { Attempt(0, 0L) }
            a.count++
            if (a.count >= MAX_FAILS) a.until = now() + LOCK_MS
            return Result(false, null, "code")
        }

        fails.remove(clientIp)
        val token = newToken()
        tokens.add(token)
        return Result(true, token, null)
    }

    @Synchronized
    fun valid(token: String?): Boolean = token != null && token in tokens

    @Synchronized
    fun revokeAll() = tokens.clear()

    private fun newToken(): String {
        val bytes = ByteArray(18)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
