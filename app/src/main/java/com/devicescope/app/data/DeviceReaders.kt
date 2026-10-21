package com.devicescope.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.telephony.TelephonyManager
import kotlinx.coroutines.CancellationException
import java.io.File

/** Outcome of a single on-device read. */
sealed interface DeviceReading<out T> {
    data class Available<T>(val value: T) : DeviceReading<T>
    data object NotAvailable : DeviceReading<Nothing>
    data class Failed(val error: Throwable) : DeviceReading<Nothing>
}

val <T> DeviceReading<T>.valueOrNull: T?
    get() = when (this) {
        is DeviceReading.Available -> value
        DeviceReading.NotAvailable -> null
        is DeviceReading.Failed -> null
    }

private val DeviceReading<*>.isSecurityFailure: Boolean
    get() = this is DeviceReading.Failed && error is SecurityException

private inline fun <T : Any> readDeviceValue(block: () -> T?): DeviceReading<T> = try {
    block()?.let { DeviceReading.Available(it) } ?: DeviceReading.NotAvailable
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    DeviceReading.Failed(error)
}

private inline fun <T, R> DeviceReading<T>.flatMap(
    transform: (T) -> DeviceReading<R>,
): DeviceReading<R> = when (this) {
    is DeviceReading.Available -> transform(value)
    DeviceReading.NotAvailable -> DeviceReading.NotAvailable
    is DeviceReading.Failed -> this
}

data class BatterySnapshot(
    val voltageMv: DeviceReading<Int>,
    val temperatureTenthsC: DeviceReading<Int>,
    val currentUa: DeviceReading<Long>,
    val pluggedSource: DeviceReading<Int>,
    val batteryStatus: DeviceReading<Int>,
)

object BatteryDiagnosticReader {
    fun read(context: Context): DeviceReading<BatterySnapshot> = readDeviceValue {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return@readDeviceValue null

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        BatterySnapshot(
            voltageMv = readDeviceValue {
                batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }
            },
            temperatureTenthsC = readDeviceValue {
                batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1).takeIf { it >= 0 }
            },
            currentUa = readDeviceValue {
                batteryManager
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    ?.takeUnless { it == Int.MIN_VALUE }
                    ?.toLong()
            },
            pluggedSource = readDeviceValue {
                batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            },
            batteryStatus = readDeviceValue {
                batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            },
        )
    }
}

object UsbDiagnosticReader {
    fun readDeviceCount(context: Context): DeviceReading<Int> = readDeviceValue {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return@readDeviceValue null
        usbManager.getDeviceList().size
    }
}

data class RemovableVolume(
    val id: String,
    val label: String,
    val state: String,
    val totalBytes: Long?,
    val availableBytes: Long?,
    val appDirectory: File?,
    val writable: Boolean,
)

object StorageDiagnosticReader {
    fun readRemovableVolumes(context: Context): DeviceReading<List<RemovableVolume>> = readDeviceValue {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return@readDeviceValue null
        val externalFilesDirs = readDeviceValue { context.getExternalFilesDirs(null) }
            .valueOrNull
            .orEmpty()

        storageManager.getStorageVolumes()
            .filter { readDeviceValue { it.isRemovable }.valueOrNull == true }
            .mapIndexedNotNull { index, volume ->
                try {
                    readVolume(context, volume, externalFilesDirs, index)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
    }

    private fun readVolume(
        context: Context,
        volume: StorageVolume,
        externalFilesDirs: Array<out File?>,
        index: Int,
    ): RemovableVolume {
        val description = readDeviceValue { volume.getDescription(context) }.valueOrNull.orEmpty()
        val label = description.ifBlank { "Almacenamiento extraíble ${index + 1}" }
        val state = readDeviceValue { volume.state }.valueOrNull.orEmpty()
        val appDirectory = findAppDirectory(volume, externalFilesDirs, index)
        val mounted = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
        if (mounted && appDirectory != null) {
            val missing = readDeviceValue { !appDirectory.exists() }.valueOrNull == true
            if (missing) {
                readDeviceValue { appDirectory.mkdirs() }
            }
        }
        val statPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readDeviceValue { volume.directory?.absolutePath }.valueOrNull
        } else {
            null
        } ?: appDirectory?.absolutePath
        val stat = if (mounted && statPath != null) {
            readDeviceValue { StatFs(statPath) }.valueOrNull
        } else {
            null
        }
        val uuid = readDeviceValue { volume.uuid }.valueOrNull?.takeIf(String::isNotBlank)
        val writable = mounted &&
            state == Environment.MEDIA_MOUNTED &&
            readDeviceValue { appDirectory?.canWrite() == true }.valueOrNull == true

        return RemovableVolume(
            id = uuid ?: "${label}_$index",
            label = label,
            state = state,
            totalBytes = stat?.totalBytes,
            availableBytes = stat?.availableBytes,
            appDirectory = appDirectory,
            writable = writable,
        )
    }

    private fun findAppDirectory(
        volume: StorageVolume,
        externalFilesDirs: Array<out File?>,
        removableIndex: Int,
    ): File? {
        val directories = externalFilesDirs.filterNotNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val rootPath = readDeviceValue { volume.directory?.canonicalPath }.valueOrNull
            val pathMatch = directories.firstOrNull { directory ->
                rootPath != null &&
                    readDeviceValue {
                        directory.canonicalPath.startsWith(rootPath)
                    }.valueOrNull == true
            }
            if (pathMatch != null) return pathMatch
        }
        val uuid = readDeviceValue { volume.uuid }.valueOrNull?.takeIf(String::isNotBlank)
        if (uuid != null) {
            directories.firstOrNull { it.absolutePath.contains(uuid, ignoreCase = true) }?.let { return it }
        }
        return externalFilesDirs.getOrNull(removableIndex + 1)
    }
}

data class SimSnapshot(
    val supported: Boolean,
    val simState: DeviceReading<Int>,
    val operatorName: DeviceReading<String>,
    val countryIso: DeviceReading<String>,
    val signalLevel: DeviceReading<Int>,
    val signalDbm: DeviceReading<Int>,
) {
    val permissionError: Boolean
        get() = operatorName.isSecurityFailure ||
            countryIso.isSecurityFailure ||
            signalLevel.isSecurityFailure ||
            signalDbm.isSecurityFailure
}

object TelephonyDiagnosticReader {
    fun read(context: Context, hasPhonePermission: Boolean): DeviceReading<SimSnapshot> = readDeviceValue {
        val hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (!hasTelephony || telephonyManager == null) {
            return@readDeviceValue SimSnapshot(
                supported = false,
                simState = DeviceReading.NotAvailable,
                operatorName = DeviceReading.NotAvailable,
                countryIso = DeviceReading.NotAvailable,
                signalLevel = DeviceReading.NotAvailable,
                signalDbm = DeviceReading.NotAvailable,
            )
        }

        val simState = readDeviceValue { telephonyManager.getSimState() }
        if (!hasPhonePermission) {
            return@readDeviceValue SimSnapshot(
                supported = true,
                simState = simState,
                operatorName = DeviceReading.NotAvailable,
                countryIso = DeviceReading.NotAvailable,
                signalLevel = DeviceReading.NotAvailable,
                signalDbm = DeviceReading.NotAvailable,
            )
        }

        val signal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            readDeviceValue { telephonyManager.getSignalStrength() }
        } else {
            DeviceReading.NotAvailable
        }

        SimSnapshot(
            supported = true,
            simState = simState,
            operatorName = readDeviceValue {
                telephonyManager.getSimOperatorName().takeIf(String::isNotBlank)
            },
            countryIso = readDeviceValue {
                telephonyManager.getSimCountryIso().takeIf(String::isNotBlank)
            },
            signalLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signal.flatMap { strength ->
                    readDeviceValue { strength.level.takeIf { it in 0..4 } }
                }
            } else {
                DeviceReading.NotAvailable
            },
            signalDbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                signal.flatMap { strength ->
                    readDeviceValue {
                        strength.cellSignalStrengths
                            .map { it.dbm }
                            .firstOrNull { it in -150..-1 }
                    }
                }
            } else {
                DeviceReading.NotAvailable
            },
        )
    }
}
