package com.yacbridge.vpn.tunnel

import android.util.Log
import com.yacbridge.vpn.model.TunnelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * YAC Bridge Helper protocol implementation
 * Реализует протокол из yac-ws-bridge/adapter-and-helper
 *
 * Frame format:
 * [1 byte type][4 bytes streamId][4 bytes seqId][2 bytes len][data]
 */
class YacBridgeTunnel(private val config: TunnelConfig) {

    companion object {
        private const val TAG = "YacBridgeTunnel"

        // Frame types
        const val TYPE_HELLO: Byte = 0x01
        const val TYPE_HELLO_ACK: Byte = 0x02
        const val TYPE_OPEN: Byte = 0x03
        const val TYPE_OPEN_OK: Byte = 0x04
        const val TYPE_DATA: Byte = 0x05
        const val TYPE_FIN: Byte = 0x06
        const val TYPE_PING: Byte = 0x07
        const val TYPE_PONG: Byte = 0x08
        const val TYPE_ERROR: Byte = 0x09

        const val FRAME_HEADER_SIZE = 11 // 1 + 4 + 4 + 2
        const val MAX_FRAME_SIZE = 32 * 1024
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val streamCounter = AtomicInteger(1)
    private val seqCounter = AtomicInteger(0)
    private val streams = ConcurrentHashMap<Int, StreamContext>()

    val bytesIn = AtomicLong(0)
    val bytesOut = AtomicLong(0)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var connectedPeerId: String? = null
    private var onConnectedCallback: (() -> Unit)? = null
    private var onDisconnectedCallback: ((String) -> Unit)? = null

    fun setCallbacks(
        onConnected: () -> Unit,
        onDisconnected: (String) -> Unit
    ) {
        onConnectedCallback = onConnected
        onDisconnectedCallback = onDisconnected
    }

    fun connect() {
        val request = Request.Builder()
            .url(config.gatewayUrl)
            .addHeader("Authorization", "Bearer ${config.authToken}")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened, sending HELLO")
                sendHello(ws)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleFrame(ws, bytes.toByteArray())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Text message: $text")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: $code $reason")
                _connected.value = false
                onDisconnectedCallback?.invoke(reason)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _connected.value = false
                onDisconnectedCallback?.invoke(t.message ?: "Connection failed")
            }
        })

        // Start ping loop
        scope.launch {
            while (isActive) {
                delay(config.pingIntervalMs)
                sendPing()
            }
        }
    }

    fun disconnect() {
        scope.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connected.value = false
        streams.clear()
    }

    private fun sendHello(ws: WebSocket) {
        val token = config.authToken.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(FRAME_HEADER_SIZE + token.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_HELLO)
        buf.putInt(0) // streamId
        buf.putInt(seqCounter.getAndIncrement())
        buf.putShort(token.size.toShort())
        buf.put(token)
        ws.send(buf.array().toByteString())
    }

    private fun sendPing() {
        val buf = ByteBuffer.allocate(FRAME_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_PING)
        buf.putInt(0)
        buf.putInt(seqCounter.getAndIncrement())
        buf.putShort(0)
        webSocket?.send(buf.array().toByteString())
    }

    private fun handleFrame(ws: WebSocket, data: ByteArray) {
        if (data.size < FRAME_HEADER_SIZE) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val type = buf.get()
        val streamId = buf.int
        val seqId = buf.int
        val len = buf.short.toInt() and 0xFFFF
        val payload = if (len > 0) ByteArray(len).also { buf.get(it) } else ByteArray(0)

        when (type) {
            TYPE_HELLO_ACK -> {
                Log.i(TAG, "HELLO_ACK received, tunnel established")
                _connected.value = true
                onConnectedCallback?.invoke()
            }
            TYPE_OPEN_OK -> {
                val stream = streams[streamId]
                stream?.openAck?.complete(Unit)
                Log.d(TAG, "Stream $streamId opened OK")
            }
            TYPE_DATA -> {
                bytesIn.addAndGet(len.toLong())
                val stream = streams[streamId]
                stream?.incomingQueue?.offer(payload)
            }
            TYPE_FIN -> {
                val stream = streams[streamId]
                stream?.closed = true
                stream?.socket?.close()
                streams.remove(streamId)
                Log.d(TAG, "Stream $streamId closed by remote")
            }
            TYPE_PONG -> {
                Log.d(TAG, "PONG received")
            }
            TYPE_ERROR -> {
                Log.e(TAG, "Error frame for stream $streamId: ${payload.toString(Charsets.UTF_8)}")
            }
        }
    }

    fun openStream(host: String, port: Int): Int {
        val streamId = streamCounter.getAndAdd(2) // odd IDs for helper
        val target = "$host:$port"
        val targetBytes = target.toByteArray(Charsets.UTF_8)

        val buf = ByteBuffer.allocate(FRAME_HEADER_SIZE + targetBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_OPEN)
        buf.putInt(streamId)
        buf.putInt(seqCounter.getAndIncrement())
        buf.putShort(targetBytes.size.toShort())
        buf.put(targetBytes)

        val stream = StreamContext(streamId)
        streams[streamId] = stream
        webSocket?.send(buf.array().toByteString())
        return streamId
    }

    fun sendData(streamId: Int, data: ByteArray) {
        val chunks = data.toList().chunked(MAX_FRAME_SIZE)
        for (chunk in chunks) {
            val chunkArr = chunk.toByteArray()
            val buf = ByteBuffer.allocate(FRAME_HEADER_SIZE + chunkArr.size)
                .order(ByteOrder.BIG_ENDIAN)
            buf.put(TYPE_DATA)
            buf.putInt(streamId)
            buf.putInt(seqCounter.getAndIncrement())
            buf.putShort(chunkArr.size.toShort())
            buf.put(chunkArr)
            webSocket?.send(buf.array().toByteString())
            bytesOut.addAndGet(chunkArr.size.toLong())
        }
    }

    fun sendFin(streamId: Int) {
        val buf = ByteBuffer.allocate(FRAME_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(TYPE_FIN)
        buf.putInt(streamId)
        buf.putInt(seqCounter.getAndIncrement())
        buf.putShort(0)
        webSocket?.send(buf.array().toByteString())
        streams.remove(streamId)
    }

    fun getStream(streamId: Int): StreamContext? = streams[streamId]
}

class StreamContext(val streamId: Int) {
    val incomingQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    val openAck = CompletableDeferred<Unit>()
    var closed = false
    var socket: Socket? = null
}
