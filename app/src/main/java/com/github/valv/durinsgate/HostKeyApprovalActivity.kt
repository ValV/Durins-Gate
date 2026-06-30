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

        val lookupKey = intent.getStringExtra("key") ?: ""
        val configId = intent.getStringExtra(SshForegroundService.EXTRA_CONFIG_ID)

        val pair = HostKeyVerifierManager.pendingVerifications[lookupKey]
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
            tvTitle.text = getString(R.string.host_key_approval_warning_mismatch)
            tvTitle.setTextColor(getColor(android.R.color.holo_red_dark))
            tvMessage.text = getString(R.string.host_key_approval_mismatch_message)
        } else {
            tvTitle.text = getString(R.string.host_key_approval_new_title)
            tvMessage.text =
                getString(R.string.host_key_approval_new_message)
        }

        tvHostInfo.text = "${exception.hostname}:${exception.port}"
        tvFingerprint.text = HostKeyVerifierManager.getFingerprint(exception.publicKey)

        btnApprove.setOnClickListener {
            val manager = HostKeyVerifierManager(this)
            manager.addHostKey(exception.hostname, exception.port, exception.publicKey)

            HostKeyVerifierManager.pendingVerifications.remove(lookupKey)

            // Dismiss notification
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(exception.hostname.hashCode())

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
            HostKeyVerifierManager.pendingVerifications.remove(lookupKey)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(exception.hostname.hashCode())
            finish()
        }
    }
}
