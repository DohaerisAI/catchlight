package com.phoneaf.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.phoneaf.detect.Pt
import com.phoneaf.focus.isTrackingArea
import com.phoneaf.focus.spotMFor
import com.phoneaf.focus.trackingSpotMFor
import com.phoneaf.ptp.Ptp
import com.phoneaf.ptp.PtpIpTransport
import com.phoneaf.ptp.PtpTransport
import com.phoneaf.ptp.UsbPtpTransport
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

sealed interface ConnState {
    data object NoCamera : ConnState
    data object AwaitingPermission : ConnState
    data object Connecting : ConnState
    data class Live(val model: String, val protocol: Int, val via: String = "USB") : ConnState
    data class Failed(val reason: String) : ConnState
}

/**
 * Owns the USB link and the single camera I/O thread.
 *
 * PTP is strictly one-transaction-at-a-time, so this thread interleaves:
 *   queued commands (shutter, S1) → newest AF point (conflated) → one live-view frame.
 * AF points therefore reach the camera within one live-view round trip (~30–60 ms).
 */
class CameraSession(private val context: Context, private val onFrame: (LiveViewFrame) -> Unit) {

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _state = MutableStateFlow<ConnState>(ConnState.NoCamera)
    val state: StateFlow<ConnState> = _state

    private val pendingAf = AtomicReference<Pt?>(null)
    private val commands = ConcurrentLinkedQueue<(SonyCamera) -> Unit>()
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var lastAfResult: String = "–"
        private set
    private val _focusSettings = MutableStateFlow<FocusSettings?>(null)
    val focusSettings: StateFlow<FocusSettings?> = _focusSettings
    @Volatile private var pollNow = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) start(device)
                    else _state.value = ConnState.Failed("USB permission denied")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (isSony(device)) connect(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (isSony(device)) {
                    stop()
                    _state.value = ConnState.NoCamera
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Connect to the first Sony device on the bus, asking for permission if needed. */
    fun connectFirst(): Boolean {
        val device = usb.deviceList.values.firstOrNull(::isSony) ?: run {
            _state.value = ConnState.NoCamera
            return false
        }
        connect(device)
        return true
    }

    fun connect(device: UsbDevice) {
        if (running) return
        if (usb.hasPermission(device)) { start(device); return }
        _state.value = ConnState.AwaitingPermission
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags,
        )
        usb.requestPermission(device, pi)
    }

    /** Newest AF point wins; older un-sent points are dropped. */
    fun setAfPoint(p: Pt) = pendingAf.set(p)

    fun submit(cmd: (SonyCamera) -> Unit) { commands += cmd }

    /** Ask the camera to switch to Flexible Spot M and/or AF-C, then re-read the settings. */
    fun fixFocusSettings(area: Boolean, mode: Boolean) = submit { cam ->
        if (area) lastAfResult = "area " + Ptp.name(cam.setU16Setting(Ptp.PROP_FOCUS_AREA, spotMFor(_focusSettings.value?.focusArea)).code)
        if (mode) {
            val prop = _focusSettings.value?.focusModeProp ?: Ptp.PROP_FOCUS_MODE_PTP
            lastAfResult = "mode " + Ptp.name(cam.setU16Setting(prop, Ptp.FOCUS_AF_C).code)
        }
        Thread.sleep(250)
        cam.resetAfPath()
        pollNow = true
    }

    /** Result of the last Hybrid Focus Area change, for the settings screen. */
    @Volatile var hybridAreaResult: String = ""
        private set
    private var areaBeforeHybrid: Int? = null

    /**
     * Hybrid tracking: switch the camera to Tracking: Flexible Spot M so its own Real-time
     * Tracking follows the subject once Catchlight has placed the start point; switching off
     * restores the Focus Area the camera had before.
     */
    fun setTrackingArea(on: Boolean) = submit { cam ->
        val current = _focusSettings.value?.focusArea
        if (on) {
            if (isTrackingArea(current)) {
                hybridAreaResult = "Camera already uses a Tracking focus area."
            } else {
                areaBeforeHybrid = current
                val r = cam.setU16Setting(Ptp.PROP_FOCUS_AREA, trackingSpotMFor(current))
                hybridAreaResult = if (r.ok) "Camera switched to Tracking: Flexible Spot M."
                else "Camera refused the change (${Ptp.name(r.code)}). Set Focus Area → Tracking: Flexible Spot M on the camera."
            }
        } else {
            areaBeforeHybrid?.let { cam.setU16Setting(Ptp.PROP_FOCUS_AREA, it) }
            areaBeforeHybrid = null
            hybridAreaResult = ""
        }
        Thread.sleep(250)
        cam.resetAfPath()
        pollNow = true
    }

    /**
     * Diagnostic: each corner uses a different delivery method, with S1 held so the
     * "release S1" variants are meaningful. Whichever corner the camera's frame visits
     * tells us which method works on this body:
     * top-left = ctl, top-right = prop, bottom-right = ctl-S1, bottom-left = prop-S1.
     */
    fun runAfTest() = submit { cam ->
        val corners = listOf(Pt(0.15f, 0.2f), Pt(0.85f, 0.2f), Pt(0.85f, 0.8f), Pt(0.15f, 0.8f))
        val results = StringBuilder()
        cam.halfPress(true)
        for ((i, path) in SonyCamera.AfPath.entries.withIndex()) {
            cam.afPath = path
            val p = corners[i]
            val r = cam.setAfPoint(p.x, p.y)
            results.append(listOf("TL", "TR", "BR", "BL")[i]).append(':').append(path.short)
                .append('=').append(Ptp.name(r.code)).append("  ")
            lastAfResult = results.toString().trim()
            Thread.sleep(1500)
        }
        cam.halfPress(false)
        cam.resetAfPath()
        lastAfTest = results.toString().trim()
    }

    @Volatile var capability: String = "not connected"
        private set

    @Volatile var lastAfTest: String = ""
        private set

    /** Use one AF delivery method from now on (chosen after the AF test). */
    fun forceAfPath(path: SonyCamera.AfPath?) = submit { it.afPath = path; it.afAuto = path == null }

    private fun start(device: UsbDevice) = startWith { UsbPtpTransport.open(usb, device) }

    /**
     * Wi-Fi: PC Remote over PTP/IP. [host] is the camera's IP (Wi-Fi Direct: usually the
     * gateway of the camera's network). Sockets are bound to Wi-Fi explicitly, because a camera
     * network has no internet and Android would otherwise route them over mobile data.
     */
    fun connectWifi(host: String?) {
        if (running) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifi == null) {
            _state.value = ConnState.Failed("Phone isn't on Wi-Fi. Join the camera's network (DIRECT-…) first.")
            return
        }
        val target = host?.takeIf { it.isNotBlank() }
            ?: cm.getLinkProperties(wifi)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
                ?.gateway?.hostAddress
            ?: DEFAULT_CAMERA_IP
        lastWifiHost = target
        startWith { PtpIpTransport.connect(target, clientGuid(), "phoneAF", wifi.socketFactory) }
    }

    @Volatile var lastWifiHost: String = ""
        private set

    /** Stable 16-byte client id: the camera's Access Point pairing remembers it. */
    private fun clientGuid(): ByteArray {
        val prefs = context.getSharedPreferences("phoneaf", Context.MODE_PRIVATE)
        val hex = prefs.getString("ptpip_guid", null) ?: java.util.UUID.randomUUID().toString().replace("-", "")
            .also { prefs.edit().putString("ptpip_guid", it).apply() }
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun startWith(open: () -> Pair<PtpTransport?, String>) {
        if (running) return
        running = true
        _state.value = ConnState.Connecting
        thread = Thread({ ioLoop(open) }, "phoneAF-camera-io").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    /** User-initiated disconnect (also used to switch between USB and Wi-Fi). */
    fun disconnect() {
        stop()
        _state.value = ConnState.NoCamera
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    fun close() {
        stop()
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun ioLoop(open: () -> Pair<PtpTransport?, String>) {
        val (transport, openError) = open()
        if (transport == null) {
            _state.value = ConnState.Failed(openError)
            running = false
            return
        }
        val cam = SonyCamera(transport)
        try {
            cam.connect()?.let { reason ->
                _state.value = ConnState.Failed(reason)
                return
            }
            _state.value = ConnState.Live(cam.model, cam.protocol, if (transport is PtpIpTransport) "Wi-Fi" else "USB")
            var misses = 0
            var lastPoll = 0L
            while (running) {
                // Focus Mode / Area can change on the camera at any time; re-read every 2 s.
                val now = System.currentTimeMillis()
                if (pollNow || now - lastPoll > 2000) {
                    pollNow = false
                    lastPoll = now
                    cam.readFocusSettings()?.let { _focusSettings.value = it }
                    capability = cam.capabilityReport(cam.lastPropDump)
                }
                var n = 0
                while (n++ < 4) {
                    val cmd = commands.poll() ?: break
                    runCatching { cmd(cam) }.onFailure { Log.w(TAG, "command failed", it) }
                }
                pendingAf.getAndSet(null)?.let { p ->
                    val r = cam.setAfPoint(p.x, p.y)
                    lastAfResult = Ptp.name(r.code) + " (" + (cam.afPath?.short ?: "auto") + ")"
                }
                val frame = cam.liveView()
                if (frame == null) {
                    if (++misses > 150) {
                        _state.value = ConnState.Failed("Live view stopped responding")
                        break
                    }
                    Thread.sleep(15)
                } else {
                    misses = 0
                    onFrame(frame)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "io loop crashed", t)
            _state.value = ConnState.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { cam.halfPress(false) }
            cam.release()
            transport.close()
            running = false
        }
    }

    private fun isSony(d: UsbDevice) = d.vendorId == Ptp.SONY_VENDOR_ID

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val TAG = "CameraSession"
        const val DEFAULT_CAMERA_IP = "192.168.122.1" // Sony Wi-Fi Direct default
        private const val ACTION_PERMISSION = "com.phoneaf.USB_PERMISSION"
    }
}
