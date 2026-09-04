package com.jingcjie.wifi_direct_cable.compat

import org.json.JSONArray
import org.json.JSONObject

enum class SupportLevel {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    UNSUPPORTED;

    /** Worst of the two, used to fold findings into an overall verdict. */
    fun worseOf(other: SupportLevel): SupportLevel =
        if (other.ordinal > ordinal) other else this
}

/**
 * One checked capability.
 *
 * @param required whether this feeds the overall verdict. Cellular call audio is
 *        permanently unsupported and is reported as such, but it must not drag
 *        the whole device to UNSUPPORTED — that would say "this app will not work
 *        here", which is false.
 * @param reason machine-readable, and where possible **actionable**: a permission
 *        the user can grant reads differently from hardware that is absent.
 */
data class CompatibilityFinding(
    val id: String,
    val level: SupportLevel,
    val required: Boolean,
    val reason: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("level", level.name)
        .put("required", required)
        .apply { reason?.let { put("reason", it) } }
}

data class CompatibilityReport(
    val overall: SupportLevel,
    val findings: List<CompatibilityFinding>
) {
    fun finding(id: String): CompatibilityFinding? = findings.firstOrNull { it.id == id }

    fun toJson(): JSONObject = JSONObject()
        .put("overall", overall.name)
        .put("findings", JSONArray().also { array -> findings.forEach { array.put(it.toJson()) } })
}

/**
 * Everything the rules depend on, gathered so the rules are a pure function.
 */
data class CompatibilityEnvironment(
    val apiLevel: Int,
    val minApiLevel: Int,
    val supportedAbis: List<String>,
    val bundledAbis: List<String>,
    val hasWifiHardware: Boolean,
    val hasWifiDirect: Boolean,
    val wifiEnabled: Boolean,
    val nearbyWifiDevicesGranted: Boolean,
    val hasTelephony: Boolean,
    val simUsable: Boolean,
    val opusAvailable: Boolean,
    val recordAudioGranted: Boolean,
    val ignoringBatteryOptimizations: Boolean
)

/**
 * Reports what this specific device can actually do.
 *
 * The brief's instruction is not to advertise "works everywhere", and this is how
 * that is enforced in code rather than in prose. Three levels, each with a reason,
 * and the Wi-Fi Direct layer assessed separately from the cellular features —
 * because the transport is close to device-independent while the telephony and
 * audio capabilities genuinely are not.
 *
 * Pure and Android-free, so every rule is unit tested. [AndroidCompatibilityProbe]
 * does the platform reading.
 */
object CompatibilityChecker {

    // Findings that decide whether the app works at all.
    const val ID_ANDROID_VERSION = "android.version"
    const val ID_ABI = "device.abi"
    const val ID_WIFI_HARDWARE = "wifi.hardware"
    const val ID_WIFI_DIRECT = "wifi.direct"
    const val ID_WIFI_ENABLED = "wifi.enabled"
    const val ID_NEARBY_PERMISSION = "permission.nearbyWifiDevices"

    // Optional features.
    const val ID_BACKGROUND = "background.execution"
    const val ID_AUDIO_LINK = "audio.link"
    const val ID_GATEWAY = "gateway.telephony"
    const val ID_CELLULAR_CALL_AUDIO = "telephony.audio.cellular"

    const val REASON_BELOW_MIN_API = "android_version_below_minimum"
    const val REASON_NO_HARDWARE = "hardware_not_present"
    const val REASON_NOT_SUPPORTED = "not_supported_by_device"
    const val REASON_DISABLED = "currently_disabled"
    const val REASON_PERMISSION = "permission_not_granted"
    const val REASON_NO_ABI = "no_bundled_native_library_for_this_abi"
    const val REASON_NO_TELEPHONY = "no_cellular_hardware"
    const val REASON_NO_SIM = "no_usable_sim"
    const val REASON_BATTERY_OPTIMIZED = "battery_optimization_may_kill_the_session"
    const val REASON_PRIVILEGED = "requires_privileged_or_system_app"

    fun evaluate(environment: CompatibilityEnvironment): CompatibilityReport {
        val findings = mutableListOf<CompatibilityFinding>()

        findings += required(
            ID_ANDROID_VERSION,
            environment.apiLevel >= environment.minApiLevel,
            REASON_BELOW_MIN_API
        )

        // The app cannot install without a matching ABI, but the check is kept so
        // a split or sideloaded build with the wrong native libraries reports the
        // cause rather than crashing at first use.
        findings += required(
            ID_ABI,
            environment.supportedAbis.any(environment.bundledAbis::contains),
            REASON_NO_ABI
        )

        findings += required(ID_WIFI_HARDWARE, environment.hasWifiHardware, REASON_NO_HARDWARE)
        findings += required(ID_WIFI_DIRECT, environment.hasWifiDirect, REASON_NOT_SUPPORTED)

        // Wi-Fi being off and the permission being ungranted are both the user's
        // to fix, so they are PARTIAL rather than UNSUPPORTED: the device is
        // capable, it is just not ready right now.
        findings += fixable(ID_WIFI_ENABLED, environment.wifiEnabled, REASON_DISABLED)
        findings += fixable(
            ID_NEARBY_PERMISSION,
            environment.nearbyWifiDevicesGranted,
            REASON_PERMISSION
        )

        findings += CompatibilityFinding(
            id = ID_BACKGROUND,
            level = if (environment.ignoringBatteryOptimizations) {
                SupportLevel.SUPPORTED
            } else {
                SupportLevel.PARTIALLY_SUPPORTED
            },
            required = false,
            reason = if (environment.ignoringBatteryOptimizations) null else REASON_BATTERY_OPTIMIZED
        )

        findings += optional(
            ID_AUDIO_LINK,
            supported = environment.opusAvailable && environment.recordAudioGranted,
            partial = environment.opusAvailable && !environment.recordAudioGranted,
            reason = when {
                !environment.opusAvailable -> REASON_NO_ABI
                else -> REASON_PERMISSION
            }
        )

        findings += optional(
            ID_GATEWAY,
            supported = environment.hasTelephony && environment.simUsable,
            partial = environment.hasTelephony && !environment.simUsable,
            reason = if (!environment.hasTelephony) REASON_NO_TELEPHONY else REASON_NO_SIM
        )

        // Always unsupported, on every device, in every build. Not marked required,
        // so it never drags the overall verdict down — the app works fine without
        // it, and reporting UNSUPPORTED overall here would itself be misleading.
        findings += CompatibilityFinding(
            id = ID_CELLULAR_CALL_AUDIO,
            level = SupportLevel.UNSUPPORTED,
            required = false,
            reason = REASON_PRIVILEGED
        )

        val overall = findings
            .filter(CompatibilityFinding::required)
            .fold(SupportLevel.SUPPORTED) { worst, finding -> worst.worseOf(finding.level) }

        return CompatibilityReport(overall, findings)
    }

    /** A hard requirement: missing means the app will not work. */
    private fun required(id: String, ok: Boolean, reason: String) = CompatibilityFinding(
        id = id,
        level = if (ok) SupportLevel.SUPPORTED else SupportLevel.UNSUPPORTED,
        required = true,
        reason = if (ok) null else reason
    )

    /** Required, but the user can fix it, so its absence is only partial. */
    private fun fixable(id: String, ok: Boolean, reason: String) = CompatibilityFinding(
        id = id,
        level = if (ok) SupportLevel.SUPPORTED else SupportLevel.PARTIALLY_SUPPORTED,
        required = true,
        reason = if (ok) null else reason
    )

    private fun optional(
        id: String,
        supported: Boolean,
        partial: Boolean,
        reason: String
    ) = CompatibilityFinding(
        id = id,
        level = when {
            supported -> SupportLevel.SUPPORTED
            partial -> SupportLevel.PARTIALLY_SUPPORTED
            else -> SupportLevel.UNSUPPORTED
        },
        required = false,
        reason = if (supported) null else reason
    )

    /** Plain-language explanation, kept beside the reasons so none goes unexplained. */
    fun explain(reason: String): String = when (reason) {
        REASON_BELOW_MIN_API -> "This app requires Android 13 or newer."
        REASON_NO_HARDWARE -> "This device has no Wi-Fi hardware."
        REASON_NOT_SUPPORTED -> "This device does not support Wi-Fi Direct."
        REASON_DISABLED -> "Wi-Fi is turned off. Turn it on to connect."
        REASON_PERMISSION -> "A required permission has not been granted."
        REASON_NO_ABI -> "No native library is bundled for this device's processor."
        REASON_NO_TELEPHONY -> "This device has no cellular hardware, so it cannot act as a gateway."
        REASON_NO_SIM -> "No usable SIM, so this device cannot act as a gateway."
        REASON_BATTERY_OPTIMIZED ->
            "Battery optimisation may end the connection in the background. " +
                "Exempting the app makes long sessions more reliable."
        REASON_PRIVILEGED ->
            "Not available to a normal app. Routing live cellular call audio " +
                "requires a privileged or system-signed build."
        else -> "Unavailable on this device."
    }
}
