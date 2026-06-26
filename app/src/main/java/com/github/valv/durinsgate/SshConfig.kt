package com.github.valv.durinsgate

import java.io.Serializable

data class SshConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var localPort: Int = 1080,
    var isSocks5: Boolean = true,
    var keyAlias: String? = null,
    var keepAliveInterval: Int = 60,
    var socks5BindRetries: Int = 2,
    var socks5BindRetryDelay: Long = 1000,
    var timeoutConnect: Int = 10000,
    var timeoutClient: Int = 15000,
    var connectionCheckInterval: Long = 2000,
    var timeoutWakeLock: Long = 3600 * 1000L,
    var verificationExpiry: Long = 5 * 60 * 1000L, // 5 minutes
    var timeoutVerificationRetry: Long = 5000,
    var backoffMultiplier: Double = 2.0,
    var maxRetryDelay: Long = 10 * 60 * 1000L,
    var isEnabled: Boolean = false
) : Serializable
