package org.carcallrouter.companion.core

import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot as PolicySnapshot

/** Test-only convenience factory; production construction has no permissive defaults. */
private fun Snapshot(
    now: Long,
    enabled: Boolean = true,
    authorized: Boolean = true,
    active: Boolean = true,
    singleCall: Boolean = true,
    safeCellularCall: Boolean = true,
    projection: Boolean? = true,
    targetHfpConnected: Boolean? = true,
    targetAvailable: Boolean? = true,
    endpointRevision: Long = 1,
    route: Route = Route.COMPETING_DEVICE,
) = PolicySnapshot(
    now,
    enabled,
    authorized,
    active,
    singleCall,
    safeCellularCall,
    projection,
    targetHfpConnected,
    targetAvailable,
    endpointRevision,
    route,
)

/** Named, deterministic policy checks executed by the pure-JVM JUnit module. */
object PolicyCases {
    private fun policy(
        initial: Route = Route.COMPETING_DEVICE,
        manual: Boolean = false,
    ) = RoutingPolicy().also { it.begin(0, initial, manual) }

    fun cases(): List<Pair<String, () -> Unit>> =
        listOf(
            "idle never requests" to { check(!RoutingPolicy().evaluate(Snapshot(0)).requestTarget) },
            "fresh active call requests immediately" to { check(policy().evaluate(Snapshot(0)).requestTarget) },
            "disabled auto stays passive" to
                {
                    val p = policy()
                    check(!p.evaluate(Snapshot(0, enabled=false)).requestTarget)
                    check(p.phase == Phase.SUSPENDED)
                },
            "ringing cannot trigger a route" to { check(!policy().evaluate(Snapshot(0, active=false)).requestTarget) },
            "unknown projection waits for callback" to
                {
                    val p = policy()
                    check(!p.evaluate(Snapshot(0, projection=null)).requestTarget)
                    check(p.evaluate(Snapshot(100, projection = true)).requestTarget)
                },
            "disconnected projection blocks" to { check(!policy().evaluate(Snapshot(0, projection=false)).requestTarget) },
            "no target device means no request" to { check(!policy().evaluate(Snapshot(0, targetAvailable=false)).requestTarget) },
            "device arriving inside window triggers" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0, targetAvailable = false))
                    check(p.evaluate(Snapshot(900, targetAvailable = true)).requestTarget)
                },
            "device arriving after deadline does not trigger" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0, targetAvailable = false))
                    check(!p.evaluate(Snapshot(10001, targetAvailable=true)).requestTarget)
                },
            "unsafe emergency call blocked" to
                {
                    val p = policy()
                    check(!p.evaluate(Snapshot(0, safeCellularCall=false)).requestTarget)
                    check(p.phase == Phase.SUSPENDED)
                },
            "two calls blocked" to { check(!policy().evaluate(Snapshot(0, singleCall=false)).requestTarget) },
            "target already selected needs no request" to
                {
                    val p = policy()
                    check(!p.evaluate(Snapshot(0, route=Route.TARGET)).requestTarget)
                    check(p.verified)
                },
            "request is not verification" to {
                val p = policy()
                p.evaluate(Snapshot(0))
                check(!p.verified)
                check(p.phase == Phase.VERIFYING)
            },
            "target-device callback verifies" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(100, route = Route.TARGET))
                    check(p.verified)
                },
            "only one request in flight" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    check(!p.evaluate(Snapshot(100)).requestTarget)
                    check(p.requests == 1)
                },
            "known competitor retry after timeout" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    check(p.evaluate(Snapshot(2500)).requestTarget)
                    check(p.requests == 2)
                },
            "explicit timeout outcome retries only after request gap" to
                {
                    val p = policy()
                    val first = p.evaluate(Snapshot(0))
                    p.requestFailed(requireNotNull(first.requestAttempt), RoutingPolicy.RequestError.TIMEOUT)
                    check(!p.evaluate(Snapshot(100)).requestTarget)
                    check(p.evaluate(Snapshot(2500)).requestTarget)
                },
            "endpoint gone waits for a fresh endpoint revision" to
                {
                    val p = policy()
                    val first = p.evaluate(Snapshot(0, endpointRevision = 4))
                    p.requestFailed(requireNotNull(first.requestAttempt), RoutingPolicy.RequestError.ENDPOINT_GONE)
                    check(!p.evaluate(Snapshot(2500, endpointRevision=4)).requestTarget)
                    check(p.evaluate(Snapshot(2600, endpointRevision = 5)).requestTarget)
                },
            "another request cancellation stops automation" to
                {
                    val p = policy()
                    val first = p.evaluate(Snapshot(0))
                    p.requestFailed(requireNotNull(first.requestAttempt), RoutingPolicy.RequestError.CANCELLED_BY_OTHER)
                    check(
                        p.phase == Phase.SUSPENDED,
                    )
                    check(!p.evaluate(Snapshot(3000)).requestTarget)
                },
            "late callback from replaced attempt is ignored" to
                {
                    val p = policy()
                    val first = p.evaluate(Snapshot(0))
                    val second = p.evaluate(Snapshot(2500))
                    p.requestFailed(requireNotNull(first.requestAttempt), RoutingPolicy.RequestError.CANCELLED_BY_OTHER)
                    check(
                        p.phase == Phase.VERIFYING,
                    )
                    p.evaluate(Snapshot(2600, route = Route.TARGET))
                    check(p.verified)
                    check(second.requestAttempt == 2)
                },
            "successful outcome still requires endpoint observation" to
                {
                    val p = policy()
                    val first = p.evaluate(Snapshot(0))
                    p.requestSucceeded(requireNotNull(first.requestAttempt), 100)
                    check(!p.evaluate(Snapshot(599)).requestTarget)
                    p.evaluate(Snapshot(600))
                    check(
                        p.phase == Phase.FAILED,
                    )
                },
            "successful outcome plus target observation verifies" to
                {
                    val p = policy()
                    val first = p.evaluate(Snapshot(0))
                    p.requestSucceeded(requireNotNull(first.requestAttempt), 100)
                    p.evaluate(Snapshot(200, route = Route.TARGET))
                    check(p.verified)
                },
            "late evidence receives a full action window" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0, targetAvailable = false))
                    check(p.evaluate(Snapshot(9000, targetAvailable = true)).requestTarget)
                    check(!p.evaluate(Snapshot(10001)).requestTarget)
                    check(
                        p.phase == Phase.VERIFYING,
                    )
                },
            "unknown competitor never gets blind retry" to
                {
                    val p = policy(Route.OTHER_BLUETOOTH)
                    p.evaluate(Snapshot(0, route = Route.OTHER_BLUETOOTH))
                    check(!p.evaluate(Snapshot(2500, route=Route.OTHER_BLUETOOTH)).requestTarget)
                },
            "known competing-device reassertion bounded" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(100, route = Route.TARGET))
                    check(!p.evaluate(Snapshot(350)).requestTarget)
                    check(p.evaluate(Snapshot(2500)).requestTarget)
                    p.evaluate(Snapshot(2600, route = Route.TARGET))
                    check(p.evaluate(Snapshot(5000)).requestTarget)
                    p.evaluate(Snapshot(5100, route = Route.TARGET))
                    check(!p.evaluate(Snapshot(5500)).requestTarget)
                    check(
                        p.requests == 3,
                    )
                },
            "early known competitor bounce waits for request gap" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(50, route = Route.TARGET))
                    val d = p.evaluate(Snapshot(100))
                    check(!d.requestTarget)
                    check(
                        d.wakeAt == 2500L,
                    )
                },
            "speaker override is debounced then respected" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(100, route = Route.TARGET))
                    check(!p.evaluate(Snapshot(200, route=Route.SPEAKER)).requestTarget)
                    check(
                        p.phase != Phase.SUSPENDED,
                    )
                    p.evaluate(Snapshot(1000, route = Route.SPEAKER))
                    check(p.phase == Phase.SUSPENDED)
                },
            "handset override respected" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0, route = Route.TARGET))
                    p.evaluate(Snapshot(200, route = Route.HANDSET))
                    check(
                        p.phase == Phase.SUSPENDED,
                    )
                },
            "other Bluetooth override respected" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0, route = Route.TARGET))
                    p.evaluate(Snapshot(200, route = Route.OTHER_BLUETOOTH))
                    check(
                        p.phase == Phase.SUSPENDED,
                    )
                },
            "wired override respected" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0, route = Route.TARGET))
                    p.evaluate(Snapshot(200, route = Route.WIRED))
                    check(
                        p.phase == Phase.SUSPENDED,
                    )
                },
            "new alternative during pending request is debounced then respected" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    check(!p.evaluate(Snapshot(100, route=Route.SPEAKER)).requestTarget)
                    check(
                        p.phase != Phase.SUSPENDED,
                    )
                    p.evaluate(Snapshot(1000, route = Route.SPEAKER))
                    check(p.phase == Phase.SUSPENDED)
                },
            "unknown change after target-device routing is not fought" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0, route = Route.TARGET))
                    p.evaluate(Snapshot(100, route = Route.UNKNOWN))
                    check(
                        p.phase == Phase.SUSPENDED,
                    )
                },
            "projection loss cancels guard" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(200, projection = false))
                    check(p.phase == Phase.SUSPENDED)
                    check(!p.evaluate(Snapshot(250, projection=true)).requestTarget)
                },
            "target-device disconnect cancels guard" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(200, targetHfpConnected = false))
                    check(p.phase == Phase.SUSPENDED)
                },
            "temporary projection uncertainty does not cancel guard" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(100, route = Route.TARGET))
                    check(!p.evaluate(Snapshot(200, projection=null, route=Route.COMPETING_DEVICE)).requestTarget)
                    check(
                        p.phase != Phase.SUSPENDED,
                    )
                    check(p.evaluate(Snapshot(2500, projection = true, route = Route.COMPETING_DEVICE)).requestTarget)
                },
            "temporary HFP uncertainty does not cancel guard" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(100, route = Route.TARGET))
                    check(!p.evaluate(Snapshot(200, targetHfpConnected=null, route=Route.COMPETING_DEVICE)).requestTarget)
                    check(
                        p.phase != Phase.SUSPENDED,
                    )
                    check(p.evaluate(Snapshot(2500, targetHfpConnected = true, route = Route.COMPETING_DEVICE)).requestTarget)
                },
            "transient endpoint-list gap does not cancel guard" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(100, route = Route.TARGET))
                    check(!p.evaluate(Snapshot(200, targetAvailable=false, route=Route.COMPETING_DEVICE)).requestTarget)
                    check(
                        p.phase != Phase.SUSPENDED,
                    )
                    check(p.evaluate(Snapshot(2500, targetAvailable = true, route = Route.COMPETING_DEVICE)).requestTarget)
                },
            "hold cancels guard permanently" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(200, active = false))
                    check(!p.evaluate(Snapshot(500, active=true)).requestTarget)
                },
            "call waiting cancels guard" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(200, singleCall = false))
                    check(p.phase == Phase.SUSPENDED)
                },
            "startup window releases control" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(100, route = Route.TARGET))
                    p.evaluate(Snapshot(5500, route = Route.TARGET))
                    check(
                        p.phase == Phase.RELEASED,
                    )
                    check(!p.evaluate(Snapshot(5600)).requestTarget)
                },
            "user pause permanently stops this session" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.suspend("User pause")
                    check(!p.evaluate(Snapshot(1000)).requestTarget)
                },
            "manual test bypasses auto and projection only" to
                {
                    val p = policy(manual = true)
                    check(p.evaluate(Snapshot(0, enabled = false, projection = false)).requestTarget)
                    check(!p.evaluate(Snapshot(1500, enabled=false, projection=false)).requestTarget)
                    check(
                        p.requests == 1,
                    )
                },
            "manual test still blocks emergency" to
                { check(!policy(manual=true).evaluate(Snapshot(0, safeCellularCall=false)).requestTarget) },
            "manual test still requires target device" to
                { check(!policy(manual=true).evaluate(Snapshot(0, targetHfpConnected=false, targetAvailable=false)).requestTarget) },
            "manual success releases immediately" to
                {
                    val p = policy(manual = true)
                    p.evaluate(Snapshot(0))
                    p.evaluate(Snapshot(50, route = Route.TARGET))
                    check(p.phase == Phase.RELEASED)
                },
            "request exception prevents retries" to
                {
                    val p = policy()
                    val d = p.evaluate(Snapshot(0))
                    p.requestFailed(requireNotNull(d.requestAttempt), RoutingPolicy.RequestError.RUNTIME_EXCEPTION)
                    check(!p.evaluate(Snapshot(2600)).requestTarget)
                },
            "new session resets pause and counters" to
                {
                    val p = policy()
                    p.evaluate(Snapshot(0))
                    p.suspend("pause")
                    p.begin(9000, Route.COMPETING_DEVICE)
                    check(p.evaluate(Snapshot(9000)).requestTarget)
                    check(
                        p.requests == 1,
                    )
                },
        ) + auditCases() + safetyCases() + traceCases()

    private fun auditCases(): List<Pair<String, () -> Unit>> =
        listOf(
            "manual one-shot cannot bypass Telecom authorization" to {
                val p = policy(manual = true)
                check(!p.evaluate(Snapshot(0, enabled = false, authorized = false)).requestTarget)
                check(p.phase == Phase.SUSPENDED)
            },
            "authorization revocation prevents startup retry" to {
                val p = policy()
                p.evaluate(Snapshot(0))
                check(!p.evaluate(Snapshot(1600, authorized = false)).requestTarget)
                check(p.phase == Phase.SUSPENDED)
            },
            "authorization restoration does not undo session suspension" to {
                val p = policy()
                p.evaluate(Snapshot(0, authorized = false))
                check(!p.evaluate(Snapshot(100, authorized = true)).requestTarget)
            },
            "speaker event cannot be erased by later queued competing-device event" to {
                val p = policy()
                p.evaluate(Snapshot(0))
                p.observeRoute(Route.SPEAKER, 100)
                p.observeRoute(Route.COMPETING_DEVICE, 100)
                check(!p.evaluate(Snapshot(100)).requestTarget)
                check(p.phase != Phase.SUSPENDED)
            },
            "other Bluetooth event cannot be erased while waiting for projection" to {
                val p = policy()
                p.evaluate(Snapshot(0, projection = null))
                p.observeRoute(Route.OTHER_BLUETOOTH, 100)
                check(!p.evaluate(Snapshot(100)).requestTarget)
                check(p.phase == Phase.SUSPENDED)
            },
            "unknown initial route may acquire ordinary handset baseline" to {
                val p = policy(Route.UNKNOWN)
                check(p.evaluate(Snapshot(0, route = Route.HANDSET)).requestTarget)
            },
            "streaming override is respected" to {
                val p = policy()
                p.evaluate(Snapshot(0, route = Route.TARGET))
                check(!p.evaluate(Snapshot(100, route = Route.STREAMING)).requestTarget)
                check(p.phase == Phase.SUSPENDED)
            },
        ) +
            listOf(Route.SPEAKER, Route.HANDSET, Route.OTHER_BLUETOOTH, Route.WIRED).map { route ->
                "override while projection is pending: $route" to {
                    val p = policy()
                    p.evaluate(Snapshot(0, projection = null))
                    check(!p.evaluate(Snapshot(100, projection = true, route = route)).requestTarget)
                    check(p.phase == Phase.SUSPENDED)
                }
            } +
            listOf(Route.SPEAKER, Route.HANDSET, Route.OTHER_BLUETOOTH, Route.WIRED).map { route ->
                "override while target-device availability is pending: $route" to {
                    val p = policy()
                    p.evaluate(Snapshot(0, targetAvailable = false))
                    check(!p.evaluate(Snapshot(100, targetAvailable = true, route = route)).requestTarget)
                    check(p.phase == Phase.SUSPENDED)
                }
            }

    private fun safetyCases(): List<Pair<String, () -> Unit>> {
        val safe = CallSafety.Evidence(true, false, false, false, false, true, false)
        return listOf(
            "ordinary SIM call accepted" to { check(CallSafety.rejection(safe) == null) },
            "network emergency rejected" to { check(CallSafety.rejection(safe.copy(emergencyFlag = true)) != null) },
            "emergency callback mode rejected" to { check(CallSafety.rejection(safe.copy(emergencyCallbackMode = true)) != null) },
            "emergency number rejected" to { check(CallSafety.rejection(safe.copy(numberIsEmergency = true)) != null) },
            "unavailable emergency API rejected" to { check(CallSafety.rejection(safe.copy(numberIsEmergency = null)) != null) },
            "hidden caller number rejected" to { check(CallSafety.rejection(safe.copy(telephoneHandlePresent = false)) != null) },
            "unknown SIM capability rejected" to { check(CallSafety.rejection(safe.copy(simAccount = null)) != null) },
            "VoIP account rejected" to { check(CallSafety.rejection(safe.copy(simAccount = false)) != null) },
            "external or self-managed call rejected" to { check(CallSafety.rejection(safe.copy(externalOrSelfManaged = true)) != null) },
            "conference rejected" to { check(CallSafety.rejection(safe.copy(conference = true)) != null) },
        )
    }

    private fun traceCases(): List<Pair<String, () -> Unit>> =
        listOf(
            "routing trace is structured and sequenced" to {
                var now = 100L
                val lines = mutableListOf<String>()
                val trace = RoutingTrace({ now }, { "test session" }, lines::add)
                trace.begin("automatic", "fresh active")
                now = 125
                trace.event("REQUEST_SUBMITTED", "attempt" to 1)
                check(lines[0].contains("schema=1 session=test_session seq=1 elapsed_ms=0 event=SESSION_STARTED"))
                check(lines[1].contains("seq=2 elapsed_ms=25 event=REQUEST_SUBMITTED attempt=1"))
            },
            "routing trace distinguishes Telecom from HFP audio confirmation" to {
                var now = 0L
                val lines = mutableListOf<String>()
                val trace = RoutingTrace({ now }, { "session" }, lines::add)
                trace.begin("automatic", "active")
                now = 40
                trace.confirmTelecom()
                now = 70
                trace.confirmTargetHfpAudio()
                now = 100
                trace.finish(Phase.RELEASED, RoutingPolicy.ReasonCode.STARTUP_COMPLETE, "test_complete")
                check(lines.any { it.contains("event=TELECOM_ENDPOINT_CONFIRMED") })
                check(lines.any { it.contains("event=TARGET_HFP_AUDIO_CONFIRMED") })
                check(lines.last().contains("best_confirmation=TARGET_HFP_AUDIO"))
            },
            "routing trace never fabricates HFP confirmation" to {
                val lines = mutableListOf<String>()
                val trace = RoutingTrace({ 0L }, { "session" }, lines::add)
                trace.begin("automatic", "active")
                trace.confirmTelecom()
                trace.finish(Phase.FAILED, RoutingPolicy.ReasonCode.REQUEST_NOT_VERIFIED, "test_complete")
                check(lines.none { it.contains("event=TARGET_HFP_AUDIO_CONFIRMED") })
                check(lines.last().contains("best_confirmation=TELECOM_ENDPOINT"))
            },
        )
}
