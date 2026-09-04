# WI-FI DIRECT

How the Phone 1 ↔ Phone 2 link is actually established.

This layer is the most device-independent part of the project and the part that
was already working before this work started. It is documented here rather than
rewritten.

---

## 1. Why the roles are inverted

The single most surprising thing in this codebase, and worth understanding before
reading the transport code.

In Wi-Fi Direct, the **group owner** acts as the access point. A client that joins
learns the group owner's IP address from `WifiP2pInfo.groupOwnerAddress`. But the
group owner is **not** told the addresses of the devices that joined — there is no
public API that hands it a client list with IPs.

So the naive design ("the group owner listens, clients connect to it") has the
problem backwards: only the *client* knows where to send.

The existing solution, in `ProtocolV2TransportSetup`:

| Wi-Fi P2P role | Transport role | Behaviour |
|---|---|---|
| `CLIENT` | **`LISTENER`** | binds TCP `8988` and `8989`, then sends UDP beacons to the group owner on `8987` |
| `GROUP_OWNER` | **`CONNECTOR`** | receives a beacon, learns the client's IP from the datagram, and dials back |

The client advertises itself; the group owner connects out. `SessionRole
.transportRole()` encodes the mapping, and the beacon carries a rendezvous id plus
the ports the client is listening on.

Setup deadline: 30 s.

---

## 2. Ports

| Port | Protocol | Purpose |
|---:|---|---|
| 8987 | UDP | rendezvous beacons (client → group owner) |
| 8988 | TCP | `CONTROL` channel — handshake, pairing, chat, gateway, telephony, SMS |
| 8989 | TCP | `BULK` channel — file transfer, speed test |
| 8990 | UDP | RTP audio |
| 8991 | UDP | RTCP |

Control and bulk are **separate TCP connections** so a large file transfer cannot
starve control traffic or delay a heartbeat.

The two UDP audio ports are not part of the session transport — see §6.

---

## 3. Discovery: DNS-SD, not just `discoverPeers`

`WiFiDirectManager` registers a local service (`WDCable` / `_wdcable._tcp`) with
`WifiP2pDnsSdServiceInfo`, and a matching `WifiP2pDnsSdServiceRequest`.

This matters because plain `discoverPeers()` returns **every** Wi-Fi Direct device
in range — printers, TVs, other phones. Service discovery lets the app tell which
of those actually run WDCable (`isWdCablePeer()`), so the peer list is not full of
devices that cannot possibly connect.

The app also calls `startListening()` (API 33) so it is discoverable without the
user having to initiate a scan.

---

## 4. The group owner address is never assumed

`WifiP2pInfo.groupOwnerAddress` is read from the real connection info returned by
`requestConnectionInfo`, at `WiFiDirectManager.kt:606` and `:1435`. It is not
hardcoded, and it is not assumed to be `192.168.49.1` — which is the common
default but is **not** guaranteed, and differs on some OEM builds.

If the address is missing, `ProtocolV2TransportSetup` fails with
`endpoint_unavailable` rather than guessing.

---

## 5. State model

The native layer tracks a richer set of states than the brief's seven, because
Wi-Fi Direct genuinely has more distinguishable conditions:

```
BlockedByPermission   Unavailable   Ready        Listening   ServiceRegistered
Discovering           Connecting    Connected    Disconnecting
UserStoppedScan       Background    Error
```

These map onto the session phases in `SessionStateMachine`, which are what the UI
mostly shows:

```
DISCONNECTED → WIFI_DIRECT_CONNECTED → CONNECTING_TRANSPORT → HANDSHAKING
             → READY ⇄ DEGRADED → DISCONNECTING → DISCONNECTED
                                                → FAILED
```

Transitions are guarded — an invalid one throws rather than silently corrupting
state — and are unit-tested in `SessionStateMachineTest`.

State is **polled and reconciled**, not merely inferred from broadcasts:
`requestP2pState`, `requestDiscoveryState`, `requestDeviceInfo`,
`requestConnectionInfo` and `requestGroupInfo` are all queried on hydration,
because broadcasts are missed often enough in practice that trusting them alone
produces a UI that disagrees with reality.

---

## 6. What runs over the session transport, and what does not

**Over it:** the `CONTROL` and `BULK` TCP channels. Everything framed as a
`ProtocolFrame`, and everything the v3 secure layer protects.

**Not over it:** the Audio Link. `AudioService` opens its own RTP/RTCP UDP
sockets. `ProtocolChannel.AUDIO` exists in the enum but has **no references
anywhere in the codebase**, and `ProtocolFrameType.AUDIO_FRAME` is never sent.

The practical consequence is a security one and is documented in
`ANDROID_LIMITATIONS.md` §5.1: audio has only the Wi-Fi Direct group's link-layer
WPA2 protection, not the app's authenticated encryption.

---

## 7. Robustness that already exists

| Mechanism | Value |
|---|---|
| Heartbeat interval | 5 s |
| Heartbeat timeout | 15 s → session marked `DEGRADED` |
| Connect attempts | 10, exponential backoff 1 s → 30 s |
| Handshake timeout | 10 s |
| Wi-Fi Direct connect timeout | 60 s |
| Transport setup deadline | 30 s |

Plus: monotonic `operationId` so stale framework callbacks are discarded,
generation counters so a superseded session cannot resurrect itself, channel
disconnect recovery (`handleChannelDisconnected`), and a `connectedDevice`
foreground service to keep the process alive.

---

## 8. Permissions

For API 33+, Wi-Fi Direct needs:

- `NEARBY_WIFI_DEVICES` with `android:usesPermissionFlags="neverForLocation"`
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
- `INTERNET` — for **local sockets only**; this app makes no outbound connections

`ACCESS_FINE_LOCATION` is deliberately **not** requested. With `neverForLocation`
declared and no location being derived from Wi-Fi scans, it is not required on API
33+, and asking for it would be an unnecessary privacy cost.

---

## 9. Things that vary by device

Do not treat any of these as guaranteed:

- **Concurrent Wi-Fi Direct and hotspot.** Many chipsets cannot hold a P2P group
  and a SoftAP at once. Some drop the group when the hotspot starts.
- **Concurrent Wi-Fi Direct and normal Wi-Fi.** Usually works; the P2P interface
  is separate. Not guaranteed on older or budget chipsets.
- **`getListenState`** is API 34 and is guarded accordingly.
- **Group owner negotiation.** Which device becomes owner is decided by the
  framework, not by this app. The code must work either way round, which is why
  the transport roles are derived rather than fixed.
- **Background execution.** OEM battery managers will kill the session. The
  compatibility checker reports this as `background.execution`.
