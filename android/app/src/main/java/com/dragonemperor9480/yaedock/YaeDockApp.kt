package com.dragonemperor9480.yaedock

import android.app.Application
import com.dragonemperor9480.yaedock.pairing.PcStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class YaeDockApp : Application() {
    /** Lives as long as the process, so connections survive screen rotation. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store by lazy { PcStore(filesDir) }
    val connections by lazy { ConnectionManager(store, scope) }
}
