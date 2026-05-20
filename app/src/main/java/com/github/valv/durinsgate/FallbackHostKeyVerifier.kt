package com.github.valv.durinsgate

import android.content.Context
import android.util.Log
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.File
import java.security.PublicKey

class FallbackHostKeyVerifier(private val context: Context) : HostKeyVerifier {
    private val knownHosts = OpenSSHKnownHosts(File(context.filesDir, "known_hosts").apply {
        if (!exists()) createNewFile()
    })

    override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
        return try {
            val result = knownHosts.verify(hostname, port, key)
            if (!result) {
                // Host key not in known_hosts - log for manual verification
                Log.w("SSH", "Unknown host key for $hostname:$port")
                // In production, you'd notify the user and ask for confirmation
            }
            result
        } catch (e: Exception) {
            Log.e("SSH", "Host key verification error", e)
            false
        }
    }

    override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> {
        return knownHosts.findExistingAlgorithms(hostname, port)
    }
}
