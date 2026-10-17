package com.novaremote.agent

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

// everything about "who is allowed on the wire": the robot's own wifi address, whether a client
// sits on the same /24, and the client's hardware (MAC) address read from the kernel arp table.
object Net {

    // the robot's IPv4 on the wifi (wlan0 preferred), or null when it is not on wifi
    fun wifiIpv4(): String? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        // prefer a wlan interface, then any other non-loopback site-local address
        val ordered = ifaces.sortedByDescending { it.name.startsWith("wlan") }
        for (nif in ordered) {
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            if (runCatching { nif.isLoopback }.getOrDefault(true)) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    // same /24 as the robot. keeps the server to the local wifi; a client from another subnet is
    // refused before the code is even checked.
    fun sameSubnet(robotIp: String?, clientIp: String?): Boolean {
        val r = robotIp?.split(".") ?: return false
        val c = clientIp?.split(".") ?: return false
        if (r.size != 4 || c.size != 4) return false
        return r[0] == c[0] && r[1] == c[1] && r[2] == c[2]
    }

    // the client's MAC, looked up in /proc/net/arp by its IP. only works for a device on the same
    // layer-2 segment (same wifi) - which is exactly the case we allow. null when unknown.
    fun macForIp(clientIp: String?): String? {
        clientIp ?: return null
        val arp = File("/proc/net/arp")
        if (!arp.canRead()) return null
        return runCatching {
            arp.readLines().drop(1).firstNotNullOfOrNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                // IP address, HW type, Flags, HW address, Mask, Device
                if (cols.size >= 4 && cols[0] == clientIp && cols[3] != "00:00:00:00:00:00") {
                    cols[3].lowercase()
                } else null
            }
        }.getOrNull()
    }

    // parse the comma-separated allow list from BuildConfig into normalised MACs
    fun parseMacAllow(raw: String): Set<String> =
        raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
