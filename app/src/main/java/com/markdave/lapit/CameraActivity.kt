package com.markdave.lapit

import android.content.ContentValues
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** This phone sits on the tripod and takes the photos. Another phone controls it. */
class CameraActivity : PermActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var countdownTv: TextView
    private lateinit var flashView: View
    private lateinit var blackout: View

    private var link: NearbyLink? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var zoom = 0f
    private var lightsOut = false
    private var shots = 0
    private var counting = false
    private var saver = false
    private val controllers = LinkedHashMap<String, String>()

    @Volatile private var streamOn = true
    @Volatile private var controllerId: String? = null
    @Volatile private var wantThumb = false
    @Volatile private var mirror = false
    private var lastSent = 0L

    private val ui = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        statusTv = findViewById(R.id.status)
        infoTv = findViewById(R.id.info)
        countdownTv = findViewById(R.id.countdown)
        flashView = findViewById(R.id.flash)
        blackout = findViewById(R.id.blackout)

        saver = Prefs.saver(this)
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        } catch (e: Exception) {
            null
        }

        findViewById<Button>(R.id.btnFlipLocal).setOnClickListener { flip() }
        findViewById<Button>(R.id.btnShootLocal).setOnClickListener { shoot(3) }
        findViewById<Button>(R.id.btnLights).setOnClickListener { setLightsOut(true) }
        blackout.setOnClickListener { setLightsOut(false) }

        statusTv.text = "Starting…"
        updateInfo()
        withPerms(Perms.camera()) {
            startCamera()
            startLink()
        }
        ui.postDelayed(infoTick, 15000)
    }

    private val infoTick = object : Runnable {
        override fun run() {
            updateInfo()
            sendStatus()
            ui.postDelayed(this, 15000)
        }
    }

    private fun updateInfo() {
        val bat = Util.battery(this)
        infoTv.text = "🔋 $bat%   •   📸 $shots shots" + (if (saver) "   •   🍃 Saver ON" else "") +
            "\nPhotos save to Gallery ▸ Pictures/Lapit"
    }

    // ---------------- Bluetooth link ----------------

    private fun startLink() {
        val myName = Prefs.nick(this) + "|" + Prefs.deviceId(this)
        link = NearbyLink(this, SERVICE_CAM, Strategy.P2P_STAR, myName, object : NearbyLink.Listener {
            override fun onConnectionRequest(endpointId: String, name: String, code: String): Boolean {
                if (isFinishing) return false
                AlertDialog.Builder(this@CameraActivity)
                    .setTitle("Allow remote control?")
                    .setMessage(
                        "${NearbyLink.displayName(name)} wants to control this camera.\n\n" +
                            "Code: $code\n\nMake sure the remote phone shows the same code."
                    )
                    .setPositiveButton("Allow") { _, _ -> link?.accept(endpointId) }
                    .setNegativeButton("Deny") { _, _ -> link?.reject(endpointId) }
                    .setCancelable(false)
                    .show()
                return false
            }

            override fun onConnected(endpointId: String, name: String) {
                controllers[endpointId] = name
                controllerId = endpointId
                statusTv.text = "🎮 Remote: ${NearbyLink.displayName(name)} — ready to shoot"
                Util.vibrate(this@CameraActivity, 80)
                sendStatus()
                if (saver) ui.postDelayed({ if (controllerId != null && !lightsOut) setLightsOut(true) }, 8000)
            }

            override fun onDisconnected(endpointId: String, name: String) {
                controllers.remove(endpointId)
                controllerId = controllers.keys.lastOrNull()
                if (controllerId == null) {
                    statusTv.text = "Remote disconnected — waiting for a remote…"
                    if (lightsOut) setLightsOut(false)
                }
            }

            override fun onBytes(endpointId: String, data: ByteArray) {
                val o = Wire.parse(data) ?: return
                handle(endpointId, o)
            }

            override fun onStatus(msg: String) {
                statusTv.text = msg
            }
        })
        link?.startAdvertising()
        statusTv.text = "📡 Waiting for a remote…\nOn the other phone open Lapit ▸ Remote Shutter."
    }

    private fun handle(from: String, o: JSONObject) {
        controllerId = from
        when (o.optString("t")) {
            "shoot" -> shoot(o.optInt("timer", 0))
            "flip" -> flip()
            "flash" -> {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                imageCapture?.flashMode = flashMode
                sendStatus()
            }
            "zoom" -> {
                zoom = o.optDouble("v", 0.0).toFloat().coerceIn(0f, 1f)
                camera?.cameraControl?.setLinearZoom(zoom)
            }
            "stream" -> {
                streamOn = o.optBoolean("on", true)
                sendStatus()
            }
            "lights" -> setLightsOut(o.optBoolean("on", false))
            "ping" -> sendStatus()
        }
    }

    private fun flashName(): String = when (flashMode) {
        ImageCapture.FLASH_MODE_ON -> "on"
        ImageCapture.FLASH_MODE_AUTO -> "auto"
        else -> "off"
    }

    private fun sendStatus() {
        val l = link ?: return
        if (l.connected.isEmpty()) return
        l.sendAll(
            Wire.json(
                Wire.obj(
                    "t" to "status",
                    "lens" to (if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"),
                    "flash" to flashName(),
                    "zoom" to zoom.toDouble(),
                    "bat" to Util.battery(this),
                    "lights" to lightsOut,
                    "shots" to shots,
                    "stream" to streamOn,
                    "saver" to saver
                )
            )
        )
    }

    private fun sendErr(msg: String) {
        statusTv.text = "⚠ $msg"
        link?.sendAll(Wire.json(Wire.obj("t" to "error", "msg" to msg)))
    }

    // ---------------- Camera ----------------

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                provider = f.get()
                bindCamera()
            } catch (e: Exception) {
                statusTv.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        p.unbindAll()
        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val has = try {
            p.hasCamera(selector)
        } catch (e: Exception) {
            false
        }
        if (!has) {
            lensFacing = CameraSelector.LENS_FACING_BACK
            selector = CameraSelector.DEFAULT_BACK_CAMERA
        }

        val cap = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        @Suppress("DEPRECATION")
        val an = ImageAnalysis.Builder()
            .setTargetResolution(Size(480, 640))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        an.setAnalyzer(exec) { img -> onFrame(img) }

        val cases = ArrayList<UseCase>()
        cases.add(cap)
        cases.add(an)
        if (!lightsOut) {
            val pv = Preview.Builder().build()
            pv.setSurfaceProvider(previewView.surfaceProvider)
            cases.add(pv)
        }

        camera = try {
            p.bindToLifecycle(this, selector, *cases.toTypedArray())
        } catch (e: Exception) {
            // Some older phones can't run live view + photo + preview together: drop the remote live view
            try {
                cases.remove(an)
                p.unbindAll()
                statusTv.text = "This phone can't stream live view — photos still work"
                p.bindToLifecycle(this, selector, *cases.toTypedArray())
            } catch (e2: Exception) {
                statusTv.text = "Camera error: ${e2.message}"
                null
            }
        }
        imageCapture = cap
        camera?.cameraControl?.setLinearZoom(zoom)
        mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
    }

    /** Runs on the camera thread for each preview frame. Sends a small JPEG to the remote. */
    private fun onFrame(img: ImageProxy) {
        try {
            val to = controllerId ?: return
            val l = link ?: return
            val thumb = wantThumb
            val now = SystemClock.elapsedRealtime()
            val interval = if (saver) 500L else 140L
            if (!thumb && (!streamOn || now - lastSent < interval)) return
            val bmp = img.toBitmap()
            val out = Util.scaleRotate(bmp, img.imageInfo.rotationDegrees, if (thumb) 640 else 400, mirror)
            if (thumb) {
                wantThumb = false
                l.send(to, Wire.blob(Wire.PHOTO, Util.jpeg(out, 82)))
            } else {
                if (l.sendFrame(to, Wire.blob(Wire.FRAME, Util.jpeg(out, if (saver) 35 else 50)))) lastSent = now
            }
        } catch (e: Exception) {
        } finally {
            img.close()
        }
    }

    private fun flip() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
        bindCamera()
        sendStatus()
    }

    private fun shoot(timer: Int) {
        if (counting) return
        if (timer <= 0) {
            takePhoto()
            return
        }
        counting = true
        var n = timer
        countdownTv.visibility = View.VISIBLE
        val r = object : Runnable {
            override fun run() {
                if (n <= 0) {
                    countdownTv.visibility = View.GONE
                    counting = false
                    takePhoto()
                    return
                }
                countdownTv.text = n.toString()
                link?.sendAll(Wire.json(Wire.obj("t" to "count", "n" to n)))
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                n--
                ui.postDelayed(this, 1000)
            }
        }
        ui.post(r)
    }

    private fun takePhoto() {
        val cap = imageCapture
        if (cap == null) {
            sendErr("Camera not ready yet")
            return
        }
        val name = "Lapit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lapit")
        val opts = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            .build()

        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        if (!lightsOut) {
            flashView.alpha = 1f
            flashView.visibility = View.VISIBLE
            flashView.animate().alpha(0f).setDuration(300).withEndAction { flashView.visibility = View.GONE }.start()
        }

        cap.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                shots++
                wantThumb = true
                link?.sendAll(Wire.json(Wire.obj("t" to "saved", "name" to name, "shots" to shots)))
                if (!lightsOut) statusTv.text = "📸 Saved photo #$shots"
                updateInfo()
            }

            override fun onError(exception: ImageCaptureException) {
                sendErr("Photo failed: ${exception.message}")
            }
        })
    }

    private fun setLightsOut(on: Boolean) {
        if (lightsOut == on) return
        lightsOut = on
        blackout.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        bindCamera()
        sendStatus()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        link?.stopAll()
        exec.shutdown()
        tone?.release()
        super.onDestroy()
    }
}
