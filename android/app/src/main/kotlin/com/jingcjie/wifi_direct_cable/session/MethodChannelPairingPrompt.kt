package com.jingcjie.wifi_direct_cable.session

import com.jingcjie.wifi_direct_cable.MainThreadDispatcher
import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.core.security.PairingCode
import com.jingcjie.wifi_direct_cable.core.security.PairingPrompt
import com.jingcjie.wifi_direct_cable.core.security.SecureSessionNegotiator
import com.jingcjie.wifi_direct_cable.diagnostics.DiagnosticsLogger
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges [PairingPrompt] to the Flutter UI over the method channel.
 *
 * The awkward part is the threading. The negotiation runs on a background
 * executor and is written as a straight-line blocking sequence, because the
 * pairing exchange is inherently ordered. But `MethodChannel.invokeMethod` must
 * be called on the main thread and answers asynchronously. So each prompt hops to
 * the main thread, then blocks the *calling* (background) thread on a latch until
 * Dart replies or the timeout expires.
 *
 * Blocking the main thread here would deadlock — the reply can only arrive on the
 * main thread — so [requestPairingCode] and [confirmShortAuthString] refuse to run
 * on it rather than hanging the app.
 *
 * Dart side (all on the `wifi_direct_cable` method channel):
 *
 * | Method | Argument | Expected reply |
 * |---|---|---|
 * | `onPairingCodeDisplay` | peer id/name, `code` | none (fire and forget) |
 * | `onPairingCodeRequired` | peer id/name | the typed code as a `String`, or null to cancel |
 * | `onPairingVerify` | peer id, `sas` | `true` if the user confirmed both screens match |
 * | `onPairingFinished` | `paired` | none (dismiss any pairing dialog) |
 */
class MethodChannelPairingPrompt(
    private val methodChannel: MethodChannel,
    private val dispatcher: MainThreadDispatcher = MainThreadDispatcher(),
    private val isMainThread: () -> Boolean = {
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : PairingPrompt {

    override fun displayPairingCode(peerDeviceId: DeviceId, peerName: String): PairingCode {
        val code = PairingCode.random()
        // Fire and forget: the gateway just shows the code. The dialog is replaced
        // by the verification prompt once the exchange completes, so there is
        // nothing to wait for here.
        dispatcher.dispatch {
            methodChannel.invokeMethod(
                "onPairingCodeDisplay",
                mapOf(
                    "peerDeviceId" to peerDeviceId.toString(),
                    "peerName" to peerName,
                    "code" to code.digits
                )
            )
        }
        DiagnosticsLogger.log(
            "security",
            "Displaying pairing code",
            mapOf("peerDeviceId" to peerDeviceId.shortLabel())
        )
        // Never log the code itself.
        return code
    }

    override fun requestPairingCode(peerDeviceId: DeviceId, peerName: String): PairingCode? {
        val reply = await(
            "onPairingCodeRequired",
            mapOf(
                "peerDeviceId" to peerDeviceId.toString(),
                "peerName" to peerName
            )
        ) ?: return null

        val code = PairingCode.parseOrNull(reply as? String)
        DiagnosticsLogger.log(
            "security",
            if (code == null) "Pairing code entry cancelled or invalid" else "Pairing code entered",
            mapOf("peerDeviceId" to peerDeviceId.shortLabel())
        )
        return code
    }

    override fun confirmShortAuthString(peerDeviceId: DeviceId, sas: String): Boolean {
        val reply = await(
            "onPairingVerify",
            mapOf(
                "peerDeviceId" to peerDeviceId.toString(),
                "shortAuthString" to sas
            )
        )
        val confirmed = reply == true
        DiagnosticsLogger.log(
            "security",
            if (confirmed) "Short auth string confirmed by user" else "Short auth string not confirmed",
            mapOf("peerDeviceId" to peerDeviceId.shortLabel())
        )
        return confirmed
    }

    /** Tells the UI to dismiss any pairing dialog. Safe to call from any thread. */
    override fun onPairingFinished(paired: Boolean) {
        dispatcher.dispatch {
            methodChannel.invokeMethod("onPairingFinished", mapOf("paired" to paired))
        }
    }

    /**
     * Invokes [method] on the main thread and blocks the caller until Dart
     * answers, the call fails, or [timeoutMs] elapses.
     *
     * @return the reply, or null on cancel/error/timeout. A null is always
     *         treated by the caller as "do not pair", never as "proceed anyway".
     */
    private fun await(method: String, arguments: Map<String, Any?>): Any? {
        check(!isMainThread()) {
            "$method would deadlock: the reply can only be delivered on the main thread"
        }

        val latch = CountDownLatch(1)
        val holder = AtomicReference<Any?>(null)

        dispatcher.dispatch {
            methodChannel.invokeMethod(
                method,
                arguments,
                object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        holder.set(result)
                        latch.countDown()
                    }

                    override fun error(code: String, message: String?, details: Any?) {
                        DiagnosticsLogger.log(
                            "security",
                            "Pairing prompt returned an error",
                            mapOf("method" to method, "code" to code)
                        )
                        latch.countDown()
                    }

                    override fun notImplemented() {
                        DiagnosticsLogger.log(
                            "security",
                            "Pairing prompt is not implemented on the Flutter side",
                            mapOf("method" to method)
                        )
                        latch.countDown()
                    }
                }
            )
        }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            DiagnosticsLogger.log(
                "security",
                "Pairing prompt timed out",
                mapOf("method" to method, "timeoutMs" to timeoutMs)
            )
            return null
        }
        return holder.get()
    }

    companion object {
        /**
         * Long enough for a person to read a code off one phone and type it into
         * another.
         *
         * Taken from [SecureSessionNegotiator.PROMPT_TIMEOUT_MS] rather than
         * redefined, because the negotiator's socket read timeout has to outwait
         * this value. Two independent constants drifted apart once already and
         * produced a pairing race — see that field's comment.
         */
        const val DEFAULT_TIMEOUT_MS = SecureSessionNegotiator.PROMPT_TIMEOUT_MS
    }
}
