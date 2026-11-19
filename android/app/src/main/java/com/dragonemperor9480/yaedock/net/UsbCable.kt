package com.dragonemperor9480.yaedock.net

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
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

    class Plugged(val accessory: UsbAccessory, val fingerprint: String)

    private val _plugged = MutableStateFlow<Plugged?>(null)
    val plugged: StateFlow<Plugged?> = _plugged.asStateFlow()

    /** Notes [accessory], which Android just handed the app, if it is a YaeDock PC this app may use. */
    fun attached(context: Context, accessory: UsbAccessory) {
        if (accessory.manufacturer != MANUFACTURER) return
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        if (!manager.hasPermission(accessory)) return
        val fingerprint = accessory.serial?.let(::normalizeFingerprint)?.takeIf { it.isNotEmpty() } ?: return
        _plugged.value = Plugged(accessory, fingerprint)
    }

    /** Looks for a YaeDock PC already attached, as when the app starts while plugged in. */
    fun look(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        manager.accessoryList?.forEach { attached(context, it) }
    }

    fun detached() {
        _plugged.value = null
    }

    /** The cable to the PC with [fingerprint], if this phone is plugged into it. */
    fun transportFor(context: Context, fingerprint: String): Transport? {
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
