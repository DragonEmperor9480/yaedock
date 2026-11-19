package com.dragonemperor9480.yaedock

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.dragonemperor9480.yaedock.net.UsbCable
import com.dragonemperor9480.yaedock.clock.ClockStore
import com.dragonemperor9480.yaedock.pairing.PcStore
import com.dragonemperor9480.yaedock.ui.ClockFonts
import com.dragonemperor9480.yaedock.ui.ThemeCatalog
import com.dragonemperor9480.yaedock.ui.clockDrawError
import com.dragonemperor9480.yaedock.update.AppUpdates
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class YaeDockApp : Application() {
    /** Lives as long as the process, so connections survive screen rotation. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store by lazy { PcStore(filesDir) }
    val settings by lazy { AppSettings(this) }
    val connections by lazy { ConnectionManager(this, store, scope) { settings.useCable.value } }

    val updates by lazy { AppUpdates(this, settings, scope) }

    /** The themes shipped in assets/themes, and the ones the user imported. */
    val themes by lazy { ThemeStore(ThemeCatalog.load(assets), File(filesDir, "themes")) }

    /** The clocks shipped in assets/clocks, in file order, and the ones the user imported. */
    val clocks by lazy {
        val builtIn = assets.list("clocks").orEmpty().sorted().map { name ->
            ClockStore.builtIn(assets.open("clocks/$name").bufferedReader().use { it.readText() })
        }
        ClockStore(builtIn, File(filesDir, "clocks"), ::clockDrawError)
    }

    override fun onCreate() {
        super.onCreate()
        ClockFonts.install(this)
        // Android says when the cable to a PC is unplugged only by broadcast.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = UsbCable.detached()
            },
            IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
