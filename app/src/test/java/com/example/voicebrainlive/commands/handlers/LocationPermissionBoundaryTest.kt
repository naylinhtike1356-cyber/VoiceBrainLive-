package com.example.voicebrainlive.commands.handlers

import android.Manifest
import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Permission-denial fixtures never initialize Google location provider or invoke geofencing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationPermissionBoundaryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class PermissionContext(app: Application, private val fine: Boolean, private val coarse: Boolean = false) : ContextWrapper(app) {
        var fineChecks = 0
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> {
                fineChecks++
                if (fine) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
            Manifest.permission.ACCESS_COARSE_LOCATION -> if (coarse) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            else -> PackageManager.PERMISSION_DENIED
        }
    }

    private fun providerLazy(handler: LocationHandler): Lazy<*> = LocationHandler::class.java.declaredFields
        .single { it.name.startsWith("fusedLocationClient") && it.name.endsWith("delegate") }
        .apply { isAccessible = true }.get(handler) as Lazy<*>

    /** Exercise the private API boundary directly, bypassing the earlier caller check. */
    private suspend fun fetchAtBoundary(handler: LocationHandler): Location? = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = LocationHandler::class.java.getDeclaredMethod("fetchLastLocation", Continuation::class.java)
            .apply { isAccessible = true }
        try { method.invoke(handler, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    @Test fun deniedAtApiBoundaryReturnsNullWithoutInitializingProvider() = runBlocking {
        val context = PermissionContext(app, fine = false)
        val handler = LocationHandler(context)
        assertNull(fetchAtBoundary(handler))
        assertEquals(1, context.fineChecks)
        assertFalse(providerLazy(handler).isInitialized())
    }

    @Test fun coarseOnlyPreservesExistingFinePermissionPolicyWithoutProviderAccess() = runBlocking {
        val handler = LocationHandler(PermissionContext(app, fine = false, coarse = true))
        assertNull(fetchAtBoundary(handler))
        assertFalse(providerLazy(handler).isInitialized())
    }

    @Test fun cancelledContextPropagatesCancellationWithoutProviderAccess() = runBlocking {
        val handler = LocationHandler(PermissionContext(app, fine = false))
        val cancelled = Job().apply { cancel() }
        try {
            withContext(cancelled) { fetchAtBoundary(handler) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(providerLazy(handler).isInitialized())
        }
    }

    @Test fun denialPreservesExistingHomeOfficeBytesAndDoesNotRegisterGeofences() = runBlocking {
        val file = File(app.filesDir, "location_triggers.json")
        val original = "{\"home_lat\":37.5,\"home_lng\":127.0,\"office_lat\":37.6,\"office_lng\":127.1,\"label\":\"မြန်မာ 한국어\"}".toByteArray(Charsets.UTF_8)
        file.writeBytes(original)
        val handler = LocationHandler(PermissionContext(app, fine = false))
        assertNotNull(handler.execute("set_home_location", null, null))
        assertNotNull(handler.execute("set_office_location", null, null))
        assertArrayEquals(original, file.readBytes())
        assertFalse(providerLazy(handler).isInitialized())
        val geofence = LocationHandler::class.java.declaredFields
            .single { it.name.startsWith("geofenceHelper") && it.name.endsWith("delegate") }
            .apply { isAccessible = true }.get(handler) as Lazy<*>
        assertFalse(geofence.isInitialized())
    }
}
