package com.vyrox.runtime.core.capsule

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsulePolicyTest {
    @Test
    fun trimsAndNormalizesWhitespace() {
        assertEquals("My Capsule", CapsulePolicy.normalizeName("  My   Capsule "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNamesThatAreTooShort() {
        CapsulePolicy.normalizeName("A")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathSeparators() {
        CapsulePolicy.normalizeName("../Capsule")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNamesThatAreTooLong() {
        CapsulePolicy.normalizeName("x".repeat(CapsulePolicy.MAX_NAME_LENGTH + 1))
    }
}
