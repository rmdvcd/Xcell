package com.devicescope.app.data

import android.app.ActivityManager
import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.Formatter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val MAX_APK_SIZE_BYTES = 512L * 1024L * 1024L
private const val FOUR_GIB_BYTES = 4L * 1024L * 1024L * 1024L
private const val GLES_32 = 0x00030002

/** A required manifest feature and the result of checking it against this device. */
data class ApkFeatureCheck(
    val label: String,
    val featureName: String?,
    val minimumVersion: Int?,
    val requiredGlEsVersion: Int?,
    val supported: Boolean?,
)

enum class ApkCompatibilityVerdict(val key: String, val label: String) {
    COMPATIBLE("compatible", "✅ Compatible"),
    COMPATIBLE_WITH_LIMITATIONS("compatible_with_limitations", "⚠️ Compatible con limitaciones"),
    NOT_COMPATIBLE("not_compatible", "❌ No compatible"),
}

data class ApkCompatibilityReport(
    val apkDisplayName: String,
    val packageName: String,
    val versionName: String?,
    val minimumSdkVersion: Int,
    val targetSdkVersion: Int?,
    val deviceApiLevel: Int,
    val deviceAndroidVersion: String,
    val deviceTotalRamBytes: Long?,
    val deviceGlEsVersion: Int?,
    val requiredFeatures: List<ApkFeatureCheck>,
    val blockers: List<String>,
    val limitations: List<String>,
    val verdict: ApkCompatibilityVerdict,
    val analyzedAtMillis: Long,
) {
    fun toJson(): String = JSONObject()
        .put("schemaVersion", 1)
        .put("verdict", verdict.key)
        .put("verdictLabel", verdict.label)
        .put("analyzedAtMillis", analyzedAtMillis)
        .put(
            "apk",
            JSONObject()
                .put("displayName", apkDisplayName)
                .put("packageName", packageName)
                .put("versionName", versionName ?: JSONObject.NULL)
                .put("minSdkVersion", minimumSdkVersion)
                .put("targetSdkVersion", targetSdkVersion ?: JSONObject.NULL),
        )
        .put(
            "device",
            JSONObject()
                .put("apiLevel", deviceApiLevel)
                .put("androidVersion", deviceAndroidVersion)
                .put("totalRamBytes", deviceTotalRamBytes ?: JSONObject.NULL)
                .put("ramHeuristicThresholdBytes", FOUR_GIB_BYTES)
                .put("openGlEsVersion", deviceGlEsVersion ?: JSONObject.NULL)
                .put("openGlEsVersionLabel", deviceGlEsVersion?.let(::formatGlEsVersion) ?: JSONObject.NULL),
        )
        .put(
            "requiredFeatures",
            JSONArray().apply {
                requiredFeatures.forEach { feature ->
                    put(
                        JSONObject()
                            .put("label", feature.label)
                            .put("featureName", feature.featureName ?: JSONObject.NULL)
                            .put("minimumVersion", feature.minimumVersion ?: JSONObject.NULL)
                            .put("requiredGlEsVersion", feature.requiredGlEsVersion ?: JSONObject.NULL)
                            .put("requiredGlEsVersionLabel", feature.requiredGlEsVersion?.let(::formatGlEsVersion) ?: JSONObject.NULL)
                            .put("supported", feature.supported ?: JSONObject.NULL),
                    )
                }
            },
        )
        .put("blockers", JSONArray(blockers))
        .put("limitations", JSONArray(limitations))
        .toString(2)
}

/** Reads a user-selected APK locally, without installing it or retaining a copy. */
object ApkCompatibilityAnalyzer {
    fun analyze(context: Context, apkUri: Uri): ApkCompatibilityReport {
        val appContext = context.applicationContext
        val apkDisplayName = queryDisplayName(appContext, apkUri)
        val archiveFile = File.createTempFile("devicescope-apk-", ".apk", appContext.cacheDir)
        try {
            copySelectedApk(appContext, apkUri, archiveFile)
            val packageInfo = getArchivePackageInfo(appContext.packageManager, archiveFile.absolutePath)
                ?: throw IOException("Android no pudo leer el manifiesto. El archivo puede no ser un APK válido.")
            val applicationInfo = packageInfo.applicationInfo
                ?: throw IOException("El APK no contiene información de aplicación legible.")
            val deviceInfo = readDeviceInfo(appContext)
            val minimumSdk = applicationInfo.minSdkVersion.takeIf { it > 0 } ?: 1
            val targetSdk = applicationInfo.targetSdkVersion.takeIf { it > 0 }
            val requiredFeatures = readRequiredFeatures(
                packageManager = appContext.packageManager,
                packageInfo = packageInfo,
                deviceGlEsVersion = deviceInfo.glEsVersion,
            )

            val blockers = mutableListOf<String>()
            val limitations = mutableListOf<String>()

            if (deviceInfo.apiLevel < minimumSdk) {
                blockers += "El APK requiere Android API $minimumSdk o posterior; este dispositivo usa API ${deviceInfo.apiLevel}."
            }

            requiredFeatures.forEach { feature ->
                when (feature.supported) {
                    false -> {
                        val version = feature.minimumVersion?.let { " (versión $it)" }.orEmpty()
                        val requirement = feature.requiredGlEsVersion?.let {
                            "OpenGL ES ${formatGlEsVersion(it)}"
                        } ?: "${feature.featureName ?: feature.label}$version"
                        val available = if (feature.requiredGlEsVersion != null) {
                            deviceInfo.glEsVersion?.let { "; el dispositivo informa OpenGL ES ${formatGlEsVersion(it)}" }
                                ?: "; no se pudo leer la versión OpenGL ES del dispositivo"
                        } else {
                            ""
                        }
                        blockers += "No está disponible el requisito obligatorio $requirement$available."
                    }

                    null -> limitations += "No se pudo verificar la característica requerida '${feature.label}'; la compatibilidad con ese requisito es incierta."
                    true -> Unit
                }
            }

            val highGraphicsDemand = requiredFeatures.any { feature ->
                (feature.requiredGlEsVersion ?: 0) >= GLES_32 ||
                    feature.featureName?.let { it in HIGH_GRAPHICS_FEATURES } == true
            }
            val totalRamBytes = deviceInfo.totalRamBytes
            if (highGraphicsDemand && totalRamBytes != null && totalRamBytes < FOUR_GIB_BYTES) {
                val graphicsRequirement = requiredFeatures
                    .firstOrNull { (it.requiredGlEsVersion ?: 0) >= GLES_32 || it.featureName?.let { name -> name in HIGH_GRAPHICS_FEATURES } == true }
                    ?.let { it.requiredGlEsVersion?.let { version -> "OpenGL ES ${formatGlEsVersion(version)}" } ?: it.featureName }
                    ?: "gráficos avanzados"
                limitations += "El APK se considera gráficamente exigente por $graphicsRequirement, y el dispositivo tiene ${Formatter.formatShortFileSize(appContext, totalRamBytes)} de RAM total (menos de 4 GB). Puede funcionar con lentitud o cerrarse; es una heurística, no un requisito de RAM declarado por el APK."
            } else if (highGraphicsDemand && totalRamBytes == null) {
                limitations += "El APK declara requisitos gráficos avanzados, pero no se pudo leer la RAM total para aplicar la comprobación heurística de 4 GB."
            }

            val verdict = when {
                blockers.isNotEmpty() -> ApkCompatibilityVerdict.NOT_COMPATIBLE
                limitations.isNotEmpty() -> ApkCompatibilityVerdict.COMPATIBLE_WITH_LIMITATIONS
                else -> ApkCompatibilityVerdict.COMPATIBLE
            }

            return ApkCompatibilityReport(
                apkDisplayName = apkDisplayName,
                packageName = packageInfo.packageName.orEmpty(),
                versionName = packageInfo.versionName,
                minimumSdkVersion = minimumSdk,
                targetSdkVersion = targetSdk,
                deviceApiLevel = deviceInfo.apiLevel,
                deviceAndroidVersion = deviceInfo.androidVersion,
                deviceTotalRamBytes = totalRamBytes,
                deviceGlEsVersion = deviceInfo.glEsVersion,
                requiredFeatures = requiredFeatures,
                blockers = blockers,
                limitations = limitations,
                verdict = verdict,
                analyzedAtMillis = System.currentTimeMillis(),
            )
        } finally {
            archiveFile.delete()
        }
    }

    private fun readRequiredFeatures(
        packageManager: PackageManager,
        packageInfo: PackageInfo,
        deviceGlEsVersion: Int?,
    ): List<ApkFeatureCheck> {
        val requiredFeatures = packageInfo.reqFeatures.orEmpty().filter { feature ->
            feature.flags and FeatureInfo.FLAG_REQUIRED != 0
        }
        return buildList {
            requiredFeatures.forEach { feature ->
                val name = feature.name
                val version = feature.version.takeIf { it > 0 }
                val glEsVersion = feature.reqGlEsVersion.takeIf { it > 0 }

                if (name != null) {
                    val supported = runCatching {
                        if (version != null) {
                            packageManager.hasSystemFeature(name, version)
                        } else {
                            packageManager.hasSystemFeature(name)
                        }
                    }.getOrDefault(false)
                    add(
                        ApkFeatureCheck(
                            label = name,
                            featureName = name,
                            minimumVersion = version,
                            requiredGlEsVersion = null,
                            supported = supported,
                        ),
                    )
                }

                if (glEsVersion != null) {
                    add(
                        ApkFeatureCheck(
                            label = "OpenGL ES ${formatGlEsVersion(glEsVersion)}",
                            featureName = null,
                            minimumVersion = null,
                            requiredGlEsVersion = glEsVersion,
                            supported = deviceGlEsVersion?.let { it >= glEsVersion },
                        ),
                    )
                }

                if (name == null && glEsVersion == null) {
                    add(
                        ApkFeatureCheck(
                            label = "Característica requerida sin nombre",
                            featureName = null,
                            minimumVersion = null,
                            requiredGlEsVersion = null,
                            supported = null,
                        ),
                    )
                }
            }
        }
    }

    private fun readDeviceInfo(context: Context): DeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalRamBytes = activityManager?.let { manager ->
            runCatching {
                val memoryInfo = ActivityManager.MemoryInfo()
                manager.getMemoryInfo(memoryInfo)
                memoryInfo.totalMem.takeIf { it > 0L }
            }.getOrNull()
        }
        val glEsVersion = runCatching {
            activityManager?.deviceConfigurationInfo?.reqGlEsVersion?.takeIf { it > 0 }
        }.getOrNull()
        return DeviceInfo(
            apiLevel = Build.VERSION.SDK_INT,
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "No disponible" },
            totalRamBytes = totalRamBytes,
            glEsVersion = glEsVersion,
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return displayName?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "archivo.apk"
    }

    private fun copySelectedApk(context: Context, uri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("No se pudo abrir el APK seleccionado.")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var copiedBytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    copiedBytes += count
                    if (copiedBytes > MAX_APK_SIZE_BYTES) {
                        throw IOException("El APK supera el límite de análisis local de 512 MB.")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getArchivePackageInfo(packageManager: PackageManager, archivePath: String): PackageInfo? =
        packageManager.getPackageArchiveInfo(archivePath, PackageManager.GET_CONFIGURATIONS)

    private data class DeviceInfo(
        val apiLevel: Int,
        val androidVersion: String,
        val totalRamBytes: Long?,
        val glEsVersion: Int?,
    )

    private val HIGH_GRAPHICS_FEATURES = setOf(
        "android.hardware.vulkan.level",
        "android.hardware.vulkan.version",
        "android.hardware.opengles.aep",
    )
}

fun formatGlEsVersion(encodedVersion: Int): String {
    if (encodedVersion <= 0) return "No disponible"
    val major = (encodedVersion ushr 16) and 0xFFFF
    val minor = encodedVersion and 0xFFFF
    return "$major.$minor"
}
