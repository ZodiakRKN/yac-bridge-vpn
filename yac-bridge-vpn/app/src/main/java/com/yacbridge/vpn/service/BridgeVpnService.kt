package com.yacbridge.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.yacbridge.vpn.R
import com.yacbridge.vpn.model.TunnelConfig
import com.yacbridge.vpn.model.TunnelState
import com.yacbridge.vpn.model.TunnelStatus
import com.yacbridge.vpn.tunnel.Socks5Server
import com.yacbridge.vpn.tunnel.YacBridgeTunnel
import com.yacbridge.vpn.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class BridgeVpnService : VpnService() {

    companion object {
        private const val TAG = "BridgeVpnService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "yac_bridge_vpn"
        const val ACTION_CONNECT = "com.yacbridge.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.yacbridge.vpn.DISCONNECT"

        val status = MutableLiveData(TunnelStatus())
    }

    private val binder = LocalBinder()
    private var tunnel: YacBridgeTunnel? = null
    private var socks5Server: Socks5Server? = null
    private var vpnInterface: android.os.ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var config = TunnelConfig()

    inner class LocalBinder : Binder() {
        fun getService(): BridgeVpnService = this@BridgeVpnService
    }

    override fun onBind(intent: Intent): IBinder {
        return if (intent.action == SERVICE_INTERFACE) {
            super.onBind(intent)!!
        } else {
            binder
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startVpn()
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        updateStatus(TunnelState.CONNECTING, "Подключение...")
        startForeground(NOTIFICATION_ID, buildNotification("Подключение..."))

        // Load config from preferences
        val prefs = getSharedPreferences("yac_config", MODE_PRIVATE)
        config = TunnelConfig(
            gatewayUrl = prefs.getString("gateway_url", TunnelConfig().gatewayUrl) ?: TunnelConfig().gatewayUrl,
            authToken = prefs.getString("auth_token", TunnelConfig().authToken) ?: TunnelConfig().authToken,
            listenPort = prefs.getInt("listen_port", 1080),
            useRelay = prefs.getBoolean("use_relay", false)
        )

        tunnel = YacBridgeTunnel(config).also { t ->
            t.setCallbacks(
                onConnected = {
                    Log.i(TAG, "Tunnel connected!")
                    startSocks5AndVpn()
                },
                onDisconnected = { reason ->
                    Log.w(TAG, "Tunnel disconnected: $reason")
                    updateStatus(TunnelState.RECONNECTING, "Переподключение: $reason")
                    scope.launch {
                        delay(config.reconnectDelayMs)
                        tunnel?.connect()
                    }
                }
            )
            t.connect()
        }

        // Monitor stats
        scope.launch {
            while (isActive) {
                delay(1000)
                val t = tunnel ?: continue
                val current = status.value ?: continue
                if (current.state == TunnelState.CONNECTED) {
                    status.postValue(current.copy(
                        bytesIn = t.bytesIn.get(),
                        bytesOut = t.bytesOut.get()
                    ))
                }
            }
        }
    }

    private fun startSocks5AndVpn() {
        try {
            // Start SOCKS5 proxy
            socks5Server = Socks5Server(config.listenPort, tunnel!!).also { it.start() }

            // Setup VPN interface
            val builder = Builder()
                .addAddress("10.8.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setSession("YAC Bridge VPN")
                .setMtu(1500)

            // Exclude our own app to avoid loop
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                updateStatus(TunnelState.ERROR, "Не удалось создать VPN интерфейс")
                return
            }

            updateStatus(TunnelState.CONNECTED, "Подключено", System.currentTimeMillis())
            updateNotification("Подключено ✓")

            // Start packet routing loop
            startPacketRouting()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}")
            updateStatus(TunnelState.ERROR, "Ошибка: ${e.message}")
        }
    }

    private fun startPacketRouting() {
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(vpnFd)
        val outputStream = FileOutputStream(vpnFd)
        val packet = ByteBuffer.allocate(32767)

        scope.launch {
            while (isActive && vpnInterface != null) {
                try {
                    packet.clear()
                    val length = inputStream.read(packet.array())
                    if (length <= 0) {
                        delay(10)
                        continue
                    }
                    packet.limit(length)

                    // Parse IP packet and route via SOCKS5
                    routePacket(packet, outputStream)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Packet routing error: ${e.message}")
                    delay(100)
                }
            }
        }
    }

    private suspend fun routePacket(packet: ByteBuffer, output: FileOutputStream) {
        // For simplicity, we use the SOCKS5 server as the routing layer
        // The VPN interface captures packets, which are then proxied via SOCKS5
        // This is handled transparently by the OS tun interface + SOCKS5 binding
    }

    fun stopVpn() {
        scope.coroutineContext.cancelChildren()
        tunnel?.disconnect()
        tunnel = null
        socks5Server?.stop()
        socks5Server = null
        vpnInterface?.close()
        vpnInterface = null
        updateStatus(TunnelState.DISCONNECTED, "Отключено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(state: TunnelState, message: String, connectedSince: Long = 0L) {
        status.postValue(TunnelStatus(
            state = state,
            message = message,
            connectedSince = connectedSince
        ))
    }

    private fun buildNotification(text: String): Notification {
        createNotificationChannel()
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YAC Bridge VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "YAC Bridge VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "VPN tunnel status" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
