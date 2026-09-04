# PROTOCOL

The local application protocol spoken between Phone 1 (gateway) and Phone 2
(client) over the Wi-Fi Direct link.

**Implementation status:** §1–§5 (v3 codec, crypto, handshake, pairing, session
wiring) and all of §6 — gateway status, call control and SMS — are implemented and
unit-tested. Nothing here has been compiled or run — see `ARCHITECTURE.md` §8.

`sms.event` reports `status: "sent"` to mean **accepted for sending**, never
delivered: delivery reports require registered `PendingIntent`s and are
carrier-dependent, and none are registered.

---

## 0. Two wire versions

| | v2 | v3 |
|---|---|---|
| Codec | `ProtocolCodec` | `SecureProtocolCodec` |
| Header | 56 bytes | 96 bytes |
| Encryption | none | AES-256-GCM |
| Integrity | none | CRC32 + GCM tag |
| Sender identity | none | 128-bit device ID |
| Timestamp | none | 64-bit epoch ms |
| Status | **unchanged, still shipped** | new |

v2 is deliberately untouched so the existing Windows companion app
([WDCableWUI](https://github.com/jingcjie/WDCableWUI)) keeps interoperating. v3
is negotiated at handshake via the `secure.v3` capability; a peer that offers
only v2 still gets chat, file transfer, speed test, and audio, but **is refused
the gateway and telephony channels**, because those must never run on an
unauthenticated link.

---

## 1. v3 frame header (96 bytes, big-endian)

```
off  size  field
  0     4  magic            0x57444342  "WDCB"
  4     2  version          3
  6     2  headerSize       96
  8     2  frameType        see §2
 10     2  flags            bit0 = FLAG_ENCRYPTED
 12     2  channel          1=CONTROL 2=BULK (3=AUDIO defined, never used)
 14     2  reserved         0
 16     8  streamId
 24     8  sequenceNumber   per-connection, monotonic, drives replay detection
 32    16  correlationId    message ID (UUID); echoed on the matching reply
 48     8  timestampMs      sender wall clock, epoch ms
 56    16  senderDeviceId   random per-install UUID
 72    12  nonce            AES-GCM IV; all zero when not encrypted
 84     4  bodyCrc32        CRC32 over the on-wire body
 88     4  metadataLength   plaintext length
 92     4  payloadLength    plaintext length
```

Body:

- **Not encrypted** — `metadata || payload`, exactly `metadataLength + payloadLength` bytes.
- **Encrypted** — `AES-256-GCM(metadata || payload)`, which is
  `metadataLength + payloadLength + 16` bytes (the JCE appends the tag).

Limits: metadata ≤ 64 KiB, payload ≤ 1 MiB. Both are validated **before**
allocation, so a hostile length field cannot drive an OOM.

### Why two integrity mechanisms

They do different jobs and it is worth being precise about which is which:

- **`bodyCrc32` detects corruption.** It is checked first so a damaged frame is
  dropped before any AES work. It is **not** a security control — a CRC is
  trivially recomputed by an attacker.
- **The GCM tag provides authentication.** It covers the entire header as
  associated data, so frame type, channel, sequence number, timestamp and sender
  ID cannot be altered without the tag failing.

The CRC field itself is excluded from the AAD (zeroed in the AAD copy) because it
is computed over the ciphertext and therefore cannot be known until after
sealing. That is safe: on an encrypted frame every byte the CRC protects is
already covered by the tag.

### Nonces

12 bytes: a 4-byte per-generator random prefix followed by an 8-byte
monotonically increasing counter (`NonceGenerator`).

Random nonces are **not** used. Under a fixed AES-GCM key, a repeated 96-bit
nonce leaks the XOR of two plaintexts and allows forgery by recovering the GHASH
key. At 50 audio packets/second a birthday collision on a random 96-bit nonce is
a real risk over a long session; a counter removes it. Each direction **and each
channel** has its own key, so no two counters ever run under the same key.

---

## 2. Frame types

| ID | Name | Direction | Status |
|---:|---|---|---|
| 1 | `handshake.hello` | both | existing |
| 2 | `handshake.ack` | both | existing |
| 3 | `heartbeat.ping` | both | existing |
| 4 | `heartbeat.pong` | both | existing |
| 5 | `close` | both | existing |
| 6 | `error` | both | existing |
| 10 | `control.message` | both | existing |
| 11 | `ack` | both | existing |
| 20–23 | `bulk.start` / `chunk` / `complete` / `cancel` | both | existing |
| 30 | `audio.frame` | both | existing |
| **40** | `pair.request` | initiator → responder | **implemented** |
| **41** | `pair.response` | responder → initiator | **implemented** |
| **42** | `pair.confirm` | initiator → responder | **implemented** |
| **43** | `pair.result` | responder → initiator | **implemented** |
| **44** | `device.info` | both | defined |
| **50** | `gateway.status.request` | client → gateway | **implemented** |
| **51** | `gateway.status` | gateway → client | **implemented** |
| **60** | `call.request` | client → gateway | **implemented** |
| **61** | `call.state` | gateway → client | **implemented** |
| **62** | `call.answer` | client → gateway | **implemented** |
| **63** | `call.reject` | client → gateway | **implemented** |
| **64** | `call.hangup` | client → gateway | **implemented** |
| **70** | `sms.list.request` | client → gateway | **implemented** |
| **71** | `sms.list` | gateway → client | **implemented** |
| **72** | `sms.send.request` | client → gateway | **implemented** |
| **73** | `sms.event` | gateway → client | **implemented** |
| **80** | `audio.start` | both | defined |
| **81** | `audio.stop` | both | defined |

`call.state` is a **single** frame type carrying `dialing` / `ringing` / `active`
/ `ended` / `failed` in its metadata, rather than five separate types. That keeps
the client to one handler, and makes a future state an unknown *metadata value*
to ignore rather than an unknown *frame type* that would abort the connection.

---

## 3. Pairing and key agreement

Runs on the CONTROL channel in plaintext v3 (there are no keys yet), immediately
after the transport connects and before any application frame is accepted.

```
INITIATOR = Wi-Fi Direct group owner        RESPONDER = Wi-Fi Direct client

  pair.request  { deviceId, publicKey, nonce }  ---->
                                                <----  pair.response { deviceId, publicKey, nonce }
  pair.confirm  { mac }                         ---->
                                                <----  pair.result   { mac, ok }
```

All three JSON fields are base64. `publicKey` is an X.509-encoded P-256 point;
`nonce` is 32 random bytes.

### Derivation

```
Z            = ECDH_P256(localPrivate, peerPublic)
authSecret   = pairing code (first pairing)  |  stored long-term secret (reconnect)
ikm          = Z || authSecret

transcript   = SHA256( "wdcable/v3/transcript"
                     || initiatorDeviceId || responderDeviceId
                     || len(initiatorPub) || initiatorPub
                     || len(responderPub) || responderPub
                     || len(initiatorNonce) || initiatorNonce
                     || len(responderNonce) || responderNonce )

confirmKey   = HKDF(ikm, salt=transcript, info="wdcable/v3/confirm",  32)
initiatorMac = HMAC-SHA256(confirmKey, "INITIATOR")
responderMac = HMAC-SHA256(confirmKey, "RESPONDER")

k_i2r        = HKDF(ikm, salt=transcript, info="wdcable/v3/key/initiator-to-responder", 32)
k_r2i        = HKDF(ikm, salt=transcript, info="wdcable/v3/key/responder-to-initiator", 32)

sas          = HKDF(ikm, salt=transcript, info="wdcable/v3/sas", 4) mod 10^6
longTerm     = HKDF(ikm, salt=transcript, info="wdcable/v3/longterm", 32)
```

Notes:

- Raw ECDH output is **never** used as a key — it is not uniformly distributed.
  Everything goes through HKDF-SHA256 (verified against the RFC 5869 vectors in
  `CryptoPrimitivesTest`).
- Every variable-length field in the transcript is length-prefixed, so a shorter
  key and a longer nonce cannot be shifted between fields to collide.
- Fields are ordered by **role**, not by local/remote, so both devices compute the
  same transcript.
- Two directional keys, not one, so neither side has to coordinate a shared nonce
  counter.
- **P-256, not X25519.** `XDH` support is uneven across Android 13 devices and OEM
  security providers, while `secp256r1` ECDH has been universally available for
  years. Device compatibility wins; the security margin is ample either way.
- The ephemeral ECDH half gives **forward secrecy**: stealing the stored long-term
  secret later does not decrypt recorded sessions.

A confirmation mismatch means a wrong code, a diverged long-term secret, or
interference. The session **must be aborted, never retried silently** — retrying
turns a one-guess-per-attempt code into an online oracle.

### Where this runs

`SecureSessionNegotiator` drives the exchange on the CONTROL transport, called
from the end of `SessionManager.performHandshake` — so a reconnect is secured
exactly like a first connect. On success every open transport is switched to v3
via `SessionTransport.activateSecurity`.

Each channel gets its **own** key, derived with the channel name as an extra HKDF
label. They must not share one: each channel runs an independent nonce counter,
so a shared key would make nonce uniqueness depend on their random 4-byte
prefixes never colliding.

### What v3 does and does not cover

**Covered: `CONTROL` and `BULK`.** Those are the two TCP channels
`ProtocolV2TransportSetup.channelPorts()` opens, and they are the only entries in
the session's transport map. Chat, file transfer, speed test, pairing, gateway
status, call control and SMS all run over them and are all encrypted and
authenticated.

**Not covered: the Audio Link.** Despite `ProtocolChannel.AUDIO` existing in the
enum, no audio ever crosses a `SessionTransport` — `ProtocolChannel.AUDIO` has no
references anywhere in the codebase, and `ProtocolFrameType.AUDIO_FRAME` is never
sent. `AudioService` opens its own RTP and RTCP **UDP** sockets (ports 8990 and
8991) and writes Opus frames straight onto them.

So microphone audio is protected **only by the Wi-Fi Direct group's link-layer
WPA2**, which stops an outsider but not a device that is inside the group. Closing
that gap properly means SRTP, which is a real piece of work and would break the
Windows companion app's audio interop, so it is recorded here as a known gap
rather than quietly implied to be handled.

### Negotiating down

A peer that does not advertise `secure.v3` keeps the plaintext v2 channel and the
existing feature set. That decision is made from the **capability handshake,
before any pairing frame is sent** — never by starting the exchange and backing
out. Abandoning it halfway is unrecoverable: the two peers would disagree about
how to parse the next frame on the wire.

For the same reason this build advertises `secure.v3` only when a real
`PairingPrompt` is wired to the UI. Until then both peers agree up front to stay
on v2, rather than one of them failing mid-exchange. `MainActivity` supplies
`MethodChannelPairingPrompt`, so the capability is on in the shipped app.

### User-facing flow

1. Phone 1 shows a six-digit code (`onPairingCodeDisplay`).
2. Phone 2 asks the user to type it (`onPairingCodeRequired`). Cancelling
   abandons the session — it is never retried, because repeated guesses would
   turn the code into an online oracle.
3. Both phones show the SAS and ask whether it matches (`onPairingVerify`).
   Answering "they differ", or dismissing, still completes the session but
   records `sasVerified = false`, and the UI must not present that pairing as
   verified.
4. Either outcome fires `onPairingFinished` so any dialog is dismissed.

### Reconnection

After a successful first pairing both sides store `longTerm` (see §5) and every
later connection runs the same exchange with `authSecret = longTerm`. The user
types the code exactly once. Fresh ephemeral keys each time mean fresh session
keys each time.

---

## 4. Post-handshake frame rules

Once `SecureChannel.activate()` has been called, every inbound frame is checked,
in this order:

1. **Encrypted?** A plaintext frame is rejected (`ENCRYPTION_REQUIRED`). This is
   the anti-downgrade rule: an attacker must not be able to clear
   `FLAG_ENCRYPTED` and push the link back to plaintext.
2. **CRC** matches (`CHECKSUM_MISMATCH`) — cheap corruption check first.
3. **GCM tag** verifies (`AUTHENTICATION_FAILED`).
4. **Sender** equals the paired peer's device ID (`UNKNOWN_DEVICE`).
5. **Timestamp** within ±5 minutes (`STALE_FRAME`). Deliberately generous, and
   symmetric: two phones are never clock-synchronised, and rejecting
   future-dated frames would break a link between devices a minute apart. This is
   a coarse bound, not the replay defence.
6. **Sequence number** passes the 1024-wide sliding replay window
   (`REPLAYED_FRAME`). This is the real duplicate and replay defence. It accepts
   genuine reordering inside the window and rejects anything already delivered or
   older than the window.

Any of these is **fatal to the session**. A frame that fails authentication means
someone is interfering, and continuing to read would mean parsing
attacker-chosen bytes.

---

## 5. What is stored, and what is never sent

Persisted per paired peer (`PairingStore`, encrypted under an Android Keystore
AES-256-GCM key, only the wrapped ciphertext reaching `SharedPreferences`):

- peer device ID (random UUID)
- long-term secret (32 bytes)
- peer display name (untrusted, UI only)
- paired-at / last-seen timestamps
- whether the SAS was confirmed

The local device ID is a **random UUID**, deliberately not derived from any
hardware identifier — no IMEI, no MAC, no `ANDROID_ID`, no SIM serial. Those are
restricted, personal, or both.

**Never transmitted, by design:** SIM PIN, IMSI/ICCID, carrier credentials,
account passwords, contact lists, or location.

---

## 6. Gateway message shapes

Metadata is UTF-8 JSON. These are implemented on both ends — `GatewaySessionHandler`
answers them and `RemoteTelephonyController` consumes them.

```jsonc
// 51 gateway.status  — gateway → client
{
  "simState":      "ready",         // ready | absent | locked | unavailable
  "carrier":       "Example Mobile", // or omitted when unavailable
  "networkType":   "LTE",
  "signalLevel":   3,                // 0..4, or omitted
  "mobileData":    "connected",
  "callState":     "idle",
  "batteryPercent": 74,
  "gatewayVersion": "3.0.0",
  "capabilities":  ["gateway.status", "telephony.dial", "telephony.state"]
}
```

Any value the platform does not expose on that device/version is **omitted**, and
the client renders **"Unavailable on this device"**. It is never invented or
defaulted.

```jsonc
// 60 call.request — client → gateway
{ "requestId": "uuid", "number": "+15551234567" }

// 61 call.state — gateway → client
{ "requestId": "uuid", "state": "dialing",  "callId": "uuid" }
{ "requestId": "uuid", "state": "failed", "reason": "permission_denied" }
```

`call.state` values: `dialing`, `ringing`, `active`, `ended`, `failed`.
`failed` reasons: `permission_denied`, `unsupported_on_device`, `no_sim`,
`invalid_number`, `rejected_by_gateway`, `gateway_busy`, `platform_error`,
`session_not_authenticated`.

Every `call.state` event also carries:

- `outgoing` — whether this device initiated the call.
- `durationMs` — on `ended` only.
- **`answerConfirmed`** — true only when the platform actually confirmed the call
  connected. It is **always false for an outgoing call**: the radio goes off-hook
  while the far end is still ringing, and Android gives an unprivileged app no way
  to observe the moment the callee answers. The client must therefore present an
  outgoing call's duration as time since dialling, not talk time. Only an inbound
  call observed going `ringing → offhook` sets it true.

### Refused on an unauthenticated session

Every frame in this section is rejected with
`{"state":"failed","reason":"session_not_authenticated"}` unless the v3 secure
channel is active. That includes `gateway.status.request`: carrier, SIM and signal
state describe a real person's device and are not handed to an unpaired peer.

### Number validation

`call.request.number` is validated against a strict allowlist — optional leading
`+`, then digits — before it reaches the dialer. This is a security control, not
input tidying: passing an unvalidated remote string to the dialer would let a
client run **MMI/USSD codes** on the gateway's SIM, including
`**21*<number>#` to silently forward the victim's incoming calls.

### Capability gating

The gateway advertises only what that device has actually granted. A missing
capability means "this device cannot do this" and the client must render it as
unavailable rather than offering a control that cannot work.

`telephony.audio.cellular` is **never advertised by any build**. It is defined
only so code and UI can name the capability the app does *not* have. See
`ANDROID_LIMITATIONS.md`.

---

## 7. Test coverage

Runnable on a plain JVM with `./gradlew :app:testDebugUnitTest` — no device, no
emulator, no Wi-Fi Direct:

| Test | Covers |
|---|---|
| `CryptoPrimitivesTest` | HKDF vs **RFC 5869 vectors**, AEAD round-trip, tamper/AAD/wrong-key rejection |
| `NonceGeneratorTest` | 100k nonces without repetition, counter layout |
| `ReplayWindowTest` | duplicates, reordering, window edges, bitmap lapping |
| `PairingCodeTest` | six digits incl. leading zeros, spread, parsing |
| `SecureHandshakeTest` | full two-party exchange, key direction, wrong code, **MITM SAS mismatch**, reflection, resume |
| `SecureProtocolCodecTest` | round-trip, header/body tampering, CRC, downgrade, v2 rejection, freshness |
| `SecureChannelTest` | end-to-end encrypt/decrypt, replay, unknown device, stale, downgrade, 2000-frame run |
| `SecureSessionNegotiatorTest` | **both peers on real threads** over an in-memory pipe: first pairing, reconnect without prompting, wrong code, cancelled entry, stale one-sided pairing, per-channel key agreement |
