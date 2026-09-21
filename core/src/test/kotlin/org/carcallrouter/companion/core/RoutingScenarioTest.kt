package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingScenarioTest {
    @Test
    fun slowScoConfirmationIsTheStrongestRecordedEvidence() {
        var now = 0L
        val lines = mutableListOf<String>()
        val trace = RoutingTrace({ now }, { "slow-sco" }, lines::add)
        trace.begin("automatic", "active")
        trace.confirmTelecom()
        now = 3_000
        trace.confirmTargetHfpAudio()
        assertEquals(
            RoutingTrace.Confirmation.TARGET_HFP_AUDIO,
            trace.finish(Phase.RELEASED, RoutingPolicy.ReasonCode.TARGET_AUDIO_CONFIRMED, "complete"),
        )
    }

    @Test
    fun capturedSamsungSplitBrainForcesExactlyOneBmwRequest() {
        val policy = RoutingPolicy(settleDelayMs = 500).also { it.begin(0, Route.TARGET) }

        assertFalse(
            policy.evaluate(snapshot(7, route = Route.TARGET, targetHfpAudio = false)).requestTarget,
        )
        policy.observeRoute(Route.OTHER_BLUETOOTH, 7)
        val request = policy.evaluate(snapshot(500, route = Route.TARGET, targetHfpAudio = false))

        assertTrue(request.requestTarget)
        assertEquals(1, request.requestAttempt)
        assertEquals(1, policy.requests)
        assertFalse(policy.evaluate(snapshot(3_000, route = Route.TARGET, targetHfpAudio = false)).requestTarget)
    }

    @Test
    fun targetScoAfterRequestMustRemainStableBeforeRelease() {
        val policy = RoutingPolicy(settleDelayMs = 0, targetAudioStableMs = 250).also { it.begin(0, Route.TARGET) }
        policy.evaluate(snapshot(0, route = Route.TARGET, targetHfpAudio = false))
        policy.evaluate(snapshot(300, route = Route.TARGET, targetHfpAudio = true))
        assertEquals(Phase.STABILIZING, policy.phase)
        policy.evaluate(snapshot(549, route = Route.TARGET, targetHfpAudio = true))
        assertFalse(policy.verified)
        policy.evaluate(snapshot(550, route = Route.TARGET, targetHfpAudio = true))
        assertTrue(policy.verified)
        assertEquals(Phase.RELEASED, policy.phase)
    }

    @Test
    fun latePrerequisitesStillReceiveOneFullActionWindow() {
        val policy = RoutingPolicy(settleDelayMs = 500).also { it.begin(0, Route.COMPETING_DEVICE) }
        assertFalse(policy.evaluate(snapshot(500, targetAvailable = false)).requestTarget)
        assertTrue(policy.evaluate(snapshot(9_000, targetAvailable = true)).requestTarget)
        assertFalse(policy.evaluate(snapshot(11_500, targetAvailable = true)).requestTarget)
        policy.evaluate(snapshot(13_000, targetAvailable = true))
        assertEquals(Phase.FAILED, policy.phase)
    }

    @Test
    fun manualSelectionAfterCompletionCannotBeReasserted() {
        val policy = RoutingPolicy(settleDelayMs = 0, targetAudioStableMs = 0).also { it.begin(0, Route.COMPETING_DEVICE) }
        policy.evaluate(snapshot(0, targetHfpAudio = false))
        policy.evaluate(snapshot(100, route = Route.TARGET, targetHfpAudio = true))
        assertEquals(Phase.RELEASED, policy.phase)

        policy.observeRoute(Route.SPEAKER, 1_000)
        assertFalse(policy.evaluate(snapshot(1_000, route = Route.SPEAKER, targetHfpAudio = false)).requestTarget)
        assertEquals(1, policy.requests)
    }

    private fun snapshot(
        now: Long,
        projection: Boolean? = true,
        targetHfpConnected: Boolean? = true,
        targetHfpAudio: Boolean? = false,
        targetAvailable: Boolean? = true,
        route: Route = Route.COMPETING_DEVICE,
    ) = Snapshot(
        now = now,
        enabled = true,
        authorized = true,
        active = true,
        singleCall = true,
        safeCellularCall = true,
        projection = projection,
        targetHfpConnected = targetHfpConnected,
        targetHfpAudio = targetHfpAudio,
        targetAvailable = targetAvailable,
        endpointRevision = 1,
        route = route,
    )
}
