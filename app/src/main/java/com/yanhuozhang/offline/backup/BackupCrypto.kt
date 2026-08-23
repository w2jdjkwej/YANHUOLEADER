package com.yanhuozhang.offline.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupCrypto {
    private val magic = byteArrayOf('Y'.code.toByte(), 'H'.code.toByte(), 'Z'.code.toByte(), 'B'.code.toByte())
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val KEY_SIZE_BITS = 256
    private const val GCM_TAG_SIZE_BITS = 128
    private const val PBKDF2_ITERATIONS = 210_000
    private const val HEADER_SIZE = 4 + 1 + SALT_SIZE + IV_SIZE

    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        secureRandom: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(password.isNotEmpty()) { "备份密码不能为空" }
        val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_SIZE).also(secureRandom::nextBytes)

        return try {
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)

            ByteBuffer.allocate(HEADER_SIZE + ciphertext.size)
                .put(magic)
                .put(FORMAT_VERSION)
                .put(salt)
                .put(iv)
                .put(ciphertext)
                .array()
        } catch (error: GeneralSecurityException) {
            throw BackupException("无法加密备份", error)
        }
    }

    fun decrypt(encrypted: ByteArray, password: CharArray): ByteArray {
        if (encrypted.size <= HEADER_SIZE || password.isEmpty()) {
            throw BackupException("备份文件无效")
        }

        return try {
            val buffer = ByteBuffer.wrap(encrypted)
            val actualMagic = ByteArray(magic.size).also(buffer::get)
            if (!actualMagic.contentEquals(magic) || buffer.get() != FORMAT_VERSION) {
                throw BackupException("不支持的备份格式")
            }
            val salt = ByteArray(SALT_SIZE).also(buffer::get)
            val iv = ByteArray(IV_SIZE).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)

            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (error: BackupException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw BackupException("密码错误或备份文件已损坏", error)
        } catch (error: RuntimeException) {
            throw BackupException("备份文件无效", error)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
