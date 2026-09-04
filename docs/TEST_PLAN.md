# TEST PLAN

How to verify this app, split into what a machine can check and what requires two
physical Android phones.

**Nothing in this repository has been compiled or run.** Section 1 must pass
before section 3 is worth attempting.

---

## 0. What you need

| | Requirement |
|---|---|
| Toolchain | Flutter 3.44.0, a **JDK** 17+ (not a JRE), Android SDK with `adb` |
| `android/local.properties` | `flutter.sdk=<path>` and `sdk.dir=<path>` |
| Devices | **Two physical Android 13+ phones**, 64-bit (`arm64-v8a` or `x86_64`) |
| Phone 1 | A working SIM, mobile data on |
| Phone 2 | **SIM removed**, or a device with no SIM slot |

Wi-Fi Direct **cannot be tested between emulators**. There is no substitute for
two handsets.

Devices should be different models if possible. Most of what breaks in this app
is OEM-specific: Wi-Fi Direct behaviour, background execution, and telephony
reporting all vary sharply between vendors.

---

## 1. Build and static checks — run these first

Start with the offline checks, which need nothing but Python and take a second:

```bash
python tools/static_checks/run_all.py
```

These verify what neither compiler can: that method-channel argument keys agree
across the Dart/Kotlin boundary (a mismatch is a silent runtime null), that
Kotlin named arguments match declared parameters, that Dart widget calls supply
every required parameter, and that no string literal is unterminated. They found
three real defects during development. See `tools/static_checks/README.md`,
including the one known benign finding.

Then the real toolchain:

```bash
# 1. Dart analysis. The Flutter layer has never been analysed; expect findings.
flutter pub get
flutter analyze

# 2. Kotlin unit tests. 27 test classes, no device needed.
cd android && ./gradlew :app:testDebugUnitTest

# 3. Dart tests.
flutter test

# 4. Full debug build.
flutter build apk --debug
```

Expect the first `flutter analyze` and the first `gradlew` run to produce errors.
That is the point of running them.

### What the automated tests already cover

| Brief's item | Covered by | Kind |
|---|---|---|
| 4. Packet framing | `ProtocolCodecTest`, `SecureProtocolCodecTest` | automated |
| 5. Packet validation | `SecureProtocolCodecTest` (CRC, truncation, bad magic, version) | automated |
| 6. Encryption | `CryptoPrimitivesTest` (RFC 5869 vectors), `SecureChannelTest` | automated |
| 7. Pairing | `SecureHandshakeTest`, `SecureSessionNegotiatorTest` | automated |
| 8. Call command protocol | `GatewaySessionHandlerTest`, `RemoteTelephonyControllerTest` | automated |
| 9. Call-state protocol | `CallStateTrackerTest` | automated |
| 10. SMS protocol | `SmsModelsTest`, plus SMS cases in the two handler tests | automated |
| Device compatibility | `CompatibilityCheckerTest` | automated |
| 1, 2, 3, 11, 12, 13, 14, 15 | **manual only** — sections 3–5 below | manual |

---

## 2. Install

```bash
adb devices                       # confirm both phones are listed
flutter install -d <phone-1-id>
flutter install -d <phone-2-id>
```

Grant on **Phone 1**: Nearby devices, Phone, Microphone, SMS.
Grant on **Phone 2**: Nearby devices, Microphone.

Then, on both, open **Diagnostics** and confirm the compatibility verdict is
SUPPORTED or PARTIALLY_SUPPORTED. If either says **UNSUPPORTED**, read the reason
and stop — the rest of the plan will not work on that device.

Keep a log running throughout:

```bash
adb -s <id> logcat -c && adb -s <id> logcat | grep -i "wdcable\|WiFiDirect\|flutter"
```

---

## 3. Core link — the first milestone

These must all pass before telephony is worth testing.

### T1 — Peer discovery

1. Open the app on both phones, Connection tab.
2. Tap scan on Phone 2.

**Expect:** Phone 1 appears within ~15 s, listed by name. Only WDCable peers are
listed — the app filters by DNS-SD service `_wdcable._tcp`, so unrelated P2P
devices should not appear.

**If it fails:** check Wi-Fi is on (not just Wi-Fi Direct), check the Nearby
devices permission, and check Diagnostics → compatibility.

### T2 — Connection and pairing

1. Tap Phone 1 in the list on Phone 2.
2. Accept the system Wi-Fi Direct invitation on Phone 1.
3. **Phone 1 shows a 6-digit pairing code.** Type it into Phone 2.
4. Both phones then show a **6-digit confirmation number**.

**Expect:** the two confirmation numbers are **identical**. Tap "They match" on
both. Diagnostics → Security then shows *Encrypted and authenticated*, with a
peer device ID.

> **This comparison is the actual man-in-the-middle defence.** The typed code
> alone does not provide it — see `ANDROID_LIMITATIONS.md` §5. If the two numbers
> ever differ, something is relaying your connection: tap "They differ" and do not
> continue.

### T3 — Wrong code is rejected

Repeat T2 but type a wrong code on Phone 2.

**Expect:** the session fails with a *wrong code* error, no pairing is stored, and
the app does **not** offer an immediate retry loop. Reconnecting starts a fresh
pairing.

### T4 — Reconnect without re-pairing

1. Disconnect from the Connection tab.
2. Reconnect.

**Expect:** **no pairing code is requested.** The stored long-term secret is used.
Diagnostics → Security shows encrypted again, and the frame counters restart from
zero (each session derives fresh keys).

### T5 — Heartbeat and connection loss

1. Connect and leave idle for 2 minutes.
2. Walk one phone out of range, or turn its Wi-Fi off.

**Expect:** idle stays connected (5 s heartbeat, 15 s timeout). On loss, the
client reports disconnection within ~15 s, and does not sit claiming "connected".

### T6 — Automatic reconnection

Bring the phone back into range or re-enable Wi-Fi.

**Expect:** the session re-establishes with exponential backoff (1 s → 30 s, up to
10 attempts) without user action, and without asking for the pairing code.

### T7 — Existing features still work

Regression check, because the security layer changed the transport:
send a **chat** message, transfer a **file** (>10 MB), run a **speed test**, and
start the **Audio Link**, in both directions.

**Expect:** all behave as before pairing was introduced. If file transfer or audio
broke, suspect the codec switch in `SocketSessionTransport`.

---

## 4. Robustness

### T8 — App restart

Kill the app on Phone 2 (swipe from recents) and reopen it.

**Expect:** it recovers to a sane state; no crash; no stale "connected" that is
not real. Re-pairing is **not** required.

### T9 — Wi-Fi Direct disconnect from Settings

Forget the P2P group in Android Settings while connected.

**Expect:** both sides notice and report disconnection; no hang; a later reconnect
works.

### T10 — Sleep / wake

Connect, lock both phones, wait 10 minutes, unlock.

**Expect:** either the session survives, or it reports a clean disconnect and
reconnects. What must **not** happen is a UI that still claims "connected" over a
dead socket.

> Expect trouble here on aggressive OEM builds (Xiaomi, Huawei, Oppo, and Samsung
> to a lesser degree). Exempt the app from battery optimisation and retest.
> Diagnostics flags this as `background.execution` = PARTIALLY_SUPPORTED.

### T11 — Rotation and configuration changes

Rotate both phones during: an idle connection, a file transfer, an active call,
and while the pairing dialog is open. Also change system language.

**Expect:** no crash, no dropped session, and the pairing dialog survives rotation.

### T12 — Airplane mode with Wi-Fi on

Put **both** phones in airplane mode, then re-enable Wi-Fi only.

**Expect:** discovery, connection, pairing, chat and file transfer all still work.
**This is the core offline requirement** — no Internet, no cloud, no account.
Telephony features will correctly report unavailable (no cellular).

---

## 5. Remote SIM gateway

Phone 1 has the SIM. Phone 2 does not.

### T13 — Gateway status

Open **Dialer** on Phone 2.

**Expect:** SIM shows *Ready*; carrier, network and signal are populated. Anything
the platform does not expose reads **"Unavailable on this device"** — that is
correct behaviour, not a bug. A signal of `0/4` is a real reading and must be
distinguishable from unavailable.

On Phone 2's own Diagnostics, the gateway capability should be
PARTIALLY_SUPPORTED with reason *no usable SIM*.

### T14 — Place a call

Enter a number you control on Phone 2 and press Call.

**Expect:** Phone 1 dials. Phone 2 shows *Dialing*, then *In call*, with a timer
labelled **"Since dialing"**.

> The timer counts from dialling, **not** from when the callee answered. Android
> does not tell an unprivileged app when the far end picks up, so this is
> deliberate. See `ANDROID_LIMITATIONS.md` §2.

**Audio is on Phone 1**, not Phone 2. The notice at the bottom of the Dialer says
so. This is the project's hard limit, not a defect.

### T15 — MMI codes are rejected

Try to dial each of `*#06#`, `**21*<number>#`, `##002#`.

**Expect:** rejected on Phone 2 **before anything is transmitted**. Phone 1 must
never dial them.

> This is a security test, not a formatting one. `**21*<number>#` is
> unconditional call forwarding — if it ever reaches the dialer, an attacker can
> silently redirect the SIM owner's incoming calls.

Also confirm normal formatting still works: `+1 (555) 123-4567` should dial.

### T16 — Hang up, answer, reject

- Hang up an active call from Phone 2 → Phone 1 ends it.
- Call **into** Phone 1 from a third phone → Phone 2 shows *Ringing* with Answer
  and Reject.
- Answer from Phone 2 → the call connects, and here `answerConfirmed` is genuinely
  true because the platform reported `ringing → offhook`.

### T17 — Unpaired device is refused

Hardest to stage, and the most important. Using a second install or a modified
client, connect over Wi-Fi Direct but **do not complete pairing**, then send a
call request.

**Expect:** refused with `session_not_authenticated`. The gateway must not dial,
and must not even report its SIM or carrier.

### T17b — Forget a device, and recover from a desynchronised pairing

1. Diagnostics → Security on either phone → **Forget this device**, confirm.
2. The session drops.
3. Reconnect.

**Expect:** a **new pairing code** is requested — the stored secret is gone. This
is also the recovery path if either phone ever reports
`pairing_desynchronised`: forget the device on one side and pair again.

To stage the desync deliberately, forget the device on **one** phone only, then
reconnect. The side that still has a record reports `pairing_desynchronised`
rather than "wrong pairing code", because nobody typed a code.

### T18 — SMS

- **Send** from Phone 2 → arrives from Phone 1's number. The UI says *"Handed to
  the network"*, never "delivered" — no delivery report is registered.
- **Read**: unless Phone 1 is the device's **default SMS app**, the conversation
  list correctly explains that Android restricts reading SMS to the default
  messaging app. That is expected, not a failure.
- Try sending to `**21*5551234567#` → rejected locally.

### T19 — Permission revoked mid-session

Revoke Phone permission on Phone 1 in Settings while connected.

**Expect:** capabilities are recomputed, and Phone 2's Call button becomes
unavailable with the reason *permission not granted* — rather than a button that
fails when pressed.

### T20 — Internet is kept distinct from the link

Diagnostics → Internet on Phone 2.

**Expect:** it states that Internet is separate from the Wi-Fi Direct link, that
Android does not let an app read hotspot state, and that tethering must be enabled
in Settings. The app must not offer a tethering switch.

---

## 6. Recording results

For each failure capture: device model, Android version, the step, `adb logcat`
output, and **Diagnostics → export logs**. The native layer writes structured
`key=value` diagnostics under `wifi`, `transport`, `security`, `gateway`, `sms`
and `client` categories, which is usually enough to place a failure without a
debugger.

Suggested table:

| Test | Phone 1 | Phone 2 | Result | Notes |
|---|---|---|---|---|
| T1 | | | | |

---

## 7. Known gaps this plan will not cover

Stated so nobody spends time hunting them as bugs:

- **Remote cellular call audio does not exist** and cannot be tested. No supported
  Android API provides it (`ANDROID_LIMITATIONS.md` §3).
- **Audio Link traffic is not encrypted** by the app. It is RTP over UDP with only
  the Wi-Fi Direct group's link-layer WPA2 (§5.1).
- **Delivery confirmation for SMS** is not implemented.
- **`dialing` vs `active`** cannot be distinguished precisely without becoming the
  default dialer.
- **Programmatic tethering** is impossible for a normal app (§6).
