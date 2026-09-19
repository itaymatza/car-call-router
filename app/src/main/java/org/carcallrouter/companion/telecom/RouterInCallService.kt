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
import android.telecom.CallEndpointException
import android.telecom.InCallService
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.ProjectionMonitor
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.RouterSettings
import org.carcallrouter.companion.SessionBridge
import org.carcallrouter.companion.core.RoutingPolicy
import org.carcallrouter.companion.core.RoutingTrace
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.UUID

/** Telecom-bound non-UI companion; never claims the dialer role or manipulates media profiles. */
class RouterInCallService : InCallService(), SessionBridge.Control {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: RouterSettings
    private lateinit var router: AddressedTelecomRouter
    private lateinit var classifier: CellularClassifier
    private lateinit var projectionMonitor: ProjectionMonitor
    private lateinit var hfp: HfpMonitor
    private lateinit var trace: RoutingTrace
    private var projection: Boolean? = null
    private var sequence = 0
    private var disposed = false
    private var policy = RoutingPolicy()
    private var sessionStarted = false
    private var lastPublished = ""
    private var manualSession = false
    private var lastTracePolicy: Pair<RoutingPolicy.Phase, RoutingPolicy.ReasonCode>? = null
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
            policy.suspend(
                "Settings changed during a call; automatic routing paused for this session",
                RoutingPolicy.ReasonCode.SETTINGS_CHANGED
            )
            trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode)
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
        lastTracePolicy = null
        projection = null
        lastPublished = ""
        trace = RoutingTrace(
            now = SystemClock::elapsedRealtime,
            newSessionId = { UUID.randomUUID().toString().replace("-", "").take(12) },
            emit = { RouterLog.event("ROUTING_TRACE", it) }
        )
        settings = RouterSettings(this)
        router = AddressedTelecomRouter(this)
        classifier = CellularClassifier(this)
        hfp = HfpMonitor(this) {
            // Cancellation edges must survive a disconnect/reconnect before queued evaluation.
            val address = settings.targetAddress?.uppercase()
            if (guardHasActed() && address != null && hfp.known && address !in hfp.connected) {
                suspendSessionFromEvent(
                    "target Bluetooth HFP disappeared; this session stays paused",
                    RoutingPolicy.ReasonCode.TARGET_HFP_DISCONNECTED
                )
            }
            queueEvaluation()
        }
        projectionMonitor = ProjectionMonitor(this) { value ->
            projection = value
            if (!manualSession && guardHasActed() && value == false) {
                suspendSessionFromEvent(
                    "Projection disconnected; this session stays paused",
                    RoutingPolicy.ReasonCode.PROJECTION_DISCONNECTED
                )
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
                    suspendSessionFromEvent(
                        "Conference children observed; system retains routing",
                        RoutingPolicy.ReasonCode.CONFERENCE_OBSERVED
                    )
                }
                queueEvaluation()
            }
        }
        val state = call.details.state
        val record = Record(id, callback, isPreActive(state), state)
        records[call] = record
        if (records.size > 1) {
            // Do not let rapid call removal erase the fact that the session was ambiguous.
            suspendSessionFromEvent(
                "Another call observed; system retains routing for this session",
                RoutingPolicy.ReasonCode.MULTIPLE_CALLS
            )
        }
        call.registerCallback(callback, handler)
        RouterLog.event("CALL_ADDED", "call=$id; state=${stateName(state)}; preActiveObserved=${record.sawPreActive}")
        if (!sessionStarted && state == Call.STATE_ACTIVE) {
            // No observed transition: may be process recovery or binding to an old call.
            sessionStarted = true
            trace.begin("automatic", "already_active_bind")
            policy.suspend(
                "Already-active call on service bind; automatic takeover suppressed",
                RoutingPolicy.ReasonCode.ALREADY_ACTIVE_BIND
            )
            trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode)
        }
        queueEvaluation()
    }

    private fun handleState(call: Call, state: Int) {
        val record = records[call] ?: return
        val previous = record.lastState
        record.lastState = state
        if (sessionStarted && state != Call.STATE_ACTIVE) {
            // Use the callback state, not call.details which can already contain a later state.
            suspendSessionFromEvent(
                "Observed ${stateName(state)}; no reassertion on resume",
                RoutingPolicy.ReasonCode.CALL_NOT_ACTIVE
            )
        }
        if (isPreActive(state)) record.sawPreActive = true
        if (state != previous) RouterLog.event("CALL_STATE", "call=${record.id}; ${stateName(previous)} -> ${stateName(state)}")
        if (!sessionStarted && state == Call.STATE_ACTIVE) {
            sessionStarted = true
            manualSession = false
            if (record.sawPreActive && previous != Call.STATE_HOLDING) {
                trace.begin("automatic", "fresh_active_transition")
                policy.begin(SystemClock.elapsedRealtime(), currentRoute())
                trace.event("ACTIVE_TRANSITION", "call" to record.id, "initial_route" to currentRoute())
                RouterLog.event("SESSION_START", "call=${record.id}; detected ACTIVE transition; target=${RouterLog.deviceId(settings.targetAddress)}")
            } else {
                trace.begin("automatic", "active_without_fresh_transition")
                policy.suspend(
                    "No fresh answered/connected transition observed",
                    RoutingPolicy.ReasonCode.NO_FRESH_ACTIVE_TRANSITION
                )
                trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode)
            }
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
            val confirmation = trace.finish(policy.phase, policy.reasonCode, "call_removed")
            if (confirmation != null) {
                settings.recordLastSession(policy.phase.name, policy.reasonCode.name, confirmation.name)
            }
            router.clearSession()
            policy = RoutingPolicy()
            lastTracePolicy = null
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
        policy.observeRoute(currentRoute(), SystemClock.elapsedRealtime())
        // Device names may contain personal data, so log only type + a salted ID.
        RouterLog.event("ENDPOINT", "type=${callEndpoint.endpointType}; id=${RouterLog.deviceId(callEndpoint.identifier.toString())}")
        trace.event(
            "ENDPOINT_CHANGED",
            "type" to callEndpoint.endpointType,
            "id" to RouterLog.deviceId(callEndpoint.identifier.toString()),
            "route" to currentRoute()
        )
        queueEvaluation()
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        router.updateAvailable(availableEndpoints)
        RouterLog.event("AVAILABLE_ENDPOINTS", availableEndpoints.joinToString { "type=${it.endpointType},id=${RouterLog.deviceId(it.identifier.toString())}" })
        trace.event(
            "ENDPOINT_SNAPSHOT",
            "count" to availableEndpoints.size,
            "bluetooth_count" to availableEndpoints.count { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
        )
        queueEvaluation()
    }

    /**
     * API 37 virtual callback. The exact public signature is enforced by the service harness until
     * the API 37 platform package is available to hosted sdkmanager builds.
     */
    @Suppress("unused")
    fun onCallEndpointRequested(callEndpoint: CallEndpoint) {
        val own = router.consumeOwnRequest(callEndpoint)
        RouterLog.event(
            "ENDPOINT_REQUEST_OBSERVED",
            "type=${callEndpoint.endpointType}; ownPending=$own; id=${RouterLog.deviceId(callEndpoint.identifier.toString())}"
        )
        trace.event(
            "ENDPOINT_REQUEST_OBSERVED",
            "type" to callEndpoint.endpointType,
            "id" to RouterLog.deviceId(callEndpoint.identifier.toString()),
            "origin" to if (own) "self" else "external"
        )
        if (sessionStarted && !own) {
            suspendSessionFromEvent(
                "Another in-call UI requested an endpoint; respecting possible user override",
                RoutingPolicy.ReasonCode.EXTERNAL_ENDPOINT_REQUEST
            )
        }
        queueEvaluation()
    }

    private fun guardHasActed(): Boolean = sessionStarted &&
        policy.phase in setOf(RoutingPolicy.Phase.WAITING, RoutingPolicy.Phase.VERIFYING,
            RoutingPolicy.Phase.STABILIZING) && (policy.requests > 0 || policy.verified)

    /** Latch safety-relevant events before later callbacks can replace their state. */
    private fun suspendSessionFromEvent(reason: String, code: RoutingPolicy.ReasonCode) {
        if (disposed) return
        sessionStarted = true
        trace.begin("automatic", "safety_event")
        policy.suspend(reason, code)
        handler.removeCallbacks(tick)
        RouterLog.event("SESSION_PAUSED_EVENT", reason)
        trace.event("SESSION_SUSPENDED", "reason" to code)
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
            endpointRevision = router.endpointRevision(),
            route = currentRoute()
        ))
        val policyState = policy.phase to policy.reasonCode
        if (policyState != lastTracePolicy) {
            lastTracePolicy = policyState
            trace.event(
                "POLICY_STATE",
                "phase" to policy.phase,
                "reason" to policy.reasonCode,
                "attempts" to policy.requests
            )
        }
        if (decision.requestTarget && target.endpoint != null) {
            val attempt = requireNotNull(decision.requestAttempt)
            try {
                RouterLog.event("ROUTE_REQUEST", "target=${RouterLog.deviceId(address)}; attempt=${policy.requests}; mode=${if (manualSession) "manual" else "auto"}; backend=Telecom.requestCallEndpointChange; basis=${target.basis}")
                trace.event(
                    "REQUEST_SUBMITTED",
                    "attempt" to policy.requests,
                    "mode" to if (manualSession) "manual" else "automatic",
                    "basis" to target.basis,
                    "target" to RouterLog.deviceId(address)
                )
                router.request(
                    target.endpoint,
                    accepted = {
                        policy.requestSucceeded(attempt, SystemClock.elapsedRealtime())
                        RouterLog.event("ROUTE_ACCEPTED", "attempt=$attempt; Telecom completed the request; awaiting endpoint observation")
                        trace.event("REQUEST_ACCEPTED", "attempt" to attempt)
                        queueEvaluation()
                    },
                    rejected = { error ->
                        val mapped = when (error.code) {
                            CallEndpointException.ERROR_REQUEST_TIME_OUT -> RoutingPolicy.RequestError.TIMEOUT
                            CallEndpointException.ERROR_ENDPOINT_DOES_NOT_EXIST -> RoutingPolicy.RequestError.ENDPOINT_GONE
                            CallEndpointException.ERROR_ANOTHER_REQUEST -> RoutingPolicy.RequestError.CANCELLED_BY_OTHER
                            else -> RoutingPolicy.RequestError.UNSPECIFIED
                        }
                        policy.requestFailed(attempt, mapped)
                        RouterLog.event("ROUTE_REJECTED", "attempt=$attempt; code=${error.code}; class=$mapped")
                        trace.event("REQUEST_REJECTED", "attempt" to attempt, "code" to error.code, "class" to mapped)
                        queueEvaluation()
                    }
                )
            } catch (e: RuntimeException) {
                policy.requestFailed(attempt, RoutingPolicy.RequestError.RUNTIME_EXCEPTION)
                RouterLog.event("ROUTE_ERROR", "attempt=$attempt; type=${e.javaClass.simpleName}")
                trace.event("REQUEST_ERROR", "attempt" to attempt, "type" to e.javaClass.simpleName)
            }
        }
        val route = currentRoute()
        val sco = when {
            address == null -> false
            !hfp.known -> null
            else -> address in hfp.audioConnected
        }
        if (route == RoutingPolicy.Route.TARGET) {
            trace.confirmTelecom()
            if (sco == true) trace.confirmTargetHfpAudio()
        }
        val state = "Service: bound\nProjection: ${projection ?: "unknown"}\nCall: ${if (active) "ACTIVE" else "not active"}; live=${live.size}\nSafety: $lastSafety\nSelected device: endpoint resolved=${target.endpoint != null}; HFP connected=${hfpConnected ?: "unknown"}; SCO=${sco ?: "unknown"}\nEndpoint resolution: ${target.reason}\nRoute: $route\nController: ${policy.phase}; attempts=${policy.requests}; reason=${policy.reasonCode}\n${policy.reason}"
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
        trace.begin("manual", "route_now")
        policy.begin(now, currentRoute(), manualOneShot = true)
        manualCooldownUntil = now + 1_500
        RouterLog.event("MANUAL_TEST", "One request only; projection gate bypassed explicitly")
        evaluate()
    }

    override fun pauseSession() {
        sessionStarted = true
        trace.begin("manual", "pause")
        policy.suspend("Paused by user for this call session", RoutingPolicy.ReasonCode.USER_PAUSED)
        handler.removeCallbacks(tick)
        RouterLog.event("USER_PAUSE", "No further requests this session; an already-submitted request cannot be recalled")
        trace.event("SESSION_SUSPENDED", "reason" to policy.reasonCode)
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
        trace.finish(policy.phase, policy.reasonCode, "service_stopped")
        router.clearSession()
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
