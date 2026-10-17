package com.novaremote.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetTest {

    @Test fun sameSubnetMatchesWithin24() {
        assertTrue(Net.sameSubnet("192.168.1.10", "192.168.1.200"))
    }

    @Test fun differentSubnetIsRejected() {
        assertFalse(Net.sameSubnet("192.168.1.10", "192.168.2.200"))
        assertFalse(Net.sameSubnet("192.168.1.10", "10.0.0.5"))
    }

    @Test fun badInputIsRejected() {
        assertFalse(Net.sameSubnet(null, "192.168.1.1"))
        assertFalse(Net.sameSubnet("192.168.1.1", null))
        assertFalse(Net.sameSubnet("192.168.1", "192.168.1.1"))
    }

    @Test fun parseMacAllowTrimsAndLowercases() {
        val set = Net.parseMacAllow(" AA:BB:CC:00:11:22 , dd:ee:ff:33:44:55 ,, ")
        assertEquals(setOf("aa:bb:cc:00:11:22", "dd:ee:ff:33:44:55"), set)
    }

    @Test fun parseMacAllowEmptyIsEmpty() {
        assertTrue(Net.parseMacAllow("").isEmpty())
        assertTrue(Net.parseMacAllow("  ").isEmpty())
    }
}
