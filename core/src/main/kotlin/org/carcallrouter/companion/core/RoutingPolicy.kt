package org.carcallrouter.companion.core

/**
 * A bounded, one-shot call-routing transaction.
 *
 * Android Auto is allowed to finish its call-start routing, one request is made for the configured
 * endpoint, and success is based on target HFP audio rather than Telecom's displayed endpoint.
 */
class RoutingPolicy(
    private val evidenceWindowMs: Long = 10_000,
    private val settleDelayMs: Long = 500,
    private val actionWindowMs: Long = 4_000,
    private val platformRequestTimeoutMs: Long = 2_500,
    private val targetAudioStableMs: Long = 250,
) {
    enum class Phase { IDLE, WAITING, VERIFYING, STABILIZING, RELEASED, SUSPENDED, FAILED }

    enum class Route { TARGET, COMPETING_DEVICE, OTHER_BLUETOOTH, SPEAKER, HANDSET, WIRED, STREAMING, UNKNOWN }

    enum class RequestError { TIMEOUT, ENDPOINT_GONE, CANCELLED_BY_OTHER, UNSPECIFIED, RUNTIME_EXCEPTION }

    enum class ReasonCode {
        NO_SESSION,
        WAITING_PREREQUISITES,
        SETTLING_AFTER_ACTIVE,
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
        EVIDENCE_DEADLINE_EXPIRED,
        ACTION_DEADLINE_EXPIRED,
        WAITING_PROJECTION,
        PROJECTION_UNKNOWN,
        PROJECTION_DISCONNECTED,
        WAITING_TARGET_HFP,
        TARGET_HFP_UNKNOWN,
        TARGET_HFP_DISCONNECTED,
        WAITING_ENDPOINT_SNAPSHOT,
        WAITING_TARGET_ENDPOINT,
        WAITING_FRESH_ENDPOINT_SNAPSHOT,
        TARGET_ENDPOINT_CONFIRMED,
        TARGET_ENDPOINT_STABILIZING,
        TARGET_AUDIO_CONFIRMING,
        TARGET_AUDIO_CONFIRMED,
        TARGET_AUDIO_NOT_CONFIRMED,
        ROUTE_MOVED_AFTER_CONFIRMATION,
        ALTERNATIVE_ROUTE_DURING_REQUEST,
        ALTERNATIVE_ROUTE_DEBOUNCE,
        REQUEST_NOT_VERIFIED,
        REQUEST_ACCEPTED,
        REQUEST_ACCEPTED_NOT_OBSERVED,
        REQUEST_TIMED_OUT,
        REQUEST_ENDPOINT_GONE,
        REQUEST_CANCELLED_BY_OTHER,
        REQUEST_BUDGET_EXHAUSTED,
        REQUEST_SUBMITTED,
        REQUEST_FAILED,
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
        /** True only while the configured HFP device owns call audio. */
        val targetHfpAudio: Boolean?,
        /** null means Telecom has not delivered its first endpoint snapshot yet. */
        val targetAvailable: Boolean?,
        /** Monotonically increases for every available-endpoint callback in this call session. */
        val endpointRevision: Long,
        /** Diagnostic only. It is not accepted as proof that call audio reached the target. */
        val route: Route,
    )

    data class Decision(
        val requestTarget: Boolean = false,
        val requestAttempt: Int? = null,
        val wakeAt: Long? = null,
    )

    var phase = Phase.IDLE
        private set
    var reason = "No call session"
        private set
    var reasonCode = ReasonCode.NO_SESSION
        private set
    var requests = 0
        private set
    var verified = false
        private set

    private var manual = false
    private var evidenceDeadline = 0L
    private var settleUntil = 0L
    private var actionDeadline: Long? = null
    private var inFlightAttempt: Int? = null
    private var inFlightUntil: Long? = null
    private var targetAudioSince: Long? = null

    fun begin(
        now: Long,
        initial: Route,
        manualOneShot: Boolean = false,
    ) {
        manual = manualOneShot
        evidenceDeadline = now + evidenceWindowMs
        settleUntil = now + if (manual) 0 else settleDelayMs
        actionDeadline = null
        inFlightAttempt = null
        inFlightUntil = null
        targetAudioSince = null
        requests = 0
        verified = false
        phase = Phase.WAITING
        reason =
            if (manual) {
                "Manual one-shot: awaiting prerequisites"
            } else {
                "Waiting for Android Auto call-start routing to settle"
            }
        reasonCode = if (manual) ReasonCode.WAITING_PREREQUISITES else ReasonCode.SETTLING_AFTER_ACTIVE
        @Suppress("UNUSED_VARIABLE")
        val diagnosticInitialRoute = initial
    }

    fun suspend(
        reason: String,
        code: ReasonCode = ReasonCode.EXTERNAL_SUSPEND,
    ) {
        phase = Phase.SUSPENDED
        this.reason = reason
        reasonCode = code
        clearPendingRequest()
        targetAudioSince = null
    }

    /**
     * Route callbacks are intentionally observational. They cannot reliably distinguish Samsung
     * call-start routing from a deliberate user choice, and this transaction never reasserts after
     * its single request.
     */
    fun observeRoute(
        route: Route,
        now: Long,
    ) {
        @Suppress("UNUSED_VARIABLE")
        val diagnosticObservation = route to now
    }

    fun requestSucceeded(
        attempt: Int,
        now: Long,
    ) {
        if (inFlightAttempt != attempt || phase in terminalPhases) return
        clearPendingRequest()
        phase = Phase.VERIFYING
        reason = "Telecom accepted the one-shot request; awaiting BMW HFP audio"
        reasonCode = ReasonCode.REQUEST_ACCEPTED
        @Suppress("UNUSED_VARIABLE")
        val callbackAt = now
    }

    fun requestFailed(
        attempt: Int,
        error: RequestError,
    ) {
        if (inFlightAttempt != attempt || phase in terminalPhases) return
        clearPendingRequest()
        when (error) {
            RequestError.TIMEOUT -> {
                // A timeout can race the actual Bluetooth transition. Never send another request;
                // keep observing target HFP audio until the bounded action deadline.
                phase = Phase.VERIFYING
                reason = "Telecom request timed out; awaiting BMW HFP audio without retrying"
                reasonCode = ReasonCode.REQUEST_TIMED_OUT
            }
            RequestError.ENDPOINT_GONE ->
                fail("BMW endpoint disappeared during the one-shot request", ReasonCode.REQUEST_ENDPOINT_GONE)
            RequestError.CANCELLED_BY_OTHER ->
                suspend(
                    "Another endpoint request replaced the one-shot request; automation stopped",
                    ReasonCode.REQUEST_CANCELLED_BY_OTHER,
                )
            RequestError.UNSPECIFIED, RequestError.RUNTIME_EXCEPTION ->
                fail("Telecom rejected the one-shot routing request", ReasonCode.REQUEST_FAILED)
        }
    }

    fun evaluate(s: Snapshot): Decision {
        if (phase in terminalPhases) return Decision()
        if (!s.authorized) return stop("Telecom authorization is missing or revoked", ReasonCode.AUTHORIZATION_MISSING)
        if (!s.safeCellularCall) return stop("Emergency, non-cellular or unclassified call", ReasonCode.UNSAFE_CALL)
        if (!s.singleCall) return stop("Multiple calls / conference: system routing retained", ReasonCode.MULTIPLE_CALLS)
        if (!s.active) return stop("Call is no longer ACTIVE", ReasonCode.CALL_NOT_ACTIVE)
        if (!manual && !s.enabled) return stop("Master toggle is off", ReasonCode.DISABLED)
        if (!manual && s.projection == false) {
            return stop("Android Auto projection is not active", ReasonCode.PROJECTION_DISCONNECTED)
        }
        if (!manual && s.projection == null) {
            return waitFor("Waiting for Android Auto projection evidence", ReasonCode.PROJECTION_UNKNOWN, s.now)
        }
        if (s.targetHfpConnected == false) {
            return stop("Configured BMW device is not connected for HFP", ReasonCode.TARGET_HFP_DISCONNECTED)
        }
        if (s.targetHfpConnected == null) {
            return waitFor("Waiting for Bluetooth HFP evidence", ReasonCode.TARGET_HFP_UNKNOWN, s.now)
        }
        if (s.targetAvailable != true) {
            val code = if (s.targetAvailable == null) ReasonCode.WAITING_ENDPOINT_SNAPSHOT else ReasonCode.WAITING_TARGET_ENDPOINT
            return waitFor("Waiting for BMW in Telecom's endpoint list", code, s.now)
        }

        if (s.now < settleUntil) {
            targetAudioSince = null
            phase = Phase.WAITING
            reason = "Waiting for Android Auto call-start routing to settle"
            reasonCode = ReasonCode.SETTLING_AFTER_ACTIVE
            return Decision(wakeAt = minOf(settleUntil, evidenceDeadline))
        }
        if (actionDeadline == null && s.now >= evidenceDeadline) {
            return fail("Evidence deadline expired before safe routing could start", ReasonCode.EVIDENCE_DEADLINE_EXPIRED)
        }

        if (s.targetHfpAudio == true) {
            val since = targetAudioSince ?: s.now.also { targetAudioSince = it }
            val stableAt = since + targetAudioStableMs
            if (s.now < stableAt) {
                if (s.now >= currentDeadline()) {
                    return fail(
                        "BMW HFP audio did not remain stable within the bounded window",
                        ReasonCode.TARGET_AUDIO_NOT_CONFIRMED,
                    )
                }
                phase = Phase.STABILIZING
                reason = "BMW owns HFP audio; confirming it remains stable"
                reasonCode = ReasonCode.TARGET_AUDIO_CONFIRMING
                return Decision(wakeAt = minOf(stableAt, currentDeadline()))
            }
            verified = true
            phase = Phase.RELEASED
            reason = "BMW HFP audio confirmed; one-shot routing complete"
            reasonCode = ReasonCode.TARGET_AUDIO_CONFIRMED
            clearPendingRequest()
            return Decision()
        }
        targetAudioSince = null

        if (requests == 0) {
            if (s.targetHfpAudio == null) {
                return waitFor("Waiting for current HFP audio ownership", ReasonCode.TARGET_HFP_UNKNOWN, s.now)
            }
            requests = 1
            actionDeadline = s.now + actionWindowMs
            inFlightAttempt = 1
            inFlightUntil = s.now + platformRequestTimeoutMs
            phase = Phase.VERIFYING
            reason = "One-shot BMW request submitted; awaiting HFP audio confirmation"
            reasonCode = ReasonCode.REQUEST_SUBMITTED
            return Decision(
                requestTarget = true,
                requestAttempt = 1,
                wakeAt = minOf(currentDeadline(), requireNotNull(inFlightUntil)),
            )
        }

        inFlightUntil?.let { timeout ->
            if (s.now < timeout) return Decision(wakeAt = minOf(timeout, currentDeadline()))
            clearPendingRequest()
            phase = Phase.VERIFYING
            reason = "Telecom callback deadline expired; awaiting HFP audio without retrying"
            reasonCode = ReasonCode.REQUEST_TIMED_OUT
        }

        if (s.now >= currentDeadline()) {
            return fail(
                "BMW HFP audio was not confirmed after the one-shot request",
                ReasonCode.TARGET_AUDIO_NOT_CONFIRMED,
            )
        }
        phase = Phase.VERIFYING
        return Decision(wakeAt = currentDeadline())
    }

    private fun waitFor(
        message: String,
        code: ReasonCode,
        now: Long,
    ): Decision {
        val deadline = currentDeadline()
        if (now >= deadline) {
            val code = if (actionDeadline == null) ReasonCode.EVIDENCE_DEADLINE_EXPIRED else ReasonCode.TARGET_AUDIO_NOT_CONFIRMED
            val failure =
                if (actionDeadline == null) {
                    "Evidence deadline expired before safe routing could start"
                } else {
                    "BMW HFP audio was not confirmed after the one-shot request"
                }
            return fail(failure, code)
        }
        phase = Phase.WAITING
        reason = message
        reasonCode = code
        return Decision(wakeAt = deadline)
    }

    private fun currentDeadline(): Long = actionDeadline ?: evidenceDeadline

    private fun clearPendingRequest() {
        inFlightAttempt = null
        inFlightUntil = null
    }

    private fun stop(
        message: String,
        code: ReasonCode,
    ): Decision {
        suspend(message, code)
        return Decision()
    }

    private fun fail(
        message: String,
        code: ReasonCode,
    ): Decision {
        phase = Phase.FAILED
        reason = message
        reasonCode = code
        clearPendingRequest()
        targetAudioSince = null
        return Decision()
    }

    private companion object {
        val terminalPhases = setOf(Phase.IDLE, Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)
    }
}
