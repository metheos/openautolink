package com.openautolink.app.transport.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-scoped owner of [AaWirelessBtServer].
 *
 * Lives at Application scope on purpose. The advertiser's entire job is to make the
 * phone *initiate* a projection session, so hanging it off SessionManager — which
 * only exists once a session is already running — would be circular. That mistake
 * cost a test cycle: the DEBUG broadcast was delivered (`result=0`) but nothing
 * listened, because no session had started to register the receiver.
 *
 * Kept opt-in via broadcast while we establish whether gearhead reacts to our SDP
 * record at all. Once proven, the credentials should come from the live network
 * state rather than adb extras.
 *
 * ```
 * # start advertising + set the details handed to the phone
 * adb shell am broadcast -a com.openautolink.app.DEBUG_AAW_BT \
 *   --es ssid Fortress --es psk '<key>' --es bssid aa:bb:cc:dd:ee:ff \
 *   --es ip 192.168.1.100 --ei port 5277
 *
 * # stop
 * adb shell am broadcast -a com.openautolink.app.DEBUG_AAW_BT_STOP
 * ```
 */
object AaWirelessBtControl {

    private const val TAG = "AaWirelessBtControl"
    private const val ACTION_START = "com.openautolink.app.DEBUG_AAW_BT"
    private const val ACTION_STOP = "com.openautolink.app.DEBUG_AAW_BT_STOP"

    /**
     * Set the direct-transport preference. Lives here rather than in
     * SessionManager because SessionManager's debug receiver only registers once
     * a session is running — and switching to "wpp" is a precondition for the
     * session starting at all. Same circular-dependency trap the advertiser hit.
     */
    private const val ACTION_SET_TRANSPORT = "com.openautolink.app.DEBUG_SET_TRANSPORT"

    /** Companion's identity-probe port; it reports its AA proxy port here. */
    private const val IDENTITY_PORT = 5278

    /** Reserved MACs gearhead rejects outright — see pev.smali validation. */
    private const val ZERO_MAC = "00:00:00:00:00:00"
    private const val BROADCAST_MAC = "ff:ff:ff:ff:ff:ff"
    private val BSSID_RE = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var btServer: AaWirelessBtServer? = null

    /**
     * Last IPv4 we saw the companion on, used to ask it for its proxy port.
     *
     * Set by whoever discovers or connects to the phone. Null until then, in
     * which case we advertise the car's own address.
     */
    @Volatile
    var lastKnownPhoneIp: String? = null
        set(value) {
            field = value
            if (value != null) {
                synchronized(recentPhoneIps) {
                    recentPhoneIps.remove(value)
                    recentPhoneIps.add(0, value)
                    while (recentPhoneIps.size > 4) recentPhoneIps.removeAt(recentPhoneIps.size - 1)
                }
            }
        }

    /**
     * Recently-seen phone addresses, newest first.
     *
     * Bluetooth toggling drops WiFi and the phone reappears on a different subnet,
     * so the address that worked a minute ago may not be the one that works now —
     * but the old one is often still valid. Keeping a short history and probing
     * all of them is cheaper and more reliable than a subnet scan.
     */
    private val recentPhoneIps = mutableListOf<String>()


    @Volatile
    private var started = false

    fun init(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_START -> handleStart(context, intent)
                    ACTION_SET_TRANSPORT -> {
                        val mode = intent.getStringExtra("mode").orEmpty()
                        if (mode.isBlank()) {
                            OalLog.w(TAG, "$ACTION_SET_TRANSPORT missing 'mode' extra")
                            return
                        }
                        scope.launch {
                            com.openautolink.app.data.AppPreferences.getInstance(context)
                                .setDirectTransport(mode)
                            OalLog.i(TAG, "Direct transport set to '$mode'")
                        }
                    }
                    ACTION_STOP -> {
                        OalLog.i(TAG, "Stopping AA wireless BT advertiser on request")
                        btServer?.stop()
                        btServer = null
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
            addAction(ACTION_SET_TRANSPORT)
        }
        // Exported so it can be driven from adb during bring-up. This is a debug
        // affordance; when the advertiser starts automatically it should stop being
        // externally triggerable.
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )

        // Start advertising automatically whenever WPP is the selected transport.
        //
        // Selecting Settings → Transport → Wireless (WPP) binds the TCP listener
        // (AasdkSession.startWpp) but that alone publishes nothing over Bluetooth,
        // so the phone has nothing to discover and reports "no Android Auto". The
        // SDP record is what makes the head unit visible; it must come up on the
        // same trigger as the listener, not from a debug broadcast.
        //
        // Observed in-vehicle before this fix: "AA wireless BT control ready" and
        // "WPP TCP server listening on 0.0.0.0:5277", but never
        // "Listening on Android Auto Wireless UUID" — the advertiser sat waiting
        // for a broadcast that only ever came from adb.
        scope.launch {
            val prefs = com.openautolink.app.data.AppPreferences.getInstance(context)
            prefs.directTransport
                .map { it == com.openautolink.app.data.AppPreferences.DIRECT_TRANSPORT_WPP }
                .distinctUntilChanged()
                .collect { isWpp ->
                    if (isWpp) {
                        OalLog.i(TAG, "WPP transport selected — starting Bluetooth advertiser")
                        startFromPreferences(context)
                    } else if (btServer != null) {
                        OalLog.i(TAG, "Transport is no longer WPP — stopping Bluetooth advertiser")
                        btServer?.stop()
                        btServer = null
                    }
                }
        }

        OalLog.i(TAG, "AA wireless BT control ready")
    }

    /**
     * Start advertising using the credentials stored in Settings.
     *
     * Intent extras are honoured when present, but are only a bring-up
     * convenience — the normal path is Settings → Wireless (WPP), because in a
     * real vehicle nobody can run `adb` mid-drive.
     *
     * ### Why the credentials must be typed in
     *
     * These describe the network the phone should join to reach the head unit.
     * On AAOS that is usually the car's own hotspot, and an unprivileged app
     * cannot read a running SoftAP's SSID or passphrase:
     * `WifiManager.getWifiApConfiguration()` needs a system signature, and
     * `LocalOnlyHotspot` only reports credentials for an AP the app itself
     * started — not the vehicle's. So the values come from the user.
     *
     * Missing or malformed credentials are a hard failure on the phone side, so
     * they are validated before anything is advertised:
     *   - empty SSID              → nothing to join
     *   - empty/zero/broadcast BSSID → `WIFI_INVALID_BSSID`
     *   - empty PSK on a secured network → `WIFI_SECURITY_NOT_SUPPORTED`
     *     (we send `securityMode=OPEN`, which gearhead rejects for a WPA2 AP)
     */
    private fun handleStart(context: Context, intent: Intent?) {
        scope.launch { startFromPreferences(context, intent) }
    }

    /**
     * Load credentials from Settings (optionally overridden by intent extras)
     * and begin advertising. Shared by the automatic transport trigger and the
     * adb bring-up broadcast so both validate identically.
     */
    private suspend fun startFromPreferences(context: Context, intent: Intent? = null) {
            val prefs = com.openautolink.app.data.AppPreferences.getInstance(context)

            val ssid = intent?.getStringExtra("ssid")?.takeIf { it.isNotBlank() }
                ?: prefs.hotspotSsid.first()
            val psk = intent?.getStringExtra("psk")
                ?: prefs.hotspotPassword.first()
            val bssid = intent?.getStringExtra("bssid")?.takeIf { it.isNotBlank() }
                ?: prefs.wppBssid.first()
            val port = intent?.getIntExtra("port", 5277) ?: 5277
            // The address the phone is told to dial. Detected from the live
            // interface rather than stored, because it changes with the network.
            val ip = intent?.getStringExtra("ip")?.takeIf { it.isNotBlank() }
                ?: prefs.wppLocalIp.first().takeIf { it.isNotBlank() }
                ?: localIpv4Address(prefs.wppApInterface.first())
                ?: ""

            val problems = buildList {
                if (ssid.isBlank()) add("SSID is empty")
                if (bssid.isBlank()) add("BSSID is empty")
                else if (!BSSID_RE.matches(bssid)) add("BSSID '$bssid' is not a MAC address")
                else if (bssid.equals(ZERO_MAC, true) || bssid.equals(BROADCAST_MAC, true)) {
                    add("BSSID $bssid is reserved (zero/broadcast)")
                }
                if (ip.isBlank()) add("could not determine this device's IPv4 address")
            }
            if (problems.isNotEmpty()) {
                OalLog.w(TAG, "Not advertising — ${problems.joinToString("; ")}. " +
                        "Set these in Settings → Transport → Wireless (WPP).")
                return
            }

            val creds = AaWirelessBtServer.WifiCredentials(
                ssid = ssid, psk = psk, bssid = bssid, ip = ip, port = port,
                channelsMhz = apChannelsMhz(prefs.wppChannelMhz.first()),
            )
            startAdvertising(
                context, creds,
                manualIp = intent?.getStringExtra("ip")?.takeIf { it.isNotBlank() }
                    ?: prefs.wppLocalIp.first().takeIf { it.isNotBlank() },
                apInterface = prefs.wppApInterface.first(),
            )
    }

    /**
     * Best-effort local IPv4 for the access-point interface.
     *
     * Picking "the first non-loopback address" is wrong on a real head unit. A
     * connected car has several interfaces up at once — modem, telematics,
     * ethernet, plus the SoftAP — and the first one enumerated is rarely the one
     * the phone can reach. Observed in-vehicle: the car advertised
     * `172.16.101.100` (an unrelated internal interface) while the phone, having
     * correctly joined the car's AP, sat on `10.2.110.109`. Association and
     * credentials were perfect; the TCP connect simply had nowhere to go.
     *
     * Preference order:
     *  1. an interface that looks like a SoftAP (`ap*`, `wlan1`, `swlan*`, …)
     *  2. any other wlan interface
     *  3. anything else routable
     *
     * Within each tier, RFC1918 addresses are preferred, since an AP hands out
     * private addresses. This is still a heuristic — hence the manual override.
     */
    /**
     * Last-resort scan for the companion across EVERY network the head unit is on.
     *
     * A Blazer has two independent radios and they sit on different networks:
     *
     *   ap_br_swlan0  the telematics module's AP ("Blazing", 10.2.110.x) that the
     *                 phone joins — and whose inbound filtering is the reason the
     *                 loopback endpoint exists at all
     *   wlan0         the head unit's own Android WiFi, a client only. Observed on
     *                 home WiFi (192.168.0.104) and on the phone's hotspot
     *                 (10.187.47.188), never on Blazing
     *
     * An earlier version scanned only the telematics subnet and so missed the
     * companion entirely when it was reachable over wlan0: at 16:34:29 the scan of
     * 10.2.110.0/24 found nothing, and 25s later discovery reported the phone at
     * 10.187.47.73 — the phone-hotspot subnet, via the other radio.
     *
     * Which radio carries the traffic does not matter to the design; only that the
     * head unit can dial the companion. Reaching it over the phone's own hotspot is
     * arguably better, since the phone is the AP there and the telematics module's
     * filtering is bypassed completely.
     */
    private fun findCompanionOnAnySubnet(manualIp: String?): Pair<String, Int>? {
        val localIps = buildList {
            manualIp?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(allLocalIpv4())
        }.distinct()
        if (localIps.isEmpty()) return null

        for (ip in localIps) {
            val prefix = ip.substringBeforeLast('.', "")
            if (prefix.isEmpty()) continue
            OalLog.i(TAG, "Scanning $prefix.0/24 for the companion")
            scanSubnet(prefix, ip)?.let { return it }
        }
        OalLog.w(TAG, "No companion on any local subnet (${localIps.joinToString()})")
        return null
    }

    /** Every non-loopback IPv4 the head unit currently holds, across both radios. */
    private fun allLocalIpv4(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            // The telematics module also exposes several vt* interfaces on
            // 172.16.x that lead nowhere useful; scanning them wastes seconds.
            .filterNot { it.name.startsWith("vt") }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress }
            }
    }.getOrDefault(emptyList())

    /**
     * Scans one /24 for the companion, returning its address AND proxy port.
     *
     * Does the whole identity exchange in the scan rather than connecting once to
     * test the port and again to ask the question. The second round trip cost ~5s
     * over the telematics bridge — measured at 17:01, where the scan gave up at
     * 39.2s and the companion's reply only completed at 44.3s.
     *
     * Timeout is 1200ms, not the 250ms of the previous revision. Two in-vehicle
     * runs bracket the right value: at 900ms the companion was found but the scan
     * took 7.2s; at 250ms the scan took 1.06s and found nothing, on a subnet where
     * the companion demonstrably answered five seconds later. The telematics
     * bridge is simply slow. With 128 threads a /24 still completes in about 2s
     * even when every address times out.
     */
    private fun scanSubnet(prefix: String, ourIp: String): Pair<String, Int>? {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(128)
        return try {
            val tasks = (1..254)
                .map { "$prefix.$it" }
                .filter { it != ourIp }
                .map { ip ->
                    java.util.concurrent.Callable {
                        askCompanion(ip, connectTimeoutMs = 1200)?.let { ip to it }
                    }
                }
            pool.invokeAll(tasks, 6, java.util.concurrent.TimeUnit.SECONDS)
                .asSequence()
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .firstOrNull()
                ?.also { OalLog.i(TAG, "Companion found at ${it.first} with proxy port ${it.second}") }
        } catch (e: Exception) {
            OalLog.w(TAG, "Scan of $prefix.0/24 failed: ${e.message}")
            null
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Asks the companion at [phoneIp] for its Android Auto proxy port.
     *
     * Returns null if nothing answers, the reply is not ours, or it reports no
     * proxy (wpp=0). A zero must not be treated as a port: advertising a dead
     * port sends Android Auto to a closed socket and fails silently on both ends.
     *
     * The default timeout is generous because the telematics bridge is slow — a
     * probe that would succeed at 1200ms returned nothing at 250ms.
     */
    private fun askCompanion(phoneIp: String, connectTimeoutMs: Int = 1200): Int? = runCatching {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(phoneIp, IDENTITY_PORT), connectTimeoutMs)
            sock.soTimeout = connectTimeoutMs
            sock.getOutputStream().apply { write("OAL?\n".toByteArray()); flush() }
            val reply = sock.getInputStream().bufferedReader().readLine().orEmpty()
            if (!reply.startsWith("OAL!")) return@runCatching null
            reply.removePrefix("OAL!").split('\t')
                .firstOrNull { it.startsWith("wpp=") }
                ?.removePrefix("wpp=")?.trim()?.toIntOrNull()
                ?.takeIf { it in 1..65535 }
        }
    }.getOrNull()

    private fun companionProxyPort(phoneIp: String): Int? =
        askCompanion(phoneIp)?.also {
            OalLog.i(TAG, "Companion at $phoneIp reports AA proxy on port $it")
        }

    /**
     * Frequencies (MHz) advertised as supported by the head unit's access point.
     *
     * Must not be empty. The phone intersects this list with its own scan
     * results; an empty list produces
     *   "WiFi channels not supported: []" -> NO_COMPATIBLE_WIFI_CHANNEL_FOUND
     * and the connection is abandoned even though everything else succeeded.
     *
     * An unprivileged app cannot read a running SoftAP's channel
     * (getWifiApConfiguration is signature-gated), so:
     *   1. use the configured override if set — the reliable answer
     *   2. otherwise advertise the common 5 GHz set, which at least gives the
     *      intersection a chance of being non-empty
     *
     * The fallback is a guess and is logged as one.
     */
    private fun apChannelsMhz(override: Int): List<Int> {
        if (override > 0) {
            OalLog.i(TAG, "AP channel from settings: $override MHz")
            return listOf(override)
        }
        // The phone's own scan reported [5180, 5200, 5220, 5240, 5745, 5765,
        // 5785, 5805, 5825, ...], so covering that range keeps the intersection
        // non-empty for a head unit that lands anywhere in it.
        val fallback = listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)
        OalLog.w(TAG, "AP channel not configured — advertising the common 5GHz set $fallback. " +
                "If projection fails with NO_COMPATIBLE_WIFI_CHANNEL_FOUND, set the exact " +
                "channel in Settings.")
        return fallback
    }

    private fun localIpv4Address(apInterface: String): String? = runCatching {
        data class Candidate(val name: String, val addr: String)

        val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress?.let { a -> Candidate(nif.name, a) } }
            }
        if (candidates.isEmpty()) return@runCatching null

        // Interface tiers. The configured AP interface wins outright; the rest
        // are fallbacks for OEMs that name it differently, so a wrong setting
        // degrades to a guess rather than to nothing.
        fun tier(name: String): Int = when {
            name == apInterface -> 0
            name.startsWith("ap_br_") || name.startsWith("ap") ||
                name.startsWith("swlan") || name == "wlan1" -> 1
            name.startsWith("wlan") -> 2
            else -> 3
        }
        fun isPrivate(a: String): Boolean =
            a.startsWith("10.") || a.startsWith("192.168.") ||
                (a.startsWith("172.") && a.substringAfter('.').substringBefore('.').toIntOrNull()
                    ?.let { it in 16..31 } == true)

        // A gateway-looking address (x.y.z.1) is a strong signal for "this
        // interface IS the access point", since an AP is the gateway for its
        // clients. Ranks above interface-name guessing, which varies by OEM.
        fun looksLikeGateway(a: String) = a.endsWith(".1")

        val chosen = candidates.sortedWith(
            compareBy<Candidate> { tier(it.name) }
                .thenBy { if (looksLikeGateway(it.addr)) 0 else 1 }
                .thenBy { if (isPrivate(it.addr)) 0 else 1 }
        ).first()

        // Always log, not just when ambiguous: the car's AP subnet is reassigned
        // by the telematics module on every restart, so the advertised address
        // legitimately changes run to run and must be traceable in the logs.
        run {
            OalLog.i(TAG, "Local address candidates: " +
                    candidates.joinToString { "${it.name}=${it.addr}" } +
                    " — advertising ${chosen.addr} (${chosen.name}). " +
                    "If the phone joins the AP but projection never starts, compare this " +
                    "against the phone's own address: they must share a subnet.")
        }
        chosen.addr
    }.getOrNull()

    private fun startAdvertising(
        context: Context,
        creds: AaWirelessBtServer.WifiCredentials,
        manualIp: String?,
        apInterface: String,
    ) {
        // Switching the session into WPP mode is what binds the listener — see
        // AasdkSession.startWpp(). Doing it here rather than starting our own
        // server avoids two components racing for the same port.
        //
        // The session must already be running in "wpp" transport mode for the
        // advertised port to be listening when the phone dials in. Set
        // Settings -> Direct transport -> wpp (or DEBUG_SET_TRANSPORT) first.
        OalLog.i(TAG, "Advertising ${creds.ip}:${creds.port} — session must be in 'wpp' " +
                "transport mode for that port to be bound")

        btServer?.stop()
        val bt = AaWirelessBtServer(context, scope)
        btServer = bt
        // Probe first: if the BT stack refuses to publish the record we want that
        // stated plainly in the log, not inferred later from the phone's silence.
        OalLog.i(TAG, "SDP advertise capability: ${bt.canAdvertise()}")
        // Re-resolve the address on every handshake rather than reusing the value
        // captured here: the car's AP is given a new subnet on each ignition
        // cycle, so this snapshot goes stale as soon as the car is restarted.
        // Honour a manual override if one is set, otherwise look it up live.
        bt.setAddressResolver { manualIp ?: localIpv4Address(apInterface) }

        // Endpoint selection, evaluated per handshake.
        //
        // Prefer the companion's loopback proxy: the phone connects to itself, so
        // the car's access point is never asked to accept an inbound connection —
        // which it refuses. Falling back to the car's own address preserves the
        // shared-network case (proven working on a tablet) and costs nothing.
        bt.setEndpointResolver {
            // Resolve the companion's address, preferring one discovery already
            // found. The sweep is the fallback, not the primary: it runs on the
            // BT dial-back, which is seconds after the phone associates and
            // often before it has an address or its servers are listening.
            // Measured in-vehicle: this sweep found nothing at 15:45:43 while
            // OAL's own discovery found the phone at 15:47:01, same subnet.
            //
            // A cached address is re-verified rather than trusted: the phone's
            // address changes when the AP is resubnetted each ignition cycle.
            // Try every address we know of, not just the newest. Toggling
            // Bluetooth drops WiFi, so the phone moves between the car's AP, its
            // own hotspot subnet and home WiFi within a single session — observed
            // in one log: 10.2.110.109, then 10.187.47.73, then the car itself on
            // 192.168.0.104. A single cached value is stale as often as not.
            val candidates = buildList {
                lastKnownPhoneIp?.let { add(it) }
                addAll(recentPhoneIps)
            }.distinct()
            var proxyPort: Int? = null
            var cached: String? = null
            for (ip in candidates) {
                val p = companionProxyPort(ip)
                if (p != null) {
                    proxyPort = p
                    cached = ip
                    lastKnownPhoneIp = ip
                    break
                }
            }
            if (proxyPort == null) {
                if (cached != null) {
                    OalLog.i(TAG, "Companion did not answer at $cached — re-scanning")
                    lastKnownPhoneIp = null
                }
                // The scan returns the port too — asking again would cost another
                // slow round trip over the telematics bridge.
                findCompanionOnAnySubnet(manualIp)?.let { (ip, port) ->
                    lastKnownPhoneIp = ip
                    proxyPort = port
                }
            }
            when {
                proxyPort != null ->
                    AaWirelessBtServer.Endpoint.PhoneLoopback(proxyPort)
                else -> {
                    // No companion: advertise our own address, and pick the one on
                    // the SAME network as the phone. The head unit has two radios
                    // on different networks (telematics AP vs its own wlan0), so
                    // "our IP" is ambiguous — naming the wrong one sends the phone
                    // somewhere it cannot route to.
                    val ip = manualIp
                        ?: lastKnownPhoneIp?.let { phone ->
                            val phonePrefix = phone.substringBeforeLast('.', "")
                            allLocalIpv4().firstOrNull {
                                it.substringBeforeLast('.', "") == phonePrefix
                            }?.also {
                                OalLog.i(TAG, "Advertising $it — same subnet as the phone ($phone)")
                            }
                        }
                        ?: localIpv4Address(apInterface)
                    if (ip.isNullOrBlank()) null
                    else AaWirelessBtServer.Endpoint.CarDirect(ip, 5277)
                }
            }
        }
        bt.updateCredentials(creds)
        bt.start()
    }
}
