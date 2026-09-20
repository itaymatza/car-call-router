package org.carcallrouter.companion.telecom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import org.carcallrouter.companion.Access
import org.carcallrouter.companion.RouterLog

@SuppressLint("MissingPermission")
class HfpMonitor(
    private val context: Context,
    private val changed: () -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var headset: BluetoothHeadset? = null
    private var closed = false
    private var registered = false
    private var lastLoggedState: String? = null
    var known = false
        private set
    var connected: Set<String> = emptySet()
        private set
    var audioConnected: Set<String> = emptySet()
        private set
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // Extras are not trusted. Re-query the authenticated Bluetooth service.
                refresh()
            }
        }
    private val listener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                handler.post {
                    if (closed) {
                        runCatching { adapter?.closeProfileProxy(profile, proxy) }
                    } else if (profile == BluetoothProfile.HEADSET) {
                        headset = proxy as? BluetoothHeadset
                        refresh()
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                handler.post {
                    if (!closed && profile == BluetoothProfile.HEADSET) {
                        headset = null
                        known = false
                        connected = emptySet()
                        audioConnected = emptySet()
                        changed()
                    }
                }
            }
        }

    fun start() {
        if (!Access.bluetoothGranted(context)) {
            changed()
            return
        }
        try {
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                    addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            registered = true
            if (adapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET) != true) {
                RouterLog.event("HFP_PROXY", "Bluetooth profile proxy unavailable")
            }
        } catch (e: RuntimeException) {
            RouterLog.event("HFP_ERROR", e.javaClass.simpleName)
        }
    }

    fun refresh() {
        if (closed) return
        try {
            val proxy = headset
            if (proxy == null || !Access.bluetoothGranted(context)) {
                known = false
                connected = emptySet()
                audioConnected = emptySet()
            } else {
                val devices = proxy.connectedDevices
                known = true
                connected = devices.map { it.address.uppercase() }.toSet()
                audioConnected = devices.filter { proxy.isAudioConnected(it) }.map { it.address.uppercase() }.toSet()
            }
        } catch (e: RuntimeException) {
            known = false
            connected = emptySet()
            audioConnected = emptySet()
            RouterLog.event("HFP_QUERY_ERROR", e.javaClass.simpleName)
        }
        val stateKey = "$known|${connected.sorted()}|${audioConnected.sorted()}"
        if (stateKey != lastLoggedState) {
            lastLoggedState = stateKey
            RouterLog.event(
                "HFP_STATE",
                "known=$known; connected=${connected.map(RouterLog::deviceId)}; sco=${audioConnected.map(RouterLog::deviceId)}",
            )
        }
        changed()
    }

    override fun close() {
        closed = true
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        headset?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        headset = null
    }
}
