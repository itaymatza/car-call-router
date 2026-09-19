package org.carcallrouter.companion.core

/** Pure state machine: no Android, clocks, I/O, polling or hidden global state. */
class RoutingPolicy(
    private val windowMs: Long = 4_000,
    private val verificationMs: Long = 1_500,
    private val minRequestGapMs: Long = 300,
    private val maxRequests: Int = 3
) {
    enum class Phase { IDLE, WAITING, VERIFYING, STABILIZING, RELEASED, SUSPENDED, FAILED }
    enum class Route { TARGET, COMPETING_DEVICE, OTHER_BLUETOOTH, SPEAKER, HANDSET, WIRED, STREAMING, UNKNOWN }
    enum class ReasonCode {
        NO_SESSION,
        WAITING_PREREQUISITES,
        EXTERNAL_SUSPEND,
        SETTINGS_CHANGED,
        ALREADY_ACTIVE_BIND,
        CONFERENCE_OBSERVED,
        NO_FRESH_ACTIVE_TRANSITION,
        EXTERNAL_ENDPOINT_REQUEST,
        USER_PAUSED,
        AUTHORIZATION_MISSING,
        UNSAFE_CALL,
        MULTIPLE_CALLS,
        CALL_NOT_ACTIVE,
        DISABLED,
        USER_OVERRIDE,
        STARTUP_COMPLETE,
        STARTUP_DEADLINE_EXPIRED,
        WAITING_PROJECTION,
        PROJECTION_UNKNOWN,
        PROJECTION_DISCONNECTED,
        WAITING_TARGET_HFP,
        TARGET_HFP_UNKNOWN,
        TARGET_HFP_DISCONNECTED,
        WAITING_ENDPOINT_SNAPSHOT,
        WAITING_TARGET_ENDPOINT,
        TARGET_ENDPOINT_CONFIRMED,
        TARGET_ENDPOINT_STABILIZING,
        ROUTE_MOVED_AFTER_CONFIRMATION,
        ALTERNATIVE_ROUTE_DURING_REQUEST,
        REQUEST_NOT_VERIFIED,
        REQUEST_BUDGET_EXHAUSTED,
        REQUEST_SUBMITTED,
        REQUEST_FAILED
    }
    data class Snapshot(
        val now: Long,
        val enabled: Boolean = true,
        val authorized: Boolean = true,
        val active: Boolean = true,
        val singleCall: Boolean = true,
        val safeCellularCall: Boolean = true,
        val projection: Boolean? = true,
        /** null means the Bluetooth profile service cannot currently provide evidence. */
        val targetHfpConnected: Boolean? = true,
        /** null means Telecom has not delivered its first endpoint snapshot yet. */
        val targetAvailable: Boolean? = true,
        val route: Route = Route.COMPETING_DEVICE
    )
    data class Decision(val requestTarget: Boolean = false, val wakeAt: Long? = null)

    var phase = Phase.IDLE; private set
    var reason = "No call session"; private set
    var reasonCode = ReasonCode.NO_SESSION; private set
    var requests = 0; private set
    var verified = false; private set
    private var manual = false
    private var deadline = 0L
    private var pendingUntil: Long? = null
    private var lastRequest: Long? = null
    private var initialRoute = Route.UNKNOWN

    fun begin(now: Long, initial: Route, manualOneShot: Boolean = false) {
        manual = manualOneShot
        deadline = now + windowMs
        initialRoute = initial
        pendingUntil = null
        lastRequest = null
        requests = 0
        verified = false
        phase = Phase.WAITING
        reason = if (manual) "Manual one-shot: awaiting prerequisites" else "New ACTIVE call: awaiting prerequisites"
        reasonCode = ReasonCode.WAITING_PREREQUISITES
    }

    fun suspend(reason: String, code: ReasonCode = ReasonCode.EXTERNAL_SUSPEND) {
        phase = Phase.SUSPENDED
        this.reason = reason
        reasonCode = code
        pendingUntil = null
    }

    /**
     * Record alternative route events synchronously, before queued evaluations can coalesce.
     * Only an explicitly configured competing car may receive startup reassertions.
     * An unknown initial route does not establish that the first baseline route is an override.
     */
    fun observeRoute(route: Route) {
        if (phase in setOf(Phase.IDLE, Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)) return
        val alternative = route in setOf(Route.SPEAKER, Route.HANDSET, Route.WIRED,
            Route.OTHER_BLUETOOTH, Route.STREAMING)
        val changedKnownBaseline = initialRoute != Route.UNKNOWN && route != initialRoute
        if (alternative && (verified || changedKnownBaseline ||
                    (requests > 0 && route != initialRoute))) {
            suspend("Alternative route observed; respecting possible user override", ReasonCode.USER_OVERRIDE)
        }
    }

    fun requestFailed() {
        phase = Phase.FAILED
        reason = "Telecom routing request threw an exception; no blind retry"
        reasonCode = ReasonCode.REQUEST_FAILED
        pendingUntil = null
    }

    fun evaluate(s: Snapshot): Decision {
        if (phase in setOf(Phase.IDLE, Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)) return Decision()
        if (!s.authorized) return stop("Telecom authorization is missing or revoked", ReasonCode.AUTHORIZATION_MISSING)
        if (!s.safeCellularCall) return stop("Emergency, non-cellular or unclassified call", ReasonCode.UNSAFE_CALL)
        if (!s.singleCall) return stop("Multiple calls / conference: system routing retained", ReasonCode.MULTIPLE_CALLS)
        if (!s.active) return stop("Call is no longer ACTIVE", ReasonCode.CALL_NOT_ACTIVE)
        if (!manual && !s.enabled) return stop("Master toggle is off", ReasonCode.DISABLED)
        observeRoute(s.route)
        if (phase == Phase.SUSPENDED) return Decision()
        if (s.now >= deadline) {
            phase = if (s.route == Route.TARGET) Phase.RELEASED else Phase.FAILED
            reason = if (phase == Phase.RELEASED) "Startup window complete; user owns routing" else "Startup deadline expired without target device verification"
            reasonCode = if (phase == Phase.RELEASED) ReasonCode.STARTUP_COMPLETE else ReasonCode.STARTUP_DEADLINE_EXPIRED
            pendingUntil = null
            return Decision()
        }
        if (!manual && s.projection == false) {
            if (requests > 0 || verified) return stop("Projection disconnected", ReasonCode.PROJECTION_DISCONNECTED)
            reason = "Waiting for Android Auto projection"
            reasonCode = ReasonCode.WAITING_PROJECTION
            return Decision(wakeAt = deadline)
        }
        if (!manual && s.projection == null) {
            reason = "Projection state temporarily unknown; routing paused pending fresh evidence"
            reasonCode = ReasonCode.PROJECTION_UNKNOWN
            return Decision(wakeAt = deadline)
        }
        if (s.targetHfpConnected == false) {
            if (requests > 0 || verified) return stop("Configured target disconnected from Bluetooth HFP", ReasonCode.TARGET_HFP_DISCONNECTED)
            reason = "Waiting for configured target Bluetooth HFP connection"
            reasonCode = ReasonCode.WAITING_TARGET_HFP
            return Decision(wakeAt = deadline)
        }
        if (s.targetHfpConnected == null) {
            reason = "Bluetooth HFP state temporarily unknown; routing paused pending fresh evidence"
            reasonCode = ReasonCode.TARGET_HFP_UNKNOWN
            return Decision(wakeAt = deadline)
        }
        if (s.targetAvailable != true) {
            reason = if (s.targetAvailable == null)
                "Waiting for Telecom's first call-endpoint snapshot"
            else
                "Waiting for configured target in Telecom's current call endpoints"
            reasonCode = if (s.targetAvailable == null) ReasonCode.WAITING_ENDPOINT_SNAPSHOT else ReasonCode.WAITING_TARGET_ENDPOINT
            return Decision(wakeAt = deadline)
        }
        if (s.route == Route.TARGET) {
            verified = true
            pendingUntil = null
            phase = if (manual) Phase.RELEASED else Phase.STABILIZING
            reason = if (manual) "target Bluetooth device verified; one-shot complete" else "target Bluetooth device verified; bounded startup guard"
            reasonCode = if (manual) ReasonCode.TARGET_ENDPOINT_CONFIRMED else ReasonCode.TARGET_ENDPOINT_STABILIZING
            return Decision(wakeAt = if (manual) null else deadline)
        }
        // Once Target device is established, only a USER-CONFIGURED competing car gets retries.
        if (verified && s.route != Route.COMPETING_DEVICE) {
            return stop("Route moved away from target device; respecting user/unknown route change", ReasonCode.ROUTE_MOVED_AFTER_CONFIRMATION)
        }
        if (requests > 0 && !verified && s.route != initialRoute && s.route != Route.COMPETING_DEVICE) {
            return stop("New alternative route during request; respecting possible user override", ReasonCode.ALTERNATIVE_ROUTE_DURING_REQUEST)
        }
        val pending = pendingUntil
        if (pending != null && s.now < pending) return Decision(wakeAt = minOf(deadline, pending))
        if (requests > 0 && (manual || s.route != Route.COMPETING_DEVICE)) {
            phase = Phase.FAILED
            reason = "target device not verified; no retry against an unconfigured route"
            reasonCode = ReasonCode.REQUEST_NOT_VERIFIED
            return Decision()
        }
        if (requests >= maxRequests) {
            phase = Phase.FAILED
            reason = "Bounded request budget exhausted"
            reasonCode = ReasonCode.REQUEST_BUDGET_EXHAUSTED
            return Decision()
        }
        val earliest = lastRequest?.plus(minRequestGapMs)
        if (earliest != null && s.now < earliest) return Decision(wakeAt = minOf(deadline, earliest))
        requests++
        lastRequest = s.now
        pendingUntil = s.now + verificationMs
        phase = Phase.VERIFYING
        reason = "Request $requests submitted; awaiting target Bluetooth device verification"
        reasonCode = ReasonCode.REQUEST_SUBMITTED
        return Decision(requestTarget = true, wakeAt = minOf(deadline, pendingUntil!!))
    }

    private fun stop(message: String, code: ReasonCode): Decision { suspend(message, code); return Decision() }
}
