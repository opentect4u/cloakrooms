package com.synergic.cloakroom.domain.validation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GstinTest {

    @Test
    fun `a GSTIN in the right format is accepted`() {
        assertTrue(Gstin.isValid("19ABCDE1234F1Z5"))
        assertTrue(Gstin.isValid("27AAPFU0939F1ZV"))
    }

    @Test
    fun `15 characters in the wrong format are refused`() {
        assertFalse(Gstin.isValid("1234567890RTYUY")) // digits where the 5 letters belong, no Z
        assertFalse(Gstin.isValid("19ABCDE1234F1X5")) // 14th character must be Z
        assertFalse(Gstin.isValid("19ABCDE1234F0Z5")) // 13th character cannot be 0
    }

    @Test
    fun `wrong length or small letters are refused`() {
        assertFalse(Gstin.isValid("19ABCDE1234"))
        assertFalse(Gstin.isValid("19abcde1234f1z5"))
        assertFalse(Gstin.isValid(""))
    }
}
