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
        fun setupBouncyCastle() {
            val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (provider == null) {
                Security.addProvider(BouncyCastleProvider())
            } else if (provider !is BouncyCastleProvider) {
                // If the system provider has the same name, we need to insert ours at the top
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }

    private val keysDir = File(context.filesDir, "ssh_keys").apply {
        if (!exists()) mkdirs()
    }

    fun generateRSAKey(alias: String, bits: Int = 4096): String {
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

    fun generateEd25519Key(alias: String): String {
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

        // Save Private Key in PEM format
        val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(keyPair.private)
        val sw = StringWriter()
        val pemWriter = JcaPEMWriter(sw)
        pemWriter.writeObject(privateKeyInfo)
        pemWriter.close()
        privateKeyFile.writeText(sw.toString())

        // Save Public Key in OpenSSH format
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

    fun listKeys(): List<String> =
        keysDir.listFiles { _, name -> !name.endsWith(".pub") }?.map { it.name } ?: emptyList()

    fun getPrivateKeyPath(alias: String): String = File(keysDir, alias).absolutePath
    fun getPublicKey(alias: String): String =
        File(keysDir, "$alias.pub").let { if (it.exists()) it.readText() else "" }

    fun deleteKey(alias: String) {
        File(keysDir, alias).delete(); File(keysDir, "$alias.pub").delete()
    }
}
