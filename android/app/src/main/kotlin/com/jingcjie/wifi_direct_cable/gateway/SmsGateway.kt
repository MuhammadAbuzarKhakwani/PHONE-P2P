package com.jingcjie.wifi_direct_cable.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jingcjie.wifi_direct_cable.diagnostics.DiagnosticsLogger

/**
 * SMS on the gateway device.
 *
 * ## What actually works, and where
 *
 * - **Sending** needs `SEND_SMS`. Straightforward once granted.
 * - **Reading** needs `READ_SMS` *and*, in practice, for this app to be the
 *   device's **default SMS handler**. Google Play restricts the SMS permission
 *   group to the default handler, so a Play-distributed build that is not the
 *   default handler will never be able to read the inbox. A sideloaded or
 *   internally-distributed build works once the user grants the permission.
 *
 * That split is not papered over: [GatewayCapabilities] advertises `sms.send` and
 * `sms.read` independently, and the client shows the gateway's own reason when
 * one is missing. See `docs/ANDROID_LIMITATIONS.md` §4.
 *
 * **Phone 2 never sends SMS.** It asks the gateway to send, and the gateway is the
 * actual cellular sender. Nothing here gives the client a phone number of its own.
 */
class SmsGateway(private val context: Context) : SmsGatewayApi {

    private val appContext = context.applicationContext

    /**
     * Sends an SMS on behalf of the remote client.
     *
     * Long bodies are split with [SmsManager.divideMessage] and sent as a
     * multipart message, which is what the platform expects; sending an
     * over-length body as a single message silently truncates it on some carriers.
     */
    override fun send(rawAddress: String?, body: String?): SmsSendResult {
        if (!isGranted(Manifest.permission.SEND_SMS)) {
            return SmsSendResult.Failed(GatewayCapabilities.REASON_PERMISSION)
        }

        val validation = SmsRequestValidator.validate(rawAddress, body)
        if (validation is SmsRequestValidator.Result.Invalid) {
            DiagnosticsLogger.log(
                "sms",
                "Rejected SMS request",
                mapOf("reason" to validation.reason)
            )
            return SmsSendResult.Failed(validation.reason)
        }

        val valid = validation as SmsRequestValidator.Result.Valid
        val manager = appContext.getSystemService(SmsManager::class.java)
            ?: return SmsSendResult.Failed(REASON_NO_SMS_MANAGER)

        return try {
            val segments = manager.divideMessage(valid.body)
            if (segments.size > 1) {
                manager.sendMultipartTextMessage(valid.address, null, segments, null, null)
            } else {
                manager.sendTextMessage(valid.address, null, valid.body, null, null)
            }
            DiagnosticsLogger.log(
                "sms",
                "Sent SMS",
                mapOf(
                    // Never log the recipient in full, or the body at all.
                    "to" to PhoneNumberValidator.mask(valid.address),
                    "segments" to segments.size
                )
            )
            SmsSendResult.Sent(segments.size)
        } catch (exception: SecurityException) {
            SmsSendResult.Failed(GatewayCapabilities.REASON_PERMISSION)
        } catch (exception: Exception) {
            DiagnosticsLogger.log(
                "sms",
                "SMS send failed",
                mapOf("errorType" to exception.javaClass.simpleName)
            )
            SmsSendResult.Failed(REASON_SEND_FAILED)
        }
    }

    /**
     * Reads recent messages from the SMS provider.
     *
     * Returns an empty list rather than throwing when the permission or the
     * default-handler role is missing; the capability advertisement is what tells
     * the client the feature is unavailable, so this does not need to fail loudly
     * as well.
     */
    override fun readRecent(limit: Int): List<SmsMessageSummary> {
        if (!isGranted(Manifest.permission.READ_SMS)) return emptyList()

        // Referenced through their declaring interfaces on purpose.
        // Telephony.Sms gets these columns from TextBasedSmsColumns and
        // BaseColumns, and Kotlin does NOT inherit Java interface constants into
        // an implementing class's static scope the way Java does — so
        // `Telephony.Sms.ADDRESS` compiles in Java but not in Kotlin.
        val projection = arrayOf(
            BaseColumns._ID,
            Telephony.TextBasedSmsColumns.THREAD_ID,
            Telephony.TextBasedSmsColumns.ADDRESS,
            Telephony.TextBasedSmsColumns.BODY,
            Telephony.TextBasedSmsColumns.DATE,
            Telephony.TextBasedSmsColumns.TYPE,
            Telephony.TextBasedSmsColumns.READ
        )

        return try {
            appContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.TextBasedSmsColumns.DATE} DESC LIMIT ${limit.coerceIn(1, MAX_LIMIT)}"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val threadColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.THREAD_ID)
                val addressColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.ADDRESS)
                val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.BODY)
                val dateColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE)
                val typeColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.TYPE)
                val readColumn = cursor.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.READ)

                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SmsMessageSummary(
                                id = cursor.getLong(idColumn),
                                threadId = cursor.getLong(threadColumn),
                                address = cursor.getString(addressColumn).orEmpty(),
                                body = cursor.getString(bodyColumn).orEmpty(),
                                timestampMs = cursor.getLong(dateColumn),
                                incoming = cursor.getInt(typeColumn) == Telephony.TextBasedSmsColumns.MESSAGE_TYPE_INBOX,
                                read = cursor.getInt(readColumn) != 0
                            )
                        )
                    }
                }
            } ?: emptyList()
        } catch (exception: SecurityException) {
            emptyList()
        } catch (exception: Exception) {
            DiagnosticsLogger.log(
                "sms",
                "SMS read failed",
                mapOf("errorType" to exception.javaClass.simpleName)
            )
            emptyList()
        }
    }

    override fun readConversations(limit: Int): List<SmsConversation> =
        SmsConversation.group(readRecent(limit))

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 1000
        const val REASON_NO_SMS_MANAGER = "sms_manager_unavailable"
        const val REASON_SEND_FAILED = "sms_send_failed"
    }
}
