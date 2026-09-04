package com.jingcjie.wifi_direct_cable.protocol

enum class ProtocolFrameType(val id: Int, val protocolName: String) {
    HANDSHAKE_HELLO(1, "handshake.hello"),
    HANDSHAKE_ACK(2, "handshake.ack"),
    HEARTBEAT_PING(3, "heartbeat.ping"),
    HEARTBEAT_PONG(4, "heartbeat.pong"),
    CLOSE(5, "close"),
    ERROR(6, "error"),
    CONTROL_MESSAGE(10, "control.message"),
    ACK(11, "ack"),
    BULK_START(20, "bulk.start"),
    BULK_CHUNK(21, "bulk.chunk"),
    BULK_COMPLETE(22, "bulk.complete"),
    BULK_CANCEL(23, "bulk.cancel"),
    AUDIO_FRAME(30, "audio.frame"),

    // --- Pairing and identity (v3) -----------------------------------------
    PAIR_REQUEST(40, "pair.request"),
    PAIR_RESPONSE(41, "pair.response"),
    PAIR_CONFIRM(42, "pair.confirm"),
    PAIR_RESULT(43, "pair.result"),
    DEVICE_INFO(44, "device.info"),

    // --- Gateway status (Phase 6) ------------------------------------------
    GATEWAY_STATUS_REQUEST(50, "gateway.status.request"),
    GATEWAY_STATUS(51, "gateway.status"),

    // --- Remote call control (Phase 7) -------------------------------------
    //
    // CALL_STATE is the single event type carrying dialing/ringing/active/
    // ended/failed in its metadata, rather than five frame types. That keeps the
    // client's handling to one code path and makes an unknown future state a
    // metadata value to ignore rather than an unknown frame type that aborts the
    // connection.
    CALL_REQUEST(60, "call.request"),
    CALL_STATE(61, "call.state"),
    CALL_ANSWER(62, "call.answer"),
    CALL_REJECT(63, "call.reject"),
    CALL_HANGUP(64, "call.hangup"),

    // --- SMS (Phase 9) ------------------------------------------------------
    SMS_LIST_REQUEST(70, "sms.list.request"),
    SMS_LIST(71, "sms.list"),
    SMS_SEND_REQUEST(72, "sms.send.request"),
    SMS_EVENT(73, "sms.event"),

    // --- Audio session control (Phase 8) ------------------------------------
    AUDIO_START(80, "audio.start"),
    AUDIO_STOP(81, "audio.stop");

    companion object {
        fun fromId(id: Int): ProtocolFrameType {
            return entries.firstOrNull { it.id == id }
                ?: throw ProtocolException(
                    ProtocolError.INVALID_FRAME_TYPE,
                    "Unknown protocol frame type id: $id"
                )
        }
    }
}
