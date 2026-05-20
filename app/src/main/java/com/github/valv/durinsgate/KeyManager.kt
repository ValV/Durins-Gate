package com.github.valv.durinsgate

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.SecureRandom
import java.security.Security

class KeyManager(private val context: Context) {

    init {
        setupBouncyCastle()
    }

    companion object {
        private val lock = Any()

        fun setupBouncyCastle() {
            synchronized(lock) {
                val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
                if (provider == null) {
                    Security.addProvider(BouncyCastleProvider())
                } else if (provider !is BouncyCastleProvider) {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Security.insertProviderAt(BouncyCastleProvider(), 1)
                }
            }
        }
    }

    private val keysDir = File(context.filesDir, "ssh_keys").apply {
        if (!exists()) mkdirs()
    }

    fun generateRSAKey(alias: String, bits: Int = 4096): String = synchronized(lock) {
        return try {
            val generator = RSAKeyPairGenerator()
            generator.init(
                RSAKeyGenerationParameters(
                    BigInteger.valueOf(65537),
                    SecureRandom(),
                    bits,
                    80
                )
            )
            val keyPair = generator.generateKeyPair()
            saveKeyPair(alias, keyPair, "ssh-rsa")
            File(keysDir, alias).absolutePath
        } catch (e: Exception) {
            Log.e("KeyManager", "RSA Generation failed", e)
            throw e
        }
    }

    fun generateEd25519Key(alias: String): String = synchronized(lock) {
        return try {
            val generator = Ed25519KeyPairGenerator()
            generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = generator.generateKeyPair()
            saveKeyPair(alias, keyPair, "ssh-ed25519")
            File(keysDir, alias).absolutePath
        } catch (e: Exception) {
            Log.e("KeyManager", "Ed25519 Generation failed", e)
            throw e
        }
    }

    private fun saveKeyPair(alias: String, keyPair: AsymmetricCipherKeyPair, type: String) {
        val privateKeyFile = File(keysDir, alias)
        val publicKeyFile = File(keysDir, "$alias.pub")

        val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(keyPair.private)
        val sw = StringWriter()
        val pemWriter = JcaPEMWriter(sw)
        pemWriter.writeObject(privateKeyInfo)
        pemWriter.close()
        privateKeyFile.writeText(sw.toString())

        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        when (type) {
            "ssh-ed25519" -> {
                val pubKey = keyPair.public as Ed25519PublicKeyParameters
                writeString(dos, "ssh-ed25519")
                writeString(dos, pubKey.encoded)
            }

            "ssh-rsa" -> {
                val pubKey = keyPair.public as RSAKeyParameters
                writeString(dos, "ssh-rsa")
                writeBigInt(dos, pubKey.exponent)
                writeBigInt(dos, pubKey.modulus)
            }
        }

        val pubKeyBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        publicKeyFile.writeText("$type $pubKeyBase64 $alias")
    }

    private fun writeString(dos: DataOutputStream, s: String) {
        val bytes = s.toByteArray()
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun writeString(dos: DataOutputStream, bytes: ByteArray) {
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun writeBigInt(dos: DataOutputStream, b: BigInteger) {
        val bytes = b.toByteArray()
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    fun listKeys(): List<String> = synchronized(lock) {
        keysDir.listFiles { _, name -> !name.endsWith(".pub") }?.map { it.name } ?: emptyList()
    }

    fun getPrivateKeyPath(alias: String): String = File(keysDir, alias).absolutePath

    fun getPublicKey(alias: String): String = synchronized(lock) {
        File(keysDir, "$alias.pub").let { if (it.exists()) it.readText() else "" }
    }

    fun deleteKey(alias: String): Boolean = synchronized(lock) {
        return try {
            val privateKeyFile = File(keysDir, alias)
            val publicKeyFile = File(keysDir, "$alias.pub")

            val privateDeleted = if (privateKeyFile.exists()) privateKeyFile.delete() else true
            val publicDeleted = if (publicKeyFile.exists()) publicKeyFile.delete() else true

            if (privateDeleted && publicDeleted) {
                Log.i("KeyManager", "Successfully deleted key pair: $alias")
                true
            } else {
                Log.e(
                    "KeyManager",
                    "Partial deletion for $alias: private=$privateDeleted, public=$publicDeleted"
                )
                false
            }
        } catch (e: Exception) {
            Log.e("KeyManager", "Unexpected error deleting key", e)
            false
        }
    }

    fun isKeyPairComplete(alias: String): Boolean = synchronized(lock) {
        val privateKey = File(keysDir, alias)
        val publicKey = File(keysDir, "$alias.pub")
        return privateKey.exists() && publicKey.exists()
    }

    fun cleanupOrphanedKeys() = synchronized(lock) {
        keysDir.listFiles()?.forEach { file ->
            val baseName = file.name.removeSuffix(".pub")
            if (file.name.endsWith(".pub")) {
                val privateKey = File(keysDir, baseName)
                if (!privateKey.exists()) {
                    Log.w("KeyManager", "Removing orphaned public key: ${file.name}")
                    file.delete()
                }
            } else {
                val publicKey = File(keysDir, "$baseName.pub")
                if (!publicKey.exists()) {
                    Log.w("KeyManager", "Removing orphaned private key: ${file.name}")
                    file.delete()
                }
            }
        }
    }
}
