package com.jingcjie.wifi_direct_cable.core.security

import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.protocol.ProtocolChannel
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.session.SessionTransport
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs two real [SecureSessionNegotiator] instances against each other over an
 * in-memory transport pair.
 *
 * The two peers genuinely run on separate threads exchanging frames, so this
 * covers the message ordering — which is the part most likely to deadlock on real
 * hardware and the part a single-threaded test would quietly not exercise.
 */
class SecureSessionNegotiatorTest {

    // ------------------------------------------------------------- doubles --

    /** One end of a bidirectional in-memory frame pipe. */
    private class PipeTransport(
        override val channel: ProtocolChannel,
        private val inbound: LinkedBlockingQueue<ProtocolFrame>,
        private val outbound: LinkedBlockingQueue<ProtocolFrame>
    ) : SessionTransport {
        @Volatile
        private var closed = false
        private var timeoutMs = 5_000

        var secured: SecureChannel? = null
            private set

        override fun readFrame(): ProtocolFrame? {
            if (closed) return null
            return inbound.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }

        override fun writeFrame(frame: ProtocolFrame) {
            check(!closed) { "transport closed" }
            outbound.put(frame)
        }

        override fun setReadTimeout(timeoutMs: Int) {
            // 0 means "block forever" on a socket; keep the test bounded instead.
            this.timeoutMs = if (timeoutMs <= 0) 5_000 else timeoutMs.coerceAtMost(5_000)
        }

        override fun close() {
            closed = true
        }

        override fun cancel() = close()

        override fun activateSecurity(secureChannel: SecureChannel) {
            secured = secureChannel
        }

        override val isSecure: Boolean get() = secured != null

        companion object {
            fun pair(channel: ProtocolChannel): Pair<PipeTransport, PipeTransport> {
                val aToB = LinkedBlockingQueue<ProtocolFrame>()
                val bToA = LinkedBlockingQueue<ProtocolFrame>()
                return PipeTransport(channel, bToA, aToB) to PipeTransport(channel, aToB, bToA)
            }
        }
    }

    private class FakeRepository(
        private val deviceId: DeviceId,
        private val name: String
    ) : PairingRepository {
        val records = mutableMapOf<DeviceId, PairingRecord>()
        var touched = 0

        override fun localDeviceId() = deviceId
        override fun localDisplayName() = name
        override fun findPairing(peerDeviceId: DeviceId) = records[peerDeviceId]
        override fun savePairing(record: PairingRecord) {
            records[record.peerDeviceId] = record
        }

        override fun touchPairing(peerDeviceId: DeviceId, nowMs: Long) {
            touched++
        }
    }

    private class FakePrompt(
        private val code: PairingCode?,
        private val sasAnswer: Boolean = true
    ) : PairingPrompt {
        var displayedCode: PairingCode? = null
        var seenSas: String? = null
        var codeRequested = false

        override fun displayPairingCode(peerDeviceId: DeviceId, peerName: String): PairingCode =
            (code ?: PairingCode.random()).also { displayedCode = it }

        override fun requestPairingCode(peerDeviceId: DeviceId, peerName: String): PairingCode? {
            codeRequested = true
            return code
        }

        override fun confirmShortAuthString(peerDeviceId: DeviceId, sas: String): Boolean {
            seenSas = sas
            return sasAnswer
        }
    }

    // --------------------------------------------------------------- setup --

    private val gatewayId = DeviceId.random()
    private val clientId = DeviceId.random()
    private val code = PairingCode("482913")

    // Daemon threads, and shut down after each test: a non-daemon pool left
    // running keeps the Gradle test JVM alive after the suite finishes.
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "negotiator-test").apply { isDaemon = true }
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Runs both ends concurrently and returns their outcomes. */
    private fun run(
        gatewayRepo: FakeRepository,
        clientRepo: FakeRepository,
        gatewayPrompt: FakePrompt,
        clientPrompt: FakePrompt
    ): Pair<Outcome, Outcome> {
        val (gatewayTransport, clientTransport) = PipeTransport.pair(ProtocolChannel.CONTROL)

        val gatewayTask = executor.submit<Outcome> {
            capture {
                SecureSessionNegotiator(gatewayRepo, gatewayPrompt, clock = { NOW })
                    .negotiate(gatewayTransport, SecureRole.INITIATOR, SESSION)
            }
        }
        val clientTask = executor.submit<Outcome> {
            capture {
                SecureSessionNegotiator(clientRepo, clientPrompt, clock = { NOW })
                    .negotiate(clientTransport, SecureRole.RESPONDER, SESSION)
            }
        }

        return gatewayTask.get(20, TimeUnit.SECONDS) to clientTask.get(20, TimeUnit.SECONDS)
    }

    private class Outcome(
        val result: SecureSessionNegotiator.Result?,
        val error: Exception?
    )

    private fun capture(block: () -> SecureSessionNegotiator.Result): Outcome = try {
        Outcome(block(), null)
    } catch (exception: Exception) {
        Outcome(null, exception)
    }

    private fun freshPair(
        gatewayPrompt: FakePrompt = FakePrompt(code),
        clientPrompt: FakePrompt = FakePrompt(code)
    ): Triple<Pair<Outcome, Outcome>, FakeRepository, FakeRepository> {
        val gatewayRepo = FakeRepository(gatewayId, "Phone 1")
        val clientRepo = FakeRepository(clientId, "Phone 2")
        val outcomes = run(gatewayRepo, clientRepo, gatewayPrompt, clientPrompt)
        return Triple(outcomes, gatewayRepo, clientRepo)
    }

    // ---------------------------------------------------------------- tests --

    @Test
    fun `first pairing succeeds on both ends`() {
        val gatewayPrompt = FakePrompt(code)
        val clientPrompt = FakePrompt(code)
        val (outcomes, gatewayRepo, clientRepo) = freshPair(gatewayPrompt, clientPrompt)
        val (gateway, client) = outcomes

        assertNull("gateway failed: ${gateway.error}", gateway.error)
        assertNull("client failed: ${client.error}", client.error)

        assertEquals(clientId, gateway.result!!.peerDeviceId)
        assertEquals(gatewayId, client.result!!.peerDeviceId)
        assertTrue(gateway.result!!.isNewPairing)
        assertTrue(client.result!!.isNewPairing)

        // Both persisted a record for the other.
        assertNotNull(gatewayRepo.records[clientId])
        assertNotNull(clientRepo.records[gatewayId])
    }

    @Test
    fun `the gateway displays the code and the client is asked for it`() {
        val gatewayPrompt = FakePrompt(code)
        val clientPrompt = FakePrompt(code)
        freshPair(gatewayPrompt, clientPrompt)

        assertEquals(code, gatewayPrompt.displayedCode)
        assertTrue("client should have been asked to type the code", clientPrompt.codeRequested)
    }

    @Test
    fun `both ends see the same short authentication string`() {
        val gatewayPrompt = FakePrompt(code)
        val clientPrompt = FakePrompt(code)
        val (outcomes, _, _) = freshPair(gatewayPrompt, clientPrompt)

        assertEquals(outcomes.first.result!!.shortAuthString, outcomes.second.result!!.shortAuthString)
        assertEquals(gatewayPrompt.seenSas, clientPrompt.seenSas)
        assertNotNull(gatewayPrompt.seenSas)
    }

    @Test
    fun `derived per-channel keys match across the link`() {
        val (outcomes, _, _) = freshPair()
        val gateway = outcomes.first.result!!
        val client = outcomes.second.result!!

        // AUDIO is included because the derivation must work for it, but note
        // that no audio currently crosses a SessionTransport: the Audio Link uses
        // its own RTP/UDP sockets and is not secured. See ANDROID_LIMITATIONS 5.1.
        for (channel in listOf(ProtocolChannel.CONTROL, ProtocolChannel.BULK, ProtocolChannel.AUDIO)) {
            val gatewayKeys = gateway.keysFor(channel, NOW)
            val clientKeys = client.keysFor(channel, NOW)
            assertArrayEquals(
                "transmit/receive mismatch on ${channel.protocolName}",
                gatewayKeys.transmitKey(),
                clientKeys.receiveKey()
            )
        }

        // Distinct channels must not share a key.
        assertFalse(
            gateway.keysFor(ProtocolChannel.CONTROL, NOW).transmitKey()
                .contentEquals(gateway.keysFor(ProtocolChannel.BULK, NOW).transmitKey())
        )
    }

    @Test
    fun `a wrong code fails both ends`() {
        val gatewayRepo = FakeRepository(gatewayId, "Phone 1")
        val clientRepo = FakeRepository(clientId, "Phone 2")

        val (gateway, client) = run(
            gatewayRepo,
            clientRepo,
            FakePrompt(code),
            FakePrompt(PairingCode("000000"))
        )

        // The responder detects the mismatch and tells the initiator, so neither
        // side is left waiting and the initiator can say "wrong code".
        assertNotNull(client.error)
        assertNotNull(gateway.error)
        assertEquals(
            "pairing_code_mismatch",
            (client.error as SecureSessionNegotiator.NegotiationException).failureReason
        )

        // Nothing is persisted on a failed pairing.
        assertTrue(gatewayRepo.records.isEmpty())
        assertTrue(clientRepo.records.isEmpty())
    }

    @Test
    fun `a cancelled code entry aborts without storing anything`() {
        val clientRepo = FakeRepository(clientId, "Phone 2")
        val (_, client) = run(
            FakeRepository(gatewayId, "Phone 1"),
            clientRepo,
            FakePrompt(code),
            FakePrompt(null) // user dismissed the dialog
        )

        assertEquals(
            "pairing_cancelled",
            (client.error as SecureSessionNegotiator.NegotiationException).failureReason
        )
        assertTrue(clientRepo.records.isEmpty())
    }

    @Test
    fun `reconnecting uses the stored secret and never prompts`() {
        // Pair once...
        val (outcomes, gatewayRepo, clientRepo) = freshPair()
        assertNull(outcomes.first.error)

        // ...then reconnect with prompts that would fail if they were consulted.
        val gatewayPrompt = FakePrompt(null)
        val clientPrompt = FakePrompt(null)
        val (gateway, client) = run(gatewayRepo, clientRepo, gatewayPrompt, clientPrompt)

        assertNull("gateway reconnect failed: ${gateway.error}", gateway.error)
        assertNull("client reconnect failed: ${client.error}", client.error)
        assertFalse("the user must not be asked to pair again", clientPrompt.codeRequested)
        assertNull(gatewayPrompt.displayedCode)
        assertFalse(gateway.result!!.isNewPairing)
        assertEquals(1, gatewayRepo.touched)
    }

    @Test
    fun `reconnecting derives fresh session keys each time`() {
        val (first, gatewayRepo, clientRepo) = freshPair()
        val firstKey = first.first.result!!.keysFor(ProtocolChannel.CONTROL, NOW).transmitKey()

        val (gateway, _) = run(gatewayRepo, clientRepo, FakePrompt(null), FakePrompt(null))
        val secondKey = gateway.result!!.keysFor(ProtocolChannel.CONTROL, NOW).transmitKey()

        // Ephemeral ECDH each time, so a recorded session stays unreadable even if
        // the stored long-term secret leaks later.
        assertFalse(firstKey.contentEquals(secondKey))
    }

    @Test
    fun `an unverified short auth string still pairs but is recorded as unverified`() {
        val gatewayRepo = FakeRepository(gatewayId, "Phone 1")
        val clientRepo = FakeRepository(clientId, "Phone 2")

        val (gateway, _) = run(
            gatewayRepo,
            clientRepo,
            FakePrompt(code, sasAnswer = false),
            FakePrompt(code, sasAnswer = false)
        )

        assertNull(gateway.error)
        assertFalse(gateway.result!!.sasVerified)
        assertFalse(gatewayRepo.records[clientId]!!.sasVerified)
    }

    /**
     * Guards the constant relationship that caused a real pairing race.
     *
     * The responder does not read `pair.confirm` until after its code prompt
     * returns, so the initiator has to outwait the whole prompt while blocked
     * reading `pair.result`. When the two values were equal, a slow typist could
     * make the responder persist a pairing that the initiator had already given
     * up on — leaving the devices permanently disagreeing about whether they were
     * paired.
     */
    @Test
    fun `the pairing read timeout outlasts the prompt timeout`() {
        assertTrue(
            "pairing read timeout (${SecureSessionNegotiator.DEFAULT_PAIRING_TIMEOUT_MS}) must " +
                "exceed the prompt timeout (${SecureSessionNegotiator.PROMPT_TIMEOUT_MS})",
            SecureSessionNegotiator.DEFAULT_PAIRING_TIMEOUT_MS >
                SecureSessionNegotiator.PROMPT_TIMEOUT_MS
        )
        // And with enough headroom to cover key agreement and the write itself.
        assertTrue(
            SecureSessionNegotiator.DEFAULT_PAIRING_TIMEOUT_MS -
                SecureSessionNegotiator.PROMPT_TIMEOUT_MS >= 30_000
        )
    }

    @Test
    fun `diverged stored secrets report a desync, not a wrong code`() {
        // Both sides think they are already paired, but their stored secrets do
        // not match — the state a timeout race or a restored backup can leave.
        // Saying "wrong pairing code" here would be nonsense: nobody typed one.
        val gatewayRepo = FakeRepository(gatewayId, "Phone 1").apply {
            records[clientId] = PairingRecord(
                peerDeviceId = clientId,
                longTermSecret = CryptoPrimitives.randomBytes(32),
                peerName = "Phone 2",
                pairedAtMs = NOW,
                lastSeenAtMs = NOW
            )
        }
        val clientRepo = FakeRepository(clientId, "Phone 2").apply {
            records[gatewayId] = PairingRecord(
                peerDeviceId = gatewayId,
                longTermSecret = CryptoPrimitives.randomBytes(32), // different
                peerName = "Phone 1",
                pairedAtMs = NOW,
                lastSeenAtMs = NOW
            )
        }

        val (gateway, client) = run(gatewayRepo, clientRepo, FakePrompt(null), FakePrompt(null))

        assertEquals(
            SecureSessionNegotiator.REASON_DESYNCHRONISED,
            (client.error as SecureSessionNegotiator.NegotiationException).failureReason
        )
        assertNotNull(gateway.error)

        // The stored pairings are deliberately left alone: an attacker inside the
        // group could otherwise drop a good pairing just by failing confirmation.
        assertNotNull(gatewayRepo.records[clientId])
        assertNotNull(clientRepo.records[gatewayId])
    }

    @Test
    fun `a mistyped code reports a code mismatch, not a desync`() {
        val gatewayRepo = FakeRepository(gatewayId, "Phone 1")
        val clientRepo = FakeRepository(clientId, "Phone 2")

        val (_, client) = run(
            gatewayRepo, clientRepo, FakePrompt(code), FakePrompt(PairingCode("000000"))
        )

        assertEquals(
            SecureSessionNegotiator.REASON_CODE_MISMATCH,
            (client.error as SecureSessionNegotiator.NegotiationException).failureReason
        )
    }

    @Test
    fun `a stale stored pairing on one side only is reported as a mismatch`() {
        // The client was factory reset and no longer knows the gateway, but the
        // gateway still holds a record. The gateway will use its stored secret
        // while the client types a code, so confirmation must fail cleanly rather
        // than hang or half-succeed.
        val gatewayRepo = FakeRepository(gatewayId, "Phone 1").apply {
            records[clientId] = PairingRecord(
                peerDeviceId = clientId,
                longTermSecret = CryptoPrimitives.randomBytes(32),
                peerName = "Phone 2",
                pairedAtMs = NOW,
                lastSeenAtMs = NOW
            )
        }

        val (gateway, client) = run(
            gatewayRepo,
            FakeRepository(clientId, "Phone 2"),
            FakePrompt(code),
            FakePrompt(code)
        )

        assertNotNull(client.error)
        assertNotNull(gateway.error)
    }

    @Test
    fun `peer display names are carried across`() {
        val (outcomes, _, _) = freshPair()
        assertEquals("Phone 2", outcomes.first.result!!.peerName)
        assertEquals("Phone 1", outcomes.second.result!!.peerName)
    }

    @Test
    fun `an unexpected frame type is rejected`() {
        val (gatewayTransport, clientTransport) = PipeTransport.pair(ProtocolChannel.CONTROL)
        // Something that is not a pair.request arrives first.
        clientTransport.writeFrame(
            ProtocolFrame(
                type = com.jingcjie.wifi_direct_cable.protocol.ProtocolFrameType.HEARTBEAT_PING,
                channel = ProtocolChannel.CONTROL
            )
        )

        try {
            SecureSessionNegotiator(
                FakeRepository(gatewayId, "Phone 1"),
                FakePrompt(code),
                clock = { NOW }
            ).negotiate(gatewayTransport, SecureRole.INITIATOR, SESSION)
            fail("expected a NegotiationException")
        } catch (exception: SecureSessionNegotiator.NegotiationException) {
            assertEquals("pairing_unexpected_frame", exception.failureReason)
        }
    }

    private companion object {
        const val SESSION = "test-session"
        const val NOW = 1_700_000_000_000L
    }
}
