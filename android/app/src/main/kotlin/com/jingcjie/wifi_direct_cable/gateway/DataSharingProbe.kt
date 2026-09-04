package com.jingcjie.wifi_direct_cable.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject

/**
 * Reports the two *different* things the brief insists on keeping apart:
 *
 * - **The remote gateway connection** — the Wi-Fi Direct control link. Works with
 *   no Internet at all, and is what every other feature in this app runs over.
 * - **An Internet connection** — Phone 2 reaching the wider Internet through
 *   Phone 1's cellular data. A separate networking function that this app does
 *   not, and largely cannot, provide.
 *
 * ## Why this only reports
 *
 * There is **no public API to enable tethering programmatically**.
 * `TetheringManager.startTethering()` requires `TETHER_PRIVILEGED`, which is
 * `signature|privileged`; the older `ConnectivityManager.startTethering()` is
 * hidden and blocked by the non-SDK interface restrictions, and `WRITE_SETTINGS`
 * does not help. So the app must not attempt a workaround — it shows the state and
 * points the user at Settings.
 *
 * Hotspot state is also **not readable**: `WifiManager.isWifiApEnabled()` is a
 * system API. [hotspotActive] is therefore always null, which the UI renders as
 * unavailable rather than guessing "off".
 *
 * There is a further device-dependent catch, recorded in
 * `docs/ANDROID_LIMITATIONS.md` §6: many chipsets cannot hold a Wi-Fi Direct group
 * and a SoftAP hotspot at the same time. On those devices, sharing Internet over
 * the hotspot may drop the control link. That cannot be detected reliably in
 * advance, so it is documented rather than promised either way.
 */
class DataSharingProbe(context: Context) {

    private val appContext = context.applicationContext

    data class Status(
        /** This device has working Internet, whatever the transport. */
        val hasInternet: Boolean,
        /** The transport carrying it: `cellular`, `wifi`, `ethernet`, or null. */
        val transport: String?,
        /**
         * Whether the hotspot is on. Always null — Android exposes no public way
         * for an app to read this.
         */
        val hotspotActive: Boolean? = null,
        /** Always false. See the class comment. */
        val canControlTetheringProgrammatically: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("hasInternet", hasInternet)
            .put("hotspotDetectable", false)
            .put("canControlTethering", canControlTetheringProgrammatically)
            .apply { transport?.let { put("transport", it) } }
    }

    fun status(): Status {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return Status(hasInternet = false, transport = null)

        return try {
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
                ?: return Status(hasInternet = false, transport = null)

            Status(
                // VALIDATED, not just CONNECTED: a captive portal or a hotspot with
                // no upstream reports connected while nothing actually reaches the
                // Internet, and reporting that as working would be a lie the user
                // then has to debug.
                hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                transport = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> null
                }
            )
        } catch (exception: Exception) {
            Status(hasInternet = false, transport = null)
        }
    }
}
