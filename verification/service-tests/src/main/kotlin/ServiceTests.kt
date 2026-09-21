import android.content.Context
import android.content.Intent
import android.content.MemoryPrefs
import android.os.TestQueue
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.ProjectionMonitor
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.RouterSettings
import org.carcallrouter.companion.SessionBridge
import org.carcallrouter.companion.telecom.AddressedTelecomRouter
import org.carcallrouter.companion.telecom.HfpMonitor
import org.carcallrouter.companion.telecom.RouterInCallService
import java.io.File

// Production service + deterministic framework boundaries. This is not Android emulation or a
// substitute for real-device Telecom/Bluetooth qualification.
private const val TARGET = "02:00:00:00:00:01"
private const val COMPETING = "02:00:00:00:00:02"
private const val OTHER = "02:00:00:00:00:03"
private val TARGET_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
private val COMPETING_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")
private val OTHER_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003")
private val target = CallEndpoint("Target test device", CallEndpoint.TYPE_BLUETOOTH, TARGET_ID)
private val competing = CallEndpoint("Competing test device", CallEndpoint.TYPE_BLUETOOTH, COMPETING_ID)
private val other = CallEndpoint("Other test device", CallEndpoint.TYPE_BLUETOOTH, OTHER_ID)
private val speaker = CallEndpoint("Speaker", CallEndpoint.TYPE_SPEAKER)

private fun reset() {
    TestQueue.reset()
    Context.prefs = MemoryPrefs()
    Access.authorization = true
    Access.runtime = true
    RouterLog.events.clear()
    ProjectionMonitor.instances.clear()
    ProjectionMonitor.current = true
    HfpMonitor.instances.clear()
    HfpMonitor.isKnown = true
    HfpMonitor.devices = setOf(TARGET, COMPETING, OTHER)
    HfpMonitor.audioDevices = setOf(COMPETING)
    HfpMonitor.starts = 0
    SessionBridge.controller = null
}

private class Fixture(
    enabled: Boolean = true,
    projected: Boolean? = true,
    initialState: Int = Call.STATE_RINGING,
    initialEndpoint: CallEndpoint = competing,
    available: List<CallEndpoint> = listOf(target, competing, other),
) : AutoCloseable {
    val service: RouterInCallService
    val call: Call

    init {
        reset()
        ProjectionMonitor.current = projected
        RouterSettings(Context()).apply {
            setTarget(TARGET, "Target test device")
            setCompetitor(COMPETING, "Competing test device")
            this.enabled = enabled
        }
        service = RouterInCallService()
        service.currentCallEndpoint = initialEndpoint
        service.onCreate()
        service.onBind(Intent())
        service.onAvailableCallEndpointsChanged(available.toMutableList())
        service.onCallEndpointChanged(initialEndpoint)
        call = Call(Call.Details(initialState))
        service.onCallAdded(call)
        flush()
    }

    fun flush() = TestQueue.runReady()

    fun activateWithoutSettling() {
        call.deliverState(Call.STATE_ACTIVE)
        flush()
    }

    fun settle() {
        TestQueue.advanceTo(TestQueue.now + 500)
    }

    fun activateAndSettle() {
        activateWithoutSettling()
        settle()
    }

    fun route(endpoint: CallEndpoint) {
        service.currentCallEndpoint = endpoint
        service.onCallEndpointChanged(endpoint)
        flush()
    }

    fun targetAudio(active: Boolean) {
        HfpMonitor.audioDevices = if (active) setOf(TARGET) else setOf(COMPETING)
        HfpMonitor.emit(HfpMonitor.devices)
        flush()
    }

    fun endpoints(value: List<CallEndpoint>) {
        service.onAvailableCallEndpointsChanged(value.toMutableList())
        flush()
    }

    override fun close() {
        service.onDestroy()
        flush()
    }
}

private fun countEquals(
    fixture: Fixture,
    expected: Int,
) {
    check(fixture.service.issuedRequests.size == expected) {
        "Expected $expected requests, got ${fixture.service.issuedRequests}; ${SessionBridge.status}"
    }
}

fun main(args: Array<String>) {
    val tests =
        listOf<Pair<String, () -> Unit>>(
            "call_is_passive_until_active_and_settled" to {
                Fixture().use { f ->
                    countEquals(f, 0)
                    f.activateWithoutSettling()
                    countEquals(f, 0)
                    TestQueue.advanceTo(499)
                    countEquals(f, 0)
                    TestQueue.advanceTo(500)
                    countEquals(f, 1)
                }
            },
            "outgoing_connecting_active_uses_same_delay" to {
                Fixture(initialState = Call.STATE_DIALING).use { f ->
                    f.call.deliverState(Call.STATE_CONNECTING)
                    f.flush()
                    countEquals(f, 0)
                    f.activateAndSettle()
                    countEquals(f, 1)
                }
            },
            "split_brain_target_ui_with_competing_sco_forces_request" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("SCO=false"))
                }
            },
            "startup_endpoint_request_is_observational" to {
                Fixture(initialEndpoint = target).use { f ->
                    f.activateWithoutSettling()
                    f.service.onCallEndpointRequested(competing)
                    f.flush()
                    check(!SessionBridge.status.contains("SUSPENDED"))
                    f.settle()
                    countEquals(f, 1)
                    check(
                        RouterLog.events.any {
                            it.first == "ENDPOINT_REQUEST_OBSERVED" && it.second.contains("observational=true")
                        },
                    )
                }
            },
            "dialer_requests_never_trigger_reassertion" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.service.onCallEndpointRequested(speaker)
                    f.route(speaker)
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                }
            },
            "target_audio_not_telecom_endpoint_completes_transaction" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.route(target)
                    check(SessionBridge.status.contains("VERIFYING"))
                    f.targetAudio(true)
                    check(SessionBridge.status.contains("STABILIZING"))
                    TestQueue.advanceTo(750)
                    check(SessionBridge.status.contains("RELEASED"))
                    check(SessionBridge.status.contains("TARGET_AUDIO_CONFIRMED"))
                }
            },
            "transient_target_audio_does_not_verify" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(600)
                    f.targetAudio(false)
                    TestQueue.advanceTo(750)
                    check(!SessionBridge.status.contains("RELEASED"))
                }
            },
            "failed_verification_never_sends_second_request" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("FAILED"))
                    check(SessionBridge.status.contains("TARGET_AUDIO_NOT_CONFIRMED"))
                }
            },
            "manual_one_shot_bypasses_toggle_and_projection" to {
                Fixture(enabled = false, projected = false).use { f ->
                    f.activateWithoutSettling()
                    f.service.routeNow()
                    f.flush()
                    countEquals(f, 1)
                    f.service.routeNow()
                    countEquals(f, 1)
                }
            },
            "manual_one_shot_retains_safety_gates" to {
                Fixture(enabled = false, projected = false).use { f ->
                    Access.authorization = false
                    f.activateWithoutSettling()
                    f.service.routeNow()
                    countEquals(f, 0)
                }
            },
            "disabled_auto_is_passive" to {
                Fixture(enabled = false).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "missing_authorization_is_passive" to {
                Fixture().use { f ->
                    Access.authorization = false
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "unsafe_call_is_passive" to {
                Fixture().use { f ->
                    f.call.rejection = "Emergency test boundary"
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "projection_is_required_for_automatic_request" to {
                Fixture(projected = false).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "target_hfp_connection_is_required" to {
                Fixture().use { f ->
                    HfpMonitor.emit(setOf(COMPETING, OTHER))
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "target_endpoint_is_required_and_never_guessed" to {
                Fixture(available = listOf(competing, other)).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
                val duplicate = CallEndpoint("Target test device", CallEndpoint.TYPE_BLUETOOTH)
                Fixture(available = listOf(target, duplicate, competing)).use { f ->
                    f.activateAndSettle()
                    countEquals(f, 0)
                }
            },
            "settings_change_stops_pending_transaction" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    RouterSettings(f.service).enabled = false
                    f.flush()
                    TestQueue.advanceTo(500)
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("SETTINGS_CHANGED"))
                }
            },
            "hold_and_resume_does_not_restart_automation" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.call.deliverState(Call.STATE_HOLDING)
                    f.call.deliverState(Call.STATE_ACTIVE)
                    f.flush()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                }
            },
            "second_call_cancels_the_transaction" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    val second = Call(Call.Details(Call.STATE_RINGING))
                    f.service.onCallAdded(second)
                    f.service.onCallRemoved(second)
                    f.flush()
                    TestQueue.advanceTo(500)
                    countEquals(f, 0)
                }
            },
            "projection_reconnect_before_request_can_proceed" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    ProjectionMonitor.emit(false)
                    ProjectionMonitor.emit(true)
                    f.flush()
                    TestQueue.advanceTo(500)
                    countEquals(f, 1)
                }
            },
            "manual_pause_prevents_further_action" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    f.service.pauseSession()
                    TestQueue.advanceTo(500)
                    countEquals(f, 0)
                }
            },
            "runtime_request_exception_fails_without_retry" to {
                Fixture().use { f ->
                    f.service.requestException = SecurityException("test")
                    f.activateAndSettle()
                    countEquals(f, 0)
                    check(SessionBridge.status.contains("FAILED"))
                    TestQueue.advanceTo(5_000)
                    countEquals(f, 0)
                }
            },
            "timeout_error_waits_without_retry" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.service.failLatest(CallEndpointException.ERROR_REQUEST_TIME_OUT)
                    f.flush()
                    TestQueue.advanceTo(4_500)
                    countEquals(f, 1)
                    check(SessionBridge.status.contains("FAILED"))
                }
            },
            "another_request_error_suspends" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.service.failLatest(CallEndpointException.ERROR_ANOTHER_REQUEST)
                    f.flush()
                    check(SessionBridge.status.contains("SUSPENDED"))
                    countEquals(f, 1)
                }
            },
            "endpoint_gone_fails_without_retry" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.service.failLatest(CallEndpointException.ERROR_ENDPOINT_DOES_NOT_EXIST)
                    f.flush()
                    check(SessionBridge.status.contains("FAILED"))
                    countEquals(f, 1)
                }
            },
            "late_result_from_finished_generation_is_ignored" to {
                Fixture().use { f ->
                    f.service.autoCompleteRequests = false
                    f.activateAndSettle()
                    f.call.deliverState(Call.STATE_DISCONNECTED)
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    f.service.completeLatest()
                    f.flush()
                    check(RouterLog.events.any { it.first == "ROUTE_RESULT_IGNORED" })
                }
            },
            "call_removal_cancels_scheduled_work" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    f.service.onCallRemoved(f.call)
                    f.flush()
                    TestQueue.advanceTo(5_000)
                    countEquals(f, 0)
                    check(f.call.callbacks.isEmpty())
                }
            },
            "destroy_clears_callbacks_and_work" to {
                Fixture().use { f ->
                    f.activateWithoutSettling()
                    f.service.onDestroy()
                    check(f.call.callbacks.isEmpty())
                    check(TestQueue.size() == 0)
                }
            },
            "late_bind_active_call_uses_bounded_one_shot" to {
                Fixture(initialState = Call.STATE_ACTIVE).use { f ->
                    countEquals(f, 0)
                    f.settle()
                    countEquals(f, 1)
                }
            },
            "fresh_session_after_cleanup_can_route" to {
                Fixture().use { f ->
                    f.activateAndSettle()
                    f.service.onCallRemoved(f.call)
                    f.route(competing)
                    val next = Call(Call.Details(Call.STATE_RINGING))
                    f.service.onCallAdded(next)
                    f.flush()
                    next.deliverState(Call.STATE_ACTIVE)
                    f.flush()
                    TestQueue.advanceTo(TestQueue.now + 500)
                    countEquals(f, 2)
                }
            },
            "api37_callback_has_exact_platform_signature" to {
                val method = RouterInCallService::class.java.getDeclaredMethod("onCallEndpointRequested", CallEndpoint::class.java)
                check(method.returnType == Void.TYPE)
            },
            "target_audio_confirmation_is_traced_without_endpoint_agreement" to {
                Fixture(initialEndpoint = competing).use { f ->
                    f.activateAndSettle()
                    f.targetAudio(true)
                    TestQueue.advanceTo(750)
                    check(
                        RouterLog.events.any {
                            it.first == "ROUTING_TRACE" && it.second.contains("TARGET_HFP_AUDIO_CONFIRMED")
                        },
                    )
                }
            },
            "request_markers_expire_and_are_generation_bound" to {
                reset()
                var now = 0L
                val service = InCallService()
                val adapter = AddressedTelecomRouter(service) { now }
                adapter.updateAvailable(listOf(target))
                adapter.request(target, {}, {}, { _, _ -> error("unexpected") })
                now = AddressedTelecomRouter.REQUEST_MARKER_TTL_MS + 1
                val expired = adapter.observeRequest(target)
                check(expired.origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL)
                check(expired.expiredCount == 1)
                adapter.request(target, {}, {}, { _, _ -> error("unexpected") })
                val generation = adapter.generation()
                adapter.clearSession()
                check(adapter.generation() == generation + 1)
                check(adapter.observeRequest(target).origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL)
            },
            "removed_endpoint_invalidates_cached_current" to {
                reset()
                val service = InCallService().also { it.currentCallEndpoint = target }
                val adapter = AddressedTelecomRouter(service)
                adapter.updateAvailable(listOf(target, competing))
                adapter.updateCurrent(target)
                check(adapter.current()?.identifier == TARGET_ID)
                adapter.updateAvailable(listOf(competing))
                check(adapter.current() == null)
            },
        )

    val failed = mutableListOf<String>()
    val output = mutableListOf<String>()
    for ((name, test) in tests) {
        try {
            test()
            output.add("PASS $name")
        } catch (error: Throwable) {
            failed.add(name)
            output.add("FAIL $name: ${error.message}")
        }
    }
    output.add("RESULT ${tests.size - failed.size}/${tests.size} passed; failures=${failed.size}")
    output.add(
        "SCOPE: production Kotlin service/adapter/policy on deterministic JVM doubles; " +
            "NOT Android installation, platform admission, Bluetooth or audio validation.",
    )
    output.forEach(::println)
    args.firstOrNull()?.let { File(it).writeText(output.joinToString("\n") + "\n") }
    if (failed.isNotEmpty()) kotlin.system.exitProcess(1)
}
