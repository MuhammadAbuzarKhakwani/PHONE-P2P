package com.jingcjie.wifi_direct_cable.compat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.jingcjie.wifi_direct_cable.audio.NativeOpus
import com.jingcjie.wifi_direct_cable.gateway.TelephonyCodes

/**
 * Reads the device state [CompatibilityChecker] needs.
 *
 * Every read is defensive. This runs on whatever hardware the user has, including
 * OEM builds that throw from places the documentation does not mention, and a
 * diagnostics screen that crashes while reporting device capabilities would be
 * worse than one that reports a capability as absent.
 */
class AndroidCompatibilityProbe(context: Context) {

    private val appContext = context.applicationContext

    fun environment(minApiLevel: Int = MIN_API_LEVEL): CompatibilityEnvironment {
        val packageManager = appContext.packageManager
        return CompatibilityEnvironment(
            apiLevel = Build.VERSION.SDK_INT,
            minApiLevel = minApiLevel,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            bundledAbis = BUNDLED_ABIS,
            hasWifiHardware = hasFeature(packageManager, PackageManager.FEATURE_WIFI),
            hasWifiDirect = hasFeature(packageManager, PackageManager.FEATURE_WIFI_DIRECT),
            wifiEnabled = isWifiEnabled(),
            nearbyWifiDevicesGranted = isGranted(Manifest.permission.NEARBY_WIFI_DEVICES),
            hasTelephony = hasFeature(packageManager, PackageManager.FEATURE_TELEPHONY),
            simUsable = isSimUsable(),
            opusAvailable = isOpusAvailable(),
            recordAudioGranted = isGranted(Manifest.permission.RECORD_AUDIO),
            ignoringBatteryOptimizations = isIgnoringBatteryOptimizations()
        )
    }

    fun report(): CompatibilityReport = CompatibilityChecker.evaluate(environment())

    private fun hasFeature(packageManager: PackageManager, feature: String): Boolean = try {
        packageManager.hasSystemFeature(feature)
    } catch (exception: Exception) {
        false
    }

    private fun isWifiEnabled(): Boolean = try {
        appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    } catch (exception: Exception) {
        false
    }

    private fun isSimUsable(): Boolean = try {
        val manager = appContext.getSystemService(android.telephony.TelephonyManager::class.java)
        manager != null && TelephonyCodes.simUsable(TelephonyCodes.simStateName(manager.simState))
    } catch (exception: Exception) {
        false
    }

    /**
     * Whether libopus actually loaded, rather than whether an ABI looks right.
     * The library either linked or it did not, and that is the honest answer.
     */
    private fun isOpusAvailable(): Boolean = try {
        NativeOpus.available
    } catch (exception: Throwable) {
        // A failed native load surfaces as UnsatisfiedLinkError, which is an
        // Error rather than an Exception, so this catches Throwable deliberately.
        false
    }

    private fun isIgnoringBatteryOptimizations(): Boolean = try {
        appContext.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(appContext.packageName) == true
    } catch (exception: Exception) {
        false
    }

    private fun isGranted(permission: String): Boolean = try {
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    } catch (exception: Exception) {
        false
    }

    companion object {
        /** Android 13. Matches `minSdk` in the Gradle config. */
        const val MIN_API_LEVEL = 33

        /**
         * ABIs with a bundled `libopus.so`. `armeabi-v7a` is deliberately absent —
         * no 32-bit binary is shipped, so a 32-bit-only device cannot use the
         * audio features.
         */
        val BUNDLED_ABIS = listOf("arm64-v8a", "x86_64")
    }
}
