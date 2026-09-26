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
import android.os.SystemClock
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
    var sampledAt: Long? = null
        private set
    var sampleSequence: Long = 0
        private set
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                // Extras are not trusted. Re-query the authenticated Bluetooth service.
                refresh("broadcast_${intent.action?.substringAfterLast('.') ?: "unknown"}")
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
                        refresh("proxy_connected")
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

    /** A verification timer can sample without scheduling a second service evaluation. */
    fun refresh(
        trigger: String = "unspecified",
        notify: Boolean = true,
    ) {
        if (closed) return
        val startedElapsed = SystemClock.elapsedRealtime()
        val startedUptime = SystemClock.uptimeMillis()
        var devicesQueryMs = 0L
        var audioQueryMs = 0L
        var deviceCount = 0
        try {
            val proxy = headset
            if (proxy == null || !Access.bluetoothGranted(context)) {
                known = false
                connected = emptySet()
                audioConnected = emptySet()
            } else {
                val devicesStarted = SystemClock.elapsedRealtime()
                val devices = proxy.connectedDevices
                devicesQueryMs = SystemClock.elapsedRealtime() - devicesStarted
                deviceCount = devices.size
                known = true
                connected = devices.map { it.address.uppercase() }.toSet()
                val audioStarted = SystemClock.elapsedRealtime()
                audioConnected = devices.filter { proxy.isAudioConnected(it) }.map { it.address.uppercase() }.toSet()
                audioQueryMs = SystemClock.elapsedRealtime() - audioStarted
            }
        } catch (e: RuntimeException) {
            known = false
            connected = emptySet()
            audioConnected = emptySet()
            RouterLog.event("HFP_QUERY_ERROR", e.javaClass.simpleName)
        }
        val elapsedDelta = SystemClock.elapsedRealtime() - startedElapsed
        val uptimeDelta = SystemClock.uptimeMillis() - startedUptime
        sampledAt = SystemClock.elapsedRealtime()
        sampleSequence++
        RouterLog.event(
            "HFP_QUERY_TIMING",
            "elapsedMs=$elapsedDelta; uptimeMs=$uptimeDelta; sleepDeltaMs=${(elapsedDelta - uptimeDelta).coerceAtLeast(0)}; " +
                "devicesMs=$devicesQueryMs; audioMs=$audioQueryMs; deviceCount=$deviceCount; known=$known; " +
                "trigger=$trigger; sample=$sampleSequence",
        )
        val stateKey = "$known|${connected.sorted()}|${audioConnected.sorted()}"
        if (stateKey != lastLoggedState) {
            lastLoggedState = stateKey
            RouterLog.event(
                "HFP_STATE",
                "known=$known; connected=${connected.map(RouterLog::deviceId)}; sco=${audioConnected.map(RouterLog::deviceId)}",
            )
        }
        if (notify) changed()
    }

    override fun close() {
        closed = true
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        headset?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        headset = null
    }
}
