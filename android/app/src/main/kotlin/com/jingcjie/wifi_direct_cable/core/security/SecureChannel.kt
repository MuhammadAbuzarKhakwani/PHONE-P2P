package com.jingcjie.wifi_direct_cable.core.security

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.protocol.ProtocolError
import com.jingcjie.wifi_direct_cable.protocol.ProtocolException
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.protocol.SecureProtocolCodec
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Stateful v3 framing for one connection: encrypts outbound frames, verifies and
 * decrypts inbound ones, and enforces the anti-replay and freshness rules.
 *
 * Lifecycle mirrors the handshake:
 *
 * 1. Constructed **inactive**. Frames pass in plaintext v3, which is what the
 *    `PAIR_*` handshake frames need — there are no keys yet.
 * 2. [activate] is called with the [SessionKeys] the handshake produced.
 * 3. From then on every outbound frame is sealed, and any inbound plaintext frame
 *    is **rejected**, so an attacker cannot strip the encrypted flag to force the
 *    link back down to plaintext.
 *
 * Threading: one instance per connection. [writeFrame] is synchronised because
 * the nonce counter and the socket's output stream must advance together. Reads
 * are expected to come from that channel's single reader thread, matching how
 * `SessionManager` already drives its transports.
 */
class SecureChannel(
    private val localDeviceId: DeviceId,
    replayWindowSize: Int = ReplayWindow.DEFAULT_WINDOW_SIZE,
    private val clock: () -> Long = System::currentTimeMillis
) {

    @Volatile
    private var keys: SessionKeys? = null

    @Volatile
    private var nonces: NonceGenerator? = null

    @Volatile
    private var expectedPeer: DeviceId? = null

    private val replayWindow = ReplayWindow(replayWindowSize)
    private val outboundSequence = AtomicLong(0)
    private val writeLock = Any()

    private val framesSent = AtomicLong(0)
    private val framesReceived = AtomicLong(0)
    private val framesRejected = AtomicLong(0)

    val isActive: Boolean get() = keys != null

    fun framesSent(): Long = framesSent.get()
    fun framesReceived(): Long = framesReceived.get()
    fun framesRejected(): Long = framesRejected.get()
    fun duplicatesDropped(): Long = replayWindow.duplicateCount()
    fun replaysDropped(): Long = replayWindow.tooOldCount()

    /**
     * Switches the channel to authenticated framing.
     *
     * @param peerDeviceId the identity confirmed by the handshake. Every later
     *        frame must carry it; the brief's "reject unknown devices" rule is
     *        enforced here rather than trusting the frame's own claim.
     */
    fun activate(sessionKeys: SessionKeys, peerDeviceId: DeviceId) {
        synchronized(writeLock) {
            check(keys == null) { "SecureChannel is already active" }
            keys = sessionKeys
            nonces = NonceGenerator()
            expectedPeer = peerDeviceId
            replayWindow.reset()
        }
    }

    /**
     * Tears down the channel and wipes key material.
     */
    fun deactivate() {
        synchronized(writeLock) {
            keys?.destroy()
            keys = null
            nonces = null
            expectedPeer = null
            replayWindow.reset()
        }
    }

    /**
     * Stamps [frame] with the local identity, a fresh timestamp, and the next
     * sequence number, then writes it.
     */
    fun writeFrame(frame: ProtocolFrame, outputStream: OutputStream) {
        synchronized(writeLock) {
            val activeKeys = keys
            val stamped = frame.copy(
                sequenceNumber = outboundSequence.getAndIncrement(),
                timestampMs = clock(),
                senderDeviceId = localDeviceId
            )

            if (activeKeys == null) {
                SecureProtocolCodec.writeFrame(stamped, outputStream, key = null, nonce = null)
            } else {
                val key = activeKeys.transmitKey()
                try {
                    SecureProtocolCodec.writeFrame(
                        frame = stamped,
                        outputStream = outputStream,
                        key = key,
                        nonce = nonces!!.next()
                    )
                } finally {
                    CryptoPrimitives.wipe(key)
                }
            }
            framesSent.incrementAndGet()
        }
    }

    /**
     * Reads and validates the next frame, or returns null at end of stream.
     *
     * Rejects, in order: unauthenticated frames on a keyed channel, frames whose
     * GCM tag fails, frames from a device other than the paired peer, stale
     * frames, and replayed or duplicated sequence numbers.
     *
     * @throws ProtocolException on any of the above. Callers must treat it as
     *         fatal for the session — a frame that fails authentication means
     *         someone is interfering, and continuing to read would be reading
     *         attacker-chosen data.
     */
    fun readFrame(inputStream: InputStream): ProtocolFrame? {
        val activeKeys = keys
        val frame = if (activeKeys == null) {
            SecureProtocolCodec.readFrame(inputStream, key = null, requireEncryption = false)
        } else {
            val key = activeKeys.receiveKey()
            try {
                SecureProtocolCodec.readFrame(inputStream, key = key, requireEncryption = true)
            } finally {
                CryptoPrimitives.wipe(key)
            }
        } ?: return null

        if (activeKeys != null) {
            validateActiveFrame(frame)
        }

        framesReceived.incrementAndGet()
        return frame
    }

    private fun validateActiveFrame(frame: ProtocolFrame) {
        val peer = expectedPeer
        if (peer != null && frame.senderDeviceId != peer) {
            framesRejected.incrementAndGet()
            throw ProtocolException(
                ProtocolError.UNKNOWN_DEVICE,
                "Frame claims device ${frame.senderDeviceId.shortLabel()}, " +
                    "expected paired peer ${peer.shortLabel()}"
            )
        }

        if (!SecureProtocolCodec.isFresh(frame.timestampMs, clock())) {
            framesRejected.incrementAndGet()
            throw ProtocolException(
                ProtocolError.STALE_FRAME,
                "Frame timestamp ${frame.timestampMs} is outside the freshness window"
            )
        }

        if (!replayWindow.accept(frame.sequenceNumber)) {
            framesRejected.incrementAndGet()
            throw ProtocolException(
                ProtocolError.REPLAYED_FRAME,
                "Frame sequence ${frame.sequenceNumber} is a duplicate or has fallen " +
                    "outside the replay window (highest=${replayWindow.highest})"
            )
        }
    }

    /** Snapshot for the diagnostics page. Contains no key material. */
    fun statsSnapshot(): Map<String, Any> = mapOf(
        "secureActive" to isActive,
        "framesSent" to framesSent(),
        "framesReceived" to framesReceived(),
        "framesRejected" to framesRejected(),
        "duplicatesDropped" to duplicatesDropped(),
        "replaysDropped" to replaysDropped(),
        "peerDeviceId" to (expectedPeer?.shortLabel() ?: "")
    )
}
