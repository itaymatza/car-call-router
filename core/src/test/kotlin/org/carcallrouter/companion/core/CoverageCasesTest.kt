package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.EndpointIdentity.Candidate
import org.carcallrouter.companion.core.EndpointIdentity.Resolution
import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.ReasonCode
import org.carcallrouter.companion.core.RoutingPolicy.RequestError
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit edge cases keep the coverage gate tied to meaningful state-machine behavior. */
class CoverageCasesTest {
    @Test
    fun staleAndTerminalCallbacksAreNoOps() {
        val idle = RoutingPolicy()
        idle.observeRoute(Route.SPEAKER, 0)
        idle.requestSucceeded(1, 0)
        idle.requestFailed(1, RequestError.TIMEOUT)
        assertEquals(Phase.IDLE, idle.phase)

        val policy = policy()
        val first = policy.evaluate(snapshot(0))
        policy.requestSucceeded(requireNotNull(first.requestAttempt) + 1, 10)
        policy.requestFailed(requireNotNull(first.requestAttempt) + 1, RequestError.TIMEOUT)
        assertEquals(Phase.VERIFYING, policy.phase)
        policy.suspend("stop")
        policy.requestSucceeded(first.requestAttempt, 20)
        policy.requestFailed(first.requestAttempt, RequestError.TIMEOUT)
        assertEquals(Phase.SUSPENDED, policy.phase)
    }

    @Test
    fun unspecifiedPlatformFailureStopsWithoutRetry() {
        val policy = policy()
        val attempt = requireNotNull(policy.evaluate(snapshot(0)).requestAttempt)
        policy.requestFailed(attempt, RequestError.UNSPECIFIED)
        assertEquals(Phase.FAILED, policy.phase)
        assertEquals(ReasonCode.REQUEST_FAILED, policy.reasonCode)
    }

    @Test
    fun actionDeadlineFailureAndTargetDeadlineReleaseAreDistinct() {
        val failed = policy()
        failed.evaluate(snapshot(0))
        failed.evaluate(snapshot(5_500))
        assertEquals(Phase.FAILED, failed.phase)
        assertEquals(ReasonCode.ACTION_DEADLINE_EXPIRED, failed.reasonCode)

        val released = policy()
        released.evaluate(snapshot(10_000, route = Route.TARGET))
        assertEquals(Phase.RELEASED, released.phase)
        assertEquals(ReasonCode.STARTUP_COMPLETE, released.reasonCode)
    }

    @Test
    fun configurableLongWindowCanExhaustRequestBudget() {
        val policy = RoutingPolicy(actionWindowMs = 20_000).also { it.begin(0, Route.COMPETING_DEVICE) }
        assertTrue(policy.evaluate(snapshot(0)).requestTarget)
        assertTrue(policy.evaluate(snapshot(2_500)).requestTarget)
        assertTrue(policy.evaluate(snapshot(5_000)).requestTarget)
        assertFalse(policy.evaluate(snapshot(7_500)).requestTarget)
        assertEquals(ReasonCode.REQUEST_BUDGET_EXHAUSTED, policy.reasonCode)
    }

    @Test
    fun manualAndUnknownRoutesNeverReceiveBlindRetries() {
        val manual =
            RoutingPolicy(platformRequestTimeoutMs = 100, actionWindowMs = 1_000)
                .also { it.begin(0, Route.COMPETING_DEVICE, manualOneShot = true) }
        manual.evaluate(snapshot(0))
        manual.evaluate(snapshot(100))
        assertEquals(ReasonCode.REQUEST_NOT_VERIFIED, manual.reasonCode)

        val unknown = policy()
        unknown.evaluate(snapshot(0))
        unknown.evaluate(snapshot(2_500, route = Route.UNKNOWN))
        assertEquals(ReasonCode.ALTERNATIVE_ROUTE_DURING_REQUEST, unknown.reasonCode)
    }

    @Test
    fun traceLifecycleHandlesInactiveDuplicateAndSanitizedInput() {
        var now = 100L
        var session = "   "
        val lines = mutableListOf<String>()
        val trace = RoutingTrace({ now }, { session }, lines::add)
        trace.event("ignored")
        assertNull(trace.finish(Phase.FAILED, ReasonCode.REQUEST_FAILED, "ignored"))
        assertFalse(trace.isActive())

        trace.begin("auto mode", "x".repeat(120))
        trace.begin("ignored", "ignored")
        assertTrue(trace.isActive())
        now = 90
        trace.event("odd event", "nullable field" to null)
        trace.confirmTargetHfpAudio()
        trace.confirmTelecom()
        trace.confirmTargetHfpAudio()
        assertEquals(
            RoutingTrace.Confirmation.TARGET_HFP_AUDIO,
            trace.finish(Phase.RELEASED, ReasonCode.STARTUP_COMPLETE, "done"),
        )
        assertTrue(lines.first().contains("session=empty"))
        assertTrue(lines.any { "elapsed_ms=0" in it && "nullable_field=null" in it })
        assertEquals(1, lines.count { "TELECOM_ENDPOINT_CONFIRMED" in it })
        assertEquals(1, lines.count { "TARGET_HFP_AUDIO_CONFIRMED" in it })

        session = "second"
        trace.begin("manual", "again")
        assertEquals(
            RoutingTrace.Confirmation.NONE,
            trace.finish(Phase.FAILED, ReasonCode.REQUEST_FAILED, "done"),
        )
    }

    @Test
    fun emptySavedLabelUsesOnlyUnambiguousHfpTopology() {
        val one = Candidate("one", "Car")
        assertTrue(EndpointIdentity.resolve("   ", listOf(one), true, 1) is Resolution.Matched)
        assertTrue(
            EndpointIdentity.resolve("", listOf(one, Candidate("two", "Other")), true, 2)
                is Resolution.Unavailable,
        )
    }

    private fun policy() = RoutingPolicy().also { it.begin(0, Route.COMPETING_DEVICE) }

    private fun snapshot(
        now: Long,
        route: Route = Route.COMPETING_DEVICE,
    ) = Snapshot(
        now,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        1,
        route,
    )
}
