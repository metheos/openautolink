package com.openautolink.app.wake

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import androidx.core.content.ContextCompat
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.diagnostics.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Process-scope, passive observer for the pre-ignition window.
 *
 * All access to [reducer], its summaries, sampler generation and summary emission is serialized
 * through [stateLock]. Task 2's reducer intentionally owns mutable state and is not thread-safe;
 * receiver, display, VHAL, Activity and session callbacks can arrive on different threads.
 *
 * This object only reads framework state and writes diagnostics. It never publishes SDP, starts
 * projection, dials a peer, requests a network, binds process traffic, or changes transport state.
 */
object PreWakeMonitor {
    private const val TAG = "PreWakeMonitor"
    private const val GM_SYSTEM_STATE = "gm.intent.action.SYSTEM_STATE_CHANGED"
    private const val GM_POWER_MODE = "gm.intent.action.POWER_MODE_CHANGED"
    private const val GM_POWEROFF_VIEW = "gm.intent.ACTION_BROADCAST_POWEROFF_VIEW"
    private const val GM_HOME_STARTED = "com.gm.HOME_STARTED"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val policy = PreWakeSignalPolicy()
    private val reducer = WakeAttemptReducer { nextAttemptId++ }
    private val gmDeliveryEvidence = GmDeliveryEvidenceTracker()
    private val gmIngressLimiter = GmBroadcastIngressLimiter()
    private val receivers = mutableListOf<BroadcastReceiver>()
    private val summaryScheduler = AttemptSummaryScheduler(
        timeoutMs = PreWakeSignalPolicy.DEFAULT_SAMPLING_PLAN.durationMs,
        schedule = { attemptId, delayMs, callback ->
            val job = scope.launch {
                delay(delayMs)
                callback()
            }
            AttemptSummaryTimeout { job.cancel() }
        },
        emit = { summary, outcome -> emitSummary(summary, outcome.name.lowercase()) },
    )
    private val sessionReadinessDispatcher = BoundedObservationDispatcher<SessionReadinessObservation>(
        capacity = 8,
        schedule = { drain -> scope.launch { drain() } },
        consume = { observation ->
            record(
                policy.sessionReady(observation.source, observation.elapsedMs),
                candidateSource = false,
            )
        },
        onDropped = { count ->
            DiagnosticLog.w(
                TAG,
                "WAKE EVENT session readiness outcome=dropped count=$count",
            )
        },
        onFailure = { error ->
            DiagnosticLog.w(
                TAG,
                "WAKE EVENT session readiness outcome=record-failed " +
                    "error=${safe(error.javaClass.simpleName)}",
            )
        },
    )

    private var nextAttemptId = 1L
    private var initialized = false
    private var applicationContext: Context? = null
    private var processStartElapsedMs = 0L
    private var powerEpoch = 0L
    private var samplerJob: Job? = null
    private var samplerGeneration = 0L
    private var configuredApInterface = AppPreferences.DEFAULT_WPP_AP_INTERFACE
    private var lastApSnapshot: ApSnapshot? = null
    private var lastDisplayState: Int? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(stateLock) {
            if (initialized) {
                DiagnosticLog.i(TAG, "WAKE EVENT monitor init outcome=already-initialized")
                return
            }
            initialized = true
            applicationContext = app
            processStartElapsedMs = SystemClock.elapsedRealtime()
        }

        registerGmReceiver(app, "SYSTEM_STATE_CHANGED", GM_SYSTEM_STATE) { intent ->
            val elapsedMs = SystemClock.elapsedRealtime()
            val raw = intExtraOrNull(intent, "System_State")
            if (!allowGmIngress(GM_SYSTEM_STATE, raw, elapsedMs)) return@registerGmReceiver
            if (raw == null) {
                recordGmDelivery(GmDeliverySignal.SYSTEM_STATE, elapsedMs)
                DiagnosticLog.w(TAG, "WAKE EVENT GM System_State outcome=missing-extra")
            } else {
                record(
                    policy.gmSystemState(raw, elapsedMs),
                    candidateSource = true,
                    gmDeliverySignal = GmDeliverySignal.SYSTEM_STATE,
                )
            }
            readCapabilities(app, "GM System_State")
            sampleAp("GM System_State")
        }
        registerGmReceiver(app, "POWER_MODE_CHANGED", GM_POWER_MODE) { intent ->
            val elapsedMs = SystemClock.elapsedRealtime()
            val raw = intExtraOrNull(intent, "power_mode")
            if (!allowGmIngress(GM_POWER_MODE, raw, elapsedMs)) return@registerGmReceiver
            if (raw == null) {
                recordGmDelivery(GmDeliverySignal.POWER_MODE, elapsedMs)
                DiagnosticLog.w(TAG, "WAKE EVENT GM power_mode outcome=missing-extra")
            } else {
                record(
                    policy.gmPowerMode(raw, elapsedMs),
                    candidateSource = true,
                )
                recordGmDelivery(GmDeliverySignal.POWER_MODE, elapsedMs)
            }
            readCapabilities(app, "GM power_mode")
            sampleAp("GM power_mode")
        }
        registerGmReceiver(app, "POWEROFF_VIEW", GM_POWEROFF_VIEW) { intent ->
            val elapsedMs = SystemClock.elapsedRealtime()
            val view = booleanExtra(intent, "gm.poweroff_view_state")
            val mute = booleanExtra(intent, "gm.poweroff_mute_state")
            val fpi = booleanExtra(intent, "gm.poweroff_fpi_state")
            val dedupeKey = GmIngressDedupeKey.poweroffView(view, mute, fpi)
            if (!allowGmIngress(GM_POWEROFF_VIEW, dedupeKey, elapsedMs)) {
                return@registerGmReceiver
            }
            recordGmDelivery(GmDeliverySignal.POWEROFF_VIEW, elapsedMs)
            val values = listOf(
                "view" to view,
                "mute" to mute,
                "fpi" to fpi,
            ).joinToString(",") { (name, value) -> "$name=${value ?: "missing"}" }
            DiagnosticLog.i(
                TAG,
                "WAKE EVENT GM POWEROFF_VIEW detail=$values hintOnly=true outcome=recorded",
            )
            readCapabilities(app, "GM POWEROFF_VIEW")
        }
        registerGmReceiver(app, "HOME_STARTED(optional)", GM_HOME_STARTED) {
            val elapsedMs = SystemClock.elapsedRealtime()
            if (!allowGmIngress(
                    GM_HOME_STARTED,
                    GmIngressDedupeKey.ACTION_ONLY,
                    elapsedMs,
                )
            ) {
                return@registerGmReceiver
            }
            recordGmDelivery(GmDeliverySignal.HOME_STARTED, elapsedMs)
            DiagnosticLog.i(
                TAG,
                "WAKE EVENT GM HOME_STARTED hintOnly=true outcome=delivered-recorded",
            )
            readCapabilities(app, "GM HOME_STARTED")
        }
        registerBluetoothReceiver(app)
        registerDisplayListener(app)

        recordRaw(WakeEvent(WakeSignal.PROCESS_START, processStartElapsedMs, "pid=${Process.myPid()}"))
        logEpoch(app, "process-start")
        readCapabilities(app, "process-start")

        scope.launch {
            configuredApInterface = runCatching {
                AppPreferences.getInstance(app).wppApInterface.first()
            }.getOrElse { error ->
                DiagnosticLog.w(
                    TAG,
                    "WAKE EVENT AP config outcome=read-failed error=${safe(error.javaClass.simpleName)}",
                )
                AppPreferences.DEFAULT_WPP_AP_INTERFACE
            }.ifBlank { AppPreferences.DEFAULT_WPP_AP_INTERFACE }
            DiagnosticLog.i(
                TAG,
                "WAKE EVENT AP config interface=${safe(configuredApInterface)} outcome=available",
            )
            sampleApNow("process-start", candidateSource = false)
        }
    }

    fun reportIgnition(rawState: Int) {
        val elapsedMs = SystemClock.elapsedRealtime()
        if (rawState == 2) synchronized(stateLock) { powerEpoch++ }
        record(policy.ignitionState(rawState, elapsedMs), candidateSource = true)
        sampleAp("ignition-$rawState")
    }

    fun reportActivity(callback: PreWakeActivityCallback) {
        record(policy.activity(callback, SystemClock.elapsedRealtime()), candidateSource = true)
    }

    fun reportSessionReady(source: String) {
        val elapsedMs = SystemClock.elapsedRealtime()
        val observation = SessionReadinessObservation(
            source = safe(source),
            elapsedMs = elapsedMs,
        )
        sessionReadinessDispatcher.offer(observation)
    }

    fun reportSurfaceReady(width: Int, height: Int) {
        record(policy.surfaceReady(width, height, SystemClock.elapsedRealtime()), candidateSource = false)
    }

    fun reportSdpPublished(elapsedMs: Long) {
        record(policy.sdpPublished(elapsedMs), candidateSource = false)
    }

    fun reportPhoneDialback(elapsedMs: Long) {
        record(policy.phoneDialback(elapsedMs), candidateSource = false)
    }

    private fun registerGmReceiver(
        context: Context,
        label: String,
        action: String,
        onReceive: (Intent) -> Unit,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != action) {
                    DiagnosticLog.w(TAG, "WAKE EVENT GM $label outcome=ignored-action")
                    return
                }
                runCatching { onReceive(intent) }
                    .onFailure { error ->
                        DiagnosticLog.w(
                            TAG,
                            "WAKE EVENT GM $label outcome=delivery-failed " +
                                "error=${safe(error.javaClass.simpleName)}",
                        )
                    }
            }
        }
        try {
            // GM senders are separate UIDs. Export is deliberate; SYSTEM_STATE and POWER_MODE
            // remain untrusted hints, while protected owner broadcasts still require delivery
            // across a UID boundary. Each action gets its own receiver and outcome line.
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_EXPORTED,
            )
            synchronized(stateLock) { receivers += receiver }
            DiagnosticLog.i(
                TAG,
                "WAKE EVENT GM receiver=$label exported=true outcome=registration-succeeded " +
                    "deliveryProof=false",
            )
        } catch (error: Throwable) {
            DiagnosticLog.w(
                TAG,
                "WAKE EVENT GM receiver=$label exported=true outcome=registration-failed " +
                    "error=${safe(error.javaClass.simpleName)}",
            )
        }
    }

    private fun registerBluetoothReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
                    DiagnosticLog.w(TAG, "WAKE EVENT Bluetooth outcome=ignored-action")
                    return
                }
                if (!intent.hasExtra(BluetoothAdapter.EXTRA_STATE)) {
                    DiagnosticLog.w(TAG, "WAKE EVENT Bluetooth outcome=missing-extra")
                    return
                }
                val raw = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, Int.MIN_VALUE)
                record(policy.bluetoothState(raw, SystemClock.elapsedRealtime()), candidateSource = true)
                sampleAp("bluetooth-$raw")
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            synchronized(stateLock) { receivers += receiver }
            DiagnosticLog.i(TAG, "WAKE EVENT Bluetooth receiver exported=true outcome=registration-succeeded")
        } catch (error: Throwable) {
            DiagnosticLog.w(
                TAG,
                "WAKE EVENT Bluetooth receiver exported=true outcome=registration-failed " +
                    "error=${safe(error.javaClass.simpleName)}",
            )
        }
    }

    private fun registerDisplayListener(context: Context) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (manager == null) {
            DiagnosticLog.w(TAG, "WAKE EVENT display listener outcome=service-unavailable")
            return
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = recordDisplay(manager, displayId, "added")
            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    DiagnosticLog.i(TAG, "WAKE EVENT display=default state=REMOVED outcome=transition-recorded")
                }
            }
            override fun onDisplayChanged(displayId: Int) = recordDisplay(manager, displayId, "changed")
        }
        try {
            manager.registerDisplayListener(listener, null)
            DiagnosticLog.i(TAG, "WAKE EVENT display listener outcome=registration-succeeded")
            recordDisplay(manager, Display.DEFAULT_DISPLAY, "initial")
        } catch (error: Throwable) {
            DiagnosticLog.w(
                TAG,
                "WAKE EVENT display listener outcome=registration-failed " +
                    "error=${safe(error.javaClass.simpleName)}",
            )
        }
    }

    private fun recordDisplay(manager: DisplayManager, displayId: Int, source: String) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        val state = manager.getDisplay(displayId)?.state
        if (state == null) {
            DiagnosticLog.w(TAG, "WAKE EVENT display=default source=$source outcome=not-available")
            return
        }
        val changed = synchronized(stateLock) {
            if (lastDisplayState == state) false else {
                lastDisplayState = state
                true
            }
        }
        if (changed) {
            DiagnosticLog.i(
                TAG,
                "WAKE EVENT display=default state=${displayStateName(state)} source=$source " +
                    "evidence=screen-only outcome=transition-recorded",
            )
        }
    }

    private fun record(
        decision: PreWakePolicyDecision,
        candidateSource: Boolean,
        gmDeliverySignal: GmDeliverySignal? = null,
    ) {
        val event = decision.event
        val outcome = if (event == null) "auxiliary-recorded" else "recorded"
        DiagnosticLog.i(
            TAG,
            "WAKE EVENT kind=${decision.observation.kind} detail=${decision.observation.detail} " +
                "hintOnly=${decision.observation.hintOnly} readiness=${decision.impliesSessionReadiness} " +
                "authorization=${decision.authorizesBehavior} outcome=$outcome",
        )
        if (event != null) {
            recordRaw(event, gmDeliverySignal)
        }

        if (candidateSource && decision.sampling != null) {
            applicationContext?.let { logEpoch(it, decision.observation.kind.name) }
            startSampler(decision.sampling, decision.observation.kind.name)
        }
    }

    private fun recordRaw(event: WakeEvent, gmDeliverySignal: GmDeliverySignal? = null) {
        val summary = synchronized(stateLock) {
            val current = reducer.record(event)
            gmDeliveryEvidence.retain(reducer.retainedAttemptWindows())
            if (gmDeliverySignal != null) {
                gmDeliveryEvidence.observeAt(event.elapsedMs, gmDeliverySignal)
            }
            summaryScheduler.observe(current, reducer.previousSummary)
            current
        }
        if (summary.sessionReadyAtMs != null && summary.surfaceReadyAtMs != null) {
            finishSampler("ready")
            summaryScheduler.ready(summary)
        }
    }

    private fun recordGmDelivery(signal: GmDeliverySignal, elapsedMs: Long) {
        synchronized(stateLock) {
            gmDeliveryEvidence.retain(reducer.retainedAttemptWindows())
            gmDeliveryEvidence.observeAt(elapsedMs, signal)
        }
    }

    private fun allowGmIngress(action: String, rawValue: Int?, elapsedMs: Long): Boolean {
        val epoch = synchronized(stateLock) { powerEpoch }
        val result = gmIngressLimiter.evaluate(action, rawValue, elapsedMs, epoch)
        if (!result.allowed && result.shouldLogSuppression) {
            DiagnosticLog.w(
                TAG,
                "WAKE EVENT GM ingress action=${safe(action)} outcome=" +
                    result.outcome.name.lowercase(),
            )
        }
        return result.allowed
    }

    private fun startSampler(plan: PreWakeSamplingPlan, source: String) {
        synchronized(stateLock) {
            if (samplerJob?.isActive == true) {
                DiagnosticLog.i(
                    TAG,
                    "WAKE EVENT sampler source=$source outcome=coalesced generation=$samplerGeneration",
                )
                return
            }
            samplerGeneration += 1L
            val generation = samplerGeneration
            samplerJob = scope.launch {
                val startedAt = SystemClock.elapsedRealtime()
                val deadline = startedAt + plan.durationMs
                DiagnosticLog.i(
                    TAG,
                    "WAKE EVENT sampler source=$source intervalMs=${plan.intervalMs} " +
                        "durationMs=${plan.durationMs} generation=$generation outcome=started",
                )
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    sampleApNow("sampler-$generation", candidateSource = false)
                    delay(plan.intervalMs)
                }
                if (isActive) {
                    DiagnosticLog.i(
                        TAG,
                        "WAKE EVENT sampler generation=$generation elapsedMs=" +
                            "${SystemClock.elapsedRealtime() - startedAt} outcome=timeout-finished",
                    )
                    synchronized(stateLock) {
                        if (samplerGeneration == generation) samplerJob = null
                    }
                }
            }
        }
    }

    private fun finishSampler(outcome: String) {
        val job = synchronized(stateLock) {
            samplerGeneration += 1L
            samplerJob.also { samplerJob = null }
        }
        if (job != null) {
            job.cancel()
            DiagnosticLog.i(TAG, "WAKE EVENT sampler outcome=${safe(outcome)}-finished")
        }
    }

    private fun emitSummary(summary: WakeSummary, outcome: String) {
        val gmEvidenceFields = synchronized(stateLock) {
            gmDeliveryEvidence.format(summary.attemptId)
        }
        val missing = buildList {
            if (summary.btReadyAtMs == null) add("BLUETOOTH_ON")
            if (summary.apReadyAtMs == null) add("AP_PRESENT")
            if (summary.ignitionOnAtMs == null) add("IGNITION_ON")
            if (summary.activityStartedAtMs == null) add("ACTIVITY_START")
            if (summary.sessionReadyAtMs == null) add("SESSION_READY")
            if (summary.surfaceReadyAtMs == null) add("SURFACE_READY")
        }.joinToString(",").ifEmpty { "none" }
        val line = WakeSummaryFormatter.formatForDiagnosticLog(
            summary = summary,
            gmEvidenceFields = gmEvidenceFields,
            outcome = safe(outcome),
            missing = missing,
        )
        if (outcome == "timeout") DiagnosticLog.w(TAG, line) else DiagnosticLog.i(TAG, line)
    }

    private fun sampleAp(reason: String) {
        scope.launch { sampleApNow(reason, candidateSource = false) }
    }

    private fun sampleApNow(reason: String, candidateSource: Boolean) {
        val interfaceName = configuredApInterface
        val snapshot = try {
            val networkInterface = NetworkInterface.getByName(interfaceName)
            val ip = networkInterface
                ?.takeIf { it.isUp }
                ?.inetAddresses
                ?.toList()
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
            ApSnapshot(ip = ip, error = null)
        } catch (error: Throwable) {
            ApSnapshot(ip = null, error = error.javaClass.simpleName)
        }
        val previous = synchronized(stateLock) {
            val old = lastApSnapshot
            lastApSnapshot = snapshot
            old
        }
        if (previous != snapshot) {
            val decision = policy.accessPoint(interfaceName, snapshot.ip, SystemClock.elapsedRealtime())
            DiagnosticLog.i(
                TAG,
                "AP interface transition interface=${safe(interfaceName)} " +
                    "from=${safe(previous?.ip ?: "absent")} to=${safe(snapshot.ip ?: "absent")} " +
                    "reason=${safe(reason)} error=${safe(snapshot.error ?: "-")} outcome=recorded",
            )
            record(decision, candidateSource)
        }
    }

    private fun readCapabilities(context: Context, reason: String) {
        CAPABILITY_KEYS.forEach { key ->
            try {
                val value = Settings.Global.getString(context.contentResolver, key)
                DiagnosticLog.i(
                    TAG,
                    "WAKE EVENT setting=$key reason=${safe(reason)} available=${value != null} " +
                        "value=${safe(value ?: "-")} error=- outcome=read-complete",
                )
            } catch (error: Throwable) {
                DiagnosticLog.w(
                    TAG,
                    "WAKE EVENT setting=$key reason=${safe(reason)} available=false value=- " +
                        "error=${safe(error.javaClass.simpleName)} outcome=read-failed",
                )
            }
        }
    }

    private fun logEpoch(context: Context, reason: String) {
        val bootCount = try {
            "value=${Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)}"
        } catch (error: Throwable) {
            "error=${safe(error.javaClass.simpleName)}"
        }
        val interactive = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.fold(onSuccess = { it.toString() }, onFailure = { "error:${safe(it.javaClass.simpleName)}" })
        val displayState = runCatching {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.getDisplay(Display.DEFAULT_DISPLAY)?.state?.let(::displayStateName) ?: "unavailable"
        }.getOrElse { "error:${safe(it.javaClass.simpleName)}" }
        DiagnosticLog.i(
            TAG,
            "WAKE EVENT epoch reason=${safe(reason)} pid=${Process.myPid()} " +
                "processStartElapsedMs=$processStartElapsedMs nowElapsedMs=${SystemClock.elapsedRealtime()} " +
                "wallTimeMs=${System.currentTimeMillis()} bootCount=$bootCount " +
                "interactive=$interactive display=$displayState outcome=recorded",
        )
    }

    private fun intExtraOrNull(intent: Intent, name: String): Int? =
        if (intent.hasExtra(name)) intent.getIntExtra(name, Int.MIN_VALUE) else null

    private fun booleanExtra(intent: Intent, name: String): Boolean? =
        if (intent.hasExtra(name)) intent.getBooleanExtra(name, false) else null

    private fun displayStateName(state: Int): String = when (state) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "RAW_$state"
    }

    private fun safe(value: String): String = value
        .replace(Regex("[\\p{Cc}>(),=]"), "_")
        .take(96)

    private data class SessionReadinessObservation(
        val source: String,
        val elapsedMs: Long,
    )

    private data class ApSnapshot(val ip: String?, val error: String?)

    private val CAPABILITY_KEYS = listOf(
        "fid_wifi_disable_status",
        "fid_hotspot_disable_status",
        "hmi_hotspot_disable_status",
        "share_hotspot_data_status",
        "car_data_subscription_status",
        "cellular_network_available_status",
        "data_services_enabled",
    )
}

enum class GmDeliverySignal(val summaryField: String) {
    SYSTEM_STATE("gmSystemState"),
    POWER_MODE("gmPowerMode"),
    POWEROFF_VIEW("gmPoweroffView"),
    HOME_STARTED("gmHomeStarted"),
}

/** Keeps explicit GM-delivery evidence only for the reducer's current and previous attempt. */
class GmDeliveryEvidenceTracker {
    private val observationsByAttempt = mutableMapOf<Long, MutableSet<GmDeliverySignal>>()
    private val pendingBySignal = mutableMapOf<GmDeliverySignal, Long>()
    private var windows: List<WakeAttemptWindow> = emptyList()

    var retainedAttemptIds: Set<Long> = emptySet()
        private set

    fun retain(currentAttemptId: Long, previousAttemptId: Long?) {
        windows = emptyList()
        retainedAttemptIds = buildSet {
            previousAttemptId?.let(::add)
            add(currentAttemptId)
        }
        observationsByAttempt.keys.retainAll(retainedAttemptIds)
    }

    fun retain(windows: List<WakeAttemptWindow>) {
        this.windows = windows.takeLast(2)
        retainedAttemptIds = this.windows.mapTo(linkedSetOf()) { it.attemptId }
        observationsByAttempt.keys.retainAll(retainedAttemptIds)
        val pending = pendingBySignal.toMap()
        pendingBySignal.clear()
        pending.forEach { (signal, elapsedMs) ->
            val attemptId = attemptIdFor(elapsedMs)
            when {
                attemptId != null -> observe(attemptId, signal)
                shouldKeepPending(elapsedMs) -> pendingBySignal[signal] = elapsedMs
            }
        }
    }

    fun observeAt(elapsedMs: Long, signal: GmDeliverySignal) {
        val attemptId = attemptIdFor(elapsedMs)
        if (attemptId == null) {
            pendingBySignal[signal] = elapsedMs
        } else {
            observe(attemptId, signal)
        }
    }

    fun observe(attemptId: Long, signal: GmDeliverySignal) {
        if (attemptId !in retainedAttemptIds) return
        observationsByAttempt.getOrPut(attemptId) { mutableSetOf() } += signal
    }

    fun format(attemptId: Long): String {
        val observed = observationsByAttempt[attemptId].orEmpty()
        return GmDeliverySignal.entries.joinToString(" ") { signal ->
            "${signal.summaryField}=${if (signal in observed) "observed" else "not_observed"}"
        }
    }

    private fun attemptIdFor(elapsedMs: Long): Long? = windows
        .lastOrNull { window ->
            (window.startsAfterMs == null || elapsedMs > window.startsAfterMs) &&
                (window.endsAtMs == null || elapsedMs <= window.endsAtMs)
        }
        ?.attemptId

    private fun shouldKeepPending(elapsedMs: Long): Boolean {
        val newest = windows.lastOrNull() ?: return true
        return newest.endsAtMs != null && elapsedMs > newest.endsAtMs
    }
}
