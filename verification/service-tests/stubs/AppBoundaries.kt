package com.itaymatza.carcallrouter
import android.content.Context
object Access {
 var authorization=true;var runtime=true
 fun ongoingCalls(c:Context)=authorization
 fun runtimeGranted(c:Context)=runtime
}
object RouterLog {
 val events=mutableListOf<Pair<String,String>>()
 fun event(tag:String,message:String){events.add(tag to message)}
 fun deviceId(value:String?)=value?:"none"
 fun recentText()=events.joinToString("\n")
}
class ProjectionMonitor(c:Context,private val changed:(Boolean?)->Unit):AutoCloseable{
 init{instances.add(this)}
 fun start(){changed(current)}
 override fun close(){instances.remove(this)}
 companion object{
  var current:Boolean?=true;val instances=mutableListOf<ProjectionMonitor>()
  fun emit(value:Boolean?){current=value;instances.toList().forEach{it.changed(value)}}
 }
}
