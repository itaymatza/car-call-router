package android.content
open class Intent
interface SharedPreferences {
 fun interface OnSharedPreferenceChangeListener {fun onSharedPreferenceChanged(prefs:SharedPreferences,key:String?)}
 fun getBoolean(k:String,d:Boolean):Boolean
 fun getString(k:String,d:String?):String?
 fun getLong(k:String,d:Long):Long
 fun edit():Editor
 fun registerOnSharedPreferenceChangeListener(l:OnSharedPreferenceChangeListener)
 fun unregisterOnSharedPreferenceChangeListener(l:OnSharedPreferenceChangeListener)
 interface Editor {
  fun putBoolean(k:String,v:Boolean):Editor;fun putString(k:String,v:String?):Editor
  fun putLong(k:String,v:Long):Editor;fun remove(k:String):Editor;fun apply()
 }
}
class MemoryPrefs:SharedPreferences{
 private val data=mutableMapOf<String,Any?>()
 private val listeners=linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
 override fun getBoolean(k:String,d:Boolean)=data[k] as? Boolean?:d
 override fun getString(k:String,d:String?)=data[k] as? String?:d
 override fun getLong(k:String,d:Long)=data[k] as? Long?:d
 override fun registerOnSharedPreferenceChangeListener(l:SharedPreferences.OnSharedPreferenceChangeListener){listeners.add(l)}
 override fun unregisterOnSharedPreferenceChangeListener(l:SharedPreferences.OnSharedPreferenceChangeListener){listeners.remove(l)}
 override fun edit():SharedPreferences.Editor=object:SharedPreferences.Editor{
  val changes=linkedMapOf<String,Any?>()
  override fun putBoolean(k:String,v:Boolean)=apply{changes[k]=v}
  override fun putString(k:String,v:String?)=apply{changes[k]=v}
  override fun putLong(k:String,v:Long)=apply{changes[k]=v}
  override fun remove(k:String)=apply{changes[k]=null}
  override fun apply(){for((k,v) in changes){if(v==null)data.remove(k)else data[k]=v;listeners.toList().forEach{it.onSharedPreferenceChanged(this@MemoryPrefs,k)}}}
 }
}
open class Context {
 fun getSharedPreferences(name:String,mode:Int):SharedPreferences=prefs
 companion object {const val MODE_PRIVATE=0;var prefs:SharedPreferences=MemoryPrefs()}
}
