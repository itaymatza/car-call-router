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
import kotlin.random.Random

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
private val handset = CallEndpoint("Handset", CallEndpoint.TYPE_EARPIECE, java.util.UUID.fromString("00000000-0000-0000-0000-000000000005"))
private val wired =
    CallEndpoint("Wired", CallEndpoint.TYPE_WIRED_HEADSET, java.util.UUID.fromString("00000000-0000-0000-0000-000000000006"))
private var adversarialInterleavings = 0

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

private fun <T> permutations(values: List<T>): List<List<T>> =
    if (values.isEmpty()) {
        listOf(emptyList())
    } else {
        values.flatMapIndexed { index, value ->
            permutations(values.filterIndexed { candidate, _ -> candidate != index }).map { listOf(value) + it }
        }
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
            "late_bind_to_active_call_recovers_from_configured_car_route" to
                {
                    Fixture(initialState = Call.STATE_ACTIVE).use { f ->
                        countEquals(f, 1)
                    }
                },
            "late_bind_to_active_call_never_takes_over_speaker" to
                {
                    Fixture(initialState = Call.STATE_ACTIVE, initialEndpoint = speaker).use { f ->
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("ALREADY_ACTIVE_BIND"))
                    }
                },
            "late_bind_to_active_call_requires_android_auto" to
                {
                    Fixture(initialState = Call.STATE_ACTIVE, projected = false).use { f ->
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("ALREADY_ACTIVE_BIND"))
                    }
                },
            "late_bind_unknown_projection_expires_without_routing" to
                {
                    Fixture(initialState = Call.STATE_ACTIVE, projected = null).use { f ->
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("LATE_BIND_RECOVERY_EVIDENCE"))
                        TestQueue.advanceTo(5000)
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("ALREADY_ACTIVE_BIND"))
                    }
                },
            "late_bind_current_endpoint_before_snapshot_waits_then_routes" to
                {
                    Fixture(
                        projected = true,
                        initialState = Call.STATE_ACTIVE,
                        initialEndpoint = competing,
                        available = emptyList(),
                    ).use { f ->
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("LATE_BIND_RECOVERY_EVIDENCE"))
                        f.endpoints(listOf(target, competing, other))
                        f.flush()
                        countEquals(f, 1)
                    }
                },
            "late_bind_verified_other_bluetooth_never_routes" to
                {
                    Fixture(
                        initialState = Call.STATE_ACTIVE,
                        initialEndpoint = other,
                    ).use { f ->
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("ALREADY_ACTIVE_BIND"))
                    }
                },
            "late_bind_target_route_requires_no_request" to
                {
                    Fixture(
                        initialState = Call.STATE_ACTIVE,
                        initialEndpoint = target,
                    ).use { f ->
                        countEquals(f, 0)
                        check(SessionBridge.status.contains("STABILIZING"))
                    }
                },
            "hfp_monitor_starts_only_for_projection_or_manual_request" to
                {
                    Fixture(projected = false).use { f ->
                        check(HfpMonitor.starts == 0)
                        f.active()
                        check(HfpMonitor.starts == 0)
                        f.service.routeNow()
                        check(HfpMonitor.starts == 1)
                    }
                    Fixture(projected = null).use {
                        check(HfpMonitor.starts == 0)
                        ProjectionMonitor.emit(true)
                        it.flush()
                        check(HfpMonitor.starts == 1)
                    }
                },
            "hfp_monitor_stops_off_projection_and_restarts_on_reconnect" to
                {
                    Fixture(projected = true).use { f ->
                        check(HfpMonitor.starts == 1)
                        ProjectionMonitor.emit(false)
                        f.flush()
                        HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                        f.flush()
                        check(HfpMonitor.starts == 1)
                        ProjectionMonitor.emit(true)
                        f.flush()
                        check(HfpMonitor.starts == 2)
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
            "new_call_first_seen_active_can_recover_in_same_service_instance" to
                {
                    Fixture(initialState = Call.STATE_ACTIVE).use { f ->
                        countEquals(f, 1)
                        f.service.onCallRemoved(f.call)
                        f.route(competing)
                        val next = Call(Call.Details(Call.STATE_ACTIVE))
                        f.service.onCallAdded(next)
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
            "api37_call_start_request_does_not_block_initial_routing" to
                {
                    Fixture().use { f ->
                        // Reproduce Samsung's ordering from the field log: ACTIVE begins the
                        // automatic session, then Telecom replays its existing route before the
                        // queued policy evaluation can submit our first request.
                        f.call.deliverState(Call.STATE_ACTIVE)
                        f.service.onCallEndpointRequested(competing)
                        f.flush()
                        countEquals(f, 1)
                        check(!SessionBridge.status.contains("SUSPENDED"))
                    }
                },
            "api37_pre_active_request_replay_after_our_request_is_not_user_override" to
                {
                    Fixture().use { f ->
                        // Samsung can report the head-unit selection before ACTIVE and replay the
                        // same callback just after this guard submits its BMW request.
                        f.service.onCallEndpointRequested(competing)
                        f.flush()
                        f.active()
                        countEquals(f, 1)
                        f.service.onCallEndpointRequested(competing)
                        f.flush()
                        check(!SessionBridge.status.contains("SUSPENDED"))
                        check(
                            RouterLog.events.any {
                                it.first == "ROUTING_TRACE" &&
                                    it.second.contains("event=ENDPOINT_REQUEST_OBSERVED") &&
                                    it.second.contains("classification=STARTUP_REPLAY")
                            },
                        )
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
            "late_request_result_from_finished_generation_is_ignored" to
                {
                    Fixture().use { f ->
                        f.service.autoCompleteRequests = false
                        f.active()
                        check(f.service.pendingRequests.size == 1)
                        f.call.deliverState(Call.STATE_DISCONNECTED)
                        f.service.onCallRemoved(f.call)
                        f.flush()
                        f.service.completeLatest()
                        f.flush()
                        check(
                            RouterLog.events.any {
                                it.first == "ROUTE_RESULT_IGNORED" && it.second.contains("result=accepted")
                            },
                        )
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
            "process_recreation_recovers_active_call_from_configured_car_route" to
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
                        check(recovered.issuedRequests.size == 1)
                        check(SessionBridge.status.contains("VERIFYING"))
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
                        val traces = RouterLog.events.filter { it.first == "ROUTING_TRACE" }.map { it.second }
                        check(traces.last().contains("event=SESSION_FINISHED")) {
                            "Persisting diagnostics started an orphan trace: ${traces.takeLast(3)}"
                        }
                    }
                },
            "diagnostic_preference_writes_do_not_pause_an_active_session" to
                {
                    Fixture().use { f ->
                        f.established()
                        RouterSettings(f.service).recordLastSession("TEST", "TEST", "TEST")
                        f.flush()
                        check(!SessionBridge.status.contains("SETTINGS_CHANGED"))
                    }
                },
            "late_bind_evidence_permutations_route_once_and_latch_protected_edges" to
                {
                    val actions = listOf("projection", "hfp", "endpoints", "route")
                    val protectedRoutes = listOf(speaker, handset, wired)
                    for (order in permutations(actions)) {
                        fun run(
                            protectedEdge: String?,
                            protectedRoute: CallEndpoint? = null,
                        ) {
                            Fixture(
                                projected = null,
                                initialState = Call.STATE_ACTIVE,
                                available = emptyList(),
                            ).use { f ->
                                HfpMonitor.emit(emptySet(), known = false)
                                for (action in order) {
                                    when (action) {
                                        "projection" -> ProjectionMonitor.emit(true)
                                        "hfp" -> HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                                        "endpoints" -> f.endpoints(listOf(target, competing, other))
                                        "route" -> f.route(competing)
                                    }
                                    if (protectedEdge == action) f.route(requireNotNull(protectedRoute))
                                }
                                // A later car callback in the same main-loop batch must not erase
                                // the protected route edge that was already observed.
                                if (protectedEdge != null) f.route(competing)
                                f.flush()
                                countEquals(f, if (protectedEdge == null) 1 else 0)
                                adversarialInterleavings++
                            }
                        }
                        run(null)
                        order.forEach { edge -> protectedRoutes.forEach { route -> run(edge, route) } }
                    }
                    check(adversarialInterleavings == 312)
                },
            "late_bind_protected_endpoint_request_is_never_overridden" to
                {
                    for (protected in listOf(speaker, handset, wired)) {
                        Fixture(
                            projected = null,
                            initialState = Call.STATE_ACTIVE,
                            available = emptyList(),
                        ).use { f ->
                            f.service.onCallEndpointRequested(protected)
                            ProjectionMonitor.emit(true)
                            HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                            f.endpoints(listOf(target, competing, other))
                            f.route(competing)
                            f.flush()
                            countEquals(f, 0)
                            check(SessionBridge.status.contains("EXTERNAL_ENDPOINT_REQUEST"))
                        }
                    }
                },
            "seeded_late_bind_callback_storms_preserve_override_safety" to
                {
                    val random = Random(20260920)
                    repeat(1_000) {
                        val protected = random.nextBoolean()
                        Fixture(
                            projected = null,
                            initialState = Call.STATE_ACTIVE,
                            available = emptyList(),
                        ).use { f ->
                            HfpMonitor.emit(emptySet(), known = false)
                            val actions =
                                mutableListOf<() -> Unit>(
                                    { ProjectionMonitor.emit(true) },
                                    { HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER)) },
                                    { f.endpoints(listOf(target, competing, other)) },
                                    { f.route(competing) },
                                    { ProjectionMonitor.emit(null) },
                                    { HfpMonitor.emit(emptySet(), known = false) },
                                )
                            if (protected) actions.add { f.route(listOf(speaker, handset, wired).random(random)) }
                            actions.shuffle(random)
                            actions.forEach { it() }
                            // End with complete positive evidence. A protected edge must remain
                            // sticky even when all callbacks coalesce before evaluation.
                            ProjectionMonitor.emit(true)
                            HfpMonitor.emit(setOf(TARGET, COMPETING, OTHER))
                            f.endpoints(listOf(target, competing, other))
                            f.route(competing)
                            f.flush()
                            countEquals(f, if (protected) 0 else 1)
                            adversarialInterleavings++
                        }
                    }
                    check(adversarialInterleavings == 1_312)
                },
            "request_markers_expire_and_cannot_claim_future_callbacks" to
                {
                    reset()
                    var now = 0L
                    val service = InCallService()
                    val adapter = AddressedTelecomRouter(service) { now }
                    adapter.updateAvailable(listOf(target))
                    adapter.request(target, {}, {}, { _, _ -> error("unexpected rejection") })
                    now = AddressedTelecomRouter.REQUEST_MARKER_TTL_MS + 1
                    val observation = adapter.observeRequest(target)
                    check(observation.origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL)
                    check(observation.expiredCount == 1)
                    check(observation.pendingCount == 0)
                },
            "request_markers_are_generation_bound" to
                {
                    reset()
                    val service = InCallService()
                    val adapter = AddressedTelecomRouter(service) { 0L }
                    adapter.updateAvailable(listOf(target))
                    val oldGeneration = adapter.generation()
                    adapter.request(target, {}, {}, { _, _ -> error("unexpected rejection") })
                    adapter.clearSession()
                    check(adapter.generation() == oldGeneration + 1)
                    val observation = adapter.observeRequest(target)
                    check(observation.origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL)
                    check(observation.pendingCount == 0)
                },
            "runtime_request_failure_removes_own_marker" to
                {
                    reset()
                    val service = InCallService().also { it.requestException = SecurityException("test") }
                    val adapter = AddressedTelecomRouter(service) { 0L }
                    adapter.updateAvailable(listOf(target))
                    var threw = false
                    try {
                        adapter.request(target, {}, {}, { _, _ -> error("unexpected rejection") })
                    } catch (_: SecurityException) {
                        threw = true
                    }
                    check(threw)
                    val observation = adapter.observeRequest(target)
                    check(observation.origin == AddressedTelecomRouter.RequestOrigin.EXTERNAL)
                    check(observation.pendingCount == 0)
                },
            "removed_endpoint_invalidates_cached_and_platform_current_route" to
                {
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
    out.add(
        "RESULT ${allTests.size - failed.size}/${allTests.size} passed; failures=${failed.size}; " +
            "adversarial_interleavings=$adversarialInterleavings",
    )
    out.add(
        "SCOPE: production Kotlin service/adapter/policy on a deterministic JVM double; " +
            "NOT Android installation, platform admission, Bluetooth or audio validation.",
    )
    out.forEach(::println)
    args.firstOrNull()?.let { File(it).writeText(out.joinToString("\n") + "\n") }
    if (failed.isNotEmpty()) kotlin.system.exitProcess(1)
}
