package com.yacbridge.vpn.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yacbridge.vpn.R
import com.yacbridge.vpn.databinding.ActivityMainBinding
import com.yacbridge.vpn.model.TunnelState
import com.yacbridge.vpn.service.BridgeVpnService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupUI()
        observeStatus()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            val status = BridgeVpnService.status.value
            if (status?.state == TunnelState.CONNECTED || status?.state == TunnelState.CONNECTING) {
                stopVpnService()
            } else {
                checkAndStartVpn()
            }
        }
    }

    private fun observeStatus() {
        BridgeVpnService.status.observe(this) { status ->
            updateUI(status.state, status.message, status.bytesIn, status.bytesOut, status.connectedSince)
        }
    }

    private fun updateUI(
        state: TunnelState,
        message: String,
        bytesIn: Long,
        bytesOut: Long,
        connectedSince: Long
    ) {
        binding.tvStatus.text = when (state) {
            TunnelState.CONNECTED -> "ПОДКЛЮЧЕНО"
            TunnelState.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
            TunnelState.RECONNECTING -> "ПЕРЕПОДКЛЮЧЕНИЕ..."
            TunnelState.DISCONNECTED -> "ОТКЛЮЧЕНО"
            TunnelState.ERROR -> "ОШИБКА"
        }

        binding.tvMessage.text = message

        binding.tvBytesIn.text = formatBytes(bytesIn)
        binding.tvBytesOut.text = formatBytes(bytesOut)

        if (connectedSince > 0) {
            val elapsed = System.currentTimeMillis() - connectedSince
            binding.tvUptime.text = formatUptime(elapsed)
        } else {
            binding.tvUptime.text = "00:00:00"
        }

        when (state) {
            TunnelState.CONNECTED -> {
                binding.btnConnect.text = "ОТКЛЮЧИТЬ"
                binding.btnConnect.setBackgroundColor(getColor(R.color.error_red))
                binding.statusIndicator.setBackgroundResource(R.drawable.indicator_connected)
                binding.statsCard.alpha = 1f
            }
            TunnelState.CONNECTING, TunnelState.RECONNECTING -> {
                binding.btnConnect.text = "ОТКЛЮЧИТЬ"
                binding.btnConnect.setBackgroundColor(getColor(R.color.warning_amber))
                binding.statusIndicator.setBackgroundResource(R.drawable.indicator_connecting)
                binding.statsCard.alpha = 0.5f
            }
            TunnelState.DISCONNECTED, TunnelState.ERROR -> {
                binding.btnConnect.text = "ПОДКЛЮЧИТЬ"
                binding.btnConnect.setBackgroundColor(getColor(R.color.accent_cyan))
                binding.statusIndicator.setBackgroundResource(R.drawable.indicator_disconnected)
                binding.statsCard.alpha = 0.3f
            }
        }
    }

    private fun checkAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, BridgeVpnService::class.java).apply {
            action = BridgeVpnService.ACTION_CONNECT
        }
        startForegroundService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, BridgeVpnService::class.java).apply {
            action = BridgeVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun formatUptime(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}
