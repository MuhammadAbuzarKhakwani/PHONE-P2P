package com.jingcjie.wifi_direct_cable.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityCheckerTest {

    /** A modern phone with everything granted and a working SIM. */
    private fun idealDevice() = CompatibilityEnvironment(
        apiLevel = 34,
        minApiLevel = 33,
        supportedAbis = listOf("arm64-v8a"),
        bundledAbis = listOf("arm64-v8a", "x86_64"),
        hasWifiHardware = true,
        hasWifiDirect = true,
        wifiEnabled = true,
        nearbyWifiDevicesGranted = true,
        hasTelephony = true,
        simUsable = true,
        opusAvailable = true,
        recordAudioGranted = true,
        ignoringBatteryOptimizations = true
    )

    private fun evaluate(environment: CompatibilityEnvironment) =
        CompatibilityChecker.evaluate(environment)

    private fun levelOf(environment: CompatibilityEnvironment, id: String) =
        evaluate(environment).finding(id)?.level

    private fun reasonOf(environment: CompatibilityEnvironment, id: String) =
        evaluate(environment).finding(id)?.reason

    // ---------------------------------------------------------------- overall --

    @Test
    fun `an ideal device is fully supported`() {
        assertEquals(SupportLevel.SUPPORTED, evaluate(idealDevice()).overall)
    }

    /**
     * The most important property of the whole checker.
     *
     * Cellular call audio is permanently unavailable to a normal app, and is
     * reported as such — but it must not make the device read as UNSUPPORTED.
     * That would tell the user the app will not work here, which is false: every
     * other feature works fine.
     */
    @Test
    fun `permanently unavailable call audio does not drag the overall verdict down`() {
        val report = evaluate(idealDevice())

        assertEquals(SupportLevel.SUPPORTED, report.overall)
        val finding = report.finding(CompatibilityChecker.ID_CELLULAR_CALL_AUDIO)!!
        assertEquals(SupportLevel.UNSUPPORTED, finding.level)
        assertFalse("call audio must not be a required capability", finding.required)
        assertEquals(CompatibilityChecker.REASON_PRIVILEGED, finding.reason)
    }

    @Test
    fun `call audio is reported unsupported on every device`() {
        // No environment, however capable, can make this supported.
        for (environment in listOf(
            idealDevice(),
            idealDevice().copy(hasTelephony = false),
            idealDevice().copy(simUsable = false, opusAvailable = false)
        )) {
            assertEquals(
                SupportLevel.UNSUPPORTED,
                levelOf(environment, CompatibilityChecker.ID_CELLULAR_CALL_AUDIO)
            )
        }
    }

    // ------------------------------------------------------------- hard stops --

    @Test
    fun `an old Android version is unsupported`() {
        val old = idealDevice().copy(apiLevel = 30)
        assertEquals(SupportLevel.UNSUPPORTED, evaluate(old).overall)
        assertEquals(
            CompatibilityChecker.REASON_BELOW_MIN_API,
            reasonOf(old, CompatibilityChecker.ID_ANDROID_VERSION)
        )
    }

    @Test
    fun `the minimum version itself is supported`() {
        assertEquals(SupportLevel.SUPPORTED, evaluate(idealDevice().copy(apiLevel = 33)).overall)
    }

    @Test
    fun `a device without Wi-Fi Direct is unsupported`() {
        val noP2p = idealDevice().copy(hasWifiDirect = false)
        assertEquals(SupportLevel.UNSUPPORTED, evaluate(noP2p).overall)
        assertEquals(
            CompatibilityChecker.REASON_NOT_SUPPORTED,
            reasonOf(noP2p, CompatibilityChecker.ID_WIFI_DIRECT)
        )
    }

    @Test
    fun `a device with no Wi-Fi hardware is unsupported`() {
        assertEquals(
            SupportLevel.UNSUPPORTED,
            evaluate(idealDevice().copy(hasWifiHardware = false)).overall
        )
    }

    @Test
    fun `a 32-bit only device is unsupported because no native library is bundled`() {
        val armv7 = idealDevice().copy(supportedAbis = listOf("armeabi-v7a"))
        assertEquals(SupportLevel.UNSUPPORTED, evaluate(armv7).overall)
        assertEquals(
            CompatibilityChecker.REASON_NO_ABI,
            reasonOf(armv7, CompatibilityChecker.ID_ABI)
        )
    }

    @Test
    fun `a device listing several ABIs passes if any is bundled`() {
        val multi = idealDevice().copy(supportedAbis = listOf("armeabi-v7a", "arm64-v8a"))
        assertEquals(SupportLevel.SUPPORTED, levelOf(multi, CompatibilityChecker.ID_ABI))
    }

    // ------------------------------------------------------- user-fixable gaps --

    @Test
    fun `Wi-Fi being off is partial, not unsupported`() {
        // The device is capable; it is simply not ready. Saying UNSUPPORTED would
        // tell the user to give up on hardware that works.
        val wifiOff = idealDevice().copy(wifiEnabled = false)
        assertEquals(SupportLevel.PARTIALLY_SUPPORTED, evaluate(wifiOff).overall)
        assertEquals(
            CompatibilityChecker.REASON_DISABLED,
            reasonOf(wifiOff, CompatibilityChecker.ID_WIFI_ENABLED)
        )
    }

    @Test
    fun `a missing nearby-devices permission is partial`() {
        val noPermission = idealDevice().copy(nearbyWifiDevicesGranted = false)
        assertEquals(SupportLevel.PARTIALLY_SUPPORTED, evaluate(noPermission).overall)
        assertEquals(
            CompatibilityChecker.REASON_PERMISSION,
            reasonOf(noPermission, CompatibilityChecker.ID_NEARBY_PERMISSION)
        )
    }

    @Test
    fun `battery optimisation is flagged but does not affect the overall verdict`() {
        val optimised = idealDevice().copy(ignoringBatteryOptimizations = false)
        assertEquals(SupportLevel.SUPPORTED, evaluate(optimised).overall)
        assertEquals(
            SupportLevel.PARTIALLY_SUPPORTED,
            levelOf(optimised, CompatibilityChecker.ID_BACKGROUND)
        )
    }

    // ------------------------------------------------------- optional features --

    @Test
    fun `a client phone with no SIM is still fully supported overall`() {
        // Phone 2's normal state. It cannot be a gateway, but the app works.
        val clientPhone = idealDevice().copy(simUsable = false)

        assertEquals(SupportLevel.SUPPORTED, evaluate(clientPhone).overall)
        assertEquals(
            SupportLevel.PARTIALLY_SUPPORTED,
            levelOf(clientPhone, CompatibilityChecker.ID_GATEWAY)
        )
        assertEquals(
            CompatibilityChecker.REASON_NO_SIM,
            reasonOf(clientPhone, CompatibilityChecker.ID_GATEWAY)
        )
    }

    @Test
    fun `a tablet with no cellular hardware cannot be a gateway`() {
        val tablet = idealDevice().copy(hasTelephony = false, simUsable = false)

        assertEquals(SupportLevel.SUPPORTED, evaluate(tablet).overall)
        assertEquals(SupportLevel.UNSUPPORTED, levelOf(tablet, CompatibilityChecker.ID_GATEWAY))
        assertEquals(
            CompatibilityChecker.REASON_NO_TELEPHONY,
            reasonOf(tablet, CompatibilityChecker.ID_GATEWAY)
        )
    }

    @Test
    fun `audio needs both the codec and the microphone permission`() {
        assertEquals(
            SupportLevel.PARTIALLY_SUPPORTED,
            levelOf(idealDevice().copy(recordAudioGranted = false), CompatibilityChecker.ID_AUDIO_LINK)
        )
        assertEquals(
            SupportLevel.UNSUPPORTED,
            levelOf(idealDevice().copy(opusAvailable = false), CompatibilityChecker.ID_AUDIO_LINK)
        )
        assertEquals(
            CompatibilityChecker.REASON_NO_ABI,
            reasonOf(idealDevice().copy(opusAvailable = false), CompatibilityChecker.ID_AUDIO_LINK)
        )
    }

    // -------------------------------------------------------------- reporting --

    @Test
    fun `a supported finding carries no reason`() {
        assertNull(evaluate(idealDevice()).finding(CompatibilityChecker.ID_WIFI_DIRECT)?.reason)
    }

    @Test
    fun `every reason has a plain-language explanation`() {
        val reasons = listOf(
            CompatibilityChecker.REASON_BELOW_MIN_API,
            CompatibilityChecker.REASON_NO_HARDWARE,
            CompatibilityChecker.REASON_NOT_SUPPORTED,
            CompatibilityChecker.REASON_DISABLED,
            CompatibilityChecker.REASON_PERMISSION,
            CompatibilityChecker.REASON_NO_ABI,
            CompatibilityChecker.REASON_NO_TELEPHONY,
            CompatibilityChecker.REASON_NO_SIM,
            CompatibilityChecker.REASON_BATTERY_OPTIMIZED,
            CompatibilityChecker.REASON_PRIVILEGED
        )
        for (reason in reasons) {
            val explanation = CompatibilityChecker.explain(reason)
            assertTrue("no explanation for $reason", explanation.isNotBlank())
            assertFalse(
                "explanation for $reason fell through to the default",
                explanation == "Unavailable on this device."
            )
        }
    }

    @Test
    fun `the report serialises for the diagnostics page`() {
        val json = evaluate(idealDevice()).toJson()
        assertEquals("SUPPORTED", json.getString("overall"))
        assertTrue(json.getJSONArray("findings").length() >= 10)
        assertNotNull(json.getJSONArray("findings").getJSONObject(0).getString("id"))
    }

    @Test
    fun `the worst required finding decides the overall verdict`() {
        // One unsupported requirement outranks any number of partial ones.
        val broken = idealDevice().copy(wifiEnabled = false, hasWifiDirect = false)
        assertEquals(SupportLevel.UNSUPPORTED, evaluate(broken).overall)
    }
}
