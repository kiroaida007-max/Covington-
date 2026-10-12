package dev.apkproject.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The table generated from the SDK's RequiresPermission annotations (scripts/privacy/platform_permissions.py). */
class PlatformPermissionsTest {
    @Test
    fun `requirements per API from the SDK annotations`() {
        assertEquals(listOf("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"), PlatformPermissions.permissions("android.location.LocationManager.getLastKnownLocation"))
        assertEquals(listOf("READ_PRIVILEGED_PHONE_STATE"), PlatformPermissions.permissions("android.telephony.TelephonyManager.getImei"))
        assertEquals(listOf("READ_PHONE_STATE"), PlatformPermissions.permissions("android.telephony.TelephonyManager.getVoiceMailNumber"))
        // Up to API 22 only: newer levels run the call without it, so it never makes a source "cannot run".
        assertTrue(PlatformPermissions.knows("android.accounts.AccountManager.getAccounts"))
        assertEquals(emptyList(), PlatformPermissions.permissions("android.accounts.AccountManager.getAccounts"))
        // Conditional (the network name needs location; the call works without it).
        assertEquals(emptyList(), PlatformPermissions.permissions("android.net.wifi.WifiManager.getConnectionInfo"))
        // An allOf of every overload: any one is taken as enough.
        assertEquals(listOf("ACCESS_FINE_LOCATION", "ACCESS_WIFI_STATE"), PlatformPermissions.permissions("android.net.wifi.WifiManager.getConfiguredNetworks"))
        assertEquals(emptyList(), PlatformPermissions.permissions("android.location.Geocoder.getFromLocation"))
    }
}
