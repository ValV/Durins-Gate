package com.github.valv.durinsgate

import android.content.Context
import android.util.Base64
import android.util.Log
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.File
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

class HostKeyVerificationException(
    val hostname: String,
    val port: Int,
    val publicKey: PublicKey,
    val isMismatch: Boolean
) : Exception("Host key verification failed for $hostname")

data class KnownHostEntry(
    val raw: String,
    val host: String,
    val type: String,
    val fingerprint: String
)

class HostKeyVerifierManager(private val context: Context) {
    private val knownHostsFile = File(context.filesDir, "known_hosts")

    fun getVerifier(): HostKeyVerifier {
        if (!knownHostsFile.exists()) {
            knownHostsFile.createNewFile()
        }
        val delegate = OpenSSHKnownHosts(knownHostsFile)

        return object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val result = delegate.verify(hostname, port, key)
                if (!result) {
                    val isMismatch = isHostKnown(hostname, port)
                    throw HostKeyVerificationException(hostname, port, key, isMismatch)
                }
                return true
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> {
                return delegate.findExistingAlgorithms(hostname, port)
            }
        }
    }

    fun addHostKey(hostname: String, port: Int, publicKey: PublicKey) {
        try {
            if (!knownHostsFile.exists()) {
                knownHostsFile.createNewFile()
            }

            val kt = KeyType.fromKey(publicKey)
            val typeName = when (kt) {
                KeyType.RSA -> "ssh-rsa"
                KeyType.DSA -> "ssh-dss"
                KeyType.ECDSA256 -> "ecdsa-sha2-nistp256"
                KeyType.ECDSA384 -> "ecdsa-sha2-nistp384"
                KeyType.ECDSA521 -> "ecdsa-sha2-nistp521"
                KeyType.ED25519 -> "ssh-ed25519"
                else -> kt.toString().lowercase()
            }

            val buffer = Buffer.PlainBuffer().putPublicKey(publicKey)
            val keyBlob = Base64.encodeToString(
                buffer.array(),
                buffer.rpos(),
                buffer.available(),
                Base64.NO_WRAP
            )

            val entry = "[$hostname]:$port $typeName $keyBlob"

            synchronized(this) {
                val lines = if (knownHostsFile.exists()) knownHostsFile.readLines() else emptyList()
                // Fix 2: Exact matching for replacement
                val filteredLines = lines.filter { line ->
                    val parts = line.split(" ")
                    parts.isEmpty() || parts[0] != "[$hostname]:$port"
                }
                val newContent = filteredLines.toMutableList()
                newContent.add(entry)
                knownHostsFile.writeText(newContent.joinToString("\n") + "\n")
            }

            Log.i("HostKeyVerifier", "Host key added/updated for $hostname:$port")
        } catch (e: Exception) {
            Log.e("HostKeyVerifier", "Failed to add host key", e)
        }
    }

    fun isHostKnown(hostname: String, port: Int): Boolean {
        return try {
            if (!knownHostsFile.exists()) return false
            // Fix 2: Exact matching of [hostname]:port
            val target = "[$hostname]:$port"
            knownHostsFile.readLines().any { line ->
                val parts = line.split(" ")
                parts.isNotEmpty() && parts[0] == target
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getKnownHosts(): List<KnownHostEntry> {
        if (!knownHostsFile.exists()) return emptyList()
        return knownHostsFile.readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split(" ")
            val host = parts.getOrNull(0) ?: "Unknown"
            val type = parts.getOrNull(1) ?: ""
            KnownHostEntry(line, host, type, "")
        }
    }

    fun removeHostKey(rawLine: String) {
        synchronized(this) {
            if (!knownHostsFile.exists()) return
            val lines = knownHostsFile.readLines()
            val filteredLines = lines.filter { it != rawLine }
            knownHostsFile.writeText(filteredLines.joinToString("\n") + if (filteredLines.isNotEmpty()) "\n" else "")
        }
    }

    companion object {
        // Fix 3 & 8: Tracking timestamp for pending verifications
        val pendingVerifications = ConcurrentHashMap<String, Pair<HostKeyVerificationException, Long>>()

        fun getFingerprint(key: PublicKey): String {
            return net.schmizz.sshj.common.SecurityUtils.getFingerprint(key)
        }
    }
}
