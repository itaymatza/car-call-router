import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.MemoryPrefs
import android.os.TestQueue
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import com.itaymatza.carcallrouter.*
import com.itaymatza.carcallrouter.telecom.*
import java.io.File

// Real production service + routing policy; substituted Android/framework boundaries.
// This is NOT Android emulation, an APK installation, real Telecom, or Bluetooth testing.
private const val BMW="00:11:22:33:44:55"
private const val CAR="00:11:22:33:44:66"
private const val BUDS="00:11:22:33:44:77"
private val bmw=BluetoothDevice(BMW)
private val car=BluetoothDevice(CAR)
private val buds=BluetoothDevice(BUDS)
private fun audio(address:String?=CAR,route:Int=CallAudioState.ROUTE_BLUETOOTH,
                  supported:Collection<BluetoothDevice> = listOf(bmw,car,buds))=
 CallAudioState(route,address?.let{BluetoothDevice(it)},supported)
private fun reset(){
 TestQueue.reset();Context.prefs=MemoryPrefs();Access.authorization=true;Access.runtime=true
 RouterLog.events.clear();ProjectionMonitor.instances.clear();ProjectionMonitor.current=true
 HfpMonitor.instances.clear();HfpMonitor.isKnown=true;HfpMonitor.devices=setOf(BMW,CAR,BUDS);HfpMonitor.audioDevices=emptySet()
 SessionBridge.controller=null
}
private class Fixture(enabled:Boolean=true,projected:Boolean?=true,
                      initialState:Int=Call.STATE_RINGING,initialAudio:CallAudioState=audio()):AutoCloseable{
 val service:RouterInCallService
 val call:Call
 init{
  reset();ProjectionMonitor.current=projected
  val settings=RouterSettings(Context());settings.enabled=enabled
  settings.setTarget(BMW,"BMW test double");settings.setCompetitor(CAR,"Aftermarket test double")
  service=RouterInCallService();service.callAudioState=initialAudio
  service.onCreate();service.onBind(Intent());service.onCallAudioStateChanged(initialAudio)
  call=Call(Call.Details(initialState));service.onCallAdded(call);flush()
 }
 fun flush()=TestQueue.runReady()
 fun active(){call.deliverState(Call.STATE_ACTIVE);flush()}
 fun route(a:CallAudioState){service.callAudioState=a;service.onCallAudioStateChanged(a)}
 fun established(){active();check(service.issuedRequests.size==1);route(audio(BMW));flush();check(SessionBridge.status.contains("STABILIZING"))}
 fun count()=service.issuedRequests.size
 override fun close(){service.onDestroy();TestQueue.runReady()}
}
private fun countEquals(f:Fixture,expected:Int){check(f.count()==expected){"Expected $expected requests, observed ${f.service.issuedRequests}; status=${SessionBridge.status}"}}
fun main(args:Array<String>){
 val tests=listOf<Pair<String,()->Unit>>(
 "incoming_ringing_is_passive_then_active_routes" to {Fixture().use{f->countEquals(f,0);f.active();countEquals(f,1);check(f.service.issuedRequests.single().second==BMW)}},
 "outgoing_dialing_connecting_active_routes" to {Fixture(initialState=Call.STATE_DIALING).use{f->f.call.deliverState(Call.STATE_CONNECTING);f.flush();countEquals(f,0);f.active();countEquals(f,1)}},
 "late_bind_to_active_call_remains_passive" to {Fixture(initialState=Call.STATE_ACTIVE).use{f->countEquals(f,0);f.call.deliverDetails();f.flush();countEquals(f,0)}},
 "default_toggle_off_is_passive" to {Fixture(enabled=false).use{f->f.active();countEquals(f,0)}},
 "missing_authorization_is_passive" to {Fixture().use{f->Access.authorization=false;f.active();countEquals(f,0)}},
 "missing_runtime_permission_is_passive" to {Fixture().use{f->Access.runtime=false;f.active();countEquals(f,0)}},
 "unsafe_call_is_passive" to {Fixture().use{f->f.call.rejection="Emergency test boundary";f.active();countEquals(f,0)}},
 "missing_projection_is_passive" to {Fixture(projected=null).use{f->f.active();countEquals(f,0)}},
 "target_missing_from_supported_devices_is_passive" to {Fixture(initialAudio=audio(supported=listOf(car))).use{f->f.active();countEquals(f,0)}},
 "target_not_hfp_connected_is_passive" to {Fixture().use{f->HfpMonitor.emit(setOf(CAR));f.active();countEquals(f,0)}},
 "duplicate_target_address_is_not_guessed" to {Fixture(initialAudio=audio(supported=listOf(bmw,bmw,car))).use{f->f.active();countEquals(f,0)}},
 "explicit_one_shot_bypasses_toggle_and_projection" to {Fixture(enabled=false,projected=false).use{f->f.active();f.service.routeNow();countEquals(f,1);f.service.routeNow();countEquals(f,1)}},
 "one_shot_still_requires_authorization" to {Fixture(enabled=false,projected=false).use{f->Access.authorization=false;f.active();f.service.routeNow();countEquals(f,0)}},
 "manual_pause_survives_competitor_event" to {Fixture().use{f->f.established();TestQueue.now=350;f.service.pauseSession();f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "speaker_then_competitor_same_batch_respects_override" to {Fixture().use{f->f.established();TestQueue.now=350;f.route(audio(null,CallAudioState.ROUTE_SPEAKER));f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "endpoint_speaker_then_bluetooth_same_batch_respects_override" to {Fixture().use{f->f.established();TestQueue.now=350;f.service.onCallEndpointChanged(CallEndpoint(CallEndpoint.TYPE_SPEAKER));f.service.onCallEndpointChanged(CallEndpoint(CallEndpoint.TYPE_BLUETOOTH));f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "settings_off_then_on_same_batch_stays_paused" to {Fixture().use{f->f.established();TestQueue.now=350;val s=RouterSettings(f.service);s.enabled=false;s.enabled=true;f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "separate_hold_evaluation_cancels_guard" to {Fixture().use{f->f.established();TestQueue.now=350;f.call.deliverState(Call.STATE_HOLDING);f.flush();f.call.deliverState(Call.STATE_ACTIVE);f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "coalesced_hold_resume_must_cancel_guard" to {Fixture().use{f->f.established();TestQueue.now=350;f.call.deliverState(Call.STATE_HOLDING);f.call.deliverState(Call.STATE_ACTIVE);f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "coalesced_second_call_add_remove_must_cancel_guard" to {Fixture().use{f->f.established();TestQueue.now=350;val other=Call(Call.Details(Call.STATE_RINGING));f.service.onCallAdded(other);f.service.onCallRemoved(other);f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "coalesced_projection_disconnect_reconnect_must_cancel_guard" to {Fixture().use{f->f.established();TestQueue.now=350;ProjectionMonitor.emit(false);ProjectionMonitor.emit(true);f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "coalesced_hfp_disconnect_reconnect_must_cancel_guard" to {Fixture().use{f->f.established();TestQueue.now=350;HfpMonitor.emit(setOf(CAR));HfpMonitor.emit(setOf(BMW,CAR));f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "positive_known_competitor_retries_are_bounded" to {Fixture().use{f->f.established();for(t in listOf(350L,700L,1050L)){TestQueue.now=t;f.route(audio(CAR));f.flush();f.route(audio(BMW));f.flush()};countEquals(f,3)}},
 "deadline_stops_further_requests" to {Fixture().use{f->f.established();TestQueue.advanceTo(4000);f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "telecom_exception_prevents_blind_retry" to {Fixture().use{f->f.service.requestException=SecurityException("test boundary");f.active();countEquals(f,0);check(SessionBridge.status.contains("FAILED"));f.service.requestException=null;TestQueue.advanceTo(1600);f.route(audio(CAR));f.flush();countEquals(f,0)}},
 "destroy_removes_callbacks_and_scheduled_work" to {Fixture().use{f->f.active();f.service.onDestroy();check(f.call.callbacks.isEmpty());check(TestQueue.size()==0);TestQueue.advanceTo(5000);countEquals(f,1)}},
 "call_removal_cancels_pending_timeout" to {Fixture().use{f->f.active();f.call.deliverState(Call.STATE_DISCONNECTED);f.service.onCallRemoved(f.call);f.flush();TestQueue.advanceTo(5000);countEquals(f,1);check(f.call.callbacks.isEmpty())}},
 "unknown_bluetooth_override_is_not_fought" to {Fixture().use{f->f.established();TestQueue.now=350;f.route(audio(BUDS));f.route(audio(CAR));f.flush();countEquals(f,1)}}
 )
 val extraTests=listOf<Pair<String,()->Unit>>(
 "coalesced_telecom_target_loss_must_cancel_guard" to {Fixture().use{f->f.established();TestQueue.now=350;f.route(audio(CAR,supported=listOf(car)));f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "coalesced_conference_children_event_must_cancel_guard" to {Fixture().use{f->f.established();TestQueue.now=350;val child=Call(Call.Details(Call.STATE_ACTIVE));f.call.callbacks.toList().forEach{it.onChildrenChanged(f.call,mutableListOf(child));it.onChildrenChanged(f.call,mutableListOf())};f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "old_hold_callback_with_newer_active_details_must_cancel" to {Fixture().use{f->f.established();TestQueue.now=350;check(f.call.details.state==Call.STATE_ACTIVE);f.call.callbacks.toList().forEach{it.onStateChanged(f.call,Call.STATE_HOLDING);it.onStateChanged(f.call,Call.STATE_ACTIVE)};f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "projection_becoming_available_before_first_request_is_allowed" to {Fixture(projected=null).use{f->f.active();countEquals(f,0);ProjectionMonitor.emit(true);f.flush();countEquals(f,1)}},
 "hfp_becoming_available_before_first_request_is_allowed" to {Fixture().use{f->HfpMonitor.emit(emptySet(),known=false);f.active();countEquals(f,0);HfpMonitor.emit(setOf(BMW,CAR));f.flush();countEquals(f,1)}},
 "manual_one_shot_keeps_projection_bypass" to {Fixture(enabled=false).use{f->f.active();f.service.routeNow();countEquals(f,1);ProjectionMonitor.emit(false);f.route(audio(BMW));f.flush();check(SessionBridge.status.contains("RELEASED"))}},
 "a_fresh_session_after_complete_call_cleanup_can_route" to {Fixture().use{f->f.established();val other=Call(Call.Details(Call.STATE_RINGING));f.service.onCallAdded(other);f.flush();f.service.onCallRemoved(other);f.service.onCallRemoved(f.call);f.flush();val next=Call(Call.Details(Call.STATE_RINGING));f.route(audio(CAR));f.service.onCallAdded(next);f.flush();next.deliverState(Call.STATE_ACTIVE);f.flush();countEquals(f,2)}},
 "rebind_to_existing_active_call_does_not_retake_route" to {Fixture().use{f->f.established();f.service.onUnbind(Intent());f.service.onBind(Intent());f.service.onCallAdded(f.call);f.route(audio(CAR));f.flush();countEquals(f,1)}},
 "duplicate_active_callbacks_do_not_reset_request_budget" to {Fixture().use{f->f.active();repeat(5){f.call.deliverState(Call.STATE_ACTIVE);f.call.deliverDetails();f.flush()};countEquals(f,1)}}
 )
 val allTests=tests+extraTests
 val failed=mutableListOf<String>();val out=mutableListOf<String>()
 for((name,test) in allTests){try{test();out.add("PASS $name")}catch(t:Throwable){failed.add(name);out.add("FAIL $name: ${t.message}")}}
 out.add("RESULT ${allTests.size-failed.size}/${allTests.size} passed; failures=${failed.size}")
 out.add("SCOPE: production Kotlin service/adapter/policy on a deterministic JVM double; NOT Android installation, platform admission, Bluetooth or audio validation.")
 out.forEach(::println)
 args.firstOrNull()?.let{File(it).writeText(out.joinToString("\n")+"\n")}
 if(failed.isNotEmpty()) kotlin.system.exitProcess(1)
}
