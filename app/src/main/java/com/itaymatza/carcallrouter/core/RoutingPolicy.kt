package com.itaymatza.carcallrouter.core

/** Pure state machine: no Android, clocks, I/O, polling or hidden global state. */
class RoutingPolicy(
    private val windowMs: Long = 4_000,
    private val verificationMs: Long = 1_500,
    private val minRequestGapMs: Long = 300,
    private val maxRequests: Int = 3
) {
    enum class Phase { IDLE, WAITING, VERIFYING, STABILIZING, RELEASED, SUSPENDED, FAILED }
    enum class Route { TARGET, COMPETING_CAR, OTHER_BLUETOOTH, SPEAKER, HANDSET, WIRED, STREAMING, UNKNOWN }
    data class Snapshot(
        val now: Long,
        val enabled: Boolean = true,
        val authorized: Boolean = true,
        val active: Boolean = true,
        val singleCall: Boolean = true,
        val safeCellularCall: Boolean = true,
        val projection: Boolean? = true,
        val targetAvailable: Boolean = true,
        val route: Route = Route.COMPETING_CAR
    )
    data class Decision(val requestTarget: Boolean = false, val wakeAt: Long? = null)

    var phase = Phase.IDLE; private set
    var reason = "No call session"; private set
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
    }

    fun suspend(reason: String) {
        phase = Phase.SUSPENDED
        this.reason = reason
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
            suspend("Alternative route observed; respecting possible user override")
        }
    }

    fun requestFailed() {
        phase = Phase.FAILED
        reason = "Telecom routing request threw an exception; no blind retry"
        pendingUntil = null
    }

    fun evaluate(s: Snapshot): Decision {
        if (phase in setOf(Phase.IDLE, Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)) return Decision()
        if (!s.authorized) return stop("Telecom authorization is missing or revoked")
        if (!s.safeCellularCall) return stop("Emergency, non-cellular or unclassified call")
        if (!s.singleCall) return stop("Multiple calls / conference: system routing retained")
        if (!s.active) return stop("Call is no longer ACTIVE")
        if (!manual && !s.enabled) return stop("Master toggle is off")
        observeRoute(s.route)
        if (phase == Phase.SUSPENDED) return Decision()
        if (s.now >= deadline) {
            phase = if (s.route == Route.TARGET) Phase.RELEASED else Phase.FAILED
            reason = if (phase == Phase.RELEASED) "Startup window complete; user owns routing" else "Startup deadline expired without BMW verification"
            pendingUntil = null
            return Decision()
        }
        if (!manual && s.projection != true) {
            if (requests > 0 || verified) return stop("Projection disappeared or became unknown")
            reason = "Waiting for verified Android Auto projection"
            return Decision(wakeAt = deadline)
        }
        if (!s.targetAvailable) {
            if (requests > 0 || verified) return stop("Configured BMW call device disappeared")
            reason = "Waiting for configured BMW in Telecom's supported Bluetooth devices"
            return Decision(wakeAt = deadline)
        }
        if (s.route == Route.TARGET) {
            verified = true
            pendingUntil = null
            phase = if (manual) Phase.RELEASED else Phase.STABILIZING
            reason = if (manual) "BMW device verified; one-shot complete" else "BMW device verified; bounded startup guard"
            return Decision(wakeAt = if (manual) null else deadline)
        }
        // Once BMW is established, only a USER-CONFIGURED competing car gets retries.
        if (verified && s.route != Route.COMPETING_CAR) {
            return stop("Route moved away from BMW; respecting user/unknown route change")
        }
        if (requests > 0 && !verified && s.route != initialRoute && s.route != Route.COMPETING_CAR) {
            return stop("New alternative route during request; respecting possible user override")
        }
        val pending = pendingUntil
        if (pending != null && s.now < pending) return Decision(wakeAt = minOf(deadline, pending))
        if (requests > 0 && (manual || s.route != Route.COMPETING_CAR)) {
            phase = Phase.FAILED
            reason = "BMW not verified; no retry against an unconfigured route"
            return Decision()
        }
        if (requests >= maxRequests) {
            phase = Phase.FAILED
            reason = "Bounded request budget exhausted"
            return Decision()
        }
        val earliest = lastRequest?.plus(minRequestGapMs)
        if (earliest != null && s.now < earliest) return Decision(wakeAt = minOf(deadline, earliest))
        requests++
        lastRequest = s.now
        pendingUntil = s.now + verificationMs
        phase = Phase.VERIFYING
        reason = "Request $requests submitted; awaiting BMW device verification"
        return Decision(requestTarget = true, wakeAt = minOf(deadline, pendingUntil!!))
    }

    private fun stop(message: String): Decision { suspend(message); return Decision() }
}
