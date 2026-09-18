package org.carcallrouter.companion

import android.content.Context
import android.content.SharedPreferences

class RouterSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("router_settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    val targetAddress: String? get() = prefs.getString("target", null)
    val targetName: String get() = prefs.getString("target_name", "Not selected") ?: "Not selected"
    val competitorAddress: String? get() = prefs.getString("competitor", null)
    val competitorName: String get() = prefs.getString("competitor_name", "None — no takeover retries") ?: "None"
    fun setTarget(address: String, name: String) {
        val edit = prefs.edit().putString("target", address).putString("target_name", name)
        if (address.equals(competitorAddress, true)) edit.remove("competitor").remove("competitor_name")
        edit.apply()
    }
    fun setCompetitor(address: String?, name: String?) {
        require(address == null || !address.equals(targetAddress, true)) { "target device cannot also be the competing device" }
        prefs.edit().putString("competitor", address).putString("competitor_name", name).apply()
    }
    fun markBound() { prefs.edit().putLong("last_bound", System.currentTimeMillis()).apply() }
    val lastBound: Long get() = prefs.getLong("last_bound", 0)
}
