# QUICKSTART

Get an APK onto two phones and make a call from the phone without a SIM.

> **Read this first.** This code has never been compiled. The first build may
> fail with compiler errors — that is expected, and both routes below are set up
> to show you exactly what they are. See "If the build fails" at the end.

---

## What you need

- **Two Android 13+ phones**, 64-bit (essentially all modern phones).
- **Phone 1** — has the SIM. This is the *gateway*.
- **Phone 2** — SIM removed, or a device with no SIM. This is the *client*.

Wi-Fi Direct cannot be tested on emulators. Two real handsets, or nothing.

---

## Getting an APK

### Route A — GitHub, nothing to install (easiest)

1. Push this repository to GitHub.
2. **Actions** → **Debug APK** → **Run workflow**.
3. Wait ~10 minutes.
4. Download the **khakwani-p2p-debug-apk** artifact from the run.
5. Unzip, copy `app-debug.apk` to both phones, tap to install
   (allow "install from unknown sources").

No signing keys, no secrets, no local toolchain. The run summary shows a table of
what passed and what did not.

### Route B — build locally

Needs Flutter 3.44.0, a **JDK 17+** (a JRE will not do), and the Android SDK.

**Windows**

```powershell
.\tools\build_apk.ps1 -Install
```

**Linux / macOS**

```bash
./tools/build_apk.sh --install
```

The script checks the toolchain, writes `android/local.properties`, runs the
offline checks, analyses, tests, builds, and installs on every attached device.
Drop `-Install` / `--install` to build only.

---

## First run

### On both phones

Open the app and grant **Nearby devices**. Then open the **Diagnostics** tab
(the heart-rate icon) and check the verdict at the top:

| Verdict | Meaning |
|---|---|
| **Supported** | good to go |
| **Partially supported** | works, but read the reasons — usually Wi-Fi off or a permission |
| **Not supported** | stop. The reason says why (no Wi-Fi Direct, Android too old, 32-bit device) |

### On Phone 1 (the SIM)

Grant **Phone** as well, and **SMS** if you want messaging. Leave the app open on
the **Connection** tab so Phone 2 can find it.

### On Phone 2 (no SIM)

1. **Connection** tab → scan.
2. Tap Phone 1 when it appears (~15 s).
3. Accept the Wi-Fi Direct invitation **on Phone 1**.
4. Phone 1 shows a **6-digit pairing code**. Type it into Phone 2.
5. Both phones now show a **6-digit confirmation number**.

**Compare the two confirmation numbers.**

- **Same** → tap *They match* on both. Done, and you never type a code again.
- **Different** → tap *They differ* and stop. Someone is relaying your
  connection. This check is the real protection; the typed code alone is not
  (`docs/ANDROID_LIMITATIONS.md` §5).

---

## Making a call

On Phone 2: **Dialer** tab → enter a number → **Call**.

Phone 1 dials. Phone 2 shows *Dialing* → *In call* with a timer.

> **The conversation is on Phone 1**, on its speaker and microphone. Phone 2
> controls the call; it does not carry the audio. This is a hard Android limit,
> not a missing feature — no supported API gives an ordinary app the cellular
> audio stream. The notice on the Dialer says so, permanently.
>
> The timer says **"Since dialing"** because Android never tells an ordinary app
> when the other person picks up.

If the Call button is greyed out, the text under it says why — it is the
gateway's own reason (no SIM, permission not granted, and so on).

---

## Messaging

Phone 2: **Messages** tab. Type a recipient and a message, send.

Phone 1 sends it, from Phone 1's number. The result says *"Handed to the
network"*, never "delivered" — no delivery receipt is registered, so delivery is
genuinely unknown.

The conversation list will usually be empty with an explanation: Android only
lets the phone's **default messaging app** read SMS. That is expected.

---

## If something is wrong

Open **Diagnostics** on either phone.

| What you see | What it means |
|---|---|
| *Not encrypted* under Security | pairing did not complete; telephony is correctly refused |
| *Paired, but not verified* | you skipped the confirmation-number comparison |
| *Pairing desynchronised* | the two phones' stored pairings diverged → **Forget this device**, then pair again |
| Call button greyed out | read the reason beneath it |
| Everything greyed, no gateway | not connected, or Phone 1 has no SIM |

**Forget this device** lives in Diagnostics → Security. It is also how you revoke
a phone you no longer trust.

---

## Working with no internet at all

Put **both** phones in aeroplane mode, then switch Wi-Fi back on.

Pairing, chat, file transfer and the speed test all still work. That is the point
of the project: the link is direct, with no cloud, no account, and no server.

Telephony correctly reports unavailable, because Phone 1 has no cellular in
aeroplane mode.

---

## If the build fails

Expected on a first run. Both routes surface the errors:

- **Route A** — open the failing step in the Actions log.
- **Route B** — the compiler errors print in your terminal.

Fastest triage:

```bash
python tools/static_checks/run_all.py   # seconds, no toolchain needed
flutter analyze                          # the least-verified layer
cd android && ./gradlew testDebugUnitTest
```

`docs/TEST_PLAN.md` has the full sequence plus 21 numbered two-phone tests.

---

## Things that will never work

Stated so you do not spend time hunting them:

- **Remote cellular call audio.** Not possible for an ordinary Android app.
- **Encrypted audio.** The Audio Link is RTP over UDP, protected only by
  Wi-Fi Direct's own WPA2 (`ANDROID_LIMITATIONS.md` §5.1).
- **Reading SMS**, unless the app is the default messaging app.
- **Turning tethering on.** No public API exists; the app points you at Settings.
- **A virtual SIM.** Phone 2 never gets cellular identity. Every cellular action
  happens on Phone 1.
