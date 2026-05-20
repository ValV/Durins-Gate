package com.github.valv.durinsgate

import android.content.Context
import android.util.Log
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.File

class HostKeyVerifierManager(private val context: Context) {
    private val knownHostsFile = File(context.filesDir, "known_hosts")

    fun getVerifier(): OpenSSHKnownHosts {
        // Create known_hosts file if it doesn't exist
        if (!knownHostsFile.exists()) {
            knownHostsFile.createNewFile()
        }
        return OpenSSHKnownHosts(knownHostsFile)
    }

    fun addHostKey(hostname: String, port: Int, publicKey: String) {
        try {
            if (!knownHostsFile.exists()) {
                knownHostsFile.createNewFile()
            }
            val entry = "[$hostname]:$port $publicKey"
            knownHostsFile.appendText("$entry\n")
            Log.i("HostKeyVerifier", "Host key added for $hostname:$port")
        } catch (e: Exception) {
            Log.e("HostKeyVerifier", "Failed to add host key", e)
        }
    }

    fun isHostKnown(hostname: String): Boolean {
        return try {
            val verifier = getVerifier()
            // Check if any entry exists for this host
            knownHostsFile.readLines().any { it.contains(hostname) }
        } catch (e: Exception) {
            false
        }
    }
}
