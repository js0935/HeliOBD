package com.heli.obd.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun versionParts_stripsPrefix() {
        assertEquals(listOf(0, 2, 0), UpdateChecker.versionParts("v0.2.0"))
    }

    @Test
    fun versionParts_plainNumber() {
        assertEquals(listOf(2), UpdateChecker.versionParts("2"))
    }

    @Test
    fun versionParts_suffixIgnored() {
        assertEquals(listOf(0, 2, 1), UpdateChecker.versionParts("0.2.1-rc1"))
    }

    @Test
    fun versionParts_threePartsOnly() {
        assertEquals(listOf(1, 2, 3), UpdateChecker.versionParts("1.2.3.4"))
    }

    @Test
    fun versionParts_empty() {
        assertEquals(emptyList<Int>(), UpdateChecker.versionParts(""))
    }

    @Test
    fun isNewer_minorBump() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.2.1"))
    }

    @Test
    fun isNewer_patchBump() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.3.0"))
    }

    @Test
    fun isNewer_majorBump() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "1.0.0"))
    }

    @Test
    fun isNewer_sameVersion() {
        assertFalse(UpdateChecker.isNewer("0.2.0", "0.2.0"))
    }

    @Test
    fun isNewer_older() {
        assertFalse(UpdateChecker.isNewer("0.2.1", "0.2.0"))
    }

    @Test
    fun isNewer_shortListPadded() {
        assertTrue(UpdateChecker.isNewer("0.2", "0.2.1"))
        assertFalse(UpdateChecker.isNewer("0.2.1", "0.2"))
    }
}
