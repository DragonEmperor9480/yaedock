package com.dragonemperor9480.yaedock.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.IntentCompat
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import com.dragonemperor9480.yaedock.pairing.PairingInfo
import com.dragonemperor9480.yaedock.pairing.PairingUri
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The PC this phone is plugged into by USB cable, if it is one running
 * YaeDock: the PC switched the phone into Android Open Accessory mode and
 * named itself by its certificate's fingerprint, in the accessory's serial.
 */
object UsbCable {
    /** What the PC calls itself as an accessory. */
    const val MANUFACTURER = "YaeDock"

    /**
     * A YaeDock PC on the cable. [pairing] is set while `yaedock pair` waits
     * on it: the PC then hands over the same link as its QR code.
     */
    class Plugged(val accessory: UsbAccessory, val fingerprint: String, val pairing: PairingInfo? = null)

    private val _plugged = MutableStateFlow<Plugged?>(null)
    val plugged: StateFlow<Plugged?> = _plugged.asStateFlow()

    /** Notes [accessory], which Android just handed the app, if it is a YaeDock PC this app may use. */
    fun attached(context: Context, accessory: UsbAccessory) {
        if (accessory.manufacturer != MANUFACTURER) return
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        if (!manager.hasPermission(accessory)) return
        val fingerprint = accessory.serial?.let(::normalizeFingerprint)?.takeIf { it.isNotEmpty() } ?: return
        unanswered = false
        _plugged.value = Plugged(accessory, fingerprint, pairingIn(accessory.version, fingerprint))
    }

    /**
     * The pairing link in an accessory's version string, `<protocol> <link>`,
     * if there is one and it names the same PC as the serial.
     */
    fun pairingIn(version: String?, fingerprint: String): PairingInfo? {
        val link = version?.substringAfter(' ', "")?.takeIf { it.isNotEmpty() } ?: return null
        return PairingUri.parse(link)?.takeIf { it.fingerprint == fingerprint }
    }

    /** Where Android answers [look]'s request to use the cable. */
    const val ACTION_PERMISSION = "com.dragonemperor9480.yaedock.USB_PERMISSION"

    /**
     * The accessory the app last asked about, so it asks once, not at every
     * look. Kept as [askKey], since comparing accessories reads their serial,
     * which Android refuses (and throws) before the app has permission.
     */
    private var asked: String? = null

    /** An accessory's fields that can be read without permission. */
    private fun askKey(accessory: UsbAccessory) =
        listOf(accessory.manufacturer, accessory.model, accessory.description, accessory.version, accessory.uri).joinToString("\u0000")

    /**
     * Looks for a YaeDock PC already attached, as when the app starts while
     * plugged in. Android forgets the app's permission when the app is
     * updated, so the app asks for it again then.
     */
    fun look(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        manager.accessoryList?.forEach { accessory ->
            if (accessory.manufacturer != MANUFACTURER) return@forEach
            if (manager.hasPermission(accessory)) {
                attached(context, accessory)
            } else if (askKey(accessory) != asked) {
                asked = askKey(accessory)
                val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
                // Mutable: Android adds the answer to it.
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                manager.requestPermission(accessory, PendingIntent.getBroadcast(context, 0, intent, flags))
            }
        }
    }

    /** Android's answer to [look]'s request. */
    fun answered(context: Context, intent: Intent) {
        val accessory = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java) ?: return
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) attached(context, accessory)
    }

    /** Whether the last link over the cable got no answer; the next one goes over Wi-Fi. */
    @Volatile private var unanswered = false

    /**
     * The PC did not answer over the cable, as when its YaeDock restarted
     * while the phone stayed plugged in: the next link tries Wi-Fi instead.
     */
    fun unanswered() {
        unanswered = true
    }

    fun detached() {
        unanswered = false
        asked = null
        _plugged.value = null
    }

    /** The cable to the PC with [fingerprint], if this phone is plugged into it. */
    fun transportFor(context: Context, fingerprint: String): Transport? {
        if (unanswered) {
            unanswered = false
            return null
        }
        val plugged = _plugged.value?.takeIf { it.fingerprint == normalizeFingerprint(fingerprint) } ?: return null
        val manager = context.getSystemService(UsbManager::class.java) ?: return null
        return UsbTransport(manager, plugged.accessory)
    }
}

/** The PC over the USB cable. */
class UsbTransport(private val manager: UsbManager, private val accessory: UsbAccessory) : Transport {
    override fun open(events: LinkEvents): Link {
        val file = try {
            manager.openAccessory(accessory)
        } catch (e: Exception) {
            null
        }
        if (file == null) {
            // Unplugged, or held by another app: the next attempt goes over Wi-Fi.
            UsbCable.detached()
            Thread { events.onClosed("could not open the USB cable") }.start()
            return object : Link {
                override val via = Via.CABLE
                override fun sendText(text: String) = false
                override fun sendBinary(bytes: ByteArray) = false
                override fun queued() = 0L
                override fun close() {}
                override fun cancel() {}
            }
        }
        val descriptor = file.fileDescriptor
        return StreamLink(FileInputStream(descriptor), FileOutputStream(descriptor), events, Via.CABLE) {
            file.close()
        }
    }
}
