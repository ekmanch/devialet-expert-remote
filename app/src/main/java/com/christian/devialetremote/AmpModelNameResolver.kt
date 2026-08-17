package com.christian.devialetremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.net.Inet4Address

/**
 * Resolves a Devialet amp's real make/model (e.g. "Devialet Expert 140 Pro")
 * from its mDNS hostname, as a nicer alternative to the friendly name the
 * amp broadcasts over UDP (which is just whatever's set in the Devialet
 * configurator, e.g. "My Devialet").
 *
 * The amp doesn't advertise a Devialet-specific mDNS service type, but it
 * does advertise _spotify-connect._tcp, and that service's mDNS hostname is
 * of the form "Expert140Pro-<serial>.local" - confirmed via avahi-browse /
 * avahi-resolve against the real amp. _spotify-connect._tcp itself isn't
 * Devialet-specific (any Spotify Connect receiver advertises it), so
 * [onModelNameResolved] fires for every match it sees and leaves it to the
 * caller to confirm the IP is actually a known Devialet amp before using
 * the name.
 *
 * Requires API 36 (Android 16) - NsdServiceInfo.getHostname() doesn't exist
 * before that. On older devices [start] silently does nothing; callers
 * should already be showing the UDP-broadcast friendly name as a fallback,
 * so there's nothing else to do here. This is a cosmetic enhancement, not
 * something the app depends on.
 *
 * Android's own continuous-discovery re-query timing (per RFC 6762 backoff)
 * can be noticeably slower than desktop tools like avahi-browse, which are
 * usually just reading a warm cache maintained by a long-running daemon
 * rather than doing a fresh query - and some OEM Wi-Fi stacks (Samsung's
 * included) are additionally sluggish about multicast reception in power-save
 * states. To compensate, the discovery session restarts itself to force
 * fresh queries rather than waiting on Android's default backoff: quickly at
 * first ([RETRY_DELAYS_MS]), then settling into a slower steady cadence
 * ([STEADY_INTERVAL_MS]) once that burst is exhausted. [retryNow] resets
 * back to the front of that fast burst on demand - e.g. the currently
 * selected amp still has no resolved name after the app resumes, or the
 * user just switched to a different amp that hasn't resolved yet - instead
 * of waiting out however much of the current interval is left.
 */
class AmpModelNameResolver(
    private val context: Context,
    // ip is the resolved service's address, modelName is display-ready
    // ("Devialet Expert 140 Pro"). Called on the main thread. Not guaranteed
    // to be a Devialet amp - check ip against known amps before trusting it.
    private val onModelNameResolved: (ip: String, modelName: String) -> Unit
) {

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private var retryIndex = 0
    private val restartRunnable = Runnable {
        if (Build.VERSION.SDK_INT < 36) return@Runnable // shouldn't happen - see start() - but keeps this call site lint-safe on its own
        stopInternal()
        startInternal()
        scheduleRestart()
    }

    fun start() {
        if (Build.VERSION.SDK_INT < 36) return // getHostname() isn't available - nothing to do
        if (discoveryListener != null) return
        retryIndex = 0
        startInternal()
        scheduleRestart()
    }

    fun stop() {
        restartHandler.removeCallbacks(restartRunnable)
        stopInternal()
    }

    /**
     * Jump back to the front of the fast retry burst and restart discovery
     * right away, instead of waiting out whatever's left of the current
     * interval. Safe to call even if [start] was never called or already
     * has a session running.
     */
    fun retryNow() {
        if (Build.VERSION.SDK_INT < 36) return
        restartHandler.removeCallbacks(restartRunnable)
        retryIndex = 0
        stopInternal()
        startInternal()
        scheduleRestart()
    }

    private fun scheduleRestart() {
        val delay = RETRY_DELAYS_MS.getOrElse(retryIndex) { STEADY_INTERVAL_MS }
        retryIndex++
        restartHandler.postDelayed(restartRunnable, delay)
    }

    private fun stopInternal() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        runCatching { nsdManager.stopServiceDiscovery(listener) }
    }

    @RequiresApi(36)
    private fun startInternal() {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // Prefer IPv4 explicitly: on a dual-stack network the first/only
                // address in getHost() can be IPv6, which would never match the
                // IPv4 key the UDP broadcast listener uses (DevialetStatusListener
                // reads packet.address, always IPv4 here) - and since that mismatch
                // isn't an error, it would otherwise silently look identical to
                // discovery never finding anything at all.
                val addresses = runCatching { serviceInfo.hostAddresses }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: legacyHostFallback(serviceInfo)
                val ip = (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
                val hostname = serviceInfo.hostname

                // Left in deliberately (not just on failure) - this is the one
                // spot that shows what the phone actually saw, which matters
                // since a mismatch here fails silently rather than erroring.
                Log.d(TAG, "Resolved ${serviceInfo.serviceName}: ip=$ip hostname=$hostname addresses=$addresses")

                if (ip == null || hostname == null) return
                val modelName = parseModelName(hostname) ?: return
                onModelNameResolved(ip, modelName)
            }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                runCatching { nsdManager.resolveService(serviceInfo, mainExecutor, resolveListener) }
            }
        }

        discoveryListener = discovery
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { Log.w(TAG, "discoverServices threw", it) }
    }

    /**
     * Fallback for the case where [NsdServiceInfo.getHostAddresses] (API 34+)
     * comes back null/empty - should be effectively unreachable here since
     * this whole resolver is gated to API 36+, but kept as cheap insurance.
     * Isolated in its own function so the deprecation suppression doesn't
     * spread to the (non-deprecated) hostAddresses call above it.
     */
    @Suppress("DEPRECATION")
    private fun legacyHostFallback(serviceInfo: NsdServiceInfo): List<java.net.InetAddress> =
        listOfNotNull(serviceInfo.host)

    companion object {
        private const val TAG = "AmpModelNameResolver"

        // Fast burst first (retries quickly right after start()/retryNow()),
        // then whatever's left over falls back to the slower steady cadence
        // below. Total burst span here is ~19s before settling down.
        private val RETRY_DELAYS_MS = longArrayOf(1_500L, 1_500L, 3_000L, 5_000L, 8_000L)
        private const val STEADY_INTERVAL_MS = 30_000L

        // Not Devialet-specific on its own - see class doc. Trailing dot
        // matches the fully-qualified DNS-SD type form Android's own docs
        // use for discoverServices/registerService.
        private const val SERVICE_TYPE = "_spotify-connect._tcp."

        /**
         * Splits "Expert140Pro" into "Expert 140 Pro" on letter/digit
         * boundaries and prefixes "Devialet" - hardcoded rather than parsed,
         * since neither the mDNS hostname nor the UDP broadcast actually
         * contains the word "Devialet", but this app is exclusively a
         * Devialet remote, so it's safe to assume.
         */
        fun parseModelName(mdnsHostname: String): String? {
            val raw = mdnsHostname.substringBefore('-').trim()
            if (raw.isEmpty()) return null
            val spaced = raw.replace(Regex("(?<=[a-zA-Z])(?=\\d)|(?<=\\d)(?=[A-Z])"), " ")
            return "Devialet $spaced"
        }
    }
}
