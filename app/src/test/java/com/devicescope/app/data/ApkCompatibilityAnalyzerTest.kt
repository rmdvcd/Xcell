package com.devicescope.app.data

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class ApkCompatibilityAnalyzerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `apk requiring a newer api is not compatible`() {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val activityManager = mockk<ActivityManager>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)
        val cacheDir = temporaryFolder.newFolder("cache")

        every { context.applicationContext } returns context
        every { context.cacheDir } returns cacheDir
        every { context.contentResolver } returns resolver
        every { context.packageManager } returns packageManager
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { uri.lastPathSegment } returns "incompatible.apk"
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))

        val applicationInfo = ApplicationInfo().apply {
            minSdkVersion = Int.MAX_VALUE
            targetSdkVersion = 35
        }
        val packageInfo = PackageInfo().apply {
            packageName = "com.example.incompatible"
            versionName = "1.0"
            this.applicationInfo = applicationInfo
            reqFeatures = emptyArray()
        }
        every {
            packageManager.getPackageArchiveInfo(any<String>(), PackageManager.GET_CONFIGURATIONS)
        } returns packageInfo

        val report = ApkCompatibilityAnalyzer.analyze(context, uri)

        assertEquals(ApkCompatibilityVerdict.NOT_COMPATIBLE, report.verdict)
        assertTrue(report.verdict.label.contains("No compatible"))
        assertTrue(report.blockers.any { it.contains("API ${Int.MAX_VALUE}") })
        assertTrue(cacheDir.listFiles().isNullOrEmpty())
    }
}
