package com.yacbridge.vpn.model

data class TunnelConfig(
    val gatewayUrl: String = "wss://d5d39r7l30a40c2n6joc.uvah0e6r.apigw.yandexcloud.net/_helper",
    val authToken: String = "3f4hjfjn",
    val pingIntervalMs: Long = 300000L,
    val reconnectDelayMs: Long = 3000L,
    val listenPort: Int = 1080,
    val useRelay: Boolean = false
)

enum class TunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

data class TunnelStatus(
    val state: TunnelState = TunnelState.DISCONNECTED,
    val message: String = "",
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val connectedSince: Long = 0L
)
