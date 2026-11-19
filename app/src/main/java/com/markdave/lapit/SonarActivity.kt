package com.markdave.lapit

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Bluetooth sonar: shows Bluetooth things around you (earbuds, watches, speakers, trackers, phones)
 * on a radar. Distance comes from signal strength. Direction comes from turning around slowly:
 * your body blocks the signal, so it is strongest when you face the thing.
 */
class SonarActivity : PermActivity(), SensorEventListener {

    class Target(val addr: String) {
        var name: String? = null
        var friend: String? = null
        var rssi = -100.0
        var lastSeen = 0L
        var txPower: Int? = null
        val bins = DoubleArray(36) { Double.NaN }
        val history = ArrayDeque<Pair<Long, Double>>()

        fun label(things: Map<String, String>): String =
            things[addr]?.let { "⭐ $it" } ?: friend?.let { "🙋 $it" } ?: name ?: "Unknown …${addr.takeLast(5)}"

        /** Rough metres from signal strength. Walls, bodies and pockets make this jumpy. */
        fun distance(): Double {
            val at1m = txPower?.let { it - 41.0 } ?: -59.0
            return 10.0.pow((at1m - rssi) / (10 * 2.2)).coerceIn(0.1, 99.0)
        }

        /** Direction (0–360° from north) once the user has turned through enough of a circle. */
        fun bearing(): Float? {
            var filled = 0
            for (v in bins) if (!v.isNaN()) filled++
            if (filled < 14) return null
            var best = -1
            var bestV = -999.0
            for (i in 0 until 36) {
                val c = bins[i]
                if (c.isNaN()) continue
                val l = bins[(i + 35) % 36].let { if (it.isNaN()) c else it }
                val r = bins[(i + 1) % 36].let { if (it.isNaN()) c else it }
                val s = (l + 2 * c + r) / 4.0
                if (s > bestV) {
                    bestV = s
                    best = i
                }
            }
            return if (best < 0) null else best * 10f
        }
    }

    private lateinit var radar: RadarView
    private lateinit var statusTv: TextView
    private lateinit var list: LinearLayout
    private lateinit var showAll: CheckBox
    private lateinit var findSw: SwitchCompat
    private lateinit var lockPanel: View
    private lateinit var lockName: TextView
    private lateinit var lockDist: TextView
    private lateinit var lockBar: ProgressBar
    private lateinit var lockTrend: TextView
    private lateinit var btnSound: Button

    private val targets = HashMap<String, Target>()
    private var things: Map<String, String> = emptyMap()
    private var locked: String? = null
    private var sound = true
    private var saver = false
    private var ready = false
    private var started = false
    private var scanning = false
    private var scanSince = 0L
    private var openedAt = 0L

    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var sensors: SensorManager? = null
    private var heading = 0f
    private var hasCompass = false
    private val ui = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sonar)
        radar = findViewById(R.id.radar)
        statusTv = findViewById(R.id.status)
        list = findViewById(R.id.list)
        showAll = findViewById(R.id.showAll)
        findSw = findViewById(R.id.findable)
        lockPanel = findViewById(R.id.lockPanel)
        lockName = findViewById(R.id.lockName)
        lockDist = findViewById(R.id.lockDist)
        lockBar = findViewById(R.id.lockBar)
        lockTrend = findViewById(R.id.lockTrend)
        btnSound = findViewById(R.id.btnSound)

        saver = Prefs.saver(this)
        radar.saver = saver
        things = Prefs.things(this)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            null
        }

        radar.onTap = { lock(it) }
        findViewById<Button>(R.id.btnUnlock).setOnClickListener { lock(null) }
        btnSound.setOnClickListener {
            sound = !sound
            btnSound.text = if (sound) "🔊 Ping" else "🔇 Quiet"
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveLocked() }
        showAll.setOnCheckedChangeListener { _, _ -> refresh() }

        findSw.isChecked = Prefs.findable(this)
        findSw.setOnCheckedChangeListener { _, on ->
            Prefs.setFindable(this, on)
            if (on) startAdvertising() else stopAdvertising()
        }

        withPerms(Perms.nearby()) {
            ready = true
            startAll()
        }
    }

    override fun onStart() {
        super.onStart()
        if (ready) startAll()
    }

    override fun onStop() {
        stopAll()
        super.onStop()
    }

    private fun startAll() {
        if (started) return
        started = true
        openedAt = SystemClock.elapsedRealtime()
        startScan()
        val sm = sensors
        val s = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        hasCompass = s != null
        radar.hasCompass = hasCompass
        if (sm != null && s != null) sm.registerListener(this, s, if (saver) SensorManager.SENSOR_DELAY_UI else SensorManager.SENSOR_DELAY_GAME)
        if (findSw.isChecked) startAdvertising()
        ui.post(tick)
        ui.post(pinger)
    }

    private fun stopAll() {
        if (!started) return
        started = false
        ui.removeCallbacks(tick)
        ui.removeCallbacks(pinger)
        stopScan()
        sensors?.unregisterListener(this)
        stopAdvertising()
    }

    // ---------------- BLE scan ----------------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) onResult(r)
        }

        override fun onScanFailed(errorCode: Int) {
            statusTv.text = "Scan failed (code $errorCode). Turn Bluetooth off and on, then reopen."
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            statusTv.text = "Turn on Bluetooth, then reopen Sonar"
            return
        }
        scanner = ad.bluetoothLeScanner
        val sc = scanner ?: return
        val mode = if (saver) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
        try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(mode).build(), scanCb)
            scanning = true
            scanSince = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            statusTv.text = "Scan error: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            scanner?.stopScan(scanCb)
        } catch (e: Exception) {
        }
        scanning = false
        scanSince = SystemClock.elapsedRealtime()
    }

    @SuppressLint("MissingPermission")
    private fun onResult(r: ScanResult) {
        val addr = r.device?.address ?: return
        val t = targets.getOrPut(addr) { Target(addr) }
        val rec = r.scanRecord
        val nm = rec?.deviceName ?: try {
            r.device.name
        } catch (e: SecurityException) {
            null
        }
        if (!nm.isNullOrBlank()) t.name = nm
        val friendBytes = rec?.getServiceData(LAPIT_UUID)
        if (friendBytes != null && friendBytes.isNotEmpty()) t.friend = String(friendBytes, Charsets.US_ASCII)
        val tx = rec?.txPowerLevel ?: Int.MIN_VALUE
        if (tx > -100 && tx < 30) t.txPower = tx

        val now = SystemClock.elapsedRealtime()
        t.rssi = if (t.lastSeen == 0L || now - t.lastSeen > 10000) r.rssi.toDouble() else t.rssi * 0.7 + r.rssi * 0.3
        t.lastSeen = now
        if (hasCompass) {
            val bin = ((((heading + 5f) % 360f) + 360f) % 360f / 10f).toInt().coerceIn(0, 35)
            val old = t.bins[bin]
            t.bins[bin] = if (old.isNaN()) r.rssi.toDouble() else old * 0.6 + r.rssi * 0.4
        }
        t.history.addLast(Pair(now, t.rssi))
        while (t.history.size > 60) t.history.removeFirst()
    }

    // ---------------- Compass ----------------

    private val rotM = FloatArray(9)
    private val orient = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent?) {
        val ev = e ?: return
        SensorManager.getRotationMatrixFromVector(rotM, ev.values)
        SensorManager.getOrientation(rotM, orient)
        var deg = Math.toDegrees(orient[0].toDouble()).toFloat()
        if (deg < 0) deg += 360f
        val diff = ((deg - heading + 540f) % 360f) - 180f
        heading = (heading + diff * 0.25f + 360f) % 360f
        radar.heading = heading
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- Being findable (Lapit beacon) ----------------

    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            toast("🙋 Friends with Lapit now see you as ${Prefs.nick(this@SonarActivity)}")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) return
            toast("This phone can't broadcast (code $errorCode)")
            findSw.isChecked = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            toast("This phone can't be findable (no BLE broadcast support)")
            findSw.isChecked = false
            return
        }
        val nick = Prefs.nick(this).filter { it.code in 32..126 }.take(12).toByteArray(Charsets.US_ASCII)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (saver) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .addServiceData(LAPIT_UUID, nick)
            .build()
        try {
            adv.startAdvertising(settings, data, advCb)
        } catch (e: Exception) {
            toast("Can't broadcast: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCb)
        } catch (e: Exception) {
        }
    }

    // ---------------- Screen updates ----------------

    private val tick = object : Runnable {
        override fun run() {
            dutyCycle()
            refresh()
            ui.postDelayed(this, if (saver) 1000L else 500L)
        }
    }

    /** Battery saver: scan 8 s, rest 12 s (unless you're tracking something). */
    private fun dutyCycle() {
        if (!saver) return
        val now = SystemClock.elapsedRealtime()
        if (locked != null) {
            if (!scanning) startScan()
            return
        }
        if (scanning && now - scanSince > 8000) stopScan()
        else if (!scanning && now - scanSince > 12000) startScan()
    }

    private fun fmtDist(d: Double): String = when {
        d < 1.0 -> "${(d * 100).roundToInt()} cm"
        d < 10.0 -> String.format(Locale.US, "%.1f m", d)
        else -> "${d.roundToInt()} m"
    }

    private fun fallbackAngle(addr: String): Float = ((addr.hashCode() and 0x7fffffff) % 360).toFloat()

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        targets.values.removeAll { now - it.lastSeen > 90000 }

        val all = showAll.isChecked
        val visible = targets.values
            .filter { now - it.lastSeen < 20000 }
            .filter { all || it.name != null || it.friend != null || things.containsKey(it.addr) || it.addr == locked }
            .sortedByDescending { it.rssi }

        radar.dots = visible.take(40).map {
            RadarView.Dot(
                key = it.addr,
                label = it.label(things),
                dist = it.distance(),
                bearing = it.bearing(),
                fallback = fallbackAngle(it.addr),
                locked = it.addr == locked,
                kind = when {
                    things.containsKey(it.addr) -> 2
                    it.friend != null -> 1
                    else -> 0
                }
            )
        }

        val total = targets.values.count { now - it.lastSeen < 20000 }
        val scanState = if (scanning) "scanning" else "resting (saver)"
        statusTv.text = when {
            total == 0 && now - openedAt > 8000 ->
                "Nothing found yet. Check Bluetooth and Location are ON. Things in pairing mode are easiest to find."
            !hasCompass -> "$total around • $scanState • no compass on this phone: distance only"
            else -> "$total around • $scanState • Hold phone flat and turn slowly in a full circle to get direction"
        }

        list.removeAllViews()
        for (t in visible.take(30)) {
            val tv = TextView(this)
            val age = (now - t.lastSeen) / 1000
            val dir = t.bearing()?.let { " • " + compassName(it) } ?: ""
            tv.text = "${t.label(things)}\n≈ ${fmtDist(t.distance())} • ${t.rssi.roundToInt()} dBm$dir • ${age}s ago"
            tv.textSize = 13f
            tv.setTextColor(if (t.addr == locked) 0xFFFF5A7A.toInt() else 0xFFDDEEE4.toInt())
            tv.setPadding(dp(10), dp(8), dp(10), dp(8))
            tv.setBackgroundResource(R.drawable.tile_bg)
            tv.setOnClickListener { lock(t.addr) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            list.addView(tv, lp)
        }
        if (visible.isEmpty()) {
            val tv = TextView(this)
            tv.text = if (all) "No Bluetooth devices yet…" else "No named devices yet. Tick “Show unnamed devices too” to see everything."
            tv.setTextColor(0xFF8FA89A.toInt())
            tv.setPadding(dp(10), dp(10), dp(10), dp(10))
            list.addView(tv)
        }

        updateLock(now)
    }

    private fun compassName(b: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((b + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]
    }

    private fun relativeArrow(b: Float): String {
        val rel = ((b - heading) % 360f + 360f) % 360f
        return when {
            rel < 30 || rel > 330 -> "⬆ ahead"
            rel < 150 -> "➡ to your right"
            rel < 210 -> "⬇ behind you"
            else -> "⬅ to your left"
        }
    }

    private fun lock(addr: String?) {
        locked = addr
        if (addr != null) {
            Util.vibrate(this, 40)
            if (saver && !scanning) startScan()
        }
        refresh()
    }

    private fun updateLock(now: Long) {
        val t = locked?.let { targets[it] }
        if (t == null) {
            lockPanel.visibility = View.GONE
            return
        }
        lockPanel.visibility = View.VISIBLE
        lockName.text = "🎯 " + t.label(things)
        val age = now - t.lastSeen
        if (age > 8000) {
            lockDist.text = "Signal lost…"
            lockTrend.text = "Walk around slowly. It may be off, out of battery, or out of range (~10–30 m)."
            lockBar.progress = 0
            return
        }
        lockDist.text = "≈ " + fmtDist(t.distance())
        lockBar.progress = ((t.rssi + 100.0) / 60.0 * 100.0).roundToInt().coerceIn(0, 100)

        val old = t.history.firstOrNull { now - it.first <= 4000 }?.second ?: t.rssi
        val diff = t.rssi - old
        val trend = when {
            diff > 3 -> "🔥 Hotter — keep going"
            diff < -3 -> "❄ Colder — turn around"
            else -> "• Steady"
        }
        val b = t.bearing()
        val dir = if (b != null && hasCompass) "  •  Strongest: ${relativeArrow(b)}" else if (hasCompass) "  •  Turn in a circle to find direction" else ""
        lockTrend.text = trend + dir
    }

    /** Sonar ping: faster beeps = closer. */
    private val pinger = object : Runnable {
        override fun run() {
            var next = 1000L
            val t = locked?.let { targets[it] }
            if (t != null && SystemClock.elapsedRealtime() - t.lastSeen < 8000) {
                val strength = ((t.rssi + 95.0) / 60.0).coerceIn(0.0, 1.0)
                next = (1400 - strength * 1280).toLong()
                if (sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                if (t.distance() < 1.0) Util.vibrate(this@SonarActivity, 25)
            }
            ui.postDelayed(this, next)
        }
    }

    private fun saveLocked() {
        val addr = locked ?: return
        val t = targets[addr] ?: return
        val et = EditText(this)
        et.setText(things[addr] ?: t.friend ?: t.name ?: "")
        et.hint = "e.g. Earbuds ni Matthew"
        AlertDialog.Builder(this)
            .setTitle("Save as My Thing")
            .setMessage("Saved things show up as ⭐ yellow dots. Note: some gadgets change their Bluetooth address, so this works best for trackers, watches and speakers.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setThing(this, addr, et.text.toString())
                things = Prefs.things(this)
                refresh()
            }
            .setNeutralButton("Forget") { _, _ ->
                Prefs.setThing(this, addr, null)
                things = Prefs.things(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        tone?.release()
        super.onDestroy()
    }
}
