package com.jingcjie.wifi_direct_cable.gateway

/**
 * Outcome of an outbound SMS.
 *
 * Reports only what the gateway actually knows at the point of sending. Handing
 * the message to the platform is not proof it reached anyone: delivery reports
 * require registering `PendingIntent`s and are carrier-dependent, so [Sent] means
 * "accepted for sending", never "delivered", and the client must not claim
 * otherwise.
 */
sealed interface SmsSendResult {
    data class Sent(val segments: Int) : SmsSendResult
    data class Failed(val reason: String) : SmsSendResult
}

/**
 * The SMS operations [GatewaySessionHandler] needs.
 *
 * Extracted for the same reason as [CellularGateway]: the real implementation
 * needs a `Context`, `SmsManager` and the SMS content provider, none of which
 * exist under a plain JUnit run.
 */
interface SmsGatewayApi {
    fun send(rawAddress: String?, body: String?): SmsSendResult
    fun readConversations(limit: Int = SmsGateway.DEFAULT_LIMIT): List<SmsConversation>
    fun readRecent(limit: Int = SmsGateway.DEFAULT_LIMIT): List<SmsMessageSummary>
}
