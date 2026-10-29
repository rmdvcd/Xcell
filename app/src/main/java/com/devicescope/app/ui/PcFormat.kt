package com.devicescope.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formateo compartido por las pantallas de PC-Móvil. */
object PcFormat {

    fun bytes(value: Long): String = when {
        value >= 1_099_511_627_776L -> String.format(Locale.getDefault(), "%.2f TB", value / 1_099_511_627_776.0)
        value >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.1f GB", value / 1_073_741_824.0)
        value >= 1_048_576L -> String.format(Locale.getDefault(), "%.0f MB", value / 1_048_576.0)
        value >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", value / 1024.0)
        else -> "$value B"
    }

    fun rate(bytesPerSecond: Double): String =
        if (bytesPerSecond <= 0.0) "0 KB/s" else bytesPerSecond.let { "${bytes(it.toLong())}/s" }

    fun percent(value: Double): String = String.format(Locale.getDefault(), "%.0f%%", value)

    fun temperature(celsius: Double?): String =
        celsius?.let { String.format(Locale.getDefault(), "%.0f °C", it) } ?: "—"

    fun uptime(seconds: Long): String {
        if (seconds <= 0) return "—"
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

    fun dateTime(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    fun megahertz(value: Double): String =
        if (value <= 0) "—" else String.format(Locale.getDefault(), "%.2f GHz", value / 1000.0)

    fun distance(meters: Double?): String = when {
        meters == null || meters <= 0.0 -> "—"
        meters < 1.0 -> "< 1 m"
        meters < 10.0 -> String.format(Locale.getDefault(), "≈ %.1f m", meters)
        else -> String.format(Locale.getDefault(), "≈ %.0f m", meters)
    }
}
