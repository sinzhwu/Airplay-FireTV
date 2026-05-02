package com.airplay.firetv.airplay

import org.bouncycastle.jce.provider.BouncyCastleProvider
import timber.log.Timber
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AirPlayCrypto {

    private val keyPair: KeyPair
    private val rsaCipher: Cipher
    private val aesCipher: Cipher

    var aesKey: ByteArray? = null
        private set
    var aesIv: ByteArray? = null
        private set

    init {
        val generator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider())
        generator.initialize(1024, SecureRandom())
        keyPair = generator.generateKeyPair()

        rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", BouncyCastleProvider())
        aesCipher = Cipher.getInstance("AES/CTR/NoPadding", BouncyCastleProvider())

        Timber.d("AirPlayCrypto initialized with 1024-bit RSA key pair")
    }

    fun getPublicKeyPem(): String {
        val encoded = keyPair.public.encoded
        val base64 = Base64.getEncoder().encodeToString(encoded)
        val builder = StringBuilder()
        builder.append("-----BEGIN PUBLIC KEY-----\n")
        var i = 0
        while (i < base64.length) {
            val end = minOf(i + 64, base64.length)
            builder.append(base64.substring(i, end)).append('\n')
            i = end
        }
        builder.append("-----END PUBLIC KEY-----")
        return builder.toString()
    }

    fun getPublicKeyBase64(): String {
        return Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    fun decryptAesKey(encryptedKeyBase64: String): Boolean {
        return try {
            val encryptedKey = Base64.getDecoder().decode(encryptedKeyBase64)
            rsaCipher.init(Cipher.DECRYPT_MODE, keyPair.private)
            aesKey = rsaCipher.doFinal(encryptedKey)
            Timber.d("AES key decrypted successfully, length=${aesKey?.size}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt AES key")
            false
        }
    }

    fun setAesIv(ivBase64: String): Boolean {
        return try {
            aesIv = Base64.getDecoder().decode(ivBase64)
            Timber.d("AES IV set, length=${aesIv?.size}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode AES IV")
            false
        }
    }

    fun decrypt(data: ByteArray): ByteArray? {
        val key = aesKey ?: return null
        val iv = aesIv ?: return null
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            aesCipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            aesCipher.doFinal(data)
        } catch (e: Exception) {
            Timber.e(e, "AES decryption failed")
            null
        }
    }

    fun decryptInPlace(data: ByteArray, offset: Int, length: Int): ByteArray? {
        val key = aesKey ?: return null
        val iv = aesIv ?: return null
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            aesCipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            aesCipher.doFinal(data, offset, length)
        } catch (e: Exception) {
            Timber.e(e, "AES in-place decryption failed")
            null
        }
    }

    fun hasKeys(): Boolean = aesKey != null && aesIv != null

    fun clearKeys() {
        aesKey = null
        aesIv = null
    }
}
