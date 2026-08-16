package com.christian.devialetremote

/**
 * A Devialet amplifier seen on the LAN, learned passively from its own status
 * broadcast (port 45454, ~1x/sec - see DevialetStatusListener). Every amp on
 * the network sends these regardless of which one this app has selected, so
 * we just keep track of who we've heard from recently and let the user pick
 * one instead of typing an IP.
 *
 * [lastSeenAtMs] is in SystemClock.elapsedRealtime() terms (matches the other
 * timing fields in MainActivity), not wall-clock time.
 */
data class DiscoveredAmp(
    val ipAddress: String,
    val deviceName: String,
    val lastSeenAtMs: Long
)
