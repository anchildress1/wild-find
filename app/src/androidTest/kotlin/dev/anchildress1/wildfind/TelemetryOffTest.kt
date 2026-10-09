package dev.anchildress1.wildfind

import android.Manifest
import android.content.pm.PackageManager
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** R7: ONNX Runtime's bundled telemetry stays off, so iNat remains the app's only network peer. */
@RunWith(AndroidJUnit4::class)
class TelemetryOffTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theAppTurnsOrtTelemetryOffBeforeAnyModelLoads() {
        assertEquals("1", Os.getenv("ORT_DISABLE_TELEMETRY"))
    }

    @Test
    fun theTelemetryNetworkStatePermissionIsStripped() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()

        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in requested)
    }
}
