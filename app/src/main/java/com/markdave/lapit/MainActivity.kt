package com.markdave.lapit

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : PermActivity() {
    private lateinit var nickTv: TextView
    private lateinit var batTv: TextView
    private lateinit var saverHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nickTv = findViewById(R.id.nick)
        batTv = findViewById(R.id.battery)
        saverHint = findViewById(R.id.saverHint)

        nickTv.setOnClickListener { editNick() }

        val sw = findViewById<SwitchCompat>(R.id.saver)
        sw.isChecked = Prefs.saver(this)
        updateSaverHint(sw.isChecked)
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.setSaver(this, on)
            updateSaverHint(on)
        }

        val tiles = findViewById<LinearLayout>(R.id.tiles)
        tile(tiles, "📷  Be the Camera", "Put this phone on a tripod or ledge. Another phone takes the shots.") {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        tile(tiles, "🎮  Remote Shutter", "Control the camera phone: live view, timer, zoom, flash, flip.") {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        tile(tiles, "💬  Crowd Chat", "Text your group with no signal — messages hop phone-to-phone through the crowd.") {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        tile(tiles, "📡  Sonar Finder", "Find Bluetooth things around you: earbuds, watch, speaker, tracker, friends.") {
            startActivity(Intent(this, SonarActivity::class.java))
        }
        tile(tiles, "🔗  Share Lapit", "Show a QR code so a friend can download the app.") { showQr() }

        val v = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.version).text = "Lapit v$v • made by Mark Dave"
    }

    override fun onResume() {
        super.onResume()
        refreshHeader()
    }

    private fun refreshHeader() {
        nickTv.text = "👤 ${Prefs.nick(this)}  ✎"
        val b = Util.battery(this)
        batTv.text = if (b >= 0) "🔋 $b%" else ""
    }

    private fun updateSaverHint(on: Boolean) {
        saverHint.text = if (on)
            "ON: slower live view, camera phone screen goes dark, Bluetooth scans in bursts, chat stops searching once linked."
        else
            "OFF: full speed. Turn on when your battery is low or for long events."
    }

    private fun tile(parent: LinearLayout, title: String, sub: String, onClick: () -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundResource(R.drawable.tile_bg)
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.isClickable = true
        box.isFocusable = true
        box.setOnClickListener { onClick() }

        val t = TextView(this)
        t.text = title
        t.textSize = 19f
        t.setTextColor(Color.WHITE)
        t.paint.isFakeBoldText = true
        box.addView(t)

        val s = TextView(this)
        s.text = sub
        s.textSize = 13f
        s.setTextColor(0xFF8FA89A.toInt())
        box.addView(s)

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        parent.addView(box, lp)
    }

    private fun editNick() {
        val et = EditText(this)
        et.setText(Prefs.nick(this))
        et.setSelection(et.text.length)
        AlertDialog.Builder(this)
            .setTitle("Your callsign")
            .setMessage("This is what nearby phones see. No real names needed.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setNick(this, et.text.toString())
                refreshHeader()
            }
            .setNeutralButton("Random") { _, _ ->
                Prefs.setNick(this, Callsigns.random())
                refreshHeader()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQr() {
        val size = 640
        val bmp = try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val m = QRCodeWriter().encode(APK_URL, BarcodeFormat.QR_CODE, size, size, hints)
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.setBackgroundColor(Color.WHITE)
        box.addView(iv, LinearLayout.LayoutParams(dp(260), dp(260)))
        val tv = TextView(this)
        tv.text = "Scan with the phone camera, download Lapit.apk, then open it and allow “Install unknown apps”.\n\nNeeds internet once to download. After that Lapit works offline."
        tv.textSize = 13f
        tv.setPadding(0, dp(10), 0, 0)
        box.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Share Lapit")
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
    }
}
