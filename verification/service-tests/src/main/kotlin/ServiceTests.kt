import android.content.Context
import android.content.Intent
import android.content.MemoryPrefs
import android.os.TestQueue
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.ProjectionMonitor
import org.carcallrouter.companion.RouterLog
import org.carcallrouter.companion.RouterSettings
import org.carcallrouter.companion.SessionBridge
import org.carcallrouter.companion.telecom.HfpMonitor
import org.carcallrouter.companion.telecom.RouterInCallService
import java.io.File

// Real production service + routing policy; substituted Android/framework boundaries.
// This is NOT Android emulation, an APK installation, real Telecom, or Bluetooth testing.
// Locally administered, synthetic addresses; never real paired-device identifiers.
private const val TARGET = "02:00:00:00:00:01"
private const val COMPETING = "02:00:00:00:00:02"
private const val OTHER = "02:00:00:00:00:03"
private val TARGET_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
private val COMPETING_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")
private val OTHER_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000003")
private val target = CallEndpoint("Target test device", CallEndpoint.TYPE_BLUETOOTH, TARGET_ID)
private val competing = CallEndpoint("Competing test device", CallEndpoint.TYPE_BLUETOOTH, COMPETING_ID)
private val other = CallEndpoint("Other test device", CallEndpoint.TYPE_BLUETOOTH, OTHER_ID)
private val speaker = CallEndpoint("Speaker", CallEndpoint.TYPE_SPEAKER, java.util.UUID.fromString("00000000-0000-0000-0000-000000000004"))

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
    HfpMonitor.audioDevices = emptySet()
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
        val settings = RouterSettings(Context())
        settings.setTarget(TARGET, "Target test device")
        settings.setCompetitor(COMPETING, "Competing test device")
        settings.enabled = enabled
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

    fun active() {
        call.deliverState(Call.STATE_ACTIVE)
        flush()
    }

    fun route(endpoint: CallEndpoint) {
        service.currentCallEndpoint = endpoint
        service.onCallEndpointChanged(endpoint)
    }

    fun endpoints(value: List<CallEndpoint>) {
        service.onAvailableCallEndpointsChanged(value.toMutableList())
    }

    fun established() {
        active()
        check(service.issuedRequests.size == 1)
        route(target)
        flush()
        check(SessionBridge.status.contains("STABILIZING"))
    }

    fun count() = service.issuedRequests.size

    override fun close() {
        service.onDestroy()
        TestQueue.runReady()
    }
}

private fun countEquals(
    f: Fixture,
    expected: Int,
) {
    check(f.count() == expected) { "Expected $expected requests, observed ${f.service.issuedRequests}; status=${SessionBridge.status}" }
}

fun main(args: Array<String>) {
    val tests =
        listOf<Pair<String, () -> Unit>>(
            "incoming_ringing_is_passive_then_active_routes" to
                {
                    Fixture().use { f ->
                        countEquals(f, 0)
                        f.active()
                        countEquals(f, 1)
                        check(
                            f.service.issuedRequests
                                .single()
                                .second == TARGET_ID.toString(),
                        )
                    }
                },
            "outgoing_dialing_connecting_active_routes" to
                {
                    Fixture(initialState = Call.STATE_DIALING).use { f ->
                        f.call.deliverState(Call.STATE_CONNECTING)
                        f.flush()
                        countEquals(f, 0)
                        f.active()
                        countEquals(f, 1)
                    }
                },
            "late_bind_to_active_call_remains_passive" to
                {
                    Fixture(initialState = Call.STATE_ACTIVE).use { f ->
                        countEquals(f, 0)
                        f.call.deliverDetails()
                        f.flush()
                        countEquals(f, 0)
                    }
                },
            "default_toggle_off_is_passive" to {
                Fixture(enabled = false).use { f ->
                    f.active()
                    countEquals(f, 0)
                }
            },
            "missing_authorization_is_passive" to {
                Fixture().use { f ->
                    Access.authorization = false
                    f.active()
                    countEquals(f, 0)
                }
            },
            "missing_runtime_permission_is_passive" to {
                Fixture().use { f ->
                    Access.runtime = false
                    f.active()
                    countEquals(f, 0)
                }
            },
            "unsafe_call_is_passive" to {
                Fixture().use { f ->
                    f.call.rejection = "Emergency test boundary"
                    f.active()
                    countEquals(f, 0)
                }
            },
            "missing_projection_is_passive" to {
                Fixture(projected = null).use { f ->
                    f.active()
                    countEquals(f, 0)
                }
            },
            "target_missing_from_supported_devices_is_passive" to {
                Fixture(available = listOf(competing)).use { f ->
                    f.active()
                    countEquals(f, 0)
                }
            },
            "target_not_hfp_connected_is_passive" to {
                Fixture().use { f ->
                    HfpMonitor.emit(setOf(COMPETING))
                    f.active()
                    countEquals(f, 0)
                }
            },
            "duplicate_target_name_is_not_guessed" to
                {
                    val duplicate = CallEndpoint("Target test device", CallEndpoint.TYPE_BLUETOOTH)
                    Fixture(available = listOf(target, duplicate, competing)).use { f ->
                        f.active()
                        countEquals(f, 0)
                    }
                },
            "explicit_one_shot_bypasses_toggle_and_projection" to
                {
                    Fixture(enabled = false, projected = false).use { f ->
                        f.active()
                        f.service.routeNow()
                        countEquals(f, 1)
                        f.service.routeNow()
                        countEquals(f, 1)
                    }
                },
            "one_shot_still_requires_authorization" to
                {
                    Fixture(enabled = false, projected = false).use { f ->
                        Access.authorization = false
                        f.active()
                        f.service.routeNow()
                        countEquals(f, 0)
                    }
                },
            "manual_pause_survives_competitor_event" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        f.service.pauseSession()
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "speaker_then_competitor_same_batch_respects_override" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        f.route(speaker)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "endpoint_speaker_then_bluetooth_same_batch_respects_override" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        f.service.onCallEndpointChanged(speaker)
                        f.service.onCallEndpointChanged(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "settings_off_then_on_same_batch_stays_paused" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        val s = RouterSettings(f.service)
                        s.enabled = false
                        s.enabled = true
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "separate_hold_evaluation_cancels_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        f.call.deliverState(Call.STATE_HOLDING)
                        f.flush()
                        f.call.deliverState(Call.STATE_ACTIVE)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "coalesced_hold_resume_must_cancel_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        f.call.deliverState(Call.STATE_HOLDING)
                        f.call.deliverState(Call.STATE_ACTIVE)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "coalesced_second_call_add_remove_must_cancel_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        val second = Call(Call.Details(Call.STATE_RINGING))
                        f.service.onCallAdded(second)
                        f.service.onCallRemoved(second)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "coalesced_projection_disconnect_reconnect_must_cancel_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        ProjectionMonitor.emit(false)
                        ProjectionMonitor.emit(true)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "coalesced_hfp_disconnect_reconnect_must_cancel_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        HfpMonitor.emit(setOf(COMPETING))
                        HfpMonitor.emit(setOf(TARGET, COMPETING))
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "temporary_projection_unknown_recovers_without_cancelling_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        ProjectionMonitor.emit(null)
                        f.flush()
                        f.route(competing)
                        f.flush()
                        ProjectionMonitor.emit(true)
                        f.flush()
                        TestQueue.advanceTo(2500)
                        countEquals(f, 2)
                    }
                },
            "temporary_hfp_unknown_recovers_without_cancelling_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        HfpMonitor.emit(emptySet(), known = false)
                        f.flush()
                        f.route(competing)
                        f.flush()
                        HfpMonitor.emit(setOf(TARGET, COMPETING))
                        f.flush()
                        TestQueue.advanceTo(2500)
                        countEquals(f, 2)
                    }
                },
            "transient_endpoint_gap_recovers_without_cancelling_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        f.endpoints(listOf(competing))
                        f.flush()
                        f.route(competing)
                        f.flush()
                        f.endpoints(listOf(target, competing, other))
                        f.flush()
                        TestQueue.advanceTo(2500)
                        countEquals(f, 2)
                    }
                },
            "positive_known_competitor_retries_are_bounded" to
                {
                    Fixture().use { f ->
                        f.established()
                        for (t in listOf(2500L, 5000L)) {
                            TestQueue.advanceTo(t)
                            f.route(competing)
                            f.flush()
                            f.route(target)
                            f.flush()
                        }
                        countEquals(f, 3)
                    }
                },
            "deadline_stops_further_requests" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.advanceTo(5500)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "telecom_exception_prevents_blind_retry" to
                {
                    Fixture().use { f ->
                        f.service.requestException = SecurityException("test boundary")
                        f.active()
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("FAILED"))
                        f.service.requestException =
                            null
                        TestQueue.advanceTo(1600)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 0)
                    }
                },
            "destroy_removes_callbacks_and_scheduled_work" to
                {
                    Fixture().use { f ->
                        f.active()
                        f.service.onDestroy()
                        check(f.call.callbacks.isEmpty())
                        check(TestQueue.size() == 0)
                        TestQueue.advanceTo(5000)
                        countEquals(f, 1)
                    }
                },
            "call_removal_cancels_pending_timeout" to
                {
                    Fixture().use { f ->
                        f.active()
                        f.call.deliverState(Call.STATE_DISCONNECTED)
                        f.service.onCallRemoved(f.call)
                        f.flush()
                        TestQueue.advanceTo(5000)
                        countEquals(f, 1)
                        check(f.call.callbacks.isEmpty())
                    }
                },
            "unknown_bluetooth_override_is_not_fought" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        f.route(other)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
        )
    val extraTests =
        listOf<Pair<String, () -> Unit>>(
            "coalesced_telecom_target_gap_recovers_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        f.endpoints(listOf(competing))
                        f.endpoints(listOf(target, competing, other))
                        f.route(competing)
                        f.flush()
                        TestQueue.advanceTo(2500)
                        countEquals(f, 2)
                    }
                },
            "coalesced_conference_children_event_must_cancel_guard" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        val child = Call(Call.Details(Call.STATE_ACTIVE))
                        f.call.callbacks.toList().forEach {
                            it.onChildrenChanged(f.call, mutableListOf(child))
                            it.onChildrenChanged(f.call, mutableListOf())
                        }
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "old_hold_callback_with_newer_active_details_must_cancel" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.now = 350
                        check(f.call.details.state == Call.STATE_ACTIVE)
                        f.call.callbacks.toList().forEach {
                            it.onStateChanged(f.call, Call.STATE_HOLDING)
                            it.onStateChanged(f.call, Call.STATE_ACTIVE)
                        }
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "projection_becoming_available_before_first_request_is_allowed" to
                {
                    Fixture(projected = null).use { f ->
                        f.active()
                        countEquals(f, 0)
                        ProjectionMonitor.emit(true)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "hfp_becoming_available_before_first_request_is_allowed" to
                {
                    Fixture().use { f ->
                        HfpMonitor.emit(emptySet(), known = false)
                        f.active()
                        countEquals(f, 0)
                        HfpMonitor.emit(setOf(TARGET, COMPETING))
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "manual_one_shot_keeps_projection_bypass" to
                {
                    Fixture(enabled = false).use { f ->
                        f.active()
                        f.service.routeNow()
                        countEquals(f, 1)
                        ProjectionMonitor.emit(false)
                        f.route(target)
                        f.flush()
                        check(SessionBridge.status.contains("RELEASED"))
                    }
                },
            "a_fresh_session_after_complete_call_cleanup_can_route" to
                {
                    Fixture().use { f ->
                        f.established()
                        val second = Call(Call.Details(Call.STATE_RINGING))
                        f.service.onCallAdded(second)
                        f.flush()
                        f.service.onCallRemoved(second)
                        f.service.onCallRemoved(f.call)
                        f.flush()
                        val next = Call(Call.Details(Call.STATE_RINGING))
                        f.route(competing)
                        f.service.onCallAdded(next)
                        f.flush()
                        next.deliverState(Call.STATE_ACTIVE)
                        f.flush()
                        countEquals(f, 2)
                    }
                },
            "rebind_to_existing_active_call_does_not_retake_route" to
                {
                    Fixture().use { f ->
                        f.established()
                        f.service.onUnbind(Intent())
                        f.service.onBind(Intent())
                        f.service.onAvailableCallEndpointsChanged(mutableListOf(target, competing, other))
                        f.service.onCallAdded(f.call)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "duplicate_active_callbacks_do_not_reset_request_budget" to
                {
                    Fixture().use { f ->
                        f.active()
                        repeat(5) {
                            f.call.deliverState(Call.STATE_ACTIVE)
                            f.call.deliverDetails()
                            f.flush()
                        }
                        countEquals(f, 1)
                    }
                },
            "api37_callback_has_exact_platform_signature" to
                {
                    val m = RouterInCallService::class.java.getDeclaredMethod("onCallEndpointRequested", CallEndpoint::class.java)
                    check(
                        m.returnType == Void.TYPE,
                    )
                },
            "api37_own_request_before_endpoint_change_is_not_an_override" to
                {
                    Fixture().use { f ->
                        f.active()
                        f.service.onCallEndpointRequested(target)
                        f.flush()
                        check(!SessionBridge.status.contains("SUSPENDED"))
                        f.route(target)
                        f.flush()
                        check(SessionBridge.status.contains("STABILIZING"))
                    }
                },
            "api37_own_request_after_endpoint_change_is_not_an_override" to
                {
                    Fixture().use { f ->
                        f.active()
                        f.route(target)
                        f.flush()
                        f.service.onCallEndpointRequested(target)
                        f.flush()
                        check(!SessionBridge.status.contains("SUSPENDED"))
                        TestQueue.advanceTo(2500)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 2)
                    }
                },
            "api37_external_endpoint_request_suspends_the_session" to
                {
                    Fixture().use { f ->
                        f.active()
                        f.service.onCallEndpointRequested(speaker)
                        f.flush()
                        check(SessionBridge.status.contains("SUSPENDED"))
                        TestQueue.advanceTo(2000)
                        countEquals(f, 1)
                    }
                },
            "api37_multiple_own_requests_are_consumed_individually" to
                {
                    Fixture().use { f ->
                        f.established()
                        TestQueue.advanceTo(2500)
                        f.route(competing)
                        f.flush()
                        countEquals(f, 2)
                        f.service.onCallEndpointRequested(target)
                        f.service.onCallEndpointRequested(target)
                        f.flush()
                        check(!SessionBridge.status.contains("SUSPENDED"))
                    }
                },
            "structured_trace_requires_endpoint_and_exact_hfp_audio_evidence" to
                {
                    Fixture().use { f ->
                        f.active()
                        f.route(target)
                        f.flush()
                        var traces = RouterLog.events.filter { it.first == "ROUTING_TRACE" }.map { it.second }
                        check(traces.any { it.contains("event=TELECOM_ENDPOINT_CONFIRMED") })
                        check(traces.none { it.contains("event=TARGET_HFP_AUDIO_CONFIRMED") })
                        HfpMonitor.audioDevices =
                            setOf(TARGET)
                        HfpMonitor.emit(HfpMonitor.devices)
                        f.flush()
                        traces =
                            RouterLog.events.filter { it.first == "ROUTING_TRACE" }.map { it.second }
                        check(traces.any { it.contains("event=TARGET_HFP_AUDIO_CONFIRMED") })
                    }
                },
            "authorization_revocation_during_request_stays_suspended_after_restore" to
                {
                    Fixture().use { f ->
                        f.active()
                        countEquals(f, 1)
                        Access.authorization = false
                        TestQueue.advanceTo(1500)
                        check(SessionBridge.status.contains("AUTHORIZATION_MISSING"))
                        Access.authorization =
                            true
                        f.route(competing)
                        f.flush()
                        TestQueue.advanceTo(4000)
                        countEquals(f, 1)
                    }
                },
            "runtime_permission_revocation_during_request_stays_suspended_after_restore" to
                {
                    Fixture().use { f ->
                        f.active()
                        countEquals(f, 1)
                        Access.runtime = false
                        TestQueue.advanceTo(1500)
                        check(SessionBridge.status.contains("UNSAFE_CALL"))
                        Access.runtime =
                            true
                        f.route(competing)
                        f.flush()
                        TestQueue.advanceTo(4000)
                        countEquals(f, 1)
                    }
                },
            "process_recreation_preserves_settings_but_does_not_retake_active_call" to
                {
                    Fixture().use { f ->
                        f.established()
                        val saved = RouterSettings(f.service)
                        check(saved.enabled)
                        check(saved.targetAddress == TARGET)
                        f.service.onDestroy()
                        val recovered = RouterInCallService()
                        recovered.currentCallEndpoint =
                            competing
                        recovered.onCreate()
                        recovered.onBind(Intent())
                        recovered.onAvailableCallEndpointsChanged(mutableListOf(target, competing, other))
                        recovered.onCallEndpointChanged(competing)
                        recovered.onCallAdded(f.call)
                        TestQueue.runReady()
                        val restored = RouterSettings(recovered)
                        check(restored.enabled)
                        check(
                            restored.targetAddress == TARGET,
                        )
                        check(recovered.issuedRequests.isEmpty())
                        check(SessionBridge.status.contains("ALREADY_ACTIVE_BIND"))
                        recovered.onDestroy()
                    }
                },
            "aosp_watchdog_never_overlaps_requests" to
                {
                    Fixture().use { f ->
                        f.service.autoCompleteRequests = false
                        f.active()
                        countEquals(f, 1)
                        TestQueue.advanceTo(2499)
                        countEquals(f, 1)
                        TestQueue.advanceTo(2500)
                        countEquals(f, 2)
                        check(!SessionBridge.status.contains("SUSPENDED"))
                    }
                },
            "timeout_error_retries_after_gap" to
                {
                    Fixture().use { f ->
                        f.service.autoCompleteRequests = false
                        f.active()
                        f.service.failLatest(CallEndpointException.ERROR_REQUEST_TIME_OUT)
                        f.flush()
                        TestQueue.advanceTo(2499)
                        countEquals(f, 1)
                        TestQueue.advanceTo(2500)
                        countEquals(f, 2)
                    }
                },
            "endpoint_gone_requires_fresh_snapshot" to
                {
                    Fixture().use { f ->
                        f.service.autoCompleteRequests = false
                        f.active()
                        f.service.failLatest(CallEndpointException.ERROR_ENDPOINT_DOES_NOT_EXIST)
                        f.flush()
                        TestQueue.advanceTo(2500)
                        countEquals(f, 1)
                        f.endpoints(listOf(target, competing, other))
                        f.flush()
                        countEquals(f, 2)
                    }
                },
            "another_request_error_suspends" to
                {
                    Fixture().use { f ->
                        f.service.autoCompleteRequests = false
                        f.active()
                        f.service.failLatest(CallEndpointException.ERROR_ANOTHER_REQUEST)
                        f.flush()
                        check(SessionBridge.status.contains("SUSPENDED"))
                        TestQueue.advanceTo(5000)
                        countEquals(f, 1)
                    }
                },
            "accepted_outcome_without_endpoint_does_not_retry" to
                {
                    Fixture().use { f ->
                        f.active()
                        TestQueue.advanceTo(500)
                        countEquals(f, 1)
                        check(SessionBridge.status.contains("FAILED"))
                    }
                },
            "completed_call_persists_last_session_result" to
                {
                    Fixture().use { f ->
                        f.established()
                        HfpMonitor.audioDevices = setOf(TARGET)
                        HfpMonitor.emit(HfpMonitor.devices)
                        f.flush()
                        f.service.onCallRemoved(f.call)
                        f.flush()
                        val last = checkNotNull(RouterSettings(f.service).lastSession)
                        check(
                            last.phase == "STABILIZING",
                        )
                        check(last.reason == "TARGET_ENDPOINT_STABILIZING")
                        check(last.confirmation == "TARGET_HFP_AUDIO")
                    }
                },
        )
    val allTests = tests + extraTests
    val failed = mutableListOf<String>()
    val out = mutableListOf<String>()
    for ((name, test) in allTests) {
        try {
            test()
            out.add("PASS $name")
        } catch (
            t: Throwable,
        ) {
            failed.add(name)
            out.add("FAIL $name: ${t.message}")
        }
    }
    out.add("RESULT ${allTests.size - failed.size}/${allTests.size} passed; failures=${failed.size}")
    out.add(
        "SCOPE: production Kotlin service/adapter/policy on a deterministic JVM double; " +
            "NOT Android installation, platform admission, Bluetooth or audio validation.",
    )
    out.forEach(::println)
    args.firstOrNull()?.let { File(it).writeText(out.joinToString("\n") + "\n") }
    if (failed.isNotEmpty()) kotlin.system.exitProcess(1)
}
