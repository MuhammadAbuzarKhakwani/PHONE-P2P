# ANDROID LIMITATIONS

What this app can and cannot do on stock Android, with the reason in each case.

This document exists because the honest answer to "can Phone 2 use Phone 1's SIM
over Wi-Fi Direct?" is **partly, and not for call audio**. Nothing here is
softened, and no limitation is left implicit.

> **This app does not create a virtual SIM.** Phone 2 never gets cellular
> identity, never registers on a network, and cannot place a call by itself.
> Phone 1 remains the only device with a SIM, and every cellular action happens
> on Phone 1, triggered by a remote command.

**Verification status:** the claims below are from the public Android API
contracts. The security, gateway, telephony, SMS and compatibility layers are all
implemented and unit-tested, but **nothing has been compiled or run** — see
`ARCHITECTURE.md` §8. Every behaviour that depends on the platform rather than on
pure logic must be re-verified on real hardware before the UI is trusted, and the
device-dependent items below (signal, network type, radio concurrency) will differ
between handsets by design.

---

## 1. Summary

| Capability | Status | Gate |
|---|---|---|
| Wi-Fi Direct discovery, connect, sockets | **Supported** | `NEARBY_WIFI_DEVICES` (API 33+) |
| Encrypted, authenticated app channel (control + bulk) | **Supported** | app-level, no OS gate |
| Encrypted **audio** stream | **NOT implemented** | §5.1 — RTP/UDP, link-layer WPA2 only |
| Code-based pairing | **Supported, with a caveat** | §5 |
| SIM present / absent | **Supported** | none |
| Carrier name, network type | **Usually** | device/carrier dependent |
| Signal strength | **Usually** | API 28+, `READ_PHONE_STATE` |
| Mobile data state | **Supported** | none for basic state |
| Call state (idle/ringing/offhook) | **Supported** | `READ_PHONE_STATE` |
| Place an outgoing call | **Supported** | `CALL_PHONE` |
| Answer / reject / end a call | **Supported, version-gated** | `ANSWER_PHONE_CALLS`, API 26 / 28 |
| Send SMS | **Supported, policy-gated** | `SEND_SMS`, §4 |
| Read SMS / conversations | **Partial, policy-gated** | default SMS handler, §4 |
| Phone's own number | **Unreliable** | often null; carrier dependent |
| **Route live cellular call audio to Phone 2** | **NOT POSSIBLE** | §3 — privileged permission |
| **Programmatically enable tethering** | **NOT POSSIBLE** | §6 — privileged permission |
| Wi-Fi Direct + hotspot at the same time | **Device dependent** | §6 |
| Read hotspot / tethering state | **NOT POSSIBLE** | §6 — system API |
| Compatibility self-check | **Supported** | app-level, no OS gate |

---

## 2. What works, and what it needs

### Placing a call — supported

`Intent(ACTION_CALL)` or `TelecomManager.placeCall()` with the `CALL_PHONE`
runtime permission. Phone 1 can dial a number that Phone 2 sends.

### Answering, rejecting, ending — supported, version-gated

- `TelecomManager.acceptRingingCall()` — API 26+, needs `ANSWER_PHONE_CALLS`.
- `TelecomManager.endCall()` — **API 28+**, needs `ANSWER_PHONE_CALLS`.

Both are real runtime permissions the user must grant explicitly. On devices
below API 28 the end-call path is unavailable; since this project's `minSdk` is
33, that is not a practical concern here, but the capability must still be probed
rather than assumed.

### Call state — supported

`TelephonyManager` with `TelephonyCallback.CallStateListener` (API 31+;
`PhoneStateListener` is deprecated from API 31) and `READ_PHONE_STATE`. This
gives `idle` / `ringing` / `offhook`.

Note this is coarser than the brief's five states. `dialing` versus `active` is
**not** directly observable from `TelephonyManager` — distinguishing them
requires either an `InCallService` (which means becoming the default dialer) or
inference from state transitions plus timing. The gateway should report what it
can actually observe and mark the rest unavailable, rather than guessing.

### SIM, signal, network — mostly supported

- `TelephonyManager.getSimState()` — no permission required.
- `getNetworkOperatorName()`, `getDataState()` — generally available.
- `getSignalStrength()` — API 28+, `READ_PHONE_STATE`. `SignalStrength.getLevel()`
  gives a 0–4 bucket.

OEM and carrier variation here is real. **Any field the platform does not return
must render as "Unavailable on this device"** — never as 0, "Unknown", or an
invented default.

### The phone's own number — unreliable

`TelephonyManager.getLine1Number()` needs `READ_PHONE_NUMBERS` (or
`READ_PHONE_STATE`) and **very often returns null or an empty string**. The
number is only present if the carrier provisioned it onto the SIM. Do not build
UI that depends on it.

---

## 3. Cellular call audio — not possible, and why

**This is the hard limit of the whole project.** A normal third-party app cannot
capture or inject live cellular call audio on any current Android version.

- `MediaRecorder.AudioSource.VOICE_CALL`, `VOICE_DOWNLINK` and `VOICE_UPLINK`
  require the **`CAPTURE_AUDIO_OUTPUT`** permission, which is
  `signature|privileged` — grantable only to apps signed with the platform key or
  installed on the privileged system partition. A sideloaded or Play-installed app
  cannot hold it.
- `AudioPlaybackCaptureConfiguration` (API 29+), the modern playback-capture API,
  **explicitly cannot capture the voice-call stream**. It is scoped to apps that
  opt in, and telephony audio is excluded by design.
- The accessibility-service route that some call recorders used was closed in
  Android 10.
- There is no `AudioManager`, `Telecom`, or `InCallService` API that hands an
  unprivileged app the call's audio buffers. Becoming the default dialer via
  `InCallService` grants call *control*, **not** the audio stream.

**Consequence:** Phone 2 can tell Phone 1 to dial, and can watch the call's state,
but **the conversation happens on Phone 1's speaker and microphone.** The app must
say so plainly.

The client renders this as:

> **Remote cellular call audio is not supported on this device.**

`ProtocolConstants.CAPABILITY_CELLULAR_CALL_AUDIO` exists but is
**never advertised by any build**. It is defined only so code and UI can name the
capability the app does not have.

### The legitimate fallback

The existing Audio Link (Opus over RTP, microphone source) can carry
**speakerphone audio acoustically**: Phone 1 on speakerphone, its microphone
picking up both sides of the conversation, streamed to Phone 2.

That is a real, useful, honest capability. It is also clearly degraded — it is a
room microphone, so it has echo, background noise, and no access to the clean
downlink. It must be labelled as what it is and **never** described as remote call
audio or a virtual SIM.

Anything better requires a **privileged or system build**, a **rooted device**, or
an **OEM partnership**. Those are out of scope for this project and are not
attempted.

---

## 4. SMS — works, but Play Store policy is the real gate

- **Sending:** `SmsManager.sendTextMessage()` with `SEND_SMS`. Technically
  straightforward.
- **Reading:** `READ_SMS` / `RECEIVE_SMS` and the `Telephony.Sms` content
  provider.

The blocker is not the API, it is **Google Play policy**: `SEND_SMS`, `READ_SMS`
and `RECEIVE_SMS` are restricted permissions, available only to an app that is the
user-selected **default SMS handler** (or that has an approved policy exception).

So:

| Distribution | SMS status |
|---|---|
| Sideloaded / self-signed / internal | **Works** once the user grants the permissions |
| Google Play listing | **Rejected** unless the app becomes the default SMS handler |

Becoming the default SMS handler is a large commitment — the app must then handle
*all* the user's SMS, implementing the full set of required components. That is a
product decision well beyond a gateway feature, and is **not** undertaken here.

**Phone 2 never has a SIM and never sends SMS itself.** It asks Phone 1 to send,
and Phone 1 is the actual cellular sender. Delivery reports are surfaced only when
the platform provides them.

---

## 5. Pairing security — the honest caveat

The six-digit pairing code combined with ephemeral P-256 ECDH gives:

- **Confidentiality against a passive eavesdropper — yes.** Sniffing the link
  reveals nothing; keys come from ECDH.
- **Protection against pairing with the wrong nearby device — yes.**
- **Protection against an active man-in-the-middle — no, not from the code
  alone.**

An attacker who relays the whole handshake runs its own ECDH with each side, so it
learns both shared secrets. It can then brute-force a six-digit code **offline**
against the confirmation MAC — a million HMACs, milliseconds — and complete the
handshake with both phones.

This is a property of the construction, not a bug. Closing it properly requires a
PAKE (SPAKE2 and similar), which binds the code into the group operation so it
cannot be brute-forced offline.

**What this app does instead:** after key agreement both phones display a
**six-digit short authentication string** derived from the handshake transcript. A
man-in-the-middle necessarily has a *different* transcript with each side, so its
two SAS values cannot both match. **A user who compares the two screens detects
the attack.** This is the check that actually closes the MITM hole; the typed code
handles device selection and the passive case.

`PairingRecord.sasVerified` records whether the user confirmed the match. When it
is false, the UI must not present the pairing as fully verified.

### 5.1 The Audio Link is not covered by the app-layer encryption

Worth stating separately because it is easy to assume otherwise once the rest of
the link is encrypted.

The v3 secure channel covers the **CONTROL** and **BULK** TCP channels. The Audio
Link does not use them: `AudioService` opens its own **RTP/RTCP UDP sockets**
(ports 8990 and 8991) and writes Opus frames directly. `ProtocolChannel.AUDIO`
exists in the enum but has no references anywhere in the codebase.

So the protection for microphone audio is:

| Threat | Protected? |
|---|---|
| Someone outside the Wi-Fi Direct group | **Yes** — the group is WPA2-protected at the link layer |
| A device inside the group that is not paired with you | **No** |
| Tampering or replay of audio packets | **No** — no authentication tag |

Fixing this properly means SRTP. That is a substantial piece of work and would
break audio interoperability with the Windows companion app, so it is recorded as
a **known gap** rather than being implied to be handled. Users should treat the
Audio Link as they would a room microphone on a shared local network.

Other deliberate choices:

- The Keystore wrapping key is created **without**
  `setUserAuthenticationRequired`, because the gateway has to re-key while the
  phone is locked in a pocket. Availability was chosen over strictness; the
  consequence is that an attacker with an unlocked device and app-data access
  could use the pairing.
- Sessions are rekeyed after **12 hours** (`SessionKeys.MAX_SESSION_AGE_MS`).

---

## 6. Mobile data sharing — separate from the control channel

The brief's Phase 10 distinction matters and the UI must keep it visible:

- **REMOTE GATEWAY CONNECTION** — the Wi-Fi Direct control channel. Works with no
  Internet at all.
- **INTERNET CONNECTION** — Phone 2 reaching the Internet through Phone 1's
  cellular data. A *different* networking function.

**There is no public API to turn tethering on programmatically.**
`TetheringManager.startTethering()` (API 30+) requires `TETHER_PRIVILEGED`, which
is `signature|privileged`. The older `ConnectivityManager.startTethering()` is
hidden and blocked by the non-SDK interface restrictions. `WRITE_SETTINGS` does
not help.

So the app **must not** attempt a workaround. It should show the current state and
direct the user to Settings to enable the hotspot themselves.

### Radio concurrency — device dependent

Whether a device can hold a **Wi-Fi Direct group and a Wi-Fi hotspot at the same
time** varies by chipset and OEM. Many devices cannot; some drop the P2P group
when SoftAP starts. This cannot be assumed either way and must be **detected and
reported**, not promised.

Practically: on many phones, routing Phone 2's Internet through Phone 1's hotspot
while also holding the Wi-Fi Direct control link is not possible simultaneously.
The compatibility checker must probe this rather than claim it.

---

## 7. Platform and runtime constraints

- **`minSdk` is 33 (Android 13).** Required by `WifiP2pManager.startListening`,
  `ACTION_WIFI_P2P_REQUEST_RESPONSE_CHANGED`, and the `NEARBY_WIFI_DEVICES`
  permission path. Devices below Android 13 are unsupported.
- **64-bit only.** Only `arm64-v8a` and `x86_64` `libopus.so` are bundled;
  `armeabi-v7a` is deliberately excluded. A 32-bit-only device cannot run the
  audio features.
- **Foreground service required.** The session runs under a
  `connectedDevice` foreground service. Android 14+ enforces service-type
  declarations and Android 15 tightens background start rules further.
- **Doze and OEM battery management** will kill sockets on aggressive OEM builds
  (Xiaomi, Huawei, Oppo, Samsung to a lesser degree). The user may need to exempt
  the app from battery optimisation. This is a known, unavoidable source of
  dropped sessions.
- **Wi-Fi Direct is not emulator-testable.** Two physical phones are required for
  any real verification.
- **No root is used anywhere,** and no root-only API is called. Nothing in this
  project asks for or benefits from root.

---

## 8. What would require what

| Want | Needs |
|---|---|
| Remote cellular call audio | Platform-signed build, privileged partition, or root |
| Programmatic tethering | Platform-signed build (`TETHER_PRIVILEGED`) |
| SMS on Google Play | Becoming the default SMS handler |
| `dialing` vs `active` call states | `InCallService` + default dialer role |
| Reliable own-number | Carrier provisioning; not fixable in the app |
| Anything in this app | **No Internet, no cloud, no account** |

The last row is the one the project actually delivers: the Phone 1 ↔ Phone 2
control link is fully offline — no cloud, no Firebase, no SIP server, no Google
dependency for the core connection.
