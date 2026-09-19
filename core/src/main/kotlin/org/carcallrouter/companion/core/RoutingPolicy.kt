package org.carcallrouter.companion.core

/** Pure state machine: no Android, clocks, I/O, polling or hidden global state. */
class RoutingPolicy(
    private val evidenceWindowMs: Long = 10_000,
    private val actionWindowMs: Long = 5_500,
    private val platformRequestTimeoutMs: Long = 2_500,
    private val minRequestGapMs: Long = 2_500,
    private val resultCallbackGraceMs: Long = 500,
    private val routeDebounceMs: Long = 1_000,
    private val maxRequests: Int = 3
) {
    enum class Phase { IDLE, WAITING, VERIFYING, STABILIZING, RELEASED, SUSPENDED, FAILED }
    enum class Route { TARGET, COMPETING_DEVICE, OTHER_BLUETOOTH, SPEAKER, HANDSET, WIRED, STREAMING, UNKNOWN }
    enum class RequestError { TIMEOUT, ENDPOINT_GONE, CANCELLED_BY_OTHER, UNSPECIFIED, RUNTIME_EXCEPTION }
    enum class ReasonCode {
        NO_SESSION, WAITING_PREREQUISITES, EXTERNAL_SUSPEND, SETTINGS_CHANGED,
        ALREADY_ACTIVE_BIND, CONFERENCE_OBSERVED, NO_FRESH_ACTIVE_TRANSITION,
        EXTERNAL_ENDPOINT_REQUEST, USER_PAUSED, AUTHORIZATION_MISSING, UNSAFE_CALL,
        MULTIPLE_CALLS, CALL_NOT_ACTIVE, DISABLED, USER_OVERRIDE, STARTUP_COMPLETE,
        EVIDENCE_DEADLINE_EXPIRED, ACTION_DEADLINE_EXPIRED, WAITING_PROJECTION,
        PROJECTION_UNKNOWN, PROJECTION_DISCONNECTED, WAITING_TARGET_HFP,
        TARGET_HFP_UNKNOWN, TARGET_HFP_DISCONNECTED, WAITING_ENDPOINT_SNAPSHOT,
        WAITING_TARGET_ENDPOINT, WAITING_FRESH_ENDPOINT_SNAPSHOT, TARGET_ENDPOINT_CONFIRMED,
        TARGET_ENDPOINT_STABILIZING, ROUTE_MOVED_AFTER_CONFIRMATION,
        ALTERNATIVE_ROUTE_DURING_REQUEST, ALTERNATIVE_ROUTE_DEBOUNCE,
        REQUEST_NOT_VERIFIED, REQUEST_ACCEPTED, REQUEST_ACCEPTED_NOT_OBSERVED,
        REQUEST_TIMED_OUT, REQUEST_ENDPOINT_GONE, REQUEST_CANCELLED_BY_OTHER,
        REQUEST_BUDGET_EXHAUSTED, REQUEST_SUBMITTED, REQUEST_FAILED
    }

    data class Snapshot(
        val now: Long,
        val enabled: Boolean,
        val authorized: Boolean,
        val active: Boolean,
        val singleCall: Boolean,
        val safeCellularCall: Boolean,
        val projection: Boolean?,
        /** null means the Bluetooth profile service cannot currently provide evidence. */
        val targetHfpConnected: Boolean?,
        /** null means Telecom has not delivered its first endpoint snapshot yet. */
        val targetAvailable: Boolean?,
        /** Monotonically increases for every available-endpoint callback in this call session. */
        val endpointRevision: Long,
        val route: Route
    )

    data class Decision(
        val requestTarget: Boolean = false,
        val requestAttempt: Int? = null,
        val wakeAt: Long? = null
    )

    var phase = Phase.IDLE; private set
    var reason = "No call session"; private set
    var reasonCode = ReasonCode.NO_SESSION; private set
    var requests = 0; private set
    var verified = false; private set

    private var manual = false
    private var evidenceDeadline = 0L
    private var actionDeadline: Long? = null
    private var inFlightAttempt: Int? = null
    private var inFlightUntil: Long? = null
    private var inFlightRevision = 0L
    private var acceptedUntil: Long? = null
    private var waitForRevisionAfter: Long? = null
    private var lastRequest: Long? = null
    private var pendingAlternative: Route? = null
    private var initialRoute = Route.UNKNOWN

    fun begin(now: Long, initial: Route, manualOneShot: Boolean = false) {
        manual = manualOneShot
        evidenceDeadline = now + evidenceWindowMs
        actionDeadline = null
        initialRoute = initial
        inFlightAttempt = null
        inFlightUntil = null
        acceptedUntil = null
        waitForRevisionAfter = null
        lastRequest = null
        pendingAlternative = null
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
        clearPendingRequest()
        pendingAlternative = null
    }

    /** Latch route edges before queued evaluations can coalesce them. */
    fun observeRoute(route: Route, now: Long) {
        if (phase in terminalPhases) return
        val alternative = route in alternativeRoutes
        val changedKnownBaseline = initialRoute != Route.UNKNOWN && route != initialRoute
        val shouldRespect = alternative && (verified || changedKnownBaseline ||
            (requests > 0 && route != initialRoute))
        if (!shouldRespect) {
            if (!alternative) pendingAlternative = null
            return
        }
        val debounceUntil = lastRequest?.plus(routeDebounceMs)
        if (requests > 0 && debounceUntil != null && now < debounceUntil) {
            pendingAlternative = route
            reason = "Alternative route observed during request settling; awaiting confirmation"
            reasonCode = ReasonCode.ALTERNATIVE_ROUTE_DEBOUNCE
            return
        }
        suspend("Alternative route observed; respecting possible user override", ReasonCode.USER_OVERRIDE)
    }

    fun requestSucceeded(attempt: Int, now: Long) {
        if (inFlightAttempt != attempt || phase in terminalPhases) return
        clearPendingRequest()
        acceptedUntil = now + resultCallbackGraceMs
        phase = Phase.VERIFYING
        reason = "Telecom completed request $attempt; awaiting endpoint observation"
        reasonCode = ReasonCode.REQUEST_ACCEPTED
    }

    fun requestFailed(attempt: Int, error: RequestError) {
        if (inFlightAttempt != attempt || phase in terminalPhases) return
        val failedRevision = inFlightRevision
        clearPendingRequest()
        when (error) {
            RequestError.TIMEOUT -> {
                phase = Phase.WAITING
                reason = "Telecom request $attempt timed out; retry allowed within budget"
                reasonCode = ReasonCode.REQUEST_TIMED_OUT
            }
            RequestError.ENDPOINT_GONE -> {
                phase = Phase.WAITING
                waitForRevisionAfter = failedRevision
                reason = "Requested endpoint disappeared; awaiting a fresh endpoint snapshot"
                reasonCode = ReasonCode.REQUEST_ENDPOINT_GONE
            }
            RequestError.CANCELLED_BY_OTHER -> suspend(
                "Another endpoint request cancelled this request; respecting external routing",
                ReasonCode.REQUEST_CANCELLED_BY_OTHER
            )
            RequestError.UNSPECIFIED, RequestError.RUNTIME_EXCEPTION -> fail(
                "Telecom routing request failed; no blind retry",
                ReasonCode.REQUEST_FAILED
            )
        }
    }

    fun evaluate(s: Snapshot): Decision {
        if (phase in terminalPhases) return Decision()
        if (!s.authorized) return stop("Telecom authorization is missing or revoked", ReasonCode.AUTHORIZATION_MISSING)
        if (!s.safeCellularCall) return stop("Emergency, non-cellular or unclassified call", ReasonCode.UNSAFE_CALL)
        if (!s.singleCall) return stop("Multiple calls / conference: system routing retained", ReasonCode.MULTIPLE_CALLS)
        if (!s.active) return stop("Call is no longer ACTIVE", ReasonCode.CALL_NOT_ACTIVE)
        if (!manual && !s.enabled) return stop("Master toggle is off", ReasonCode.DISABLED)

        observeRoute(s.route, s.now)
        if (phase == Phase.SUSPENDED) return Decision()
        pendingAlternative?.let { alternative ->
            if (s.route == alternative) {
                val until = requireNotNull(lastRequest) + routeDebounceMs
                if (s.now < until) return Decision(wakeAt = minOf(currentDeadline(), until))
                return stop("Alternative route persisted after request settling", ReasonCode.USER_OVERRIDE)
            }
            pendingAlternative = null
        }

        val deadline = currentDeadline()
        if (s.now >= deadline) {
            phase = if (s.route == Route.TARGET) Phase.RELEASED else Phase.FAILED
            val evidenceExpired = actionDeadline == null
            reason = when {
                phase == Phase.RELEASED -> "Startup window complete; user owns routing"
                evidenceExpired -> "Evidence deadline expired before routing could start"
                else -> "Routing action deadline expired without target verification"
            }
            reasonCode = when {
                phase == Phase.RELEASED -> ReasonCode.STARTUP_COMPLETE
                evidenceExpired -> ReasonCode.EVIDENCE_DEADLINE_EXPIRED
                else -> ReasonCode.ACTION_DEADLINE_EXPIRED
            }
            clearPendingRequest()
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
            reason = if (s.targetAvailable == null) "Waiting for Telecom's first call-endpoint snapshot"
            else "Waiting for configured target in Telecom's current call endpoints"
            reasonCode = if (s.targetAvailable == null) ReasonCode.WAITING_ENDPOINT_SNAPSHOT else ReasonCode.WAITING_TARGET_ENDPOINT
            return Decision(wakeAt = deadline)
        }

        waitForRevisionAfter?.let { required ->
            if (s.endpointRevision <= required) {
                reason = "Waiting for a fresh endpoint snapshot before retrying"
                reasonCode = ReasonCode.WAITING_FRESH_ENDPOINT_SNAPSHOT
                return Decision(wakeAt = deadline)
            }
            waitForRevisionAfter = null
        }

        if (s.route == Route.TARGET) {
            verified = true
            clearPendingRequest()
            acceptedUntil = null
            if (actionDeadline == null) actionDeadline = s.now + actionWindowMs
            phase = if (manual) Phase.RELEASED else Phase.STABILIZING
            reason = if (manual) "target Bluetooth device verified; one-shot complete" else "target Bluetooth device verified; bounded startup guard"
            reasonCode = if (manual) ReasonCode.TARGET_ENDPOINT_CONFIRMED else ReasonCode.TARGET_ENDPOINT_STABILIZING
            return Decision(wakeAt = if (manual) null else currentDeadline())
        }

        acceptedUntil?.let { until ->
            if (s.now < until) return Decision(wakeAt = minOf(deadline, until))
            return fail("Telecom reported success but the target endpoint was not observed", ReasonCode.REQUEST_ACCEPTED_NOT_OBSERVED)
        }
        inFlightAttempt?.let {
            val until = requireNotNull(inFlightUntil)
            if (s.now < until) return Decision(wakeAt = minOf(deadline, until))
            // A generation token makes a late callback from this attempt harmless.
            clearPendingRequest()
            reason = "Telecom request callback deadline expired; bounded retry allowed"
            reasonCode = ReasonCode.REQUEST_TIMED_OUT
        }

        if (verified && s.route != Route.COMPETING_DEVICE) {
            return stop("Route moved away from target device; respecting user/unknown route change", ReasonCode.ROUTE_MOVED_AFTER_CONFIRMATION)
        }
        if (requests > 0 && !verified && s.route != initialRoute && s.route != Route.COMPETING_DEVICE) {
            return stop("New alternative route during request; respecting possible user override", ReasonCode.ALTERNATIVE_ROUTE_DURING_REQUEST)
        }
        if (requests > 0 && (manual || s.route != Route.COMPETING_DEVICE)) {
            return fail("target device not verified; no retry against an unconfigured route", ReasonCode.REQUEST_NOT_VERIFIED)
        }
        if (requests >= maxRequests) {
            return fail("Bounded request budget exhausted", ReasonCode.REQUEST_BUDGET_EXHAUSTED)
        }

        val earliestRequest = lastRequest?.plus(minRequestGapMs)
        if (earliestRequest != null && s.now < earliestRequest) {
            return Decision(wakeAt = minOf(currentDeadline(), earliestRequest))
        }

        if (actionDeadline == null) actionDeadline = s.now + actionWindowMs
        requests++
        lastRequest = s.now
        inFlightAttempt = requests
        inFlightUntil = s.now + platformRequestTimeoutMs
        inFlightRevision = s.endpointRevision
        phase = Phase.VERIFYING
        reason = "Request $requests submitted; awaiting Telecom outcome and target verification"
        reasonCode = ReasonCode.REQUEST_SUBMITTED
        return Decision(true, requests, minOf(currentDeadline(), requireNotNull(inFlightUntil)))
    }

    private fun currentDeadline(): Long = actionDeadline ?: evidenceDeadline
    private fun clearPendingRequest() { inFlightAttempt = null; inFlightUntil = null }
    private fun stop(message: String, code: ReasonCode): Decision { suspend(message, code); return Decision() }
    private fun fail(message: String, code: ReasonCode): Decision {
        phase = Phase.FAILED
        reason = message
        reasonCode = code
        clearPendingRequest()
        pendingAlternative = null
        return Decision()
    }

    private companion object {
        val terminalPhases = setOf(Phase.IDLE, Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)
        val alternativeRoutes = setOf(Route.SPEAKER, Route.HANDSET, Route.WIRED, Route.OTHER_BLUETOOTH, Route.STREAMING)
    }
}
