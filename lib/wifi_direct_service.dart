import 'dart:async';
import 'package:flutter/services.dart';
import 'models/gateway_models.dart';
import 'models/wifi_direct_models.dart';

// Event classes for the event-driven architecture
abstract class WiFiDirectEvent {}

class PeersChangedEvent extends WiFiDirectEvent {
  final List<WiFiDirectDevice> peers;
  PeersChangedEvent(this.peers);
}

class WiFiP2pStateChangedEvent extends WiFiDirectEvent {
  final bool enabled;
  WiFiP2pStateChangedEvent(this.enabled);
}

class ConnectionChangedEvent extends WiFiDirectEvent {
  final WiFiDirectConnectionInfo connectionInfo;
  ConnectionChangedEvent(this.connectionInfo);
}

class NativeStateChangedEvent extends WiFiDirectEvent {
  final Map<String, dynamic> data;
  NativeStateChangedEvent(this.data);

  String get state => data['state']?.toString() ?? 'Unavailable';
  int get operationId => _readEventInt(data['opId']);
  bool get p2pStateKnown => data['p2pStateKnown'] == true;
  bool get p2pEnabled => data['p2pEnabled'] == true;
  bool get isDiscovering => data['isDiscovering'] == true;
  String get discoveryState => data['discoveryState']?.toString() ?? 'stopped';
  bool get isListening => data['isListening'] == true;
  String get listenState => data['listenState']?.toString() ?? 'unknown';
  bool get serviceRegistered => data['serviceRegistered'] == true;
  String get peerAddress => data['peerAddress']?.toString() ?? '';
  String get peerName => data['peerName']?.toString() ?? '';
  String get wifiRole => data['wifiRole']?.toString() ?? '';
  String get transportRole => data['transportRole']?.toString() ?? '';
  int get reasonCode => _readEventInt(data['reasonCode'], fallback: -1);
  String get reasonName => data['reasonName']?.toString() ?? '';
  String get callback => data['callback']?.toString() ?? '';

  String get decodedError {
    if (reasonCode < 0 || reasonName.isEmpty) return '';
    return '$reasonName ($reasonCode)';
  }
}

class DiscoveryStateChangedEvent extends NativeStateChangedEvent {
  DiscoveryStateChangedEvent(super.data);
}

class ListenStateChangedEvent extends NativeStateChangedEvent {
  ListenStateChangedEvent(super.data);
}

class ServiceStateChangedEvent extends NativeStateChangedEvent {
  ServiceStateChangedEvent(super.data);
}

class SessionStateChangedEvent extends WiFiDirectEvent {
  final String state;
  final String sessionId;
  final String role;
  final String transportRole;
  final String groupOwnerAddress;
  final String disconnectReason;

  SessionStateChangedEvent({
    required this.state,
    required this.sessionId,
    required this.role,
    this.transportRole = '',
    required this.groupOwnerAddress,
    required this.disconnectReason,
  });
}

class SessionReadyEvent extends WiFiDirectEvent {
  final String sessionId;
  final String role;
  final String transportRole;
  final int protocolVersion;
  final List<String> capabilities;
  final List<String> peerCapabilities;

  SessionReadyEvent({
    required this.sessionId,
    required this.role,
    this.transportRole = '',
    required this.protocolVersion,
    required this.capabilities,
    this.peerCapabilities = const [],
  });
}

class SessionFailedEvent extends WiFiDirectEvent {
  final String reason;
  final String message;
  final String sessionId;

  SessionFailedEvent({
    required this.reason,
    required this.message,
    required this.sessionId,
  });
}

class PeerProtocolMissingEvent extends WiFiDirectEvent {
  final String reason;
  final String message;
  final String sessionId;

  PeerProtocolMissingEvent({
    required this.reason,
    required this.message,
    required this.sessionId,
  });
}

class DisconnectReasonEvent extends WiFiDirectEvent {
  final String reason;
  final String sessionId;

  DisconnectReasonEvent({required this.reason, required this.sessionId});
}

/// Phone 1 reported its cellular state.
class GatewayStatusEvent extends WiFiDirectEvent {
  final GatewayStatusInfo status;
  GatewayStatusEvent(this.status);
}

/// The gateway sent its conversations and messages.
class SmsListEvent extends WiFiDirectEvent {
  final List<SmsConversationInfo> conversations;
  final List<SmsMessageInfo> messages;
  SmsListEvent(this.conversations, this.messages);
}

/// An outbound SMS was accepted for sending, or refused.
class SmsOutcomeEvent extends WiFiDirectEvent {
  final SmsSendOutcome outcome;
  SmsOutcomeEvent(this.outcome);
}

/// A remote call changed state, or a request was refused.
class RemoteCallStateEvent extends WiFiDirectEvent {
  final RemoteCallState call;
  RemoteCallStateEvent(this.call);
}

/// Phone 1 (the gateway) is showing a pairing code for the user to read out.
class PairingCodeDisplayEvent extends WiFiDirectEvent {
  final String peerDeviceId;
  final String peerName;
  final String code;
  PairingCodeDisplayEvent(this.peerDeviceId, this.peerName, this.code);
}

/// The pairing exchange finished; any pairing dialog should be dismissed.
class PairingFinishedEvent extends WiFiDirectEvent {
  final String peerDeviceId;
  final bool paired;
  PairingFinishedEvent(this.peerDeviceId, this.paired);
}

class DataReceivedEvent extends WiFiDirectEvent {
  final String message;
  final int? timestamp;
  DataReceivedEvent(this.message, {this.timestamp});
}

class BinaryDataReceivedEvent extends WiFiDirectEvent {
  final Uint8List data;
  BinaryDataReceivedEvent(this.data);
}

class SpeedTestDataReceivedEvent extends WiFiDirectEvent {
  final int bytesReceived;
  final int durationMs;
  final double speedMbps;
  SpeedTestDataReceivedEvent(
    this.bytesReceived,
    this.durationMs,
    this.speedMbps,
  );
}

class SpeedTestReceiveProgressEvent extends WiFiDirectEvent {
  final int bytesReceived;
  final int totalBytes;
  final double speedMbps;
  final double progress;
  SpeedTestReceiveProgressEvent(
    this.bytesReceived,
    this.totalBytes,
    this.speedMbps,
    this.progress,
  );
}

class SpeedTestSendProgressEvent extends WiFiDirectEvent {
  final int bytesSent;
  final int totalBytes;
  final double speedMbps;
  final double progress;
  SpeedTestSendProgressEvent(
    this.bytesSent,
    this.totalBytes,
    this.speedMbps,
    this.progress,
  );
}

abstract class FileTransferEvent extends WiFiDirectEvent {
  final String transferId;
  final String fileName;
  final int fileSize;
  final int bytesTransferred;
  final bool isUploading;
  final String? filePath;
  final String? savedLocation;
  final String? error;

  FileTransferEvent({
    required this.transferId,
    required this.fileName,
    required this.fileSize,
    required this.bytesTransferred,
    required this.isUploading,
    this.filePath,
    this.savedLocation,
    this.error,
  });

  double get progress {
    if (fileSize < 0) return 0.0;
    if (fileSize == 0) return 1.0;
    return (bytesTransferred / fileSize).clamp(0.0, 1.0);
  }
}

class FileTransferStartedEvent extends FileTransferEvent {
  final FileTransferStatus status;

  FileTransferStartedEvent({
    required super.transferId,
    required super.fileName,
    required super.fileSize,
    required super.bytesTransferred,
    required super.isUploading,
    super.savedLocation,
    this.status = FileTransferStatus.transferring,
  });
}

class FileTransferProgressEvent extends FileTransferEvent {
  final FileTransferStatus status;

  FileTransferProgressEvent({
    required super.transferId,
    required super.fileName,
    required super.fileSize,
    required super.bytesTransferred,
    required super.isUploading,
    super.savedLocation,
    this.status = FileTransferStatus.transferring,
  });
}

class FileTransferCompletedEvent extends FileTransferEvent {
  FileTransferCompletedEvent({
    required super.transferId,
    required super.fileName,
    required super.fileSize,
    required super.bytesTransferred,
    required super.isUploading,
    super.filePath,
    super.savedLocation,
  });
}

class FileTransferCancelledEvent extends FileTransferEvent {
  FileTransferCancelledEvent({
    required super.transferId,
    required super.fileName,
    required super.fileSize,
    required super.bytesTransferred,
    required super.isUploading,
    super.error,
  });
}

class FileTransferFailedEvent extends FileTransferEvent {
  FileTransferFailedEvent({
    required super.transferId,
    required super.fileName,
    required super.fileSize,
    required super.bytesTransferred,
    required super.isUploading,
    super.error,
  });
}

class ReceiveDestinationChangedEvent extends WiFiDirectEvent {
  final ReceiveDestinationInfo destination;
  final String? message;

  ReceiveDestinationChangedEvent(this.destination, {this.message});
}

class ErrorEvent extends WiFiDirectEvent {
  final String error;
  ErrorEvent(this.error);
}

class AudioStateChangedEvent extends WiFiDirectEvent {
  final String mode;
  final String state;
  final int streamId;
  final String source;
  final String encoding;
  final String latencyMode;
  final String qualityMode;
  final bool peerReady;
  final bool isStreaming;
  final String message;

  AudioStateChangedEvent({
    required this.mode,
    required this.state,
    required this.streamId,
    required this.source,
    required this.encoding,
    this.latencyMode = 'lowLatency',
    this.qualityMode = 'standard',
    required this.peerReady,
    required this.isStreaming,
    required this.message,
  });
}

class AudioStatsEvent extends WiFiDirectEvent {
  final String mode;
  final String state;
  final int streamId;
  final String latencyMode;
  final String qualityMode;
  final int bitrateBps;
  final int configuredBitrateBps;
  final int bufferLevelMs;
  final int framesSent;
  final int framesReceived;
  final int droppedFrames;
  final int packetLossCount;
  final int latePacketDrops;
  final int overflowDrops;
  final int duplicatePackets;
  final int reorderedPackets;
  final int underflowCount;
  final int plcCount;
  final int rtpPacketsSent;
  final int rtpPacketsReceived;
  final int rtpBytesSent;
  final int rtpBytesReceived;
  final int rtcpPacketsSent;
  final int rtcpPacketsReceived;
  final int rtcpFractionLost;
  final int rtcpJitter;
  final int rtcpPacketCount;
  final int rtcpOctetCount;
  final int roundTripMs;
  final int encodeErrorCount;
  final int decodeErrorCount;
  final int udpSendErrorCount;
  final int udpReceiveErrorCount;
  final int latencyMs;

  AudioStatsEvent({
    required this.mode,
    required this.state,
    required this.streamId,
    this.latencyMode = 'lowLatency',
    this.qualityMode = 'standard',
    required this.bitrateBps,
    this.configuredBitrateBps = 32000,
    required this.bufferLevelMs,
    required this.framesSent,
    required this.framesReceived,
    required this.droppedFrames,
    this.packetLossCount = 0,
    this.latePacketDrops = 0,
    this.overflowDrops = 0,
    this.duplicatePackets = 0,
    this.reorderedPackets = 0,
    required this.underflowCount,
    this.plcCount = 0,
    this.rtpPacketsSent = 0,
    this.rtpPacketsReceived = 0,
    this.rtpBytesSent = 0,
    this.rtpBytesReceived = 0,
    this.rtcpPacketsSent = 0,
    this.rtcpPacketsReceived = 0,
    this.rtcpFractionLost = 0,
    this.rtcpJitter = 0,
    this.rtcpPacketCount = 0,
    this.rtcpOctetCount = 0,
    this.roundTripMs = -1,
    this.encodeErrorCount = 0,
    this.decodeErrorCount = 0,
    this.udpSendErrorCount = 0,
    this.udpReceiveErrorCount = 0,
    required this.latencyMs,
  });
}

class AudioErrorEvent extends WiFiDirectEvent {
  final String code;
  final String message;
  final int streamId;

  AudioErrorEvent({
    required this.code,
    required this.message,
    required this.streamId,
  });
}

class WiFiDirectResetEvent extends WiFiDirectEvent {}

class PermissionDeniedEvent extends WiFiDirectEvent {
  final List<String> missingCapabilities;
  PermissionDeniedEvent([this.missingCapabilities = const []]);
}

class ClientConnectedEvent extends WiFiDirectEvent {
  final String message;
  ClientConnectedEvent(this.message);
}

class DebugEvent extends WiFiDirectEvent {
  final String message;
  DebugEvent(this.message);
}

class WiFiDirectService {
  static const MethodChannel _channel = MethodChannel('wifi_direct_cable');

  // Event stream controller
  final StreamController<WiFiDirectEvent> _eventController =
      StreamController<WiFiDirectEvent>.broadcast();

  // Public stream for events
  Stream<WiFiDirectEvent> get eventStream => _eventController.stream;

  /// Asks the user to type the code shown on the other phone.
  ///
  /// Returns the typed digits, or null to cancel. Cancelling abandons the
  /// session rather than retrying: repeated guesses against a six-digit code
  /// must not be allowed, or it becomes an online oracle.
  ///
  /// If no handler is registered the native side is told null, so the app fails
  /// closed and never pairs without the user having seen a prompt.
  Future<String?> Function(String peerDeviceId, String peerName)?
  pairingCodeRequestHandler;

  /// Asks the user whether the six-digit value matches on both phones.
  ///
  /// This is the check that actually defeats a man-in-the-middle; the typed
  /// pairing code alone does not. Returning false still pairs, but the pairing
  /// is recorded as unverified.
  Future<bool> Function(String peerDeviceId, String shortAuthString)?
  pairingVerifyHandler;

  WiFiDirectService() {
    _channel.setMethodCallHandler(_handleMethodCall);
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    try {
      switch (call.method) {
        case 'onPeersChanged':
          final List<dynamic> peersData = call.arguments;
          final peers = peersData
              .map(
                (data) => WiFiDirectDevice.fromMap(
                  Map<String, dynamic>.from(data as Map),
                ),
              )
              .toList();
          _eventController.add(PeersChangedEvent(peers));
          break;

        case 'onWifiP2pStateChanged':
          final bool enabled = call.arguments;
          _eventController.add(WiFiP2pStateChangedEvent(enabled));
          break;

        case 'onConnectionChanged':
          final Map<String, dynamic> connectionData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          final connectionInfo = WiFiDirectConnectionInfo.fromMap(
            connectionData,
          );
          _eventController.add(ConnectionChangedEvent(connectionInfo));
          break;

        case 'onNativeStateChanged':
          _eventController.add(
            NativeStateChangedEvent(
              Map<String, dynamic>.from(call.arguments as Map),
            ),
          );
          break;

        case 'onDiscoveryStateChanged':
          _eventController.add(
            DiscoveryStateChangedEvent(
              Map<String, dynamic>.from(call.arguments as Map),
            ),
          );
          break;

        case 'onListenStateChanged':
          _eventController.add(
            ListenStateChangedEvent(
              Map<String, dynamic>.from(call.arguments as Map),
            ),
          );
          break;

        case 'onServiceStateChanged':
          _eventController.add(
            ServiceStateChangedEvent(
              Map<String, dynamic>.from(call.arguments as Map),
            ),
          );
          break;

        case 'onSessionStateChanged':
          final Map<String, dynamic> sessionData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            SessionStateChangedEvent(
              state: sessionData['state']?.toString() ?? 'Disconnected',
              sessionId: sessionData['sessionId']?.toString() ?? '',
              role: sessionData['role']?.toString() ?? '',
              transportRole: sessionData['transportRole']?.toString() ?? '',
              groupOwnerAddress:
                  sessionData['groupOwnerAddress']?.toString() ?? '',
              disconnectReason:
                  sessionData['disconnectReason']?.toString() ?? '',
            ),
          );
          break;

        case 'onSessionReady':
          final Map<String, dynamic> sessionData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          final capabilities = sessionData['capabilities'];
          final peerCapabilities = sessionData['peerCapabilities'];
          _eventController.add(
            SessionReadyEvent(
              sessionId: sessionData['sessionId']?.toString() ?? '',
              role: sessionData['role']?.toString() ?? '',
              transportRole: sessionData['transportRole']?.toString() ?? '',
              protocolVersion: sessionData['protocolVersion'] is int
                  ? sessionData['protocolVersion'] as int
                  : int.tryParse(
                          sessionData['protocolVersion']?.toString() ?? '',
                        ) ??
                        0,
              capabilities: capabilities is List
                  ? capabilities.map((item) => item.toString()).toList()
                  : const [],
              peerCapabilities: peerCapabilities is List
                  ? peerCapabilities.map((item) => item.toString()).toList()
                  : const [],
            ),
          );
          break;

        case 'onSessionFailed':
          final Map<String, dynamic> failureData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            SessionFailedEvent(
              reason: failureData['reason']?.toString() ?? 'session_failed',
              message: failureData['message']?.toString() ?? 'Session failed',
              sessionId: failureData['sessionId']?.toString() ?? '',
            ),
          );
          break;

        case 'onPeerProtocolMissing':
          final Map<String, dynamic> failureData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            PeerProtocolMissingEvent(
              reason:
                  failureData['reason']?.toString() ?? 'peer_protocol_missing',
              message:
                  failureData['message']?.toString() ??
                  'Peer is not running the upgraded WDCable protocol',
              sessionId: failureData['sessionId']?.toString() ?? '',
            ),
          );
          break;

        case 'onDisconnectReason':
          final Map<String, dynamic> disconnectData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            DisconnectReasonEvent(
              reason: disconnectData['reason']?.toString() ?? '',
              sessionId: disconnectData['sessionId']?.toString() ?? '',
            ),
          );
          break;

        case 'onDataReceived':
          if (call.arguments is Map) {
            // New JSON format with message and timestamp
            final Map<String, dynamic> messageData = Map<String, dynamic>.from(
              call.arguments as Map,
            );
            _eventController.add(
              DataReceivedEvent(
                messageData['message'] as String,
                timestamp: messageData['timestamp'] as int?,
              ),
            );
          } else {
            // Backward compatibility: plain string
            final String data = call.arguments as String;
            _eventController.add(DataReceivedEvent(data));
          }
          break;

        case 'onBinaryDataReceived':
          final Uint8List data = call.arguments;
          _eventController.add(BinaryDataReceivedEvent(data));
          break;

        case 'onSpeedTestDataReceived':
          final Map<String, dynamic> speedData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            SpeedTestDataReceivedEvent(
              speedData['bytesReceived'],
              speedData['durationMs'],
              speedData['speedMbps'].toDouble(),
            ),
          );
          break;

        case 'onSpeedTestReceiveProgress':
          final Map<String, dynamic> progressData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            SpeedTestReceiveProgressEvent(
              progressData['bytesReceived'],
              progressData['totalBytes'],
              progressData['speedMbps'].toDouble(),
              progressData['progress'].toDouble(),
            ),
          );
          break;

        case 'onSpeedTestSendProgress':
          final Map<String, dynamic> progressData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            SpeedTestSendProgressEvent(
              progressData['bytesSent'],
              progressData['totalBytes'],
              progressData['speedMbps'].toDouble(),
              progressData['progress'].toDouble(),
            ),
          );
          break;

        case 'onFileTransferStarted':
          final data = _fileTransferData(call.arguments);
          _eventController.add(
            FileTransferStartedEvent(
              transferId: data.transferId,
              fileName: data.fileName,
              fileSize: data.fileSize,
              bytesTransferred: data.bytesTransferred,
              isUploading: data.isUploading,
              savedLocation: data.savedLocation,
              status: FileTransferStatus.fromName(data.status),
            ),
          );
          break;

        case 'onFileTransferProgress':
          final data = _fileTransferData(call.arguments);
          _eventController.add(
            FileTransferProgressEvent(
              transferId: data.transferId,
              fileName: data.fileName,
              fileSize: data.fileSize,
              bytesTransferred: data.bytesTransferred,
              isUploading: data.isUploading,
              savedLocation: data.savedLocation,
              status: FileTransferStatus.fromName(data.status),
            ),
          );
          break;

        case 'onFileTransferCompleted':
          final data = _fileTransferData(call.arguments);
          _eventController.add(
            FileTransferCompletedEvent(
              transferId: data.transferId,
              fileName: data.fileName,
              fileSize: data.fileSize,
              bytesTransferred: data.bytesTransferred,
              isUploading: data.isUploading,
              filePath: data.filePath,
              savedLocation: data.savedLocation,
            ),
          );
          break;

        case 'onFileTransferCancelled':
          final data = _fileTransferData(call.arguments);
          _eventController.add(
            FileTransferCancelledEvent(
              transferId: data.transferId,
              fileName: data.fileName,
              fileSize: data.fileSize,
              bytesTransferred: data.bytesTransferred,
              isUploading: data.isUploading,
              error: data.error,
            ),
          );
          break;

        case 'onFileTransferFailed':
          final data = _fileTransferData(call.arguments);
          _eventController.add(
            FileTransferFailedEvent(
              transferId: data.transferId,
              fileName: data.fileName,
              fileSize: data.fileSize,
              bytesTransferred: data.bytesTransferred,
              isUploading: data.isUploading,
              error: data.error,
            ),
          );
          break;

        case 'onReceiveDestinationChanged':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            ReceiveDestinationChangedEvent(
              ReceiveDestinationInfo.fromMap(data),
              message: data['message']?.toString(),
            ),
          );
          break;

        case 'onSmsList':
          final data = Map<String, dynamic>.from(call.arguments as Map);
          _eventController.add(
            SmsListEvent(
              (data['conversations'] as List<dynamic>? ?? const [])
                  .map((item) => SmsConversationInfo.fromMap(
                        Map<String, dynamic>.from(item as Map),
                      ))
                  .toList(),
              (data['messages'] as List<dynamic>? ?? const [])
                  .map((item) => SmsMessageInfo.fromMap(
                        Map<String, dynamic>.from(item as Map),
                      ))
                  .toList(),
            ),
          );
          break;

        case 'onSmsEvent':
          _eventController.add(
            SmsOutcomeEvent(
              SmsSendOutcome.fromMap(
                Map<String, dynamic>.from(call.arguments as Map),
              ),
            ),
          );
          break;

        case 'onGatewayStatus':
          final status = GatewayStatusInfo.fromMap(
            Map<String, dynamic>.from(call.arguments as Map),
          );
          if (status != null) {
            _eventController.add(GatewayStatusEvent(status));
          }
          break;

        case 'onRemoteCallState':
          _eventController.add(
            RemoteCallStateEvent(
              RemoteCallState.fromMap(
                Map<String, dynamic>.from(call.arguments as Map),
              ),
            ),
          );
          break;

        case 'onPairingCodeDisplay':
          final data = Map<String, dynamic>.from(call.arguments as Map);
          _eventController.add(
            PairingCodeDisplayEvent(
              data['peerDeviceId']?.toString() ?? '',
              data['peerName']?.toString() ?? '',
              data['code']?.toString() ?? '',
            ),
          );
          break;

        case 'onPairingCodeRequired':
          final data = Map<String, dynamic>.from(call.arguments as Map);
          final handler = pairingCodeRequestHandler;
          if (handler == null) {
            return null;
          }
          return await handler(
            data['peerDeviceId']?.toString() ?? '',
            data['peerName']?.toString() ?? '',
          );

        case 'onPairingVerify':
          final data = Map<String, dynamic>.from(call.arguments as Map);
          final handler = pairingVerifyHandler;
          if (handler == null) {
            return false;
          }
          return await handler(
            data['peerDeviceId']?.toString() ?? '',
            data['shortAuthString']?.toString() ?? '',
          );

        case 'onPairingFinished':
          final data = Map<String, dynamic>.from(call.arguments as Map);
          _eventController.add(
            PairingFinishedEvent(
              data['peerDeviceId']?.toString() ?? '',
              data['paired'] == true,
            ),
          );
          break;

        case 'onError':
          final String error = call.arguments;
          _eventController.add(ErrorEvent(error));
          break;

        case 'onAudioStateChanged':
          final Map<String, dynamic> audioData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            AudioStateChangedEvent(
              mode: audioData['mode']?.toString() ?? 'idle',
              state: audioData['state']?.toString() ?? 'idle',
              streamId: _readInt(audioData['streamId']),
              source: audioData['source']?.toString() ?? 'microphone',
              encoding: audioData['encoding']?.toString() ?? 'opus',
              latencyMode: audioData['latencyMode']?.toString() ?? 'lowLatency',
              qualityMode: audioData['qualityMode']?.toString() ?? 'standard',
              peerReady: audioData['peerReady'] == true,
              isStreaming: audioData['isStreaming'] == true,
              message: audioData['message']?.toString() ?? '',
            ),
          );
          break;

        case 'onAudioStats':
          final Map<String, dynamic> statsData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            AudioStatsEvent(
              mode: statsData['mode']?.toString() ?? 'idle',
              state: statsData['state']?.toString() ?? 'idle',
              streamId: _readInt(statsData['streamId']),
              latencyMode: statsData['latencyMode']?.toString() ?? 'lowLatency',
              qualityMode: statsData['qualityMode']?.toString() ?? 'standard',
              bitrateBps: _readInt(statsData['bitrateBps']),
              configuredBitrateBps: _readInt(
                statsData['configuredBitrateBps'],
                fallback: 32000,
              ),
              bufferLevelMs: _readInt(statsData['bufferLevelMs']),
              framesSent: _readInt(statsData['framesSent']),
              framesReceived: _readInt(statsData['framesReceived']),
              droppedFrames: _readInt(statsData['droppedFrames']),
              packetLossCount: _readInt(statsData['packetLossCount']),
              latePacketDrops: _readInt(statsData['latePacketDrops']),
              overflowDrops: _readInt(statsData['overflowDrops']),
              duplicatePackets: _readInt(statsData['duplicatePackets']),
              reorderedPackets: _readInt(statsData['reorderedPackets']),
              underflowCount: _readInt(statsData['underflowCount']),
              plcCount: _readInt(statsData['plcCount']),
              rtpPacketsSent: _readInt(statsData['rtpPacketsSent']),
              rtpPacketsReceived: _readInt(statsData['rtpPacketsReceived']),
              rtpBytesSent: _readInt(statsData['rtpBytesSent']),
              rtpBytesReceived: _readInt(statsData['rtpBytesReceived']),
              rtcpPacketsSent: _readInt(statsData['rtcpPacketsSent']),
              rtcpPacketsReceived: _readInt(statsData['rtcpPacketsReceived']),
              rtcpFractionLost: _readInt(statsData['rtcpFractionLost']),
              rtcpJitter: _readInt(statsData['rtcpJitter']),
              rtcpPacketCount: _readInt(statsData['rtcpPacketCount']),
              rtcpOctetCount: _readInt(statsData['rtcpOctetCount']),
              roundTripMs: _readInt(statsData['roundTripMs']),
              encodeErrorCount: _readInt(statsData['encodeErrorCount']),
              decodeErrorCount: _readInt(statsData['decodeErrorCount']),
              udpSendErrorCount: _readInt(statsData['udpSendErrorCount']),
              udpReceiveErrorCount: _readInt(statsData['udpReceiveErrorCount']),
              latencyMs: _readInt(statsData['latencyMs']),
            ),
          );
          break;

        case 'onAudioError':
          final Map<String, dynamic> errorData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
          _eventController.add(
            AudioErrorEvent(
              code: errorData['code']?.toString() ?? 'audio_error',
              message: errorData['message']?.toString() ?? 'Audio Link error',
              streamId: _readInt(errorData['streamId']),
            ),
          );
          break;

        case 'onWifiDirectReset':
          _eventController.add(WiFiDirectResetEvent());
          break;

        case 'onPermissionDenied':
          if (call.arguments is Map) {
            final Map<String, dynamic> permissionData =
                Map<String, dynamic>.from(call.arguments as Map);
            final capabilities = permissionData['capabilities'];
            _eventController.add(
              PermissionDeniedEvent(
                capabilities is List
                    ? capabilities.map((item) => item.toString()).toList()
                    : const [],
              ),
            );
          } else {
            _eventController.add(PermissionDeniedEvent());
          }
          break;

        case 'onClientConnected':
          final String message = call.arguments;
          _eventController.add(ClientConnectedEvent(message));
          break;

        case 'onDebug':
          final String message = call.arguments;
          _eventController.add(DebugEvent(message));
          break;
      }
    } catch (e) {
      _eventController.add(ErrorEvent('Error handling method call: $e'));
    }
  }

  // WiFi Direct operations
  Future<String> discoverPeers() async {
    try {
      final String result = await _channel.invokeMethod('discoverPeers');
      return result;
    } catch (e) {
      throw Exception('Failed to discover peers: $e');
    }
  }

  Future<String> connectToPeer(String deviceAddress) async {
    try {
      final String result = await _channel.invokeMethod('connectToPeer', {
        'deviceAddress': deviceAddress,
      });
      return result;
    } catch (e) {
      throw Exception('Failed to connect to peer: $e');
    }
  }

  Future<String> disconnect() async {
    try {
      final String result = await _channel.invokeMethod('disconnect');
      return result;
    } catch (e) {
      throw Exception('Failed to disconnect: $e');
    }
  }

  Future<String> sendData(String data) async {
    try {
      final String result = await _channel.invokeMethod('sendData', {
        'data': data,
      });
      return result;
    } catch (e) {
      throw Exception('Failed to send data: $e');
    }
  }

  Future<void> startFileTransfer({
    required String transferId,
    required String filePath,
    required String fileName,
  }) async {
    try {
      await _channel.invokeMethod('startFileTransfer', {
        'transferId': transferId,
        'filePath': filePath,
        'fileName': fileName,
      });
    } catch (e) {
      throw Exception('Failed to start file transfer: $e');
    }
  }

  Future<void> cancelFileTransfer(String transferId) async {
    try {
      await _channel.invokeMethod('cancelFileTransfer', {
        'transferId': transferId,
      });
    } catch (e) {
      throw Exception('Failed to cancel file transfer: $e');
    }
  }

  Future<ReceiveDestinationInfo> getReceiveDestination() async {
    final result = await _channel.invokeMethod('getReceiveDestination');
    return ReceiveDestinationInfo.fromMap(
      Map<String, dynamic>.from(result as Map),
    );
  }

  Future<ReceiveDestinationInfo> setReceiveDestination(String mode) async {
    final result = await _channel.invokeMethod('setReceiveDestination', {
      'mode': mode,
    });
    return ReceiveDestinationInfo.fromMap(
      Map<String, dynamic>.from(result as Map),
    );
  }

  Future<ReceiveDestinationInfo?> pickCustomReceiveDestination() async {
    final result = await _channel.invokeMethod('pickCustomReceiveDestination');
    if (result == null) return null;
    return ReceiveDestinationInfo.fromMap(
      Map<String, dynamic>.from(result as Map),
    );
  }

  /// Paired peers. Never includes key material.
  Future<List<Map<String, dynamic>>> getPairedDevices() async {
    try {
      final result = await _channel.invokeMethod('getPairedDevices');
      return (result as List<dynamic>? ?? const [])
          .map((item) => Map<String, dynamic>.from(item as Map))
          .toList();
    } catch (e) {
      return const [];
    }
  }

  /// Forgets a pairing, and disconnects if it was the connected peer.
  ///
  /// This is the only recovery path from a `pairing_desynchronised` failure,
  /// where the two devices' stored secrets have diverged and no typed code can
  /// succeed until one side forgets the other.
  Future<bool> forgetPairedDevice(String deviceId) async {
    try {
      return await _channel.invokeMethod('forgetPairedDevice', {
            'deviceId': deviceId,
          }) ==
          true;
    } catch (e) {
      return false;
    }
  }

  Future<bool> forgetAllPairings() async {
    try {
      return await _channel.invokeMethod('forgetAllPairings') == true;
    } catch (e) {
      return false;
    }
  }

  /// What this device can actually do, with a reason for each gap.
  Future<CompatibilityReportInfo?> getCompatibilityReport() async {
    try {
      final result = await _channel.invokeMethod('getCompatibilityReport');
      if (result == null) return null;
      return CompatibilityReportInfo.fromMap(Map<String, dynamic>.from(result as Map));
    } catch (e) {
      return null;
    }
  }

  /// Internet reachability. Deliberately separate from the Wi-Fi Direct control
  /// link, which works with no Internet at all.
  Future<DataSharingInfo?> getDataSharingStatus() async {
    try {
      final result = await _channel.invokeMethod('getDataSharingStatus');
      if (result == null) return null;
      return DataSharingInfo.fromMap(Map<String, dynamic>.from(result as Map));
    } catch (e) {
      return null;
    }
  }

  Future<Map<String, dynamic>> getConnectionStats() async {
    try {
      final result = await _channel.invokeMethod('getConnectionStats');
      return Map<String, dynamic>.from(result as Map);
    } catch (e) {
      throw Exception('Failed to get connection stats: $e');
    }
  }

  Future<String> getDiagnosticLogs() async {
    try {
      final String result = await _channel.invokeMethod('getDiagnosticLogs');
      return result;
    } catch (e) {
      throw Exception('Failed to get diagnostic logs: $e');
    }
  }

  Future<String> clearDiagnosticLogs() async {
    try {
      final String result = await _channel.invokeMethod('clearDiagnosticLogs');
      return result;
    } catch (e) {
      throw Exception('Failed to clear diagnostic logs: $e');
    }
  }

  Future<Map<String, dynamic>> getDeviceSettings() async {
    try {
      final result = await _channel.invokeMethod('getDeviceSettings');
      return Map<String, dynamic>.from(result as Map);
    } catch (e) {
      throw Exception('Failed to get device settings: $e');
    }
  }

  Future<bool> isWifiP2pEnabled() async {
    try {
      final bool result = await _channel.invokeMethod('isWifiP2pEnabled');
      return result;
    } catch (e) {
      throw Exception('Failed to check WiFi P2P status: $e');
    }
  }

  Future<Map<String, dynamic>> getDiscoveryStatus() async {
    try {
      final result = await _channel.invokeMethod('getDiscoveryStatus');
      return Map<String, dynamic>.from(result as Map);
    } catch (e) {
      throw Exception('Failed to get discovery status: $e');
    }
  }

  Future<void> stopDiscovery() async {
    try {
      await _channel.invokeMethod('stopDiscovery');
    } catch (e) {
      throw Exception('Failed to stop discovery: $e');
    }
  }

  Future<String> resetWifiDirectSettings() async {
    try {
      final String result = await _channel.invokeMethod(
        'resetWifiDirectSettings',
      );
      return result;
    } catch (e) {
      throw Exception('Failed to reset WiFi Direct settings: $e');
    }
  }

  Future<void> setSpeedTesting(bool enabled) async {
    try {
      await _channel.invokeMethod('setSpeedTesting', {'enabled': enabled});
    } catch (e) {
      throw Exception('Failed to set speed testing mode: $e');
    }
  }

  Future<String> requestSpeedTestData(int sizeBytes) async {
    try {
      final String result = await _channel.invokeMethod(
        'requestSpeedTestData',
        {'sizeBytes': sizeBytes},
      );
      return result;
    } catch (e) {
      throw Exception('Failed to request speed test data: $e');
    }
  }

  Future<String> sendSpeedTestData(int sizeBytes) async {
    try {
      final String result = await _channel.invokeMethod('sendSpeedTestData', {
        'sizeBytes': sizeBytes,
      });
      return result;
    } catch (e) {
      throw Exception('Failed to send speed test data: $e');
    }
  }

  // --- Remote SIM gateway -------------------------------------------------

  /// Asks the paired gateway to report its cellular state.
  ///
  /// Returns false when the session is not authenticated, in which case nothing
  /// was sent: gateway state is not handed to an unpaired peer.
  Future<bool> requestGatewayStatus() async {
    try {
      return await _channel.invokeMethod('requestGatewayStatus') == true;
    } catch (e) {
      return false;
    }
  }

  /// The last status the peer gateway reported, or null if it has not reported
  /// one. Null must render as unavailable, never as defaults.
  Future<GatewayStatusInfo?> getGatewayStatus() async {
    try {
      final result = await _channel.invokeMethod('getGatewayStatus');
      if (result == null) return null;
      return GatewayStatusInfo.fromMap(Map<String, dynamic>.from(result as Map));
    } catch (e) {
      return null;
    }
  }

  /// This device's own cellular state, for showing what it can offer as a gateway.
  Future<GatewayStatusInfo?> getLocalGatewayStatus() async {
    try {
      final result = await _channel.invokeMethod('getLocalGatewayStatus');
      if (result == null) return null;
      return GatewayStatusInfo.fromMap(Map<String, dynamic>.from(result as Map));
    } catch (e) {
      return null;
    }
  }

  /// Asks the gateway to dial [number].
  ///
  /// Returns the request id, or null if the request was refused before it went
  /// on the wire — an invalid number, a gateway that cannot dial, a call already
  /// in progress. A refusal always arrives as a `failed` [RemoteCallStateEvent]
  /// carrying the reason, so the caller does not need to interpret the null.
  Future<String?> placeRemoteCall(String number) async {
    try {
      final result = await _channel.invokeMethod('placeRemoteCall', {
        'number': number,
      });
      return result as String?;
    } catch (e) {
      return null;
    }
  }

  Future<bool> answerRemoteCall() async {
    try {
      return await _channel.invokeMethod('answerRemoteCall') == true;
    } catch (e) {
      return false;
    }
  }

  Future<bool> rejectRemoteCall() async {
    try {
      return await _channel.invokeMethod('rejectRemoteCall') == true;
    } catch (e) {
      return false;
    }
  }

  Future<bool> hangUpRemoteCall() async {
    try {
      return await _channel.invokeMethod('hangUpRemoteCall') == true;
    } catch (e) {
      return false;
    }
  }

  /// Asks the gateway for its conversations.
  ///
  /// Returns false when the gateway has not advertised `sms.read`, which is the
  /// common case: Android restricts reading SMS to the device's default SMS
  /// handler. Nothing is sent in that case.
  Future<bool> requestSmsList({int limit = 200}) async {
    try {
      return await _channel.invokeMethod('requestSmsList', {'limit': limit}) == true;
    } catch (e) {
      return false;
    }
  }

  /// Asks the gateway to send an SMS.
  ///
  /// Returns the request id, or null if refused before it went on the wire. A
  /// refusal always arrives as a failed [SmsOutcomeEvent] carrying the reason.
  Future<String?> sendRemoteSms(String address, String body) async {
    try {
      final result = await _channel.invokeMethod('sendRemoteSms', {
        'address': address,
        'body': body,
      });
      return result as String?;
    } catch (e) {
      return null;
    }
  }

  Future<Map<String, dynamic>> getAudioSupport() async {
    try {
      final result = await _channel.invokeMethod('getAudioSupport');
      return Map<String, dynamic>.from(result as Map);
    } catch (e) {
      throw Exception('Failed to get audio support: $e');
    }
  }

  Future<String> startAudio({
    required String mode,
    String source = 'microphone',
    String encoding = 'opus',
    String? latencyMode,
    String? qualityMode,
  }) async {
    try {
      final arguments = <String, Object?>{
        'mode': mode,
        'source': source,
        'encoding': encoding,
      };
      if (latencyMode != null) {
        arguments['latencyMode'] = latencyMode;
      }
      if (qualityMode != null) {
        arguments['qualityMode'] = qualityMode;
      }
      final String result = await _channel.invokeMethod(
        'startAudio',
        arguments,
      );
      return result;
    } catch (e) {
      throw Exception('Failed to start audio: $e');
    }
  }

  Future<String> stopAudio() async {
    try {
      final String result = await _channel.invokeMethod('stopAudio');
      return result;
    } catch (e) {
      throw Exception('Failed to stop audio: $e');
    }
  }

  void dispose() {
    _eventController.close();
  }

  int _readInt(Object? value, {int fallback = 0}) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? fallback;
  }
}

int _readEventInt(Object? value, {int fallback = 0}) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? fallback;
}

_PlatformFileTransferData _fileTransferData(Object? arguments) {
  final data = Map<String, dynamic>.from(arguments as Map);
  return _PlatformFileTransferData(
    transferId: data['transferId']?.toString() ?? '',
    fileName: data['fileName']?.toString() ?? 'unknown_file',
    fileSize: _readEventInt(data['fileSize'], fallback: -1),
    bytesTransferred: _readEventInt(data['bytesTransferred']),
    isUploading: data['direction']?.toString() == 'send',
    status: data['status']?.toString(),
    filePath: data['filePath']?.toString(),
    savedLocation: data['savedLocation']?.toString(),
    error: data['error']?.toString(),
  );
}

class _PlatformFileTransferData {
  final String transferId;
  final String fileName;
  final int fileSize;
  final int bytesTransferred;
  final bool isUploading;
  final String? status;
  final String? filePath;
  final String? savedLocation;
  final String? error;

  const _PlatformFileTransferData({
    required this.transferId,
    required this.fileName,
    required this.fileSize,
    required this.bytesTransferred,
    required this.isUploading,
    this.status,
    this.filePath,
    this.savedLocation,
    this.error,
  });
}
