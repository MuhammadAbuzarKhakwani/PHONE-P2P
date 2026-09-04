package com.jingcjie.wifi_direct_cable.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsModelsTest {

    private fun message(
        id: Long,
        threadId: Long,
        address: String = "+15551234567",
        body: String = "hello",
        timestampMs: Long = 1_000L,
        incoming: Boolean = true,
        read: Boolean = true
    ) = SmsMessageSummary(id, threadId, address, body, timestampMs, incoming, read)

    // -------------------------------------------------------------- grouping --

    @Test
    fun `messages are grouped into threads, newest first`() {
        val conversations = SmsConversation.group(
            listOf(
                message(1, threadId = 10, timestampMs = 100),
                message(2, threadId = 10, timestampMs = 300),
                message(3, threadId = 20, timestampMs = 200)
            )
        )

        assertEquals(2, conversations.size)
        assertEquals(10L, conversations[0].threadId) // newest message overall
        assertEquals(20L, conversations[1].threadId)
    }

    @Test
    fun `a thread is labelled and snippeted by its newest message`() {
        val conversations = SmsConversation.group(
            listOf(
                message(1, threadId = 10, body = "older", address = "+1111", timestampMs = 100),
                message(2, threadId = 10, body = "newer", address = "+2222", timestampMs = 500)
            )
        )

        val thread = conversations.single()
        assertEquals("newer", thread.snippet)
        assertEquals("+2222", thread.address)
        assertEquals(500L, thread.timestampMs)
        assertEquals(2, thread.messageCount)
    }

    @Test
    fun `only unread incoming messages count as unread`() {
        val thread = SmsConversation.group(
            listOf(
                message(1, threadId = 10, incoming = true, read = false),
                message(2, threadId = 10, incoming = true, read = true),
                // An outgoing message is never "unread" no matter what the
                // provider's read flag says.
                message(3, threadId = 10, incoming = false, read = false)
            )
        ).single()

        assertEquals(1, thread.unreadCount)
        assertEquals(3, thread.messageCount)
    }

    @Test
    fun `grouping an empty list yields no conversations`() {
        assertTrue(SmsConversation.group(emptyList()).isEmpty())
    }

    @Test
    fun `a conversation serialises for the wire`() {
        val json = SmsConversation.group(listOf(message(1, threadId = 7, body = "hi"))).single().toJson()
        assertEquals(7L, json.getLong("threadId"))
        assertEquals("hi", json.getString("snippet"))
    }

    @Test
    fun `a message round trips`() {
        val original = message(5, threadId = 9, body = "round trip", incoming = false, read = false)
        assertEquals(original, SmsMessageSummary.fromJson(original.toJson()))
    }

    // ------------------------------------------------------------ validation --

    private fun invalidReason(address: String?, body: String?): String {
        val result = SmsRequestValidator.validate(address, body)
        assertTrue("expected rejection, got $result", result is SmsRequestValidator.Result.Invalid)
        return (result as SmsRequestValidator.Result.Invalid).reason
    }

    @Test
    fun `a normal message is accepted and the address normalised`() {
        val result = SmsRequestValidator.validate("+1 (555) 123-4567", "hello there")
        assertTrue(result is SmsRequestValidator.Result.Valid)
        result as SmsRequestValidator.Result.Valid
        assertEquals("+15551234567", result.address)
        assertEquals("hello there", result.body)
    }

    @Test
    fun `an MMI code is rejected as a recipient`() {
        // Same reasoning as dialling: an unvalidated remote string must not reach
        // the telephony stack, whichever API it would reach.
        assertEquals("mmi_code_not_allowed", invalidReason("**21*5551234567#", "hi"))
    }

    @Test
    fun `an empty body is rejected`() {
        assertEquals(SmsRequestValidator.REASON_EMPTY_BODY, invalidReason("+15551234567", ""))
        assertEquals(SmsRequestValidator.REASON_EMPTY_BODY, invalidReason("+15551234567", null))
    }

    @Test
    fun `an over-length body is rejected rather than silently truncated`() {
        val tooLong = "x".repeat(SmsRequestValidator.MAX_BODY_CHARS + 1)
        assertEquals(SmsRequestValidator.REASON_BODY_TOO_LONG, invalidReason("+15551234567", tooLong))

        // The boundary itself is fine.
        val atLimit = "x".repeat(SmsRequestValidator.MAX_BODY_CHARS)
        assertTrue(
            SmsRequestValidator.validate("+15551234567", atLimit) is SmsRequestValidator.Result.Valid
        )
    }

    @Test
    fun `a blank recipient is rejected`() {
        assertEquals("empty_number", invalidReason(null, "hi"))
        assertEquals("empty_number", invalidReason("", "hi"))
    }

    @Test
    fun `whitespace-only body is still a body`() {
        // A space is a legitimate, if odd, message. Rejecting it would be the
        // validator inventing a rule the platform does not have.
        assertTrue(
            SmsRequestValidator.validate("+15551234567", " ") is SmsRequestValidator.Result.Valid
        )
    }
}
