package android.telecom
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.os.OutcomeReceiver
import java.io.FileDescriptor
import java.io.PrintWriter
class Call(var details:Details){
 class Details(var state:Int)
 var rejection:String?=null
 val callbacks=linkedSetOf<Callback>()
 fun registerCallback(c:Callback,h:Handler){callbacks.add(c)}
 fun unregisterCallback(c:Callback){callbacks.remove(c)}
 fun deliverState(state:Int){details.state=state;callbacks.toList().forEach{it.onStateChanged(this,state)}}
 fun deliverDetails(){callbacks.toList().forEach{it.onDetailsChanged(this,details)}}
 abstract class Callback{
  open fun onStateChanged(call:Call,state:Int){}
  open fun onDetailsChanged(call:Call,details:Details){}
  open fun onChildrenChanged(call:Call,children:MutableList<Call>){}
 }
 companion object{
  const val STATE_NEW=0;const val STATE_DIALING=1;const val STATE_RINGING=2
  const val STATE_HOLDING=3;const val STATE_ACTIVE=4;const val STATE_DISCONNECTED=7
  const val STATE_SELECT_PHONE_ACCOUNT=8;const val STATE_CONNECTING=9;const val STATE_DISCONNECTING=10
 }
}
class CallEndpoint(val endpointName:CharSequence,val endpointType:Int,val identifier:java.util.UUID=java.util.UUID.randomUUID()){
 constructor(endpointType:Int):this("",endpointType)
 companion object{const val TYPE_UNKNOWN=-1;const val TYPE_EARPIECE=1;const val TYPE_BLUETOOTH=2;const val TYPE_WIRED_HEADSET=3;const val TYPE_SPEAKER=4;const val TYPE_STREAMING=5}
}
class CallEndpointException(val code:Int,message:String="test endpoint error"):RuntimeException(message)
open class InCallService:Context(){
 val issuedRequests=mutableListOf<Pair<Long,String>>()
 var requestException:RuntimeException?=null
 var currentCallEndpoint:CallEndpoint=CallEndpoint("Handset",CallEndpoint.TYPE_EARPIECE)
 fun requestCallEndpointChange(endpoint:CallEndpoint,executor:java.util.concurrent.Executor,receiver:OutcomeReceiver<Void?,CallEndpointException>){requestException?.let{throw it};issuedRequests.add(SystemClock.elapsedRealtime() to endpoint.identifier.toString());executor.execute{receiver.onResult(null)}}
 open fun onCreate(){}
 open fun onBind(i:Intent):IBinder?=object:IBinder{}
 open fun onCallAdded(c:Call){}
 open fun onCallRemoved(c:Call){}
 open fun onCallEndpointChanged(e:CallEndpoint){}
 open fun onAvailableCallEndpointsChanged(e:MutableList<CallEndpoint>){}
 open fun onUnbind(i:Intent)=false
 open fun onDestroy(){}
 open fun dump(fd:FileDescriptor,w:PrintWriter,args:Array<out String>){}
}
