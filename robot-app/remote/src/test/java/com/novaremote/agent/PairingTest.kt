package com.novaremote.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    private var clock = 0L
    private fun pairing(macAllow: Set<String> = emptySet()) =
        Pairing(macAllow, now = { clock })

    @Test fun codeIsNineDigits() {
        assertTrue(pairing().code.matches(Regex("\\d{9}")))
    }

    @Test fun rightCodePairsAndTokenIsValid() {
        val p = pairing()
        val r = p.pair("192.168.1.5", null, p.code)
        assertTrue(r.ok)
        assertNotNull(r.token)
        assertTrue(p.valid(r.token))
        assertFalse(p.valid("something-else"))
    }

    @Test fun wrongCodeIsRejected() {
        val p = pairing()
        val wrong = if (p.code == "000000000") "111111111" else "000000000"
        val r = p.pair("192.168.1.5", null, wrong)
        assertFalse(r.ok)
        assertEquals("code", r.reason)
        assertNull(r.token)
    }

    @Test fun macAllowListBlocksUnknownDevice() {
        val p = pairing(setOf("aa:bb:cc:dd:ee:ff"))
        val blocked = p.pair("192.168.1.5", "11:22:33:44:55:66", p.code)
        assertFalse(blocked.ok)
        assertEquals("mac", blocked.reason)
        val allowed = p.pair("192.168.1.5", "AA:BB:CC:DD:EE:FF", p.code) // case-insensitive
        assertTrue(allowed.ok)
    }

    @Test fun macAllowListBlocksWhenMacUnknown() {
        val p = pairing(setOf("aa:bb:cc:dd:ee:ff"))
        val r = p.pair("192.168.1.5", null, p.code)
        assertFalse(r.ok)
        assertEquals("mac", r.reason)
    }

    @Test fun locksAfterTooManyWrongTries() {
        val p = pairing()
        val wrong = if (p.code == "000000000") "111111111" else "000000000"
        repeat(Pairing.MAX_FAILS) { p.pair("10.0.0.2", null, wrong) }
        assertTrue(p.lockedUntil("10.0.0.2") > clock)
        // even the right code is refused while locked
        val r = p.pair("10.0.0.2", null, p.code)
        assertFalse(r.ok)
        assertEquals("locked", r.reason)
    }

    @Test fun lockExpiresAndAllowsRetry() {
        val p = pairing()
        val wrong = if (p.code == "000000000") "111111111" else "000000000"
        repeat(Pairing.MAX_FAILS) { p.pair("10.0.0.2", null, wrong) }
        clock += Pairing.LOCK_MS + 1
        val r = p.pair("10.0.0.2", null, p.code)
        assertTrue(r.ok)
    }

    @Test fun revokeAllInvalidatesTokens() {
        val p = pairing()
        val token = p.pair("192.168.1.5", null, p.code).token
        assertTrue(p.valid(token))
        p.revokeAll()
        assertFalse(p.valid(token))
    }
}
