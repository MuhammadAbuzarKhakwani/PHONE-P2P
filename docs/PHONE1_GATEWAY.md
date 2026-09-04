# PHONE 1 — GATEWAY

The device with the SIM. Everything cellular happens here; Phone 2 only ever sends
commands.

---

## 1. Setup

1. Install the app, leave the SIM in, mobile data on.
2. Grant **Nearby devices**, **Phone**, and — if you want SMS — **SMS**.
3. Open **Diagnostics** and confirm the verdict is not UNSUPPORTED.
4. Leave the app open on the Connection tab so Phone 2 can discover it.

Phone 1 accepts the incoming Wi-Fi Direct invitation, then **displays a six-digit
pairing code** for the user to read out to whoever is holding Phone 2.

---

## 2. What Phone 1 advertises

Capabilities are recomputed from live permission state on every status read, never
cached — a permission revoked in Settings must immediately stop being offered.

| Capability | Requires |
|---|---|
| `gateway`, `gateway.status` | telephony hardware |
| `telephony.state` | `READ_PHONE_STATE` |
| `telephony.dial` | `CALL_PHONE` **and** a usable SIM |
| `telephony.answer` | `ANSWER_PHONE_CALLS`, API 28+ |
| `sms.send` | `SEND_SMS` **and** a usable SIM |
| `sms.read` | `READ_SMS` **and** being the default SMS handler |
| `telephony.audio.cellular` | **never advertised, on any device** |

A capability that is absent carries a machine-readable reason
(`permission_not_granted`, `no_usable_sim`, `requires_default_sms_handler`, …), so
Phone 2 shows *why* a control is unavailable rather than a dead button.

`GatewayCapabilitiesTest` asserts that call audio can never become advertised,
whatever permissions are granted.

---

## 3. What it reports

`GatewayStatus`, pushed on change and on request:

| Field | Notes |
|---|---|
| `simState` | always present; `unknown` is a valid answer |
| `callState` | `idle` / `ringing` / `offhook` |
| `carrier`, `networkType` | omitted when unavailable |
| `signalLevel` | 0–4. **Omitted** when unavailable — `0` is a genuine reading |
| `mobileData`, `batteryPercent`, `charging` | omitted when unavailable |

**A null field is omitted from the JSON entirely and rendered by Phone 2 as
"Unavailable on this device".** It is never defaulted to `0` or `"Unknown"` — a
user cannot tell an invented default from a real measurement, and a plausible
wrong number is worse than an honest gap.

Network type is bucketed to a generation (2G/3G/LTE/5G). An unrecognised radio
technology reports **nothing** rather than being guessed into the nearest bucket.

---

## 4. Calls

```
CALL_REQUEST  ──► validate number ──► TelecomManager.placeCall()
                                 └──► CALL_STATE dialing
TelephonyCallback ──► CALL_STATE ringing / active / ended
```

### Number validation is a security control

Every number arriving from the network is checked against a strict allowlist —
optional leading `+`, then digits — before it reaches the dialer.

This is not formatting hygiene. Without it, a client could make Phone 1's own SIM
execute **MMI/USSD codes**:

| Code | Effect |
|---|---|
| `*#06#` | reveals the IMEI |
| `**21*<number>#` | **unconditional call forwarding** to an attacker's number |
| `##002#` | clears forwarding |

Call forwarding is the serious one: it silently redirects the SIM owner's incoming
calls, and it is one dial away. `*`, `#`, `,`, `;` and any URI scheme are rejected.
The number is validated on Phone 2 as well, so such a string never even reaches
the wire — but Phone 1's check is the one that matters, and it is not optional.

Numbers are **masked in diagnostics** (`*******4567`), because the diagnostic log
is exportable.

### Call state is coarser than it looks

Android gives an unprivileged app only `idle` / `ringing` / `offhook`. It does
**not** report the moment a callee answers.

So on an **outgoing** call, `answerConfirmed` is always `false`: the radio goes
off-hook while the far end is still ringing, and starting a talk-time counter
there would be wrong. Only an inbound call observed going `ringing → offhook` sets
it true.

---

## 5. SMS

- **Sending** works with `SEND_SMS`. Long bodies are split with
  `divideMessage()` and sent multipart, because sending an over-length body as one
  message truncates it on some carriers.
- A successful send reports **"handed to the network"**, never "delivered" — no
  delivery-report `PendingIntent` is registered, and delivery reporting is
  carrier-dependent.
- **Reading** requires being the device's default SMS handler in practice. See
  `ANDROID_LIMITATIONS.md` §4.

Bodies are never logged. Recipients are masked.

---

## 6. Security posture

Phone 1 refuses **every** gateway frame — including a status request — unless the
session is running the v3 authenticated framing. Carrier, SIM and signal describe
a real person's device, and dialling on someone's SIM at their expense is the
exact risk the security layer exists to prevent.

The refusal is enforced in one place (`GatewaySessionHandler`) so no future call
site can forget it, and `GatewaySessionHandlerTest` asserts the gateway is never
even reached on an unauthenticated session.

Phone 1 stores, per paired peer: a random peer device ID, a 32-byte long-term
secret (encrypted under an Android Keystore key), a display name, timestamps, and
whether the confirmation code was verified. **Never** the SIM PIN, IMSI, ICCID,
carrier credentials, contacts, or location.

---

## 7. The hard limit

**Phone 1 cannot send its call audio to Phone 2.** The conversation happens on
Phone 1's own speaker and microphone. No supported Android API gives an
unprivileged app the cellular audio stream — see `ANDROID_LIMITATIONS.md` §3.

The honest workaround is the existing Audio Link: put Phone 1 on speakerphone and
let its microphone pick up both sides. That is a room microphone, with echo and
background noise and no access to the clean downlink, and it must be described as
exactly that.

---

## 8. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Phone 2 sees no dial button | `CALL_PHONE` not granted, or no usable SIM — check the reason shown |
| Calls fail with `permission_denied` | permission revoked after pairing; capabilities are recomputed, so re-check Settings |
| Session dies when screen is off | OEM battery management; exempt the app from battery optimisation |
| Conversation list empty and explained | expected unless Phone 1 is the default SMS app |
| Signal shows "Unavailable" | OEM does not expose it, or `READ_PHONE_STATE` is missing |
