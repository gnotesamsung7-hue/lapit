package com.markdave.lapit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone-to-phone link over Bluetooth (+ Wi-Fi Direct when available) using Google Nearby Connections.
 * Works with no internet and no SIM load.
 * Endpoint names look like "Callsign|deviceId".
 */
class NearbyLink(
    ctx: Context,
    private val serviceId: String,
    private val strategy: Strategy,
    val myName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onPeerFound(endpointId: String, name: String) {}
        /** Return true to auto-accept. Return false and call accept()/reject() yourself later. */
        fun onConnectionRequest(endpointId: String, name: String, code: String): Boolean = true
        fun onConnected(endpointId: String, name: String) {}
        fun onConnectFailed(endpointId: String) {}
        fun onDisconnected(endpointId: String, name: String) {}
        fun onBytes(endpointId: String, data: ByteArray) {}
        fun onStatus(msg: String) {}
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(ctx.applicationContext)
    private val main = Handler(Looper.getMainLooper())

    val connected = LinkedHashMap<String, String>()
    private val pendingNames = HashMap<String, String>()
    private val requested = HashSet<String>()
    private val found = HashMap<String, Pair<String, Long>>()
    private val framesInFlight = ConcurrentHashMap<Long, Long>()

    var autoConnect = true
    var initiateRule: (String) -> Boolean = { true }
    var advertising = false
        private set
    var discovering = false
        private set

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val b = payload.asBytes() ?: return
                listener.onBytes(endpointId, b)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status != PayloadTransferUpdate.Status.IN_PROGRESS) framesInFlight.remove(update.payloadId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            if (listener.onConnectionRequest(endpointId, info.endpointName, info.authenticationDigits)) accept(endpointId)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val name = pendingNames.remove(endpointId) ?: found[endpointId]?.first ?: endpointId
            requested.remove(endpointId)
            if (result.status.isSuccess) {
                connected[endpointId] = name
                listener.onConnected(endpointId, name)
            } else {
                listener.onConnectFailed(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = connected.remove(endpointId) ?: endpointId
            requested.remove(endpointId)
            listener.onDisconnected(endpointId, name)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            found[endpointId] = Pair(info.endpointName, SystemClock.elapsedRealtime())
            listener.onPeerFound(endpointId, info.endpointName)
            if (autoConnect && initiateRule(info.endpointName)) requestConnect(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            found.remove(endpointId)
        }
    }

    fun startAdvertising() {
        val o = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(myName, serviceId, lifecycle, o)
            .addOnSuccessListener { advertising = true }
            .addOnFailureListener { e ->
                if (code(e) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) advertising = true
                else listener.onStatus("Can't go visible: ${e.message}")
            }
    }

    fun startDiscovery() {
        val o = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discovery, o)
            .addOnSuccessListener { discovering = true }
            .addOnFailureListener { e ->
                if (code(e) == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) discovering = true
                else listener.onStatus("Can't search: ${e.message}")
            }
    }

    fun stopDiscovery() {
        client.stopDiscovery()
        discovering = false
    }

    fun stopAdvertising() {
        client.stopAdvertising()
        advertising = false
    }

    fun requestConnect(id: String) {
        if (connected.containsKey(id) || !requested.add(id)) return
        client.requestConnection(myName, id, lifecycle)
            .addOnFailureListener { e ->
                requested.remove(id)
                if (code(e) != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) listener.onConnectFailed(id)
            }
    }

    /** Connect to anyone we found a while ago but never linked with (covers one-sided discovery). */
    fun retryFound(olderThanMs: Long) {
        val now = SystemClock.elapsedRealtime()
        for ((id, v) in found.entries.toList()) {
            if (!connected.containsKey(id) && !requested.contains(id) && now - v.second > olderThanMs) requestConnect(id)
        }
    }

    fun accept(id: String) {
        client.acceptConnection(id, payloads)
    }

    fun reject(id: String) {
        client.rejectConnection(id)
        pendingNames.remove(id)
    }

    fun send(id: String, data: ByteArray) {
        client.sendPayload(id, Payload.fromBytes(data))
    }

    /** For live video: drops the frame if the link is still busy, so the preview never lags behind. */
    fun sendFrame(id: String, data: ByteArray): Boolean {
        val now = SystemClock.elapsedRealtime()
        for ((k, t) in framesInFlight.entries.toList()) if (now - t > 3000) framesInFlight.remove(k)
        if (framesInFlight.size >= 2) return false
        val p = Payload.fromBytes(data)
        framesInFlight[p.id] = now
        client.sendPayload(id, p)
        return true
    }

    fun sendAll(data: ByteArray, except: String? = null) {
        val ids = connected.keys.filter { it != except }
        if (ids.isNotEmpty()) client.sendPayload(ids, Payload.fromBytes(data))
    }

    fun stopAll() {
        main.removeCallbacksAndMessages(null)
        try {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        } catch (e: Exception) {
        }
        connected.clear()
        advertising = false
        discovering = false
    }

    private fun code(e: Exception): Int = (e as? ApiException)?.statusCode ?: -1

    companion object {
        fun displayName(n: String): String = n.substringBefore('|')
        fun idOf(n: String): String = n.substringAfter('|', n)
    }
}
