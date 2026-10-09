package dev.anchildress1.wildfind

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import dev.anchildress1.wildfind.core.region.RegionKey
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The device's rough area as a whole-degree region (R7): coarse location only, rounded at once, never stored or sent
 * as coordinates.
 */
class RoughLocation(private val context: Context) {
    /** The current region, or null without permission, with location off, or after [TIMEOUT_MS]. */
    suspend fun region(): RegionKey? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = PROVIDERS.firstOrNull { granted && manager.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { done ->
                val signal = CancellationSignal()
                done.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    done.resume(location?.let { RegionKey.from(it.latitude, it.longitude) })
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L

        // Fused first where it exists (Android 12+); with only coarse permission every provider returns a fuzzed fix.
        val PROVIDERS = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
    }
}
