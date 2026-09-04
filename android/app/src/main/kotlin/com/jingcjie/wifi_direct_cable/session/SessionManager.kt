package com.jingcjie.wifi_direct_cable.session

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jingcjie.wifi_direct_cable.ReceiveDestinationManager
import com.jingcjie.wifi_direct_cable.WdCableRuntime
import com.jingcjie.wifi_direct_cable.audio.AudioCapabilities
import com.jingcjie.wifi_direct_cable.diagnostics.DiagnosticsLogger
import com.jingcjie.wifi_direct_cable.client.RemoteTelephonyController
import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.gateway.CallStateTracker
import com.jingcjie.wifi_direct_cable.gateway.GatewaySessionHandler
import com.jingcjie.wifi_direct_cable.gateway.GatewayStatus
import com.jingcjie.wifi_direct_cable.gateway.SmsGateway
import com.jingcjie.wifi_direct_cable.gateway.TelephonyGateway
import com.jingcjie.wifi_direct_cable.core.security.PairingPrompt
import com.jingcjie.wifi_direct_cable.core.security.PairingStore
import com.jingcjie.wifi_direct_cable.core.security.SecureChannel
import com.jingcjie.wifi_direct_cable.core.security.SecureRole
import com.jingcjie.wifi_direct_cable.core.security.SecureSessionNegotiator
import com.jingcjie.wifi_direct_cable.protocol.ProtocolChannel
import com.jingcjie.wifi_direct_cable.protocol.ProtocolConstants
import com.jingcjie.wifi_direct_cable.protocol.ProtocolError
import com.jingcjie.wifi_direct_cable.protocol.ProtocolException
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrame
import com.jingcjie.wifi_direct_cable.protocol.ProtocolFrameType
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

class SessionManager(
    private val context: Context,
    private val methodChannel: MethodChannel,
    private val transportAdapter: SessionTransportAdapter = SocketSessionTransportAdapter(),
    /**
     * Supplies the pairing code and the short-authentication-string confirmation.
     * Defaults to [PairingPrompt.REFUSING], which declines new pairings — so
     * until the Flutter UI is wired up, a device that has never paired stays on
     * the plaintext v2 channel instead of silently pairing with no user consent.
     */
    private val pairingPrompt: PairingPrompt = PairingPrompt.REFUSING
) {
    private data class Runtime(
        val generation: Int,
        val sessionId: String,
        val role: SessionRole,
        val peerAddress: String?,
        val transports: Map<ProtocolChannel, SessionTransport>,
        val peerCapabilities: MutableSet<String> = mutableSetOf(),
        val sequenceNumber: AtomicLong = AtomicLong(0),
        val lastFrameReceivedAt: AtomicLong = AtomicLong(System.currentTimeMillis())
    ) {
        val controlTransport: SessionTransport
            get() = transports.getValue(ProtocolChannel.CONTROL)

        val bulkTransport: SessionTransport
            get() = transports.getValue(ProtocolChannel.BULK)

        val transportRole: SessionTransportRole
            get() = role.transportRole()

        /**
         * Set once the v3 pairing exchange has completed and every transport has
         * been switched to authenticated framing. Null means the session is still
         * on the plaintext v2 channel, either because the peer does not speak v3
         * or because negotiation has not run yet.
         */
        @Volatile
        var secure: SecureState? = null
    }

    /** What survives a completed negotiation, for diagnostics and gating. */
    private class SecureState(
        val peerDeviceId: DeviceId,
        val peerName: String,
        val shortAuthString: String,
        val sasVerified: Boolean,
        val channels: Map<ProtocolChannel, SecureChannel>
    )

    private data class IncomingFileStream(
        val transferId: String,
        val fileName: String,
        val safeFileName: String,
        val expectedSize: Long,
        val file: File,
        val outputStream: FileOutputStream,
        val digest: MessageDigest,
        val startedAtMs: Long,
        var bytesReceived: Long = 0L
    )

    private data class IncomingSpeedStream(
        val testId: String,
        val expectedSize: Long,
        val startedAtMs: Long,
        var bytesReceived: Long = 0L
    )

    private val setupExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newCachedThreadPool()
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transportSetup = ProtocolV2TransportSetup(
        transportAdapter = transportAdapter,
        ioExecutor = ioExecutor,
        isCurrent = { expectedGeneration -> isCurrent(expectedGeneration) },
        emitDebug = { message -> methodChannel.invokeOnMain("onDebug", message) }
    )
    private val stateMachine = SessionStateMachine()
    private val generation = AtomicInteger(0)
    private val activeFileReceives = ConcurrentHashMap<Long, IncomingFileStream>()
    private val activeSpeedReceives = ConcurrentHashMap<Long, IncomingSpeedStream>()
    private val speedTestActive = AtomicBoolean(false)
    private val bulkSendLock = Any()
    private val receiveDestinationManager = ReceiveDestinationManager(context, methodChannel)

    /** Device identity and pairing records, wrapped under an Android Keystore key. */
    private val pairingStore = PairingStore(context)

    private val secureNegotiator = SecureSessionNegotiator(pairingStore, pairingPrompt)

    /**
     * Phone 1's cellular side. Created eagerly but inert: it registers no
     * listener and reads nothing until [TelephonyGateway.start] is called, and on
     * a device with no telephony hardware it simply advertises no capabilities.
     */
    private val telephonyGateway = TelephonyGateway(
        context,
        object : TelephonyGateway.Listener {
            override fun onCallEvent(event: CallStateTracker.CallEvent) {
                gatewayHandler.sendCallEvent(event)
            }

            override fun onStatusChanged(status: GatewayStatus) {
                // Only push to a peer that has authenticated; carrier and SIM
                // state are not for an unpaired device.
                if (runtime?.secure != null) {
                    gatewayHandler.sendStatus()
                }
            }
        }
    )

    /**
     * Phone 2's side. Present on both devices because either may act as the
     * client; it stays inert until a gateway actually reports a status.
     */
    private val remoteTelephony = RemoteTelephonyController(
        isSecure = { runtime?.secure != null },
        sendFrame = ::sendGatewayFrame,
        listener = object : RemoteTelephonyController.Listener {
            override fun onGatewayStatus(status: com.jingcjie.wifi_direct_cable.gateway.GatewayStatus) {
                methodChannel.invokeOnMain("onGatewayStatus", statusToMap(status))
            }

            override fun onCallState(snapshot: RemoteTelephonyController.CallSnapshot) {
                methodChannel.invokeOnMain("onRemoteCallState", snapshot.toMap())
            }

            override fun onSmsList(
                conversations: List<Map<String, Any?>>,
                messages: List<Map<String, Any?>>
            ) {
                methodChannel.invokeOnMain(
                    "onSmsList",
                    mapOf("conversations" to conversations, "messages" to messages)
                )
            }

            override fun onSmsEvent(
                requestId: String?,
                status: String,
                reason: String?,
                segments: Int?
            ) {
                methodChannel.invokeOnMain(
                    "onSmsEvent",
                    mapOf(
                        "requestId" to requestId,
                        "status" to status,
                        "reason" to reason,
                        "segments" to segments
                    )
                )
            }
        }
    )

    // Lazy so the mutual reference with telephonyGateway (whose listener calls
    // back into this handler) has no initialisation-order question at all.
    private val gatewayHandler: GatewaySessionHandler by lazy {
        GatewaySessionHandler(
            gateway = telephonyGateway,
            sms = SmsGateway(context),
            isSecure = { runtime?.secure != null },
            sendFrame = ::sendGatewayFrame,
            gatewayVersion = appVersion(),
            deviceName = Build.MODEL ?: "Android"
        )
    }

    @Volatile
    private var fileTransferCleanup: ((String) -> Unit)? = null

    @Volatile
    private var audioHandler: AudioSessionHandler? = null

    @Volatile
    private var runtime: Runtime? = null

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var currentRole: SessionRole? = null

    @Volatile
    private var groupOwnerAddress: String? = null

    @Volatile
    private var lastDisconnectReason: String? = null

    @Volatile
    var lastRecoveryReason: String? = null
        private set

    @Volatile
    private var heartbeatFuture: ScheduledFuture<*>? = null

    fun updateConnectionInfo(groupFormed: Boolean, isGroupOwner: Boolean, ownerAddress: String?) {
        if (!groupFormed) {
            disconnect("wifi_direct_disconnected")
            return
        }

        val role = if (isGroupOwner) SessionRole.GROUP_OWNER else SessionRole.CLIENT
        val normalizedOwnerAddress = ownerAddress?.takeIf { it.isNotBlank() }
        if (role == SessionRole.CLIENT && normalizedOwnerAddress == null) {
            failWithoutRuntime("missing_group_owner_address", "Wi-Fi Direct did not provide a group owner address")
            return
        }

        val currentPhase = stateMachine.phase
        if (isSameWifiDirectGroup(role, normalizedOwnerAddress) && currentPhase.keepsActiveSession()) {
            DiagnosticsLogger.log(
                "session",
                "Ignoring duplicate Wi-Fi Direct connection info",
                mapOf(
                    "sessionId" to currentSessionId,
                    "role" to role.eventName,
                    "transportRole" to role.transportRole().eventName,
                    "peer" to normalizedOwnerAddress,
                    "phase" to currentPhase.eventName
                )
            )
            methodChannel.invokeOnMain("onDebug", "Wi-Fi Direct connection info unchanged; keeping WDCable session")
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val newGeneration = generation.incrementAndGet()
        audioHandler?.onSessionEnded("wifi_direct_reconnected")
        fileTransferCleanup?.invoke("wifi_direct_reconnected")
        closeRuntimeOnly()
        transportAdapter.cancel()
        stopHeartbeat()
        cleanupIncomingBulkStreams(deletePartialFiles = true)
        speedTestActive.set(false)
        stateMachine.reset(SessionPhase.DISCONNECTED)

        currentSessionId = sessionId
        currentRole = role
        groupOwnerAddress = normalizedOwnerAddress
        lastDisconnectReason = null
        lastRecoveryReason = null

        emitState(SessionPhase.WIFI_DIRECT_CONNECTED, sessionId, role)
        DiagnosticsLogger.log(
            "session",
            "Wi-Fi Direct connected",
            mapOf(
                "sessionId" to sessionId,
                "role" to role.eventName,
                "transportRole" to role.transportRole().eventName,
                "peer" to normalizedOwnerAddress
            )
        )

        setupExecutor.execute {
            setupSession(newGeneration, sessionId, role, normalizedOwnerAddress)
        }
    }

    fun disconnect(reason: String = "local_disconnect") {
        generation.incrementAndGet()
        lastDisconnectReason = reason
        val sessionId = currentSessionId
        val role = currentRole

        transitionForCleanup(SessionPhase.DISCONNECTING, sessionId, role, reason)
        audioHandler?.onSessionEnded(reason)
        // Drop the peer's gateway state so a reconnect never shows a stale SIM,
        // signal, or in-progress call from the previous session.
        remoteTelephony.reset()
        telephonyGateway.stop()
        stopHeartbeat()
        fileTransferCleanup?.invoke(reason)
        closeRuntimeOnly()
        transportAdapter.cancel()
        runtime = null
        transitionForCleanup(SessionPhase.DISCONNECTED, sessionId, role, reason)
        emitDisconnectReason(reason, sessionId)
        cleanupIncomingBulkStreams(deletePartialFiles = true)
        speedTestActive.set(false)
        WdCableRuntime.stopConnectionService(context)
        DiagnosticsLogger.log(
            "session",
            "Disconnected",
            mapOf("sessionId" to sessionId, "reason" to reason)
        )
    }

    fun cleanup() {
        disconnect("app_destroyed")
        telephonyGateway.shutdown()
        setupExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        heartbeatExecutor.shutdownNow()
    }

    fun registerAudioHandler(handler: AudioSessionHandler) {
        audioHandler = handler
    }

    fun registerFileTransferCleanup(callback: (String) -> Unit) {
        fileTransferCleanup = callback
    }

    fun isReady(): Boolean = runtime != null && stateMachine.phase == SessionPhase.READY

    // --- Remote SIM gateway, exposed to Flutter -----------------------------

    fun requestGatewayStatus(): Boolean = remoteTelephony.requestStatus()

    /** @return the request id, or null if refused locally (the UI is told why). */
    fun placeRemoteCall(number: String?): String? = remoteTelephony.placeCall(number)

    fun answerRemoteCall(): Boolean = remoteTelephony.answerCall()

    fun rejectRemoteCall(): Boolean = remoteTelephony.rejectCall()

    fun hangUpRemoteCall(): Boolean = remoteTelephony.hangUp()

    /** False when the gateway cannot read SMS, which is the common case. */
    fun requestSmsList(limit: Int): Boolean = remoteTelephony.requestSmsList(limit)

    // --- Pairing management -------------------------------------------------

    /** Paired peers, without any key material. */
    fun pairedDevices(): List<Map<String, Any?>> = pairingStore.pairedDevicesSummary()

    /**
     * Forgets a peer, then drops the session if it was the connected one.
     *
     * Disconnecting matters: the point of forgetting a device is to stop trusting
     * it, and leaving the established session running would keep an authenticated
     * channel open to a peer the user just revoked. The next connection starts a
     * fresh pairing.
     *
     * This is also the only recovery path from a `pairing_desynchronised` failure,
     * where the two devices' stored secrets have diverged and no code the user
     * types can succeed until one side forgets the other.
     */
    fun forgetPairing(deviceIdText: String?): Boolean {
        val deviceId = DeviceId.parseOrNull(deviceIdText) ?: return false
        val connectedPeer = runtime?.secure?.peerDeviceId
        pairingStore.removePairing(deviceId)
        if (connectedPeer == deviceId) {
            disconnect("pairing_forgotten")
        }
        return true
    }

    /** Forgets every pairing and this device's identity. Always disconnects. */
    fun forgetAllPairings() {
        pairingStore.clearAll()
        disconnect("pairings_cleared")
    }

    /** @return the request id, or null if refused locally. */
    fun sendRemoteSms(address: String?, body: String?): String? =
        remoteTelephony.sendSms(address, body)

    /** Last status the gateway reported, or null if it has not reported one. */
    fun lastGatewayStatus(): Map<String, Any?>? =
        remoteTelephony.lastStatus()?.let(::statusToMap)

    /** This device's own cellular state, for the gateway-side UI. */
    fun localGatewayStatus(): Map<String, Any?> =
        statusToMap(telephonyGateway.readStatus(appVersion(), Build.MODEL ?: "Android"))

    private fun statusToMap(
        status: com.jingcjie.wifi_direct_cable.gateway.GatewayStatus
    ): Map<String, Any?> = mapOf(
        "simState" to status.simState,
        "callState" to status.callState,
        "carrier" to status.carrier,
        "networkType" to status.networkType,
        "signalLevel" to status.signalLevel,
        "mobileData" to status.mobileDataState,
        "batteryPercent" to status.batteryPercent,
        "charging" to status.charging,
        "gatewayVersion" to status.gatewayVersion,
        "deviceName" to status.deviceName,
        "capabilities" to status.capabilities,
        "unavailable" to status.unavailableReasons
    )

    fun getReceiveDestination(): ReceiveDestinationManager.Destination =
        receiveDestinationManager.current()

    fun setReceiveDestination(mode: String): ReceiveDestinationManager.Destination =
        receiveDestinationManager.setMode(mode)

    fun saveCustomReceiveDestination(
        uri: Uri,
        displayName: String
    ): ReceiveDestinationManager.Destination =
        receiveDestinationManager.saveCustomFolder(uri, displayName)

    fun clearSpeedTestState() {
        speedTestActive.set(false)
    }

    fun getConnectionStats(): Map<String, Any> {
        val phase = stateMachine.phase
        val activeRuntime = runtime
        return mapOf(
            "isConnected" to (phase != SessionPhase.DISCONNECTED && phase != SessionPhase.FAILED),
            "isGroupOwner" to (currentRole == SessionRole.GROUP_OWNER),
            "transportRole" to (currentRole?.transportRole()?.eventName ?: ""),
            "groupOwnerAddress" to (groupOwnerAddress ?: ""),
            "sessionState" to phase.eventName,
            "sessionId" to (currentSessionId ?: ""),
            "isReady" to (phase == SessionPhase.READY),
            "disconnectReason" to (lastDisconnectReason ?: ""),
            "lastRecoveryReason" to (lastRecoveryReason ?: ""),
            "controlChannelOpen" to (activeRuntime?.transports?.containsKey(ProtocolChannel.CONTROL) == true),
            "bulkChannelOpen" to (activeRuntime?.transports?.containsKey(ProtocolChannel.BULK) == true)
        ) + secureStats(activeRuntime)
    }

    /**
     * Security state for the diagnostics page. Reports the honest state rather
     * than a reassuring one: an unsecured session says so, so the UI can show
     * that the link is plaintext instead of implying encryption that is not there.
     *
     * Contains no key material — only counters and the peer's short label.
     */
    private fun secureStats(activeRuntime: Runtime?): Map<String, Any> {
        val secure = activeRuntime?.secure
            ?: return mapOf(
                "secureActive" to false,
                "securePeerDeviceId" to "",
                "secureSasVerified" to false,
                "localDeviceId" to pairingStore.localDeviceId().shortLabel()
            )

        val control = secure.channels[ProtocolChannel.CONTROL]
        return mapOf(
            "secureActive" to true,
            "securePeerDeviceId" to secure.peerDeviceId.shortLabel(),
            "securePeerName" to secure.peerName,
            "secureShortAuthString" to secure.shortAuthString,
            "secureSasVerified" to secure.sasVerified,
            "localDeviceId" to pairingStore.localDeviceId().shortLabel(),
            "secureFramesSent" to (control?.framesSent() ?: 0L),
            "secureFramesReceived" to (control?.framesReceived() ?: 0L),
            "secureFramesRejected" to (control?.framesRejected() ?: 0L),
            "secureDuplicatesDropped" to (control?.duplicatesDropped() ?: 0L),
            "secureReplaysDropped" to (control?.replaysDropped() ?: 0L)
        )
    }

    fun replayCurrentState() {
        val phase = stateMachine.phase
        emitState(phase, currentSessionId, currentRole, lastDisconnectReason)
        val activeRuntime = runtime
        if (phase == SessionPhase.READY && activeRuntime != null) {
            emitSessionReady(activeRuntime)
        }
    }

    fun getAudioSessionInfo(): AudioSessionInfo? {
        val activeRuntime = runtime ?: return null
        if (stateMachine.phase != SessionPhase.READY) return null
        return AudioSessionInfo(
            sessionId = activeRuntime.sessionId,
            role = activeRuntime.role,
            transportRole = activeRuntime.transportRole,
            peerAddress = activeRuntime.peerAddress,
            peerCapabilities = activeRuntime.peerCapabilities.toSet()
        )
    }

    fun sendAudioControl(metadata: JSONObject) {
        val activeRuntime = runtime ?: throw IOException("The WDCable session is not ready")
        activeRuntime.controlTransport.writeFrame(
            ProtocolFrame(
                type = ProtocolFrameType.CONTROL_MESSAGE,
                channel = ProtocolChannel.CONTROL,
                streamId = metadata.optLong("streamId", 0L),
                sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                correlationId = UUID.randomUUID(),
                metadataJson = metadata
                    .put("sessionId", activeRuntime.sessionId)
                    .put("timestamp", System.currentTimeMillis())
                    .toString()
            )
        )
    }

    fun sendAudioError(streamId: Long, code: String, message: String) {
        val activeRuntime = runtime ?: return
        sendFeatureError(activeRuntime, streamId, code, message, "audio")
    }

    fun sendFileStream(
        transferId: String,
        fileName: String,
        sizeBytes: Long,
        inputStream: InputStream,
        shouldCancel: () -> Boolean,
        onProgress: (Long) -> Unit
    ): FileSendResult {
        val activeRuntime = runtime
        if (activeRuntime == null || stateMachine.phase != SessionPhase.READY) {
            throw IOException("The WDCable session is not ready")
        }
        return sendFileStreamInternal(
            activeRuntime = activeRuntime,
            transferId = transferId,
            fileName = fileName,
            sizeBytes = sizeBytes,
            inputStream = inputStream,
            shouldCancel = shouldCancel,
            onProgress = onProgress
        )
    }

    fun requestSpeedTestData(sizeBytes: Long, result: MethodChannel.Result) {
        val activeRuntime = readyRuntimeOrError(result) ?: return
        if (!speedTestActive.compareAndSet(false, true)) {
            result.error("SPEED_TEST_BUSY", "A speed test is already running", null)
            return
        }

        ioExecutor.execute {
            try {
                val testId = UUID.randomUUID().toString()
                val streamId = nextStreamId()
                val metadata = JSONObject()
                    .put("kind", "speed-request")
                    .put("testId", testId)
                    .put("sessionId", activeRuntime.sessionId)
                    .put("sizeBytes", sizeBytes)
                    .put("timestamp", System.currentTimeMillis())

                activeRuntime.bulkTransport.writeFrame(
                    ProtocolFrame(
                        type = ProtocolFrameType.BULK_START,
                        channel = ProtocolChannel.BULK,
                        streamId = streamId,
                        sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                        correlationId = UUID.fromString(testId),
                        metadataJson = metadata.toString()
                    )
                )
                DiagnosticsLogger.log(
                    "speed",
                    "Speed download requested",
                    mapOf("sessionId" to activeRuntime.sessionId, "testId" to testId, "sizeBytes" to sizeBytes)
                )
                mainHandler.post {
                    result.success("Speed test data request sent: $sizeBytes bytes")
                }
            } catch (exception: Exception) {
                speedTestActive.set(false)
                mainHandler.post {
                    result.error("REQUEST_FAILED", "Failed to request speed test data: ${exception.message}", null)
                }
            }
        }
    }

    fun sendSpeedTestData(sizeBytes: Long, result: MethodChannel.Result) {
        val activeRuntime = readyRuntimeOrError(result) ?: return
        if (!speedTestActive.compareAndSet(false, true)) {
            result.error("SPEED_TEST_BUSY", "A speed test is already running", null)
            return
        }

        ioExecutor.execute {
            try {
                sendSpeedPayload(activeRuntime, UUID.randomUUID().toString(), sizeBytes, emitLocalProgress = true)
                mainHandler.post {
                    result.success("Speed test data sent: $sizeBytes bytes")
                }
            } catch (exception: Exception) {
                speedTestActive.set(false)
                mainHandler.post {
                    result.error("SEND_FAILED", "Failed to send speed test data: ${exception.message}", null)
                }
            }
        }
    }

    fun sendChatMessage(message: String, result: MethodChannel.Result) {
        val activeRuntime = readyRuntimeOrError(result) ?: return

        ioExecutor.execute {
            try {
                val timestamp = System.currentTimeMillis()
                val messageId = UUID.randomUUID().toString()
                val metadata = JSONObject()
                    .put("kind", "chat")
                    .put("messageId", messageId)
                    .put("timestamp", timestamp)
                    .put("senderPlatform", "android")
                    .put("sessionId", activeRuntime.sessionId)

                activeRuntime.controlTransport.writeFrame(
                    ProtocolFrame(
                        type = ProtocolFrameType.CONTROL_MESSAGE,
                        channel = ProtocolChannel.CONTROL,
                        streamId = 0L,
                        sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                        correlationId = UUID.fromString(messageId),
                        metadataJson = metadata.toString(),
                        payload = message.toByteArray(Charsets.UTF_8)
                    )
                )
                DiagnosticsLogger.log(
                    "chat",
                    "Chat message sent",
                    mapOf("sessionId" to activeRuntime.sessionId, "messageId" to messageId)
                )
                mainHandler.post {
                    result.success("Message sent")
                }
            } catch (exception: Exception) {
                methodChannel.invokeOnMain("onError", "Chat send failed: ${exception.message}")
                mainHandler.post {
                    result.error("SEND_FAILED", "Failed to send chat message: ${exception.message}", null)
                }
            }
        }
    }

    private fun setupSession(
        expectedGeneration: Int,
        sessionId: String,
        role: SessionRole,
        ownerAddress: String?
    ) {
        val openedTransports = linkedMapOf<ProtocolChannel, SessionTransport>()
        try {
            if (!isCurrent(expectedGeneration)) return
            val setupDeadlineMs = transportSetup.deadlineFromNow()

            emitState(SessionPhase.CONNECTING_TRANSPORT, sessionId, role)
            val opened = transportSetup.openTransports(role, ownerAddress, expectedGeneration, sessionId, setupDeadlineMs)
            openedTransports.putAll(opened.transports)
            if (!isCurrent(expectedGeneration)) {
                closeTransports(openedTransports.values)
                return
            }

            val newRuntime = Runtime(
                generation = expectedGeneration,
                sessionId = sessionId,
                role = role,
                peerAddress = opened.peerAddress,
                transports = openedTransports.toMap()
            )

            emitState(SessionPhase.HANDSHAKING, sessionId, role)
            performHandshake(newRuntime)
            newRuntime.transports.values.forEach { it.setReadTimeout(0) }

            if (!isCurrent(expectedGeneration)) {
                closeTransports(newRuntime.transports.values)
                return
            }

            runtime = newRuntime
            emitState(SessionPhase.READY, sessionId, role)
            emitSessionReady(newRuntime)
            startControlReadLoop(newRuntime)
            startBulkReadLoop(newRuntime)
            startHeartbeat(newRuntime)
            DiagnosticsLogger.log(
                "session",
                "Session ready",
                mapOf(
                    "sessionId" to sessionId,
                    "role" to role.eventName,
                    "transportRole" to role.transportRole().eventName,
                    "remoteEndpoint" to (opened.peerAddress ?: "")
                )
            )
            WdCableRuntime.startConnectionService(context)
        } catch (exception: TransportSetupException) {
            closeTransports(openedTransports.values)
            failSession(expectedGeneration, sessionId, role, exception.failureReason, exception, false)
        } catch (exception: PeerProtocolMissingException) {
            closeTransports(openedTransports.values)
            failSession(
                expectedGeneration,
                sessionId,
                role,
                exception.failureReason,
                exception,
                exception.failureReason == "protocol_mismatch"
            )
        } catch (exception: ProtocolException) {
            closeTransports(openedTransports.values)
            val reason = when (exception.error) {
                ProtocolError.UNSUPPORTED_VERSION,
                ProtocolError.MALFORMED_MAGIC -> "protocol_mismatch"
                else -> "protocol_mismatch"
            }
            failSession(expectedGeneration, sessionId, role, reason, exception, true)
        } catch (exception: Exception) {
            closeTransports(openedTransports.values)
            val reason = if (isCurrent(expectedGeneration)) "tcp_connect_timeout" else "cancelled"
            failSession(expectedGeneration, sessionId, role, reason, exception, false)
        }
    }

    private fun connectWithRetry(
        channel: ProtocolChannel,
        host: String,
        port: Int,
        expectedGeneration: Int
    ): SessionTransport {
        var attempt = 1
        var lastError: Exception? = null
        while (attempt <= MAX_CONNECT_ATTEMPTS && isCurrent(expectedGeneration)) {
            try {
                methodChannel.invokeOnMain(
                    "onDebug",
                    "Connecting ${channel.protocolName} channel to $host:$port (attempt $attempt)"
                )
                return transportAdapter.connect(channel, host, port)
            } catch (exception: Exception) {
                lastError = exception
                Thread.sleep(connectRetryDelayMs(attempt))
                attempt++
            }
        }
        throw lastError ?: IOException("Connection cancelled")
    }

    private fun performHandshake(activeRuntime: Runtime) {
        val control = activeRuntime.controlTransport
        control.setReadTimeout(HANDSHAKE_TIMEOUT_MS)

        try {
            if (activeRuntime.role == SessionRole.CLIENT) {
                DiagnosticsLogger.log(
                    "protocol",
                    "Sending handshake hello",
                    mapOf(
                        "sessionId" to activeRuntime.sessionId,
                        "role" to activeRuntime.role.eventName,
                        "transportRole" to activeRuntime.transportRole.eventName
                    )
                )
                control.writeFrame(buildHandshakeHello(activeRuntime))
                val ack = control.readFrame() ?: throw PeerProtocolMissingException("Peer closed before handshake ack")
                if (ack.type != ProtocolFrameType.HANDSHAKE_ACK) {
                    throw PeerProtocolMissingException("Expected handshake ack, received ${ack.type.protocolName}")
                }
                activeRuntime.peerCapabilities.addAll(validateHandshakeAck(ack.metadataJson))
                DiagnosticsLogger.log(
                    "protocol",
                    "Handshake ack accepted",
                    mapOf(
                        "sessionId" to activeRuntime.sessionId,
                        "role" to activeRuntime.role.eventName,
                        "transportRole" to activeRuntime.transportRole.eventName
                    )
                )
            } else {
                val hello = control.readFrame() ?: throw PeerProtocolMissingException("Peer closed before handshake hello")
                if (hello.type != ProtocolFrameType.HANDSHAKE_HELLO) {
                    throw PeerProtocolMissingException("Expected handshake hello, received ${hello.type.protocolName}")
                }
                activeRuntime.peerCapabilities.addAll(validateHandshakeHello(hello.metadataJson))
                DiagnosticsLogger.log(
                    "protocol",
                    "Handshake hello accepted",
                    mapOf(
                        "sessionId" to activeRuntime.sessionId,
                        "role" to activeRuntime.role.eventName,
                        "transportRole" to activeRuntime.transportRole.eventName
                    )
                )
                control.writeFrame(buildHandshakeAck(activeRuntime))
            }
        } catch (exception: SocketTimeoutException) {
            throw PeerProtocolMissingException(
                "Timed out waiting for WDCable handshake",
                exception,
                "handshake_timeout"
            )
        } catch (exception: org.json.JSONException) {
            throw PeerProtocolMissingException("Invalid handshake metadata", exception)
        }

        // Runs for both call sites of performHandshake, so a reconnect is secured
        // exactly like a first connect.
        negotiateSecurity(activeRuntime)
    }

    /**
     * Upgrades the session to the v3 authenticated framing, if both peers offer
     * it.
     *
     * Must run immediately after the capability handshake and before any
     * application frame is exchanged, because both peers switch codecs at the
     * same point in the byte stream.
     *
     * A peer that does not advertise [ProtocolConstants.CAPABILITY_SECURE_V3] is
     * not an error: the session continues on the plaintext v2 channel with the
     * existing feature set. What such a session must never be given is the
     * gateway or telephony channels — placing calls on a SIM over an
     * unauthenticated link is exactly the risk this layer exists to remove.
     */
    private fun negotiateSecurity(activeRuntime: Runtime) {
        val bothSupport =
            ProtocolConstants.CAPABILITY_SECURE_V3 in securityCapabilities() &&
                activeRuntime.peerCapabilities.contains(ProtocolConstants.CAPABILITY_SECURE_V3)
        if (!bothSupport) {
            DiagnosticsLogger.log(
                "security",
                "v3 not offered by both peers; staying on the plaintext channel",
                mapOf(
                    "sessionId" to activeRuntime.sessionId,
                    "role" to activeRuntime.role.eventName,
                    "localOffersV3" to (ProtocolConstants.CAPABILITY_SECURE_V3 in securityCapabilities()),
                    "peerOffersV3" to activeRuntime.peerCapabilities.contains(ProtocolConstants.CAPABILITY_SECURE_V3)
                )
            )
            return
        }

        val role = SecureRole.forGroupOwner(activeRuntime.role == SessionRole.GROUP_OWNER)
        val negotiation = try {
            secureNegotiator.negotiate(
                transport = activeRuntime.controlTransport,
                role = role,
                sessionId = activeRuntime.sessionId
            )
        } catch (exception: SecureSessionNegotiator.NegotiationException) {
            // Dismiss any pairing dialog before unwinding, or the gateway would
            // sit showing its code forever after a failed attempt.
            pairingPrompt.onPairingFinished(paired = false)
            // Re-thrown as the session layer's typed failure so the existing
            // teardown path reports the real reason ("pairing_code_mismatch")
            // instead of the generic catch-all mapping it to a connect timeout.
            throw PeerProtocolMissingException(
                exception.message ?: "Secure negotiation failed",
                exception,
                exception.failureReason
            )
        }

        try {
            val now = System.currentTimeMillis()
            val localDeviceId = pairingStore.localDeviceId()
            val secureChannels = LinkedHashMap<ProtocolChannel, SecureChannel>()

            // Every channel gets its own key and its own nonce counter.
            //
            // SCOPE: activeRuntime.transports holds only CONTROL and BULK — the two
            // TCP channels opened by ProtocolV2TransportSetup.channelPorts(). The
            // Audio Link is NOT here: it runs on its own RTP/RTCP UDP sockets
            // (ports 8990/8991) in AudioService and never becomes a
            // SessionTransport, so it is NOT covered by the v3 secure framing.
            // Microphone audio therefore has only the Wi-Fi Direct group's
            // link-layer WPA2 protection. See docs/ANDROID_LIMITATIONS.md.
            for ((channel, transport) in activeRuntime.transports) {
                val secureChannel = SecureChannel(localDeviceId)
                secureChannel.activate(negotiation.keysFor(channel, now), negotiation.peerDeviceId)
                transport.activateSecurity(secureChannel)
                secureChannels[channel] = secureChannel
            }

            activeRuntime.secure = SecureState(
                peerDeviceId = negotiation.peerDeviceId,
                peerName = negotiation.peerName,
                shortAuthString = negotiation.shortAuthString,
                sasVerified = negotiation.sasVerified,
                channels = secureChannels
            )

            DiagnosticsLogger.log(
                "security",
                "Secure channel active",
                mapOf(
                    "sessionId" to activeRuntime.sessionId,
                    "peerDeviceId" to negotiation.peerDeviceId.shortLabel(),
                    "channels" to secureChannels.keys.joinToString(",") { it.protocolName },
                    "sasVerified" to negotiation.sasVerified
                )
            )
            // Only start listening for call state once the peer is authenticated;
            // there is no reason to hold a telephony listener for a session that
            // is not allowed to use it.
            telephonyGateway.start()

            // Deliberately NOT pushing an initial status here. This runs inside
            // performHandshake, which happens *before* `runtime = newRuntime`, so
            // sendGatewayFrame() would find a null runtime and drop the frame —
            // or, on a reconnect, write it to the previous session's closed
            // transport. The client pulls the first status itself (the Dialer and
            // Messages tabs both call requestGatewayStatus on connect), and every
            // later change is pushed from the telephony listener once `runtime` is
            // set, so nothing is lost by leaving it out.
        } finally {
            pairingPrompt.onPairingFinished(paired = true)
            // Wipes the ephemeral handshake material now that every channel is
            // keyed. The per-channel keys live on inside each SecureChannel.
            negotiation.close()
        }
    }

    private fun validateHandshakeHello(metadataJson: String): Set<String> {
        val metadata = JSONObject(metadataJson)
        if (metadata.optString("appId") != ProtocolConstants.APP_ID) {
            throw PeerProtocolMissingException("Peer app id is not WDCable")
        }
        val protocolMin = metadata.optInt("protocolMin", -1)
        val protocolMax = metadata.optInt("protocolMax", -1)
        if (ProtocolConstants.VERSION !in protocolMin..protocolMax) {
            throw ProtocolException(
                ProtocolError.UNSUPPORTED_VERSION,
                "Peer supports protocol $protocolMin..$protocolMax"
            )
        }
        validateHandshakeRoles(
            metadata,
            expectedRole = SessionRole.CLIENT,
            expectedTransportRole = SessionTransportRole.LISTENER
        )
        return capabilitiesFrom(metadata)
    }

    private fun validateHandshakeAck(metadataJson: String): Set<String> {
        val metadata = JSONObject(metadataJson)
        if (metadata.optString("appId") != ProtocolConstants.APP_ID) {
            throw PeerProtocolMissingException("Peer app id is not WDCable")
        }
        if (metadata.optInt("protocolVersion", -1) != ProtocolConstants.VERSION) {
            throw ProtocolException(
                ProtocolError.UNSUPPORTED_VERSION,
                "Peer selected unsupported protocol ${metadata.optInt("protocolVersion", -1)}"
            )
        }
        validateHandshakeRoles(
            metadata,
            expectedRole = SessionRole.GROUP_OWNER,
            expectedTransportRole = SessionTransportRole.CONNECTOR
        )
        return capabilitiesFrom(metadata)
    }

    private fun validateHandshakeRoles(
        metadata: JSONObject,
        expectedRole: SessionRole,
        expectedTransportRole: SessionTransportRole
    ) {
        val peerRole = metadata.optString("role")
        val peerTransportRole = metadata.optString("transportRole")
        if (peerRole != expectedRole.eventName || peerTransportRole != expectedTransportRole.eventName) {
            throw PeerProtocolMissingException(
                "Peer role mismatch: role=$peerRole transportRole=$peerTransportRole"
            )
        }
    }

    private fun capabilitiesFrom(metadata: JSONObject): Set<String> {
        val capabilities = metadata.optJSONArray("capabilities") ?: return emptySet()
        return buildSet {
            for (index in 0 until capabilities.length()) {
                val capability = capabilities.optString(index)
                if (capability.isNotBlank()) {
                    add(capability)
                }
            }
        }
    }

    private fun buildHandshakeHello(activeRuntime: Runtime): ProtocolFrame {
        return ProtocolFrame(
            type = ProtocolFrameType.HANDSHAKE_HELLO,
            channel = ProtocolChannel.CONTROL,
            sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
            correlationId = UUID.randomUUID(),
            metadataJson = baseHandshakeMetadata(activeRuntime)
                .put("protocolMin", ProtocolConstants.VERSION)
                .put("protocolMax", ProtocolConstants.VERSION)
                .toString()
        )
    }

    private fun buildHandshakeAck(activeRuntime: Runtime): ProtocolFrame {
        return ProtocolFrame(
            type = ProtocolFrameType.HANDSHAKE_ACK,
            channel = ProtocolChannel.CONTROL,
            sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
            correlationId = UUID.randomUUID(),
            metadataJson = baseHandshakeMetadata(activeRuntime)
                .put("protocolVersion", ProtocolConstants.VERSION)
                .toString()
        )
    }

    private fun baseHandshakeMetadata(activeRuntime: Runtime): JSONObject {
        return JSONObject()
            .put("appId", ProtocolConstants.APP_ID)
            .put("platform", "android")
            .put("appVersion", appVersion())
            .put("deviceName", Build.MODEL ?: "Android")
            .put("role", activeRuntime.role.eventName)
            .put("transportRole", activeRuntime.transportRole.eventName)
            .put("sessionId", activeRuntime.sessionId)
            .put("capabilities", capabilitiesJson())
            .put("channels", channelsJson())
    }

    private fun capabilitiesJson(): JSONArray {
        return JSONArray().also { array -> localCapabilitiesList().forEach(array::put) }
    }

    /**
     * The v3 secure framing is advertised only when this device can actually
     * complete a pairing — that is, when a real [PairingPrompt] is wired up to
     * the UI.
     *
     * Doing it through the capability handshake, rather than attempting the
     * exchange and backing out, is what keeps the two peers in step. Both sides
     * decide before a single pairing frame is sent, so there is no point at which
     * one has switched codecs and the other has not. Abandoning the exchange
     * halfway is not recoverable: the peers would disagree about how to parse the
     * next frame on the wire.
     */
    private fun securityCapabilities(): List<String> =
        if (pairingPrompt === PairingPrompt.REFUSING) {
            emptyList()
        } else {
            listOf(ProtocolConstants.CAPABILITY_SECURE_V3, ProtocolConstants.CAPABILITY_PAIRING)
        }

    private fun channelsJson(): JSONObject {
        val channels = JSONObject()
        for ((channel, port) in channelPorts()) {
            channels.put(
                channel.protocolName,
                JSONObject()
                    .put("transport", "tcp")
                    .put("port", port)
            )
        }
        return channels
    }

    private fun startControlReadLoop(activeRuntime: Runtime) {
        ioExecutor.execute {
            while (isCurrent(activeRuntime.generation) && stateMachine.phase == SessionPhase.READY) {
                try {
                    val frame = activeRuntime.controlTransport.readFrame()
                        ?: throw IOException("Control channel closed by peer")
                    activeRuntime.lastFrameReceivedAt.set(System.currentTimeMillis())
                    handleControlFrame(activeRuntime, frame)
                } catch (exception: Exception) {
                    if (isCurrent(activeRuntime.generation) && stateMachine.phase == SessionPhase.READY) {
                        degradeReadySession(activeRuntime, "control_channel_failed", exception)
                    }
                    return@execute
                }
            }
        }
    }

    private fun startBulkReadLoop(activeRuntime: Runtime) {
        ioExecutor.execute {
            while (isCurrent(activeRuntime.generation) && stateMachine.phase == SessionPhase.READY) {
                try {
                    val frame = activeRuntime.bulkTransport.readFrame()
                        ?: throw IOException("Bulk channel closed by peer")
                    handleBulkFrame(activeRuntime, frame)
                } catch (exception: Exception) {
                    if (isCurrent(activeRuntime.generation) && stateMachine.phase == SessionPhase.READY) {
                        degradeReadySession(activeRuntime, "bulk_channel_failed", exception)
                    }
                    return@execute
                }
            }
        }
    }

    private fun handleBulkFrame(activeRuntime: Runtime, frame: ProtocolFrame) {
        val metadata = if (frame.metadataJson.isBlank()) JSONObject() else JSONObject(frame.metadataJson)
        when (frame.type) {
            ProtocolFrameType.BULK_START -> handleBulkStart(activeRuntime, frame, metadata)
            ProtocolFrameType.BULK_CHUNK -> handleBulkChunk(frame, metadata)
            ProtocolFrameType.BULK_COMPLETE -> handleBulkComplete(activeRuntime, frame, metadata)
            ProtocolFrameType.BULK_CANCEL -> handleBulkCancel(frame, metadata)
            else -> DiagnosticsLogger.log(
                "bulk",
                "Ignoring unexpected bulk frame",
                mapOf("type" to frame.type.protocolName, "streamId" to frame.streamId)
            )
        }
    }

    private fun handleBulkStart(activeRuntime: Runtime, frame: ProtocolFrame, metadata: JSONObject) {
        when (metadata.optString("kind")) {
            "file" -> startIncomingFile(frame, metadata)
            "speed-data" -> {
                activeSpeedReceives[frame.streamId] = IncomingSpeedStream(
                    testId = metadata.optString("testId"),
                    expectedSize = metadata.optLong("sizeBytes", -1L),
                    startedAtMs = System.currentTimeMillis()
                )
            }
            "speed-request" -> {
                val requestedBytes = metadata.optLong("sizeBytes", 0L)
                val testId = metadata.optString("testId", UUID.randomUUID().toString())
                ioExecutor.execute {
                    try {
                        sendSpeedPayload(activeRuntime, testId, requestedBytes, emitLocalProgress = false)
                    } catch (exception: Exception) {
                        sendBulkError(activeRuntime, frame.streamId, "speed_send_failed", exception.message ?: "Speed send failed")
                    }
                }
            }
            else -> DiagnosticsLogger.log(
                "bulk",
                "Unknown bulk start kind",
                mapOf("kind" to metadata.optString("kind"), "streamId" to frame.streamId)
            )
        }
    }

    private fun startIncomingFile(frame: ProtocolFrame, metadata: JSONObject) {
        val transferId = metadata.optString("transferId", UUID.randomUUID().toString())
        val fileName = metadata.optString("fileName", "unknown_file")
        val expectedSize = metadata.optLong("sizeBytes", -1L)
        activeFileReceives.remove(frame.streamId)?.let { existing ->
            closeQuietly(existing.outputStream)
            existing.file.delete()
            emitFileTransferTerminal(
                method = "onFileTransferFailed",
                incoming = existing,
                status = "failed",
                error = "Duplicate transfer start for the same stream"
            )
        }
        val safeName = safeFileName(fileName)
        val partialFile = receiveDestinationManager.createPartialFile(transferId)
        partialFile.delete()
        val incoming = IncomingFileStream(
            transferId = transferId,
            fileName = fileName,
            safeFileName = safeName,
            expectedSize = expectedSize,
            file = partialFile,
            outputStream = FileOutputStream(partialFile),
            digest = MessageDigest.getInstance("SHA-256"),
            startedAtMs = System.currentTimeMillis()
        )
        activeFileReceives[frame.streamId] = incoming
        methodChannel.invokeOnMain(
            "onFileTransferStarted",
            fileTransferData(incoming, status = "transferring")
        )
        DiagnosticsLogger.log(
            "file",
            "Incoming file started",
            mapOf("transferId" to transferId, "fileName" to incoming.safeFileName, "sizeBytes" to expectedSize)
        )
    }

    private fun handleBulkChunk(frame: ProtocolFrame, metadata: JSONObject) {
        activeFileReceives[frame.streamId]?.let { incoming ->
            incoming.outputStream.write(frame.payload)
            incoming.digest.update(frame.payload)
            incoming.bytesReceived += frame.payload.size
            methodChannel.invokeOnMain(
                "onFileTransferProgress",
                fileTransferData(incoming, status = "transferring")
            )
            return
        }

        activeSpeedReceives[frame.streamId]?.let { incoming ->
            incoming.bytesReceived += frame.payload.size
            val elapsedMs = (System.currentTimeMillis() - incoming.startedAtMs).coerceAtLeast(1L)
            val speedMbps = calculateSpeedMbps(incoming.bytesReceived, elapsedMs)
            val progress = if (incoming.expectedSize > 0) {
                (incoming.bytesReceived.toDouble() / incoming.expectedSize.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            methodChannel.invokeOnMain(
                "onSpeedTestReceiveProgress",
                mapOf(
                    "bytesReceived" to incoming.bytesReceived,
                    "totalBytes" to incoming.expectedSize,
                    "speedMbps" to speedMbps,
                    "progress" to progress
                )
            )
            return
        }

        DiagnosticsLogger.log(
            "bulk",
            "Chunk for unknown stream",
            mapOf("streamId" to frame.streamId, "kind" to metadata.optString("kind"))
        )
    }

    private fun handleBulkComplete(activeRuntime: Runtime, frame: ProtocolFrame, metadata: JSONObject) {
        activeFileReceives.remove(frame.streamId)?.let { incoming ->
            completeIncomingFile(activeRuntime, frame, metadata, incoming)
            return
        }

        activeSpeedReceives.remove(frame.streamId)?.let { incoming ->
            val durationMs = (System.currentTimeMillis() - incoming.startedAtMs).coerceAtLeast(1L)
            val speedMbps = calculateSpeedMbps(incoming.bytesReceived, durationMs)
            methodChannel.invokeOnMain(
                "onSpeedTestDataReceived",
                mapOf(
                    "bytesReceived" to incoming.bytesReceived,
                    "durationMs" to durationMs,
                    "speedMbps" to speedMbps
                )
            )
            speedTestActive.set(false)
            DiagnosticsLogger.log(
                "speed",
                "Speed data received",
                mapOf(
                    "sessionId" to activeRuntime.sessionId,
                    "testId" to incoming.testId,
                    "bytesReceived" to incoming.bytesReceived,
                    "durationMs" to durationMs,
                    "speedMbps" to speedMbps
                )
            )
            return
        }
    }

    private fun completeIncomingFile(
        activeRuntime: Runtime,
        frame: ProtocolFrame,
        metadata: JSONObject,
        incoming: IncomingFileStream
    ) {
        try {
            incoming.outputStream.flush()
            incoming.outputStream.close()
            val actualHash = incoming.digest.digest().toHex()
            val expectedSize = metadata.optLong("sizeBytes", incoming.expectedSize)
            if (expectedSize >= 0 && incoming.bytesReceived != expectedSize) {
                incoming.file.delete()
                throw IOException(
                    "Size mismatch for ${incoming.safeFileName}: " +
                        "expected $expectedSize bytes, received ${incoming.bytesReceived}"
                )
            }
            val expectedHash = metadata.optString("sha256", "")
            if (expectedHash.isNotBlank() && !expectedHash.equals(actualHash, ignoreCase = true)) {
                incoming.file.delete()
                throw IOException("Checksum mismatch for ${incoming.safeFileName}")
            }
            val published = receiveDestinationManager.publish(
                incoming.file,
                incoming.safeFileName
            )
            methodChannel.invokeOnMain(
                "onFileTransferCompleted",
                fileTransferData(
                    incoming = incoming,
                    status = "completed",
                    fileName = published.displayName,
                    fileSize = expectedSize.coerceAtLeast(incoming.bytesReceived),
                    filePath = published.location,
                    savedLocation = published.savedLocation
                )
            )
            DiagnosticsLogger.log(
                "file",
                "Incoming file completed",
                mapOf(
                    "sessionId" to activeRuntime.sessionId,
                    "transferId" to incoming.transferId,
                    "fileName" to incoming.safeFileName,
                    "bytesReceived" to incoming.bytesReceived,
                    "sha256" to actualHash
                )
            )
            sendAck(activeRuntime, frame, "bulk.complete")
        } catch (exception: Exception) {
            incoming.file.delete()
            sendBulkError(activeRuntime, frame.streamId, "file_receive_failed", exception.message ?: "File receive failed")
            emitFileTransferTerminal(
                method = "onFileTransferFailed",
                incoming = incoming,
                status = "failed",
                error = exception.message ?: "File receive failed"
            )
        }
    }

    private fun handleBulkCancel(frame: ProtocolFrame, metadata: JSONObject) {
        activeFileReceives.remove(frame.streamId)?.let { incoming ->
            closeQuietly(incoming.outputStream)
            incoming.file.delete()
            val reason = metadata.optString("reason", "peer_cancelled")
            val failed = reason.contains("failed", ignoreCase = true) ||
                reason.contains("error", ignoreCase = true)
            emitFileTransferTerminal(
                method = if (failed) {
                    "onFileTransferFailed"
                } else {
                    "onFileTransferCancelled"
                },
                incoming = incoming,
                status = if (failed) "failed" else "cancelled",
                error = reason
            )
        }
        activeSpeedReceives.remove(frame.streamId)
        speedTestActive.set(false)
        DiagnosticsLogger.log(
            "bulk",
            "Bulk stream cancelled",
            mapOf("streamId" to frame.streamId, "reason" to metadata.optString("reason"))
        )
    }

    private fun handleControlFrame(activeRuntime: Runtime, frame: ProtocolFrame) {
        when (frame.type) {
            ProtocolFrameType.HEARTBEAT_PING -> {
                activeRuntime.controlTransport.writeFrame(
                    ProtocolFrame(
                        type = ProtocolFrameType.HEARTBEAT_PONG,
                        channel = ProtocolChannel.CONTROL,
                        sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                        correlationId = frame.correlationId,
                        metadataJson = heartbeatMetadata(activeRuntime).toString()
                    )
                )
            }
            ProtocolFrameType.HEARTBEAT_PONG -> {
                activeRuntime.lastFrameReceivedAt.set(System.currentTimeMillis())
            }
            ProtocolFrameType.CLOSE -> {
                disconnect("peer_closed")
            }
            ProtocolFrameType.ERROR -> {
                val metadata = if (frame.metadataJson.isBlank()) JSONObject() else JSONObject(frame.metadataJson)
                val code = metadata.optString("code", "peer_error")
                val message = metadata.optString("message", "Peer reported protocol error")
                if (code.startsWith("audio_")) {
                    audioHandler?.onAudioFeatureError(code, message, metadata.optLong("streamId", frame.streamId))
                    DiagnosticsLogger.log(
                        "audio",
                        "Peer reported audio error",
                        mapOf("sessionId" to activeRuntime.sessionId, "code" to code, "message" to message)
                    )
                } else if (code.startsWith("file_") || code.startsWith("speed_") || code.startsWith("bulk_")) {
                    methodChannel.invokeOnMain("onError", message)
                    DiagnosticsLogger.log(
                        "bulk",
                        "Peer reported stream error",
                        mapOf("sessionId" to activeRuntime.sessionId, "code" to code, "message" to message)
                    )
                } else {
                    failSession(
                        activeRuntime.generation,
                        activeRuntime.sessionId,
                        activeRuntime.role,
                        "peer_error",
                        IOException(message),
                        false
                    )
                }
            }
            ProtocolFrameType.CONTROL_MESSAGE -> handleControlMessage(activeRuntime, frame)
            ProtocolFrameType.ACK -> {
                methodChannel.invokeOnMain("onDebug", "Received ack ${frame.correlationId}")
                DiagnosticsLogger.log("control", "Ack received", mapOf("correlationId" to frame.correlationId))
            }
            else -> {
                // Gateway and telephony frames. The handler enforces the rule that
                // these are refused on an unauthenticated session.
                // Gateway frames first (this device acting as Phone 1), then the
                // client side (this device acting as Phone 2). A device is only
                // ever one of the two in a given session, so at most one claims
                // the frame.
                if (!gatewayHandler.handle(frame) && !remoteTelephony.handle(frame)) {
                    methodChannel.invokeOnMain(
                        "onDebug",
                        "Ignoring ${frame.type.protocolName} on control channel"
                    )
                }
            }
        }
    }

    /** Writes a gateway frame on the control channel, if one is open. */
    private fun sendGatewayFrame(type: ProtocolFrameType, metadata: JSONObject) {
        val activeRuntime = runtime ?: return
        try {
            activeRuntime.controlTransport.writeFrame(
                ProtocolFrame(
                    type = type,
                    channel = ProtocolChannel.CONTROL,
                    sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                    correlationId = UUID.randomUUID(),
                    metadataJson = metadata.toString()
                )
            )
        } catch (exception: Exception) {
            DiagnosticsLogger.log(
                "gateway",
                "Failed to send gateway frame",
                mapOf("frameType" to type.protocolName, "errorType" to exception.javaClass.simpleName)
            )
        }
    }

    private fun handleControlMessage(activeRuntime: Runtime, frame: ProtocolFrame) {
        val metadata = if (frame.metadataJson.isBlank()) JSONObject() else JSONObject(frame.metadataJson)
        val kind = metadata.optString("kind")
        when {
            kind.startsWith("audio.") -> {
                audioHandler?.onAudioControlMessage(metadata)
            }
            kind == "chat" -> {
                val message = frame.payload.toString(Charsets.UTF_8)
                val data = mapOf(
                    "message" to message,
                    "timestamp" to metadata.optLong("timestamp", System.currentTimeMillis()),
                    "messageId" to metadata.optString("messageId"),
                    "senderPlatform" to metadata.optString("senderPlatform", "unknown"),
                    "sessionId" to metadata.optString("sessionId", activeRuntime.sessionId)
                )
                methodChannel.invokeOnMain("onDataReceived", data)
                DiagnosticsLogger.log(
                    "chat",
                    "Chat message received",
                    mapOf(
                        "sessionId" to activeRuntime.sessionId,
                        "messageId" to metadata.optString("messageId"),
                        "senderPlatform" to metadata.optString("senderPlatform", "unknown")
                    )
                )
            }
            else -> {
                methodChannel.invokeOnMain("onDebug", "Unknown control message kind: $kind")
            }
        }
    }

    private fun startHeartbeat(activeRuntime: Runtime) {
        stopHeartbeat()
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate({
            if (!isCurrent(activeRuntime.generation) || stateMachine.phase != SessionPhase.READY) return@scheduleAtFixedRate

            val ageMs = System.currentTimeMillis() - activeRuntime.lastFrameReceivedAt.get()
            if (ageMs > HEARTBEAT_TIMEOUT_MS) {
                degradeReadySession(
                    activeRuntime,
                    "heartbeat_timeout",
                    IOException("No control frames received for ${ageMs}ms")
                )
                return@scheduleAtFixedRate
            }

            try {
                activeRuntime.controlTransport.writeFrame(
                    ProtocolFrame(
                        type = ProtocolFrameType.HEARTBEAT_PING,
                        channel = ProtocolChannel.CONTROL,
                        sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                        correlationId = UUID.randomUUID(),
                        metadataJson = heartbeatMetadata(activeRuntime).toString()
                    )
                )
            } catch (exception: Exception) {
                degradeReadySession(activeRuntime, "heartbeat_send_failed", exception)
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun heartbeatMetadata(activeRuntime: Runtime): JSONObject {
        return JSONObject()
            .put("sessionId", activeRuntime.sessionId)
            .put("timestamp", System.currentTimeMillis())
    }

    private fun sendFileStreamInternal(
        activeRuntime: Runtime,
        transferId: String,
        fileName: String,
        sizeBytes: Long,
        inputStream: InputStream,
        shouldCancel: () -> Boolean,
        onProgress: (Long) -> Unit
    ): FileSendResult {
        val streamId = nextStreamId()
        val correlationId = UUID.nameUUIDFromBytes(transferId.toByteArray(Charsets.UTF_8))
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesSent = 0L
        val startedAtMs = System.currentTimeMillis()
        val safeName = safeFileName(fileName)
        val cancellationState = BulkTransferCancellationState(shouldCancel)

        synchronized(bulkSendLock) {
            try {
                if (cancellationState.isCancellationRequested()) {
                    throw FileTransferCancelledException(bytesSent)
                }
                activeRuntime.bulkTransport.writeFrame(
                    ProtocolFrame(
                        type = ProtocolFrameType.BULK_START,
                        channel = ProtocolChannel.BULK,
                        streamId = streamId,
                        sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                        correlationId = correlationId,
                        metadataJson = JSONObject()
                            .put("kind", "file")
                            .put("transferId", transferId)
                            .put("sessionId", activeRuntime.sessionId)
                            .put("fileName", safeName)
                            .put("sizeBytes", sizeBytes)
                            .put("timestamp", startedAtMs)
                            .toString()
                    )
                )
                cancellationState.markStartSent()

                val buffer = ByteArray(BULK_CHUNK_SIZE)
                while (true) {
                    if (cancellationState.isCancellationRequested()) {
                        throw FileTransferCancelledException(bytesSent)
                    }
                    val read = try {
                        inputStream.read(buffer)
                    } catch (exception: Exception) {
                        if (cancellationState.isCancellationRequested()) {
                            throw FileTransferCancelledException(bytesSent, exception)
                        }
                        throw exception
                    }
                    if (read == -1) break
                    val payload = buffer.copyOf(read)
                    digest.update(payload)
                    bytesSent += read
                    activeRuntime.bulkTransport.writeFrame(
                        ProtocolFrame(
                            type = ProtocolFrameType.BULK_CHUNK,
                            channel = ProtocolChannel.BULK,
                            streamId = streamId,
                            sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                            correlationId = correlationId,
                            metadataJson = JSONObject()
                                .put("kind", "file")
                                .put("transferId", transferId)
                                .put("offset", bytesSent - read)
                                .toString(),
                            payload = payload
                        )
                    )
                    onProgress(bytesSent)
                }

                if (cancellationState.isCancellationRequested()) {
                    throw FileTransferCancelledException(bytesSent)
                }

                val sha256 = digest.digest().toHex()
                activeRuntime.bulkTransport.writeFrame(
                    ProtocolFrame(
                        type = ProtocolFrameType.BULK_COMPLETE,
                        channel = ProtocolChannel.BULK,
                        streamId = streamId,
                        sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                        correlationId = correlationId,
                        metadataJson = JSONObject()
                            .put("kind", "file")
                            .put("transferId", transferId)
                            .put("sizeBytes", bytesSent)
                            .put("sha256", sha256)
                            .put("timestamp", System.currentTimeMillis())
                            .toString()
                    )
                )
                onProgress(bytesSent)
                DiagnosticsLogger.log(
                    "file",
                    "File sent",
                    mapOf(
                        "sessionId" to activeRuntime.sessionId,
                        "transferId" to transferId,
                        "fileName" to safeName,
                        "bytesSent" to bytesSent,
                        "sha256" to sha256,
                        "durationMs" to (System.currentTimeMillis() - startedAtMs)
                    )
                )
                return FileSendResult(safeName, bytesSent)
            } catch (exception: Exception) {
                if (cancellationState.claimCancelFrame()) {
                    trySendBulkCancel(
                        activeRuntime = activeRuntime,
                        streamId = streamId,
                        transferId = transferId,
                        correlationId = correlationId,
                        reason = if (cancellationState.isCancellationRequested()) {
                            "user_cancelled"
                        } else {
                            "file_send_failed"
                        },
                        message = exception.message ?: "File send stopped"
                    )
                }
                if (
                    exception is FileTransferCancelledException ||
                    cancellationState.isCancellationRequested()
                ) {
                    throw FileTransferCancelledException(bytesSent, exception)
                }
                throw exception
            }
        }
    }

    private fun trySendBulkCancel(
        activeRuntime: Runtime,
        streamId: Long,
        transferId: String,
        correlationId: UUID,
        reason: String,
        message: String
    ): Boolean {
        return try {
            activeRuntime.bulkTransport.writeFrame(
                ProtocolFrame(
                    type = ProtocolFrameType.BULK_CANCEL,
                    channel = ProtocolChannel.BULK,
                    streamId = streamId,
                    sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                    correlationId = correlationId,
                    metadataJson = JSONObject()
                        .put("kind", "file")
                        .put("transferId", transferId)
                        .put("reason", reason)
                        .put("message", message)
                        .toString()
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendSpeedPayload(
        activeRuntime: Runtime,
        testId: String,
        sizeBytes: Long,
        emitLocalProgress: Boolean
    ) {
        val safeSize = sizeBytes.coerceAtLeast(0L)
        val streamId = nextStreamId()
        var bytesSent = 0L
        val startedAtMs = System.currentTimeMillis()
        val correlationId = UUID.fromString(testId)

        synchronized(bulkSendLock) {
            activeRuntime.bulkTransport.writeFrame(
                ProtocolFrame(
                    type = ProtocolFrameType.BULK_START,
                    channel = ProtocolChannel.BULK,
                    streamId = streamId,
                    sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                    correlationId = correlationId,
                    metadataJson = JSONObject()
                        .put("kind", "speed-data")
                        .put("testId", testId)
                        .put("sessionId", activeRuntime.sessionId)
                        .put("sizeBytes", safeSize)
                        .put("timestamp", startedAtMs)
                        .toString()
                )
            )

            val buffer = ByteArray(BULK_CHUNK_SIZE)
            while (bytesSent < safeSize) {
                val chunkSize = minOf(buffer.size.toLong(), safeSize - bytesSent).toInt()
                val payload = if (chunkSize == buffer.size) buffer else buffer.copyOf(chunkSize)
                activeRuntime.bulkTransport.writeFrame(
                    ProtocolFrame(
                        type = ProtocolFrameType.BULK_CHUNK,
                        channel = ProtocolChannel.BULK,
                        streamId = streamId,
                        sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                        correlationId = correlationId,
                        metadataJson = JSONObject()
                            .put("kind", "speed-data")
                            .put("testId", testId)
                            .put("offset", bytesSent)
                            .toString(),
                        payload = payload
                    )
                )
                bytesSent += chunkSize
                if (emitLocalProgress) {
                    val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
                    methodChannel.invokeOnMain(
                        "onSpeedTestSendProgress",
                        mapOf(
                            "bytesSent" to bytesSent,
                            "totalBytes" to safeSize,
                            "speedMbps" to calculateSpeedMbps(bytesSent, elapsedMs),
                            "progress" to if (safeSize > 0) bytesSent.toDouble() / safeSize.toDouble() else 1.0
                        )
                    )
                }
            }

            activeRuntime.bulkTransport.writeFrame(
                ProtocolFrame(
                    type = ProtocolFrameType.BULK_COMPLETE,
                    channel = ProtocolChannel.BULK,
                    streamId = streamId,
                    sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                    correlationId = correlationId,
                    metadataJson = JSONObject()
                        .put("kind", "speed-data")
                        .put("testId", testId)
                        .put("sizeBytes", bytesSent)
                        .put("timestamp", System.currentTimeMillis())
                        .toString()
                )
            )
        }

        if (emitLocalProgress) {
            val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
            methodChannel.invokeOnMain(
                "onSpeedTestSendProgress",
                mapOf(
                    "bytesSent" to bytesSent,
                    "totalBytes" to safeSize,
                    "speedMbps" to calculateSpeedMbps(bytesSent, elapsedMs),
                    "progress" to 1.0
                )
            )
            speedTestActive.set(false)
        }

        DiagnosticsLogger.log(
            "speed",
            "Speed payload sent",
            mapOf(
                "sessionId" to activeRuntime.sessionId,
                "testId" to testId,
                "bytesSent" to bytesSent,
                "durationMs" to (System.currentTimeMillis() - startedAtMs)
            )
        )
    }

    private fun sendAck(activeRuntime: Runtime, frame: ProtocolFrame, ackFor: String) {
        try {
            activeRuntime.controlTransport.writeFrame(
                ProtocolFrame(
                    type = ProtocolFrameType.ACK,
                    channel = ProtocolChannel.CONTROL,
                    streamId = frame.streamId,
                    sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                    correlationId = frame.correlationId,
                    metadataJson = JSONObject()
                        .put("ackFor", ackFor)
                        .put("streamId", frame.streamId)
                        .put("timestamp", System.currentTimeMillis())
                        .toString()
                )
            )
            DiagnosticsLogger.log(
                "control",
                "Ack sent",
                mapOf("sessionId" to activeRuntime.sessionId, "ackFor" to ackFor, "streamId" to frame.streamId)
            )
        } catch (exception: Exception) {
            DiagnosticsLogger.log(
                "control",
                "Ack send failed",
                mapOf(
                    "sessionId" to activeRuntime.sessionId,
                    "ackFor" to ackFor,
                    "streamId" to frame.streamId,
                    "errorType" to exception.javaClass.simpleName,
                    "error" to exception.message
                )
            )
        }
    }

    private fun sendBulkError(activeRuntime: Runtime, streamId: Long, code: String, message: String) {
        sendFeatureError(activeRuntime, streamId, code, message, "bulk")
    }

    private fun sendFeatureError(
        activeRuntime: Runtime,
        streamId: Long,
        code: String,
        message: String,
        category: String
    ) {
        try {
            activeRuntime.controlTransport.writeFrame(
                ProtocolFrame(
                    type = ProtocolFrameType.ERROR,
                    channel = ProtocolChannel.CONTROL,
                    streamId = streamId,
                    sequenceNumber = activeRuntime.sequenceNumber.incrementAndGet(),
                    correlationId = UUID.randomUUID(),
                    metadataJson = JSONObject()
                        .put("code", code)
                        .put("message", message)
                        .put("streamId", streamId)
                        .toString()
                )
            )
            DiagnosticsLogger.log(
                category,
                "Feature error sent",
                mapOf("sessionId" to activeRuntime.sessionId, "streamId" to streamId, "code" to code, "message" to message)
            )
        } catch (exception: Exception) {
            DiagnosticsLogger.log(
                category,
                "Feature error send failed",
                mapOf(
                    "sessionId" to activeRuntime.sessionId,
                    "streamId" to streamId,
                    "code" to code,
                    "errorType" to exception.javaClass.simpleName,
                    "error" to exception.message
                )
            )
        }
    }

    private fun readyRuntimeOrError(result: MethodChannel.Result): Runtime? {
        val activeRuntime = runtime
        if (activeRuntime == null || stateMachine.phase != SessionPhase.READY) {
            result.error("SESSION_NOT_READY", "The WDCable session is not ready", null)
            return null
        }
        return activeRuntime
    }

    private fun cleanupIncomingBulkStreams(deletePartialFiles: Boolean) {
        activeFileReceives.values.forEach { incoming ->
            closeQuietly(incoming.outputStream)
            if (deletePartialFiles) {
                incoming.file.delete()
            }
        }
        activeFileReceives.clear()
        activeSpeedReceives.clear()
    }

    private fun fileTransferData(
        incoming: IncomingFileStream,
        status: String,
        fileName: String = incoming.safeFileName,
        fileSize: Long = incoming.expectedSize,
        filePath: String? = null,
        savedLocation: String? = null,
        error: String? = null
    ): Map<String, Any?> = mapOf(
        "transferId" to incoming.transferId,
        "direction" to "receive",
        "fileName" to fileName,
        "fileSize" to fileSize,
        "bytesTransferred" to incoming.bytesReceived,
        "status" to status,
        "filePath" to filePath,
        "savedLocation" to savedLocation,
        "error" to error
    )

    private fun emitFileTransferTerminal(
        method: String,
        incoming: IncomingFileStream,
        status: String,
        error: String? = null
    ) {
        methodChannel.invokeOnMain(
            method,
            fileTransferData(incoming, status = status, error = error)
        )
    }

    private fun safeFileName(fileName: String): String = BulkFileNames.safeFileName(fileName)

    private fun nextStreamId(): Long = kotlin.math.abs(UUID.randomUUID().mostSignificantBits)

    private fun calculateSpeedMbps(bytes: Long, elapsedTimeMs: Long): Double {
        if (elapsedTimeMs <= 0) return 0.0
        return (bytes * 8.0) / (elapsedTimeMs / 1000.0) / (1024 * 1024)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun closeQuietly(outputStream: FileOutputStream) {
        try {
            outputStream.close()
        } catch (_: Exception) {
        }
    }

    private fun isSameWifiDirectGroup(role: SessionRole, ownerAddress: String?): Boolean {
        val knownRole = currentRole ?: return false
        if (knownRole != role) return false
        return role == SessionRole.GROUP_OWNER || groupOwnerAddress == ownerAddress
    }

    private fun isSameWifiDirectGroupInfo(
        info: android.net.wifi.p2p.WifiP2pInfo?,
        role: SessionRole,
        ownerAddress: String?
    ): Boolean {
        if (info?.groupFormed != true) return false
        val currentInfoRole = if (info.isGroupOwner) SessionRole.GROUP_OWNER else SessionRole.CLIENT
        if (currentInfoRole != role) return false
        if (role == SessionRole.GROUP_OWNER) return true
        return info.groupOwnerAddress?.hostAddress == ownerAddress
    }

    private fun SessionPhase.keepsActiveSession(): Boolean {
        return when (this) {
            SessionPhase.WIFI_DIRECT_CONNECTED,
            SessionPhase.CONNECTING_TRANSPORT,
            SessionPhase.HANDSHAKING,
            SessionPhase.READY,
            SessionPhase.DEGRADED -> true
            SessionPhase.DISCONNECTING,
            SessionPhase.DISCONNECTED,
            SessionPhase.FAILED -> false
        }
    }

    private fun failWithoutRuntime(reason: String, message: String) {
        generation.incrementAndGet()
        lastDisconnectReason = reason
        currentSessionId = UUID.randomUUID().toString()
        stateMachine.reset(SessionPhase.DISCONNECTED)
        emitState(SessionPhase.FAILED, currentSessionId, currentRole, reason)
        methodChannel.invokeOnMain(
            "onSessionFailed",
            mapOf("reason" to reason, "message" to message, "sessionId" to (currentSessionId ?: ""))
        )
    }

    private fun degradeReadySession(activeRuntime: Runtime, reason: String, exception: Exception) {
        if (!generation.compareAndSet(activeRuntime.generation, activeRuntime.generation + 1)) return

        val recoveryGeneration = activeRuntime.generation + 1
        lastDisconnectReason = reason
        lastRecoveryReason = reason
        DiagnosticsLogger.log(
            "session",
            "Session degraded",
            mapOf(
                "sessionId" to activeRuntime.sessionId,
                "role" to activeRuntime.role.eventName,
                "reason" to reason,
                "errorType" to exception.javaClass.simpleName,
                "error" to exception.message
            )
        )

        stopHeartbeat()
        audioHandler?.onSessionEnded(reason)
        cleanupIncomingBulkStreams(deletePartialFiles = true)
        fileTransferCleanup?.invoke(reason)
        speedTestActive.set(false)
        closeRuntimeOnly()
        transportAdapter.cancel()
        runtime = null
        emitState(SessionPhase.DEGRADED, activeRuntime.sessionId, activeRuntime.role, reason)
        confirmGroupThenRecoverTransport(
            recoveryGeneration,
            activeRuntime.sessionId,
            activeRuntime.role,
            activeRuntime.peerAddress,
            reason
        )
    }

    private fun confirmGroupThenRecoverTransport(
        recoveryGeneration: Int,
        sessionId: String,
        role: SessionRole,
        ownerAddress: String?,
        degradedReason: String
    ) {
        setupExecutor.execute {
            try {
                Thread.sleep(DEGRADED_GROUP_CONFIRM_DELAY_MS)
            } catch (_: InterruptedException) {
                return@execute
            }

            if (!isCurrent(recoveryGeneration) || stateMachine.phase != SessionPhase.DEGRADED) return@execute

            WdCableRuntime.requestConnectionInfoOnce(
                reason = "degraded_group_confirm:$degradedReason",
                dispatchToListener = false
            ) { info ->
                setupExecutor.execute {
                    if (!isCurrent(recoveryGeneration) || stateMachine.phase != SessionPhase.DEGRADED) return@execute

                    if (!isSameWifiDirectGroupInfo(info, role, ownerAddress)) {
                        lastRecoveryReason = "wifi_direct_group_lost_after_$degradedReason"
                        DiagnosticsLogger.log(
                            "session",
                            "Wi-Fi Direct group lost during degraded recovery",
                            mapOf(
                                "sessionId" to sessionId,
                                "reason" to degradedReason,
                                "groupFormed" to (info?.groupFormed ?: false),
                                "isGroupOwner" to (info?.isGroupOwner ?: false),
                                "groupOwnerAddress" to (info?.groupOwnerAddress?.hostAddress ?: "")
                            )
                        )
                        disconnect("wifi_direct_group_lost")
                        return@execute
                    }

                    rebuildTransportAfterDegraded(recoveryGeneration, sessionId, role, ownerAddress, degradedReason)
                }
            }
        }
    }

    private fun rebuildTransportAfterDegraded(
        recoveryGeneration: Int,
        sessionId: String,
        role: SessionRole,
        ownerAddress: String?,
        degradedReason: String
    ) {
        val openedTransports = linkedMapOf<ProtocolChannel, SessionTransport>()
        try {
            if (!isCurrent(recoveryGeneration) || stateMachine.phase != SessionPhase.DEGRADED) return
            val setupDeadlineMs = transportSetup.deadlineFromNow()

            DiagnosticsLogger.log(
                "session",
                "Rebuilding WDCable transport after degraded state",
                mapOf(
                    "sessionId" to sessionId,
                    "role" to role.eventName,
                    "transportRole" to role.transportRole().eventName,
                    "reason" to degradedReason
                )
            )
            val opened = transportSetup.openTransports(role, ownerAddress, recoveryGeneration, sessionId, setupDeadlineMs)
            openedTransports.putAll(opened.transports)
            if (!isCurrent(recoveryGeneration) || stateMachine.phase != SessionPhase.DEGRADED) {
                closeTransports(openedTransports.values)
                return
            }

            val newRuntime = Runtime(
                generation = recoveryGeneration,
                sessionId = sessionId,
                role = role,
                peerAddress = opened.peerAddress,
                transports = openedTransports.toMap()
            )

            performHandshake(newRuntime)
            newRuntime.transports.values.forEach { it.setReadTimeout(0) }

            if (!isCurrent(recoveryGeneration) || stateMachine.phase != SessionPhase.DEGRADED) {
                closeTransports(newRuntime.transports.values)
                return
            }

            runtime = newRuntime
            lastDisconnectReason = null
            lastRecoveryReason = "recovered_after_$degradedReason"
            emitState(SessionPhase.READY, sessionId, role)
            emitSessionReady(newRuntime)
            startControlReadLoop(newRuntime)
            startBulkReadLoop(newRuntime)
            startHeartbeat(newRuntime)
            WdCableRuntime.startConnectionService(context)
            DiagnosticsLogger.log(
                "session",
                "WDCable transport recovered",
                mapOf(
                    "sessionId" to sessionId,
                    "role" to role.eventName,
                    "transportRole" to role.transportRole().eventName,
                    "reason" to degradedReason,
                    "remoteEndpoint" to (opened.peerAddress ?: "")
                )
            )
        } catch (exception: TransportSetupException) {
            closeTransports(openedTransports.values)
            lastRecoveryReason = "transport_recovery_failed_after_$degradedReason"
            failSession(recoveryGeneration, sessionId, role, exception.failureReason, exception, false)
        } catch (exception: Exception) {
            closeTransports(openedTransports.values)
            lastRecoveryReason = "transport_recovery_failed_after_$degradedReason"
            val reason = if (isCurrent(recoveryGeneration)) "tcp_connect_timeout" else "cancelled"
            failSession(recoveryGeneration, sessionId, role, reason, exception, false)
        }
    }

    private fun failSession(
        expectedGeneration: Int,
        sessionId: String,
        role: SessionRole,
        reason: String,
        exception: Exception,
        peerProtocolMissing: Boolean
    ) {
        if (!generation.compareAndSet(expectedGeneration, expectedGeneration + 1)) return

        lastDisconnectReason = reason
        DiagnosticsLogger.log(
            "session",
            "Session failed",
            mapOf(
                "sessionId" to sessionId,
                "role" to role.eventName,
                "transportRole" to role.transportRole().eventName,
                "reason" to reason,
                "errorType" to exception.javaClass.simpleName,
                "error" to exception.message
            )
        )
        stopHeartbeat()
        audioHandler?.onSessionEnded(reason)
        cleanupIncomingBulkStreams(deletePartialFiles = true)
        fileTransferCleanup?.invoke(reason)
        speedTestActive.set(false)
        closeRuntimeOnly()
        transportAdapter.cancel()
        runtime = null
        emitState(SessionPhase.FAILED, sessionId, role, reason)
        WdCableRuntime.stopConnectionService(context)

        if (peerProtocolMissing) {
            methodChannel.invokeOnMain(
                "onPeerProtocolMissing",
                mapOf(
                    "reason" to reason,
                    "message" to (exception.message ?: "Peer is not running the upgraded WDCable protocol"),
                    "sessionId" to sessionId,
                    "transportRole" to role.transportRole().eventName
                )
            )
        }

        methodChannel.invokeOnMain(
            "onSessionFailed",
            mapOf(
                "reason" to reason,
                "message" to (exception.message ?: reason),
                "sessionId" to sessionId,
                "transportRole" to role.transportRole().eventName
            )
        )
        emitDisconnectReason(reason, sessionId)
    }

    private fun emitSessionReady(activeRuntime: Runtime) {
        methodChannel.invokeOnMain(
            "onSessionReady",
            mapOf(
                "sessionId" to activeRuntime.sessionId,
                "role" to activeRuntime.role.eventName,
                "transportRole" to activeRuntime.transportRole.eventName,
                "protocolVersion" to ProtocolConstants.VERSION,
                "capabilities" to localCapabilitiesList(),
                "peerCapabilities" to activeRuntime.peerCapabilities.toList()
            )
        )
    }

    private fun localCapabilitiesList(): List<String> {
        return AudioCapabilities.advertisedCapabilitiesForRuntime() + securityCapabilities()
    }

    private fun emitState(
        phase: SessionPhase,
        sessionId: String?,
        role: SessionRole?,
        reason: String? = null
    ) {
        try {
            if (stateMachine.phase != phase) {
                stateMachine.transitionTo(phase)
            }
        } catch (_: IllegalStateException) {
            stateMachine.reset(phase)
        }

        methodChannel.invokeOnMain(
            "onSessionStateChanged",
            mapOf(
                "state" to phase.eventName,
                "sessionId" to (sessionId ?: ""),
                "role" to (role?.eventName ?: ""),
                "transportRole" to (role?.transportRole()?.eventName ?: ""),
                "groupOwnerAddress" to (groupOwnerAddress ?: ""),
                "disconnectReason" to (reason ?: lastDisconnectReason ?: "")
            )
        )
    }

    private fun transitionForCleanup(
        phase: SessionPhase,
        sessionId: String?,
        role: SessionRole?,
        reason: String
    ) {
        try {
            emitState(phase, sessionId, role, reason)
        } catch (_: Exception) {
            stateMachine.reset(phase)
        }
    }

    private fun emitDisconnectReason(reason: String, sessionId: String?) {
        methodChannel.invokeOnMain(
            "onDisconnectReason",
            mapOf(
                "reason" to reason,
                "sessionId" to (sessionId ?: "")
            )
        )
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(true)
        heartbeatFuture = null
    }

    private fun closeRuntimeOnly() {
        runtime?.transports?.values?.let(::closeTransports)
        runtime = null
    }

    private fun closeTransports(transports: Collection<SessionTransport>) {
        transports.forEach { transport ->
            try {
                transport.cancel()
            } catch (_: Exception) {
            }
        }
    }

    private fun isCurrent(expectedGeneration: Int): Boolean {
        return generation.get() == expectedGeneration
    }

    private fun channelPorts(): List<Pair<ProtocolChannel, Int>> {
        return listOf(
            ProtocolChannel.CONTROL to ProtocolConstants.DEFAULT_CONTROL_PORT,
            ProtocolChannel.BULK to ProtocolConstants.DEFAULT_BULK_PORT
        )
    }

    private fun connectRetryDelayMs(attempt: Int): Long {
        val exponentialDelay = INITIAL_RETRY_DELAY_MS * (2.0.pow(attempt - 1)).toLong()
        return min(exponentialDelay, MAX_RETRY_DELAY_MS)
    }

    private fun appVersion(): String {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun MethodChannel.invokeOnMain(method: String, arguments: Any?) {
        mainHandler.post {
            invokeMethod(method, arguments)
        }
    }

    companion object {
        private const val MAX_CONNECT_ATTEMPTS = 10
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val HANDSHAKE_TIMEOUT_MS = 10000
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val HEARTBEAT_TIMEOUT_MS = 15000L
        private const val DEGRADED_GROUP_CONFIRM_DELAY_MS = 1000L
        private const val BULK_CHUNK_SIZE = 64 * 1024
    }
}

data class FileSendResult(
    val fileName: String,
    val bytesSent: Long
)

class FileTransferCancelledException(
    val bytesTransferred: Long,
    cause: Throwable? = null
) : IOException("File transfer cancelled", cause)
