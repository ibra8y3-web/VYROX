package com.vyrox.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class VncRfbClientProtocolTest {
    @Test
    fun rfb38ClientInitSendsSharedFlagBeforeServerInitIsRead() {
        val bytes = ByteArrayOutputStream()
        writeRfbClientInit(DataOutputStream(bytes))
        assertArrayEquals(byteArrayOf(1), bytes.toByteArray())
    }
}
