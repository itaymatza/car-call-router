@file:Suppress("DEPRECATION")
package com.itaymatza.carcallrouter.telecom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Compatibility boundary. Public, deprecated API retained deliberately for exact MAC targeting.
 * Modern CallEndpoint has no public Bluetooth-address getter; names/UUID persistence are NOT
 * used as a guessed device mapping. Replacing this adapter does not change the policy engine.
 */
class AddressedTelecomRouter(private val service: InCallService) {
    fun state(): CallAudioState? = try { service.callAudioState } catch (_: RuntimeException) { null }
    @SuppressLint("MissingPermission")
    fun supported(state: CallAudioState?, address: String?): BluetoothDevice? {
        if (address == null) return null
        return try { state?.supportedBluetoothDevices?.singleOrNull { address.equals(it.address, true) } }
        catch (_: RuntimeException) { null }
    }
    @SuppressLint("MissingPermission")
    fun activeAddress(state: CallAudioState?): String? = try {
        if (state?.route == CallAudioState.ROUTE_BLUETOOTH) state.activeBluetoothDevice?.address else null
    } catch (_: RuntimeException) { null }
    @SuppressLint("MissingPermission")
    fun request(device: BluetoothDevice) { service.requestBluetoothAudio(device) }
}
