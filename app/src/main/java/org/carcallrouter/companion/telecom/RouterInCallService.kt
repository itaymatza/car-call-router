@file:Suppress("DEPRECATION")
package org.carcallrouter.companion.telecom

import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.ProjectionMonitor
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.RouterSettings
import org.carcallrouter.companion.SessionBridge
import org.carcallrouter.companion.core.RoutingPolicy
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/** Telecom-bound non-UI companion; never claims the dialer role or manipulates media profiles. */
class RouterInCallService : InCallService(), SessionBridge.Control {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: RouterSettings
    private lateinit var router: AddressedTelecomRouter
    private lateinit var classifier: CellularClassifier
    private lateinit var projectionMonitor: ProjectionMonitor
    private lateinit var hfp: HfpMonitor
    private var projection: Boolean? = null
    private var sequence = 0
    private var disposed = false
    private var policy = RoutingPolicy()
    private var sessionStarted = false
    private var lastPublished = ""
    private var manualSession = false
    private var lastSafety = "No calls"
    private var manualCooldownUntil = 0L
    private data class Record(val id: Int, val callback: Call.Callback, var sawPreActive: Boolean, var lastState: Int)
    private val records = IdentityHashMap<Call, Record>()
    private val tick = Runnable {
        // One-shot deadline verification, not continuous polling.
        evaluate()
    }
    private val evaluateEvent = Runnable { evaluate() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "last_bound" && sessionStarted) {
            policy.suspend("Settings changed during a call; automatic routing paused for this session")
            queueEvaluation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startObservers()
    }

    private fun startObservers() {
        disposed = false
        policy = RoutingPolicy()
        sessionStarted = false
        manualSession = false
        projection = null
        lastPublished = ""
        settings = RouterSettings(this)
        router = AddressedTelecomRouter(this)
        classifier = CellularClassifier(this)
        hfp = HfpMonitor(this) {
            // Cancellation edges must survive a disconnect/reconnect before queued evaluation.
            val address = settings.targetAddress?.uppercase()
            if (guardHasActed() && address != null && hfp.known && address !in hfp.connected) {
                suspendSessionFromEvent("target Bluetooth HFP disappeared; this session stays paused")
            }
            queueEvaluation()
        }
        projectionMonitor = ProjectionMonitor(this) { value ->
            projection = value
            if (!manualSession && guardHasActed() && value == false) {
                suspendSessionFromEvent("Projection disconnected; this session stays paused")
            }
            queueEvaluation()
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        SessionBridge.controller = WeakReference(this)
        RouterLog.event("SERVICE_CREATE", "non-UI service; authorized=${Access.ongoingCalls(this)}")
        hfp.start()
        projectionMonitor.start()
    }

    override fun onBind(intent: Intent): IBinder {
        if (disposed) startObservers()
        settings.markBound()
        RouterLog.event("TELECOM_BOUND", "System bound the service")
        return requireNotNull(super.onBind(intent))
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val id = ++sequence
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) { handleState(call, state) }
            override fun onDetailsChanged(call: Call, details: Call.Details) { handleState(call, details.state) }
            override fun onChildrenChanged(call: Call, children: MutableList<Call>) {
                if (children.isNotEmpty()) {
                    suspendSessionFromEvent("Conference children observed; system retains routing")
                }
                queueEvaluation()
            }
        }
        val state = call.details.state
        val record = Record(id, callback, isPreActive(state), state)
        records[call] = record
        if (records.size > 1) {
            // Do not let rapid call removal erase the fact that the session was ambiguous.
            suspendSessionFromEvent("Another call observed; system retains routing for this session")
        }
        call.registerCallback(callback, handler)
        RouterLog.event("CALL_ADDED", "call=$id; state=${stateName(state)}; preActiveObserved=${record.sawPreActive}")
        if (!sessionStarted && state == Call.STATE_ACTIVE) {
            // No observed transition: may be process recovery or binding to an old call.
            sessionStarted = true
            policy.suspend("Already-active call on service bind; automatic takeover suppressed")
        }
        queueEvaluation()
    }

    private fun handleState(call: Call, state: Int) {
        val record = records[call] ?: return
        val previous = record.lastState
        record.lastState = state
        if (sessionStarted && state != Call.STATE_ACTIVE) {
            // Use the callback state, not call.details which can already contain a later state.
            suspendSessionFromEvent("Observed ${stateName(state)}; no reassertion on resume")
        }
        if (isPreActive(state)) record.sawPreActive = true
        if (state != previous) RouterLog.event("CALL_STATE", "call=${record.id}; ${stateName(previous)} -> ${stateName(state)}")
        if (!sessionStarted && state == Call.STATE_ACTIVE) {
            sessionStarted = true
            manualSession = false
            if (record.sawPreActive && previous != Call.STATE_HOLDING) {
                policy.begin(SystemClock.elapsedRealtime(), currentRoute())
                RouterLog.event("SESSION_START", "call=${record.id}; detected ACTIVE transition; target=${RouterLog.deviceId(settings.targetAddress)}")
            } else policy.suspend("No fresh answered/connected transition observed")
        }
        queueEvaluation()
    }

    override fun onCallRemoved(call: Call) {
        records.remove(call)?.let {
            call.unregisterCallback(it.callback)
            RouterLog.event("CALL_REMOVED", "call=${it.id}")
        }
        if (records.isEmpty()) {
            handler.removeCallbacks(tick)
            policy = RoutingPolicy()
            sessionStarted = false
            manualSession = false
            manualCooldownUntil = 0L
            RouterLog.event("SESSION_END", "No audio reset, disconnect, A2DP or projection operation performed")
        }
        queueEvaluation()
        super.onCallRemoved(call)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        router.updateCurrent(callEndpoint)
        policy.observeRoute(currentRoute())
        // Device names may contain personal data, so log only type + a salted ID.
        RouterLog.event("ENDPOINT", "type=${callEndpoint.endpointType}; id=${RouterLog.deviceId(callEndpoint.identifier.toString())}")
        queueEvaluation()
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        router.updateAvailable(availableEndpoints)
        RouterLog.event("AVAILABLE_ENDPOINTS", availableEndpoints.joinToString { "type=${it.endpointType},id=${RouterLog.deviceId(it.identifier.toString())}" })
        queueEvaluation()
    }

    /**
     * Forward-compatible API 37 callback. Compiling against API 36 keeps the build on the latest
     * stable SDK available to CI; Android 37 dispatches this same virtual method signature.
     */
    @Suppress("unused")
    fun onCallEndpointRequested(callEndpoint: CallEndpoint) {
        val own = router.isOwnPendingRequest(callEndpoint)
        RouterLog.event(
            "ENDPOINT_REQUEST_OBSERVED",
            "type=${callEndpoint.endpointType}; ownPending=$own; id=${RouterLog.deviceId(callEndpoint.identifier.toString())}"
        )
        if (sessionStarted && !own) {
            suspendSessionFromEvent("Another in-call UI requested an endpoint; respecting possible user override")
        }
    }

    private fun guardHasActed(): Boolean = sessionStarted &&
        policy.phase in setOf(RoutingPolicy.Phase.WAITING, RoutingPolicy.Phase.VERIFYING,
            RoutingPolicy.Phase.STABILIZING) && (policy.requests > 0 || policy.verified)

    /** Latch safety-relevant events before later callbacks can replace their state. */
    private fun suspendSessionFromEvent(reason: String) {
        if (disposed) return
        sessionStarted = true
        policy.suspend(reason)
        handler.removeCallbacks(tick)
        RouterLog.event("SESSION_PAUSED_EVENT", reason)
    }

    private fun queueEvaluation() {
        if (disposed) return
        handler.removeCallbacks(evaluateEvent)
        handler.post(evaluateEvent)
    }

    private fun currentRoute(): RoutingPolicy.Route {
        val current = router.current() ?: return RoutingPolicy.Route.UNKNOWN
        return when (current.endpointType) {
            CallEndpoint.TYPE_SPEAKER -> RoutingPolicy.Route.SPEAKER
            CallEndpoint.TYPE_EARPIECE -> RoutingPolicy.Route.HANDSET
            CallEndpoint.TYPE_WIRED_HEADSET -> RoutingPolicy.Route.WIRED
            CallEndpoint.TYPE_STREAMING -> RoutingPolicy.Route.STREAMING
            CallEndpoint.TYPE_BLUETOOTH -> when {
                !hfp.known -> RoutingPolicy.Route.UNKNOWN
                router.isCurrent(targetEndpoint().endpoint) -> RoutingPolicy.Route.TARGET
                router.isCurrent(competitorEndpoint().endpoint) -> RoutingPolicy.Route.COMPETING_DEVICE
                else -> RoutingPolicy.Route.OTHER_BLUETOOTH
            }
            else -> RoutingPolicy.Route.UNKNOWN
        }
    }

    private fun targetEndpoint(): AddressedTelecomRouter.Target {
        val address = settings.targetAddress?.uppercase()
        return router.target(
            savedLabel = settings.targetName,
            targetHfpConnected = address != null && hfp.known && address in hfp.connected,
            connectedHfpCount = if (hfp.known) hfp.connected.size else 0
        )
    }

    private fun competitorEndpoint(): AddressedTelecomRouter.Target {
        val address = settings.competitorAddress?.uppercase()
        return router.target(
            savedLabel = settings.competitorName,
            targetHfpConnected = address != null && hfp.known && address in hfp.connected,
            connectedHfpCount = if (hfp.known) hfp.connected.size else 0
        )
    }

    private fun liveCalls(): List<Call> = records.keys.filter {
        it.details.state != Call.STATE_DISCONNECTED && it.details.state != Call.STATE_DISCONNECTING
    }

    private fun evaluate() {
        if (disposed) return
        handler.removeCallbacks(tick)
        val live = liveCalls()
        val active = live.any { it.details.state == Call.STATE_ACTIVE }
        val rejections = live.mapNotNull(classifier::rejection)
        val safe = Access.runtimeGranted(this) && live.isNotEmpty() && rejections.isEmpty()
        lastSafety = when {
            !Access.runtimeGranted(this) -> "Required runtime permissions not granted"
            rejections.isNotEmpty() -> rejections.joinToString("; ")
            live.isEmpty() -> "No live calls"
            else -> "SIM-backed call and emergency-number checks passed"
        }
        val address = settings.targetAddress?.uppercase()
        val hfpConnected = when {
            address == null -> false
            !hfp.known -> null
            else -> address in hfp.connected
        }
        val target = targetEndpoint()
        val endpointAvailable = if (router.hasAvailableSnapshot()) target.endpoint != null else null
        val now = SystemClock.elapsedRealtime()
        val decision = policy.evaluate(RoutingPolicy.Snapshot(
            now = now,
            enabled = settings.enabled,
            authorized = Access.ongoingCalls(this),
            active = active,
            singleCall = live.size == 1 && records.size == 1,
            safeCellularCall = safe,
            projection = projection,
            targetHfpConnected = hfpConnected,
            targetAvailable = endpointAvailable,
            route = currentRoute()
        ))
        if (decision.requestTarget && target.endpoint != null) {
            try {
                RouterLog.event("ROUTE_REQUEST", "target=${RouterLog.deviceId(address)}; attempt=${policy.requests}; mode=${if (manualSession) "manual" else "auto"}; backend=Telecom.requestCallEndpointChange; basis=${target.basis}")
                router.request(
                    target.endpoint,
                    accepted = {
                        RouterLog.event("ROUTE_ACCEPTED", "Telecom accepted the request; awaiting endpoint callback verification")
                        queueEvaluation()
                    },
                    rejected = { error ->
                        policy.requestFailed()
                        RouterLog.event("ROUTE_REJECTED", "code=${error.code}")
                        queueEvaluation()
                    }
                )
            } catch (e: RuntimeException) {
                policy.requestFailed()
                RouterLog.event("ROUTE_ERROR", e.javaClass.simpleName)
            }
        }
        val sco = address != null && address in hfp.audioConnected
        val state = "Service: bound\nProjection: ${projection ?: "unknown"}\nCall: ${if (active) "ACTIVE" else "not active"}; live=${live.size}\nSafety: $lastSafety\nSelected device: endpoint resolved=${target.endpoint != null}; HFP connected=${hfpConnected ?: "unknown"}; SCO=$sco\nEndpoint resolution: ${target.reason}\nRoute: ${currentRoute()}\nController: ${policy.phase}; attempts=${policy.requests}\n${policy.reason}"
        if (state != lastPublished) {
            lastPublished = state
            RouterLog.event("STATUS", state.replace("\n", " | "))
            SessionBridge.publish(state)
        }
        if (policy.phase !in setOf(RoutingPolicy.Phase.SUSPENDED, RoutingPolicy.Phase.FAILED)) {
            decision.wakeAt?.let { handler.postDelayed(tick, (it - SystemClock.elapsedRealtime()).coerceAtLeast(1)) }
        }
    }

    override fun routeNow() {
        val now = SystemClock.elapsedRealtime()
        if (now < manualCooldownUntil) {
            RouterLog.event("MANUAL_TEST", "A request is already within its verification window")
            return
        }
        // This explicit one-shot bypasses only auto toggle/projection, NOT safety/identity checks.
        sessionStarted = true
        manualSession = true
        policy.begin(now, currentRoute(), manualOneShot = true)
        manualCooldownUntil = now + 1_500
        RouterLog.event("MANUAL_TEST", "One request only; projection gate bypassed explicitly")
        evaluate()
    }

    override fun pauseSession() {
        sessionStarted = true
        policy.suspend("Paused by user for this call session")
        handler.removeCallbacks(tick)
        RouterLog.event("USER_PAUSE", "No further requests this session; an already-submitted request cannot be recalled")
        evaluate()
    }

    override fun onUnbind(intent: Intent): Boolean {
        RouterLog.event("TELECOM_UNBOUND", "System unbound service")
        stopObservers()
        return super.onUnbind(intent)
    }

    private fun stopObservers() {
        if (disposed) return
        disposed = true
        handler.removeCallbacksAndMessages(null)
        records.forEach { (call, record) -> call.unregisterCallback(record.callback) }
        records.clear()
        projectionMonitor.close()
        hfp.close()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (SessionBridge.controller?.get() === this) SessionBridge.controller = null
        SessionBridge.publish("No Telecom service bound. This is normal between calls.")
    }

    override fun onDestroy() { stopObservers(); super.onDestroy() }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("Call Route Companion (redacted)")
        writer.println(SessionBridge.status)
        writer.println(RouterLog.recentText())
    }

    companion object {
        private fun isPreActive(state: Int) = state in setOf(Call.STATE_NEW, Call.STATE_RINGING, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT)
        private fun stateName(state: Int) = when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
            else -> "STATE_$state"
        }
    }
}
