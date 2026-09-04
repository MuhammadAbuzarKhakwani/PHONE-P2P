package com.jingcjie.wifi_direct_cable.session

import com.jingcjie.wifi_direct_cable.core.security.SecureChannel
import com.jingcjie.wifi_direct_cable.protocol.ProtocolChannel
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame

interface SessionTransport {
    val channel: ProtocolChannel

    fun readFrame(): ProtocolFrame?

    fun writeFrame(frame: ProtocolFrame)

    fun setReadTimeout(timeoutMs: Int)

    fun close()

    fun cancel()

    /**
     * Switches this connection from the plaintext v2 framing to the v3
     * authenticated framing, using [secureChannel] for every subsequent frame.
     *
     * Both peers must call this at exactly the same point in the byte stream —
     * immediately after the pairing exchange completes — or they will disagree
     * about how to parse the next frame. `SecureSessionNegotiator` is what
     * guarantees that ordering; do not call this directly from anywhere else.
     *
     * Has a default body so that existing implementations, including test
     * doubles, keep compiling without change. A transport that cannot be secured
     * simply reports [isSecure] as false.
     */
    fun activateSecurity(secureChannel: SecureChannel) {
        throw UnsupportedOperationException(
            "${javaClass.simpleName} does not support the v3 secure framing"
        )
    }

    /** True once [activateSecurity] has been applied. */
    val isSecure: Boolean get() = false
}

interface SessionTransportAdapter {
    fun accept(
        channel: ProtocolChannel,
        port: Int,
        shouldCancel: () -> Boolean
    ): SessionTransport

    fun connect(
        channel: ProtocolChannel,
        host: String,
        port: Int
    ): SessionTransport

    fun listen(
        channel: ProtocolChannel,
        preferredPort: Int = 0
    ): SessionTransportListener

    fun close()

    fun cancel()
}

interface SessionTransportListener {
    val channel: ProtocolChannel
    val port: Int

    fun accept(shouldCancel: () -> Boolean): SessionTransport

    fun close()
}
