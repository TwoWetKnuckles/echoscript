package org.echoscript.app

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Record the actual runtime; a configured AVD alone is not evidence that it was applied. */
class ResourceProfileTest {
    @Test fun actualManagedHeapMeetsTheDeclaredTestProfile() {
        val arguments = InstrumentationRegistry.getArguments()
        val expectedMiB = arguments.getString("expectedManagedHeapMiB")?.toLong() ?: 32L
        val actual = Runtime.getRuntime().maxMemory()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = checkNotNull(context.getSystemService(ActivityManager::class.java))
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val value = JSONObject().put("scope", "Declared emulator profile, not physical-device acceptance")
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("expectedManagedHeapMiB", expectedMiB)
            .put("actualMaxHeapBytes", actual).put("memoryClassMiB", manager.memoryClass)
            .put("totalDeviceMemoryBytes", memory.totalMem).put("availableDeviceMemoryBytes", memory.availMem)
        arguments.getString("additionalTestOutputDir")?.let { folder ->
            File(folder).mkdirs(); File(folder, "synthetic-resource-profile.json").writeText(value.toString(2))
        }
        assertTrue("The CI phone has less managed heap than its declared profile: $value", actual >= expectedMiB * 1024 * 1024)
    }
}
