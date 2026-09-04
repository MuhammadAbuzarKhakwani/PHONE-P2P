# PHONE 2 — CLIENT

The device without a SIM. It drives Phone 1's cellular features remotely; it never
gains cellular capability of its own.

> **Phone 2 does not have a virtual SIM.** It has no cellular identity, never
> registers on a network, and cannot place a call by itself. Every cellular action
> happens on Phone 1, triggered by a command. Any wording that suggests otherwise
> is wrong.

---

## 1. Setup

1. Install the app. Remove the SIM, or use a device that has no SIM slot.
2. Grant **Nearby devices** (and **Microphone** if you want the Audio Link).
3. Connection tab → scan → pick Phone 1.
4. Type the six-digit code Phone 1 is showing.
5. Compare the confirmation number that then appears on both phones and, if they
   match, tap **They match**.

Step 5 is not a formality — see §5.

Diagnostics on Phone 2 will report the gateway capability as
PARTIALLY_SUPPORTED with reason *no usable SIM*. That is correct: Phone 2 cannot
*be* a gateway. It can still *use* one.

---

## 2. Screens

| Tab | Shows |
|---|---|
| **Dialer** | gateway status, keypad, active-call controls, call-audio notice |
| **Messages** | composer, conversation list, send outcome |
| **Diagnostics** | compatibility verdict, security state, connection stats, Internet |

The six original tabs (connection, chat, speed test, audio, files, settings) are
unchanged.

---

## 3. How the client decides what to offer

Phone 2 shows a control only when Phone 1 has **advertised the matching
capability**. A gateway that has not yet reported its status supports nothing, so
controls stay unavailable until the first `gateway.status` arrives rather than
being offered optimistically and failing.

Where a capability is missing, the **gateway's own reason** is shown — *no usable
SIM*, *permission not granted*, *requires default SMS handler* — not a generic
"unavailable".

### Failing locally

Requests that cannot succeed are refused **before anything goes on the wire**:

- an invalid number or an MMI sequence
- a capability the gateway never advertised
- a call already in progress
- an unauthenticated session

Phone 1 rejects all of these too and remains the security boundary. Refusing
locally is about latency and clarity: a failure that arrives after a network round
trip reads like a flaky link rather than "this device cannot do that". A useful
side effect is that an MMI sequence never leaves the phone.

---

## 4. What the call UI does and does not claim

An active call shows a timer labelled **"Since dialing"**, with an explanation.

That label is deliberate. Android does not tell an unprivileged gateway when the
callee picks up, so for an outgoing call the app genuinely does not know the talk
time. Presenting the elapsed time as call duration would be inventing information.
Only an inbound call that the gateway watched go `ringing → offhook` is genuinely
confirmed.

A notice is permanently visible on the Dialer:

> **Remote cellular call audio is not supported on this device. The conversation
> happens on the gateway phone's own speaker and microphone.**

It does not disappear once a call connects, because that is exactly when someone
would otherwise raise Phone 2 to their ear and hear nothing.

---

## 5. The confirmation number matters

After pairing, both phones display a six-digit **confirmation number**.

The typed pairing code alone does **not** stop an active man-in-the-middle. An
attacker relaying the whole handshake performs its own key exchange with each
side, learns both shared secrets, and can brute-force a six-digit code offline in
milliseconds.

The confirmation number closes that. It is derived from the completed handshake
transcript, and a relaying attacker necessarily has a *different* transcript with
each phone — so its two numbers cannot both match.

- **Numbers match** → tap "They match". Diagnostics shows a verified pairing.
- **Numbers differ** → tap "They differ" and do not continue. Something is
  relaying your connection.
- **Dismissed** → the pairing still works but is recorded as unverified, and
  Diagnostics says so.

Full analysis in `ANDROID_LIMITATIONS.md` §5.

---

## 6. Messages

Phone 2 asks Phone 1 to send; **Phone 1 is the cellular sender**, and messages go
out from Phone 1's number.

A successful send says *"Handed to the network"* — never "delivered". No delivery
report is registered, so delivery is genuinely unknown.

The conversation list is usually empty with an explanation, because Android
restricts reading SMS to the phone's default messaging app. That is expected
behaviour on most devices, not a fault.

---

## 7. Internet is a separate thing

The **Wi-Fi Direct link** and an **Internet connection** are different, and
Diagnostics keeps them apart deliberately.

Everything in this app — pairing, chat, files, dialling, SMS — works with **no
Internet at all** on either phone. Confirm it by putting both phones in airplane
mode with Wi-Fi on (test T12).

Getting Phone 2 *onto the Internet* through Phone 1's mobile data is a separate
networking function that this app does not provide: Android has no public API to
enable tethering, so the app reports state and points at Settings. It also cannot
read hotspot state, and says so rather than showing "off".

On many phones the radio cannot hold a Wi-Fi Direct group and a hotspot at the
same time, so enabling the hotspot may drop the control link.

---

## 8. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Phone 1 not in the scan list | Wi-Fi off, Nearby devices not granted, or Phone 1 not in the foreground |
| Asked for the code on every connect | pairing was not stored — check it completed, and app data was not cleared |
| Call button greyed out | read the reason under it; it is the gateway's own |
| Confirmation numbers differ | **stop.** Do not pair. Retry somewhere else |
| Security card says "Not encrypted" | the peer does not speak v3 — telephony is correctly refused |
| Nothing heard during a call | expected. Audio is on Phone 1 — see §4 |
| "Pairing desynchronised" | the two stored pairings diverged. Diagnostics → Security → **Forget this device**, then pair again |
