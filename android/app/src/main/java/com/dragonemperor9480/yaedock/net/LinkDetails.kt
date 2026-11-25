package com.dragonemperor9480.yaedock.net

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build

/**
 * What the phone's Wi-Fi is doing now: the band, the Wi-Fi generation, the
 * speed the phone and the router agreed, and the signal. Any of them can be
 * unknown. Read from Android without asking the user for anything: the
 * network's name would need location access, and is left out.
 */
data class WifiDetails(
    /** Megahertz, such as 5180. */
    val frequencyMhz: Int,
    /** One of [ScanResult]'s `WIFI_STANDARD_` values, on Android 11 and later. */
    val standard: Int?,
    val linkMbps: Int?,
    /** dBm, such as -55. */
    val rssi: Int?,
)

/** The phone's Wi-Fi connection now, or null when it is not on Wi-Fi. */
@Suppress("DEPRECATION")
fun wifiDetails(context: Context): WifiDetails? {
    val info = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
    }.getOrNull() ?: return null
    if (info.frequency <= 0) return null
    return WifiDetails(
        frequencyMhz = info.frequency,
        standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.wifiStandard else null,
        linkMbps = info.linkSpeed.takeIf { it > 0 },
        // Android reports this when it has no reading.
        rssi = info.rssi.takeIf { it in -126..-1 },
    )
}

/** `5 GHz`: the band a frequency is in. */
fun wifiBand(frequencyMhz: Int): String? = when (frequencyMhz) {
    in 2400..2500 -> "2.4 GHz"
    in 4900..5899 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    in 57000..71000 -> "60 GHz"
    else -> null
}

/** `Wi-Fi 6E`: the generation's everyday name, from a `WIFI_STANDARD_` value. */
fun wifiGeneration(standard: Int?, frequencyMhz: Int): String? = when (standard) {
    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
    // Wi-Fi 6 on the 6 GHz band has a name of its own.
    ScanResult.WIFI_STANDARD_11AX -> if (wifiBand(frequencyMhz) == "6 GHz") "Wi-Fi 6E" else "Wi-Fi 6"
    ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
    ScanResult.WIFI_STANDARD_11AD -> "WiGig"
    ScanResult.WIFI_STANDARD_LEGACY -> "older Wi-Fi"
    else -> null
}

/** `Good signal`, by the usual dBm steps. */
fun wifiSignal(rssi: Int): String = when {
    rssi >= -55 -> "Excellent signal"
    rssi >= -67 -> "Good signal"
    rssi >= -75 -> "Fair signal"
    else -> "Weak signal"
}

/** The Wi-Fi line for the PC menu: `5 GHz · Wi-Fi 6 · 866 Mbps · Good signal`. */
fun wifiSummary(wifi: WifiDetails): String = listOfNotNull(
    wifiBand(wifi.frequencyMhz),
    wifiGeneration(wifi.standard, wifi.frequencyMhz),
    wifi.linkMbps?.let { "$it Mbps" },
    wifi.rssi?.let(::wifiSignal),
).joinToString(" · ")

/** `USB 2.0 · 480 Mbps` or `USB 3 · 5 Gbps`: a cable's speed as the PC reports it. */
fun usbSpeed(mbps: Int): String = when {
    mbps <= 12 -> "USB 1.1 · $mbps Mbps"
    mbps < 5000 -> "USB 2.0 · $mbps Mbps"
    else -> "USB 3 · ${mbps / 1000} Gbps"
}
