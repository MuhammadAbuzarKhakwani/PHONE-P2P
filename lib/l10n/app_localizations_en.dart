// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Khakwani P2P';

  @override
  String get wifiP2pDriver => 'WiFi P2P Driver';

  @override
  String get readyForConnections => 'Ready for connections';

  @override
  String get disabledEnableWifi => 'Disabled - Enable WiFi to continue';

  @override
  String get connectionStatus => 'Connection Status';

  @override
  String get connected => 'Connected';

  @override
  String get disconnected => 'Disconnected';

  @override
  String get scanForDevices => 'Scan for Devices';

  @override
  String get scanning => 'Scanning...';

  @override
  String get stopScan => 'Stop Scan';

  @override
  String get deviceInfo => 'Device Info';

  @override
  String get resetWifiDirect => 'Reset WiFi Direct';

  @override
  String get noDevicesFound => 'No devices found';

  @override
  String get tapScanForDevices =>
      'Tap \"Scan for Devices\" to find nearby devices';

  @override
  String availableDevices(int count) {
    return 'Available Devices ($count)';
  }

  @override
  String get connect => 'Connect';

  @override
  String get disconnect => 'Disconnect';

  @override
  String get logs => 'Logs';

  @override
  String get clearLogs => 'Clear Logs';

  @override
  String get chat => 'Chat';

  @override
  String get speedTest => 'Speed Test';

  @override
  String get fileTransfer => 'File Transfer';

  @override
  String get settings => 'Settings';

  @override
  String get connectedReadyToChat => 'Connected - Ready to chat';

  @override
  String get notConnectedConnectToPeer =>
      'Not connected - Connect to a peer to start chatting';

  @override
  String get host => 'Host';

  @override
  String get client => 'Client';

  @override
  String get noMessagesYet => 'No messages yet';

  @override
  String get connectToPeerAndStartChatting =>
      'Connect to a peer and start chatting!';

  @override
  String get typeAMessage => 'Type a message...';

  @override
  String get connectToStartChatting => 'Connect to start chatting';

  @override
  String get justNow => 'Just now';

  @override
  String daysAgo(int count) {
    return '${count}d ago';
  }

  @override
  String hoursAgo(int count) {
    return '${count}h ago';
  }

  @override
  String minutesAgo(int count) {
    return '${count}m ago';
  }

  @override
  String get pleaseConnectToPeerFirst => 'Please connect to a peer first';

  @override
  String fileSent(String fileName) {
    return 'File sent: $fileName';
  }

  @override
  String failedToSendFile(String error) {
    return 'Failed to send file: $error';
  }

  @override
  String get notConnected => 'Not Connected';

  @override
  String get readyForFileTransfer => 'Ready for file transfer';

  @override
  String get connectToStartTransferringFiles =>
      'Connect to start transferring files';

  @override
  String get sendFile => 'Send File';

  @override
  String get receiveFiles => 'Receive Files';

  @override
  String filesWillBeAutomaticallyReceived(String location) {
    return 'Files sent by the peer will be saved to $location';
  }

  @override
  String get saveReceivedFilesTo => 'Save received files to';

  @override
  String get appStorage => 'App storage';

  @override
  String get downloadsFolder => 'Downloads';

  @override
  String get chooseCustomFolder => 'Choose custom folder';

  @override
  String get receiveDestinationFailed => 'Could not use that receive location';

  @override
  String fileReceived(String fileName) {
    return 'File received: $fileName';
  }

  @override
  String fileTransferCancelled(String fileName) {
    return 'Transfer cancelled: $fileName';
  }

  @override
  String fileTransferFailed(String fileName, String error) {
    return 'Transfer failed for $fileName: $error';
  }

  @override
  String get preparing => 'Preparing';

  @override
  String get queued => 'Queued';

  @override
  String get cancelling => 'Cancelling...';

  @override
  String get cancelled => 'Cancelled';

  @override
  String get failed => 'Failed';

  @override
  String get cancel => 'Cancel';

  @override
  String get noActiveTransfers => 'No Active Transfers';

  @override
  String get uploadComplete => 'Upload Complete';

  @override
  String get downloadComplete => 'Download Complete';

  @override
  String get uploading => 'Uploading...';

  @override
  String get downloading => 'Downloading...';

  @override
  String get recentTransfers => 'Recent Transfers';

  @override
  String get noRecentTransfers => 'No recent transfers';

  @override
  String get sent => 'Sent';

  @override
  String get received => 'Received';

  @override
  String get openFile => 'Open file';

  @override
  String get clear => 'Clear';

  @override
  String failedToOpenFile(String error) {
    return 'Failed to open file: $error';
  }

  @override
  String noSupportedAppToOpenFile(String fileName, String location) {
    return 'There is no supported app to open $fileName. It’s saved in $location.';
  }

  @override
  String get systemLogs => 'System Logs';

  @override
  String get noLogsYet => 'No logs yet';

  @override
  String get customizeYourWifiDirectExperience =>
      'Customize your WiFi Direct experience';

  @override
  String get appSettings => 'App Settings';

  @override
  String get language => 'Language';

  @override
  String get chooseYourPreferredLanguage => 'Choose your preferred language';

  @override
  String get followSystem => 'Follow System';

  @override
  String get english => 'English';

  @override
  String get darkMode => 'Dark Mode';

  @override
  String get useDarkTheme => 'Use dark theme';

  @override
  String get about => 'About';

  @override
  String get version => 'Version';

  @override
  String get privacyPolicy => 'Privacy Policy';

  @override
  String get viewOurPrivacyPolicy => 'View our privacy policy';

  @override
  String get privacyPolicyContent =>
      'This app uses WiFi Direct to establish peer-to-peer connections. No data is sent to external servers. All communications happen directly between devices.';

  @override
  String get ok => 'OK';

  @override
  String get speedTestStatus => 'Speed Test Status';

  @override
  String get readyToTestConnectionSpeed => 'Ready to test connection speed';

  @override
  String get connectToPeerToTestSpeed => 'Connect to a peer to test speed';

  @override
  String get testing => 'Testing...';

  @override
  String get start => 'START';

  @override
  String get tapToStartSpeedTest => 'Tap to start speed test';

  @override
  String get connectToPeerFirst => 'Connect to a peer first';

  @override
  String get downloadTest => 'Download Test';

  @override
  String get uploadTest => 'Upload Test';

  @override
  String get download => 'Download';

  @override
  String get upload => 'Upload';

  @override
  String speed(String speed) {
    return 'Speed: $speed MB/s';
  }

  @override
  String get complete => 'Complete';

  @override
  String get inProgress => 'In Progress';

  @override
  String get latestResults => 'Latest Results';

  @override
  String get testCompletedAt => 'Test completed at';

  @override
  String get noTestResultsYet => 'No test results yet';

  @override
  String get runSpeedTestToSeeResults => 'Run a speed test to see results here';

  @override
  String get testHistory => 'Test History';

  @override
  String tests(int count) {
    return '$count tests';
  }

  @override
  String get noTestHistory => 'No test history';

  @override
  String dayAgo(int count) {
    return '$count day ago';
  }

  @override
  String daysAgoLong(int count) {
    return '$count days ago';
  }

  @override
  String hourAgo(int count) {
    return '$count hour ago';
  }

  @override
  String hoursAgoLong(int count) {
    return '$count hours ago';
  }

  @override
  String minuteAgo(int count) {
    return '$count minute ago';
  }

  @override
  String minutesAgoLong(int count) {
    return '$count minutes ago';
  }

  @override
  String get chinese => '中文';

  @override
  String get githubRepositories => 'GitHub Repositories';

  @override
  String get flutterAppRepository => 'Flutter App Repository';

  @override
  String get flutterAppDescription =>
      'WDCable Flutter - Mobile WiFi Direct file transfer app';

  @override
  String get windowsAppRepository => 'Windows App Repository';

  @override
  String get windowsAppDescription =>
      'WDCableWUI - Windows companion application';

  @override
  String urlCopiedToClipboard(String url) {
    return 'URL copied to clipboard: $url';
  }

  @override
  String get settingsTitle => 'Settings';

  @override
  String get settingsSubtitle => 'Customize your WiFi Direct experience';

  @override
  String get audioLink => 'Audio Link';

  @override
  String get audioConnectToPeerFirst => 'Connect to a peer first';

  @override
  String get audioPeerUnsupported =>
      'The connected peer does not support Audio Link';

  @override
  String get audioReady => 'Audio Link is ready';

  @override
  String get audioMode => 'Mode';

  @override
  String get audioReceive => 'Receive';

  @override
  String get audioSend => 'Send';

  @override
  String get audioSource => 'Source';

  @override
  String get audioMicrophone => 'Microphone';

  @override
  String get audioDeviceAudioUnavailable => 'Device audio unavailable';

  @override
  String get audioLatencyMode => 'Latency Mode';

  @override
  String get audioLowLatency => 'Low latency';

  @override
  String get audioStable => 'Stable';

  @override
  String get audioQualityMode => 'Quality';

  @override
  String get audioQualityStandard => 'Standard';

  @override
  String get audioQualityBalanced => 'Balanced';

  @override
  String get audioQualityHigh => 'High';

  @override
  String get audioQualityNearLossless => 'Near lossless';

  @override
  String get audioEncoding => 'Encoding';

  @override
  String get audioOpus => 'Opus';

  @override
  String get audioOpus32Kbps => 'Opus 32 kbps';

  @override
  String get audioOnlyOption => 'Only option';

  @override
  String get audioFollowSenderSide => 'Follow sender side';

  @override
  String get audioStop => 'Stop Audio';

  @override
  String get audioStart => 'Start Audio';

  @override
  String get audioLiveStats => 'Live Stats';

  @override
  String get audioState => 'State';

  @override
  String get audioBitrate => 'Bitrate';

  @override
  String get audioConfiguredBitrate => 'Configured';

  @override
  String get audioQuality => 'Quality';

  @override
  String get audioBuffer => 'Buffer';

  @override
  String get audioDropped => 'Dropped';

  @override
  String get audioPacketLoss => 'Packet Loss';

  @override
  String get audioLateDrops => 'Late Drops';

  @override
  String get audioOverflowDrops => 'Overflow Drops';

  @override
  String get audioPlc => 'PLC';

  @override
  String get audioRtcpLoss => 'RTCP Loss';

  @override
  String get audioRtcpJitter => 'RTCP Jitter';

  @override
  String get audioRoundTrip => 'Round Trip';

  @override
  String get audioFrames => 'Frames';

  @override
  String get audioLatency => 'Latency';

  @override
  String get audioStateReceiveReady => 'Receive ready';

  @override
  String get audioStateOfferSent => 'Offer sent';

  @override
  String get audioStateConnecting => 'Connecting';

  @override
  String get audioStateStreaming => 'Streaming';

  @override
  String get audioStateIdle => 'Idle';

  @override
  String get notAvailableShort => 'N/A';

  @override
  String get kbpsUnit => 'kbps';

  @override
  String get pairingTitle => 'Pair devices';

  @override
  String get pairingCodeDisplayMessage => 'Enter this code on the other phone.';

  @override
  String get pairingWaitingForPeer => 'Waiting for the other phone…';

  @override
  String get pairingEnterCodeTitle => 'Enter pairing code';

  @override
  String get pairingEnterCodeMessage => 'Type the 6-digit code shown on the other phone.';

  @override
  String get pairingCodeInvalid => 'Enter all 6 digits.';

  @override
  String get pairingVerifyTitle => 'Do these match?';

  @override
  String get pairingVerifyMessage => 'Both phones should show the same number. If they differ, someone may be intercepting the connection. Do not continue.';

  @override
  String get pairingMatches => 'They match';

  @override
  String get pairingDoesNotMatch => 'They differ';

  @override
  String get pairingCancel => 'Cancel';

  @override
  String get pairingConfirm => 'Confirm';

  @override
  String get pairingUnverifiedWarning => 'Paired, but not verified. Anyone nearby could have intercepted this pairing.';

  @override
  String get dialerTitle => 'Dialer';

  @override
  String get dialerEnterNumber => 'Enter a number';

  @override
  String get dialerCall => 'Call';

  @override
  String get dialerHangUp => 'Hang up';

  @override
  String get dialerAnswer => 'Answer';

  @override
  String get dialerReject => 'Reject';

  @override
  String get dialerNoGateway => 'Not connected to a gateway.';

  @override
  String get dialerGatewayCannotDial => 'This gateway cannot place calls.';

  @override
  String get gatewayTitle => 'Gateway';

  @override
  String get gatewayUnavailableValue => 'Unavailable on this device';

  @override
  String get gatewaySim => 'SIM';

  @override
  String get gatewayCarrier => 'Carrier';

  @override
  String get gatewayNetwork => 'Network';

  @override
  String get gatewaySignal => 'Signal';

  @override
  String get gatewayMobileData => 'Mobile data';

  @override
  String get gatewayBattery => 'Battery';

  @override
  String get gatewayCallAudioNotice => 'Remote cellular call audio is not supported on this device. The conversation happens on the gateway phone\'s own speaker and microphone.';

  @override
  String get gatewayRefreshStatus => 'Refresh';

  @override
  String get callStateDialing => 'Dialing';

  @override
  String get callStateRinging => 'Ringing';

  @override
  String get callStateActive => 'In call';

  @override
  String get callStateEnded => 'Call ended';

  @override
  String get callStateFailed => 'Call failed';

  @override
  String get callSinceDialing => 'Since dialing';

  @override
  String get callAnswerNotConfirmed => 'Android cannot confirm when the other side answers, so this counts from dialing.';

  @override
  String get simStateReady => 'Ready';

  @override
  String get simStateAbsent => 'No SIM';

  @override
  String get simStateLocked => 'Locked';

  @override
  String get simStateUnknown => 'Unknown';

  @override
  String get messagesTitle => 'Messages';

  @override
  String get messagesNoGateway => 'Not connected to a gateway.';

  @override
  String get messagesCannotRead => 'This gateway cannot read messages. Android restricts reading SMS to the phone\'s default messaging app.';

  @override
  String get messagesCannotSend => 'This gateway cannot send messages.';

  @override
  String get messagesNone => 'No conversations';

  @override
  String get messagesRecipient => 'To';

  @override
  String get messagesBody => 'Message';

  @override
  String get messagesSend => 'Send';

  @override
  String get messagesRefresh => 'Refresh';

  @override
  String get messagesHandedToNetwork => 'Handed to the network. Delivery is not confirmed.';

  @override
  String get messagesSendFailed => 'Could not send the message.';

  @override
  String get messagesSentByGateway => 'Sent from the gateway phone\'s SIM.';

  @override
  String get diagnosticsTitle => 'Diagnostics';

  @override
  String get diagnosticsCompatibility => 'Device compatibility';

  @override
  String get diagnosticsSupported => 'Supported';

  @override
  String get diagnosticsPartial => 'Partially supported';

  @override
  String get diagnosticsUnsupported => 'Not supported';

  @override
  String get diagnosticsConnection => 'Connection';

  @override
  String get diagnosticsSecurity => 'Security';

  @override
  String get diagnosticsInternet => 'Internet';

  @override
  String get diagnosticsRefresh => 'Refresh';

  @override
  String get diagnosticsSecureActive => 'Encrypted and authenticated';

  @override
  String get diagnosticsSecureInactive => 'Not encrypted. This link is running the legacy plaintext protocol.';

  @override
  String get diagnosticsSasUnverified => 'Paired, but the confirmation code was not verified on both phones.';

  @override
  String get diagnosticsInternetSeparate => 'This is separate from the Wi-Fi Direct link, which works with no Internet at all.';

  @override
  String get diagnosticsTetheringManual => 'Android does not let an app turn tethering on. Enable the hotspot in Settings if you want to share mobile data.';

  @override
  String get diagnosticsHotspotUnknown => 'Android does not let an app read hotspot state.';

  @override
  String get diagnosticsNoInternet => 'No Internet on this device';

  @override
  String get diagnosticsPacketsSent => 'Frames sent';

  @override
  String get diagnosticsPacketsReceived => 'Frames received';

  @override
  String get diagnosticsRejected => 'Frames rejected';

  @override
  String get diagnosticsDuplicates => 'Duplicates dropped';

  @override
  String get diagnosticsReplays => 'Replays dropped';

  @override
  String get diagnosticsLocalDevice => 'This device';

  @override
  String get diagnosticsPeerDevice => 'Paired peer';

  @override
  String get pairingForgetDevice => 'Forget this device';

  @override
  String get pairingForgetConfirm => 'Forget this pairing? You will need to pair again with a new code, and the current connection will be closed.';

  @override
  String get pairingForgetAll => 'Forget all paired devices';

  @override
  String get pairingNoneStored => 'No paired devices';

  @override
  String get pairingDesynchronised => 'This device and the gateway no longer agree on their stored pairing. Forget the device and pair again.';

  @override
  String get commonCancel => 'Cancel';
}
