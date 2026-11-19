package com.markdave.lapit

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

const val SERVICE_CAM = "com.markdave.lapit.CAM"
const val SERVICE_CHAT = "com.markdave.lapit.CHAT"

// Change these if you name the GitHub repo differently
const val GITHUB_USER = "gnotesamsung7-hue"
const val GITHUB_REPO = "lapit"
val APK_URL = "https://github.com/$GITHUB_USER/$GITHUB_REPO/releases/latest/download/Lapit.apk"

// BLE "I'm a Lapit user" beacon id, so friends show up on the sonar by callsign
val LAPIT_UUID: ParcelUuid = ParcelUuid.fromString("0000f1a9-0000-1000-8000-00805f9b34fb")

object Callsigns {
    private val a = listOf("Astig", "Tahimik", "Mabilis", "Lodi", "Kidlat", "Bagyo", "Turbo", "Chill", "Ninja", "Bida")
    private val b = listOf("Agila", "Tarsier", "Maya", "Kalabaw", "Butiki", "Pawikan", "Tamaraw", "Bayawak", "Kuwago", "Tikbalang")
    fun random(): String = a.random() + b.random() + Random.nextInt(10, 99)
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("lapit", Context.MODE_PRIVATE)

    fun nick(c: Context): String {
        val s = sp(c).getString("nick", null)
        if (!s.isNullOrBlank()) return s
        val n = Callsigns.random()
        sp(c).edit().putString("nick", n).apply()
        return n
    }

    fun setNick(c: Context, n: String) {
        val clean = n.replace("|", "").trim().take(16)
        if (clean.isNotEmpty()) sp(c).edit().putString("nick", clean).apply()
    }

    fun deviceId(c: Context): String {
        val s = sp(c).getString("id", null)
        if (s != null) return s
        val hex = "0123456789abcdef"
        val n = (1..8).map { hex[Random.nextInt(16)] }.joinToString("")
        sp(c).edit().putString("id", n).apply()
        return n
    }

    fun saver(c: Context): Boolean = sp(c).getBoolean("saver", false)
    fun setSaver(c: Context, on: Boolean) = sp(c).edit().putBoolean("saver", on).apply()

    fun room(c: Context): String = sp(c).getString("room", "LAPIT") ?: "LAPIT"
    fun setRoom(c: Context, r: String) = sp(c).edit().putString("room", r).apply()

    fun findable(c: Context): Boolean = sp(c).getBoolean("findable", false)
    fun setFindable(c: Context, on: Boolean) = sp(c).edit().putBoolean("findable", on).apply()

    fun things(c: Context): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        val s = sp(c).getString("things", null) ?: return m
        try {
            val o = JSONObject(s)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = o.getString(k)
            }
        } catch (e: Exception) {
        }
        return m
    }

    fun setThing(c: Context, addr: String, label: String?) {
        val m = things(c)
        if (label.isNullOrBlank()) m.remove(addr) else m[addr] = label.trim()
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        sp(c).edit().putString("things", o.toString()).apply()
    }

    fun history(c: Context, room: String): String? = sp(c).getString("hist_$room", null)
    fun setHistory(c: Context, room: String, json: String) =
        sp(c).edit().putString("hist_$room", json).apply()
}

object Perms {
    fun nearby(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        return l.toTypedArray()
    }

    fun storage(): Array<String> =
        if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray()

    fun camera(): Array<String> = nearby() + storage() + arrayOf(Manifest.permission.CAMERA)

    fun missing(c: Context, p: Array<String>): List<String> =
        p.filter { ContextCompat.checkSelfPermission(c, it) != PackageManager.PERMISSION_GRANTED }
}

/** Base screen: asks for permissions, then makes sure Bluetooth is on. */
abstract class PermActivity : AppCompatActivity() {
    private var pending: (() -> Unit)? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val denied = res.filterValues { !it }.keys.map { it.substringAfterLast('.') }
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Not allowed: ${denied.joinToString()} — some features may not work",
                    Toast.LENGTH_LONG
                ).show()
            }
            val b = pending
            pending = null
            ensureBluetoothOn()
            b?.invoke()
        }

    fun withPerms(p: Array<String>, block: () -> Unit) {
        val miss = Perms.missing(this, p)
        if (miss.isEmpty()) {
            ensureBluetoothOn()
            block()
        } else {
            pending = block
            permLauncher.launch(miss.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    fun ensureBluetoothOn() {
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!ad.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) {
                Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** Tiny message format: first byte = type. J = JSON text, F = live frame JPEG, P = photo preview JPEG. */
object Wire {
    const val JSON: Byte = 74
    const val FRAME: Byte = 70
    const val PHOTO: Byte = 80

    fun json(o: JSONObject): ByteArray = byteArrayOf(JSON) + o.toString().toByteArray(Charsets.UTF_8)
    fun blob(type: Byte, data: ByteArray): ByteArray = byteArrayOf(type) + data

    fun parse(b: ByteArray): JSONObject? {
        if (b.isEmpty() || b[0] != JSON) return null
        return try {
            JSONObject(String(b, 1, b.size - 1, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun obj(vararg kv: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for (p in kv) o.put(p.first, p.second)
        return o
    }
}

/** Room-code encryption: only phones with the same room code can read; others just relay. */
object RoomCrypto {
    private fun sha(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    fun tag(room: String): String =
        sha("lapit-tag:$room").take(5).joinToString("") { String.format("%02x", it.toInt() and 0xff) }

    fun seal(room: String, plain: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sha("lapit-room:$room"), "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun open(room: String, sealed: String): String? = try {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(sha("lapit-room:$room"), "AES"), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

object Util {
    fun battery(c: Context): Int = try {
        (c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) {
        -1
    }

    fun scaleRotate(src: Bitmap, rotation: Int, maxSide: Int, mirror: Boolean): Bitmap {
        val s = maxSide.toFloat() / maxOf(src.width, src.height)
        val m = Matrix()
        m.postScale(s, s)
        m.postRotate(rotation.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun jpeg(b: Bitmap, quality: Int): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, o)
        return o.toByteArray()
    }

    fun saveJpegToGallery(c: Context, bytes: ByteArray, name: String): Boolean {
        return try {
            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lapit")
            val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun vibrate(c: Context, ms: Long) {
        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (e: Exception) {
        }
    }
}
