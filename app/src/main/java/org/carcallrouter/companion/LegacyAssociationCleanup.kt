package org.carcallrouter.companion

import android.companion.CompanionDeviceManager
import android.content.Context

/** Removes associations created by versions that incorrectly treated a car as a wearable. */
object LegacyAssociationCleanup {
    fun run(
        context: Context,
        settings: RouterSettings,
    ) {
        if (settings.prefs.getBoolean("legacy_associations_cleaned", false)) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            val associations = manager.myAssociations
            associations.forEach { manager.disassociate(it.id) }
            settings.prefs
                .edit()
                .putBoolean("legacy_associations_cleaned", true)
                .apply()
            if (associations.isNotEmpty()) {
                RouterLog.event("MIGRATION", "Removed ${associations.size} obsolete companion association(s)")
            }
        } catch (e: RuntimeException) {
            RouterLog.event("MIGRATION_ERROR", e.javaClass.simpleName)
        }
    }
}
