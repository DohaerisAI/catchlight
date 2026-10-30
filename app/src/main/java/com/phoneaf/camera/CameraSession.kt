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
import android.net.LinkProperties
import android.net.wifi.WifiManager
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

sealed interface ConnState {
    data object NoCamera : ConnState
    data object AwaitingPermission : ConnState
    data object Connecting : ConnState
    data class Live(val model: String, val protocol: Int, val via: String = "USB") : ConnState
    /** The link dropped after being live; retrying with backoff. */
    data class Reconnecting(val reason: String, val attempt: Int, val nextInSec: Int) : ConnState
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

    private fun start(device: UsbDevice) = startWith {
        // On reconnect the camera may have re-enumerated (e.g. after power save) as a new device.
        val current = usb.deviceList.values.firstOrNull { isSony(it) && usb.hasPermission(it) } ?: device
        UsbPtpTransport.open(usb, current)
    }

    /**
     * Wi-Fi: PC Remote over PTP/IP. [host] is the camera's IP (Wi-Fi Direct: usually the
     * gateway of the camera's network). Sockets are bound to Wi-Fi explicitly, because a camera
     * network has no internet and Android would otherwise route them over mobile data.
     */
    fun connectWifi(host: String?) {
        if (running) return
        if (findWifi() == null) {
            _state.value = ConnState.Failed("Phone isn't on Wi-Fi. Join the camera's network (DIRECT-…) first.")
            return
        }
        // Resolved again on every (re)connect attempt: after a Wi-Fi drop the old network handle is dead.
        startWith {
            val (network, cm) = findWifi() ?: return@startWith null to "Phone isn't on Wi-Fi"
            val lp = cm.getLinkProperties(network)
            val phoneIp = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: "no IPv4"
            val tried = mutableListOf<String>()
            for (target in cameraCandidates(host, lp)) {
                val (t, err) = PtpIpTransport.connect(target, clientGuid(), "Catchlight", network.socketFactory, connectTimeoutMs = 3000)
                if (t != null) { lastWifiHost = target; return@startWith t to "" }
                tried += err
                Log.w(TAG, "Wi-Fi candidate failed: $err")
            }
            null to "Couldn't reach the camera over Wi-Fi. Phone IP $phoneIp. Tried ${tried.joinToString(" · ")}. " +
                "Check the phone is joined to the camera's DIRECT-… network (Android may switch away from a network with no internet) " +
                "and the camera shows PC Remote waiting for a connection."
        }
    }

    /**
     * Where the camera might be, best guess first. In Wi-Fi Direct the camera runs the network:
     * it is the DHCP server and the gateway. Sony's usual address is the last resort.
     */
    private fun cameraCandidates(host: String?, lp: LinkProperties?): List<String> {
        val out = LinkedHashSet<String>()
        host?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress?.let { out += it }
        lp?.routes?.mapNotNull { r -> r.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress }?.forEach { out += it }
        @Suppress("DEPRECATION")
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.dhcpInfo?.let { d ->
            if (d.serverAddress != 0) out += ipv4(d.serverAddress)
            if (d.gateway != 0) out += ipv4(d.gateway)
        }
        // Same subnet, host .1: the usual address of whatever runs a small network.
        lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
            ?.let { out += it.substringBeforeLast('.') + ".1" }
        out += DEFAULT_CAMERA_IP
        return out.filter { it.isNotBlank() && !it.contains(':') && it != "0.0.0.0" }
    }

    /** DhcpInfo packs IPv4 little-endian. */
    private fun ipv4(i: Int) = "${i and 0xFF}.${(i shr 8) and 0xFF}.${(i shr 16) and 0xFF}.${(i shr 24) and 0xFF}"

    private fun findWifi(): Pair<android.net.Network, ConnectivityManager>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        return n?.let { it to cm }
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

    /**
     * Owns one connection for its whole life. If a session that was live drops (camera power
     * save, a USB hiccup, Wi-Fi blip) it reconnects with backoff; a first connection that never
     * went live fails straight away, since that is usually a camera setting the user must fix.
     */
    private fun ioLoop(open: () -> Pair<PtpTransport?, String>) {
        var attempt = 0
        var everLive = false
        while (running) {
            val (wasLive, reason) = runSession(open)
            everLive = everLive || wasLive
            if (!running) break
            if (wasLive) attempt = 0
            if (!everLive) {
                _state.value = ConnState.Failed(reason)
                break
            }
            if (++attempt > MAX_RECONNECTS) {
                _state.value = ConnState.Failed("$reason. Gave up reconnecting; tap Connect to try again.")
                break
            }
            val waitMs = minOf(1000L shl (attempt - 1).coerceAtMost(3), 8000L)
            _state.value = ConnState.Reconnecting(reason, attempt, (waitMs / 1000).toInt())
            Log.w(TAG, "connection lost ($reason); retry $attempt in ${waitMs}ms")
            val until = System.currentTimeMillis() + waitMs
            while (running && System.currentTimeMillis() < until) Thread.sleep(100)
        }
        running = false
    }

    /** One connect → live-view session. Returns (reached live view, why it ended). */
    private fun runSession(open: () -> Pair<PtpTransport?, String>): Pair<Boolean, String> {
        val (transport, openError) = open()
        if (transport == null) return false to openError
        val cam = SonyCamera(transport)
        var live = false
        try {
            cam.connect()?.let { reason -> return false to reason }
            live = true
            _state.value = ConnState.Live(cam.model, cam.protocol, if (transport is PtpIpTransport) "Wi-Fi" else "USB")
            var misses = 0
            var lastPoll = 0L
            while (running) {
                // Focus Mode / Area can change on the camera at any time; re-read every 2 s, and
                // faster while S1 is held so the camera's own focus state stays current.
                val now = System.currentTimeMillis()
                if (pollNow || now - lastPoll > (if (cam.isS1Down) 600 else 2000)) {
                    pollNow = false
                    lastPoll = now
                    cam.readFocusSettings()?.let { s ->
                        if (s.focusFound != _focusSettings.value?.focusFound) {
                            Log.i(TAG, "camera focus: raw=${s.focusFound} ${FocusStatus.of(s.focusFound).label} s1=${cam.isS1Down} af=$lastAfResult")
                        }
                        _focusSettings.value = s
                    }
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
                    Log.d(TAG, "AF point %.2f,%.2f -> %s s1=%s".format(p.x, p.y, lastAfResult, cam.isS1Down))
                }
                if (transport.broken) return true to (transport.lastError.ifEmpty { "Link lost" })
                val frame = cam.liveView()
                if (frame == null) {
                    if (++misses > 150) return true to "Live view stopped responding"
                    Thread.sleep(15)
                } else {
                    misses = 0
                    onFrame(frame)
                }
            }
            return true to "Disconnected"
        } catch (t: Throwable) {
            Log.e(TAG, "io loop crashed", t)
            return live to (t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { cam.halfPress(false) }
            runCatching { cam.release() }
            transport.close()
            commands.clear()
            pendingAf.set(null)
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
        /** About two minutes of retries (1, 2, 4, then 8 s apart) before giving up. */
        private const val MAX_RECONNECTS = 16
    }
}
