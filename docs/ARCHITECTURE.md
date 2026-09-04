# ARCHITECTURE — Existing Project (Pre-Modification Baseline)

**Inspected:** 2026-09-03
**Subject:** `WDCable_flutter-main` — "WiFi Direct Cable" v2.0.1+2001

**Status:** §0–§11 describe the project **as found**, before any change. The
security layer added since is recorded in §12; nothing in §0–§11 was rewritten to
match it. No existing behaviour was modified — the v2 codec, `SessionManager`,
`WiFiDirectManager` and the Flutter UI are byte-for-byte as inspected.

---

## 0. Headline Finding: The Base Project Is Flutter, Not Native Kotlin

The project brief (Phase 2) specifies a native Android layout
(`app/src/main/java/.../` with Jetpack Compose UI). **The cloned repository is a
Flutter application** with a large native Kotlin layer beneath it.

| | Brief assumes | Repository actually is |
|---|---|---|
| App framework | Native Android (Kotlin) | **Flutter 3.44.0 / Dart ^3.8.1** |
| UI | Jetpack Compose | **Flutter widgets** (~4,000 lines Dart) |
| Wi-Fi Direct | To be written | **Already implemented in Kotlin** (1,462 lines) |
| Entry point | `MainActivity` (Compose) | `MainActivity : FlutterActivity` |
| Native↔UI bridge | n/a (same language) | **`MethodChannel`** (`wifi_direct_cable/main`) |

This is not a defect — it is a mature, shipping app (IzzyOnDroid + Microsoft
Store) — but it means Phase 2's directory layout **cannot be applied literally**
without discarding a working UI. See §7.

### Code volume

| Layer | Lines | Files |
|---|---:|---:|
| Kotlin — `src/main` | 9,565 | 43 |
| Kotlin — `src/test` | 1,213 | 8 |
| Dart — `lib/` | 9,597 | 17 |
| C++ — Opus JNI | ~120 | 1 |

---

## 1. Toolchain and Build Configuration

Read from `pubspec.yaml`, `android/settings.gradle.kts`,
`android/app/build.gradle.kts`, `android/gradle/wrapper/`, `.metadata`.

| Property | Value | Source |
|---|---|---|
| Flutter | `3.44.0` (pinned) | `pubspec.yaml` |
| Dart SDK | `^3.8.1` | `pubspec.yaml` |
| Gradle | `8.14.3-all` | `gradle-wrapper.properties` |
| Android Gradle Plugin | `8.11.1` | `settings.gradle.kts` |
| Kotlin | `2.2.20` | `settings.gradle.kts` |
| **minSdk** | **33** (Android 13) | `app/build.gradle.kts` |
| targetSdk / compileSdk | `flutter.targetSdkVersion` / `flutter.compileSdkVersion` | delegated to Flutter |
| Java / jvmTarget | 11 | `app/build.gradle.kts` |
| NDK / CMake | CMake `3.22.1`, `src/main/cpp/CMakeLists.txt` | `app/build.gradle.kts` |
| ABIs | `arm64-v8a`, `x86_64` **only** | `app/build.gradle.kts` |
| Namespace / appId | `com.jingcjie.wifi_direct_cable` | `app/build.gradle.kts` |
| License | MIT | `LICENSE` |

`minSdk = 33` is a deliberate, documented decision (`connection_imp_plan.md`):
`WifiP2pManager.startListening`, `ACTION_WIFI_P2P_REQUEST_RESPONSE_CHANGED`, and
the `NEARBY_WIFI_DEVICES` runtime-permission path are all API 33.

**`armeabi-v7a` is intentionally excluded** because no 32-bit `libopus.so` is
bundled. Any 32-bit-only target device is unsupported by construction.

### Native dependency: libopus

`android/app/src/main/jniLibs/` ships prebuilt **libopus 1.6.1** for
`arm64-v8a` and `x86_64`, with upstream `OPUS_COPYING.txt` (3-clause BSD) and a
`README.md` recording the source URL and SHA-256. **Licensing is clean and
attribution is present** — this satisfies the Phase 8 instruction to reuse the
existing Opus implementation if "functional and properly licensed."

---

## 2. Existing Permissions

`android/app/src/main/AndroidManifest.xml`:

| Permission | Purpose |
|---|---|
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Wi-Fi Direct |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`) | API 33+ P2P discovery |
| `INTERNET` | **local TCP/UDP sockets only** — not cloud access |
| `RECORD_AUDIO` | microphone capture for Audio Link |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | keeps session alive |
| `WAKE_LOCK`, `POST_NOTIFICATIONS` | session stability + FGS notification |

Notably **absent** (all required by later phases, none present today):
`READ_PHONE_STATE`, `READ_PHONE_NUMBERS`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`,
`READ_CALL_LOG`, `SEND_SMS`, `READ_SMS`, `RECEIVE_SMS`.

`ACCESS_FINE_LOCATION` is deliberately **not** required — correct for API 33+
with `neverForLocation`.

### Declared components

- `MainActivity` — `singleTop`, `taskAffinity=""`, launcher.
- `WdCableConnectionService` — `foregroundServiceType="connectedDevice"`, not exported.
- `FileProvider` — `${applicationId}.fileprovider`, not exported.

---

## 3. Native Kotlin Layer (`com.jingcjie.wifi_direct_cable`)

### 3.1 Wi-Fi Direct — `WiFiDirectManager.kt` (1,462 lines)

The most valuable asset in the repository. It is a careful, defensive wrapper
over `WifiP2pManager`, well beyond a tutorial implementation.

- **Discovery:** `discoverPeers` **plus DNS-SD service discovery** — advertises
  a local service `WDCable` / `_wdcable._tcp` via `WifiP2pDnsSdServiceInfo` and
  registers a `WifiP2pDnsSdServiceRequest`. This lets it *distinguish app peers
  from arbitrary P2P devices* (`isWdCablePeer()`, `wdCablePeerAddresses`).
- **Listening:** `startListening` / `stopListening` (API 33), with
  `getListenState` guarded to API 34 (`UPSIDE_DOWN_CAKE`).
- **State hydration:** `requestP2pState`, `requestDiscoveryState`,
  `requestDeviceInfo`, `requestConnectionInfo`, `requestGroupInfo` — state is
  *polled and reconciled*, not merely inferred from broadcasts.
- **Group-owner address:** obtained **only** from the real
  `WifiP2pInfo.groupOwnerAddress` (`WiFiDirectManager.kt:606`, `:1435`).
  **It is never hardcoded or assumed** — Phase 3's explicit requirement is
  already satisfied.
- **Lifecycle:** channel-disconnect recovery (`handleChannelDisconnected`),
  60 s connect timeout, monotonic `operationId` to discard stale callbacks,
  `cancelPendingConnect` / `removeGroupInternal` cleanup.
- **Reason-code decoding:** `reasonName()` maps `P2P_UNSUPPORTED` / `ERROR` /
  `BUSY` / `NO_SERVICE_REQUESTS` to readable strings.

Its internal state vocabulary (`STATE_*`) is richer than the brief's 7 states:

```
BlockedByPermission  Unavailable  Ready       Listening  ServiceRegistered
Discovering          Connecting   Connected   Disconnecting
UserStoppedScan      Background   Error
```

**Broadcast handling** — `WiFiDirectBroadcastReceiver.kt` (103 lines), registered
with `RECEIVER_NOT_EXPORTED` on API 33+ by `WdCableRuntime`.

### 3.2 Framing protocol — `protocol/` (7 files)

A **versioned binary protocol, v2** — already close to Phase 4's intent.

`ProtocolCodec` encodes a fixed **56-byte big-endian header**:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | magic `0x57444342` (`"WDCB"`) |
| 4 | 2 | **version** (`2`) |
| 6 | 2 | header size (`56`) |
| 8 | 2 | frame type |
| 10 | 2 | flags |
| 12 | 2 | channel |
| 14 | 2 | reserved |
| 16 | 8 | `streamId` |
| 24 | 8 | **`sequenceNumber`** |
| 32 | 16 | **`correlationId`** (UUID — serves as message ID) |
| 48 | 4 | metadata length |
| 52 | 4 | payload length |

Then `metadataJson` (UTF-8 JSON, ≤64 KiB) and `payload` (≤1 MiB).

- **Channels:** `CONTROL(1)` and `BULK(2)` are *separate TCP connections*, so bulk
  file transfer cannot starve control traffic. `AUDIO(3)` is declared in the enum
  but **never used**: audio runs on its own RTP/RTCP UDP sockets, not on a
  `SessionTransport`. (Corrected after a later static audit — the original
  inspection wrongly described all three as TCP channels. See §12.)
- **Frame types:** `HANDSHAKE_HELLO/ACK`, `HEARTBEAT_PING/PONG`, `CLOSE`,
  `ERROR`, `CONTROL_MESSAGE`, `ACK`, `BULK_START/CHUNK/COMPLETE/CANCEL`,
  `AUDIO_FRAME`.
- Every length is validated before allocation; `readFully` loops correctly;
  malformed input raises a typed `ProtocolException` carrying a `ProtocolError`
  enum. **This is genuinely robust framing.**

**Gaps vs Phase 4:** no checksum/integrity field, no `timestamp` field, no
sender device ID, metadata is JSON not CBOR, and *no encryption at all*.

### 3.3 Session layer — `session/` (13 files, incl. `SessionManager.kt` 1,963 lines)

- **`SessionStateMachine`** — explicit, guarded transitions over `SessionPhase`:
  `DISCONNECTED → WIFI_DIRECT_CONNECTED → CONNECTING_TRANSPORT → HANDSHAKING →
  READY ⇄ DEGRADED → DISCONNECTING → DISCONNECTED`, plus `FAILED`. Invalid
  transitions throw. Unit-tested.
- **`SessionTransport` / `SessionTransportAdapter` / `SessionTransportListener`**
  — a clean transport *interface* with a socket implementation
  (`SocketSessionTransportAdapter`) injected via constructor default. This is
  exactly the abstraction seam Phase 8 asks for, and it makes the session layer
  unit-testable without real sockets.

- **`ProtocolV2TransportSetup` — the UDP rendezvous.** This solves the classic
  Wi-Fi Direct problem that *the group owner does not learn the client's IP*:

  | Wi-Fi P2P role | Transport role | Behaviour |
  |---|---|---|
  | `CLIENT` | **`LISTENER`** | binds TCP `8988`/`8989`, sends UDP beacons to GO `8987` |
  | `GROUP_OWNER` | **`CONNECTOR`** | receives beacon → learns client IP → dials back |

  Note this is **inverted** from the naive assumption that the GO listens.
  Ports: rendezvous `8987`, control `8988`, bulk `8989`, RTP `8990`, RTCP `8991`.
  Setup deadline 30 s.

- **Robustness already present:** `MAX_CONNECT_ATTEMPTS = 10` with exponential
  backoff (1 s → 30 s), `HANDSHAKE_TIMEOUT_MS = 10000`,
  **`HEARTBEAT_INTERVAL_MS = 5000` / `HEARTBEAT_TIMEOUT_MS = 15000`**,
  `DEGRADED_GROUP_CONFIRM_DELAY_MS`, generation counters to invalidate stale
  sessions.
- **Capability negotiation** at handshake — peers exchange a capability string
  list (`control.chat`, `bulk.file`, `bulk.speed`, `audio.link`,
  `audio.codec.libopus`, …) and features are gated on the *intersection*. This
  is the right mechanism for the brief's "report unsupported capability
  honestly" requirement, and it already exists.

### 3.4 Audio — `audio/` (8 files, `AudioService.kt` 1,289 lines)

- **Codec:** libopus via JNI (`NativeOpus.kt` + `wdcable_opus_jni.cpp`), 48 kHz
  mono, 20 ms frames (960 samples), selectable bitrate
  32 k / 64 k / 128 k / 256 k, latency modes `lowLatency` / `stable`.
- **Transport:** **RTP over UDP** (`RtpPacket.kt`) with **RTCP**
  (`RtcpPacket.kt`) — real sequence numbers and timestamps, payload type 111,
  48 kHz clock.
- **`JitterBuffer.kt`** (181 lines, unit-tested) — reordering + loss handling.
- **`AudioCapabilities` / `AudioSupport`** — degrade honestly: if
  `NativeOpus.available` is false, audio capabilities are simply not advertised
  and `supportMessage()` returns *"libopus runtime is not available on this
  device."*

> **Critical for Phase 7/8:** `AudioProtocol.SOURCE_MICROPHONE` is the only
> source actually implemented. A `SOURCE_SYSTEM_AUDIO` constant is *declared*
> but is not a cellular-call path. **The existing app captures the microphone —
> it does not touch cellular call audio anywhere.** See §9.

### 3.5 Supporting native classes

| File | Role |
|---|---|
| `MainActivity.kt` (147) | `FlutterActivity`; wires channel, permissions, lifecycle |
| `FlutterMethodChannelHandler.kt` (515) | dispatches **36** Dart→Kotlin methods |
| `WdCableRuntime.kt` (205) | process-wide singleton; owns receiver registration, shared by Activity **and** Service |
| `WdCableConnectionService.kt` (219) | foreground service, `connectedDevice` type |
| `PermissionManager.kt` (118) | `NEARBY_WIFI_DEVICES` + `RECORD_AUDIO` only |
| `FileTransferService.kt` (454), `ChatService.kt`, `SpeedTestService.kt` | feature services over the BULK/CONTROL channels |
| `ReceiveDestinationManager.kt` (347), `IncomingPartialFileStore.kt` | SAF destinations, resumable partials |
| `diagnostics/DiagnosticsLogger.kt` (120) | structured key=value ring-buffer log, exportable |
| `MainThreadDispatcher.kt` | main-looper marshalling |

`DiagnosticsLogger` is important: the whole native layer already emits
structured, exportable diagnostics under categories (`wifi`, `transport`,
`permissions`, `runtime`). Phase 15 can build directly on it.

---

## 4. Dart / Flutter Layer (`lib/`)

```
main.dart (190)                                   6-tab TabBarView shell
controllers/wifi_direct_controller.dart (1,505)   MethodChannel client + state orchestration
wifi_direct_service.dart (1,137)                  typed wrapper over the channel
models/wifi_direct_models.dart (885)              WiFiDirectState, devices, transfers, audio stats
widgets/  connection_tab · chat_tab · speed_test_tab · audio_tab · file_transfer_tab · settings_tab
services/data_manager.dart · theme/ · providers/ · utils/app_logger.dart
l10n/  en + zh  (ARB + generated)
```

- State management: **`provider`** (`^6.1.1`) — the only non-Flutter runtime
  dependency besides `intl` and `cupertino_icons`. Dependency surface is
  minimal, which is good for an offline app.
- **Localization is real** (English + Chinese, ~1,160 generated lines). Any new
  UI string should go through `l10n/app_en.arb`, not be hardcoded.
- Native→Dart callbacks are only **five**: `onConnectionChanged`, `onDebug`,
  `onError`, `onNativeStateChanged`, `onWifiDirectReset`.

---

## 5. Test Baseline

**Kotlin — 8 unit tests (JUnit 4), 1,213 lines.** Genuinely useful ones:
`ProtocolCodecTest` (321), `AudioProtocolTest` (458), `JitterBufferTest` (146),
`SessionStateMachineTest` (108), plus `RtpPacketTest`, `RtcpPacketTest`,
`BulkFileNamesTest`, `BulkTransferCancellationStateTest`,
`IncomingPartialFileStoreTest`, `MainThreadDispatcherTest`.

**Dart — 1 test:** `test/wifi_direct_state_test.dart`.

Dependencies: `junit:junit:4.13.2`, `org.json:json:20240303`. **No Robolectric,
no MockK, no instrumentation (`androidTest`) tests.** So anything touching
`Context`, `WifiP2pManager`, or Android framework classes is currently
**untestable in CI** without adding a test dependency.

---

## 6. What This Project Does *Not* Contain

Verified by exhaustive grep across `android/app/src/main` and `lib/`:

| Capability | Present? | Evidence |
|---|---|---|
| Telephony (`TelephonyManager`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`) | **None** | zero matches |
| SMS (`SmsManager`, `Telephony.Sms`) | **None** | zero matches |
| SIM / subscription state | **None** | zero matches |
| Encryption (`Cipher`, `AES`, `HMAC`) | **None** | zero matches |
| Android Keystore | **None** | zero matches |
| Device pairing / pairing code | **None** | zero matches |
| Replay protection / nonces | **None** | — |
| Device identity / persistent device ID | **None** | — |
| Tethering APIs | **None** | — |
| Cellular call-audio capture | **None** | only `SOURCE_MICROPHONE` is implemented |
| Kotlin coroutines | **None** | uses `Executors` + `Handler` throughout |
| `kotlinx.serialization` / CBOR | **None** | uses `org.json` |
| Jetpack Compose | **None** | UI is Flutter |

**Everything the control channel sends today is plaintext**, including chat
messages. There is no authentication of the peer beyond "it speaks the WDCable
protocol and answered the DNS-SD service query." Phases 5 and 13 are therefore
entirely greenfield and are the **largest security gap** in the baseline.

Concurrency is thread-pool-based (`newCachedThreadPool`,
`newSingleThreadScheduledExecutor`), not coroutine-based. Phase 2's "use Kotlin
coroutines" would be a rewrite of working, tested threading — see §7.

---

## 7. Baseline vs. the 18-Phase Brief

| Phase | Baseline status |
|---|---|
| 3 — Wi-Fi Direct | **Largely done, and done well.** Discovery, DNS-SD filtering, connect, real GO address, socket setup, reconnect, loss detection, state→UI. Only the *state-name vocabulary* differs from the brief. |
| 4 — Protocol | **~70%.** Versioned binary framing, message ID, sequence numbers, channels exist. Missing: timestamp, sender device ID, checksum, CBOR. |
| 8 — Audio | **~80% for microphone audio.** Opus + RTP/RTCP + jitter buffer + capability gating. Missing: nothing for mic; **everything** for cellular-call audio (which is largely not permitted — see §9). |
| 12 — Robustness | **~80%.** Heartbeat, backoff reconnect, timeouts, FGS, generation counters, channel recovery. Missing: duplicate-packet detection, explicit restart recovery. |
| 15 — Diagnostics | **~60%.** `DiagnosticsLogger` + `getConnectionStats` + `getDiscoveryStatus` exist. Missing: packet loss / latency / reconnect counters surfaced as a page. |
| 5, 13 — Pairing & security | **0%.** No crypto anywhere. |
| 6, 7, 9, 10 — Gateway, calls, SMS, data | **0%.** No telephony code at all. |
| 16 — Compatibility checker | **0%** as a feature, though capability negotiation is a strong foundation. |
| 17, 18 — Tests & docs | Kotlin unit tests existed; **no `docs/` directory existed before this file.** All eight documents the brief names now exist. |

### The two structural conflicts

**(a) Phase 2's directory layout assumes the UI is native.**
Applying it literally means deleting ~4,000 lines of working, localized Flutter
UI and rewriting it in Compose. The brief itself allows the escape hatch — *"Use
Jetpack Compose for new UI unless the existing project makes another approach
substantially easier"* — and here Flutter is substantially easier. The
recommended reading is: **apply Phase 2's package structure to the Kotlin
layer** (where `protocol/`, `session/`, `audio/`, `diagnostics/` already mirror
it closely), and **keep the UI in Flutter**, adding `dialer`, `gateway`, and
`diagnostics` tabs alongside the existing six.

**(b) Phase 2's "use coroutines" conflicts with working threaded code.**
Rewriting `SessionManager`'s executor model into coroutines is a high-risk
change to the most complex, most load-bearing file in the project, with no
functional gain. Recommendation: **new** code uses coroutines; existing
executor-based code is left alone unless it is being changed for another reason.

---

## 8. Blocking Constraint: This Machine Cannot Build the Project

The brief's DEVELOPMENT RULE requires building, fixing compile errors, and
running tests after each phase. **That is not currently possible here.**

| Requirement | Status |
|---|---|
| Flutter SDK | **absent** (`flutter` not on `PATH`) |
| Dart SDK | **absent** |
| Android SDK / `adb` | **absent** (`ANDROID_HOME` unset) |
| JDK | **JRE 17 only — no `javac`**, so Gradle cannot compile |
| `android/local.properties` | **missing** — `settings.gradle.kts` hard-`require`s `flutter.sdk` and will fail immediately |
| Git repository | **not a git repo** — no history, no diff safety net |

Consequently every claim in this document is derived from **source reading, not
from a successful compile or test run.** No build or test result is asserted.

To unblock: install Flutter 3.44.0 (bundles Dart), a **JDK** 17+ (not a JRE),
and the Android SDK; then create `android/local.properties` with
`flutter.sdk=<path>` and `sdk.dir=<path>`. Two physical Android phones are
required for real verification regardless — Wi-Fi Direct cannot be exercised
between emulators.

---

## 9. Android Reality Check for the Telephony Phases

Recorded here so later phases are not designed around false assumptions. This is
a summary; `docs/ANDROID_LIMITATIONS.md` will carry the detail.

- **Placing a call — supported.** `ACTION_CALL` intent, or
  `TelecomManager.placeCall()` with `CALL_PHONE`. Phone 1 can dial on Phone 2's
  command.
- **Answering / rejecting — supported, with caveats.**
  `TelecomManager.acceptRingingCall()` requires `ANSWER_PHONE_CALLS` (API 26+);
  `TelecomManager.endCall()` is API 28+ and also requires it.
- **Call state — supported.** `TelephonyManager` /
  `TelephonyCallback.CallStateListener` (API 31+) with `READ_PHONE_STATE`.
- **SIM presence, signal strength, data state — supported**, via
  `TelephonyManager.simState`, `SignalStrength`, `SubscriptionManager`. Some
  fields are OEM- or version-dependent and must render as
  *"Unavailable on this device."*
- **SMS — supported with heavy caveats.** Sending via `SmsManager` needs
  `SEND_SMS`; *reading* SMS needs `READ_SMS`/`RECEIVE_SMS`, and Google Play
  restricts these to the app that is the user-selected **default SMS handler**.
  Functional for sideloaded/self-signed builds; a Play Store listing would be
  rejected without the default-handler role.
- **Cellular call audio — NOT available to a normal third-party app.** This is
  the hard limit. `AudioPlaybackCaptureConfiguration` (API 29+) *explicitly
  cannot capture* voice-call streams; the `VOICE_CALL`, `VOICE_DOWNLINK`, and
  `VOICE_UPLINK` `AudioSource`s require the **privileged, signature-level
  `CAPTURE_AUDIO_OUTPUT`** permission, granted only to system/OEM apps.
  There is no public API by which an unprivileged app can route cellular call
  audio to a remote device. **Any such feature must be reported as unsupported**
  rather than attempted — exactly as the brief demands.

  *Legitimate fallback:* the existing microphone Audio Link can carry
  speakerphone audio acoustically (Phone 1 on speaker, its mic picks up both
  sides). That is a real, honest, degraded capability — not a virtual SIM, and
  it must never be described as one.

---

## 10. Recommended Reuse Decisions

**Keep and build on (do not rewrite):**
`WiFiDirectManager`, `WiFiDirectBroadcastReceiver`, the entire `protocol/`
package, `SessionStateMachine`, the `SessionTransport` interfaces, the UDP
rendezvous in `ProtocolV2TransportSetup`, `JitterBuffer`, `RtpPacket` /
`RtcpPacket`, `NativeOpus` + libopus, `DiagnosticsLogger`, `WdCableRuntime`.

**Extend:**
`ProtocolFrameType` (add gateway/call/SMS types), `ProtocolConstants`
(capabilities + a v3 header carrying timestamp, device ID, auth tag),
`AudioCapabilities` (report cellular-audio as unsupported), the manifest
(telephony permissions), `PermissionManager` (telephony permission group).

**Add (greenfield):**
`core/security/` (pairing, session keys, AEAD, replay window),
`gateway/` (`TelephonyGateway`, `SmsGateway`, `GatewayState`),
`client/RemoteTelephonyController`, `compat/CompatibilityChecker`,
and Flutter `dialer` / `messages` / `gateway` / `diagnostics` tabs.

---

## 11. Immediate Risks

1. **No encryption on the control channel.** Any nearby device that completes
   the P2P connection can speak the protocol. Adding *telephony* control on top
   of an unauthenticated channel would let a stranger place calls on Phone 1's
   SIM. **Phases 5 and 13 must land before Phase 7, not after.**
2. **No build verification possible here** (§8) — all changes are currently
   unverifiable, which raises the cost of large speculative edits and argues for
   small, reviewable increments.
3. **Framework mismatch** (§7a) — needs an explicit decision before Phase 2.
4. **Protocol version bump.** Adding header fields breaks v2 wire compatibility
   with the shipped Windows companion app (`WDCableWUI`). This must be a
   deliberate v3 with a documented negotiation path, not a silent change.
5. **`minSdk 33` + 64-bit-only ABIs** already narrow the device pool; the
   compatibility checker (Phase 16) must state this plainly.

---

## 12. Change Log — Security Layer (Phases 4, 5, 13)

Added after the §0–§11 inspection, following the two decisions recorded below.

**Decisions taken:** (a) keep the Flutter UI and extend the Kotlin layer, per
Phase 2's own escape clause — see §7a; (b) write code now and verify on a machine
with a toolchain, since this one cannot build — see §8.

**Sequencing:** pairing and encryption were built *before* any telephony work,
because §11.1 identified adding remote call control to an unauthenticated channel
as the project's largest risk. This also matches the brief's FIRST MILESTONE,
which is explicitly not telephony.

### New Kotlin sources (`src/main`)

```
core/model/DeviceId.kt              random per-install UUID; no hardware identifier
core/security/
  CryptoPrimitives.kt               HKDF-SHA256, HMAC, AES-256-GCM (platform JCE only)
  NonceGenerator.kt                 counter-based 96-bit GCM nonces
  ReplayWindow.kt                   RFC 6479-style 1024-bit anti-replay bitmap
  SessionKeys.kt                    directional key derivation, 12 h staleness
  PairingCode.kt                    six-digit code + its honest threat analysis
  PairingRecord.kt                  minimal persisted pairing
  PairingStore.kt                   Keystore-wrapped storage, device identity
  SecureHandshake.kt                P-256 ECDH + transcript + confirm MAC + SAS
  SecureChannel.kt                  stateful v3 framing, replay/freshness/identity
protocol/SecureProtocolCodec.kt     96-byte v3 header, AES-256-GCM, CRC32
```

### Modified (all additive)

| File | Change | Compatibility |
|---|---|---|
| `ProtocolFrame.kt` | added `timestampMs`, `senderDeviceId` **with defaults**; content-based `equals`/`hashCode` | every existing construction site compiles unchanged |
| `ProtocolConstants.kt` | added `VERSION_V3`, `HEADER_SIZE_V3`, `FLAG_ENCRYPTED`, gateway capabilities | `VERSION`/`HEADER_SIZE` untouched |
| `ProtocolFrameType.kt` | added IDs 40–44, 50–51, 60–64, 70–73, 80–81 | no existing ID reused |
| `ProtocolError.kt` | added 7 v3 error kinds | additive |

**`ProtocolCodec.kt` (v2) was not touched**, so the shipped Windows companion app
keeps interoperating. v3 is a separate codec negotiated via the `secure.v3`
capability.

### New tests (`src/test`, plain JVM — no device needed)

`CryptoPrimitivesTest` (HKDF vs RFC 5869 vectors), `NonceGeneratorTest`,
`ReplayWindowTest`, `PairingCodeTest`, `SecureHandshakeTest` (two-party exchange
incl. MITM detection), `SecureProtocolCodecTest`, `SecureChannelTest`.

### Defects found and fixed by static audit

No compiler has run, so these were found by reading and by mechanical
cross-checking rather than by a build. Recorded because each is the kind of bug
that survives a clean compile.

**1. Pairing timeout race producing permanently inconsistent state.**
`SecureSessionNegotiator.DEFAULT_PAIRING_TIMEOUT_MS` and
`MethodChannelPairingPrompt.DEFAULT_TIMEOUT_MS` were both exactly 120 s. The
responder does not read `pair.confirm` until *after* its code prompt returns, so a
user taking almost the full two minutes made the responder write `pair.result` and
**persist the pairing** while the initiator timed out a moment later and persisted
nothing. The devices then disagreed about being paired, and every later reconnect
failed with a misleading "wrong code" until app data was cleared.
Fixed: one shared `PROMPT_TIMEOUT_MS` constant, a read timeout of 180 s with the
invariant documented, and a test pinning the ordering.

**2. Confirmation failure reported as "wrong pairing code" when nobody typed one.**
A failure against a *stored* secret now reports `pairing_desynchronised` instead.
The stored pairing is deliberately **not** auto-deleted: an attacker inside the
Wi-Fi Direct group could otherwise drop a legitimate pairing just by failing
confirmation on purpose.

**3. Initial gateway status push could never work.**
`negotiateSecurity` runs inside `performHandshake`, which is called *before*
`runtime = newRuntime`. `sendGatewayFrame` would therefore find a null runtime and
drop the frame — or, on a reconnect, write to the previous session's closed
transport. Removed; the client pulls the first status itself and later changes
push normally once `runtime` is set.

**4. `Telephony.Sms.*` column constants would not compile in Kotlin.**
`Telephony.Sms` inherits `_ID` from `BaseColumns` and `ADDRESS`, `BODY`, `DATE`,
`TYPE`, `READ`, `THREAD_ID`, `MESSAGE_TYPE_INBOX` from `TextBasedSmsColumns`.
Java inherits interface constants into an implementing class's static scope;
**Kotlin does not.** Eight references now go through the declaring interface, which
is valid in both languages.

**6. No way to forget a pairing — the recovery path did not exist.**
`PairingStore.removePairing`, `clearAll`, `markSasVerified`, `pairedDeviceIds` and
`isPaired` were all **dead code**: nothing called them, and there was no unpair
action anywhere in the UI. That made finding (1) unrecoverable — a
`pairing_desynchronised` state could only be cleared by wiping app data — and it
also meant a lost or stolen peer device could not be revoked.
Fixed: `pairedDevicesSummary()` (which deliberately never exposes the long-term
secret), `SessionManager.forgetPairing` / `forgetAllPairings` which also drop the
session, three method-channel entry points, Dart service methods, and a "Forget
this device" action in the Diagnostics security card.

**7. A Dart string literal broken across lines.**
Introduced while adding the forget-device dialog: an `
` escape was mangled into
a real newline inside a single-quoted string, which Dart does not allow. Caught by
scanning every Dart and Kotlin file for unterminated literals rather than by
noticing it. Rewritten as a `Column` of two `Text` widgets, which needs no escapes
at all. The scanner found no other instance.

**5. `ProtocolChannel.AUDIO` documented as encrypted when it is not** — see the
Audio Link entry under "Not done".

Mechanical checks that came back clean: method-channel method names **and
argument keys** in both directions, Kotlin named arguments against 790 collected
signatures, Dart widget constructors (31 parsed, every required parameter
supplied), all `com.jingcjie.*` imports, all l10n getters against both locale
implementations, Dart service/event/model references, unterminated string
literals in every source file, the v3 header offsets against the documented
table and the byte positions hardcoded in tests, and the RFC 5869 HKDF vectors
recomputed independently.

Four of those are now scripts in [`tools/static_checks/`](../tools/static_checks/)
so they can be re-run — `python tools/static_checks/run_all.py`. They are a
safety net, not a substitute for a compiler: they prove names resolve, keys agree
and literals terminate, but they cannot prove types match. Each was also debugged
against its own false positives before being trusted — a checker that cries wolf
is worse than none, and the first version of three of them did.

### Not done

- **Not compiled, not run.** See §8. Every claim above is from source reading.
- **The Audio Link is not encrypted by the v3 layer.** Found during a static
  audit, not assumed: `ProtocolChannel.AUDIO` has zero references anywhere, and
  audio runs on its own RTP/RTCP UDP sockets rather than a `SessionTransport`, so
  `negotiateSecurity` never sees it. Microphone audio has only the Wi-Fi Direct
  group's link-layer WPA2. Documented in `ANDROID_LIMITATIONS.md` §5.1 rather than
  fixed, because SRTP is substantial and would break the Windows companion app.
- **`TelephonyGateway`, `SmsGateway`, `AndroidCompatibilityProbe` and
  `DataSharingProbe` have no tests.** All four are thin wrappers over platform
  APIs that cannot run off-device; the logic worth testing was extracted behind
  `CellularGateway`, `SmsGatewayApi` and `CompatibilityChecker`.
- **`pairingUnverifiedWarning` is translated but unused.** The Diagnostics tab
  surfaces the unverified state through `diagnosticsSasUnverified` instead; the
  older string is left in place for a future Connection-tab badge.
- **`SecureChannel` has no instrumented test on real sockets.** The unit tests
  cover it over in-memory streams; the socket path needs two phones.

### Session wiring (added after the first security block)

| File | Change |
|---|---|
| `SecureSessionNegotiator.kt` | **new** — drives the four-message exchange over a transport; transport- and Android-agnostic, so both peers are testable in-process |
| `PairingRepository.kt` | **new** — `PairingRepository` + `PairingPrompt`, so the flow is testable without `Context`/Keystore |
| `SessionTransport.kt` | `activateSecurity()` / `isSecure` added **with default bodies**, so existing implementers and test doubles compile unchanged |
| `SocketSessionTransportAdapter.kt` | `SocketSessionTransport` switches codec once secured; unchanged until then |
| `SessionManager.kt` | `negotiateSecurity()` called at the end of `performHandshake` (covers both call sites); per-channel keys; secure counters in `getConnectionStats` |
| `SessionKeys.kt` / `SecureHandshake.kt` | optional `channelLabel` so each channel gets its own key |

Two design corrections were made while wiring, both because the first shape was
wrong rather than merely improvable:

1. **The auth secret moved from the `SecureHandshake` constructor to
   `acceptOffer`.** Which secret applies — a stored long-term one or a freshly
   typed code — depends on *who the peer is*, and that is only known once its
   offer arrives.
2. **Negotiating down is decided by the capability handshake, not by aborting the
   exchange.** A mid-exchange bail-out leaves the two peers disagreeing about how
   to parse the next frame. So `secure.v3` is advertised only when this device can
   actually complete a pairing.

### Pairing UI (Flutter)

| File | Change |
|---|---|
| `session/MethodChannelPairingPrompt.kt` | **new** — bridges `PairingPrompt` to Dart; hops to the main thread, blocks the calling background thread on a latch, and refuses to run on the main thread rather than deadlocking |
| `MainActivity.kt` | supplies the prompt, which is what turns the secure capability on |
| `lib/widgets/pairing_dialogs.dart` | **new** — `PairingCoordinator` plus the code-display, code-entry and SAS-comparison dialogs |
| `lib/wifi_direct_service.dart` | two request/reply handlers (`pairingCodeRequestHandler`, `pairingVerifyHandler`) and two events |
| `lib/main.dart` | owns the coordinator for the life of the page |
| `lib/l10n/*` | 13 strings, EN + ZH, in the ARB files **and** the checked-in generated classes |

A third bug was found and fixed while wiring this: a **failed** pairing left the
gateway's code dialog on screen forever, because nothing on that side was waiting
for a reply. `PairingPrompt.onPairingFinished(paired)` — a default no-op on the
interface — is now called on both the success and failure paths.

The dialogs fail closed throughout: no UI attached, a dismissed entry dialog, or a
timeout all resolve to "do not pair", never to "proceed anyway".

### Gateway and remote call control (Phases 6, 7)

Built on the user's instruction to proceed ahead of a verified build; this layer
sits on top of the still-uncompiled security work below it.

| File | Role |
|---|---|
| `gateway/PhoneNumberValidator.kt` | strict allowlist before dialling — an **MMI/USSD defence**, not input tidying |
| `gateway/GatewayStatus.kt` | status model where **null means "unavailable"** and is omitted, never defaulted; plus `TelephonyCodes` mappings |
| `gateway/GatewayCapabilities.kt` | pure capability rules + machine-readable reasons for every absence |
| `gateway/CallStateTracker.kt` | maps Android's three call states to the protocol's five |
| `gateway/CellularGateway.kt` | interface extracted so the handler's rules are testable off-device |
| `gateway/TelephonyGateway.kt` | the Android wrapper — `TelephonyManager`, `TelecomManager`, battery |
| `gateway/GatewaySessionHandler.kt` | frame routing **and the rule that telephony is refused unless authenticated** |

`SessionManager` starts the telephony listener only after the peer authenticates,
and shuts it down in `cleanup()`. The manifest gains `READ_PHONE_STATE`,
`CALL_PHONE` and `ANSWER_PHONE_CALLS`, and declares `android.hardware.telephony`
as **not required** so Phone 2 may be a tablet or Wi-Fi-only device.

Two bugs were caught while writing this layer:

1. `TelephonyGateway.failed()` routed through `CallStateTracker.onFailed()`, which
   **resets the tracker**. A "gateway busy" rejection would therefore have wiped
   the in-progress call's id and start time. Pre-flight rejections now build the
   failure event without mutating tracker state.
2. `TelephonyGateway` and `GatewaySessionHandler` reference each other, so the
   handler is `by lazy` rather than depending on property initialisation order.

Three honest limitations are encoded rather than papered over:

- `answerConfirmed` is **always false on outgoing calls** — Android does not tell
  an unprivileged app when the callee picks up.
- `telephony.audio.cellular` is **never advertised**, and
  `GatewayCapabilitiesTest` asserts that it never can be.
- `sms.read` requires the default-SMS-handler role, not merely the permission.

### Client side and dialer UI

| File | Role |
|---|---|
| `client/RemoteTelephonyController.kt` | **new** — Phone 2's side: sends commands, tracks gateway status and call state, refuses impossible requests locally |
| `lib/models/gateway_models.dart` | **new** — `GatewayStatusInfo`, `RemoteCallState`, capability names |
| `lib/widgets/dialer_tab.dart` | **new** — gateway card, keypad, active-call controls, call-audio notice |
| `session/SessionManager.kt` | routes gateway frames to the handler then the client controller; 7 new method-channel entry points; clears gateway state on disconnect |
| `lib/wifi_direct_service.dart` | 7 methods, 2 events |
| `lib/main.dart` | seventh tab |
| `lib/l10n/*` | 29 further strings, EN + ZH (213 keys each, in parity) |

`RemoteTelephonyController` refuses a request **before it reaches the wire** when
it cannot succeed — invalid number, a capability the gateway never advertised, a
call already in progress, an unauthenticated session. The gateway rejects all of
these too and remains the security boundary; refusing locally just means the user
is told the truth immediately rather than after a round trip, and a late failure
does not read as a flaky link. The number is validated on both ends, so an MMI
sequence never even leaves the client.

The UI carries the same honesty as the protocol:

- A missing value renders **"Unavailable on this device"**, and the signal row
  checks `signalLevel == null` rather than falsiness, so a genuine zero-bar
  reading is still shown.
- Controls appear only for capabilities the gateway actually advertised; where one
  is missing, the gateway's own reason is shown instead of a dead button.
- An outgoing call's timer is labelled **"Since dialing"** with an explanation,
  because `answerConfirmed` is false and the app must not imply it knows the
  callee picked up.
- The cellular-call-audio notice is always on screen, so no user is left expecting
  to hear the call on Phone 2.

### SMS (Phase 9)

| File | Role |
|---|---|
| `gateway/SmsModels.kt` | `SmsMessageSummary`, `SmsConversation` (pure thread grouping), `SmsRequestValidator` |
| `gateway/SmsGatewayApi.kt` | `SmsSendResult` + the interface, so the handler's rules stay testable |
| `gateway/SmsGateway.kt` | `SmsManager` send with multipart splitting; SMS provider read |
| `client/RemoteTelephonyController.kt` | `requestSmsList`, `sendSms`, and the two inbound frames |
| `lib/widgets/messages_tab.dart` | composer, conversation list, outcome notice |

`SEND_SMS` and `READ_SMS` are now declared, because the feature exists. The
manifest comment records why that is a **distribution** decision rather than a
technical one: Google Play grants the SMS permission group only to the user's
default SMS handler, so reading will not work on a Play build without that role.
Send and read are advertised as **separate capabilities** precisely because most
devices will have one and not the other, and the Messages tab explains the gap
rather than showing an empty list that reads as a bug.

Two places where the wording had to stay accurate:

- A successful send reports **"handed to the network"**, never "delivered".
  Delivery reports need registered `PendingIntent`s and are carrier-dependent;
  none are registered, so claiming delivery would be inventing a fact.
- The recipient of an SMS goes through the same [PhoneNumberValidator] as a
  dialled number. An unvalidated remote string reaching the telephony stack is
  the problem, regardless of which API it arrives at, and the client rejects an
  MMI sequence before it ever leaves the device.

An outgoing message is never counted as unread, whatever the provider's read flag
says — a detail the grouping test pins.

### Compatibility, data sharing, diagnostics (Phases 16, 10, 15)

| File | Role |
|---|---|
| `compat/CompatibilityChecker.kt` | pure rules producing SUPPORTED / PARTIALLY_SUPPORTED / UNSUPPORTED with a reason each |
| `compat/AndroidCompatibilityProbe.kt` | reads the device state the rules need, defensively |
| `gateway/DataSharingProbe.kt` | Internet reachability, kept distinct from the Wi-Fi Direct link |
| `lib/widgets/diagnostics_tab.dart` | compatibility, security, connection and Internet cards |

**Phase 16.** Three levels rather than a boolean, and the distinction that matters
is *whose problem it is*: Wi-Fi being off or a permission ungranted is
`PARTIALLY_SUPPORTED`, because the device is capable and the user can fix it —
reporting `UNSUPPORTED` there would tell someone to give up on working hardware.
Absent Wi-Fi Direct or a below-minimum Android version is `UNSUPPORTED`.

Findings carry a `required` flag, and only required ones fold into the overall
verdict. That exists for one specific case: **cellular call audio is permanently
`UNSUPPORTED` on every device**, and without the flag it would drag every phone to
"not supported", which is false — everything else works. `CompatibilityCheckerTest`
pins both halves of that.

**Phase 10** is deliberately reporting-only. There is no public API to enable
tethering: `TetheringManager.startTethering()` needs the signature-level
`TETHER_PRIVILEGED`, the older hidden call is blocked by the non-SDK restrictions,
and `WRITE_SETTINGS` does not help. Hotspot state is not readable either, so
`hotspotActive` is always null and the UI says Android does not expose it rather
than showing "off". Internet is reported from `NET_CAPABILITY_VALIDATED`, not
merely connected, so a captive portal or an upstream-less hotspot is not counted
as working.

**Phase 15.** The security card shows an unencrypted link **as unencrypted** —
rendering a padlock over the legacy plaintext protocol would be the most
misleading thing the screen could do — and flags a pairing whose SAS was never
confirmed. The connection card renders a fixed key list from the native stats map,
and the compatibility card shows explanations supplied by the native side rather
than mapping reason codes to prose in Dart, so a new reason cannot be added
without text to display.
