package com.markdave.lapit

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Crowd chat with no signal. Every phone running this screen links to the phones near it,
 * and messages hop phone-to-phone (up to 6 hops) so they reach people beyond Bluetooth range.
 * Messages are encrypted with the room code: other people's phones relay them but can't read them.
 */
class ChatActivity : PermActivity() {
    private lateinit var msgs: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var peersTv: TextView
    private lateinit var roomTv: TextView
    private lateinit var meshTv: TextView

    private var link: NearbyLink? = null
    private val seen = LinkedHashSet<String>()
    private val recent = ArrayDeque<JSONObject>()   // wire messages we pass on to newcomers
    private val hist = ArrayList<JSONObject>()       // readable messages for this room
    private var room = "LAPIT"
    private var roomTag = ""
    private lateinit var myId: String
    private lateinit var myNick: String
    private var saver = false
    private var relayed = 0
    private val ui = Handler(Looper.getMainLooper())

    private val quickReplies = listOf(
        "📍 Nasaan ka?", "✅ OK ako", "🚪 Kita tayo sa exit", "⏳ Wait lang",
        "🍔 Kain muna", "🚻 CR lang", "🏠 Uwi na tayo", "🔋 Low batt na ako"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        msgs = findViewById(R.id.msgs)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        peersTv = findViewById(R.id.peers)
        roomTv = findViewById(R.id.room)
        meshTv = findViewById(R.id.mesh)

        myId = Prefs.deviceId(this)
        myNick = Prefs.nick(this)
        saver = Prefs.saver(this)
        setRoom(Prefs.room(this))

        roomTv.setOnClickListener { askRoom() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else false
        }
        findViewById<Button>(R.id.btnSos).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Send SOS?")
                .setMessage("Everyone in room #$room nearby gets a loud alert. Type your location first if you can (e.g. \"near Gate 3\").")
                .setPositiveButton("Send SOS") { _, _ ->
                    val extra = input.text.toString().trim()
                    input.setText("")
                    sendText("🆘 SOS — I need help!" + (if (extra.isNotEmpty()) " $extra" else ""), "sos")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val quick = findViewById<LinearLayout>(R.id.quick)
        for (q in quickReplies) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.text = q
            b.isAllCaps = false
            b.setOnClickListener { sendText(q, "msg") }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            quick.addView(b, lp)
        }

        withPerms(Perms.nearby()) { startLink() }
        ui.postDelayed(meshTick, 5000)
    }

    // ---------------- Mesh ----------------

    private fun startLink() {
        link = NearbyLink(this, SERVICE_CHAT, Strategy.P2P_CLUSTER, "$myNick|$myId", object : NearbyLink.Listener {
            override fun onConnected(endpointId: String, name: String) {
                updatePeers()
                sysLine("🔗 ${NearbyLink.displayName(name)} joined the mesh")
                // store & forward: share recent messages with the newcomer
                for (o in recent) link?.send(endpointId, Wire.json(o))
            }

            override fun onDisconnected(endpointId: String, name: String) {
                updatePeers()
                sysLine("… ${NearbyLink.displayName(name)} went out of range")
            }

            override fun onBytes(endpointId: String, data: ByteArray) {
                val o = Wire.parse(data) ?: return
                receive(endpointId, o)
            }

            override fun onStatus(msg: String) {
                meshTv.text = msg
            }
        })
        // Only one side of each pair starts the connection, so the two phones don't collide
        link?.initiateRule = { other -> myId < NearbyLink.idOf(other) }
        link?.startAdvertising()
        link?.startDiscovery()
        updatePeers()
    }

    private val meshTick = object : Runnable {
        override fun run() {
            val l = link
            if (l != null) {
                l.retryFound(8000)
                val n = l.connected.size
                if (saver) {
                    // Searching is the biggest battery drain: pause it once we have 2+ links
                    if (n >= 2 && l.discovering) l.stopDiscovery()
                    else if (n < 2 && !l.discovering) l.startDiscovery()
                } else if (!l.discovering) l.startDiscovery()
                updatePeers()
            }
            ui.postDelayed(this, if (saver) 20000 else 10000)
        }
    }

    private fun updatePeers() {
        val l = link
        val names = l?.connected?.values?.map { NearbyLink.displayName(it) } ?: emptyList()
        peersTv.text = "📡 ${names.size} linked"
        val searching = if (l?.discovering == true) "searching" else "paused search (saver)"
        meshTv.text = (if (names.isEmpty()) "No phones linked yet — $searching…" else "Linked: " + names.joinToString(", ")) +
            "  •  relayed $relayed" + "\nYou are $myNick  •  only room #$room can read your messages"
    }

    private fun receive(from: String, o: JSONObject) {
        if (o.optString("t") != "msg") return
        val id = o.optString("id")
        if (id.isEmpty() || !seen.add(id)) return
        trimSeen()
        remember(o)

        if (o.optString("rh") == roomTag) {
            val plain = RoomCrypto.open(room, o.optString("enc"))
            if (plain != null) {
                try {
                    val m = JSONObject(plain)
                    m.put("id", id)
                    m.put("hops", o.optInt("hops", 0))
                    val sos = m.optString("kind") == "sos"
                    show(m, mine = false, record = true)
                    Util.vibrate(this, if (sos) 900L else 30L)
                } catch (e: Exception) {
                }
            }
        }

        val ttl = o.optInt("ttl", 0)
        if (ttl > 1) {
            val fwd = JSONObject(o.toString())
            fwd.put("ttl", ttl - 1)
            fwd.put("hops", o.optInt("hops", 0) + 1)
            link?.sendAll(Wire.json(fwd), except = from)
            relayed++
        }
    }

    private fun remember(o: JSONObject) {
        recent.addLast(o)
        while (recent.size > 30) recent.removeFirst()
    }

    private fun trimSeen() {
        while (seen.size > 2000) {
            val first = seen.iterator().next()
            seen.remove(first)
        }
    }

    private fun sendTyped() {
        val t = input.text.toString()
        if (t.isBlank()) return
        input.setText("")
        sendText(t, "msg")
    }

    private fun sendText(text: String, kind: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val inner = Wire.obj(
            "from" to myNick, "fid" to myId, "text" to t.take(500),
            "kind" to kind, "ts" to System.currentTimeMillis()
        )
        val wire = Wire.obj(
            "t" to "msg", "id" to id, "rh" to roomTag, "ttl" to 6, "hops" to 0,
            "enc" to RoomCrypto.seal(room, inner.toString())
        )
        seen.add(id)
        remember(wire)
        inner.put("id", id)
        show(inner, mine = true, record = true)
        val n = link?.connected?.size ?: 0
        link?.sendAll(Wire.json(wire))
        if (n == 0) sysLine("No one linked yet — your message will be passed on when someone comes in range")
    }

    // ---------------- UI ----------------

    private fun show(m: JSONObject, mine: Boolean, record: Boolean) {
        val kind = m.optString("kind")
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(m.optLong("ts", System.currentTimeMillis())))
        val hops = m.optInt("hops", 0)
        var meta = (if (mine) "You" else m.optString("from", "?")) + " · " + time
        if (!mine && hops > 0) meta += " · via $hops hop" + (if (hops > 1) "s" else "")
        val full = meta + "\n" + m.optString("text")
        val sp = SpannableString(full)
        sp.setSpan(RelativeSizeSpan(0.75f), 0, meta.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(0xFF9FC2AE.toInt()), 0, meta.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val tv = TextView(this)
        tv.text = sp
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        tv.setBackgroundResource(
            when {
                kind == "sos" -> R.drawable.bubble_sos
                mine -> R.drawable.bubble_me
                else -> R.drawable.bubble_them
            }
        )
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.topMargin = dp(6)
        msgs.addView(tv, lp)

        if (record) {
            hist.add(m)
            while (hist.size > 100) hist.removeAt(0)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sysLine(s: String) {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = 12f
        tv.setTextColor(0xFF6C8577.toInt())
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(6), 0, dp(2))
        msgs.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setRoom(r: String) {
        saveHistory()
        room = r
        roomTag = RoomCrypto.tag(r)
        Prefs.setRoom(this, r)
        roomTv.text = "# $r ✎"
        msgs.removeAllViews()
        hist.clear()
        val s = Prefs.history(this, r)
        if (s != null) {
            try {
                val arr = JSONArray(s)
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val mid = m.optString("id")
                    if (mid.isNotEmpty()) seen.add(mid)
                    show(m, mine = m.optString("fid") == myId, record = true)
                }
            } catch (e: Exception) {
            }
        }
        sysLine("Room #$r — share this code with your group. Others nearby help pass messages along but can't read them.")
    }

    private fun askRoom() {
        val et = EditText(this)
        et.setText(room)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        et.setSelection(et.text.length)
        AlertDialog.Builder(this)
            .setTitle("Room code")
            .setMessage("Everyone in your group types the same code (e.g. BARKADA7). Use LAPIT to talk with anyone nearby.")
            .setView(et)
            .setPositiveButton("Join") { _, _ ->
                val r = et.text.toString().uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(16)
                if (r.isNotEmpty() && r != room) {
                    setRoom(r)
                    updatePeers()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveHistory() {
        if (room.isEmpty() || hist.isEmpty()) return
        val arr = JSONArray()
        for (m in hist) arr.put(m)
        Prefs.setHistory(this, room, arr.toString())
    }

    override fun onPause() {
        saveHistory()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        link?.stopAll()
        super.onDestroy()
    }
}
