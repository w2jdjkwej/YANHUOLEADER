package com.yanhuozhang.offline.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun `encrypted bytes decrypt with same password`() {
        val plaintext = "烟火账备份".toByteArray()
        val encrypted = BackupCrypto.encrypt(plaintext, "correct-password".toCharArray())

        val decrypted = BackupCrypto.decrypt(encrypted, "correct-password".toCharArray())

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `wrong password does not expose data`() {
        val encrypted = BackupCrypto.encrypt("secret".toByteArray(), "correct-password".toCharArray())

        assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(encrypted, "wrong-password".toCharArray())
        }
    }

    @Test
    fun `tampered backup is rejected`() {
        val encrypted = BackupCrypto.encrypt("secret".toByteArray(), "correct-password".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(encrypted, "correct-password".toCharArray())
        }
    }
}
