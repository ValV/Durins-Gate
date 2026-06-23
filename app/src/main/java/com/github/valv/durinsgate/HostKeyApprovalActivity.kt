package com.github.valv.durinsgate

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HostKeyApprovalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host_key_approval)

        val hostname = intent.getStringExtra("hostname") ?: ""
        val configId = intent.getStringExtra(SshForegroundService.EXTRA_CONFIG_ID)
        // Fix 3 & 8: Access the exception from the Pair
        val pair = HostKeyVerifierManager.pendingVerifications[hostname]
        val exception = pair?.first

        if (exception == null) {
            finish()
            return
        }

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        val tvMessage = findViewById<TextView>(R.id.tv_message)
        val tvHostInfo = findViewById<TextView>(R.id.tv_host_info)
        val tvFingerprint = findViewById<TextView>(R.id.tv_fingerprint)
        val btnApprove = findViewById<Button>(R.id.btn_approve)
        val btnCancel = findViewById<Button>(R.id.btn_cancel)

        if (exception.isMismatch) {
            tvTitle.text = "WARNING: HOST KEY MISMATCH"
            tvTitle.setTextColor(getColor(android.R.color.holo_red_dark))
            tvMessage.text = "The host key has changed! This could be a man-in-the-middle attack."
        } else {
            tvTitle.text = "New Host Key"
            tvMessage.text =
                "The authenticity of the host cannot be established. Do you want to trust this key?"
        }

        tvHostInfo.text = "${exception.hostname}:${exception.port}"
        tvFingerprint.text = HostKeyVerifierManager.getFingerprint(exception.publicKey)

        btnApprove.setOnClickListener {
            val manager = HostKeyVerifierManager(this)
            manager.addHostKey(exception.hostname, exception.port, exception.publicKey)

            HostKeyVerifierManager.pendingVerifications.remove(hostname)

            // Dismiss notification
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(hostname.hashCode())

            // Signal service to retry
            if (configId != null) {
                val retryIntent = Intent(this, SshForegroundService::class.java).apply {
                    action = SshForegroundService.ACTION_RETRY_CONFIG
                    putExtra(SshForegroundService.EXTRA_CONFIG_ID, configId)
                }
                startService(retryIntent)
            }

            finish()
        }

        btnCancel.setOnClickListener {
            HostKeyVerifierManager.pendingVerifications.remove(hostname)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(hostname.hashCode())
            finish()
        }
    }
}
