package org.carcallrouter.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager

object Access {
    val runtimePermissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_NUMBERS)

    fun runtimeGranted(context: Context) =
        runtimePermissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    fun bluetoothGranted(context: Context) =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun ongoingCalls(context: Context): Boolean =
        try {
            context.getSystemService(TelecomManager::class.java)?.hasManageOngoingCallsPermission() == true
        } catch (_: RuntimeException) {
            false
        }

    fun authorizationLocal() = "cmd appops set --uid ${BuildConfig.APPLICATION_ID} MANAGE_ONGOING_CALLS allow"

    fun authorizationAdb() = "adb shell ${authorizationLocal()}"

    fun revocationAdb() = "adb shell cmd appops set --uid ${BuildConfig.APPLICATION_ID} MANAGE_ONGOING_CALLS default"
}
