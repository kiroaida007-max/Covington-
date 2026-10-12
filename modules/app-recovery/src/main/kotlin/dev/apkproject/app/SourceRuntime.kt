package dev.apkproject.app

/**
 * What a personal-data API gives an app at run time, by Android version: the platform's documented
 * behaviour changes applied to the app's minSdk, targetSdk and permissions. Nothing is run; this is
 * the static counterpart of watching the app on a device of every version it supports, so what it
 * says is the platform's documented behaviour, not an observation.
 *
 * Sources (developer.android.com, the "Behavior changes" and privacy pages of each release, and the
 * API reference of each method):
 * - Android 6.0 (API 23): `WifiInfo.getMacAddress` and `BluetoothAdapter.getAddress` return the
 *   constant 02:00:00:00:00:00; Wi-Fi scan results need a location permission.
 * - Android 8.0 (API 26): `Build.SERIAL` is "unknown" for apps targeting API 26 or later; ANDROID_ID
 *   is scoped to the app signing key and user; GET_ACCOUNTS no longer reveals other apps' accounts.
 * - Android 9 (API 28): the Wi-Fi network name and BSSID need a location permission.
 * - Android 10 (API 29): non-resettable identifiers (`getImei`, `getDeviceId`, `getMeid`,
 *   `getSubscriberId`, `getSimSerialNumber`, `Build.getSerial`) need READ_PRIVILEGED_PHONE_STATE,
 *   which only privileged apps get; the clipboard is readable only by the focused app or the default
 *   input method; apps targeting API 29 or later need ACCESS_FINE_LOCATION for Wi-Fi scans, the
 *   network name and cell information; Wi-Fi addresses are randomised per network.
 * - Android 11 (API 30), for apps targeting API 30 or later: only the packages the manifest queries
 *   are visible (unless QUERY_ALL_PACKAGES), `NetworkInterface.getHardwareAddress` returns null, and
 *   the phone number needs READ_PHONE_NUMBERS (or READ_SMS).
 * - Google Play services: apps targeting API 33 or later without
 *   `com.google.android.gms.permission.AD_ID` get an advertising ID of zeros on Android 13 and later;
 *   a user who deletes the ID (Android 12 and later) gives every app zeros.
 */
object SourceRuntime {
    /** The app as the platform sees it; without `uses-sdk`, minSdk is 1 and targetSdk is minSdk. */
    class App(val minSdk: Int, val targetSdk: Int, val permissions: Set<String>, val queries: Int) {
        fun has(p: String) = "android.permission.$p" in permissions

        companion object {
            fun of(m: AppDiff.Manifest?): App? = m?.let { App(it.minSdk ?: 1, it.targetSdk ?: it.minSdk ?: 1, it.permissions, it.queries.size) }
        }
    }

    /**
     * [lastApi]: the newest API level on which the call returns the data (null: every level the app
     * runs on). [note]: what the platform does.
     */
    class Verdict(val lastApi: Int?, val note: String) {
        /** Whether some device the app supports returns the data. */
        fun possible(app: App) = lastApi == null || lastApi >= app.minSdk

        fun describe(app: App): String = if (possible(app)) note else "No data on any Android version the app supports (minSdk ${app.minSdk}): $note"
    }

    private val HARDWARE_IDS = setOf("getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber")
    private const val MAC_CONSTANT = "Android 6.0 (API 23) and later return the constant 02:00:00:00:00:00; data on Android 5.1 and older"

    /** The run-time behaviour of the source [api] (as the privacy map names it) carrying [data], or null when the platform does not limit it. */
    fun verdict(api: String, data: String, app: App): Verdict? {
        val cls = api.substringBeforeLast('.')
        val name = api.substringAfterLast('.')
        return when {
            cls == "android.telephony.TelephonyManager" && name in HARDWARE_IDS || api == "android.os.Build.getSerial" ->
                if (app.has("READ_PRIVILEGED_PHONE_STATE")) null
                else Verdict(28, "Android 10 (API 29) and later give device identifiers only to privileged system apps; data on Android 9 and older")
            api == "android.os.Build.SERIAL" ->
                if (app.targetSdk >= 26) Verdict(0, "Build.SERIAL is \"unknown\" for apps targeting Android 8.0 (API 26) or later (targetSdk ${app.targetSdk})")
                else Verdict(28, "\"unknown\" on Android 10 (API 29) and later; data on Android 9 and older")
            api == "android.net.wifi.WifiInfo.getMacAddress" -> Verdict(22, MAC_CONSTANT)
            api == "android.bluetooth.BluetoothAdapter.getAddress" -> if (app.has("LOCAL_MAC_ADDRESS")) null else Verdict(22, MAC_CONSTANT)
            api == "java.net.NetworkInterface.getHardwareAddress" ->
                if (app.targetSdk >= 30) Verdict(29, "null for apps targeting Android 11 (API 30) or later (targetSdk ${app.targetSdk}); data on Android 10 and older, where Wi-Fi addresses are randomised per network")
                else Verdict(null, "Android 10 (API 29) and later randomise the Wi-Fi address per network by default")
            data == "advertising-id" && cls.startsWith("com.google.android.gms.ads.identifier.AdvertisingIdClient") ->
                if (app.targetSdk >= 33 && "com.google.android.gms.permission.AD_ID" !in app.permissions)
                    Verdict(32, "apps targeting Android 13 (API 33) or later without the com.google.android.gms.permission.AD_ID permission get zeros on Android 13 and later; data on Android 12L and older")
                else Verdict(null, "zeros once the user deletes the advertising ID (Android 12 and later)")
            cls == "android.accounts.AccountManager" ->
                Verdict(null, "Android 8.0 (API 26) and later return only the accounts the user let the app see (its own, or chosen with newChooseAccountIntent)")
            data == "installed-apps" && cls == "android.content.pm.PackageManager" -> when {
                app.has("QUERY_ALL_PACKAGES") -> Verdict(null, "QUERY_ALL_PACKAGES: every installed package (Google Play allows this permission only where the app's core purpose needs it)")
                app.targetSdk >= 30 -> Verdict(null, "on Android 11 (API 30) and later (targetSdk ${app.targetSdk}), only the packages the manifest's <queries> names (${app.queries}) and those that interact with the app")
                else -> null
            }
            data == "wifi" -> wifi(name, app)
            cls == "android.telephony.TelephonyManager" && (name == "getAllCellInfo" || name == "getCellLocation") ->
                if (!app.has("ACCESS_FINE_LOCATION") && app.targetSdk >= 29) Verdict(28, "apps targeting Android 10 (API 29) or later need ACCESS_FINE_LOCATION for cell information; data on Android 9 and older") else null
            data == "location" && !app.has("ACCESS_FINE_LOCATION") && app.has("ACCESS_COARSE_LOCATION") -> Verdict(null, "approximate location only (ACCESS_COARSE_LOCATION without ACCESS_FINE_LOCATION)")
            name == "getLine1Number" || api == "android.telephony.SubscriptionManager.getPhoneNumber" || api == "android.telephony.SubscriptionInfo.getNumber" ->
                if (app.targetSdk >= 30 && !app.has("READ_PHONE_NUMBERS") && !app.has("READ_SMS"))
                    Verdict(29, "apps targeting Android 11 (API 30) or later need READ_PHONE_NUMBERS (or READ_SMS); with READ_PHONE_STATE alone, data on Android 10 and older")
                else null
            data == "clipboard" -> Verdict(null, "Android 10 (API 29) and later: only while the app has focus or is the default input method")
            api == "Settings.Secure.getString(ANDROID_ID)" -> Verdict(null, "Android 8.0 (API 26) and later: a different value for each app signing key and user; it survives reinstalling, not a factory reset")
            else -> null
        }
    }

    private fun wifi(name: String, app: App): Verdict? {
        val scan = name == "getScanResults"
        val what = if (scan) "scan results" else "the network name and BSSID"
        return when {
            app.has("ACCESS_FINE_LOCATION") -> Verdict(null, "$what only while location services are on (Android 9 and later)")
            app.has("ACCESS_COARSE_LOCATION") && app.targetSdk >= 29 ->
                Verdict(28, "apps targeting Android 10 (API 29) or later need ACCESS_FINE_LOCATION for $what; data on Android 9 and older")
            app.has("ACCESS_COARSE_LOCATION") -> Verdict(null, "$what only while location services are on (Android 9 and later)")
            scan -> Verdict(22, "Android 6.0 (API 23) and later return scan results only with a location permission; data on Android 5.1 and older")
            else -> Verdict(27, "Android 9 (API 28) and later hide the network name and BSSID without a location permission; data on Android 8.1 and older")
        }
    }
}
