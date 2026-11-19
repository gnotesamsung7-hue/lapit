package com.markdave.lapit

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** This phone controls the camera phone: live view, shutter, timer, flash, zoom. */
class RemoteActivity : PermActivity() {
    private lateinit var statusTv: TextView
    private lateinit var camInfo: TextView
    private lateinit var live: ImageView
    private lateinit var hint: TextView
    private lateinit var countdown: TextView
    private lateinit var lastShot: ImageView
    private lateinit var btnFlash: Button
    private lateinit var btnTimer: Button
    private lateinit var btnStream: Button
    private lateinit var btnLights: Button
    private lateinit var zoomBar: SeekBar

    private var link: NearbyLink? = null
    private var camId: String? = null
    private var connecting = false
    private var timer = 0
    private var stream = true
    private var camLights = false
    private var touchingZoom = false
    private var lastZoomSend = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val hideCount = Runnable { countdown.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        if (!Prefs.saver(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusTv = findViewById(R.id.status)
        camInfo = findViewById(R.id.camInfo)
        live = findViewById(R.id.live)
        hint = findViewById(R.id.hint)
        countdown = findViewById(R.id.countdown)
        lastShot = findViewById(R.id.lastShot)
        btnFlash = findViewById(R.id.btnFlash)
        btnTimer = findViewById(R.id.btnTimer)
        btnStream = findViewById(R.id.btnStream)
        btnLights = findViewById(R.id.btnLights)
        zoomBar = findViewById(R.id.zoom)

        findViewById<Button>(R.id.shutter).setOnClickListener { shoot() }
        findViewById<Button>(R.id.btnFlip).setOnClickListener { send(Wire.obj("t" to "flip")) }
        btnFlash.setOnClickListener { send(Wire.obj("t" to "flash")) }
        btnTimer.setOnClickListener {
            timer = when (timer) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            btnTimer.text = "⏱ ${timer}s"
        }
        btnStream.setOnClickListener {
            stream = !stream
            btnStream.text = if (stream) "👁 Live" else "👁 Off"
            send(Wire.obj("t" to "stream", "on" to stream))
            if (!stream) {
                live.setImageDrawable(null)
                hint.text = "Live view paused to save battery.\nYou can still shoot."
                hint.visibility = View.VISIBLE
            } else if (camId != null) hint.visibility = View.GONE
        }
        btnLights.setOnClickListener { send(Wire.obj("t" to "lights", "on" to !camLights)) }

        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastZoomSend > 80) {
                    lastZoomSend = now
                    send(Wire.obj("t" to "zoom", "v" to progress / 100.0))
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                touchingZoom = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                touchingZoom = false
                send(Wire.obj("t" to "zoom", "v" to (sb?.progress ?: 0) / 100.0))
            }
        })

        withPerms(Perms.nearby() + Perms.storage()) { startLink() }
    }

    private fun status(s: String) {
        statusTv.text = s
    }

    private fun startLink() {
        val myName = Prefs.nick(this) + "|" + Prefs.deviceId(this)
        link = NearbyLink(this, SERVICE_CAM, Strategy.P2P_STAR, myName, object : NearbyLink.Listener {
            override fun onPeerFound(endpointId: String, name: String) {
                if (camId == null && !connecting) {
                    connecting = true
                    status("Found 📷 ${NearbyLink.displayName(name)} — connecting…")
                    link?.requestConnect(endpointId)
                }
            }

            override fun onConnectionRequest(endpointId: String, name: String, code: String): Boolean {
                status("Code $code — tap Allow on the camera phone")
                return true
            }

            override fun onConnected(endpointId: String, name: String) {
                camId = endpointId
                connecting = false
                link?.stopDiscovery()
                status("✅ Connected to ${NearbyLink.displayName(name)}")
                if (stream) hint.visibility = View.GONE
                Util.vibrate(this@RemoteActivity, 80)
                send(Wire.obj("t" to "ping"))
                if (!stream) send(Wire.obj("t" to "stream", "on" to false))
            }

            override fun onConnectFailed(endpointId: String) {
                connecting = false
                status("Couldn't connect — trying again…")
                ui.postDelayed({ restartDiscovery() }, 2000)
            }

            override fun onDisconnected(endpointId: String, name: String) {
                if (endpointId == camId) {
                    camId = null
                    status("Camera phone disconnected — searching…")
                    hint.text = "📡 Looking for the camera phone…"
                    hint.visibility = View.VISIBLE
                    live.setImageDrawable(null)
                    restartDiscovery()
                }
            }

            override fun onBytes(endpointId: String, data: ByteArray) {
                onData(data)
            }

            override fun onStatus(msg: String) {
                status(msg)
            }
        })
        link?.autoConnect = false
        link?.startDiscovery()
    }

    private fun restartDiscovery() {
        if (camId != null) return
        link?.stopDiscovery()
        link?.startDiscovery()
    }

    private fun send(o: JSONObject) {
        val id = camId ?: return
        link?.send(id, Wire.json(o))
    }

    private fun shoot() {
        if (camId == null) {
            toast("Not connected to a camera phone yet")
            return
        }
        send(Wire.obj("t" to "shoot", "timer" to timer))
        Util.vibrate(this, 30)
    }

    private fun onData(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0]) {
            Wire.FRAME -> {
                if (!stream) return
                val b = BitmapFactory.decodeByteArray(data, 1, data.size - 1) ?: return
                live.setImageBitmap(b)
            }
            Wire.PHOTO -> {
                val b = BitmapFactory.decodeByteArray(data, 1, data.size - 1) ?: return
                lastShot.setImageBitmap(b)
                lastShot.visibility = View.VISIBLE
                val name = "Lapit_remote_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                Util.saveJpegToGallery(this, data.copyOfRange(1, data.size), name)
            }
            Wire.JSON -> {
                val o = Wire.parse(data) ?: return
                when (o.optString("t")) {
                    "status" -> {
                        camLights = o.optBoolean("lights")
                        btnFlash.text = "⚡ " + o.optString("flash", "off").replaceFirstChar { it.uppercase() }
                        btnLights.text = if (camLights) "☀ Cam screen" else "🌙 Cam screen"
                        val lens = if (o.optString("lens") == "front") "Selfie" else "Back"
                        camInfo.text = "📷 $lens cam  •  🔋 ${o.optInt("bat")}%  •  ${o.optInt("shots")} shots" +
                            (if (camLights) "  •  🌙 screen dark" else "")
                        if (!touchingZoom) zoomBar.progress = (o.optDouble("zoom", 0.0) * 100).toInt()
                    }
                    "count" -> {
                        countdown.text = o.optInt("n").toString()
                        countdown.visibility = View.VISIBLE
                        ui.removeCallbacks(hideCount)
                        ui.postDelayed(hideCount, 1200)
                    }
                    "saved" -> {
                        countdown.visibility = View.GONE
                        Util.vibrate(this, 40)
                        status("📸 Click! Photo #${o.optInt("shots")} saved on the camera phone (preview saved here too)")
                    }
                    "error" -> status("⚠ " + o.optString("msg"))
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && camId != null) {
            if ((event?.repeatCount ?: 0) == 0) shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        link?.stopAll()
        super.onDestroy()
    }
}
