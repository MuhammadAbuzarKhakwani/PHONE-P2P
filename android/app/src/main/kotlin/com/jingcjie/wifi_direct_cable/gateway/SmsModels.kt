package com.jingcjie.wifi_direct_cable.gateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * One SMS, as forwarded to the client.
 *
 * Deliberately a summary rather than a faithful copy of the provider row: no
 * subscription id, no service-centre address, no raw PDU. The client needs enough
 * to show a conversation, and nothing more should cross the link.
 */
data class SmsMessageSummary(
    val id: Long,
    val threadId: Long,
    /** The other party. Never this device's own number. */
    val address: String,
    val body: String,
    val timestampMs: Long,
    val incoming: Boolean,
    val read: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put(FIELD_ID, id)
        .put(FIELD_THREAD_ID, threadId)
        .put(FIELD_ADDRESS, address)
        .put(FIELD_BODY, body)
        .put(FIELD_TIMESTAMP, timestampMs)
        .put(FIELD_INCOMING, incoming)
        .put(FIELD_READ, read)

    companion object {
        const val FIELD_ID = "id"
        const val FIELD_THREAD_ID = "threadId"
        const val FIELD_ADDRESS = "address"
        const val FIELD_BODY = "body"
        const val FIELD_TIMESTAMP = "timestamp"
        const val FIELD_INCOMING = "incoming"
        const val FIELD_READ = "read"

        fun fromJson(json: JSONObject): SmsMessageSummary = SmsMessageSummary(
            id = json.optLong(FIELD_ID),
            threadId = json.optLong(FIELD_THREAD_ID),
            address = json.optString(FIELD_ADDRESS),
            body = json.optString(FIELD_BODY),
            timestampMs = json.optLong(FIELD_TIMESTAMP),
            incoming = json.optBoolean(FIELD_INCOMING),
            read = json.optBoolean(FIELD_READ)
        )
    }
}

/** A thread, summarised for the conversation list. */
data class SmsConversation(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val timestampMs: Long,
    val messageCount: Int,
    val unreadCount: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("threadId", threadId)
        .put("address", address)
        .put("snippet", snippet)
        .put("timestamp", timestampMs)
        .put("messageCount", messageCount)
        .put("unreadCount", unreadCount)

    companion object {
        /**
         * Groups messages into conversations, newest first.
         *
         * Kept pure and separate from the content-provider query so the grouping
         * — the part with actual logic in it — is unit tested without a device.
         */
        fun group(messages: List<SmsMessageSummary>): List<SmsConversation> = messages
            .groupBy(SmsMessageSummary::threadId)
            .map { (threadId, thread) ->
                val newest = thread.maxByOrNull(SmsMessageSummary::timestampMs)
                SmsConversation(
                    threadId = threadId,
                    // The address of the most recent message, so a thread is
                    // labelled by whoever it is actually with.
                    address = newest?.address.orEmpty(),
                    snippet = newest?.body.orEmpty(),
                    timestampMs = newest?.timestampMs ?: 0L,
                    messageCount = thread.size,
                    unreadCount = thread.count { it.incoming && !it.read }
                )
            }
            .sortedByDescending(SmsConversation::timestampMs)

        fun listToJson(conversations: List<SmsConversation>): JSONArray =
            JSONArray().also { array -> conversations.forEach { array.put(it.toJson()) } }
    }
}

/**
 * Validates an outbound SMS before the gateway sends it.
 *
 * The recipient goes through [PhoneNumberValidator] for the same reason a dialled
 * number does — an unvalidated remote string reaching the telephony stack is the
 * problem, regardless of which API it reaches.
 */
object SmsRequestValidator {

    /**
     * A single GSM-7 SMS holds 160 characters, and a concatenated message loses
     * seven of those per segment to the UDH. This cap is not a protocol limit; it
     * is a guard against a client queueing an enormous multipart send that the
     * user pays for. Ten segments is already a lot of text.
     */
    const val MAX_BODY_CHARS = 1_530

    sealed interface Result {
        data class Valid(val address: String, val body: String) : Result
        data class Invalid(val reason: String) : Result
    }

    const val REASON_EMPTY_BODY = "empty_body"
    const val REASON_BODY_TOO_LONG = "body_too_long"

    fun validate(rawAddress: String?, body: String?): Result {
        val addressResult = PhoneNumberValidator.validate(rawAddress)
        if (addressResult is PhoneNumberValidator.Result.Invalid) {
            return Result.Invalid(addressResult.reason)
        }

        if (body.isNullOrEmpty()) {
            return Result.Invalid(REASON_EMPTY_BODY)
        }
        if (body.length > MAX_BODY_CHARS) {
            return Result.Invalid(REASON_BODY_TOO_LONG)
        }

        return Result.Valid(
            address = (addressResult as PhoneNumberValidator.Result.Valid).dialable,
            body = body
        )
    }
}
