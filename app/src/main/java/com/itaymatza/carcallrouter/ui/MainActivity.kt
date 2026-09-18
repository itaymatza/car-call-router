package com.itaymatza.carcallrouter.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.itaymatza.carcallrouter.*
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var settings: RouterSettings
    private lateinit var master: Switch
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private var syncing = false
    private var monitor: ProjectionMonitor? = null
    private var projection: Boolean? = null
    private val statusListener: () -> Unit = { refresh() }
    private val logListener: () -> Unit = { logs.text = RouterLog.recentText() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        settings = RouterSettings(this)
        master = findViewById(R.id.master)
        status = findViewById(R.id.status)
        logs = findViewById(R.id.logs)
        logs.movementMethod = ScrollingMovementMethod()
        master.setOnCheckedChangeListener { _, checked ->
            if (!syncing) {
                if (checked && (!Access.runtimeGranted(this) || !Access.ongoingCalls(this) || settings.targetAddress == null)) {
                    toast("Grant permissions, authorize Telecom access and select BMW first.")
                    refresh()
                } else {
                    settings.enabled = checked
                    RouterLog.event("MASTER_TOGGLE", "enabled=$checked")
                    refresh()
                }
            }
        }
        button(R.id.permissions) { requestPermissions(Access.runtimePermissions, 10) }
        button(R.id.target) { chooseDevice(false) }
        button(R.id.competitor) { chooseDevice(true) }
        button(R.id.copy_adb) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Local ADB authorization", Access.AUTHORIZE_LOCAL))
            AlertDialog.Builder(this).setTitle("One-time authorization — phone or computer")
                .setMessage("On this phone, run the copied command from an authorized local ADB shell (Wireless Debugging):\n\n" + Access.AUTHORIZE_LOCAL + "\n\nWith a computer, prefix the command with adb shell.\n\nThis authorizes a non-UI call service. Samsung Phone stays your dialer. Install/authorize before starting the test call.")
                .setPositiveButton("Copied", null).show()
        }
        button(R.id.route_now) {
            val controller = SessionBridge.controller?.get()
            if (controller == null) toast("No Telecom service bound. Authorize first, then start a normal call while parked.")
            else controller.routeNow()
        }
        button(R.id.pause) { SessionBridge.controller?.get()?.pauseSession() ?: toast("No call session") }
        button(R.id.refresh) { refresh() }
        button(R.id.battery_settings) {
            AlertDialog.Builder(this).setTitle("Optional battery settings")
                .setMessage("Use this only if idle tests show missed calls. Android battery exemption does not guarantee Samsung deep-sleep exclusion or Telecom binding. No setting is changed automatically.")
                .setPositiveButton("Open settings") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (_: RuntimeException) { toast("Battery settings are unavailable on this build.") }
                }.setNegativeButton("Cancel", null).show()
        }
        button(R.id.export) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "car-call-router-${System.currentTimeMillis()}.txt")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 20)
        }
        refresh()
    }
    override fun onStart() {
        super.onStart()
        SessionBridge.observe(statusListener)
        RouterLog.observe(logListener)
        monitor = ProjectionMonitor(this) { projection = it; refresh() }.also { it.start() }
        logs.text = RouterLog.recentText()
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onStop() {
        SessionBridge.remove(statusListener)
        RouterLog.remove(logListener)
        monitor?.close()
        monitor = null
        super.onStop()
    }
    private fun button(id: Int, action: () -> Unit) { findViewById<Button>(id).setOnClickListener { action() } }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        syncing = true
        master.isChecked = settings.enabled
        syncing = false
        findViewById<TextView>(R.id.target_value).text = "${settings.targetName}\n${settings.targetAddress ?: "No target selected"}"
        findViewById<TextView>(R.id.competitor_value).text = "${settings.competitorName}\n${settings.competitorAddress ?: ""}"
        val bound = if (settings.lastBound == 0L) "Never observed" else DateFormat.getDateTimeInstance().format(Date(settings.lastBound))
        status.text = "Version: ${BuildConfig.VERSION_NAME}\nSDK: ${android.os.Build.VERSION.SDK_INT}\nRuntime permissions: ${Access.runtimeGranted(this)}\nTelecom authorization: ${Access.ongoingCalls(this)}\nAndroid Auto projection: ${projection ?: "unknown"}\nLast Telecom binding: $bound\n\n${SessionBridge.status}"
    }

    @SuppressLint("MissingPermission")
    private fun chooseDevice(competing: Boolean) {
        if (!Access.bluetoothGranted(this)) { requestPermissions(Access.runtimePermissions, 10); return }
        try {
            val devices = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.filter { !competing || !it.address.equals(settings.targetAddress, true) }
                ?.sortedWith(compareBy({ it.name ?: "" }, { it.address })) ?: emptyList()
            val labels = devices.map { "${it.name ?: "Unnamed device"}\n${it.address}" }.toMutableList()
            if (competing) labels.add(0, "None — single initial attempt, no takeover retries")
            if (labels.isEmpty()) { toast("No paired devices. Pair BMW in Bluetooth settings first."); return }
            AlertDialog.Builder(this).setTitle(if (competing) "Bad head-unit call device" else "Native BMW call device")
                .setItems(labels.toTypedArray()) { _, index ->
                    if (competing && index == 0) settings.setCompetitor(null, null)
                    else {
                        val device = devices[index - if (competing) 1 else 0]
                        if (competing) settings.setCompetitor(device.address, device.name ?: "Unnamed")
                        else settings.setTarget(device.address, device.name ?: "Unnamed")
                        RouterLog.event("DEVICE_SELECTED", "role=${if (competing) "competitor" else "target"}; id=${RouterLog.deviceId(device.address)}")
                    }
                    refresh()
                }.setNegativeButton("Cancel", null).show()
        } catch (e: RuntimeException) {
            RouterLog.event("DEVICE_SELECTION_ERROR", e.javaClass.simpleName)
            toast("Bluetooth device access failed. Check permission and turn Bluetooth on.")
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
    @Deprecated("Framework Activity compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) data?.data?.let { uri ->
            RouterLog.export(applicationContext, uri) { ok -> toast(if (ok) "Log exported" else "Export failed") }
        }
    }
}
