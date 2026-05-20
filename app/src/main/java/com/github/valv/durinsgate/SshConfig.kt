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
    var isEnabled: Boolean = false
) : Serializable
