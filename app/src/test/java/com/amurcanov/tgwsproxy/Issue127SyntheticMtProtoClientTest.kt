package com.amurcanov.tgwsproxy

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the debug-only synthetic ingress really authenticates as DC2 obfuscated2. */
class Issue127SyntheticMtProtoClientTest {
    @Test
    fun generatedIngressDecryptsAsIntermediateDc2UsingConfiguredSecret() {
        val secretHex = "0123456789abcdeffedcba9876543210"
        val header = Issue127SyntheticMtProtoClient.buildInit(secretHex)
        assertEquals(64, header.size)
        assertEquals(0x42, header[0].toInt() and 0xff)

        val secret = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = MessageDigest.getInstance("SHA-256")
            .digest(header.copyOfRange(8, 40) + secret)
        val decipher = Cipher.getInstance("AES/CTR/NoPadding")
        decipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(header.copyOfRange(40, 56)),
        )
        val decoded = decipher.doFinal(header)
        for (index in 56..59) {
            assertEquals(0xee, decoded[index].toInt() and 0xff)
        }
        assertEquals(2, decoded[60].toInt() and 0xff)
        assertEquals(0, decoded[61].toInt() and 0xff)
    }

    @Test
    fun invalidSecretIsRejectedInsteadOfTriggeringUnauthenticatedIngress() {
        val rejected = runCatching {
            Issue127SyntheticMtProtoClient.buildInit("invalid-secret")
        }.isFailure
        assertTrue(rejected)
    }
}
