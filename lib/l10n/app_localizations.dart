import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_zh.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('zh'),
  ];

  /// The title of the application
  ///
  /// In en, this message translates to:
  /// **'Khakwani P2P'**
  String get appTitle;

  /// WiFi P2P driver status label
  ///
  /// In en, this message translates to:
  /// **'WiFi P2P Driver'**
  String get wifiP2pDriver;

  /// Status when WiFi P2P is enabled
  ///
  /// In en, this message translates to:
  /// **'Ready for connections'**
  String get readyForConnections;

  /// Status when WiFi P2P is disabled
  ///
  /// In en, this message translates to:
  /// **'Disabled - Enable WiFi to continue'**
  String get disabledEnableWifi;

  /// Connection status label
  ///
  /// In en, this message translates to:
  /// **'Connection Status'**
  String get connectionStatus;

  /// Connected status
  ///
  /// In en, this message translates to:
  /// **'Connected'**
  String get connected;

  /// Disconnected status
  ///
  /// In en, this message translates to:
  /// **'Disconnected'**
  String get disconnected;

  /// Button text to scan for devices
  ///
  /// In en, this message translates to:
  /// **'Scan for Devices'**
  String get scanForDevices;

  /// Text shown when scanning for devices
  ///
  /// In en, this message translates to:
  /// **'Scanning...'**
  String get scanning;

  /// Button text to stop scanning
  ///
  /// In en, this message translates to:
  /// **'Stop Scan'**
  String get stopScan;

  /// Button text to show device information
  ///
  /// In en, this message translates to:
  /// **'Device Info'**
  String get deviceInfo;

  /// Button text to reset WiFi Direct
  ///
  /// In en, this message translates to:
  /// **'Reset WiFi Direct'**
  String get resetWifiDirect;

  /// Message when no devices are found
  ///
  /// In en, this message translates to:
  /// **'No devices found'**
  String get noDevicesFound;

  /// Instruction text for scanning devices
  ///
  /// In en, this message translates to:
  /// **'Tap \"Scan for Devices\" to find nearby devices'**
  String get tapScanForDevices;

  /// Header for available devices list
  ///
  /// In en, this message translates to:
  /// **'Available Devices ({count})'**
  String availableDevices(int count);

  /// Button text to connect to a device
  ///
  /// In en, this message translates to:
  /// **'Connect'**
  String get connect;

  /// Button text to disconnect from a device
  ///
  /// In en, this message translates to:
  /// **'Disconnect'**
  String get disconnect;

  /// Logs section header
  ///
  /// In en, this message translates to:
  /// **'Logs'**
  String get logs;

  /// Button text to clear logs
  ///
  /// In en, this message translates to:
  /// **'Clear Logs'**
  String get clearLogs;

  /// Chat tab label
  ///
  /// In en, this message translates to:
  /// **'Chat'**
  String get chat;

  /// Speed test label in chat
  ///
  /// In en, this message translates to:
  /// **'Speed Test'**
  String get speedTest;

  /// File transfer tab label
  ///
  /// In en, this message translates to:
  /// **'File Transfer'**
  String get fileTransfer;

  /// Settings tab label
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settings;

  /// Status when connected and ready to chat
  ///
  /// In en, this message translates to:
  /// **'Connected - Ready to chat'**
  String get connectedReadyToChat;

  /// Status when not connected to peer
  ///
  /// In en, this message translates to:
  /// **'Not connected - Connect to a peer to start chatting'**
  String get notConnectedConnectToPeer;

  /// Host role indicator
  ///
  /// In en, this message translates to:
  /// **'Host'**
  String get host;

  /// Client role indicator
  ///
  /// In en, this message translates to:
  /// **'Client'**
  String get client;

  /// Message when no chat messages exist
  ///
  /// In en, this message translates to:
  /// **'No messages yet'**
  String get noMessagesYet;

  /// Instruction to connect and start chatting
  ///
  /// In en, this message translates to:
  /// **'Connect to a peer and start chatting!'**
  String get connectToPeerAndStartChatting;

  /// Chat input placeholder when connected
  ///
  /// In en, this message translates to:
  /// **'Type a message...'**
  String get typeAMessage;

  /// Chat input placeholder when not connected
  ///
  /// In en, this message translates to:
  /// **'Connect to start chatting'**
  String get connectToStartChatting;

  /// Timestamp for very recent messages
  ///
  /// In en, this message translates to:
  /// **'Just now'**
  String get justNow;

  /// Days ago timestamp
  ///
  /// In en, this message translates to:
  /// **'{count}d ago'**
  String daysAgo(int count);

  /// Hours ago timestamp
  ///
  /// In en, this message translates to:
  /// **'{count}h ago'**
  String hoursAgo(int count);

  /// Minutes ago timestamp
  ///
  /// In en, this message translates to:
  /// **'{count}m ago'**
  String minutesAgo(int count);

  /// Error message when trying to send file without connection
  ///
  /// In en, this message translates to:
  /// **'Please connect to a peer first'**
  String get pleaseConnectToPeerFirst;

  /// Success message when file is sent
  ///
  /// In en, this message translates to:
  /// **'File sent: {fileName}'**
  String fileSent(String fileName);

  /// Error message when file send fails
  ///
  /// In en, this message translates to:
  /// **'Failed to send file: {error}'**
  String failedToSendFile(String error);

  /// Connection status when not connected
  ///
  /// In en, this message translates to:
  /// **'Not Connected'**
  String get notConnected;

  /// Status when ready for file transfer
  ///
  /// In en, this message translates to:
  /// **'Ready for file transfer'**
  String get readyForFileTransfer;

  /// Instruction when not connected
  ///
  /// In en, this message translates to:
  /// **'Connect to start transferring files'**
  String get connectToStartTransferringFiles;

  /// Send file button text
  ///
  /// In en, this message translates to:
  /// **'Send File'**
  String get sendFile;

  /// Receive files section title
  ///
  /// In en, this message translates to:
  /// **'Receive Files'**
  String get receiveFiles;

  /// Description of automatic file receiving
  ///
  /// In en, this message translates to:
  /// **'Files sent by the peer will be saved to {location}'**
  String filesWillBeAutomaticallyReceived(String location);

  /// No description provided for @saveReceivedFilesTo.
  ///
  /// In en, this message translates to:
  /// **'Save received files to'**
  String get saveReceivedFilesTo;

  /// No description provided for @appStorage.
  ///
  /// In en, this message translates to:
  /// **'App storage'**
  String get appStorage;

  /// No description provided for @downloadsFolder.
  ///
  /// In en, this message translates to:
  /// **'Downloads'**
  String get downloadsFolder;

  /// No description provided for @chooseCustomFolder.
  ///
  /// In en, this message translates to:
  /// **'Choose custom folder'**
  String get chooseCustomFolder;

  /// No description provided for @receiveDestinationFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not use that receive location'**
  String get receiveDestinationFailed;

  /// No description provided for @fileReceived.
  ///
  /// In en, this message translates to:
  /// **'File received: {fileName}'**
  String fileReceived(String fileName);

  /// No description provided for @fileTransferCancelled.
  ///
  /// In en, this message translates to:
  /// **'Transfer cancelled: {fileName}'**
  String fileTransferCancelled(String fileName);

  /// No description provided for @fileTransferFailed.
  ///
  /// In en, this message translates to:
  /// **'Transfer failed for {fileName}: {error}'**
  String fileTransferFailed(String fileName, String error);

  /// No description provided for @preparing.
  ///
  /// In en, this message translates to:
  /// **'Preparing'**
  String get preparing;

  /// No description provided for @queued.
  ///
  /// In en, this message translates to:
  /// **'Queued'**
  String get queued;

  /// No description provided for @cancelling.
  ///
  /// In en, this message translates to:
  /// **'Cancelling...'**
  String get cancelling;

  /// No description provided for @cancelled.
  ///
  /// In en, this message translates to:
  /// **'Cancelled'**
  String get cancelled;

  /// No description provided for @failed.
  ///
  /// In en, this message translates to:
  /// **'Failed'**
  String get failed;

  /// No description provided for @cancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// Message when no file transfers are active
  ///
  /// In en, this message translates to:
  /// **'No Active Transfers'**
  String get noActiveTransfers;

  /// Status when upload is complete
  ///
  /// In en, this message translates to:
  /// **'Upload Complete'**
  String get uploadComplete;

  /// Status when download is complete
  ///
  /// In en, this message translates to:
  /// **'Download Complete'**
  String get downloadComplete;

  /// Status when uploading
  ///
  /// In en, this message translates to:
  /// **'Uploading...'**
  String get uploading;

  /// Status when downloading
  ///
  /// In en, this message translates to:
  /// **'Downloading...'**
  String get downloading;

  /// Recent transfers section title
  ///
  /// In en, this message translates to:
  /// **'Recent Transfers'**
  String get recentTransfers;

  /// Message when no recent transfers exist
  ///
  /// In en, this message translates to:
  /// **'No recent transfers'**
  String get noRecentTransfers;

  /// Label for sent files
  ///
  /// In en, this message translates to:
  /// **'Sent'**
  String get sent;

  /// Label for received files
  ///
  /// In en, this message translates to:
  /// **'Received'**
  String get received;

  /// Tooltip for open file button
  ///
  /// In en, this message translates to:
  /// **'Open file'**
  String get openFile;

  /// Tooltip for clear button
  ///
  /// In en, this message translates to:
  /// **'Clear'**
  String get clear;

  /// Error message when file open fails
  ///
  /// In en, this message translates to:
  /// **'Failed to open file: {error}'**
  String failedToOpenFile(String error);

  /// Informational message when no installed app supports a received file
  ///
  /// In en, this message translates to:
  /// **'There is no supported app to open {fileName}. It’s saved in {location}.'**
  String noSupportedAppToOpenFile(String fileName, String location);

  /// System logs section header
  ///
  /// In en, this message translates to:
  /// **'System Logs'**
  String get systemLogs;

  /// Message when no logs exist
  ///
  /// In en, this message translates to:
  /// **'No logs yet'**
  String get noLogsYet;

  /// Settings page subtitle
  ///
  /// In en, this message translates to:
  /// **'Customize your WiFi Direct experience'**
  String get customizeYourWifiDirectExperience;

  /// App settings section title
  ///
  /// In en, this message translates to:
  /// **'App Settings'**
  String get appSettings;

  /// Language setting title
  ///
  /// In en, this message translates to:
  /// **'Language'**
  String get language;

  /// Language setting description
  ///
  /// In en, this message translates to:
  /// **'Choose your preferred language'**
  String get chooseYourPreferredLanguage;

  /// Follow system language option
  ///
  /// In en, this message translates to:
  /// **'Follow System'**
  String get followSystem;

  /// English language option
  ///
  /// In en, this message translates to:
  /// **'English'**
  String get english;

  /// Dark mode setting title
  ///
  /// In en, this message translates to:
  /// **'Dark Mode'**
  String get darkMode;

  /// Dark mode setting description
  ///
  /// In en, this message translates to:
  /// **'Use dark theme'**
  String get useDarkTheme;

  /// About section title
  ///
  /// In en, this message translates to:
  /// **'About'**
  String get about;

  /// Version info title
  ///
  /// In en, this message translates to:
  /// **'Version'**
  String get version;

  /// Privacy policy title
  ///
  /// In en, this message translates to:
  /// **'Privacy Policy'**
  String get privacyPolicy;

  /// Privacy policy description
  ///
  /// In en, this message translates to:
  /// **'View our privacy policy'**
  String get viewOurPrivacyPolicy;

  /// Privacy policy dialog content
  ///
  /// In en, this message translates to:
  /// **'This app uses WiFi Direct to establish peer-to-peer connections. No data is sent to external servers. All communications happen directly between devices.'**
  String get privacyPolicyContent;

  /// OK button text
  ///
  /// In en, this message translates to:
  /// **'OK'**
  String get ok;

  /// Speed test status title
  ///
  /// In en, this message translates to:
  /// **'Speed Test Status'**
  String get speedTestStatus;

  /// Status when ready to test speed
  ///
  /// In en, this message translates to:
  /// **'Ready to test connection speed'**
  String get readyToTestConnectionSpeed;

  /// Status when not connected for speed test
  ///
  /// In en, this message translates to:
  /// **'Connect to a peer to test speed'**
  String get connectToPeerToTestSpeed;

  /// Speed test in progress text
  ///
  /// In en, this message translates to:
  /// **'Testing...'**
  String get testing;

  /// Start speed test button text
  ///
  /// In en, this message translates to:
  /// **'START'**
  String get start;

  /// Instruction to start speed test
  ///
  /// In en, this message translates to:
  /// **'Tap to start speed test'**
  String get tapToStartSpeedTest;

  /// Instruction when not connected
  ///
  /// In en, this message translates to:
  /// **'Connect to a peer first'**
  String get connectToPeerFirst;

  /// Download test phase label
  ///
  /// In en, this message translates to:
  /// **'Download Test'**
  String get downloadTest;

  /// Upload test phase label
  ///
  /// In en, this message translates to:
  /// **'Upload Test'**
  String get uploadTest;

  /// Download label
  ///
  /// In en, this message translates to:
  /// **'Download'**
  String get download;

  /// Upload label
  ///
  /// In en, this message translates to:
  /// **'Upload'**
  String get upload;

  /// Speed display format
  ///
  /// In en, this message translates to:
  /// **'Speed: {speed} MB/s'**
  String speed(String speed);

  /// Complete status
  ///
  /// In en, this message translates to:
  /// **'Complete'**
  String get complete;

  /// In progress status
  ///
  /// In en, this message translates to:
  /// **'In Progress'**
  String get inProgress;

  /// Latest results section title
  ///
  /// In en, this message translates to:
  /// **'Latest Results'**
  String get latestResults;

  /// Test completion time label
  ///
  /// In en, this message translates to:
  /// **'Test completed at'**
  String get testCompletedAt;

  /// Message when no test results exist
  ///
  /// In en, this message translates to:
  /// **'No test results yet'**
  String get noTestResultsYet;

  /// Instruction to run speed test
  ///
  /// In en, this message translates to:
  /// **'Run a speed test to see results here'**
  String get runSpeedTestToSeeResults;

  /// Test history section title
  ///
  /// In en, this message translates to:
  /// **'Test History'**
  String get testHistory;

  /// Number of tests format
  ///
  /// In en, this message translates to:
  /// **'{count} tests'**
  String tests(int count);

  /// Message when no test history exists
  ///
  /// In en, this message translates to:
  /// **'No test history'**
  String get noTestHistory;

  /// Single day ago format
  ///
  /// In en, this message translates to:
  /// **'{count} day ago'**
  String dayAgo(int count);

  /// Multiple days ago format
  ///
  /// In en, this message translates to:
  /// **'{count} days ago'**
  String daysAgoLong(int count);

  /// Single hour ago format
  ///
  /// In en, this message translates to:
  /// **'{count} hour ago'**
  String hourAgo(int count);

  /// Multiple hours ago format
  ///
  /// In en, this message translates to:
  /// **'{count} hours ago'**
  String hoursAgoLong(int count);

  /// Single minute ago format
  ///
  /// In en, this message translates to:
  /// **'{count} minute ago'**
  String minuteAgo(int count);

  /// Multiple minutes ago format
  ///
  /// In en, this message translates to:
  /// **'{count} minutes ago'**
  String minutesAgoLong(int count);

  /// Chinese language option
  ///
  /// In en, this message translates to:
  /// **'中文'**
  String get chinese;

  /// GitHub repositories section title
  ///
  /// In en, this message translates to:
  /// **'GitHub Repositories'**
  String get githubRepositories;

  /// Flutter app repository title
  ///
  /// In en, this message translates to:
  /// **'Flutter App Repository'**
  String get flutterAppRepository;

  /// Flutter app repository description
  ///
  /// In en, this message translates to:
  /// **'WDCable Flutter - Mobile WiFi Direct file transfer app'**
  String get flutterAppDescription;

  /// Windows app repository title
  ///
  /// In en, this message translates to:
  /// **'Windows App Repository'**
  String get windowsAppRepository;

  /// Windows app repository description
  ///
  /// In en, this message translates to:
  /// **'WDCableWUI - Windows companion application'**
  String get windowsAppDescription;

  /// Message shown when URL is copied to clipboard
  ///
  /// In en, this message translates to:
  /// **'URL copied to clipboard: {url}'**
  String urlCopiedToClipboard(String url);

  /// Settings page title
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settingsTitle;

  /// Settings page subtitle
  ///
  /// In en, this message translates to:
  /// **'Customize your WiFi Direct experience'**
  String get settingsSubtitle;

  /// No description provided for @audioLink.
  ///
  /// In en, this message translates to:
  /// **'Audio Link'**
  String get audioLink;

  /// No description provided for @audioConnectToPeerFirst.
  ///
  /// In en, this message translates to:
  /// **'Connect to a peer first'**
  String get audioConnectToPeerFirst;

  /// No description provided for @audioPeerUnsupported.
  ///
  /// In en, this message translates to:
  /// **'The connected peer does not support Audio Link'**
  String get audioPeerUnsupported;

  /// No description provided for @audioReady.
  ///
  /// In en, this message translates to:
  /// **'Audio Link is ready'**
  String get audioReady;

  /// No description provided for @audioMode.
  ///
  /// In en, this message translates to:
  /// **'Mode'**
  String get audioMode;

  /// No description provided for @audioReceive.
  ///
  /// In en, this message translates to:
  /// **'Receive'**
  String get audioReceive;

  /// No description provided for @audioSend.
  ///
  /// In en, this message translates to:
  /// **'Send'**
  String get audioSend;

  /// No description provided for @audioSource.
  ///
  /// In en, this message translates to:
  /// **'Source'**
  String get audioSource;

  /// No description provided for @audioMicrophone.
  ///
  /// In en, this message translates to:
  /// **'Microphone'**
  String get audioMicrophone;

  /// No description provided for @audioDeviceAudioUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Device audio unavailable'**
  String get audioDeviceAudioUnavailable;

  /// No description provided for @audioLatencyMode.
  ///
  /// In en, this message translates to:
  /// **'Latency Mode'**
  String get audioLatencyMode;

  /// No description provided for @audioLowLatency.
  ///
  /// In en, this message translates to:
  /// **'Low latency'**
  String get audioLowLatency;

  /// No description provided for @audioStable.
  ///
  /// In en, this message translates to:
  /// **'Stable'**
  String get audioStable;

  /// No description provided for @audioQualityMode.
  ///
  /// In en, this message translates to:
  /// **'Quality'**
  String get audioQualityMode;

  /// No description provided for @audioQualityStandard.
  ///
  /// In en, this message translates to:
  /// **'Standard'**
  String get audioQualityStandard;

  /// No description provided for @audioQualityBalanced.
  ///
  /// In en, this message translates to:
  /// **'Balanced'**
  String get audioQualityBalanced;

  /// No description provided for @audioQualityHigh.
  ///
  /// In en, this message translates to:
  /// **'High'**
  String get audioQualityHigh;

  /// No description provided for @audioQualityNearLossless.
  ///
  /// In en, this message translates to:
  /// **'Near lossless'**
  String get audioQualityNearLossless;

  /// No description provided for @audioEncoding.
  ///
  /// In en, this message translates to:
  /// **'Encoding'**
  String get audioEncoding;

  /// No description provided for @audioOpus.
  ///
  /// In en, this message translates to:
  /// **'Opus'**
  String get audioOpus;

  /// No description provided for @audioOpus32Kbps.
  ///
  /// In en, this message translates to:
  /// **'Opus 32 kbps'**
  String get audioOpus32Kbps;

  /// No description provided for @audioOnlyOption.
  ///
  /// In en, this message translates to:
  /// **'Only option'**
  String get audioOnlyOption;

  /// No description provided for @audioFollowSenderSide.
  ///
  /// In en, this message translates to:
  /// **'Follow sender side'**
  String get audioFollowSenderSide;

  /// No description provided for @audioStop.
  ///
  /// In en, this message translates to:
  /// **'Stop Audio'**
  String get audioStop;

  /// No description provided for @audioStart.
  ///
  /// In en, this message translates to:
  /// **'Start Audio'**
  String get audioStart;

  /// No description provided for @audioLiveStats.
  ///
  /// In en, this message translates to:
  /// **'Live Stats'**
  String get audioLiveStats;

  /// No description provided for @audioState.
  ///
  /// In en, this message translates to:
  /// **'State'**
  String get audioState;

  /// No description provided for @audioBitrate.
  ///
  /// In en, this message translates to:
  /// **'Bitrate'**
  String get audioBitrate;

  /// No description provided for @audioConfiguredBitrate.
  ///
  /// In en, this message translates to:
  /// **'Configured'**
  String get audioConfiguredBitrate;

  /// No description provided for @audioQuality.
  ///
  /// In en, this message translates to:
  /// **'Quality'**
  String get audioQuality;

  /// No description provided for @audioBuffer.
  ///
  /// In en, this message translates to:
  /// **'Buffer'**
  String get audioBuffer;

  /// No description provided for @audioDropped.
  ///
  /// In en, this message translates to:
  /// **'Dropped'**
  String get audioDropped;

  /// No description provided for @audioPacketLoss.
  ///
  /// In en, this message translates to:
  /// **'Packet Loss'**
  String get audioPacketLoss;

  /// No description provided for @audioLateDrops.
  ///
  /// In en, this message translates to:
  /// **'Late Drops'**
  String get audioLateDrops;

  /// No description provided for @audioOverflowDrops.
  ///
  /// In en, this message translates to:
  /// **'Overflow Drops'**
  String get audioOverflowDrops;

  /// No description provided for @audioPlc.
  ///
  /// In en, this message translates to:
  /// **'PLC'**
  String get audioPlc;

  /// No description provided for @audioRtcpLoss.
  ///
  /// In en, this message translates to:
  /// **'RTCP Loss'**
  String get audioRtcpLoss;

  /// No description provided for @audioRtcpJitter.
  ///
  /// In en, this message translates to:
  /// **'RTCP Jitter'**
  String get audioRtcpJitter;

  /// No description provided for @audioRoundTrip.
  ///
  /// In en, this message translates to:
  /// **'Round Trip'**
  String get audioRoundTrip;

  /// No description provided for @audioFrames.
  ///
  /// In en, this message translates to:
  /// **'Frames'**
  String get audioFrames;

  /// No description provided for @audioLatency.
  ///
  /// In en, this message translates to:
  /// **'Latency'**
  String get audioLatency;

  /// No description provided for @audioStateReceiveReady.
  ///
  /// In en, this message translates to:
  /// **'Receive ready'**
  String get audioStateReceiveReady;

  /// No description provided for @audioStateOfferSent.
  ///
  /// In en, this message translates to:
  /// **'Offer sent'**
  String get audioStateOfferSent;

  /// No description provided for @audioStateConnecting.
  ///
  /// In en, this message translates to:
  /// **'Connecting'**
  String get audioStateConnecting;

  /// No description provided for @audioStateStreaming.
  ///
  /// In en, this message translates to:
  /// **'Streaming'**
  String get audioStateStreaming;

  /// No description provided for @audioStateIdle.
  ///
  /// In en, this message translates to:
  /// **'Idle'**
  String get audioStateIdle;

  /// No description provided for @notAvailableShort.
  ///
  /// In en, this message translates to:
  /// **'N/A'**
  String get notAvailableShort;

  /// No description provided for @kbpsUnit.
  ///
  /// In en, this message translates to:
  /// **'kbps'**
  String get kbpsUnit;

  /// No description provided for @pairingTitle.
  ///
  /// In en, this message translates to:
  /// **'Pair devices'**
  String get pairingTitle;

  /// No description provided for @pairingCodeDisplayMessage.
  ///
  /// In en, this message translates to:
  /// **'Enter this code on the other phone.'**
  String get pairingCodeDisplayMessage;

  /// No description provided for @pairingWaitingForPeer.
  ///
  /// In en, this message translates to:
  /// **'Waiting for the other phone…'**
  String get pairingWaitingForPeer;

  /// No description provided for @pairingEnterCodeTitle.
  ///
  /// In en, this message translates to:
  /// **'Enter pairing code'**
  String get pairingEnterCodeTitle;

  /// No description provided for @pairingEnterCodeMessage.
  ///
  /// In en, this message translates to:
  /// **'Type the 6-digit code shown on the other phone.'**
  String get pairingEnterCodeMessage;

  /// No description provided for @pairingCodeInvalid.
  ///
  /// In en, this message translates to:
  /// **'Enter all 6 digits.'**
  String get pairingCodeInvalid;

  /// No description provided for @pairingVerifyTitle.
  ///
  /// In en, this message translates to:
  /// **'Do these match?'**
  String get pairingVerifyTitle;

  /// No description provided for @pairingVerifyMessage.
  ///
  /// In en, this message translates to:
  /// **'Both phones should show the same number. If they differ, someone may be intercepting the connection. Do not continue.'**
  String get pairingVerifyMessage;

  /// No description provided for @pairingMatches.
  ///
  /// In en, this message translates to:
  /// **'They match'**
  String get pairingMatches;

  /// No description provided for @pairingDoesNotMatch.
  ///
  /// In en, this message translates to:
  /// **'They differ'**
  String get pairingDoesNotMatch;

  /// No description provided for @pairingCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get pairingCancel;

  /// No description provided for @pairingConfirm.
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get pairingConfirm;

  /// No description provided for @pairingUnverifiedWarning.
  ///
  /// In en, this message translates to:
  /// **'Paired, but not verified. Anyone nearby could have intercepted this pairing.'**
  String get pairingUnverifiedWarning;

  /// No description provided for @dialerTitle.
  ///
  /// In en, this message translates to:
  /// **'Dialer'**
  String get dialerTitle;

  /// No description provided for @dialerEnterNumber.
  ///
  /// In en, this message translates to:
  /// **'Enter a number'**
  String get dialerEnterNumber;

  /// No description provided for @dialerCall.
  ///
  /// In en, this message translates to:
  /// **'Call'**
  String get dialerCall;

  /// No description provided for @dialerHangUp.
  ///
  /// In en, this message translates to:
  /// **'Hang up'**
  String get dialerHangUp;

  /// No description provided for @dialerAnswer.
  ///
  /// In en, this message translates to:
  /// **'Answer'**
  String get dialerAnswer;

  /// No description provided for @dialerReject.
  ///
  /// In en, this message translates to:
  /// **'Reject'**
  String get dialerReject;

  /// No description provided for @dialerNoGateway.
  ///
  /// In en, this message translates to:
  /// **'Not connected to a gateway.'**
  String get dialerNoGateway;

  /// No description provided for @dialerGatewayCannotDial.
  ///
  /// In en, this message translates to:
  /// **'This gateway cannot place calls.'**
  String get dialerGatewayCannotDial;

  /// No description provided for @gatewayTitle.
  ///
  /// In en, this message translates to:
  /// **'Gateway'**
  String get gatewayTitle;

  /// No description provided for @gatewayUnavailableValue.
  ///
  /// In en, this message translates to:
  /// **'Unavailable on this device'**
  String get gatewayUnavailableValue;

  /// No description provided for @gatewaySim.
  ///
  /// In en, this message translates to:
  /// **'SIM'**
  String get gatewaySim;

  /// No description provided for @gatewayCarrier.
  ///
  /// In en, this message translates to:
  /// **'Carrier'**
  String get gatewayCarrier;

  /// No description provided for @gatewayNetwork.
  ///
  /// In en, this message translates to:
  /// **'Network'**
  String get gatewayNetwork;

  /// No description provided for @gatewaySignal.
  ///
  /// In en, this message translates to:
  /// **'Signal'**
  String get gatewaySignal;

  /// No description provided for @gatewayMobileData.
  ///
  /// In en, this message translates to:
  /// **'Mobile data'**
  String get gatewayMobileData;

  /// No description provided for @gatewayBattery.
  ///
  /// In en, this message translates to:
  /// **'Battery'**
  String get gatewayBattery;

  /// No description provided for @gatewayCallAudioNotice.
  ///
  /// In en, this message translates to:
  /// **'Remote cellular call audio is not supported on this device. The conversation happens on the gateway phone\'s own speaker and microphone.'**
  String get gatewayCallAudioNotice;

  /// No description provided for @gatewayRefreshStatus.
  ///
  /// In en, this message translates to:
  /// **'Refresh'**
  String get gatewayRefreshStatus;

  /// No description provided for @callStateDialing.
  ///
  /// In en, this message translates to:
  /// **'Dialing'**
  String get callStateDialing;

  /// No description provided for @callStateRinging.
  ///
  /// In en, this message translates to:
  /// **'Ringing'**
  String get callStateRinging;

  /// No description provided for @callStateActive.
  ///
  /// In en, this message translates to:
  /// **'In call'**
  String get callStateActive;

  /// No description provided for @callStateEnded.
  ///
  /// In en, this message translates to:
  /// **'Call ended'**
  String get callStateEnded;

  /// No description provided for @callStateFailed.
  ///
  /// In en, this message translates to:
  /// **'Call failed'**
  String get callStateFailed;

  /// No description provided for @callSinceDialing.
  ///
  /// In en, this message translates to:
  /// **'Since dialing'**
  String get callSinceDialing;

  /// No description provided for @callAnswerNotConfirmed.
  ///
  /// In en, this message translates to:
  /// **'Android cannot confirm when the other side answers, so this counts from dialing.'**
  String get callAnswerNotConfirmed;

  /// No description provided for @simStateReady.
  ///
  /// In en, this message translates to:
  /// **'Ready'**
  String get simStateReady;

  /// No description provided for @simStateAbsent.
  ///
  /// In en, this message translates to:
  /// **'No SIM'**
  String get simStateAbsent;

  /// No description provided for @simStateLocked.
  ///
  /// In en, this message translates to:
  /// **'Locked'**
  String get simStateLocked;

  /// No description provided for @simStateUnknown.
  ///
  /// In en, this message translates to:
  /// **'Unknown'**
  String get simStateUnknown;

  /// No description provided for @messagesTitle.
  ///
  /// In en, this message translates to:
  /// **'Messages'**
  String get messagesTitle;

  /// No description provided for @messagesNoGateway.
  ///
  /// In en, this message translates to:
  /// **'Not connected to a gateway.'**
  String get messagesNoGateway;

  /// No description provided for @messagesCannotRead.
  ///
  /// In en, this message translates to:
  /// **'This gateway cannot read messages. Android restricts reading SMS to the phone\'s default messaging app.'**
  String get messagesCannotRead;

  /// No description provided for @messagesCannotSend.
  ///
  /// In en, this message translates to:
  /// **'This gateway cannot send messages.'**
  String get messagesCannotSend;

  /// No description provided for @messagesNone.
  ///
  /// In en, this message translates to:
  /// **'No conversations'**
  String get messagesNone;

  /// No description provided for @messagesRecipient.
  ///
  /// In en, this message translates to:
  /// **'To'**
  String get messagesRecipient;

  /// No description provided for @messagesBody.
  ///
  /// In en, this message translates to:
  /// **'Message'**
  String get messagesBody;

  /// No description provided for @messagesSend.
  ///
  /// In en, this message translates to:
  /// **'Send'**
  String get messagesSend;

  /// No description provided for @messagesRefresh.
  ///
  /// In en, this message translates to:
  /// **'Refresh'**
  String get messagesRefresh;

  /// No description provided for @messagesHandedToNetwork.
  ///
  /// In en, this message translates to:
  /// **'Handed to the network. Delivery is not confirmed.'**
  String get messagesHandedToNetwork;

  /// No description provided for @messagesSendFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not send the message.'**
  String get messagesSendFailed;

  /// No description provided for @messagesSentByGateway.
  ///
  /// In en, this message translates to:
  /// **'Sent from the gateway phone\'s SIM.'**
  String get messagesSentByGateway;

  /// No description provided for @diagnosticsTitle.
  ///
  /// In en, this message translates to:
  /// **'Diagnostics'**
  String get diagnosticsTitle;

  /// No description provided for @diagnosticsCompatibility.
  ///
  /// In en, this message translates to:
  /// **'Device compatibility'**
  String get diagnosticsCompatibility;

  /// No description provided for @diagnosticsSupported.
  ///
  /// In en, this message translates to:
  /// **'Supported'**
  String get diagnosticsSupported;

  /// No description provided for @diagnosticsPartial.
  ///
  /// In en, this message translates to:
  /// **'Partially supported'**
  String get diagnosticsPartial;

  /// No description provided for @diagnosticsUnsupported.
  ///
  /// In en, this message translates to:
  /// **'Not supported'**
  String get diagnosticsUnsupported;

  /// No description provided for @diagnosticsConnection.
  ///
  /// In en, this message translates to:
  /// **'Connection'**
  String get diagnosticsConnection;

  /// No description provided for @diagnosticsSecurity.
  ///
  /// In en, this message translates to:
  /// **'Security'**
  String get diagnosticsSecurity;

  /// No description provided for @diagnosticsInternet.
  ///
  /// In en, this message translates to:
  /// **'Internet'**
  String get diagnosticsInternet;

  /// No description provided for @diagnosticsRefresh.
  ///
  /// In en, this message translates to:
  /// **'Refresh'**
  String get diagnosticsRefresh;

  /// No description provided for @diagnosticsSecureActive.
  ///
  /// In en, this message translates to:
  /// **'Encrypted and authenticated'**
  String get diagnosticsSecureActive;

  /// No description provided for @diagnosticsSecureInactive.
  ///
  /// In en, this message translates to:
  /// **'Not encrypted. This link is running the legacy plaintext protocol.'**
  String get diagnosticsSecureInactive;

  /// No description provided for @diagnosticsSasUnverified.
  ///
  /// In en, this message translates to:
  /// **'Paired, but the confirmation code was not verified on both phones.'**
  String get diagnosticsSasUnverified;

  /// No description provided for @diagnosticsInternetSeparate.
  ///
  /// In en, this message translates to:
  /// **'This is separate from the Wi-Fi Direct link, which works with no Internet at all.'**
  String get diagnosticsInternetSeparate;

  /// No description provided for @diagnosticsTetheringManual.
  ///
  /// In en, this message translates to:
  /// **'Android does not let an app turn tethering on. Enable the hotspot in Settings if you want to share mobile data.'**
  String get diagnosticsTetheringManual;

  /// No description provided for @diagnosticsHotspotUnknown.
  ///
  /// In en, this message translates to:
  /// **'Android does not let an app read hotspot state.'**
  String get diagnosticsHotspotUnknown;

  /// No description provided for @diagnosticsNoInternet.
  ///
  /// In en, this message translates to:
  /// **'No Internet on this device'**
  String get diagnosticsNoInternet;

  /// No description provided for @diagnosticsPacketsSent.
  ///
  /// In en, this message translates to:
  /// **'Frames sent'**
  String get diagnosticsPacketsSent;

  /// No description provided for @diagnosticsPacketsReceived.
  ///
  /// In en, this message translates to:
  /// **'Frames received'**
  String get diagnosticsPacketsReceived;

  /// No description provided for @diagnosticsRejected.
  ///
  /// In en, this message translates to:
  /// **'Frames rejected'**
  String get diagnosticsRejected;

  /// No description provided for @diagnosticsDuplicates.
  ///
  /// In en, this message translates to:
  /// **'Duplicates dropped'**
  String get diagnosticsDuplicates;

  /// No description provided for @diagnosticsReplays.
  ///
  /// In en, this message translates to:
  /// **'Replays dropped'**
  String get diagnosticsReplays;

  /// No description provided for @diagnosticsLocalDevice.
  ///
  /// In en, this message translates to:
  /// **'This device'**
  String get diagnosticsLocalDevice;

  /// No description provided for @diagnosticsPeerDevice.
  ///
  /// In en, this message translates to:
  /// **'Paired peer'**
  String get diagnosticsPeerDevice;

  /// No description provided for @pairingForgetDevice.
  ///
  /// In en, this message translates to:
  /// **'Forget this device'**
  String get pairingForgetDevice;

  /// No description provided for @pairingForgetConfirm.
  ///
  /// In en, this message translates to:
  /// **'Forget this pairing? You will need to pair again with a new code, and the current connection will be closed.'**
  String get pairingForgetConfirm;

  /// No description provided for @pairingForgetAll.
  ///
  /// In en, this message translates to:
  /// **'Forget all paired devices'**
  String get pairingForgetAll;

  /// No description provided for @pairingNoneStored.
  ///
  /// In en, this message translates to:
  /// **'No paired devices'**
  String get pairingNoneStored;

  /// No description provided for @pairingDesynchronised.
  ///
  /// In en, this message translates to:
  /// **'This device and the gateway no longer agree on their stored pairing. Forget the device and pair again.'**
  String get pairingDesynchronised;

  /// No description provided for @commonCancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get commonCancel;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'zh'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'zh':
      return AppLocalizationsZh();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
