package com.dragonemperor9480.yaedock.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** The time now: [period] is AM or PM on a 12-hour phone, and [hour] always counts from 0 to 23. */
data class Clock(val time: String, val date: String, val period: String?, val hour: Int)

/** The time and date, updated as each minute starts. Follows the phone's 12/24-hour setting. */
@Composable
fun rememberClock(): Clock {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Wake just after the minute changes.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = LocalDateTime.now()
        }
    }
    val twentyFourHour = DateFormat.is24HourFormat(context)
    return Clock(
        now.format(DateTimeFormatter.ofPattern(if (twentyFourHour) "HH:mm" else "h:mm")),
        now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
        if (twentyFourHour) null else now.format(DateTimeFormatter.ofPattern("a")).uppercase(),
        now.hour,
    )
}

/** `Good evening, Shura`, or without the name when it is empty. */
fun greeting(hour: Int, name: String): String {
    val part = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    return if (name.isBlank()) part else "$part, ${name.trim()}"
}
