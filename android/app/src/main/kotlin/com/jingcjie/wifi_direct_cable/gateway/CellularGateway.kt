package com.jingcjie.wifi_direct_cable.gateway

/**
 * The cellular operations [GatewaySessionHandler] needs.
 *
 * Extracted from [TelephonyGateway] so the handler's rules — above all the rule
 * that telephony commands are refused on an unauthenticated session — can be
 * tested on a plain JVM. The real implementation needs a `Context`,
 * `TelephonyManager` and `TelecomManager`, none of which exist under a JUnit run,
 * which would otherwise leave the single most security-sensitive check in the
 * gateway untested.
 */
interface CellularGateway {

    fun readStatus(gatewayVersion: String = "", deviceName: String = ""): GatewayStatus

    /** @return `dialing` on success, or `failed` with a reason. */
    fun placeCall(rawNumber: String?): CallStateTracker.CallEvent

    /** @return an event only on failure; success is confirmed by the state listener. */
    fun answerCall(): CallStateTracker.CallEvent?

    /** @return an event only on failure; success is confirmed by the state listener. */
    fun endCall(): CallStateTracker.CallEvent?
}
