/// Models for the remote SIM gateway (Phone 1) as seen by the client (Phone 2).
library;

/// What Phone 1 reports about its cellular state.
///
/// Every optional field is nullable, and null means **the platform did not
/// expose this value on that device**, not zero and not "unknown". The UI must
/// render null as "Unavailable on this device" rather than substituting a
/// plausible-looking default — a user cannot tell a real zero-bar reading from a
/// value that was never returned.
class GatewayStatusInfo {
  const GatewayStatusInfo({
    required this.simState,
    required this.callState,
    this.carrier,
    this.networkType,
    this.signalLevel,
    this.mobileData,
    this.batteryPercent,
    this.charging,
    this.gatewayVersion = '',
    this.deviceName = '',
    this.capabilities = const [],
    this.unavailableReasons = const {},
  });

  /// `ready`, `absent`, `locked`, `notReady`, `disabled`, `error`,
  /// `restricted`, or `unknown`. Always present.
  final String simState;

  /// `idle`, `ringing`, `offhook`, or `unknown`. Always present.
  final String callState;

  final String? carrier;
  final String? networkType;

  /// Signal bars, 0-4. Null when unavailable; 0 is a genuine reading.
  final int? signalLevel;

  final String? mobileData;
  final int? batteryPercent;
  final bool? charging;
  final String gatewayVersion;
  final String deviceName;

  /// What this gateway can actually do, given its permissions and hardware.
  final List<String> capabilities;

  /// Why an absent capability is absent, keyed by capability name.
  final Map<String, String> unavailableReasons;

  bool get hasSim => simState == 'ready';

  bool get isOnCall => callState == 'ringing' || callState == 'offhook';

  bool supports(String capability) => capabilities.contains(capability);

  /// The gateway's own reason a capability is missing, or null if it did not say.
  String? reasonFor(String capability) => unavailableReasons[capability];

  static GatewayStatusInfo? fromMap(Map<String, dynamic>? data) {
    if (data == null) return null;
    return GatewayStatusInfo(
      simState: data['simState']?.toString() ?? 'unknown',
      callState: data['callState']?.toString() ?? 'unknown',
      carrier: _stringOrNull(data['carrier']),
      networkType: _stringOrNull(data['networkType']),
      signalLevel: _intOrNull(data['signalLevel']),
      mobileData: _stringOrNull(data['mobileData']),
      batteryPercent: _intOrNull(data['batteryPercent']),
      charging: data['charging'] is bool ? data['charging'] as bool : null,
      gatewayVersion: data['gatewayVersion']?.toString() ?? '',
      deviceName: data['deviceName']?.toString() ?? '',
      capabilities: (data['capabilities'] as List<dynamic>? ?? const [])
          .map((item) => item.toString())
          .toList(),
      unavailableReasons: (data['unavailable'] as Map<dynamic, dynamic>? ?? const {})
          .map((key, value) => MapEntry(key.toString(), value.toString())),
    );
  }

  static String? _stringOrNull(Object? value) {
    if (value == null) return null;
    final text = value.toString();
    return text.isEmpty ? null : text;
  }

  static int? _intOrNull(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return null;
  }
}

/// Capability names, mirroring `ProtocolConstants` on the native side.
class GatewayCapability {
  const GatewayCapability._();

  static const String gateway = 'gateway';
  static const String status = 'gateway.status';
  static const String dial = 'telephony.dial';
  static const String answer = 'telephony.answer';
  static const String callState = 'telephony.state';
  static const String smsSend = 'sms.send';
  static const String smsRead = 'sms.read';

  /// Never advertised by any build, on any device. Present so the UI can name
  /// the capability the app does not have.
  static const String cellularCallAudio = 'telephony.audio.cellular';
}

/// A remote call's current state, as reported by the gateway.
class RemoteCallState {
  const RemoteCallState({
    required this.callId,
    required this.state,
    this.reason,
    this.durationMs,
    this.answerConfirmed = false,
    this.outgoing = false,
    this.requestId,
  });

  /// `dialing`, `ringing`, `active`, `ended`, or `failed`.
  final String state;
  final String callId;
  final String? reason;
  final int? durationMs;

  /// True only when the gateway's platform actually confirmed the call
  /// connected, which for an outgoing call it never can — Android does not tell
  /// an unprivileged app when the callee picks up. So an outgoing call's timer
  /// is time since dialling, not talk time.
  final bool answerConfirmed;

  final bool outgoing;
  final String? requestId;

  bool get isActive => state == 'active';
  bool get isRinging => state == 'ringing';
  bool get isDialing => state == 'dialing';
  bool get isTerminal => state == 'ended' || state == 'failed';
  bool get isFailed => state == 'failed';

  /// True while a call occupies the line and the UI should show call controls.
  bool get isInProgress => !isTerminal;

  static RemoteCallState fromMap(Map<String, dynamic> data) => RemoteCallState(
    callId: data['callId']?.toString() ?? 'unknown',
    state: data['state']?.toString() ?? 'failed',
    reason: GatewayStatusInfo._stringOrNull(data['reason']),
    durationMs: GatewayStatusInfo._intOrNull(data['durationMs']),
    answerConfirmed: data['answerConfirmed'] == true,
    outgoing: data['outgoing'] == true,
    requestId: GatewayStatusInfo._stringOrNull(data['requestId']),
  );
}

/// One SMS held by the gateway.
class SmsMessageInfo {
  const SmsMessageInfo({
    required this.id,
    required this.threadId,
    required this.address,
    required this.body,
    required this.timestampMs,
    required this.incoming,
    required this.read,
  });

  final int id;
  final int threadId;
  final String address;
  final String body;
  final int timestampMs;
  final bool incoming;
  final bool read;

  DateTime get timestamp => DateTime.fromMillisecondsSinceEpoch(timestampMs);

  static SmsMessageInfo fromMap(Map<String, dynamic> data) => SmsMessageInfo(
    id: GatewayStatusInfo._intOrNull(data['id']) ?? 0,
    threadId: GatewayStatusInfo._intOrNull(data['threadId']) ?? 0,
    address: data['address']?.toString() ?? '',
    body: data['body']?.toString() ?? '',
    timestampMs: GatewayStatusInfo._intOrNull(data['timestamp']) ?? 0,
    incoming: data['incoming'] == true,
    read: data['read'] == true,
  );
}

/// A conversation thread on the gateway.
class SmsConversationInfo {
  const SmsConversationInfo({
    required this.threadId,
    required this.address,
    required this.snippet,
    required this.timestampMs,
    required this.messageCount,
    required this.unreadCount,
  });

  final int threadId;
  final String address;
  final String snippet;
  final int timestampMs;
  final int messageCount;
  final int unreadCount;

  DateTime get timestamp => DateTime.fromMillisecondsSinceEpoch(timestampMs);

  static SmsConversationInfo fromMap(Map<String, dynamic> data) => SmsConversationInfo(
    threadId: GatewayStatusInfo._intOrNull(data['threadId']) ?? 0,
    address: data['address']?.toString() ?? '',
    snippet: data['snippet']?.toString() ?? '',
    timestampMs: GatewayStatusInfo._intOrNull(data['timestamp']) ?? 0,
    messageCount: GatewayStatusInfo._intOrNull(data['messageCount']) ?? 0,
    unreadCount: GatewayStatusInfo._intOrNull(data['unreadCount']) ?? 0,
  );
}

/// Outcome of an outbound SMS.
///
/// [isSent] means the gateway handed the message to the platform — **not** that
/// it was delivered. Delivery reports are carrier-dependent and are not
/// registered, so the UI must never claim delivery.
class SmsSendOutcome {
  const SmsSendOutcome({
    required this.status,
    this.requestId,
    this.reason,
    this.segments,
  });

  final String status;
  final String? requestId;
  final String? reason;
  final int? segments;

  bool get isSent => status == 'sent';

  static SmsSendOutcome fromMap(Map<String, dynamic> data) => SmsSendOutcome(
    status: data['status']?.toString() ?? 'failed',
    requestId: GatewayStatusInfo._stringOrNull(data['requestId']),
    reason: GatewayStatusInfo._stringOrNull(data['reason']),
    segments: GatewayStatusInfo._intOrNull(data['segments']),
  );
}

/// One checked capability from the compatibility report.
class CompatibilityFindingInfo {
  const CompatibilityFindingInfo({
    required this.id,
    required this.level,
    required this.required,
    this.reason,
    this.explanation,
  });

  final String id;

  /// `SUPPORTED`, `PARTIALLY_SUPPORTED`, or `UNSUPPORTED`.
  final String level;

  /// Whether this feeds the overall verdict. Cellular call audio is reported
  /// unsupported but is not required, so it never makes a device read as
  /// unusable.
  final bool required;

  final String? reason;

  /// Plain-language text supplied by the native side, so the UI never has to
  /// map reason codes to prose itself.
  final String? explanation;

  bool get isSupported => level == 'SUPPORTED';
  bool get isPartial => level == 'PARTIALLY_SUPPORTED';
  bool get isUnsupported => level == 'UNSUPPORTED';

  static CompatibilityFindingInfo fromMap(Map<String, dynamic> data) =>
      CompatibilityFindingInfo(
        id: data['id']?.toString() ?? '',
        level: data['level']?.toString() ?? 'UNSUPPORTED',
        required: data['required'] == true,
        reason: GatewayStatusInfo._stringOrNull(data['reason']),
        explanation: GatewayStatusInfo._stringOrNull(data['explanation']),
      );
}

/// What this device can actually do.
class CompatibilityReportInfo {
  const CompatibilityReportInfo({required this.overall, required this.findings});

  final String overall;
  final List<CompatibilityFindingInfo> findings;

  bool get isSupported => overall == 'SUPPORTED';
  bool get isPartial => overall == 'PARTIALLY_SUPPORTED';
  bool get isUnsupported => overall == 'UNSUPPORTED';

  static CompatibilityReportInfo? fromMap(Map<String, dynamic>? data) {
    if (data == null) return null;
    return CompatibilityReportInfo(
      overall: data['overall']?.toString() ?? 'UNSUPPORTED',
      findings: (data['findings'] as List<dynamic>? ?? const [])
          .map((item) =>
              CompatibilityFindingInfo.fromMap(Map<String, dynamic>.from(item as Map)))
          .toList(),
    );
  }
}

/// Internet reachability, kept separate from the Wi-Fi Direct control link.
///
/// [hotspotDetectable] and [canControlTethering] are always false: Android
/// exposes no public way for an app to read hotspot state or to turn tethering
/// on. The UI must direct the user to Settings rather than offering a switch.
class DataSharingInfo {
  const DataSharingInfo({
    required this.hasInternet,
    this.transport,
    this.hotspotDetectable = false,
    this.canControlTethering = false,
  });

  final bool hasInternet;
  final String? transport;
  final bool hotspotDetectable;
  final bool canControlTethering;

  static DataSharingInfo? fromMap(Map<String, dynamic>? data) {
    if (data == null) return null;
    return DataSharingInfo(
      hasInternet: data['hasInternet'] == true,
      transport: GatewayStatusInfo._stringOrNull(data['transport']),
      hotspotDetectable: data['hotspotDetectable'] == true,
      canControlTethering: data['canControlTethering'] == true,
    );
  }
}
