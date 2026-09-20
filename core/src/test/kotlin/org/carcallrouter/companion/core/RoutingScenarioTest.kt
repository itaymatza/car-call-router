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
    fun slowScoConfirmationIsRecordedWithoutAnotherRouteRequest() {
        var now = 0L
        val lines = mutableListOf<String>()
        val trace = RoutingTrace({ now }, { "slow-sco" }, lines::add)
        trace.begin("automatic", "active")
        trace.confirmTelecom()
        now = 3_000
        trace.confirmTargetHfpAudio()
        assertEquals(
            RoutingTrace.Confirmation.TARGET_HFP_AUDIO,
            trace.finish(Phase.RELEASED, RoutingPolicy.ReasonCode.STARTUP_COMPLETE, "complete"),
        )
        assertEquals(1, lines.count { "TARGET_HFP_AUDIO_CONFIRMED" in it })
    }

    @Test
    fun headUnitReclaimAtThreeSecondsGetsOneBoundedReassertion() {
        val policy = policy()
        assertTrue(policy.evaluate(snapshot(0)).requestTarget)
        policy.evaluate(snapshot(100, route = Route.TARGET))
        val decision = policy.evaluate(snapshot(3_000, route = Route.COMPETING_DEVICE))
        assertTrue(decision.requestTarget)
        assertEquals(2, decision.requestAttempt)
    }

    @Test
    fun intermediateHandsetRouteDoesNotWinInsideSelfRequestDebounce() {
        val policy = policy()
        policy.evaluate(snapshot(0))
        assertFalse(policy.evaluate(snapshot(200, route = Route.HANDSET)).requestTarget)
        assertTrue(policy.phase != Phase.SUSPENDED)
        policy.evaluate(snapshot(400, route = Route.TARGET))
        assertTrue(policy.verified)
    }

    @Test
    fun projectionUnknownAtStartCanRecoverWithinEvidenceWindow() {
        val policy = policy()
        assertFalse(policy.evaluate(snapshot(0, projection = null)).requestTarget)
        assertTrue(policy.evaluate(snapshot(9_000, projection = true)).requestTarget)
    }

    @Test
    fun bothConnectionOrdersConvergeOnOneRequest() {
        val projectionFirst = policy()
        assertFalse(projectionFirst.evaluate(snapshot(0, targetHfpConnected = false, targetAvailable = false)).requestTarget)
        assertTrue(projectionFirst.evaluate(snapshot(1_000, targetHfpConnected = true, targetAvailable = true)).requestTarget)

        val hfpFirst = policy()
        assertFalse(hfpFirst.evaluate(snapshot(0, projection = false)).requestTarget)
        assertTrue(hfpFirst.evaluate(snapshot(1_000, projection = true)).requestTarget)
    }

    @Test
    fun jsonlRegressionTraceReplaysDeterministically() {
        val resource = requireNotNull(javaClass.getResource("/traces/head-unit-reclaim.jsonl"))
        val policy = policy()
        val attempts =
            resource
                .readText()
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    val fields = parseFlatJson(line)
                    policy
                        .evaluate(
                            snapshot(
                                now = requireNotNull(fields["now"]).toLong(),
                                projection = fields["projection"].toNullableBoolean(),
                                targetHfpConnected = fields["targetHfpConnected"].toNullableBoolean(),
                                targetAvailable = fields["targetAvailable"].toNullableBoolean(),
                                endpointRevision = requireNotNull(fields["endpointRevision"]).toLong(),
                                route = Route.valueOf(requireNotNull(fields["route"])),
                            ),
                        ).requestAttempt
                }.filterNotNull()
                .toList()
        assertEquals(listOf(1, 2), attempts)
        assertTrue(policy.verified)
    }

    private fun policy() = RoutingPolicy().also { it.begin(0, Route.COMPETING_DEVICE) }

    private fun snapshot(
        now: Long,
        projection: Boolean? = true,
        targetHfpConnected: Boolean? = true,
        targetAvailable: Boolean? = true,
        endpointRevision: Long = 1,
        route: Route = Route.COMPETING_DEVICE,
    ) = Snapshot(
        now,
        true,
        true,
        true,
        true,
        true,
        projection,
        targetHfpConnected,
        targetAvailable,
        endpointRevision,
        route,
    )

    private fun String?.toNullableBoolean(): Boolean? =
        when (this) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> error("Invalid nullable boolean: $this")
        }

    private fun parseFlatJson(line: String): Map<String, String> =
        Regex("\\\"([^\\\"]+)\\\"\\s*:\\s*(\\\"([^\\\"]*)\\\"|true|false|null|-?\\d+)")
            .findAll(line)
            .associate { match -> match.groupValues[1] to match.groupValues[2].trim('"') }
}
