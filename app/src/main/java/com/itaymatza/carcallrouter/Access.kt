package com.itaymatza.carcallrouter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager

object Access {
    val runtimePermissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_NUMBERS)
    fun runtimeGranted(context: Context) = runtimePermissions.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    fun bluetoothGranted(context: Context) = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    fun ongoingCalls(context: Context): Boolean = try {
        context.getSystemService(TelecomManager::class.java)?.hasManageOngoingCallsPermission() == true
    } catch (_: RuntimeException) { false }
    const val AUTHORIZE_LOCAL = "cmd appops set --uid com.itaymatza.carcallrouter MANAGE_ONGOING_CALLS allow"
    const val AUTHORIZE = "adb shell $AUTHORIZE_LOCAL"
}
