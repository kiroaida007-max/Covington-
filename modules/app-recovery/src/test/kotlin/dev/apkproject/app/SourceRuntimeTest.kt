package dev.apkproject.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The platform's documented behaviour per Android version, applied to an app's minSdk, targetSdk and permissions. */
class SourceRuntimeTest {
    private fun app(min: Int, target: Int, vararg permissions: String, queries: Int = 0) =
        SourceRuntime.App(min, target, permissions.map { if ('.' in it) it else "android.permission.$it" }.toSet(), queries)

    @Test
    fun `device identifiers are gone from Android 10 for apps that are not privileged`() {
        val imei = assertNotNull(SourceRuntime.verdict("android.telephony.TelephonyManager.getImei", "device-id", app(21, 33, "READ_PHONE_STATE")))
        assertEquals(28, imei.lastApi)
        assertTrue(imei.possible(app(21, 33)))
        assertFalse(imei.possible(app(29, 33)))
        assertTrue(imei.describe(app(29, 33)).startsWith("No data on any Android version the app supports (minSdk 29)"))
        assertNull(SourceRuntime.verdict("android.telephony.TelephonyManager.getImei", "device-id", app(29, 33, "READ_PRIVILEGED_PHONE_STATE")))
        assertEquals(28, SourceRuntime.verdict("android.os.Build.getSerial", "device-id", app(26, 28))?.lastApi)
        // Build.SERIAL: "unknown" for every app targeting API 26 or later, whatever the device.
        assertFalse(SourceRuntime.verdict("android.os.Build.SERIAL", "device-id", app(16, 26))!!.possible(app(16, 26)))
        assertTrue(SourceRuntime.verdict("android.os.Build.SERIAL", "device-id", app(16, 25))!!.possible(app(16, 25)))
    }

    @Test
    fun `hardware addresses`() {
        assertEquals(22, SourceRuntime.verdict("android.net.wifi.WifiInfo.getMacAddress", "device-id", app(21, 34))?.lastApi)
        assertFalse(SourceRuntime.verdict("android.bluetooth.BluetoothAdapter.getAddress", "device-id", app(23, 34))!!.possible(app(23, 34)))
        assertEquals(29, SourceRuntime.verdict("java.net.NetworkInterface.getHardwareAddress", "device-id", app(21, 30))?.lastApi)
        assertNull(SourceRuntime.verdict("java.net.NetworkInterface.getHardwareAddress", "device-id", app(21, 29))?.lastApi)
    }

    @Test
    fun `advertising ID needs the AD_ID permission when targeting Android 13`() {
        val api = "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info.getId"
        assertEquals(32, SourceRuntime.verdict(api, "advertising-id", app(21, 33))?.lastApi)
        assertFalse(SourceRuntime.verdict(api, "advertising-id", app(33, 34))!!.possible(app(33, 34)))
        assertNull(SourceRuntime.verdict(api, "advertising-id", app(33, 34, "com.google.android.gms.permission.AD_ID"))?.lastApi)
        assertNull(SourceRuntime.verdict(api, "advertising-id", app(21, 32))?.lastApi)
    }

    @Test
    fun `Wi-Fi names and cell information depend on the location permission`() {
        val ssid = "android.net.wifi.WifiInfo.getSSID"
        assertEquals(27, SourceRuntime.verdict(ssid, "wifi", app(21, 34))?.lastApi)
        assertEquals(28, SourceRuntime.verdict(ssid, "wifi", app(21, 29, "ACCESS_COARSE_LOCATION"))?.lastApi)
        assertNull(SourceRuntime.verdict(ssid, "wifi", app(21, 28, "ACCESS_COARSE_LOCATION"))?.lastApi)
        assertNull(SourceRuntime.verdict(ssid, "wifi", app(21, 34, "ACCESS_FINE_LOCATION"))?.lastApi)
        assertEquals(22, SourceRuntime.verdict("android.net.wifi.WifiManager.getScanResults", "wifi", app(21, 34))?.lastApi)
        assertEquals(28, SourceRuntime.verdict("android.telephony.TelephonyManager.getAllCellInfo", "location", app(21, 30, "ACCESS_COARSE_LOCATION"))?.lastApi)
        assertTrue("approximate" in SourceRuntime.verdict("android.location.LocationManager.getLastKnownLocation", "location", app(21, 30, "ACCESS_COARSE_LOCATION"))!!.note)
        assertNull(SourceRuntime.verdict("android.location.LocationManager.getLastKnownLocation", "location", app(21, 30, "ACCESS_FINE_LOCATION")))
    }

    @Test
    fun `phone number, installed apps, accounts and the clipboard`() {
        assertEquals(29, SourceRuntime.verdict("android.telephony.TelephonyManager.getLine1Number", "phone-number", app(21, 30, "READ_PHONE_STATE"))?.lastApi)
        assertNull(SourceRuntime.verdict("android.telephony.TelephonyManager.getLine1Number", "phone-number", app(21, 30, "READ_PHONE_NUMBERS")))
        val packages = assertNotNull(SourceRuntime.verdict("android.content.pm.PackageManager.getInstalledPackages", "installed-apps", app(21, 30, queries = 2)))
        assertNull(packages.lastApi)
        assertTrue("<queries> names (2)" in packages.note)
        assertTrue("QUERY_ALL_PACKAGES" in SourceRuntime.verdict("android.content.pm.PackageManager.getInstalledPackages", "installed-apps", app(21, 30, "QUERY_ALL_PACKAGES"))!!.note)
        assertNull(SourceRuntime.verdict("android.content.pm.PackageManager.getInstalledPackages", "installed-apps", app(21, 29)))
        assertNull(SourceRuntime.verdict("android.accounts.AccountManager.getAccounts", "accounts", app(21, 30))?.lastApi)
        assertNull(SourceRuntime.verdict("android.content.ClipboardManager.getPrimaryClip", "clipboard", app(21, 30))?.lastApi)
    }

    @Test
    fun `defaults follow the platform when uses-sdk is missing`() {
        val m = AppDiff.Manifest.parse("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"a.b\"><uses-sdk android:minSdkVersion=\"24\"/></manifest>")
        val app = assertNotNull(SourceRuntime.App.of(m))
        assertEquals(24, app.minSdk)
        assertEquals(24, app.targetSdk)
        assertNull(SourceRuntime.App.of(null))
    }
}
