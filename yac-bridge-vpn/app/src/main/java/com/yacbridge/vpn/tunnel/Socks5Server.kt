package com.yacbridge.vpn.tunnel

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * SOCKS5 proxy server that tunnels connections via YAC Bridge
 */
class Socks5Server(
    private val port: Int,
    private val tunnel: YacBridgeTunnel
) {
    companion object {
        private const val TAG = "Socks5Server"
        private const val SOCKS_VERSION: Byte = 0x05
        private const val AUTH_NONE: Byte = 0x00
        private const val CMD_CONNECT: Byte = 0x01
        private const val ATYP_IPV4: Byte = 0x01
        private const val ATYP_DOMAIN: Byte = 0x03
        private const val ATYP_IPV6: Byte = 0x04
        private const val REP_SUCCESS: Byte = 0x00
        private const val REP_FAILURE: Byte = 0x01
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        Log.i(TAG, "SOCKS5 server started on port $port")

        scope.launch {
            while (isActive) {
                try {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Accept error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        serverSocket?.close()
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // Step 1: Auth negotiation
            val ver = input.read()
            if (ver != SOCKS_VERSION.toInt()) {
                client.close()
                return@withContext
            }
            val nMethods = input.read()
            val methods = ByteArray(nMethods)
            input.read(methods)

            // We accept NO AUTH only
            output.write(byteArrayOf(SOCKS_VERSION, AUTH_NONE))

            // Step 2: Request
            val buf = ByteArray(4)
            input.read(buf)
            if (buf[0] != SOCKS_VERSION || buf[1] != CMD_CONNECT) {
                sendReply(output, REP_FAILURE)
                client.close()
                return@withContext
            }

            val host = when (buf[3]) {
                ATYP_IPV4 -> {
                    val addr = ByteArray(4)
                    input.read(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                ATYP_DOMAIN -> {
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.read(domain)
                    String(domain)
                }
                ATYP_IPV6 -> {
                    val addr = ByteArray(16)
                    input.read(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: ""
                }
                else -> {
                    sendReply(output, REP_FAILURE)
                    client.close()
                    return@withContext
                }
            }

            val portHigh = input.read()
            val portLow = input.read()
            val port = (portHigh shl 8) or portLow

            Log.d(TAG, "CONNECT $host:$port")

            // Open stream via tunnel
            if (!tunnel.connected.value) {
                sendReply(output, REP_FAILURE)
                client.close()
                return@withContext
            }

            val streamId = tunnel.openStream(host, port)
            val stream = tunnel.getStream(streamId)

            if (stream == null) {
                sendReply(output, REP_FAILURE)
                client.close()
                return@withContext
            }

            // Wait for OPEN_OK (max 10s)
            try {
                withTimeout(10000) { stream.openAck.await() }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Stream open timeout for $host:$port")
                sendReply(output, REP_FAILURE)
                client.close()
                return@withContext
            }

            stream.socket = client
            sendReply(output, REP_SUCCESS)

            // Bidirectional relay
            val readJob = launch {
                try {
                    val readBuf = ByteArray(8192)
                    while (isActive && !stream.closed) {
                        val n = input.read(readBuf)
                        if (n == -1) break
                        tunnel.sendData(streamId, readBuf.copyOf(n))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Read from client done: ${e.message}")
                } finally {
                    tunnel.sendFin(streamId)
                }
            }

            val writeJob = launch {
                try {
                    while (isActive && !stream.closed) {
                        val data = stream.incomingQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (data != null) {
                            output.write(data)
                            output.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Write to client done: ${e.message}")
                }
            }

            readJob.join()
            writeJob.cancelAndJoin()

        } catch (e: Exception) {
            Log.e(TAG, "Client handler error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun sendReply(output: java.io.OutputStream, rep: Byte) {
        output.write(byteArrayOf(
            SOCKS_VERSION, rep, 0x00, ATYP_IPV4,
            0, 0, 0, 0, // bound addr
            0, 0 // bound port
        ))
        output.flush()
    }
}
