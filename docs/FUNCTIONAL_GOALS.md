# FUNCTIONAL GOALS — status

Every requirement from the project brief, what implements it, and how that claim
was checked.

## The headline

**Code-complete. Not hardware-verified.**

All 18 phases are implemented and 296 unit tests are written. **Nothing has been
compiled or run** — no `flutter analyze`, no Gradle build, no install, no
two-phone session. So every row below reads "implemented", never "working".

Seven real defects were found by static audit alone (`ARCHITECTURE.md` →
*Defects found and fixed by static audit*). That is evidence of care, and equally
evidence that unreviewed code carries bugs. Expect the first build to fail.

| Legend | Meaning |
|---|---|
| **Implemented** | code exists; unit-tested where it can be tested off-device |
| **Partial** | the behaviour exists, but not exactly as the brief words it |
| **Not possible** | blocked by Android itself, documented rather than attempted |

---

## Phase-by-phase

| # | Goal | Status | Evidence |
|---|---|---|---|
| 1 | Inspect before rewriting | **Implemented** | `ARCHITECTURE.md` §0–§11 written before any edit; `ProtocolCodec`, `WiFiDirectManager`, `ProtocolV2TransportSetup`, `AudioService` still carry their original timestamps |
| 2 | Package structure | **Partial** | Applied to the Kotlin layer (`core/`, `wifi`, `transport`, `gateway/`, `client/`, `compat/`). UI stayed **Flutter**, not Compose — Phase 2's own escape clause, since rewriting ~9.6k lines of working localized UI had no functional gain |
| 3 | Wi-Fi Direct | **Implemented** | `WiFiDirectManager` (1,462 lines, pre-existing). Group-owner IP read from real `WifiP2pInfo`, never assumed. DNS-SD filtering so only app peers list |
| 3b | The 7 named states | **Partial** | `DISCONNECTED/DISCOVERING/CONNECTING/CONNECTED/FAILED` present. **`PEER_FOUND` and `RECONNECTING` do not exist by name** — the behaviours do (peer lists are emitted; reconnect uses 10 attempts with 1 s→30 s backoff) |
| 4 | Versioned protocol | **Implemented** | v3 header verified field-by-field: version, message ID, timestamp, sender device ID, type, payload, CRC32 + GCM tag. Offsets checked against the doc table and the byte positions in tests |
| 4b | CBOR | **Partial** | Metadata is **JSON**, not CBOR. Deliberate: `org.json` is already a dependency, and adding `kotlinx.serialization` could not be verified to resolve without a build |
| 5 | Pairing | **Implemented** | Gateway shows a 6-digit code, client types it; P-256 ECDH + HKDF + confirmation MAC; long-term secret stored under an Android Keystore key. Plus a **short authentication string** both phones compare — see Gaps |
| 6 | Gateway status | **Implemented** | SIM, carrier, network, signal, mobile data, call state, battery, version. Unavailable values are **omitted**, never defaulted; the client renders "Unavailable on this device" |
| 7 | Remote call control | **Implemented** | Dial, answer, reject, hang up, duration, live state. MMI/USSD blocked on both ends |
| 7b | Five call states | **Partial** | All five are reported, but `dialing` vs `active` cannot be distinguished precisely — Android gives an unprivileged app only idle/ringing/offhook. `answerConfirmed` is always false outgoing, and the UI says "Since dialing" |
| 8 | Audio | **Partial** | Opus + RTP/RTCP + jitter buffer + sequence numbers + timestamps all exist and are unit-tested. **No named `AudioTransport` interface** — the brief asked for one; the behaviour is provided by the pre-existing `AudioService` instead |
| 8b | Cellular call audio | **Not possible** | Requires signature-level `CAPTURE_AUDIO_OUTPUT`. Never advertised, and `GatewayCapabilitiesTest` asserts it never can be |
| 9 | SMS | **Implemented** | Send via `SmsManager` with multipart splitting; read via the SMS provider. Reports "handed to the network", never "delivered" |
| 9b | Reading SMS in practice | **Partial** | Needs the default-SMS-handler role. Advertised as a separate capability so the client shows exactly which half works |
| 10 | Mobile data | **Not possible** (by design) | No public API enables tethering; hotspot state is unreadable. The app reports and points at Settings, and keeps "gateway link" separate from "internet" |
| 11 | Offline | **Implemented** | **Verified mechanically**: runtime deps are only `flutter`, `flutter_localizations`, `intl`, `cupertino_icons`, `provider`. No Firebase, GMS, HTTP client, SIP or socket.io anywhere |
| 12 | Robustness | **Implemented** | Heartbeat 5 s/15 s, 10-attempt backoff, duplicate detection via `ReplayWindow`, sequence numbers, graceful disconnect, foreground service, generation counters |
| 13 | Security | **Implemented** | AES-256-GCM, HKDF (**checked against RFC 5869 vectors**), counter-based nonces, replay window, session keys per direction *and* per channel, 12-hour staleness, unknown devices rejected |
| 14 | UI | **Implemented** | 9 tabs. Dialer with keypad and call states; connection, gateway and client status |
| 15 | Diagnostics | **Implemented** | Wi-Fi Direct state, group owner, transport counters, security state, SIM/call/SMS/audio capability, internet |
| 16 | Compatibility checker | **Implemented** | SUPPORTED / PARTIALLY_SUPPORTED / UNSUPPORTED with a reason and plain-language explanation for each |
| 17 | Tests | **Partial** | 296 unit tests covering framing, validation, encryption, pairing, call/SMS protocol, compatibility. Items 1, 2, 3, 11–15 are **device-dependent** and are manual tests T1–T20 in `TEST_PLAN.md` |
| 18 | Documentation | **Implemented** | All eight named documents exist |

---

## The final-goal statement

| Claim | True? |
|---|---|
| Phone 1 = SIM + cellular gateway | yes |
| Phone 2 = no SIM + client | yes |
| Connection = direct Wi-Fi P2P | yes |
| Core control = offline, no cloud | **yes, verified** — no network dependency exists |
| Internet optionally via Phone 1 | **reported only** — Android forbids enabling tethering |
| Calling = only what Android permits | yes — dial/answer/reject/hang up, and audio honestly refused |
| Never claims a universal virtual SIM | yes — stated in README, QUICKSTART, both phone guides, and in the UI itself |

---

## Known gaps, stated plainly

1. **Nothing has been compiled.** The largest risk by far. The Dart layer
   (~2,000 new lines) has never been analysed.
2. **Cellular call audio does not exist and cannot.** The conversation happens on
   Phone 1's speaker and microphone.
3. **The Audio Link is not encrypted.** RTP over UDP with only Wi-Fi Direct's
   link-layer WPA2. `ProtocolChannel.AUDIO` is never used, so the v3 layer never
   sees it. Fixing it means SRTP (`ANDROID_LIMITATIONS.md` §5.1).
4. **No `AudioTransport` interface**, though Phase 8 asked for one. Adding it
   without refactoring the working 1,289-line `AudioService` onto it would be
   dead code, and that refactor is not safe to attempt blind.
5. **The pairing code alone does not stop an active man-in-the-middle.** The
   confirmation-number comparison does. Users who skip it get a pairing recorded
   as unverified, and the UI says so.
6. **`PEER_FOUND` / `RECONNECTING` are not named states**, though both behaviours
   exist.
7. **Metadata is JSON, not CBOR.**
8. **SMS reading needs the default-SMS-handler role**, so it will usually be
   unavailable.
9. **`TelephonyGateway`, `SmsGateway`, `AndroidCompatibilityProbe` and
   `DataSharingProbe` have no tests** — thin platform wrappers that cannot run
   off-device. The logic worth testing was extracted behind interfaces.

---

## How each claim here was checked

Not from memory. Every "Implemented" above rests on one of:

- **Mechanical cross-check** — `tools/static_checks/run_all.py`: channel method
  names *and* argument keys in both directions, Kotlin named arguments against
  790 signatures, Dart widget constructors, unterminated literals.
- **Independent recomputation** — the RFC 5869 HKDF vectors, and the v3 header
  offsets simulated and compared against the docs, the codec constants, and the
  byte positions hardcoded in tests.
- **Targeted greps** confirming a mechanism exists where claimed (heartbeat,
  replay window, nonce generator, Keystore, backoff, foreground service) and,
  for the offline claim, that no cloud dependency exists anywhere.
- **Reading the code**, which is how all seven defects were found.

None of it substitutes for a compiler. These checks prove that names resolve,
keys agree and literals terminate; they cannot prove that types match.

**Next step: `QUICKSTART.md`.**
